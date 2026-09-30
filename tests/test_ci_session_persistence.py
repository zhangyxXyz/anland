"""Run the generated launch preflight against controlled loginctl/systemctl commands."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = 'D:/MyProfile/Git/bin/bash.exe' if os.name == 'nt' else 'bash'


class SessionPersistenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.base = Path(cls.temp.name)
        java_home = os.environ.get('JAVA_HOME')
        cls.java = str(Path(java_home) / 'bin/java') if java_home else 'java'
        javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
        harness = cls.base / 'PrintPersistence.java'
        harness.write_text('''import com.anland.shell.ds.SessionPersistence;
public class PrintPersistence {
    public static void main(String[] args) {
        System.out.print(SessionPersistence.prepare(args[0], args[1]));
    }
}
''')
        sources = ROOT / 'shell-app/src/main/java/com/anland/shell/ds'
        subprocess.run([javac, '-d', str(cls.base), str(sources/'ShellUtils.java'),
                        str(sources/'SessionPersistence.java'), str(harness)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_preflight(self, state='no', mode='', user="desktop'user", uid='1000'):
        generated = subprocess.check_output([self.java, '-cp', str(self.base),
                                             'PrintPersistence', user, uid], text=True)
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            (base/'state').write_text(state)
            # Functions exercise the production shell and quoting on Linux and Git Bash.
            mocks = '''
id() { printf '1000\n'; }
loginctl() {
  [ "$2" = "$EXPECTED_USER" ] || return 91
  case "$1" in
    show-user) cat state ;;
    enable-linger)
      echo enable >> calls
      [ "$MODE" != denied ] || return 1
      [ "$MODE" = ineffective ] || echo yes > state
      ;;
    *) return 92 ;;
  esac
}
systemctl() { printf '%s\n' "$*" >> calls; }
'''
            result = subprocess.run([BASH, '-c', mocks+generated], cwd=base,
                                    env=dict(os.environ, EXPECTED_USER=user, MODE=mode),
                                    capture_output=True, text=True)
            calls = (base/'calls').read_text().splitlines() if (base/'calls').exists() else []
            return result, calls

    def test_enable_then_start_selected_user(self):
        result, calls = self.run_preflight()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ['enable', 'start user@1000.service'])

    def test_existing_linger_is_preserved(self):
        result, calls = self.run_preflight(state='yes')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ['start user@1000.service'])

    def test_enable_failure_blocks_launch(self):
        result, calls = self.run_preflight(mode='denied')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, ['enable'])

    def test_unapplied_setting_blocks_launch(self):
        result, calls = self.run_preflight(mode='ineffective')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, ['enable'])

    def test_changed_account_identity_blocks_launch(self):
        result, calls = self.run_preflight(uid='1001')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])


if __name__ == '__main__':
    unittest.main()
