package de.folio.reader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BookEntity::class], version = 3, exportSchema = false)
abstract class FolioDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN remoteEtag TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN missingRemotely INTEGER NOT NULL DEFAULT 0")
            }
        }
        /** v1 → v2: Favoriten-Flag. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
