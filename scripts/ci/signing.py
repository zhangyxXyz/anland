"""Shared local/CI signing configuration, with pinned upgrade certificates."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
CERTIFICATES = json.loads((ROOT / 'signing-certificates.json').read_text())
PRIVATE_FIELDS = ('storePassword', 'keyAlias', 'keyPassword')


def keytool():
    if os.environ.get('JAVA_HOME'):
        return str(Path(os.environ['JAVA_HOME']) / 'bin' / ('keytool.exe' if os.name == 'nt' else 'keytool'))
    return 'keytool'


def config_path(component, directory=None):
    explicit = os.environ.get('ANLAND_SIGNING_CONFIG_FILE')
    if explicit and directory is None:
        return Path(explicit)
    directory = directory or os.environ.get('ANLAND_KEYSTORE_DIR') or ROOT / 'keystore'
    return Path(directory) / f'anland-{component}.json'


def load_config(path):
    path = Path(path).resolve()
    if not path.is_file():
        raise ValueError(f'Missing private signing configuration: {path}')
    config = json.loads(path.read_text(encoding='utf-8-sig'))
    for key in (*PRIVATE_FIELDS, 'storeFile'):
        if not isinstance(config.get(key), str) or not config[key]:
            raise ValueError(f'Missing signing field: {key}')
    store = Path(config['storeFile'])
    if not store.is_absolute():
        store = path.parent / store
    if not store.is_file():
        raise ValueError('Configured keystore does not exist')
    return config, store.resolve()


def verify_key(component, config, store):
    env = dict(os.environ, ANLAND_STORE_PASSWORD=config['storePassword'])
    result = subprocess.run([keytool(), '-exportcert', '-keystore', str(store),
                             '-alias', config['keyAlias'], '-storepass:env', 'ANLAND_STORE_PASSWORD'],
                            env=env, capture_output=True)
    if result.returncode:
        raise ValueError('Cannot read signing certificate; verify the private configuration')
    if hashlib.sha256(result.stdout).hexdigest() != CERTIFICATES[component]:
        raise ValueError(f'{component} signing certificate does not match the existing APK identity')


def restore(component):
    key, raw_config = os.environ.get('SIGNING_KEY', ''), os.environ.get('SIGNING_CONFIG', '')
    if not key or not raw_config:
        raise ValueError('Missing signing key/config Secrets; never generate a replacement CI key')
    config = json.loads(raw_config)
    if set(config) != set(PRIVATE_FIELDS) or not all(isinstance(v, str) and v for v in config.values()):
        raise ValueError('Signing config Secret must contain storePassword, keyAlias and keyPassword')
    directory = Path(os.environ['RUNNER_TEMP']) / f'anland-signing-{component}'
    directory.mkdir(mode=0o700, exist_ok=True)
    store = directory / 'keystore.jks'
    try:
        store.write_bytes(base64.b64decode(''.join(key.split()), validate=True))
        store.chmod(0o600)
        verify_key(component, config, store)
        config['storeFile'] = str(store.resolve())
        path = directory / 'signing.json'
        path.write_text(json.dumps(config), encoding='utf-8')
        path.chmod(0o600)
        with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as output:
            output.write(f'ANLAND_SIGNING_CONFIG_FILE={path}\n')
    except Exception:
        shutil.rmtree(directory)
        raise


def sync_secrets(directory, repository):
    # Validate both identities before writing any remote configuration.
    configs = []
    for component in CERTIFICATES:
        config, store = load_config(config_path(component, directory))
        verify_key(component, config, store)
        configs.append((component, config, store))
    for component, config, store in configs:
        values = {
            f'ANLAND_{component.upper()}_KEYSTORE_BASE64': base64.b64encode(store.read_bytes()).decode(),
            f'ANLAND_{component.upper()}_SIGNING_CONFIG': json.dumps({k: config[k] for k in PRIVATE_FIELDS}),
        }
        for name, value in values.items():
            # Secrets go through stdin, never command arguments or log output.
            subprocess.run(['gh', 'secret', 'set', name, '--repo', repository],
                           input=value, text=True, check=True)
        print(f'Synced {component} signing Secrets; certificate verified')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('component', choices=(*CERTIFICATES, 'sync'))
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--keystore-dir')
    parser.add_argument('--repo', default='zhangyxXyz/anland')
    args = parser.parse_args()
    if args.component == 'sync':
        sync_secrets(args.keystore_dir, args.repo)
    elif args.check:
        config, store = load_config(config_path(args.component, args.keystore_dir))
        verify_key(args.component, config, store)
        print(f'Verified {args.component} signing certificate')
    else:
        restore(args.component)
