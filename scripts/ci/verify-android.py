"""Verify the actual APK/ZIP metadata, not just build configuration."""
import os
import argparse
from pathlib import Path
import re
import subprocess
import zipfile

from signing import CERTIFICATES
from versions import read_versions, version_suffix

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('component', choices=('shell', 'wayland'))
parser.add_argument('--directory', type=Path, default=Path('release'))
parser.add_argument('--apps-only', action='store_true')
args = parser.parse_args()
component = args.component
versions, suffix = read_versions(), version_suffix()
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
apk = args.directory / f'anland-{component}.apk'
signer = tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
aapt = tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
for candidate in (apk, apk.with_name(f'anland-{component}-tests.apk')):
    cert = subprocess.check_output([str(signer), 'verify', '--print-certs', str(candidate)], text=True)
    if CERTIFICATES[component] not in cert:
        raise SystemExit(f'Unexpected signing certificate: {candidate}')
badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
for key, value in {'versionCode': versions[component.upper() + '_VERSION_CODE'],
                   'versionName': versions[component.upper() + '_VERSION_NAME'] + suffix}.items():
    if not re.search(rf"\b{key}='{re.escape(value)}'", badging):
        raise SystemExit(f'Incorrect {key} in {apk}')
if component == 'wayland' and not args.apps_only:
    with zipfile.ZipFile(args.directory / 'anland-awl.zip') as archive:
        props = archive.read('module.prop').decode()
        for key, value in {'version': versions['MODULE_VERSION_NAME'] + suffix,
                           'versionCode': versions['MODULE_VERSION_CODE']}.items():
            if f'{key}={value}' not in props.splitlines():
                raise SystemExit(f'Incorrect module {key}')
        for name in ('appearance.sh', 'service.sh', 'pulse/bin/pulseaudio'):
            if name not in archive.namelist():
                raise SystemExit(f'Module missing {name}')
        if archive.read('waylandbridge') != (args.directory / 'waylandbridge').read_bytes():
            raise SystemExit('Standalone and packaged daemons differ')
print('Verified signing, component versions and packaged files')
