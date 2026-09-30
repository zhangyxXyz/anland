"""Exercise the real watcher with isolated settings commands (Linux only)."""
import os
import signal
from pathlib import Path
import subprocess
import tempfile
import time
import unittest


@unittest.skipUnless(os.name == 'posix', 'requires Linux flock and Bash')
class AppearanceSyncTest(unittest.TestCase):
    def test_android_monitor_refreshes_stale_state_and_recovers_without_duplicates(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            state = root / 'appearance'
            state.mkdir()
            (state / 'night-mode').write_text('dark\n')
            source = root / 'android-mode'
            source.write_text('false\n')
            binaries = root / 'bin'
            binaries.mkdir()
            dumpsys = binaries / 'dumpsys'
            dumpsys.write_text('#!/bin/sh\nprintf "mComputedNightMode=%s\\n" "$(cat "$ANDROID_MODE")"\n')
            dumpsys.chmod(0o755)
            env = dict(os.environ, PATH=f'{binaries}:{os.environ["PATH"]}', ANDROID_MODE=str(source))
            script = Path(__file__).resolve().parents[1] / 'module/appearance.sh'
            pids = set()
            def ensure():
                subprocess.run(['sh', str(script), str(root), '--ensure'], env=env, check=True, timeout=4)
            def wait_for(predicate):
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline:
                    if predicate():
                        return
                    time.sleep(.05)
                self.fail('Android monitor did not converge')
            def pid():
                text = (state / 'monitor.pid').read_text().strip() if (state / 'monitor.pid').exists() else ''
                return int(text) if text else 0
            try:
                ensure()
                self.assertEqual('light\n', (state / 'night-mode').read_text())
                wait_for(lambda: pid() != 0)
                first = pid()
                pids.add(first)
                stamp = (state / 'night-mode').stat().st_mtime_ns
                ensure()
                time.sleep(.2)
                self.assertEqual(first, pid(), 'duplicate monitor acquired the lock')
                self.assertEqual(stamp, (state / 'night-mode').stat().st_mtime_ns, 'unchanged mode must not be republished')
                source.write_text('true\n')
                wait_for(lambda: (state / 'night-mode').read_text() == 'dark\n')
                os.kill(first, signal.SIGKILL)
                # Recovery must work immediately, even while the old sleep is alive.
                time.sleep(.1)
                source.write_text('false\n')
                ensure()
                self.assertEqual('light\n', (state / 'night-mode').read_text())
                wait_for(lambda: pid() != first)
                pids.add(pid())
            finally:
                for item in pids:
                    try:
                        os.kill(item, signal.SIGTERM)
                    except ProcessLookupError:
                        pass

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
                'gsettings': '''if [[ "$1" == get ]]; then cat "$CALLS.state" 2>/dev/null; exit 0; fi
echo "$*" >> "$CALLS"
printf "'%s'\\n" "$4" > "$CALLS.state"
''',
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
                # Same effective mode, even when the policy/owner changes: no backend notification.
                (state / 'app-theme').write_text('com.anland.shell\nlight\n')
                time.sleep(2.2)
                self.assertEqual(4, len(log.read_text().splitlines()))
                proc.terminate()
                proc.wait(timeout=4)
                # Restarting the monitor must not reapply an already matching setting either.
                proc = subprocess.Popen(['bash', str(script), 'standalone'], env=env)
                time.sleep(2.2)
                self.assertEqual(4, len(log.read_text().splitlines()))
            finally:
                proc.terminate()
                proc.wait(timeout=4)


if __name__ == '__main__':
    unittest.main()
