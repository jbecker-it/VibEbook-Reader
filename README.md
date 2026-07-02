# Folio – NAS E-Book-Reader für Android

Ein E-Book-Reader (EPUB), der seine Bibliothek per SMB von einem NAS bezieht, alle
Bücher offline verfügbar hält und den Lesefortschritt geräteübergreifend über das NAS
abgleicht. Optimiert für Smartphones, Foldables und Tablets, mit echtem AMOLED-Modus.

## Die vier Schwerpunkte

**Skalierbar auf Foldable und Tablet.** Das Buch-Grid nutzt `GridCells.Adaptive` und
füllt jede Breite sinnvoll – vom Phone über das aufgeklappte Foldable bis zum Tablet.
Der Reader läuft bewusst immer im Vollbild; der Fließtext ist auf eine angenehme
Zeilenbreite begrenzt und mittig gesetzt, sodass auch große Displays gut lesbar bleiben.

**NAS per SMB, Offline-Sync.** `data/smb/SmbClient.kt` kapselt die SMB2/3-Anbindung
(Bibliothek `smbj`). Beim Synchronisieren werden alle `.epub`-Dateien rekursiv gefunden,
heruntergeladen, lokal entpackt (`data/epub/EpubParser.kt`) und in einer Room-Datenbank
registriert. Danach sind die Bücher vollständig offline lesbar. Der Abgleich läuft über
`WorkManager` (`data/sync/`), wahlweise nur über WLAN.

**Geräteübergreifende Metadaten.** Pro Buch wird eine eigene JSON-Datei geführt
(`<id>.json`) mit Leseposition, „fertig gelesen" und Favorit. Sie liegt lokal unter
`filesDir/progress/` und wird bei nächster Gelegenheit per SMB in den konfigurierten
Metadaten-Ordner auf dem NAS geschrieben. Die Buch-ID ist ein Hash des relativen
NAS-Pfads – dasselbe Buch erhält auf jedem Gerät dieselbe ID und damit dieselbe Datei.
Konflikte werden FELDWEISE per Last-Write-Wins aufgelöst: Leseposition/finished und
Favorit tragen je einen eigenen Zeitstempel, sodass Weiterlesen auf Gerät B ein
Favorisieren auf Gerät A nicht überschreibt (`domain/model/ReadingProgress.kt`,
`data/repository/BookRepository.kt`).

Sync-Zeitpunkte: beim Öffnen eines Buches (zieht den neuesten Stand vom NAS), beim
Schließen bzw. bei jeder lokalen Änderung (debounced, sofortiger Abgleich sofern
erreichbar), periodisch alle 6 Stunden über WorkManager sowie manuell über den
Sync-Button. Ist das NAS gerade nicht erreichbar, bleibt die Änderung lokal liegen und
wird beim nächsten Anlass nachgezogen.

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

## Bedienung des Readers

Der Reader ist minimalistisch: Tippen in der **Bildschirmmitte** blendet das Menü ein
und aus (Titel, Favoriten-Herz, Kapitel-Slider, Prozentanzeige). Tippen **rechts**
blättert vor, **links** zurück – an Kapitelgrenzen geht es automatisch ins nächste bzw.
ans Ende des vorherigen Kapitels. Solange das Menü verborgen ist, sind auch die
Systemleisten ausgeblendet.

## Bibliothek

Drei Tabs: **Bibliothek** (spiegelt die Ordnerstruktur der SMB-Freigabe wider – Ordner
antippen zum Öffnen, Zurück-Geste führt eine Ebene hoch), **Lese ich** (angefangene
Bücher, zuletzt gelesene zuerst) und **Favoriten** (per Herz auf dem Cover markiert).
Cover zeigen einen Fortschrittsbalken am unteren Rand und einen Haken, wenn das Buch
fertig gelesen ist. Während der Synchronisierung erscheint ein Statusbanner mit
Fortschrittsbalken.

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
