"""Exercise the real watcher with isolated settings commands (Linux only)."""
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest


@unittest.skipUnless(os.name == 'posix', 'requires Linux flock and Bash')
class AppearanceSyncTest(unittest.TestCase):
    def test_policy_changes_system_following_and_single_watcher(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            state = root / 'appearance'
            state.mkdir()
            binaries = root / 'bin'
            binaries.mkdir()
            log = root / 'calls'
            for name, body in {
                'xfconf-query': 'if [[ "$*" == *"/Gdk/WindowScalingFactor"* ]]; then echo 1; fi\n',
                'gsettings': 'echo "$*" >> "$CALLS"\n',
            }.items():
                tool = binaries / name
                tool.write_text('#!/bin/bash\n' + body)
                tool.chmod(0o755)
            env = dict(os.environ, PATH=f'{binaries}:{os.environ["PATH"]}',
                       HOME=str(root), XDG_RUNTIME_DIR=str(root), ANLAND_RUNTIME_DIR=str(root),
                       DISPLAY=':88', CALLS=str(log))
            script = Path(__file__).resolve().parents[1] / 'rootfs/anland-desktop-appearance'
            (state / 'night-mode').write_text('light\n')
            (state / 'app-theme').write_text('com.anland.shell\ndark\n')
            proc = subprocess.Popen(['bash', str(script), 'standalone'], env=env)
            try:
                def await_calls(count, final):
                    deadline = time.monotonic() + 8
                    while time.monotonic() < deadline:
                        rows = log.read_text().splitlines() if log.exists() else []
                        if len(rows) >= count:
                            self.assertEqual(final, rows[-1].split()[-1])
                            return
                        self.assertIsNone(proc.poll(), 'watcher exited unexpectedly')
                        time.sleep(.05)
                    self.fail(f'No appearance update: {rows}')
                await_calls(1, 'prefer-dark')
                duplicate = subprocess.run(['bash', str(script), 'standalone'], env=env, timeout=3)
                self.assertEqual(0, duplicate.returncode)
                # A system change cannot override an explicitly dark Android app.
                (state / 'night-mode').write_text('dark\n')
                time.sleep(2.2)
                self.assertEqual(1, len(log.read_text().splitlines()))
                (state / 'app-theme').write_text('com.anlandnext\nlight\n')
                await_calls(2, 'prefer-light')
                (state / 'app-theme').write_text('com.anlandnext\nsystem\n')
                await_calls(3, 'prefer-dark')
                (state / 'night-mode').write_text('light\n')
                await_calls(4, 'prefer-light')
            finally:
                proc.terminate()
                proc.wait(timeout=4)


if __name__ == '__main__':
    unittest.main()
