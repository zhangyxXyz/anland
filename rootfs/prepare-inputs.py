"""Download the checksum-locked session base and record this build's inputs."""
import json
from pathlib import Path
import shutil
import subprocess
import sys

SOURCE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SOURCE / 'scripts/ci'))
from release import sha256
from versions import read_versions, version_suffix

out = Path(sys.argv[1])
lock = json.loads((SOURCE / 'rootfs/sources.json').read_text())
package = out / 'session.deb'
subprocess.run(['curl', '-fL', '--retry', '3', '--output', str(package),
                lock['session_package']['url']], check=True)
if sha256(package) != lock['session_package']['sha256']:
    raise SystemExit('Session package changed upstream; review and update rootfs/sources.json')
if len(list(out.glob('xfdesktop4_*_arm64.deb'))) != 1 or len(list(out.glob('xfdesktop4-data_*_all.deb'))) != 1:
    raise SystemExit('Expected exactly two Xfdesktop packages')
shutil.copyfile(SOURCE / 'anland-session/anland-session.sh', out / 'anland-session')
versions = read_versions()
manifest = {
    'rootfs_version': versions['ROOTFS_VERSION'] + version_suffix(),
    'release_version': versions['RELEASE_VERSION'],
    'source': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=SOURCE, text=True).strip(),
    'xserver_commit': subprocess.check_output(['git', '-C', str(SOURCE / 'third_party/xserver'),
                                             'rev-parse', 'HEAD'], text=True).strip(),
    'sources': lock,
    'xfdesktop_version': (out / 'xfdesktop-version').read_text().strip(),
    'patched_files': {name: sha256(out / name) for name in ('Xwayland', 'anland-miniwm', 'anland-session')},
    'patches': {str(p.relative_to(SOURCE)).replace('\\', '/'): sha256(p)
                for folder in ('xfdesktop', 'xwayland')
                for p in sorted((SOURCE / 'patches' / folder).glob('*.patch'))},
}
(out / 'rootfs-components.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
(out / 'SHA256SUMS').write_text(''.join(f'{sha256(p)}  {p.name}\n' for p in sorted(out.iterdir())
                                     if p.is_file() and p.name != 'SHA256SUMS'), encoding='utf-8')
