# Folio / VibEbook Reader

Offline-EPUB-Reader für Android 8+ mit Nextcloud als einziger Remote-Quelle.
Kotlin, Compose, Room, DataStore, Hilt, WorkManager, OkHttp und WebView.

## Einrichtung

1. In Nextcloud unter **Persönliche Einstellungen → Sicherheit** ein App-Passwort erstellen.
2. In Folio die HTTPS-Serveradresse (inklusive eines optionalen Installations-Unterpfads), den von Nextcloud angezeigten Benutzernamen und das App-Passwort eintragen.
3. Bücherordner relativ zu deinen Nextcloud-Dateien wählen, z. B. `Books`. Leer bedeutet alle Dateien. Fortschrittsordner: `.folio-progress`.
4. Verbindung testen, speichern, dann in der Bibliothek synchronisieren.

Der Verbindungstest liest den Bücherordner, verändert aber keine gespeicherten Einstellungen.
Die Fortschrittsablage benötigt Schreibrechte; entsprechende Fehler erscheinen beim Sync.
Nur HTTPS mit gültigem Zertifikat wird akzeptiert. Bei einer Weiterleitung muss die endgültige Serveradresse eingetragen werden. Öffentliche Freigabelinks und E2E-verschlüsselte Ordner werden nicht unterstützt.

## Offline und Synchronisierung

- EPUB-Dateien werden rekursiv gefunden und vollständig lokal heruntergeladen.
- ETags erkennen Änderungen; Downloads werden temporär geschrieben und erst nach erfolgreichem Abschluss übernommen.
- Fehlende Remote-Bücher bleiben lokal erhalten und erhalten die Kennzeichnung „Nur lokal“.
- Fehlerhafte oder unvollständige Verzeichnisantworten brechen den Scan ab. Kein automatisches Löschen lokaler Bücher.
- Lesepositionen und Favoriten liegen als JSON-Dateien im Fortschrittsordner. Feldweise Zeitstempel entscheiden bei Konflikten, HTTP-Bedingungen verhindern unbemerkte konkurrierende Überschreibungen. Nach einem Konflikt erneut synchronisieren.
- Positionen bestehen aus Kapitelindex, Zeichenoffset und einem Kapitelanteil als Fallback.
- Die Buch-ID bleibt der Hash des relativen Bücherpfads. Alle Geräte müssen denselben Bücherwurzelordner verwenden. Umbenennen oder Verschieben verändert die Identität.
- Hintergrundabgleich alle sechs Stunden, zusätzlich manuell und bei Fortschrittsänderungen. WorkManager kann durch Android verzögert werden.
- Lokale Bücher können ohne konfigurierte Cloud-Verbindung geöffnet werden.

## Upgrade

Die frühere Netzwerkfreigaben-Anbindung ist entfernt. Ein DataStore-Migrationsschritt löscht deren alte Verbindungswerte, nicht die Leseeinstellungen. Room wird ohne destruktiven Fallback von Version 1/2 auf 3 aktualisiert. Lokale Bücher und Fortschritte bleiben erhalten.

Bei der ersten Nextcloud-Einrichtung dieselbe relative Ordnerstruktur verwenden. Vorhandene Fortschritts-JSON-Dateien können manuell in den Nextcloud-Fortschrittsordner kopiert werden; es gibt keinen automatischen Server-zu-Server-Transfer.

App-Passwörter werden mit einem Android-Keystore-Schlüssel (AES-GCM) verschlüsselt. Nach Wiederherstellung auf einem anderen Gerät ist eine erneute Anmeldung erforderlich. Passwörter nicht in Fehlerberichte aufnehmen.

## E-Reader

- E-Ink-Modus: Schwarz auf Weiß, keine Seiten-/Menüanimation, keine Material-Ripples.
- Tippen links/rechts blättert, Mitte öffnet das Menü. Linkshändige oder breitere Tap-Zonen sind in den Einstellungen wählbar.
- Page-Up/Down und Steuerkreuz links/rechts blättern. Optional funktionieren Lautstärketasten als Seitentasten. Menü oder Steuerkreuz-Mitte öffnet das Menü.
- Schriftgröße, Serif/Sans-Serif, Zeilenabstand, Ränder, Orientierungssperre und eingeschaltetes Display sind einstellbar.
- „Weiterlesen“ öffnet das zuletzt begonnene, lokal verfügbare Buch.
- Die Bibliothek enthält Ordner, „Lese ich“ und Favoriten. Herz-Ziele sind mindestens 48 dp groß.

Herstellerspezifische Refresh-APIs sind nicht enthalten. Tastenbelegung, Reaktionszeit, Ghosting und Schlaf/Wiederaufnahme müssen auf dem konkreten E-Ink-Gerät geprüft werden. Die EPUB-Paginierung bleibt WebView-basiert; Bücher mit stark erzwungenem CSS können abweichend aussehen.

## Entwicklung und Prüfung

JDK 17, Android SDK 34 und Gradle-Wrapper:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions führt diese Prüfungen aus und stellt die Debug-APK als Artefakt bereit.
Tests decken URL-Encoding, Unterpfad-Installationen, grundlegende WebDAV-Parsing-Sicherheit, Fortschritts-Merge und IDs ab. Reale Nextcloud-, Keystore-, Room-Upgrade- und Geräteprüfungen bleiben zusätzlich erforderlich.

WebDAV-Protokollreferenz: https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/basic.html
