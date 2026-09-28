"""Exercise live/stale desktop routing without touching the device's sessions."""
import contextlib
import io
import json
from pathlib import Path
import pwd
import tempfile
from unittest.mock import patch

source = (Path(__file__).resolve().parents[1] /
          'shell-app/src/main/assets/desktop-session-probe.py').read_text()

def check(processes, expected, stale=False):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        uid = root.stat().st_uid
        user = pwd.getpwuid(uid)
        runtime = root / 'run/user' / str(uid)
        desktop = runtime / 'anland-desktop'
        desktop.mkdir(parents=True)
        (root / 'proc').mkdir()
        authority = desktop / 'Xauthority'
        authority.touch()
        if processes or stale:
            (desktop / 'env').write_text('DISPLAY=:3\nXAUTHORITY=' + str(authority) + '\n')
        for pid, group, env in processes:
            proc = root / 'proc' / str(pid)
            proc.mkdir()
            (proc / 'cgroup').write_text('0::/user.slice/' + group + '\n')
            if env is not None:
                (proc / 'environ').write_bytes('\0'.join(k+'='+v for k,v in env.items()).encode())
        def mapped_path(value):
            value = str(value)
            return root / value.lstrip('/') if value in ('/proc', '/run/user') else Path(value)
        out = io.StringIO()
        failed = False
        with patch('pathlib.Path', mapped_path), patch('sys.argv', ['probe', user.pw_name]), contextlib.redirect_stdout(out):
            try:
                exec(compile(source, 'desktop-session-probe.py', 'exec'), {})
            except SystemExit as e:
                if e.code: raise
            except (RuntimeError, OSError):
                failed = True
        if expected == 'error':
            assert failed, out.getvalue()
        else:
            assert not failed
            result = json.loads(out.getvalue())
            assert result['active'] == expected
            if expected:
                assert result['env']['DBUS_SESSION_BUS_ADDRESS'] == 'unix:path=/tmp/private-bus'
                assert result['env']['DISPLAY'] == ':3'
                assert result['env']['GDK_BACKEND'] == 'x11'

private = {'DISPLAY': ':3', 'DBUS_SESSION_BUS_ADDRESS': 'unix:path=/tmp/private-bus'}
check([], False)
check([], False, stale=True)
check([(10, 'anland-desktop.service', private)], True)
check([(10, 'unrelated.service', private)], False, stale=True)
check([(10, 'anland-desktop.service', None)], 'error')
check([(10, 'anland-desktop.service', {'DISPLAY': ':3'}),
       (11, 'unrelated.service', private)], 'error')
print('desktop session probe: 6 scenarios passed')
