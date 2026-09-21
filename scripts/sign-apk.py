"""Sign distribution APKs with the backed-up key; fail closed on missing/changed keys."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def run(args, env):
    result = subprocess.run(args, env=env, capture_output=True)
    if result.returncode:
        # Do not copy subprocess diagnostics or secret JSON into public build logs.
        raise RuntimeError(f"{Path(args[0]).name} failed; check signing configuration")
    return result.stdout


def sign(apk, output, expected_file):
    raw = os.environ.get("FOLIO_SIGNING_BUNDLE", "")
    if not raw:
        raise RuntimeError("Set repository Actions secret FOLIO_SIGNING_BUNDLE. No APK will be published without the permanent key.")
    try:
        bundle = json.loads(raw)
        key = base64.b64decode(bundle["keystore"], validate=True)
        password, alias = bundle["password"], bundle["alias"]
        assert isinstance(password, str) and password and isinstance(alias, str) and alias
    except (ValueError, KeyError, TypeError, AssertionError):
        raise RuntimeError("Invalid signing bundle; paste the entire backup file into the Actions secret") from None
    expected = expected_file.read_text().strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise RuntimeError("Invalid pinned signing certificate")
    sdk = Path(os.environ["ANDROID_HOME"])
    versions = [p for p in (sdk / "build-tools").iterdir() if re.fullmatch(r"\d+\.\d+\.\d+", p.name)]
    build_tools = max(versions, key=lambda p: tuple(map(int, p.name.split('.'))))
    apksigner = str(build_tools / "apksigner")
    env = {k: v for k, v in os.environ.items() if k != "FOLIO_SIGNING_BUNDLE"}
    env["FOLIO_KEY_PASSWORD"] = password
    with tempfile.TemporaryDirectory(dir=os.environ.get("RUNNER_TEMP")) as tmp:
        store = Path(tmp) / "folio.p12"
        store.write_bytes(key)
        store.chmod(0o600)
        cert = run(["keytool", "-exportcert", "-keystore", str(store),
                    "-storepass:env", "FOLIO_KEY_PASSWORD", "-alias", alias], env)
        if hashlib.sha256(cert).hexdigest() != expected:
            raise RuntimeError("Signing key does not match the pinned certificate; refusing an incompatible APK")
        signed = Path(tmp) / "folio.apk"
        run([apksigner, "sign", "--ks", str(store), "--ks-key-alias", alias,
             "--ks-pass", "env:FOLIO_KEY_PASSWORD", "--key-pass", "env:FOLIO_KEY_PASSWORD",
             "--v4-signing-enabled", "false", "--out", str(signed), str(apk)], env)
        report = run([apksigner, "verify", "--print-certs-pem", str(signed)], env).decode()
        # Human-readable signer labels vary between build-tools versions; PEM is stable.
        certificates = re.findall(r"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----", report, re.S)
        fingerprints = [hashlib.sha256(base64.b64decode(re.sub(r"\s+", "", cert), validate=True)).hexdigest()
                        for cert in certificates]
        if fingerprints != [expected]:
            raise RuntimeError(f"Final APK signing certificate verification failed (public fingerprints: {fingerprints})")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(signed.read_bytes())
        output.with_suffix('.signing.txt').write_text(
            f"Certificate SHA-256: {expected}\nAPK SHA-256: {hashlib.sha256(output.read_bytes()).hexdigest()}\n"
            f"Version code: {os.environ.get('FOLIO_VERSION_CODE', 'local')}\n"
            f"Commit: {os.environ.get('GITHUB_SHA', 'local')}\n")
    print(f"Verified permanent signing certificate: {expected}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--certificate", type=Path, default=Path("signing/certificate.sha256"))
    args = parser.parse_args()
    try:
        sign(args.input, args.output, args.certificate)
    except (RuntimeError, OSError, KeyError, ValueError) as error:
        raise SystemExit(str(error)) from None
