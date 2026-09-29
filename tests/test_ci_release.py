import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts/ci'))
import release
import versions


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


image = module('package_image', ROOT / 'rootfs/package-image.py')


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.env = dict(GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/dev',
                        GITHUB_SHA='a' * 40, GITHUB_RUN_ID='123', GITHUB_RUN_NUMBER='12',
                        BUILD_SHELL='true', BUILD_WAYLAND='false', BUILD_ROOTFS='false')

    def test_manual_selection_and_stable_retry(self):
        first = release.plan(self.env)
        self.assertEqual(first['selected'], ['shell'])
        self.assertEqual(first['suffix'], '-dev.12+aaaaaaaa')
        self.env['GITHUB_RUN_ATTEMPT'] = '2'
        self.assertEqual(first, release.plan(self.env))

    def test_version_tag_selects_every_component(self):
        self.env.update(GITHUB_EVENT_NAME='push',
                        GITHUB_REF='refs/tags/v' + versions.read_versions()['RELEASE_VERSION'])
        result = release.plan(self.env)
        self.assertEqual(result['selected'], list(release.COMPONENTS))
        self.assertEqual(result['suffix'], '')
        self.env['ROOTFS_TARGET'] = 'Arch'
        self.assertEqual(release.plan(self.env)['rootfs_target'], 'Debian-13')

    def test_manual_target_survives_called_workflow_and_rejects_wrong_assets(self):
        self.env.update(BUILD_ROOTFS='true', ROOTFS_TARGET='Fedora-44')
        build = release.plan(self.env)
        with patch.dict(os.environ, dict(self.env, BUILD_PLAN=json.dumps(build)), clear=True):
            self.assertEqual(release.current(), build)
        base = release.archive_name(build['versions'], build['suffix'], 'Fedora-44')
        names = {'ROOTFS-SHA256SUMS', 'rootfs-components.json', base}
        release.validate_names('rootfs', names, build['versions'], build['suffix'], 'Fedora-44')
        with self.assertRaises(ValueError):
            release.validate_names('rootfs', names, build['versions'], build['suffix'], 'Debian-13')
        manifest = dict(component='rootfs', files=[], rootfs_target='Debian-13',
                        **{k: build[k] for k in ('run_id', 'source', 'versions', 'suffix')})
        with self.assertRaisesRegex(ValueError, 'different target'):
            release.verify_manifest(manifest, 'rootfs', build, {})
        self.env['ROOTFS_TARGET'] = 'Ubuntu-24'
        with self.assertRaises(ValueError):
            release.plan(self.env)

    def test_wrong_tag_and_empty_selection_fail(self):
        self.env['BUILD_SHELL'] = 'false'
        with self.assertRaises(ValueError):
            release.plan(self.env)
        self.env.update(GITHUB_EVENT_NAME='push', GITHUB_REF='refs/tags/v999.0.0')
        with self.assertRaises(ValueError):
            release.plan(self.env)

    def test_child_plan_cannot_change_source(self):
        supplied = release.plan(self.env)
        supplied['source'] = 'b' * 40
        with patch.dict(os.environ, dict(self.env, BUILD_PLAN=json.dumps(supplied)), clear=True):
            with self.assertRaises(ValueError):
                release.current()

    def test_public_and_foreign_drafts_are_protected(self):
        build = release.plan(self.env)
        for draft, body in [(False, release.marker(build)), (True, 'another run')]:
            with self.assertRaises(ValueError):
                release.require_owned_draft(build, dict(isDraft=draft, body=body))

    def test_verify_assets_detects_corruption_and_other_run(self):
        build = release.plan(self.env)
        files = [dict(name=name, size=3, sha256='a' * 64)
                 for name in sorted(release.EXPECTED['shell'] | {'shell-SHA256SUMS'})]
        manifest = dict(component='shell', files=files,
                        **{k: build[k] for k in ('run_id', 'source', 'versions', 'suffix')})
        assets = {f['name']: dict(size=3, digest='sha256:' + f['sha256']) for f in files}
        release.verify_manifest(manifest, 'shell', build, assets)
        assets[files[0]['name']]['digest'] = 'sha256:' + 'b' * 64
        with self.assertRaises(ValueError):
            release.verify_manifest(manifest, 'shell', build, assets)
        manifest['run_id'] = 'another'
        with self.assertRaises(ValueError):
            release.verify_manifest(manifest, 'shell', build, assets)

    def test_split_rootfs_requires_every_part(self):
        v = versions.read_versions()
        base = f"anland-rootfs-debian13-arm64-{v['ROOTFS_VERSION']}.tar.xz"
        names = {'ROOTFS-SHA256SUMS', 'rootfs-components.json', base + '.part-000', base + '.part-001'}
        release.validate_names('rootfs', names, v, '')
        names.remove(base + '.part-000')
        with self.assertRaises(ValueError):
            release.validate_names('rootfs', names, v, '')

    def test_failed_selected_job_marks_draft_incomplete(self):
        build = release.plan(self.env)
        draft = dict(isDraft=True, body=release.marker(build), assets=[])
        with patch.object(release, 'view', return_value=draft), patch.object(release, 'edit_notes') as edit:
            with self.assertRaises(ValueError):
                release.finalize(build, {'shell': {'result': 'failure'}, 'rootfs': {'result': 'skipped'}})
            self.assertIn('Incomplete', edit.call_args.args[1])

    def test_skipped_unselected_jobs_allow_finalization(self):
        build = release.plan(self.env)
        files = [dict(name=name, size=3, sha256='a' * 64)
                 for name in sorted(release.EXPECTED['shell'] | {'shell-SHA256SUMS'})]
        manifest = dict(component='shell', files=files,
                        **{k: build[k] for k in ('run_id', 'source', 'versions', 'suffix')})
        assets = [dict(name=f['name'], size=3, digest='sha256:' + f['sha256']) for f in files]
        assets.append(dict(name='shell-manifest.json'))
        draft = dict(isDraft=True, body=release.marker(build), assets=assets)

        def fake_gh(*args):
            if args[:2] == ('release', 'download'):
                (Path(args[args.index('--dir') + 1]) / 'shell-manifest.json').write_text(json.dumps(manifest))
            return ''

        with patch.object(release, 'view', return_value=draft), patch.object(release, 'gh', side_effect=fake_gh), \
                patch.object(release, 'edit_notes') as edit:
            release.finalize(build, {c: {'result': 'success' if c == 'shell' else 'skipped'}
                                     for c in release.COMPONENTS})
            self.assertIn('Complete', edit.call_args.args[1])

    def test_module_version_comes_from_component_not_release(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {'ANLAND_VERSION_SUFFIX': '-dev.12+aaaaaaaa'}):
            output = Path(temp) / 'module.prop'
            versions.render_module(ROOT / 'module/module.prop', output)
            props = output.read_text()
            v = versions.read_versions()
            self.assertIn('version=' + v['MODULE_VERSION_NAME'] + '-dev.12+aaaaaaaa\n', props)
            self.assertIn('versionCode=' + v['MODULE_VERSION_CODE'] + '\n', props)

    def test_invalid_version_config_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'versions'
            content = (ROOT / 'version.properties').read_text()
            for bad in (content + '\nRELEASE_VERSION=1.0.0\n',
                        content.replace('SHELL_VERSION_CODE=' + versions.read_versions()['SHELL_VERSION_CODE'],
                                        'SHELL_VERSION_CODE=0')):
                path.write_text(bad)
                with self.assertRaises(ValueError):
                    versions.read_versions(path)

    def test_export_verifies_actual_runtime_bytes(self):
        manifest = {'patched_files': {name: release.hashlib.sha256(b'patched').hexdigest()
                                      for name in image.RUNTIME.values()}}
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / 'image.tar.xz'
            for content in (b'patched', b'upstream-overwrote-it'):
                with tarfile.open(archive, 'w:xz') as tar:
                    for path in image.RUNTIME:
                        entry = tarfile.TarInfo('./' + path)
                        entry.size = len(content)
                        tar.addfile(entry, io.BytesIO(content))
                    data = json.dumps(manifest).encode()
                    entry = tarfile.TarInfo('./' + image.METADATA)
                    entry.size = len(data)
                    tar.addfile(entry, io.BytesIO(data))
                if content == b'patched':
                    self.assertEqual(image.verify_archive(archive), manifest)
                else:
                    with self.assertRaises(ValueError):
                        image.verify_archive(archive)

    def test_package_uses_selected_target_and_rejects_foreign_image(self):
        target, config = release.resolve('Arch')
        v = versions.read_versions()
        inputs = dict(target=target, target_config=config, rootfs_version=v['ROOTFS_VERSION'])
        manifest = dict(inputs, xfdesktop_files={}, patched_files={
            name: release.hashlib.sha256(b'patched').hexdigest() for name in image.RUNTIME.values()})
        with tempfile.TemporaryDirectory() as temp:
            builder = Path(temp) / 'builder'
            (builder / 'anland-overrides').mkdir(parents=True)
            (builder / 'anland-overrides/rootfs-inputs.json').write_text(json.dumps(inputs))
            archive = builder / 'upstream.tar.xz'
            with tarfile.open(archive, 'w:xz') as tar:
                contents = {path: b'patched' for path in image.RUNTIME}
                contents[image.METADATA] = json.dumps(manifest).encode()
                for path, data in contents.items():
                    entry = tarfile.TarInfo(path)
                    entry.size = len(data)
                    tar.addfile(entry, io.BytesIO(data))
            with patch.dict(os.environ, {'ROOTFS_TARGET': 'Debian-13', 'ANLAND_VERSION_SUFFIX': ''}):
                with self.assertRaisesRegex(ValueError, 'selected RootFS target'):
                    image.package(builder, Path(temp) / 'wrong')
            output = Path(temp) / 'release'
            with patch.dict(os.environ, {'ROOTFS_TARGET': target, 'ANLAND_VERSION_SUFFIX': ''}):
                image.package(builder, output)
            name = release.archive_name(v, '', target)
            self.assertTrue((output / name).is_file())
            self.assertEqual((output / 'ROOTFS-SHA256SUMS').read_text(),
                             f'{release.sha256(output / name)}  {name}\n')


if __name__ == '__main__':
    unittest.main()
