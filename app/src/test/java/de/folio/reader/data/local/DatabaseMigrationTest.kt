package de.folio.reader.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DatabaseMigrationTest {
    @Test fun staleDownloadMetadataPreservesNewerUserActions() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FolioDatabase::class.java).build()
        try {
            val dao = database.bookDao()
            val original = BookEntity("book", "book.epub", "Old", "", null, "[]", false, 42, 0, addedAt = 10)
            dao.upsert(original)
            val downloadSnapshot = dao.getById("book")!!
            dao.setFavorite("book", true)
            dao.opened("book", 200)
            dao.upsertMetadata(downloadSnapshot.copy(title = "New", downloaded = true, spineJson = "[\"new.xhtml\"]", addedAt = 100))
            val result = dao.getById("book")!!
            assertTrue(result.favorite); assertEquals(200L, result.lastOpenedAt); assertEquals(10L, result.addedAt)
            assertEquals("New", result.title); assertTrue(result.downloaded); assertEquals("[\"new.xhtml\"]", result.spineJson)
        } finally { database.close() }
    }

    @Test fun upgradesEveryExistingVersionWithoutLosingBook() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        for (version in 1..3) {
            val name = "migration-$version.db"; context.deleteDatabase(name)
            val path = context.getDatabasePath(name); path.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                val favorite = if (version >= 2) ", favorite INTEGER NOT NULL DEFAULT 0" else ""
                val remote = if (version >= 3) ", remoteEtag TEXT NOT NULL DEFAULT '', missingRemotely INTEGER NOT NULL DEFAULT 0" else ""
                db.execSQL("CREATE TABLE books (id TEXT NOT NULL PRIMARY KEY, relativePath TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, coverPath TEXT, spineJson TEXT NOT NULL, downloaded INTEGER NOT NULL, sizeBytes INTEGER NOT NULL, remoteModified INTEGER NOT NULL$favorite$remote)")
                db.execSQL("INSERT INTO books (id,relativePath,title,author,spineJson,downloaded,sizeBytes,remoteModified) VALUES ('book','Books/Novel.epub','Novel','Author','[\"chapter.xhtml\"]',1,42,7)")
                db.version = version
            }
            val database = Room.databaseBuilder(context, FolioDatabase::class.java, name)
                .addMigrations(FolioDatabase.MIGRATION_1_2, FolioDatabase.MIGRATION_2_3, FolioDatabase.MIGRATION_3_4).build()
            try {
                val book = database.bookDao().getById("book")!!
                assertEquals("Novel", book.title); assertTrue(book.downloaded); assertTrue(book.keepOffline)
                assertEquals("[\"chapter.xhtml\"]", book.spineJson); assertFalse(book.localOnly)
                assertEquals("", book.downloadError); assertEquals(42L, book.sizeBytes)
            } finally { database.close(); context.deleteDatabase(name) }
        }
    }
}
