"""CI integration test with a disposable test key. Test APKs are never uploaded."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile

spec = importlib.util.spec_from_file_location('sign_apk', 'scripts/sign-apk.py')
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)
apk = Path('app/build/outputs/apk/debug/app-debug.apk')

with tempfile.TemporaryDirectory(dir=os.environ.get('RUNNER_TEMP')) as directory:
    root = Path(directory)
    key = root / 'test.p12'
    password = secrets.token_urlsafe(32)
    env = dict(os.environ, TEST_KEY_PASSWORD=password)
    subprocess.run(['keytool', '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12',
                    '-storepass:env', 'TEST_KEY_PASSWORD', '-keypass:env', 'TEST_KEY_PASSWORD',
                    '-alias', 'test', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2',
                    '-dname', 'CN=Disposable CI Test', '-noprompt'], env=env, check=True, capture_output=True)
    cert = subprocess.run(['keytool', '-exportcert', '-keystore', str(key),
                           '-storepass:env', 'TEST_KEY_PASSWORD', '-alias', 'test'],
                          env=env, check=True, capture_output=True).stdout
    fingerprint = hashlib.sha256(cert).hexdigest()
    pin = root / 'certificate.sha256'
    pin.write_text(fingerprint)
    os.environ.pop('FOLIO_SIGNING_BUNDLE', None)
    try:
        signing.sign(apk, root / 'missing.apk', pin)
        raise AssertionError('Missing key was accepted')
    except RuntimeError as error:
        assert 'Set repository Actions secret' in str(error)
    assert not (root / 'missing.apk').exists()
    os.environ['FOLIO_SIGNING_BUNDLE'] = json.dumps({
        'keystore': base64.b64encode(key.read_bytes()).decode(), 'password': password, 'alias': 'test',
    })
    wrong = root / 'wrong.sha256'
    wrong.write_text('0' * 64)
    try:
        signing.sign(apk, root / 'wrong.apk', wrong)
        raise AssertionError('Wrong key was accepted')
    except RuntimeError as error:
        assert 'does not match' in str(error)
    assert not (root / 'wrong.apk').exists()
    for name in ['first', 'second']:
        output = root / f'{name}.apk'
        signing.sign(apk, output, pin)
        assert f'Certificate SHA-256: {fingerprint}' in output.with_suffix('.signing.txt').read_text()
    os.environ.pop('FOLIO_SIGNING_BUNDLE', None)
print('Signing checks passed: missing/wrong keys blocked; two APKs verified with the same certificate.')
