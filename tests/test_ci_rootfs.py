import importlib.util
import json
import re
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts/ci'))
from rootfs_target import resolve, archive_name
spec = importlib.util.spec_from_file_location('integrate_builder', ROOT / 'rootfs/integrate-builder.py')
integrate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(integrate)


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


if __name__ == '__main__':
    unittest.main()
