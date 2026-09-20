# Pre-release device checklist

CI is necessary, not evidence that these physical-device checks passed.

## Upgrade and privacy

- Install the old app, download books, set reading positions/favorites, then upgrade without clearing data.
- Verify local books open before configuring Nextcloud; confirm old connection preferences are removed and reader preferences survive.
- Test upgrades from database versions 1 and 2. Verify no database/table recreation or local-book deletion occurs.
- Configure the same relative folder layout in Nextcloud; ensure reading progress matches after sync.
- Inspect a debug installation: app password must not appear in plaintext preferences, logs, or backup. Keystore loss must prompt reauthentication without losing local progress.

## Nextcloud

- Test a normal server and a server installed under a URL subdirectory.
- Test spaces, Unicode, plus signs, percent signs and `#` in EPUB/folder names.
- Revoke the app password, remove folder permissions, go offline and interrupt a large download. Existing books must stay readable and errors must be understandable.
- Remove a remote book and perform a complete sync. Its local copy must remain and be labelled local-only.
- Sync two devices concurrently. Read on one and favorite on the other; verify both fields survive after retrying any ETag conflict.
- Change an EPUB without changing its filename. Verify a changed ETag triggers download and a failed extraction leaves the previous copy usable.
- Verify the unmetered-network setting on metered Wi-Fi and cellular networks. Android's UNMETERED constraint is not literally Wi-Fi-only.

## E-reader

- Record device model, Android version, WebView version, and physical key codes.
- Test page buttons, opt-in volume keys, D-pad focus, center/menu button, tap zones, left-handed mode, chapter boundaries and Back behavior.
- Verify no double page turn follows a swipe; holding a button must not skip many pages.
- Change font size/family, line spacing, margins, orientation and single/double layout; confirm the same reading anchor remains visible.
- Sleep/wake, background/foreground, rotate/fold, close/reopen quickly and force-stop after a saved position. Process death before a pending disk write completes cannot be guaranteed.
- Inspect monochrome readability, ghosting and refresh latency. Vendor-specific refresh APIs are not implemented.
- Verify external EPUB resources cannot fetch network content or access credentials through the WebView.

## Known initial-release limits

- One bound library per installation; changing its server/account/root requires a future explicit migration.
- Full download of all discovered EPUBs; no selective-download policy yet.
- Old extraction revisions are retained to avoid deleting files in use. For books missing remotely, the explicit local-removal action removes all their local revisions after confirmation and retains progress. Automatic cleanup for updated books is not implemented.
- No vendor-specific refresh controls; some branded key codes may require device-specific mapping.
- Reader typography can be affected by publisher CSS. Hardware/device tests and actual Nextcloud validation remain mandatory before release.
