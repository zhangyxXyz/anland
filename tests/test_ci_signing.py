import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts/ci'))
import signing


class SigningTests(unittest.TestCase):
    def test_relative_store_is_resolved_beside_private_config(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / 'key.jks').write_bytes(b'fixture')
            path = directory / 'signing.json'
            path.write_text(json.dumps(dict(storeFile='key.jks', storePassword='p', keyAlias='a', keyPassword='p')))
            _, store = signing.load_config(path)
            self.assertEqual(store, (directory / 'key.jks').resolve())

    def test_certificate_mismatch_is_rejected(self):
        result = subprocess.CompletedProcess([], 0, b'another certificate', b'')
        with patch.object(signing.subprocess, 'run', return_value=result):
            with self.assertRaises(ValueError):
                signing.verify_key('shell', dict(storePassword='private', keyAlias='alias'), Path('key.jks'))

    def test_ci_restore_keeps_secrets_out_of_github_env(self):
        cert = b'test certificate'
        config = dict(storePassword='private-password', keyAlias='alias', keyPassword='private-key-password')
        result = subprocess.CompletedProcess([], 0, cert, b'')
        with tempfile.TemporaryDirectory() as temp:
            envfile = Path(temp) / 'github-env'
            env = dict(SIGNING_KEY=base64.b64encode(b'key bytes').decode(), SIGNING_CONFIG=json.dumps(config),
                       RUNNER_TEMP=temp, GITHUB_ENV=str(envfile))
            with patch.dict(os.environ, env), patch.dict(signing.CERTIFICATES, shell=hashlib.sha256(cert).hexdigest()), \
                    patch.object(signing.subprocess, 'run', return_value=result) as command:
                signing.restore('shell')
            exported = envfile.read_text()
            self.assertNotIn('private-password', exported)
            self.assertNotIn('private-key-password', exported)
            self.assertNotIn('private-password', ' '.join(command.call_args.args[0]))
            path = Path(exported.strip().split('=', 1)[1])
            restored, store = signing.load_config(path)
            self.assertEqual(restored['keyPassword'], config['keyPassword'])
            self.assertEqual(store.read_bytes(), b'key bytes')

    def test_missing_secret_does_not_generate_key(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError):
                signing.restore('shell')


if __name__ == '__main__':
    unittest.main()
