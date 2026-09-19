package de.folio.reader.data.progress

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.folio.reader.domain.model.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verwaltet die lokalen Fortschrittsdateien (filesDir/progress/<id>.json).
 * Jeder Schreibvorgang signalisiert über [changes], dass eine Synchronisierung
 * mit dem Nextcloud ansteht.
 */
@Singleton
class ProgressRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File by lazy { File(context.filesDir, "progress").apply { mkdirs() } }

    /**
     * In-Memory-Spiegel der Fortschrittsdateien. Der Cache wird beim Schreiben
     * sofort aktualisiert, damit ein direkt darauf folgendes Öffnen des Buches
     * nie einen veralteten Stand von der Platte liest.
     */
    private val cache = ConcurrentHashMap<String, ReadingProgress>()
    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** Emittiert die bookId, sobald sich ein lokaler Fortschritt geändert hat. */
    val changes: SharedFlow<String> = _changes

    private fun fileFor(bookId: String) = File(dir, "$bookId.json")

    suspend fun read(bookId: String): ReadingProgress? = withContext(Dispatchers.IO) {
        cache[bookId]?.let { return@withContext it }
        val f = fileFor(bookId)
        if (!f.exists()) return@withContext null
        runCatching { ReadingProgress.fromJson(f.readText()) }.getOrNull()
            ?.also { cache[it.bookId] = it }
    }

    suspend fun readAll(): Map<String, ReadingProgress> = withContext(Dispatchers.IO) {
        val fromDisk = dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { ReadingProgress.fromJson(f.readText()) }.getOrNull() }
            ?.associateBy { it.bookId }
            ?: emptyMap()
        // Cache gewinnt: er enthält ggf. Schreibvorgänge, die noch nicht auf der Platte sind.
        fromDisk + cache
    }

    /** Schreibt lokal und stößt eine spätere Synchronisierung an. */
    suspend fun write(progress: ReadingProgress, notify: Boolean = true) = withContext(Dispatchers.IO) {
        writeMutex.lock()
        try {
            val merged = ReadingProgress.merge(read(progress.bookId), progress)!!
            val file = android.util.AtomicFile(fileFor(merged.bookId))
            val stream = file.startWrite()
            try { stream.write(merged.toJson().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
            cache[merged.bookId] = merged
            if (notify) _changes.tryEmit(merged.bookId)
        } finally { writeMutex.unlock() }
    }
}
