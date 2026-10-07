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
- Selective downloads and storage budgets are configurable; existing downloaded titles remain available until explicitly removed.
- Active extraction revisions are protected by reader leases. Unused revisions and abandoned temporary downloads are cleaned at the next library operation; verify concurrent open/update/removal on devices.
- No vendor-specific refresh controls; some branded key codes may require device-specific mapping.
- Reader typography can be affected by publisher CSS. Hardware/device tests and actual Nextcloud validation remain mandatory before release.

## Read/unread and fixed-layout comics

- Each library card has “Als gelesen markieren” / “Als ungelesen markieren”. Verify the checkmark and Reading tab update immediately, including offline; the saved reading position must not move.
- Sync two devices: change status on one, continue reading or favorite on the other, then sync both. Status, bookmark and favorite must merge independently. Explicit unread must remain unread even with a bookmark at 100%.
- Open an EPUB with `rendition:layout=pre-paginated` or numeric viewport width/height. Artwork and positioned text should scale together, centered with the whole page visible on phone and BOOX Go 6, including landscape and sleep/wake.
- Fixed pages deliberately preserve publisher fonts/colors and use one page at a time. Novel font settings and two-column layout apply only to reflowable content.
- Verify last-page forward taps keep the last page visible; backward taps on the first page keep the first page visible.
- CI runs the production injected JavaScript in Chromium with synthetic layered comic and novel fixtures; JVM tests cover OPF layout overrides and status conflict resolution. The reported comic still needs verification with its actual EPUB on Android WebView.

## Follow-up: legacy comics and queued sync

- Test a converted comic without OPF/viewport layout hints, with a CSS-sized page and positioned text. CI covers this structure plus an ordinary inline illustration that must remain reflowable.
- In the reader menu, open Aa → Darstellung für dieses Buch → Originalseite / Comic. Switch between this, Automatisch and Fließtext; verify the document reloads with its original styles and retains its bookmark. Reopen the book to verify the choice persists; another book must keep its own setting. This override is local to the device.
- Enqueue sync offline, then reconnect. Queued work must show a static explanation, not a running spinner; the sync button must remain available.
- With a metered connection (including VPNs Android classifies as metered), enable “Nur ungetaktete Netzwerke”, queue sync, then disable the setting. The pending request and periodic constraints must update without clearing app data; active downloads must not be interrupted. The restriction is never bypassed automatically.
- Simulate a transient server failure: retry/backoff must not be labeled “waiting for network”. Manual retry should replace queued work using the current settings.

## New regression and device flows

- Install the release APK over version 23 without clearing data. Confirm signature and increasing versionCode, Room migrations, old EPUB access and old progress JSON.
- Enable/disable automatic downloads. Download one undistributed title from every tab; it must open after success. Remove its local copy, retain progress and re-download.
- Rename/move a book after catalog creation; sync another upgraded device and confirm the same progress ID. Copying a file must create an independent file identity.
- Open Nextcloud browser login (including 2FA and email login). Cancel, retry, renew the password for the same bound account and reject a different account.
- Test a read-only progress folder. Setup must report write failure. Probe cleanup must never delete a preexisting file.
- Read offline, save, stop/restart the process, reconnect and confirm durable delivery. Edit while upload is in flight; only the confirmed revision becomes nonpending.
- Jump via EPUB nav/NCX and same-/cross-chapter footnotes; return to the prior position. Restore history/bookmarks without resetting favorite or completion.
- Test Zoom with D-pad focus, moved controls, manual crop progression, reset, close/reopen and chapter change. Verify one actual page turn per gesture.
- Export progress using Android SAF and reimport; confirm independent favorite/completion and no credentials in the JSON. Import local EPUB and CBZ via file picker and Nextcloud “Open with”.
- Save a novel on one device, open on another with a different font/viewport and check the saved word's monochrome frame. Change font/orientation while the marker is shown; it must stay with that word without moving the reading position. The first page-turn removes it, and opening a menu or selecting text does not. Accept newer remote progress and repeat. Image-only/legacy-anchor pages show “Hier weiterlesen”; changed editions must not highlight an unrelated old character offset.

## Kompakte Bedienoberfläche

Compose/Robolectric prüft die Buchaktionen ohne dauerhafte Löschbuttons oder Fehlerabsätze, die sechs Einstellungen-Kategorien auf 360 dp Breite sowie die Höhe der Lesemenüleisten und bestätigte Kapitelsprünge. Screenshots der tatsächlichen Compose-Komponenten liegen im CI-Bericht unter `ui/`.

Auf BOOX zusätzlich prüfen: Bibliothek zeigt im Ruhezustand nur Werkzeugleiste und Tabs; Suche lässt sich schließen; Filter zurücksetzen; Buchmenü öffnen, Fehlerdetails lesen und lokale Entfernung abbrechen. Einstellungen-Unterseiten öffnen und mit Zurück zur Übersicht wechseln; Nextcloud erneut anmelden/testen. Im Reader Kapitelwahl öffnen/abbrechen und Lesezeichen-/Verlauf-Tabs prüfen.

## Buchstart und Textseiten-Geometrie

ReaderLifecycleTest prüft Abgleich-/Verlaufsaufrufe vor und während der Buchinitialisierung, Wiederaufnahme des unveränderten Lesestands und das Zurückweisen unvollständiger IDs vor Netzwerkzugriffen. Chromium prüft höher spezifisches und inline-important Buch-CSS bei 360/401/709 Pixeln, unterschiedliche Ränder, Blättern bis zur letzten Seite und eine leere rechte Seite des letzten Spreads. Textinhalt, kursiver Satz, Auswahl und Wortanker müssen erhalten bleiben.

Die Bildschirmaufnahme des Nutzers zeigt die Symptome; ohne die betroffene EPUB-Datei ist deren genaue CSS-Konstellation nicht bekannt. Auf dem Gerät denselben Titel öffnen, mehrfach vor/zurück blättern und die letzte Seite sowie Schrift-/Randwechsel prüfen.
