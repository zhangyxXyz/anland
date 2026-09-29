import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
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
            self.assertIn('apt-get install -y /tmp/anland-session.deb', result)
            self.assertLess(result.index('upstream-cleanup'), result.index('RUN bash /tmp/anland-overrides/install.sh'))
            self.assertLess(result.index('RUN bash /tmp/anland-overrides/install.sh'), result.index('FROM scratch AS export'))
            self.assertNotIn('anland_prepare_release', script.read_text())
            self.assertTrue((builder / 'anland-overrides/install.sh').is_file())
            self.assertTrue((builder / 'anland-overrides/verify-installed.py').is_file())

    def test_upstream_drift_is_not_silently_accepted(self):
        with self.assertRaises(ValueError):
            integrate.replace_once('changed upstream', 'expected upstream', 'replacement')
        with self.assertRaises(ValueError):
            integrate.replace_once('duplicate duplicate', 'duplicate', 'replacement')


if __name__ == '__main__':
    unittest.main()
