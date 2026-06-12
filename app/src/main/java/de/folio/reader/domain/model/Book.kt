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
)
