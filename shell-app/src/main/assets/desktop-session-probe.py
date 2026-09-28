"""Read the selected user's live desktop context without changing either session.

The desktop deliberately has a private bus. Reusing only DISPLAY would let
single-instance GTK applications activate an old window on the standalone bus.
Never source arbitrary shell text or fall back to a different display on error.
"""
import json
import os
from pathlib import Path
import pwd
import sys

account = pwd.getpwnam(sys.argv[1])
runtime = Path('/run/user') / str(account.pw_uid)
members = []
live = False
for path in Path('/proc').iterdir():
    if not path.name.isdigit():
        continue
    try:
        if path.stat().st_uid != account.pw_uid:
            continue
        # Match a complete cgroup path component, not a process name.
        if not any('anland-desktop.service' in line.split(':', 2)[-1].split('/')
                   for line in (path / 'cgroup').read_text().splitlines()):
            continue
        live = True
        env = dict(part.split('=', 1) for part in
                   (path / 'environ').read_bytes().decode(errors='replace').split('\0')
                   if '=' in part)
        members.append((int(path.name), env))
    except (OSError, ValueError):
        continue
if not live:
    print(json.dumps({'active': False}))
    sys.exit(0)
state = dict(line.split('=', 1) for line in
             (runtime / 'anland-desktop/env').read_text().splitlines() if '=' in line)
display = state['DISPLAY']
authority = state['XAUTHORITY']
bus = next((env['DBUS_SESSION_BUS_ADDRESS'] for _, env in sorted(members)
            if env.get('DISPLAY') == display and env.get('DBUS_SESSION_BUS_ADDRESS')
            and env['DBUS_SESSION_BUS_ADDRESS'] != 'unix:path=' + str(runtime / 'bus')), None)
if not bus or not Path(authority).is_file():
    raise RuntimeError('Desktop is starting or stopping; retry after it is ready')
print(json.dumps({'active': True, 'env': {
    'DISPLAY': display, 'XAUTHORITY': authority, 'DBUS_SESSION_BUS_ADDRESS': bus,
    'XDG_RUNTIME_DIR': str(runtime), 'XDG_SESSION_TYPE': 'x11',
    'XDG_CURRENT_DESKTOP': 'XFCE', 'GDK_BACKEND': 'x11', 'QT_QPA_PLATFORM': 'xcb',
    'SDL_VIDEODRIVER': 'x11', 'ANLAND_OZONE_PLATFORM': 'x11'
}}))
