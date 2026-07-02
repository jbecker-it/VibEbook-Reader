package de.folio.reader.domain.model

/**
 * Ein Buch in der Bibliothek. [id] ist deterministisch aus dem relativen NAS-Pfad
 * abgeleitet, damit derselbe Titel auf jedem Gerät dieselbe ID (und damit dieselbe
 * Fortschrittsdatei) erhält.
 */
data class Book(
    val id: String,
    /** Pfad relativ zum konfigurierten NAS-Wurzelordner, z. B. "SciFi/Dune.epub". */
    val relativePath: String,
    val title: String,
    val author: String,
    /** Lokaler Pfad zum extrahierten Cover, falls vorhanden. */
    val coverPath: String?,
    /** Reihenfolge der Kapitel-XHTML-Dateien (lokale absolute Pfade nach Extraktion). */
    val spine: List<String>,
    /** true sobald die EPUB heruntergeladen und entpackt wurde. */
    val downloaded: Boolean,
    val sizeBytes: Long,
    val progress: ReadingProgress?,
    val favorite: Boolean = false,
)

/** Gesamtfortschritt 0..1 über alle Kapitel. */
val Book.readFraction: Float
    get() {
        val p = progress ?: return 0f
        val count = spine.size.coerceAtLeast(1)
        return ((p.spineIndex + p.scrollFraction) / count).coerceIn(0f, 1f)
    }

/** Fertig gelesen (Flag aus dem Fortschritt oder praktisch am Ende). */
val Book.isFinished: Boolean
    get() = progress?.finished == true || readFraction >= 0.98f

/** Bereits angefangen (für den Tab „Lese ich"). */
val Book.isStarted: Boolean
    get() = (progress?.spineIndex ?: 0) > 0 || (progress?.scrollFraction ?: 0f) > 0.01f

/** Ordner des Buches relativ zur Bibliothek ("" = Wurzel). */
val Book.folder: String
    get() = relativePath.substringBeforeLast('/', "")
