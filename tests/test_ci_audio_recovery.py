"""Exercise the module's bounded post-boot recovery without touching host audio."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = 'D:/MyProfile/Git/bin/bash.exe' if os.name == 'nt' else 'bash'
SOURCE = (ROOT/'module/service.sh').read_text().split('case "${1:-}" in')[0]


class AudioRecoveryTest(unittest.TestCase):
    def recover(self, boot_after=0, services=True, already_ready=False, succeed_on=1):
        mocks = '''
ticks=0
starts=0
getprop() { if [ "$ticks" -ge "$BOOT_AFTER" ]; then echo 1; else echo 0; fi; }
service() { if [ "$SERVICES" = yes ]; then echo "Service $2: found"; else echo 'not available'; fi; }
sleep() { ticks=$((ticks + $1)); }
pulse_is_ready() { [ "$ALREADY_READY" = yes ]; }
start_pulse() {
  starts=$((starts + 1))
  echo "start:$starts at:$ticks" >> calls
  [ "$starts" -ge "$SUCCEED_ON" ]
}
recover_boot_pulse
rc=$?
echo "ticks:$ticks" >> calls
exit "$rc"
'''
        with tempfile.TemporaryDirectory() as temp:
            script = Path(temp)/'recovery.sh'
            script.write_text(SOURCE+mocks, encoding='utf-8', newline='\n')
            result = subprocess.run([BASH, 'recovery.sh'], cwd=temp,
                env=dict(os.environ, BOOT_AFTER=str(boot_after),
                         SERVICES='yes' if services else 'no',
                         ALREADY_READY='yes' if already_ready else 'no',
                         SUCCEED_ON=str(succeed_on)), capture_output=True, text=True)
            self.assertTrue((Path(temp)/'calls').exists(), result.stderr)
            return result, (Path(temp)/'calls').read_text().splitlines()

    def test_waits_for_boot_before_starting(self):
        result, calls = self.recover(boot_after=6)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ['start:1 at:6', 'ticks:6'])

    def test_does_not_restart_a_repaired_server(self):
        result, calls = self.recover(already_ready=True)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls, ['ticks:0'])

    def test_retries_transient_output_failure(self):
        result, calls = self.recover(succeed_on=2)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls, ['start:1 at:0', 'start:2 at:5', 'ticks:5'])

    def test_missing_audio_service_has_a_deadline(self):
        result, calls = self.recover(services=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, ['ticks:180'])

    def test_output_failure_has_a_retry_limit(self):
        result, calls = self.recover(succeed_on=99)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, ['start:1 at:0', 'start:2 at:5', 'start:3 at:10', 'ticks:10'])


if __name__ == '__main__':
    unittest.main()
