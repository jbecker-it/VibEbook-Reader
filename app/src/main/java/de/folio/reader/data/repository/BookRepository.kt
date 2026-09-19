package de.folio.reader.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.folio.reader.data.epub.EpubParser
import de.folio.reader.data.local.BookDao
import de.folio.reader.data.local.BookEntity
import de.folio.reader.data.progress.BookId
import de.folio.reader.data.progress.ProgressRepository
import de.folio.reader.data.settings.SettingsRepository
import de.folio.reader.data.nextcloud.NextcloudClient
import de.folio.reader.domain.model.Book
import de.folio.reader.domain.model.ReadingProgress
import de.folio.reader.domain.model.NextcloudSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val nextcloudClient: NextcloudClient,
    private val epubParser: EpubParser,
    private val progressRepo: ProgressRepository,
    private val settingsRepo: SettingsRepository,
) {
    private val libraryMutex = Mutex()
    private val progressMutex = Mutex()
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
     * Gleicht die Bibliothek mit dem Nextcloud ab: neue Bücher werden registriert und
     * heruntergeladen, entfernte gelöscht. Anschließend werden alle Fortschritte
     * synchronisiert. [onProgress] meldet Status-Text und – wo bekannt – den
     * Fortschrittsanteil 0..1 (null = unbestimmt).
     */
    suspend fun syncLibrary(onProgress: suspend (message: String, fraction: Float?) -> Unit = { _, _ -> }) = libraryMutex.withLock {
        val settings = settingsRepo.currentNextcloudSettings()
        if (!settings.isConfigured) throw IllegalStateException("Nextcloud nicht konfiguriert")

        onProgress("Bücher auf dem Nextcloud suchen …", null)
        val rootPrefix = settings.rootPath.trim('/', '\\')
        val remote = nextcloudClient.listEpubs(settings)

        val keepIds = mutableListOf<String>()
        val total = remote.size.coerceAtLeast(1)
        remote.forEachIndexed { index, entry ->
            val libraryRel = if (rootPrefix.isEmpty()) entry.relativePath else entry.relativePath.removePrefix("$rootPrefix/")
            val id = BookId.fromPath(libraryRel)
            keepIds += id

            val existing = bookDao.getById(id)
            val needsDownload = existing == null ||
                !existing.downloaded ||
                entry.etag.isBlank() || existing.remoteEtag != entry.etag || existing.sizeBytes != entry.size

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
                        remoteModified = 0L,
                        favorite = false,
                    )
                )
            }

            if (needsDownload) {
                onProgress(
                    "Lade „${libraryRel.substringAfterLast('/')}\" (${index + 1}/$total) …",
                    index.toFloat() / total,
                )
                downloadAndExtract(settings, id, libraryRel, entry.etag, entry.size)
            }
        }

        // Only mark absence after a complete successful scan. Never remove offline data.
        bookDao.getAll().forEach { bookDao.setMissing(it.id, it.id !in keepIds) }

        onProgress("Lesefortschritt synchronisieren …", null)
        syncAllProgress(settings)
        onProgress("Fertig", 1f)
    }

    suspend fun downloadBook(id: String) = libraryMutex.withLock {
        val settings = settingsRepo.currentNextcloudSettings()
        val entity = bookDao.getById(id) ?: return@withLock
        downloadAndExtract(settings, id, entity.relativePath, entity.remoteEtag, entity.sizeBytes)
    }

    /**
     * Favorit umschalten: sofort in der lokalen DB (UI), zusätzlich in der
     * Metadaten-Datei des Buches mit eigenem Zeitstempel – die Änderung wird
     * dadurch automatisch aufs Nextcloud synchronisiert.
     */
    suspend fun toggleFavorite(id: String) {
        val entity = bookDao.getById(id) ?: return
        val newValue = !entity.favorite
        bookDao.setFavorite(id, newValue)

        val existing = progressRepo.read(id)
        val base = existing ?: ReadingProgress(
            bookId = id,
            spineIndex = 0,
            scrollFraction = 0f,
            updatedAt = 0L,
            deviceId = settingsRepo.deviceId(),
        )
        val meta = base.copy(
            favorite = newValue,
            favoriteUpdatedAt = System.currentTimeMillis(),
        )
        // write() signalisiert über changes den SyncManager → Nextcloud-Abgleich.
        progressRepo.write(meta)
    }

    private suspend fun downloadAndExtract(
        settings: NextcloudSettings,
        id: String,
        libraryRel: String,
        etag: String,
        size: Long,
    ) = withContext(Dispatchers.IO) {
        val remotePath = joinPath(settings.rootPath, libraryRel)
        val tmp = File(context.cacheDir, "$id.epub")
        nextcloudClient.download(settings, remotePath, tmp, etag)

        // Versioned extraction keeps the currently readable copy intact until indexing succeeds.
        val bookDir = File(booksDir, "$id-${java.util.UUID.randomUUID()}")
        val parsed = try {
            epubParser.extract(tmp, bookDir)
            epubParser.parse(bookDir).also { require(it.spine.isNotEmpty()) { "EPUB enthält keine lesbaren Kapitel." } }
        } catch (e: Exception) { bookDir.deleteRecursively(); throw e
        } finally { tmp.delete() }
        val existing = bookDao.getById(id)
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
                remoteModified = 0L,
                remoteEtag = etag,
                favorite = existing?.favorite ?: false,
            )
        )
    }

    // ---- Fortschritt ------------------------------------------------------

    /**
     * Leseposition lokal speichern und auf dem Nextcloud ablegen (sofern erreichbar).
     * Favoriten-Felder werden aus dem vorhandenen lokalen Stand übernommen,
     * damit das Weiterlesen den Favorit nicht zurücksetzt.
     */
    suspend fun saveProgress(progress: ReadingProgress) {
        val existing = progressRepo.read(progress.bookId)
        val enriched = if (existing != null) {
            progress.copy(
                favorite = existing.favorite,
                favoriteUpdatedAt = existing.favoriteUpdatedAt,
            )
        } else {
            progress
        }
        progressRepo.write(enriched)
    }

    /**
     * Bidirektionaler Abgleich der Metadaten-Datei eines Buches: feldweiser
     * Merge (Leseposition und Favorit mit je eigenem Zeitstempel), Ergebnis
     * wird auf die jeweils veraltete Seite geschrieben. Der Favorit wird
     * zusätzlich in die lokale DB gespiegelt, damit die UI ihn sofort zeigt.
     */
    suspend fun syncProgress(bookId: String) = progressMutex.withLock {
        val settings = settingsRepo.currentNextcloudSettings()
        if (!settings.isConfigured) return@withLock
        if (bookDao.getById(bookId)?.missingRemotely == true) return@withLock
        val remotePath = progressFilePath(settings, bookId)

        val local = progressRepo.read(bookId)
        val remoteFile = nextcloudClient.readTextOrNull(settings, remotePath)
        val remote = remoteFile?.let { ReadingProgress.fromJson(it.content) }
        require(remote == null || remote.bookId == bookId) { "Fortschrittsdatei gehört zu einem anderen Buch." }

        val merged = ReadingProgress.merge(local, remote) ?: return@withLock

        if (merged != remote) {
            nextcloudClient.writeText(settings, remotePath, merged.toJson(), remoteFile)
        }
        if (merged != local) {
            progressRepo.write(merged, notify = true)
        }

        // Favorit aus dem Merge-Ergebnis in die lokale DB übernehmen.
        bookDao.getById(bookId)?.let { entity ->
            if (entity.favorite != merged.favorite) {
                bookDao.setFavorite(bookId, merged.favorite)
            }
        }
    }

    /**
     * Leichtgewichtiger Abgleich nur der Lesefortschritte aller Bücher –
     * z.B. beim App-Start, ohne die komplette Bibliothek zu synchronisieren.
     */
    suspend fun syncAllProgress() {
        val settings = settingsRepo.currentNextcloudSettings()
        if (!settings.isConfigured) return
        syncAllProgress(settings)
    }

    private suspend fun syncAllProgress(settings: NextcloudSettings) {
        bookDao.getAll().forEach { entity ->
            syncProgress(entity.id)
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private fun progressFilePath(settings: NextcloudSettings, bookId: String): String =
        joinPath(settings.progressDir, "$bookId.json")

    private fun joinPath(vararg parts: String): String =
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
        favorite = favorite,
        missingRemotely = missingRemotely,
    )

    private fun String.toStringList(): List<String> = runCatching {
        val arr = JSONArray(this)
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())
}
