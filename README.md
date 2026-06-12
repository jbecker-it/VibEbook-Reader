# Folio – NAS E-Book-Reader für Android

Ein E-Book-Reader (EPUB), der seine Bibliothek per SMB von einem NAS bezieht, alle
Bücher offline verfügbar hält und den Lesefortschritt geräteübergreifend über das NAS
abgleicht. Optimiert für Smartphones, Foldables und Tablets, mit echtem AMOLED-Modus.

## Die vier Schwerpunkte

**Skalierbar auf Foldable und Tablet.** Das Layout richtet sich nach der
`WindowSizeClass`. Schmal (Phone, gefaltetes Foldable) → eine Spalte: Bibliothek bzw.
Reader im Vollbild. Mittel/breit (Tablet, aufgeklapptes Foldable) → zwei Spalten:
Bibliotheksliste links, Reader rechts. Das Buch-Grid nutzt `GridCells.Adaptive` und füllt
jede Breite sinnvoll. Siehe `ui/FolioApp.kt`.

**NAS per SMB, Offline-Sync.** `data/smb/SmbClient.kt` kapselt die SMB2/3-Anbindung
(Bibliothek `smbj`). Beim Synchronisieren werden alle `.epub`-Dateien rekursiv gefunden,
heruntergeladen, lokal entpackt (`data/epub/EpubParser.kt`) und in einer Room-Datenbank
registriert. Danach sind die Bücher vollständig offline lesbar. Der Abgleich läuft über
`WorkManager` (`data/sync/`), wahlweise nur über WLAN.

**Geräteübergreifender Lesefortschritt.** Pro Buch wird eine eigene JSON-Datei
geführt (`<id>.json`), lokal unter `filesDir/progress/` gespeichert und bei nächster
Gelegenheit per SMB in den konfigurierten Fortschritts-Ordner auf dem NAS geschrieben.
Die Buch-ID ist ein Hash des relativen NAS-Pfads – dasselbe Buch erhält auf jedem Gerät
dieselbe ID und damit dieselbe Fortschrittsdatei. Konflikte werden per Last-Write-Wins
über den Zeitstempel aufgelöst (`domain/model/ReadingProgress.kt`,
`data/progress/`, `data/repository/BookRepository.kt`). Beim Öffnen eines Buches wird der
Fortschritt zuerst mit dem NAS abgeglichen, sodass man nahtlos auf einem anderen Gerät
weiterliest.

**AMOLED-Modus.** Reines Schwarz (#000000) als Hintergrund, weißer Text – spart auf
OLED-Displays Strom und maximiert den Kontrast. Der Modus gilt sowohl für die App-Oberfläche
(`ui/theme/`) als auch für den Buchinhalt: Im Reader wird passendes CSS in den WebView
injiziert (`ui/reader/ReaderScreen.kt`). Wählbar in den Einstellungen neben Hell, Dunkel und
„System".

## Projekt öffnen und bauen

1. Mit **Android Studio** (Koala/2024.1+ empfohlen) den Ordner `folio-reader` öffnen.
2. Gradle-Sync ausführen lassen. Studio lädt alle Abhängigkeiten von Google Maven und Maven
   Central. (Beim ersten Sync ggf. die im Catalog gepinnten Versionen an die installierte
   Toolchain anpassen, falls Studio neuere AGP/Kotlin-Versionen verlangt.)
3. Auf einem Gerät/Emulator mit **Android 8.0 (API 26)** oder neuer starten.

Ohne Android Studio per Kommandozeile (JDK 17 vorausgesetzt):

```
./gradlew assembleDebug
```

`local.properties` wird von Android Studio mit dem SDK-Pfad erzeugt; bei reinem
CLI-Build manuell anlegen: `sdk.dir=/pfad/zum/Android/Sdk`.

## Erste Schritte in der App

1. Oben rechts auf **Einstellungen**.
2. Unter **NAS-Verbindung** Host/IP, Freigabename und Zugangsdaten eintragen. Optional einen
   Unterordner angeben, der die Bücher enthält. **Verbindung testen**, dann **Speichern**.
3. Zurück in der Bibliothek auf **Synchronisieren**. Die Bücher werden geladen und stehen
   danach offline bereit.
4. Theme (inkl. AMOLED) in den Einstellungen wählen.

## Architektur (Kurzüberblick)

```
ui/            Compose-Oberfläche (adaptive Shell, Bibliothek, Reader, Einstellungen)
domain/model/  Datenmodelle (Book, ReadingProgress, SmbSettings, ThemeMode)
data/local/    Room: Buch-Metadaten
data/settings/ DataStore: Einstellungen + stabile Geräte-ID
data/smb/      SMB-Client (smbj)
data/epub/     EPUB entpacken und Spine/Cover/Metadaten lesen
data/progress/ Lokale Fortschrittsdateien + Buch-ID-Ableitung
data/sync/     WorkManager-basierter Hintergrund-Abgleich
data/repository/ Orchestrierung: NAS ⇄ lokal ⇄ Fortschritt
di/            Hilt-Module
```

Stack: Kotlin, Jetpack Compose (Material 3), Hilt, Room, DataStore, WorkManager, smbj,
Coil. EPUB-Kapitel werden als XHTML direkt im WebView gerendert, wodurch relative
Bild- und CSS-Verweise automatisch aufgelöst werden.

## Bekannte Grenzen / Ideen

- Der Renderer ist bewusst schlank (WebView statt vollständiger Paginierungs-Engine);
  fortgeschrittene Funktionen wie seitenweises Blättern, Schriftwahl oder Lesezeichen sind
  als nächste Schritte vorgesehen.
- Bei sehr großen Bibliotheken empfiehlt sich ein selektives statt vollständiges Vorab-
  Herunterladen – die Architektur (Status `downloaded` pro Buch) ist darauf vorbereitet.
- Passwörter liegen in DataStore. Für höhere Sicherheit ließe sich `EncryptedSharedPreferences`
  bzw. der Android Keystore ergänzen.
