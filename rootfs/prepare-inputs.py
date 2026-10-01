"""Stage checksum-locked sources and provenance for the selected image."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

SOURCE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SOURCE / 'scripts/ci'))
from release import sha256
from rootfs_target import resolve
from versions import read_versions, version_suffix

out = Path(sys.argv[1])
# A retry must not pick up output from a different target.
if out.exists() and any(out.iterdir()):
    raise SystemExit('RootFS inputs directory must be empty')
out.mkdir(parents=True, exist_ok=True)
lock = json.loads((SOURCE / 'rootfs/sources.json').read_text())
target, config = resolve(os.environ.get('ROOTFS_TARGET'))
for filename, package in ((config['session_package']['filename'], config['session_package']),
                          ('xfdesktop.tar.bz2', lock['xfdesktop_source'])):
    path = out / filename
    subprocess.run(['curl', '-fL', '--retry', '3', '--output', str(path), package['url']], check=True)
    if sha256(path) != package['sha256']:
        raise SystemExit(f'Checksum mismatch: {filename}; review rootfs/sources.json')
subprocess.run(['git', '-C', str(SOURCE / 'third_party/xserver'), 'archive',
                '--output', str((out / 'xserver.tar').resolve()), 'HEAD'], check=True)
for folder in ('xfdesktop', 'xwayland'):
    shutil.copytree(SOURCE / 'patches' / folder, out / 'patches' / folder)
for name, source in {'anland-session': 'anland-session/anland-session.sh',
                     'miniwm.c': 'anland-session/miniwm.c',
                     'fcitx5-anland.cpp': 'anland-session/fcitx5-anland.cpp',
                     'fcitx5-anland.conf': 'anland-session/fcitx5-anland.conf',
                     'desktop_ime_wire.h': 'include/desktop_ime_wire.h',
                     'build-components.sh': 'rootfs/build-components.sh',
                     'record-components.py': 'rootfs/record-components.py'}.items():
    shutil.copyfile(SOURCE / source, out / name)
versions = read_versions()
manifest = {
    'target': target,
    'target_config': config,
    'rootfs_version': versions['ROOTFS_VERSION'] + version_suffix(),
    'release_version': versions['RELEASE_VERSION'],
    'source': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=SOURCE, text=True).strip(),
    'xserver_commit': subprocess.check_output(['git', '-C', str(SOURCE / 'third_party/xserver'),
                                             'rev-parse', 'HEAD'], text=True).strip(),
    'sources': {k: lock[k] for k in ('builder_repository', 'builder_commit', 'xfdesktop_source')},
    'patches': {str(p.relative_to(SOURCE)).replace('\\', '/'): sha256(p)
                for folder in ('xfdesktop', 'xwayland')
                for p in sorted((SOURCE / 'patches' / folder).glob('*.patch'))},
}
(out / 'rootfs-inputs.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
(out / 'SHA256SUMS').write_text(''.join(f'{sha256(p)}  {p.relative_to(out).as_posix()}\n'
                                     for p in sorted(out.rglob('*'))
                                     if p.is_file() and p.name != 'SHA256SUMS'), encoding='utf-8')
