"""Exercise the actual session script without changing a live graphical session.

The fixture provides Unix sockets and lightweight Xwayland/systemctl stand-ins.
It verifies the two published environments when PulseAudio starts late, and
that the packaged compatibility executables win over distribution binaries.
Run on Linux: python3 tests/session_startup_test.py [path/to/anland-session.sh]
"""
from pathlib import Path
import os
import socket
import subprocess
import sys
import tempfile

script = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / 'anland-session/anland-session.sh'

def executable(path, body):
    path.write_text('#!/bin/bash\nset -eu\n' + body)
    path.chmod(0o755)

for compat in (False, True):
    with tempfile.TemporaryDirectory(prefix='anland-session-test-') as temp:
        base = Path(temp)
        home, runtime, host, binaries = [base / p for p in ('home', 'runtime', 'host', 'bin')]
        for p in (home, runtime, host, binaries):
            p.mkdir()
        packaged = base / 'compat'
        if compat:
            packaged.mkdir()
        sockets = []
        for path in (runtime/'bus', host/'wayland-0'):
            sock = socket.socket(socket.AF_UNIX)
            sock.bind(str(path))
            sockets.append(sock)
        executable(binaries/'systemctl', '''
case "$2" in
  show-environment) printf 'PATH=%s\n' "$TEST_BASE_PATH" ;;
  set-environment) printf '%s\n' "$@" >> "$TEST_LOG"; cp "$HOME/.anlandx-env" "$TEST_ENV_COPY" ;;
  unset-environment) : ;;
esac
''')
        executable(binaries/'Xwayland', 'echo distro >> "$TEST_BINARY_LOG"\nprintf "99\\n" >&3\nsleep 2\n')
        if compat:
            executable(packaged/'Xwayland', 'echo compat >> "$TEST_BINARY_LOG"\nprintf "99\\n" >&3\nsleep 2\n')
        executable(binaries/'miniwm', 'sleep 0.1\n')
        env = dict(os.environ, HOME=str(home), XDG_RUNTIME_DIR=str(runtime),
                   ANLAND_RUNTIME_DIR=str(host), WAYLAND_DISPLAY='wayland-0',
                   ANLAND_COMPAT_BIN_DIR=str(packaged), ANLAND_MINIWM=str(binaries/'miniwm'),
                   PATH=str(binaries)+':/usr/bin:/bin', TEST_BASE_PATH=str(binaries)+':/usr/bin:/bin',
                   TEST_LOG=str(base/'manager-env'), TEST_ENV_COPY=str(base/'file-env'),
                   TEST_BINARY_LOG=str(base/'binary'))
        result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True, timeout=10)
        assert result.returncode == 1, result.stderr  # miniwm exit deliberately ends the session
        expected = 'PULSE_SERVER=unix:' + str(host/'pulse.sock')
        assert expected in (base/'manager-env').read_text().splitlines(), result.stdout+result.stderr
        assert expected in (base/'file-env').read_text().splitlines(), result.stdout+result.stderr
        assert (base/'binary').read_text().strip() == ('compat' if compat else 'distro')
        assert not (host/'pulse.sock').exists(), 'test must cover audio not ready yet'
        for sock in sockets:
            sock.close()
        print('PASS: late audio in both environments; compatibility directory', 'present' if compat else 'absent')
