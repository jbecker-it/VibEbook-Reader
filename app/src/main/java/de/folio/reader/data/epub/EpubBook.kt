package de.folio.reader.data.epub

/** Ergebnis des Parsens einer entpackten EPUB. */
data class EpubBook(
    val title: String,
    val author: String,
    /** Geordnete Liste absoluter Dateipfade der Kapitel (Spine). */
    val spine: List<String>,
    /** Absoluter Pfad zum Cover-Bild, falls vorhanden. */
    val coverPath: String?,
)
