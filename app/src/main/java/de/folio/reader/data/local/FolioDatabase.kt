package de.folio.reader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [BookEntity::class], version = 1, exportSchema = false)
abstract class FolioDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}
