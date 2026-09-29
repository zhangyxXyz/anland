"""Plan a build and collect independently uploaded components into one draft."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

from versions import read_versions

COMPONENTS = ('shell', 'wayland', 'rootfs')
EXPECTED = {
    'shell': {'anland-shell.apk', 'anland-shell-tests.apk'},
    'wayland': {'anland-wayland.apk', 'anland-wayland-tests.apk',
                'anland-awllib.aar', 'waylandbridge', 'anland-awl.zip'},
}


def plan(env):
    versions = read_versions()
    event, ref = env['GITHUB_EVENT_NAME'], env['GITHUB_REF']
    sha, run_id = env['GITHUB_SHA'], env['GITHUB_RUN_ID']
    if not re.fullmatch(r'[0-9a-f]{40}', sha) or not run_id.isdigit():
        raise ValueError('Invalid source or run ID')
    if event == 'push':
        if ref != 'refs/tags/v' + versions['RELEASE_VERSION']:
            raise ValueError('Git tag must equal v + RELEASE_VERSION in version.properties')
        selected = list(COMPONENTS)
        tag, suffix = ref.removeprefix('refs/tags/'), ''
    elif event == 'workflow_dispatch':
        selected = [c for c in COMPONENTS if env.get('BUILD_' + c.upper()) == 'true']
        if not selected:
            raise ValueError('Select at least one component')
        number = env['GITHUB_RUN_NUMBER']
        if not number.isdigit():
            raise ValueError('Invalid run number')
        tag, suffix = f'dev-{sha[:8]}-{run_id}', f'-dev.{number}+{sha[:8]}'
    else:
        raise ValueError('Only version tags and manual builds are supported')
    return dict(tag=tag, suffix=suffix, selected=selected, source=sha,
                run_id=run_id, versions=versions)


def current():
    env = dict(os.environ)
    if env.get('BUILD_PLAN'):
        supplied = json.loads(env['BUILD_PLAN'])
        env.update({'BUILD_' + c.upper(): str(c in supplied['selected']).lower()
                    for c in COMPONENTS})
        result = plan(env)
        if supplied != result:
            raise ValueError('Called workflow plan differs from this run/source/version configuration')
        return result
    return plan(env)


def gh(*args):
    return subprocess.check_output(['gh', *args], text=True, encoding='utf-8')


def view(tag):
    return json.loads(gh('release', 'view', tag, '--json', 'isDraft,body,assets'))


def marker(build):
    return f"<!-- anland-run:{build['run_id']} source:{build['source']} -->"


def require_owned_draft(build, release):
    if not release['isDraft'] or marker(build) not in release['body']:
        raise ValueError('Refusing to change a public release or a draft owned by another run')


def notes(build, status):
    repo = os.environ.get('GITHUB_REPOSITORY', '')
    server = os.environ.get('GITHUB_SERVER_URL', 'https://github.com')
    rows = '\n'.join(f'| `{k}` | `{v}` |' for k, v in build['versions'].items())
    return (f"{marker(build)}\n\nStatus: **{status}**\n\n"
            f"Source: `{build['source']}`\n\n"
            f"Components: {', '.join(build['selected'])}\n\n"
            f"Development suffix: `{build['suffix'] or '(none)'}`\n\n"
            f"[Build log]({server}/{repo}/actions/runs/{build['run_id']})\n\n"
            f"| Version setting | Value |\n|---|---|\n{rows}\n\n"
            'Only selected components belong to this build. Test APKs are compiled, '
            'not run on a device. Device validation is separate.\n\n'
            'Verify downloaded files with `sha256sum -c SHA256SUMS`. '
            'For a split RootFS, concatenate `.part-000`, `.part-001`, ... in order '
            'and verify the reconstructed archive with `ROOTFS-SHA256SUMS`.\n')


def edit_notes(build, status):
    with tempfile.TemporaryDirectory() as temp:
        path = Path(temp) / 'notes.md'
        path.write_text(notes(build, status), encoding='utf-8')
        gh('release', 'edit', build['tag'], '--draft', '--notes-file', str(path))


def prepare(build):
    # Listing distinguishes a missing tag from authentication/network errors.
    releases = json.loads(gh('api', '--paginate', '--slurp',
                            f"repos/{os.environ['GITHUB_REPOSITORY']}/releases?per_page=100"))
    exists = any(r['tag_name'] == build['tag'] for page in releases for r in page)
    if exists:
        require_owned_draft(build, view(build['tag']))
        edit_notes(build, 'Building (retry)')
    else:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'notes.md'
            path.write_text(notes(build, 'Building'), encoding='utf-8')
            gh('release', 'create', build['tag'], '--draft', '--target', build['source'],
               '--title', f"Anland {build['tag']}", '--notes-file', str(path))


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def validate_names(component, names, versions, suffix):
    if component in EXPECTED:
        if names != EXPECTED[component]:
            raise ValueError(f'Unexpected {component} assets: {names}')
        return
    archive = f"anland-rootfs-debian13-arm64-{versions['ROOTFS_VERSION']}{suffix}.tar.xz"
    required = {'ROOTFS-SHA256SUMS', 'rootfs-components.json'}
    images = names - required
    if not required <= names:
        raise ValueError('Missing RootFS provenance or archive checksum')
    if images == {archive}:
        return
    if not images or images != {f'{archive}.part-{i:03}' for i in range(len(images))}:
        raise ValueError('Missing or noncontiguous RootFS parts')


def upload(component, directory, build):
    if component not in build['selected']:
        raise ValueError('Cannot upload an unselected component')
    require_owned_draft(build, view(build['tag']))
    directory = Path(directory)
    # Control files are regenerated on retries, not treated as payload.
    manifest_name, sums_name = f'{component}-manifest.json', f'{component}-SHA256SUMS'
    files = sorted(p for p in directory.iterdir()
                   if p.is_file() and p.name not in {manifest_name, sums_name})
    validate_names(component, {p.name for p in files}, build['versions'], build['suffix'])
    manifest = dict(component=component, run_id=build['run_id'], source=build['source'],
                    versions=build['versions'], suffix=build['suffix'], files=[])
    for path in files:
        manifest['files'].append(dict(name=path.name, size=path.stat().st_size, sha256=sha256(path)))
    sums = directory / sums_name
    sums.write_text(''.join(f"{f['sha256']}  {f['name']}\n" for f in manifest['files']), encoding='utf-8')
    manifest['files'].append(dict(name=sums.name, size=sums.stat().st_size, sha256=sha256(sums)))
    manifest_path = directory / manifest_name
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    # Manifest is the completion marker and must be uploaded last.
    gh('release', 'upload', build['tag'], *map(str, files), str(sums), '--clobber')
    gh('release', 'upload', build['tag'], str(manifest_path), '--clobber')


def verify_manifest(manifest, component, build, assets):
    for key in ('run_id', 'source', 'versions', 'suffix'):
        if manifest.get(key) != build[key]:
            raise ValueError(f'{component} manifest has a different {key}')
    if manifest.get('component') != component:
        raise ValueError('Wrong component manifest')
    files = manifest['files']
    names = [f['name'] for f in files]
    if len(names) != len(set(names)) or f'{component}-SHA256SUMS' not in names:
        raise ValueError('Duplicate assets or missing component checksum file')
    validate_names(component, set(names) - {f'{component}-SHA256SUMS'},
                   build['versions'], build['suffix'])
    for file in files:
        asset = assets.get(file['name'], {})
        if (asset.get('size') != file['size'] or
                asset.get('digest') != 'sha256:' + file['sha256']):
            raise ValueError(f"Missing or corrupt Release asset: {file['name']}")
    return files


def finalize(build, results):
    release = view(build['tag'])
    require_owned_draft(build, release)
    failed = [c for c in build['selected'] if results.get(c, {}).get('result') != 'success']
    if failed:
        edit_notes(build, 'Incomplete: ' + ', '.join(failed))
        raise ValueError('Selected components failed or were cancelled: ' + ', '.join(failed))
    try:
        assets = {a['name']: a for a in release['assets']}
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            files, manifests = [], {}
            for component in build['selected']:
                name = f'{component}-manifest.json'
                gh('release', 'download', build['tag'], '--pattern', name, '--dir', temp)
                path = directory / name
                manifest = json.loads(path.read_text(encoding='utf-8'))
                files.extend(verify_manifest(manifest, component, build, assets))
                manifests[component] = manifest
                files.append(dict(name=name, size=path.stat().st_size, sha256=sha256(path)))
            expected = {f['name'] for f in files} | {'build-manifest.json', 'SHA256SUMS'}
            if set(assets) - expected:
                raise ValueError('Draft contains unexpected/stale assets; remove them before retrying')
            path = directory / 'build-manifest.json'
            path.write_text(json.dumps(dict(**build, components=manifests), indent=2) + '\n', encoding='utf-8')
            files.append(dict(name=path.name, sha256=sha256(path)))
            sums = directory / 'SHA256SUMS'
            sums.write_text(''.join(f"{f['sha256']}  {f['name']}\n" for f in files), encoding='utf-8')
            gh('release', 'upload', build['tag'], str(path), str(sums), '--clobber')
        edit_notes(build, 'Complete — draft, ready for review')
    except Exception:
        edit_notes(build, 'Incomplete: final asset verification failed')
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('plan', 'prepare', 'upload', 'finalize'))
    parser.add_argument('--component', choices=COMPONENTS)
    parser.add_argument('--directory', default='release')
    args = parser.parse_args()
    build = current()
    if args.command == 'plan':
        lines = [f"tag={build['tag']}", f"suffix={build['suffix']}",
                 'build_plan=' + json.dumps(build, separators=(',', ':'))]
        lines += [f"{c}={str(c in build['selected']).lower()}" for c in COMPONENTS]
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
                output.write('\n'.join(lines) + '\n')
        print(json.dumps(build, indent=2))
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
                summary.write(notes(build, 'Planned'))
    elif args.command == 'prepare':
        prepare(build)
    elif args.command == 'upload':
        if not args.component:
            parser.error('--component is required for upload')
        upload(args.component, args.directory, build)
    else:
        finalize(build, json.loads(os.environ['BUILD_RESULTS']))
