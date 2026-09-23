# Stable signing for installed APKs

The former CI debug APKs used fresh runner-generated keys and could not update each other.
There is one final reinstall when moving from those APKs to the permanent certificate.
All subsequent distribution APKs must keep application ID `de.folio.reader` and this certificate:

`daa8b9dfa2519f316dbb65aab43d95181e19e65d8ea739e0d60422d2fef97020`

## One-time setup (phone browser works)

1. Open repository Settings → Secrets and variables → Actions → New repository secret.
2. Name: `FOLIO_SIGNING_BUNDLE`.
3. Value: the entire contents of the privately supplied `FOLIO_SIGNING_BUNDLE.txt` backup, including braces. It contains the private keystore, password and alias; never paste it into an issue, commit or build log.
4. Save, then rerun the failed build. Download **folio-apk**, containing `folio.apk` and its public signing/build details.

Keep the private backup: this is the key for future updates. Restore the same secret if it is lost; do not generate a replacement. The GitHub connector cannot manage repository secrets, so the owner must perform this one-time step.

## Build guarantees

- Tests may use temporary debug certificates, but their APKs are never distributed.
- Distribution signing uses only the Actions secret. Missing, malformed or different keys stop publication. No fallback to a new debug key.
- The public certificate is pinned in `signing/certificate.sha256` and checked both before signing and on the resulting APK using Android's `apksigner`.
- Signing occurs after compilation; private signing material is not available to Gradle/dependencies. Temporary key files are removed when the signing process exits.
- Fork pull requests run tests but do not receive the secret or publish APKs. Repository-owned PRs are trusted build code; review workflow/script changes accordingly.
- `versionCode` comes from this workflow's increasing `github.run_number`; reruns retain the same code. Keep `.github/workflows/buildapk.yml` and its counter. If moving workflows, set a version offset above the highest version already distributed.
- The debug variant remains the current testing build. Future release variants must use the same distribution signing script/key and a higher version code to preserve update compatibility.
- CI signs and verifies two disposable APKs with one test key, and checks rejection of missing/wrong keys. Those test keys and APKs are not uploaded.

Do not reinstall the latest old **app-debug** artifact expecting future compatibility. Wait for the first successful **folio-apk** build with the permanent certificate.
