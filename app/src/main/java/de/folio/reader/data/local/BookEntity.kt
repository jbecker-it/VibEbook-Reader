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
    /** Zeitstempel der Datei auf dem Nextcloud – erkennt geänderte/neue Bücher. */
    val remoteModified: Long,
    val favorite: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "''") val remoteEtag: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0") val missingRemotely: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "''") val remoteId: String = "",
    @androidx.room.ColumnInfo(defaultValue = "'[]'") val aliasesJson: String = "[]",
    @androidx.room.ColumnInfo(defaultValue = "''") val listedEtag: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''") val contentRevision: String = "",
    @androidx.room.ColumnInfo(defaultValue = "'[]'") val tocJson: String = "[]",
    @androidx.room.ColumnInfo(defaultValue = "0") val localOnly: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "1") val keepOffline: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "0") val lastOpenedAt: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val addedAt: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "''") val downloadError: String = "",
)
