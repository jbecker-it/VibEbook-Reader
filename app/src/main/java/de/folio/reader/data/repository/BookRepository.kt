package de.folio.reader.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.folio.reader.data.epub.EpubParser
import de.folio.reader.data.local.BookDao
import de.folio.reader.data.local.BookEntity
import de.folio.reader.data.progress.BookId
import de.folio.reader.data.progress.ProgressRepository
import de.folio.reader.data.settings.SettingsRepository
import de.folio.reader.data.smb.SmbClient
import de.folio.reader.domain.model.Book
import de.folio.reader.domain.model.ReadingProgress
import de.folio.reader.domain.model.SmbSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.json.JSONArray
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val smbClient: SmbClient,
    private val epubParser: EpubParser,
    private val progressRepo: ProgressRepository,
    private val settingsRepo: SettingsRepository,
) {
    private val booksDir: File by lazy { File(context.filesDir, "books").apply { mkdirs() } }

    fun observeBooks(): Flow<List<Book>> =
        bookDao.observeAll()
            .combine(progressRepo.changes.onStart { emit("") }) { entities, _ -> entities }
            .map { entities ->
                val progressMap = progressRepo.readAll()
                entities.map { it.toBook(progressMap[it.id]) }
            }

    fun observeBook(id: String): Flow<Book?> =
        bookDao.observeById(id)
            .combine(progressRepo.changes.onStart { emit("") }) { entity, _ -> entity }
            .map { entity -> entity?.toBook(progressRepo.read(id)) }

    // ---- Bibliotheks-Sync -------------------------------------------------

    /**
     * Gleicht die Bibliothek mit dem NAS ab: neue Bücher werden registriert und
     * heruntergeladen, entfernte gelöscht. Anschließend werden alle Fortschritte
     * synchronisiert.
     */
    suspend fun syncLibrary(onProgress: (String) -> Unit = {}) {
        val settings = settingsRepo.currentSmbSettings()
        if (!settings.isConfigured) throw IllegalStateException("NAS nicht konfiguriert")

        onProgress("Bücher auf dem NAS suchen …")
        val rootPrefix = settings.rootPath.trim('/', '\\')
        val remote = smbClient.listEpubs(settings)

        val keepIds = mutableListOf<String>()
        for (entry in remote) {
            val libraryRel = entry.relativePath
                .removePrefix(rootPrefix).trim('/')
            val id = BookId.fromPath(libraryRel)
            keepIds += id

            val existing = bookDao.getById(id)
            val needsDownload = existing == null ||
                !existing.downloaded ||
                existing.remoteModified != entry.modified

            if (existing == null) {
                bookDao.upsert(
                    BookEntity(
                        id = id,
                        relativePath = libraryRel,
                        title = libraryRel.substringAfterLast('/').removeSuffix(".epub"),
                        author = "",
                        coverPath = null,
                        spineJson = "[]",
                        downloaded = false,
                        sizeBytes = entry.size,
                        remoteModified = entry.modified,
                    )
                )
            }

            if (needsDownload) {
                onProgress("Lade „${libraryRel.substringAfterLast('/')}\" …")
                downloadAndExtract(settings, id, libraryRel, entry.modified, entry.size)
            }
        }

        // Lokal entfernen, was es auf dem NAS nicht mehr gibt.
        val removed = bookDao.getAll().filter { it.id !in keepIds }
        removed.forEach { File(booksDir, it.id).deleteRecursively() }
        if (keepIds.isNotEmpty()) bookDao.deleteMissing(keepIds) else {
            removed.forEach { bookDao.delete(it.id) }
        }

        onProgress("Lesefortschritt synchronisieren …")
        syncAllProgress(settings)
        onProgress("Fertig")
    }

    suspend fun downloadBook(id: String) {
        val settings = settingsRepo.currentSmbSettings()
        val entity = bookDao.getById(id) ?: return
        downloadAndExtract(settings, id, entity.relativePath, entity.remoteModified, entity.sizeBytes)
    }

    private suspend fun downloadAndExtract(
        settings: SmbSettings,
        id: String,
        libraryRel: String,
        remoteModified: Long,
        size: Long,
    ) {
        val sharePath = joinShare(settings.rootPath, libraryRel)
        val tmp = File(context.cacheDir, "$id.epub")
        smbClient.download(settings, sharePath, tmp)

        val bookDir = File(booksDir, id)
        epubParser.extract(tmp, bookDir)
        tmp.delete()

        val parsed = epubParser.parse(bookDir)
        bookDao.upsert(
            BookEntity(
                id = id,
                relativePath = libraryRel,
                title = parsed.title,
                author = parsed.author,
                coverPath = parsed.coverPath,
                spineJson = JSONArray(parsed.spine).toString(),
                downloaded = parsed.spine.isNotEmpty(),
                sizeBytes = size,
                remoteModified = remoteModified,
            )
        )
    }

    // ---- Fortschritt ------------------------------------------------------

    /** Lokal speichern und auf dem NAS ablegen (sofern erreichbar). */
    suspend fun saveProgress(progress: ReadingProgress) {
        progressRepo.write(progress)
        runCatching { syncProgress(progress.bookId) }
    }

    /**
     * Bidirektionaler Abgleich einer einzelnen Fortschrittsdatei: Der neuere von
     * lokalem und NAS-Stand gewinnt und wird auf die jeweils andere Seite geschrieben.
     */
    suspend fun syncProgress(bookId: String) {
        val settings = settingsRepo.currentSmbSettings()
        if (!settings.isConfigured) return
        val remotePath = progressFilePath(settings, bookId)

        val local = progressRepo.read(bookId)
        val remote = smbClient.readTextOrNull(settings, remotePath)
            ?.let { runCatching { ReadingProgress.fromJson(it) }.getOrNull() }

        val winner = ReadingProgress.newer(local, remote) ?: return

        if (winner !== remote) {
            smbClient.writeText(settings, remotePath, winner.toJson())
        }
        if (winner !== local) {
            progressRepo.write(winner, notify = true)
        }
    }

    private suspend fun syncAllProgress(settings: SmbSettings) {
        bookDao.getAll().forEach { entity ->
            runCatching { syncProgress(entity.id) }
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private fun progressFilePath(settings: SmbSettings, bookId: String): String =
        joinShare(settings.progressDir, "$bookId.json")

    private fun joinShare(vararg parts: String): String =
        parts.filter { it.isNotBlank() }.joinToString("/") { it.trim('/', '\\') }

    private fun BookEntity.toBook(progress: ReadingProgress?): Book = Book(
        id = id,
        relativePath = relativePath,
        title = title,
        author = author,
        coverPath = coverPath,
        spine = spineJson.toStringList(),
        downloaded = downloaded,
        sizeBytes = sizeBytes,
        progress = progress,
    )

    private fun String.toStringList(): List<String> = runCatching {
        val arr = JSONArray(this)
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())
}
