"""Fail the image build if runtime files, package versions or dependencies differ."""
import hashlib
import json
from pathlib import Path
import subprocess
import shlex

manifest = json.loads(Path('/usr/share/anland/rootfs-components.json').read_text())
config = manifest['target_config']
os_release = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines()
                  if '=' in line and not line.startswith('#'))
os_release = {k: shlex.split(v)[0] if shlex.split(v) else '' for k, v in os_release.items()}
if os_release.get('ID') not in config['os_ids'] or (
        config['os_version'] and os_release.get('VERSION_ID') != config['os_version']):
    raise SystemExit('Image distribution differs from the selected target')
for path, expected in manifest['xfdesktop_files'].items():
    if hashlib.sha256(Path('/' + path).read_bytes()).hexdigest() != expected:
        raise SystemExit(f'Wrong installed Xfdesktop file: {path}')
for name, path in {'Xwayland': '/usr/lib/anland/Xwayland',
                   'anland-miniwm': '/usr/bin/anland-miniwm',
                   'anland-session': '/usr/bin/anland-session', 'xfdesktop': '/usr/bin/xfdesktop'}.items():
    data = Path(path).read_bytes()
    if hashlib.sha256(data).hexdigest() != manifest['patched_files'][name]:
        raise SystemExit(f'Wrong runtime file: {path}')
    if name != 'anland-session':
        if data[:4] != b'\x7fELF' or int.from_bytes(data[18:20], 'little') != 183:
            raise SystemExit(f'Not an AArch64 ELF: {path}')
        result = subprocess.run(['ldd', path], capture_output=True, text=True, check=True)
        if 'not found' in result.stdout + result.stderr:
            raise SystemExit(f'Missing shared libraries: {path}')
command = {
    'apt': ['dpkg-query', '-W', '-f=${Version}', 'anland-session'],
    'dnf': ['rpm', '-q', '--qf', '%{VERSION}-%{RELEASE}', 'anland-session'],
    'pacman': ['pacman', '-Q', 'anland-session'],
}[config['package_manager']]
session_version = subprocess.check_output(command, text=True).strip()
if config['package_manager'] == 'pacman':
    session_version = session_version.split()[1]
if session_version != config['session_package']['version']:
    raise SystemExit('Wrong session base package version')
xwayland = Path('/usr/lib/anland/Xwayland').read_bytes()
if b'/usr/share/X11/xkb' not in xwayland or b'/usr/local/share/X11/xkb' in xwayland:
    raise SystemExit('Wrong Xwayland XKB path')
session = Path('/usr/bin/anland-session').read_text()
if 'ANLAND_COMPAT_BIN_DIR:=/usr/lib/anland' not in session:
    raise SystemExit('Session does not prefer packaged Xwayland')
for path in ('/usr/local/bin/anland-desktop', '/usr/local/bin/anland-desktop-session',
             '/usr/local/bin/anland-desktop-inner', '/usr/local/bin/anland-desktop-appearance',
             '/usr/lib/systemd/user/anland-desktop.service', '/usr/lib/systemd/user/anland-session.service',
             '/usr/local/share/icons/hicolor/128x128/apps/anland-debian.png',
             '/usr/local/share/icons/hicolor/256x256/apps/anland-debian.png'):
    if not Path(path).is_file():
        raise SystemExit(f'Missing desktop runtime file: {path}')
print('Verified patched packages, AArch64 executables, runtime paths and shared libraries')
