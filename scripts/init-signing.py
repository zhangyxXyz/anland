"""Explicitly initialize one repository key; normal builds never generate keys."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]
directory = ROOT / 'keystore'
store = directory / 'anland-release.jks'
configs = [directory / f'anland-{component}.json' for component in ('shell', 'wayland')]
if store.exists() or any(p.exists() for p in configs):
    raise SystemExit('Signing material already exists. Reuse it; initialization will not overwrite it.')
directory.mkdir(exist_ok=True)
password = secrets.token_urlsafe(36)
env = dict(os.environ, ANLAND_INIT_PASSWORD=password)
tool = str(Path(env['JAVA_HOME']) / 'bin' / ('keytool.exe' if os.name == 'nt' else 'keytool'))
subprocess.run([tool, '-genkeypair', '-keystore', str(store), '-storetype', 'JKS',
                '-alias', 'anland', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
                '-dname', 'CN=Anland', '-storepass:env', 'ANLAND_INIT_PASSWORD',
                '-keypass:env', 'ANLAND_INIT_PASSWORD'], env=env, check=True, capture_output=True)
config = dict(storeFile=store.name, storePassword=password, keyAlias='anland', keyPassword=password)
for path in configs:
    path.write_text(json.dumps(config, indent=2) + '\n', encoding='utf-8')
    path.chmod(0o600)
store.chmod(0o600)
certificate = subprocess.check_output([tool, '-exportcert', '-keystore', str(store),
                                        '-alias', 'anland', '-storepass:env', 'ANLAND_INIT_PASSWORD'], env=env)
fingerprint = hashlib.sha256(certificate).hexdigest()
(ROOT / 'signing-certificates.json').write_text(
    json.dumps(dict(shell=fingerprint, wayland=fingerprint), indent=2) + '\n', encoding='utf-8')
print('Created private repository signing material in keystore/. Back up this ignored directory.')
print('Public certificate SHA256:', fingerprint)
