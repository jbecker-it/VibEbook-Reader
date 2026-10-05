# Umsetzung der App-Prüfung vom 5. Oktober 2026

Die Änderungen sind auf dem bestehenden Draft-PR #4. Main wird nicht automatisch zusammengeführt. Signaturschlüssel und Paketkennung bleiben erhalten; Release-Builds verwenden den fortlaufenden Versionszähler.

## Abgedeckte Befunde

| Audit | Änderung | Automatisierte Prüfung |
| --- | --- | --- |
| 1 | `_pending` und Fortschritt gemeinsam atomar gespeichert; separater WorkManager-Job mit Netzwerkbedingungen und Retry; veraltete Uploadbestätigung löscht keine neue Änderung | Neustart und konkurrierende Bestätigung im ProgressRepositoryTest; HTTPS-Konflikttests |
| 2 | NavHost mit eigener Reader-Lebensdauer, fortlaufender Buchbeobachtung und Übernahmeangebot für neueren externen Stand | ReaderLifecycleTest: schließen/neu öffnen und passive Positionsberichte |
| 3 | Diskfehler abgefangen; dirty-Zustand erst nach erfolgreichem Schreiben im Main-Dispatcher bestätigt; Aktionen mit Fehleranzeige | ReaderLifecycleTest: fehlschlagendes Schreiben und erneuter Versuch |
| 4 | Vollständige Inventarisierung vor Remote-Abwesenheit; Fortschritte vor Downloads; Fehlerisolierung je Lesestand und Download | HTTPS-Tests; individuelle Fehlerpfade in Repository |
| 5 | Budget 128–16384 MB, standardmäßig 2048 MB; selektiver Download, lokale Entfernung, Schutz aktiver Extraktionen und Bereinigung alter Revisionen/temporärer Dateien | Entpacklimit- und lokale Dateientfernungstests |
| 6 | ZIP- und Metadatenpfade innerhalb einer Extraktion; aktive HTML-/SVG-Inhalte entfernen; CSP; WebView auf aktuelle Buchrevision begrenzen | EpubSafetyTest und Grenzen für ZIP-/XML-/Kapitelgrößen |
| 7 | Schema 5 mit separaten Revisionszählern/Gerätekennungen; deterministische Gleichstände; Zukunftsprüfung und Verlauf | Merge in beiden Reihenfolgen; bestehende Schema-3/4-Tests |
| 8 | Erfolgreicher Selbsteintrag erforderlich; alle erfolgreichen propstat-Blöcke zusammengeführt | leeres Multistatus und gesperrter Selbsteintrag abgelehnt |
| 9 | Eigene zufällige Probe mit If-None-Match, GET und DELETE; alte Testergebnisse bei Änderungen verwerfen; gebundene Felder sperren | HTTPS-Probe prüft Methoden, Bedingung und identischen Löschpfad |
| 10 | lokale Anzeige zuerst; per Buch getrennte Sync-Sperren; Abbruch während Body-Lesen/Entpacken | langsamer HTTP-Body unter Timeout |
| 11 | alle Progress-Lesewege über AtomicFile und gemeinsamen Mutex | Wiederherstellung aus `.bak`; beschädigte Datei isoliert |
| 12 | Nextcloud `oc:id` in gemeinsamem `catalog.json` auf bestehende Progress-ID abgebildet; Pfad-Aliase; Inhaltsrevision und Kapitelpfad; Ausgabewechsel sichtbar | Register behält ID nach Umbenennung |
| 13 | E-Ink ohne laufende Spinner/Navigationstransitionen; deckende Cover-Schalter mit Rand; Statusbereich mit fester Höhe | Geräteprüfung bleibt nötig |
| 14 | EPUB-3-nav, EPUB-2-NCX, interne Links mit Fragmenten (auch Fußnotendokumente außerhalb der Spine); externe Links nach Bestätigung im Browser; Rücksprung zur vorherigen Stelle | verschachtelte nav/NCX-Fixtures und Chromium-Pagination |
| 15 | Laden-und-Öffnen in allen Tabs; letzte Öffnung separat; ausschließlich manueller Gelesen-Status; Zoom-Fokus, verschiebbarer Regler, gespeicherte Stufe und manuelle Ausschnittfolge | Reader- und Produktions-JavaScript-Tests |
| 16 | minimierte Release-APK; derselbe permanente Schlüssel; direkte GitHub Releases und manuelle Updateprüfung; Room-Schemaexport und Migrationen 1/2/3 → 4; AGP 8.6.1 mit Navigation 2.8.9 und vorhandenem Gradle 8.9 (Lint-API-Kompatibilität) | CI: JVM/Android-Ressourcentests, Lint Release, Chromium, Build und Signierprüfungen |

## Weitere Bedienfunktionen

Suche über Titel, Autor und Pfad; Sortierung nach Titel, letzter Öffnung/Lesestand und Hinzufügung; Filter für offline und gelesen/ungelesen. Ordner und Tab werden in den Einstellungen gespeichert; Grid-Scrollzustände über Compose SaveableState gehalten. Favoriten-/Lesestatusaktionen bieten Rückgängig. Schrift, Rand, Zeilenabstand, Layout und Zoom lassen sich lokal je Buch merken. Lesezeichen und letzte Positionen sind vom aktuellen Lesestand unabhängig; Android-Textauswahl wird beim Blättern respektiert.

Nextcloud Login Flow v2 öffnet den normalen Browser, fragt einen kurzlebigen Token ab und löst den echten DAV-Benutzer über OCS auf. App-Passwörter bleiben verschlüsselt; das normale Kontopasswort wird von Folio nicht abgefragt. Poll-/Login-/Rückgabe-Server müssen dieselbe HTTPS-Herkunft haben. Native Nextcloud-Favoriten sind optional und folgen den Folio-Metadaten; externe Änderungen der Nextcloud-Markierung sind keine zusätzliche Konfliktquelle.

EPUB-/CBZ-Dateien können über die Android-Dateiauswahl oder „Öffnen mit“ importiert werden. Lokale Importe bleiben ausdrücklich lokal und bekommen eine Inhaltsidentität. Backupexport/-import enthält Lesestände, Favoriten und Lesestatus, keine Zugangsdaten oder Signierschlüssel. Lesezeichen und Darstellungsoptionen sind derzeit lokale Einstellungen.

## Kompatibilität und Grenzen

- Vorhandene `.folio-progress/<id>.json` bleiben unter ihrer ID verwendbar. Das zusätzliche `catalog.json` wird mit ETag/If-None-Match geschützt. Gemeinsame Dateiregister sind auf 10000 Einträge begrenzt. Server ohne `oc:id` verwenden weiterhin den Pfad; dann ist Umbenennung keine stabile Identität.
- Ein Datei-ID-/Registerwechsel auf einem schon aktualisierten Gerät führt vorhandenen lokalen Progress unter der kanonischen ID zusammen. Umbenennungen, die bereits vor der ersten Erstellung des Registers auf allen Geräten geschehen sind, lassen sich ohne frühere Server-ID-Zuordnung nicht sicher rekonstruieren.
- Fortschrittsschema 1–4 wird gelesen, Schema 5 geschrieben. Alle beteiligten Geräte sollten die neue Version erhalten, damit alte Apps bei gleichen Zeitstempeln nicht ihre frühere Konfliktregel anwenden.
- Eine Bibliothek bleibt an Server, DAV-Konto, Bücher- und Fortschrittsordner gebunden. Passworterneuerung und Browser-Neuanmeldung für dasselbe Konto sind erlaubt. Ein Kontowechsel ist keine implizite Datenmigration.
- Entpacken: höchstens 20000 Einträge, 256 MB je Datei und insgesamt innerhalb des verfügbaren Budgets; Metadaten bis 4 MB, Container bis 1 MB, lesbare HTML-/SVG-Ressourcen bis 16 MB. Mindestens 64 MB freier Speicher bleiben für andere App-Arbeit reserviert.
- Hintergrundarbeit unterliegt Androids Netzwerk-, Akku- und Scheduler-Regeln. Die dauerhafte Vormerkung überlebt Prozessneustart; ein hartes Beenden vor der lokalen Speicherung kann nicht rückwirkend Daten sichern.
- Manuelle Comic-Ausschnitte sind rechteckige, überlappende Bereiche, keine automatische Panel-Erkennung. PDF benötigt einen eigenen Renderer und gehört nicht zu diesem EPUB-/CBZ-Ausbau.
- Tests mit synthetischen Dateien und Mockservern belegen keine tatsächliche BOOX-/Nextcloud-Gerätevalidierung. Siehe TESTING.md.

## Prüfergebnis

Der erste Release-Durchlauf (Version 25, Commit ffe3b88799480a9d58fbe8908b5402d6a8ca90c1) bestand 43 Tests ohne Fehler oder übersprungene Fälle, Chromium-Regression, Release-Lint, minimierten Build, Signierprüfungen und direkte Release-Veröffentlichung. Die zuletzt ergänzten Lebenszyklus-/Störfalltests werden im abschließenden CI-Durchlauf zusätzlich geprüft; das finale Ergebnis steht im PR.

Der Version-25-Lintbericht enthält drei übersprungene Navigation-Prüfungen. Navigation dokumentiert einen Fix ab 2.8.3 für AGP 8.4+ und weiterhin eine Inkompatibilität zu Lint 16/AGP 8.7. Die Abschlussversion verwendet daher Navigation 2.8.9 mit AGP 8.6.1; die Lint-Warnungen werden ausdrücklich erneut ausgewertet, statt die Checks zu deaktivieren. Andere Versionshinweise werden nicht pauschal als App-Defekt gewertet.
