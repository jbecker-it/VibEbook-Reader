package de.folio.reader.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val relativePath: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    /** Spine als JSON-Array von lokalen Dateipfaden. */
    val spineJson: String,
    val downloaded: Boolean,
    val sizeBytes: Long,
    /** Zeitstempel der Datei auf dem NAS – erkennt geänderte/neue Bücher. */
    val remoteModified: Long,
)
