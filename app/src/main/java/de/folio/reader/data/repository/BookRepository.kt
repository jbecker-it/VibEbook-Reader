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
     * synchronisiert. [onProgress] meldet Status-Text und – wo bekannt – den
     * Fortschrittsanteil 0..1 (null = unbestimmt).
     */
    suspend fun syncLibrary(onProgress: suspend (message: String, fraction: Float?) -> Unit = { _, _ -> }) {
        val settings = settingsRepo.currentSmbSettings()
        if (!settings.isConfigured) throw IllegalStateException("NAS nicht konfiguriert")

        onProgress("Bücher auf dem NAS suchen …", null)
        val rootPrefix = settings.rootPath.trim('/', '\\')
        val remote = smbClient.listEpubs(settings)

        val keepIds = mutableListOf<String>()
        val total = remote.size.coerceAtLeast(1)
        remote.forEachIndexed { index, entry ->
            val libraryRel = entry.relativePath.removePrefix(rootPrefix).trim('/')
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
                        favorite = false,
                    )
                )
            }

            if (needsDownload) {
                onProgress(
                    "Lade „${libraryRel.substringAfterLast('/')}\" (${index + 1}/$total) …",
                    index.toFloat() / total,
                )
                downloadAndExtract(settings, id, libraryRel, entry.modified, entry.size)
            }
        }

        // Lokal entfernen, was es auf dem NAS nicht mehr gibt.
        val removed = bookDao.getAll().filter { it.id !in keepIds }
        removed.forEach { File(booksDir, it.id).deleteRecursively() }
        if (keepIds.isNotEmpty()) bookDao.deleteMissing(keepIds) else {
            removed.forEach { bookDao.delete(it.id) }
        }

        onProgress("Lesefortschritt synchronisieren …", null)
        syncAllProgress(settings)
        onProgress("Fertig", 1f)
    }

    suspend fun downloadBook(id: String) {
        val settings = settingsRepo.currentSmbSettings()
        val entity = bookDao.getById(id) ?: return
        downloadAndExtract(settings, id, entity.relativePath, entity.remoteModified, entity.sizeBytes)
    }

    /**
     * Favorit umschalten: sofort in der lokalen DB (UI), zusätzlich in der
     * Metadaten-Datei des Buches mit eigenem Zeitstempel – die Änderung wird
     * dadurch automatisch aufs NAS synchronisiert.
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
        // write() signalisiert über changes den SyncManager → NAS-Abgleich.
        progressRepo.write(meta)
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
                remoteModified = remoteModified,
                favorite = existing?.favorite ?: false,
            )
        )
    }

    // ---- Fortschritt ------------------------------------------------------

    /**
     * Leseposition lokal speichern und auf dem NAS ablegen (sofern erreichbar).
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
        runCatching { syncProgress(progress.bookId) }
    }

    /**
     * Bidirektionaler Abgleich der Metadaten-Datei eines Buches: feldweiser
     * Merge (Leseposition und Favorit mit je eigenem Zeitstempel), Ergebnis
     * wird auf die jeweils veraltete Seite geschrieben. Der Favorit wird
     * zusätzlich in die lokale DB gespiegelt, damit die UI ihn sofort zeigt.
     */
    suspend fun syncProgress(bookId: String) {
        val settings = settingsRepo.currentSmbSettings()
        if (!settings.isConfigured) return
        val remotePath = progressFilePath(settings, bookId)

        val local = progressRepo.read(bookId)
        val remote = smbClient.readTextOrNull(settings, remotePath)
            ?.let { runCatching { ReadingProgress.fromJson(it) }.getOrNull() }

        val merged = ReadingProgress.merge(local, remote) ?: return

        if (merged != remote) {
            smbClient.writeText(settings, remotePath, merged.toJson())
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
        val settings = settingsRepo.currentSmbSettings()
        if (!settings.isConfigured) return
        syncAllProgress(settings)
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
        favorite = favorite,
    )

    private fun String.toStringList(): List<String> = runCatching {
        val arr = JSONArray(this)
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())
}
