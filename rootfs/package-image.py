"""Verify exported runtime bytes, then name and split the RootFS for Releases."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tarfile

SOURCE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SOURCE / 'scripts/ci'))
from release import sha256
from rootfs_target import resolve, archive_name
from versions import read_versions, version_suffix

RUNTIME = {
    'usr/lib/anland/Xwayland': 'Xwayland',
    'usr/bin/anland-miniwm': 'anland-miniwm',
    'usr/bin/anland-session': 'anland-session',
    'usr/bin/xfdesktop': 'xfdesktop',
}
METADATA = 'usr/share/anland/rootfs-components.json'


def verify_archive(archive):
    found, manifest = {}, None
    with tarfile.open(archive, mode='r|xz') as tar:
        for member in tar:
            name = member.name.removeprefix('./').lstrip('/')
            if name not in {*RUNTIME, METADATA}:
                continue
            if name in found or not member.isfile():
                raise ValueError(f'Duplicate or nonregular runtime entry: {name}')
            stream = tar.extractfile(member)
            if name == METADATA:
                if member.size > 1024 * 1024:
                    raise ValueError('Unexpectedly large image manifest')
                manifest = json.load(stream)
                found[name] = True
            else:
                digest = hashlib.sha256()
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
                found[name] = digest.hexdigest()
    if set(found) != {*RUNTIME, METADATA}:
        raise ValueError('Exported RootFS is missing patched runtime files')
    for path, name in RUNTIME.items():
        if found[path] != manifest['patched_files'][name]:
            raise ValueError(f'Exported runtime differs from build input: {name}')
    return manifest


def package(builder, directory):
    archives = list(Path(builder).glob('*.tar.xz'))
    if len(archives) != 1:
        raise ValueError('Expected exactly one exported RootFS archive')
    manifest = verify_archive(archives[0])
    versions = read_versions()
    version = versions['ROOTFS_VERSION'] + version_suffix()
    if manifest['rootfs_version'] != version:
        raise ValueError('Exported image version differs from version.properties')
    # Check provenance against the exact inputs injected before Docker export.
    expected = json.loads((Path(builder) / 'anland-overrides/rootfs-inputs.json').read_text())
    inputs = {k: v for k, v in manifest.items() if k not in ('patched_files', 'xfdesktop_files')}
    if inputs != expected:
        raise ValueError('Exported image has another build manifest')
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    target, config = resolve(os.environ.get('ROOTFS_TARGET'))
    if manifest['target'] != target or manifest['target_config'] != config:
        raise ValueError('Exported image differs from the selected RootFS target')
    name = archive_name(versions, version_suffix(), target)
    original_hash = sha256(archives[0])
    (directory / 'ROOTFS-SHA256SUMS').write_text(f'{original_hash}  {name}\n', encoding='utf-8')
    (directory / 'rootfs-components.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    if archives[0].stat().st_size < 2_000_000_000:
        shutil.move(str(archives[0]), directory / name)
    else:
        with archives[0].open('rb') as source:
            index = 0
            while source.tell() < archives[0].stat().st_size:
                with (directory / f'{name}.part-{index:03}').open('wb') as output:
                    remaining = 1900 * 1024 * 1024
                    while remaining:
                        chunk = source.read(min(remaining, 1024 * 1024))
                        if not chunk:
                            break
                        output.write(chunk)
                        remaining -= len(chunk)
                index += 1
    print(f'Verified and staged RootFS {version}')


if __name__ == '__main__':
    package(*sys.argv[1:])
