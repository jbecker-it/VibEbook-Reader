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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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
    private val progressLocks = ConcurrentHashMap<String, Mutex>()
    private val leases = ConcurrentHashMap<String, Int>()
    private val booksDir: File by lazy { File(context.filesDir, "books").apply { mkdirs() } }

    fun observeBooks(): Flow<List<Book>> =
        bookDao.observeAll()
            .combine(progressRepo.changes) { entities, _ -> entities }
            .map { entities ->
                val progressMap = progressRepo.readAll()
                entities.map { it.toBook(progressMap[it.id]) }
            }

    fun observeBook(id: String): Flow<Book?> =
        bookDao.observeById(id)
            .combine(progressRepo.changes) { entity, _ -> entity }
            .map { entity -> entity?.toBook(progressRepo.read(id)) }

    // ---- Bibliotheks-Sync -------------------------------------------------

    /**
     * Gleicht die Bibliothek mit dem Nextcloud ab: neue Bücher werden registriert und
     * heruntergeladen, entfernte lokal behalten. Anschließend werden alle Fortschritte
     * synchronisiert. [onProgress] meldet Status-Text und – wo bekannt – den
     * Fortschrittsanteil 0..1 (null = unbestimmt).
     */
    suspend fun syncLibrary(onProgress: suspend (message: String, fraction: Float?) -> Unit = { _, _ -> }) = libraryMutex.withLock {
        val settings = settingsRepo.currentNextcloudSettings()
        if (!settings.isConfigured) throw IllegalStateException("Nextcloud nicht konfiguriert")

        onProgress("Bücher auf dem Nextcloud suchen …", null)
        val rootPrefix = settings.rootPath.trim('/', '\\')
        val remote = nextcloudClient.listEpubs(settings)
        if (remote.isNotEmpty()) settingsRepo.bindLibrary(settings)

        val registry = nextcloudClient.bookRegistry(settings, remote.associate { it.remoteId to BookId.fromPath(if (rootPrefix.isEmpty()) it.relativePath else it.relativePath.removePrefix("$rootPrefix/")) }.filterKeys { it.isNotEmpty() })
        val keepIds = mutableSetOf<String>()
        val downloadIds = mutableListOf<String>()
        val autoDownload = settingsRepo.autoDownload.first()
        remote.forEach { entry ->
            val rel = if (rootPrefix.isEmpty()) entry.relativePath else entry.relativePath.removePrefix("$rootPrefix/")
            val byRemote = if (entry.remoteId.isNotEmpty()) bookDao.getByRemoteId(entry.remoteId) else null
            val id = byRemote?.id ?: registry[entry.remoteId] ?: BookId.fromPath(rel)
            val existing = byRemote ?: bookDao.getById(id)
            keepIds += id
            val aliases = existing?.aliasesJson?.toStringList().orEmpty().toMutableSet()
            if (existing != null && existing.relativePath != rel) aliases += existing.relativePath
            val entity = (existing ?: BookEntity(id, rel, rel.substringAfterLast('/').substringBeforeLast('.'), "", null, "[]", false, entry.size, 0L,
                keepOffline = autoDownload, addedAt = System.currentTimeMillis())).copy(
                relativePath = rel, remoteId = entry.remoteId, aliasesJson = JSONArray(aliases.toList()).toString(), listedEtag = entry.etag, missingRemotely = false,
            )
            bookDao.upsert(entity)
            val changed = !entity.downloaded || (entry.etag.isNotBlank() && entity.remoteEtag != entry.etag) || entity.sizeBytes != entry.size
            if (entity.keepOffline && changed) downloadIds += id
        }
        bookDao.getAll().filter { !it.localOnly }.forEach { bookDao.setMissing(it.id, it.id !in keepIds) }
        val errors = mutableListOf<String>()
        onProgress("Lesestände abgleichen …", null)
        try { syncPendingProgress(true) } catch (e: CancellationException) { throw e } catch (e: Exception) { errors += e.message.orEmpty() }
        downloadIds.forEachIndexed { index, id ->
            val entity = bookDao.getById(id) ?: return@forEachIndexed
            onProgress("Lade „${entity.title}“ (${index + 1}/${downloadIds.size}) …", index.toFloat() / downloadIds.size)
            try { downloadAndExtract(settings, id, entity.relativePath, entity.listedEtag, remote.first { it.relativePath == joinPath(settings.rootPath, entity.relativePath) }.size) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is de.folio.reader.data.nextcloud.DavException && e.status == 401) throw e
                bookDao.setDownloadError(id, e.message ?: "Download fehlgeschlagen")
                errors += "${entity.title}: ${e.message}"
            }
        }
        cleanupRevisions()
        if (errors.isNotEmpty()) throw IOException("${errors.size} Dateien konnten nicht abgeglichen werden. Andere Bücher bleiben nutzbar.\n" + errors.take(3).joinToString("\n"))
        onProgress("Fertig", 1f)
    }

    suspend fun downloadBook(id: String) = libraryMutex.withLock {
        val settings = settingsRepo.currentNextcloudSettings()
        val entity = bookDao.getById(id) ?: throw IOException("Buch nicht gefunden.")
        require(!entity.localOnly) { "Lokale Datei erneut importieren." }
        bookDao.setKeepOffline(id, true)
        downloadAndExtract(settings, id, entity.relativePath, entity.listedEtag, entity.sizeBytes)
    }

    suspend fun opened(id: String) { bookDao.opened(id, System.currentTimeMillis()) }
    fun retain(book: Book): () -> Unit {
        val root = extractionRoot(book)?.path ?: return {}
        leases.compute(root) { _, n -> (n ?: 0) + 1 }
        return { leases.computeIfPresent(root) { _, n -> (n - 1).takeIf { it > 0 } } }
    }
    fun extractionRoot(book: Book): File? {
        var dir = book.spine.firstOrNull()?.let { File(it).canonicalFile.parentFile } ?: return null
        val root = booksDir.canonicalFile
        while (dir.parentFile != root) {
            if (!dir.path.startsWith(root.path + File.separator)) return null
            dir = dir.parentFile ?: return null
        }
        return dir
    }
    suspend fun cleanupRevisions() = withContext(Dispatchers.IO) {
        val current = bookDao.getAll().mapNotNull { extractionRoot(it.toBook(null))?.path }.toSet()
        booksDir.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{32}(-[a-f0-9-]+)?")) }.forEach { dir ->
            if (dir.canonicalPath !in current && !leases.containsKey(dir.canonicalPath)) dir.deleteRecursively()
        }
    }
    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) { booksDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    suspend fun removeLocalCopy(id: String) = libraryMutex.withLock {
        val entity = bookDao.getById(id) ?: return@withLock
        val root = extractionRoot(entity.toBook(null))
        require(root == null || !leases.containsKey(root.path)) { "Buch zuerst schließen." }
        de.folio.reader.data.local.LocalBookFiles.remove(booksDir, id)
        if (entity.localOnly || entity.missingRemotely) bookDao.delete(id) else bookDao.clearDownload(id)
    }
    suspend fun history(id: String) = progressRepo.history(id)
    suspend fun exportProgress() = progressRepo.export()
    suspend fun importProgress(raw: String) = progressRepo.import(raw)

    suspend fun removeMissingBook(id: String) = libraryMutex.withLock {
        val entity = bookDao.getById(id) ?: return@withLock
        require(entity.missingRemotely) { "Nur nicht mehr auf Nextcloud vorhandene Bücher können entfernt werden." }
        withContext(Dispatchers.IO) { de.folio.reader.data.local.LocalBookFiles.remove(booksDir, id) }
        bookDao.delete(id)
        // Deliberately keep filesDir/progress/<id>.json for a later reimport.
    }

    /**
     * Favorit umschalten: sofort in der lokalen DB (UI), zusätzlich in der
     * Metadaten-Datei des Buches mit eigenem Zeitstempel – die Änderung wird
     * dadurch automatisch aufs Nextcloud synchronisiert.
     */
    suspend fun toggleFavorite(id: String) {
        val entity = bookDao.getById(id) ?: return
        val newValue = !entity.favorite

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
            favoriteUpdatedAt = maxOf(System.currentTimeMillis(), base.favoriteUpdatedAt + 1),
            favoriteRevision = base.favoriteRevision + 1,
            favoriteDeviceId = settingsRepo.deviceId(),
        )
        // write() signalisiert über changes den SyncManager → Nextcloud-Abgleich.
        progressRepo.write(meta, notify = !entity.localOnly)
        bookDao.setFavorite(id, newValue)
    }

    /** Read existing extracted metadata too, so upgrades need no new download. */
    suspend fun readLayouts(book: Book): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val path = book.spine.firstOrNull() ?: return@withContext emptyMap()
        val root = booksDir.canonicalFile
        var dir = File(path).canonicalFile.parentFile
        while (dir != null && dir.parentFile != root) {
            if (!dir.path.startsWith(root.path + File.separator)) return@withContext emptyMap()
            dir = dir.parentFile
        }
        val bookDir = dir ?: return@withContext emptyMap()
        runCatching { epubParser.readLayouts(bookDir) }.getOrDefault(emptyMap())
    }

    /** Changes completion without moving the bookmark or changing reading recency. */
    suspend fun setFinished(id: String, finished: Boolean) {
        val entity = bookDao.getById(id) ?: return
        val base = progressRepo.read(id) ?: ReadingProgress(
            bookId = id, spineIndex = 0, scrollFraction = 0f,
            updatedAt = 0L, deviceId = settingsRepo.deviceId(),
        )
        progressRepo.write(base.copy(
            finished = finished,
            finishedUpdatedAt = maxOf(System.currentTimeMillis(), base.finishedUpdatedAt + 1),
            finishedRevision = base.finishedRevision + 1, finishedDeviceId = settingsRepo.deviceId(),
        ), notify = !entity.localOnly)
    }

    private suspend fun downloadAndExtract(
        settings: NextcloudSettings,
        id: String,
        libraryRel: String,
        etag: String,
        size: Long,
    ) = withContext(Dispatchers.IO) {
        cleanupRevisions()
        val budget = settingsRepo.storageBudgetMb.first() * 1024L * 1024L
        require(storageBytes() + size <= budget) { "Offline-Speicherlimit erreicht. Lokale Kopien entfernen oder Limit erhöhen." }
        require(context.filesDir.usableSpace > size + 64L * 1024 * 1024) { "Zu wenig freier Speicher für dieses Buch." }
        val remotePath = joinPath(settings.rootPath, libraryRel)
        val tmp = File(context.cacheDir, "$id.epub")
        nextcloudClient.download(settings, remotePath, tmp, etag, minOf(budget - storageBytes(), context.filesDir.usableSpace - 64L * 1024 * 1024))

        // Versioned extraction keeps the currently readable copy intact until indexing succeeds.
        val bookDir = File(booksDir, "$id-${java.util.UUID.randomUUID()}")
        val jobContext = kotlin.coroutines.coroutineContext
        val parsed = try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            tmp.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            epubParser.extract(tmp, bookDir, minOf((budget - storageBytes()).coerceAtLeast(0), (context.filesDir.usableSpace - 64L * 1024 * 1024).coerceAtLeast(0))) { jobContext.ensureActive() }
            File(bookDir, "folio-manifest.sha256").writeBytes(digest.digest())
            epubParser.parse(bookDir).also { require(it.spine.isNotEmpty()) { "EPUB enthält keine lesbaren Kapitel." } }
        } catch (e: Exception) { bookDir.deleteRecursively(); throw e
        } finally { tmp.delete() }
        val existing = bookDao.getById(id)
        try { bookDao.upsert(
            BookEntity(
                id = id,
                relativePath = libraryRel,
                title = if (libraryRel.endsWith(".cbz", true)) libraryRel.substringAfterLast('/').substringBeforeLast('.') else parsed.title,
                author = parsed.author,
                coverPath = parsed.coverPath,
                spineJson = JSONArray(parsed.spine).toString(),
                downloaded = parsed.spine.isNotEmpty(),
                sizeBytes = size,
                remoteModified = 0L,
                remoteEtag = etag,
                favorite = existing?.favorite ?: false,
                remoteId = existing?.remoteId.orEmpty(), aliasesJson = existing?.aliasesJson ?: "[]", listedEtag = etag,
                contentRevision = java.security.MessageDigest.getInstance("SHA-256").digest(File(bookDir, "folio-manifest.sha256").takeIf { it.exists() }?.readBytes() ?: parsed.spine.joinToString { File(it).readText() }.toByteArray()).joinToString("") { "%02x".format(it) },
                tocJson = parsed.tocJson, keepOffline = true, lastOpenedAt = existing?.lastOpenedAt ?: 0,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            )
        ) } catch (e: Exception) { bookDir.deleteRecursively(); throw e }
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
                favoriteRevision = existing.favoriteRevision, favoriteDeviceId = existing.favoriteDeviceId,
                updatedAt = progress.updatedAt, positionRevision = existing.positionRevision + 1,
            )
        } else {
            progress
        }
        progressRepo.write(enriched, notify = bookDao.getById(progress.bookId)?.localOnly != true)
    }

    /**
     * Bidirektionaler Abgleich der Metadaten-Datei eines Buches: feldweiser
     * Merge (Leseposition und Favorit mit je eigenem Zeitstempel), Ergebnis
     * wird auf die jeweils veraltete Seite geschrieben. Der Favorit wird
     * zusätzlich in die lokale DB gespiegelt, damit die UI ihn sofort zeigt.
     */
    suspend fun syncProgress(bookId: String): Boolean = progressLocks.getOrPut(bookId) { Mutex() }.withLock {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (connectivity.activeNetwork == null || (settingsRepo.wifiOnly.first() && connectivity.isActiveNetworkMetered)) return@withLock false
        val settings = settingsRepo.currentNextcloudSettings()
        if (!settings.isConfigured) return@withLock false
        val entity = bookDao.getById(bookId)
        if (entity?.localOnly == true) { progressRepo.read(bookId)?.let { progressRepo.acknowledge(it) }; return@withLock true }
        val remotePath = progressFilePath(settings, bookId)

        val merged = nextcloudClient.syncProgress(settings, remotePath, bookId) {
            progressRepo.read(bookId)
        } ?: return@withLock true
        if (merged != progressRepo.read(bookId)) {
            progressRepo.write(merged, notify = false)
        }

        if (settingsRepo.nativeFavorites.first() && entity != null && !entity.missingRemotely)
            nextcloudClient.setFavorite(settings, joinPath(settings.rootPath, entity.relativePath), merged.favorite)
        progressRepo.acknowledge(merged)

        // Favorit aus dem Merge-Ergebnis in die lokale DB übernehmen.
        bookDao.getById(bookId)?.let { entity ->
            val currentFavorite = progressRepo.read(bookId)?.favorite ?: merged.favorite
            if (entity.favorite != currentFavorite) {
                bookDao.setFavorite(bookId, currentFavorite)
            }
        }
        true
    }

    suspend fun syncAllProgress() = syncPendingProgress(true)
    suspend fun syncPendingProgress(all: Boolean = false) {
        val pending = progressRepo.pendingIds()
        val ids = if (all) (pending + bookDao.getAll().filter { !it.localOnly }.map { it.id }).distinct() else pending
        val errors = mutableListOf<Exception>()
        ids.forEach { id ->
            try { if (!syncProgress(id)) throw IOException("Offline · Lesestand bleibt vorgemerkt.") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is de.folio.reader.data.nextcloud.DavException && e.status == 401) throw e
                errors += e
            }
        }
        if (errors.isNotEmpty()) throw errors.first()
        if (progressRepo.pendingIds().isNotEmpty()) throw IOException("Neue Änderungen werden erneut synchronisiert.")
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
        missingRemotely = missingRemotely, remoteId = remoteId, contentRevision = contentRevision, tocJson = tocJson,
        localOnly = localOnly, keepOffline = keepOffline, lastOpenedAt = lastOpenedAt, addedAt = addedAt, downloadError = downloadError,
    )

    private fun String.toStringList(): List<String> = runCatching {
        val arr = JSONArray(this)
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())
}
