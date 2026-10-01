"""Add hashes of compiled files to the input provenance inside the build stage."""
import hashlib
import json
from pathlib import Path

out = Path('/out')
manifest = json.loads(Path('/src/rootfs-inputs.json').read_text())
files = {'Xwayland': out / 'Xwayland', 'anland-miniwm': out / 'anland-miniwm',
         'anland-session': out / 'anland-session', 'xfdesktop': out / 'xfdesktop-root/usr/bin/xfdesktop',
         'desktop-ime': out / 'libanland-desktop-ime.so'}
manifest['patched_files'] = {name: hashlib.sha256(path.read_bytes()).hexdigest()
                             for name, path in files.items()}
manifest['xfdesktop_files'] = {p.relative_to(out / 'xfdesktop-root').as_posix():
                              hashlib.sha256(p.read_bytes()).hexdigest()
                              for p in sorted((out / 'xfdesktop-root').rglob('*')) if p.is_file()}
(out / 'rootfs-components.json').write_text(json.dumps(manifest, indent=2) + '\n')
