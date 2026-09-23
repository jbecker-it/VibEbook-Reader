package de.folio.reader.data.local

import java.io.File
import java.io.IOException

internal object LocalBookFiles {
    /** Only the selected book's extraction directories; never progress or neighboring books. */
    fun remove(booksDirectory: File, bookId: String) {
        require(bookId.matches(Regex("[a-f0-9]{32}"))) { "Ungültige Buch-ID." }
        val root = booksDirectory.canonicalFile
        root.listFiles()?.filter { it.name == bookId || it.name.startsWith("$bookId-") }?.forEach { directory ->
            require(directory.canonicalFile.parentFile == root) { "Ungültiger lokaler Buchpfad." }
            if (!directory.deleteRecursively()) throw IOException("Lokale Buchdateien konnten nicht vollständig entfernt werden.")
        }
    }
}
