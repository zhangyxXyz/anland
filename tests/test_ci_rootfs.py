import importlib.util
import hashlib
import io
import json
import re
from pathlib import Path
import sys
import tempfile
import tarfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts/ci'))
from rootfs_target import resolve, archive_name
spec = importlib.util.spec_from_file_location('integrate_builder', ROOT / 'rootfs/integrate-builder.py')
integrate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(integrate)
sys.path.insert(0, str(ROOT / 'rootfs'))
import mount_policy
spec = importlib.util.spec_from_file_location('repack_image', ROOT / 'rootfs/repack-image.py')
repack_image = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repack_image)


class RootfsTests(unittest.TestCase):
    def test_overrides_are_injected_before_export_after_upstream_install(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            inputs, builder = root / 'inputs', root / 'builder'
            inputs.mkdir()
            builder.mkdir()
            (inputs / 'session.deb').write_bytes(b'fixture')
            docker = builder / 'Debian-13.Dockerfile'
            docker.write_text('FROM debian:trixie AS customizer\n'
                              'RUN /usr/local/sbin/install-anland-desktop "$DESKTOP" --1 && true\n'
                              'RUN echo upstream-cleanup\nFROM scratch AS export\nCOPY --from=customizer / /\n')
            script = builder / 'build_rootfs-native.sh'
            script.write_text('before\nif [ -n "$ANLAND_PACKAGE_FAMILY" ]; then\n'
                              '  anland_prepare_release rolling\nelse\n  true\nfi\nafter\n')
            integrate.integrate(builder, inputs)
            result = docker.read_text()
            self.assertNotIn('install-anland-desktop "$DESKTOP"', result)
            self.assertIn('apt-get install -y /tmp/session.deb', result)
            self.assertLess(result.index('upstream-cleanup'), result.index('RUN bash /tmp/anland-overrides/install.sh'))
            self.assertLess(result.index('RUN bash /tmp/anland-overrides/install.sh'), result.index('FROM scratch AS export'))
            self.assertNotIn('anland_prepare_release', script.read_text())
            self.assertTrue((builder / 'anland-overrides/install.sh').is_file())
            self.assertTrue((builder / 'anland-overrides/verify-installed.py').is_file())
            self.assertEqual((builder / 'anland-overrides/mount_policy.py').read_bytes(),
                             (ROOT / 'rootfs/mount_policy.py').read_bytes())
            self.assertIn('anland-overrides/mount_policy.py /tmp/anland-overrides/', result)

    def test_every_target_uses_its_own_base_and_native_session_package(self):
        targets = json.loads((ROOT / 'rootfs/sources.json').read_text())['targets']
        for name, config in targets.items():
            with self.subTest(target=name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                inputs, builder = root / 'inputs', root / 'builder'
                inputs.mkdir()
                builder.mkdir()
                docker = builder / config['dockerfile']
                docker.write_text(f"FROM {config['base_image']} AS customizer\n"
                                  'RUN /usr/local/sbin/install-anland-desktop "$DESKTOP" --1 && true\n'
                                  'FROM scratch AS export\nCOPY --from=customizer / /\n')
                (builder / 'build_rootfs-native.sh').write_text(
                    'if [ -n "$ANLAND_PACKAGE_FAMILY" ]; then\n  anland_prepare_release\nfi\n')
                integrate.integrate(builder, inputs, name)
                result = docker.read_text()
                self.assertIn(config['session_package']['filename'], result)
                self.assertIn({'apt': 'apt-get install', 'dnf': 'dnf install',
                               'pacman': 'pacman --config'}[config['package_manager']], result)
                self.assertIn('FROM customizer AS component-builder', result)
                self.assertIn('FROM customizer AS integrated', result)
                self.assertTrue(result.endswith('COPY --from=integrated / /\n'))
                self.assertNotIn('COPY --from=component-builder / /', result)
                self.assertIn(config['session_package']['sha256'],
                              (builder / 'build_rootfs-native.sh').read_text())

    def test_defaults_and_unsupported_targets(self):
        self.assertEqual(resolve()[0], 'Debian-13')
        for target in ('Ubuntu-24', 'Ubuntu-25', '../Debian-13', 'all'):
            with self.assertRaises(ValueError):
                resolve(target)
        self.assertEqual(archive_name({'ROOTFS_VERSION': '1.2.3'}, '', 'Ubuntu-26'),
                         'anland-rootfs-ubuntu2604-arm64-1.2.3.tar.xz')

    def test_workflow_choices_match_reviewed_targets(self):
        lock = json.loads((ROOT / 'rootfs/sources.json').read_text())
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        choices = workflow.split('      rootfs_target:\n', 1)[1].split('\n# ', 1)[0]
        self.assertEqual(set(re.findall(r'^          - (.+)$', choices, re.M)), set(lock['targets']))
        self.assertIn('        default: ' + lock['default_target'], choices)

    def test_upstream_drift_is_not_silently_accepted(self):
        with self.assertRaises(ValueError):
            integrate.replace_once('changed upstream', 'expected upstream', 'replacement')
        with self.assertRaises(ValueError):
            integrate.replace_once('duplicate duplicate', 'duplicate', 'replacement')

    def test_mount_policy_preserves_other_services_and_rejects_conflicts(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            docker = root / 'etc/systemd/system/docker.service.d/local.conf'
            docker.parent.mkdir(parents=True)
            docker.write_bytes(b'[Service]\nMountFlags=shared\n')
            mount_policy.install(root)
            mount_policy.install(root)
            mount_policy.verify(root, {'service_mount_policy': mount_policy.hashes()})
            self.assertEqual(docker.read_bytes(), b'[Service]\nMountFlags=shared\n')
            self.assertEqual(len(list(root.rglob('*.conf'))), 7)
            policy = root / next(iter(mount_policy.FILES))
            policy.write_bytes(b'[Service]\nMountFlags=private\n')
            with self.assertRaisesRegex(ValueError, 'Refusing to overwrite'):
                mount_policy.install(root)

    def test_repacked_image_preserves_payload_links_xattrs_and_rejects_bad_policy(self):
        image = repack_image.image
        target, config = resolve('Debian-13')
        manifest = dict(target=target, target_config=config, rootfs_version='0.0.1',
                        patched_files={name: hashlib.sha256(b'ELF fixture').hexdigest()
                                       for name in image.RUNTIME.values()})
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            base = root / 'base.tar.xz'
            docker_path = 'etc/systemd/system/docker.service.d/local.conf'
            contents = {path: b'ELF fixture' for path in image.RUNTIME}
            contents[docker_path] = b'[Service]\nMountFlags=shared\n'
            contents['usr/bin/' + 'long-name-' * 15] = b'long path payload'
            contents[image.METADATA] = json.dumps(manifest).encode()
            with tarfile.open(base, 'w:xz', format=tarfile.PAX_FORMAT) as tar:
                for path, data in contents.items():
                    entry = tarfile.TarInfo(path)
                    entry.size = len(data)
                    entry.uid, entry.gid, entry.mode = 123, 456, 0o750
                    entry.pax_headers = {
                        'SCHILY.xattr.user.example': 'preserve-me',
                        'SCHILY.xattr.security.capability': b'\x01\x00\x00\x02\x00\x14\x80'.decode('utf-8', 'surrogateescape'),
                    }
                    tar.addfile(entry, io.BytesIO(data))
                for name, kind in (('docker-link', tarfile.SYMTYPE), ('docker-hardlink', tarfile.LNKTYPE)):
                    entry = tarfile.TarInfo(name)
                    entry.type, entry.linkname = kind, docker_path
                    tar.addfile(entry)
            original = base.read_bytes()
            output = repack_image.repack(base, root / 'output')
            self.assertEqual(base.read_bytes(), original)
            result = image.verify_archive(output)
            self.assertEqual(result['service_mount_policy'], mount_policy.hashes())
            report = json.loads((output.parent / 'repack-verification.json').read_text())
            self.assertEqual(report['modified_entries'], [image.METADATA])
            with tarfile.open(output, 'r:xz') as tar:
                self.assertEqual(tar.extractfile(docker_path).read(), contents[docker_path])
                self.assertEqual(tar.getmember(docker_path).uid, 123)
                self.assertEqual(tar.getmember(docker_path).pax_headers['SCHILY.xattr.security.capability'],
                                 b'\x01\x00\x00\x02\x00\x14\x80'.decode('utf-8', 'surrogateescape'))
                self.assertEqual(tar.getmember('docker-link').linkname, docker_path)
                exported = [(member, tar.extractfile(member).read() if member.isfile() else None)
                            for member in tar]
            for bad_data in (None, b'[Service]\nMountFlags=shared\n'):
                bad_archive = root / 'bad.tar.xz'
                bad_path = next(iter(mount_policy.FILES))
                with tarfile.open(bad_archive, 'w:xz') as tar:
                    for member, data in exported:
                        if member.name == bad_path:
                            if bad_data is None:
                                continue
                            data = bad_data
                            member.size = len(data)
                        tar.addfile(member, io.BytesIO(data) if data is not None else None)
                with self.assertRaises(ValueError):
                    image.verify_archive(bad_archive)

    def test_binary_pax_encoding_marker_does_not_change_file_attributes(self):
        original = tarfile.TarInfo('capability-helper')
        original.pax_headers = {'SCHILY.xattr.security.capability': '\udc80\x00'}
        rewritten = tarfile.TarInfo('capability-helper')
        rewritten.pax_headers = dict(original.pax_headers, hdrcharset='BINARY')
        self.assertEqual(repack_image.fingerprint(original, 'same-bytes'),
                         repack_image.fingerprint(rewritten, 'same-bytes'))
        rewritten.pax_headers['SCHILY.xattr.security.capability'] = '\x00\x00'
        self.assertNotEqual(repack_image.fingerprint(original, 'same-bytes'),
                            repack_image.fingerprint(rewritten, 'same-bytes'))


if __name__ == '__main__':
    unittest.main()
