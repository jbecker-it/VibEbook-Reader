package de.folio.reader.data.progress

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import de.folio.reader.domain.model.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Pending state commits with the public progress, not in an ephemeral event queue. */
@Singleton
class ProgressRepository @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.filesDir, "progress").apply { mkdirs() }
    private val lock = Mutex()
    private val cache = mutableMapOf<String, ReadingProgress>()
    private val pending = mutableSetOf<String>()
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount = _pendingCount.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private fun file(id: String): File {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "Ungültige Buch-ID." }
        return File(dir, "$id.json")
    }
    private fun ids() = dir.listFiles().orEmpty().map { it.name.removeSuffix(".bak").removeSuffix(".new") }
        .filter { it.matches(Regex("[a-f0-9]{32}\\.json")) }.map { it.removeSuffix(".json") }.distinct()
    private fun readLocked(id: String): ReadingProgress? {
        cache[id]?.let { return it }
        val f = file(id)
        if (!f.exists() && !File(f.path + ".bak").exists()) return null
        val raw = AtomicFile(f).openRead().bufferedReader().use { it.readText() }
        val value = ReadingProgress.fromJson(raw)
        require(value.bookId == id) { "Fortschrittsdatei gehört zu einem anderen Buch." }
        cache[id] = value
        if (JSONObject(raw).optBoolean("_pending", true)) pending.add(id)
        return value
    }
    private fun writeLocked(value: ReadingProgress, isPending: Boolean) {
        val atomic = AtomicFile(file(value.bookId))
        val stream = atomic.startWrite()
        try {
            stream.write(JSONObject(value.toJson()).put("_pending", isPending).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
        cache[value.bookId] = value
        if (isPending) pending.add(value.bookId) else pending.remove(value.bookId)
        _pendingCount.value = pending.size
        _changes.value += 1
    }
    suspend fun read(id: String): ReadingProgress? = withContext(Dispatchers.IO) { lock.withLock { readLocked(id) } }
    suspend fun readAll(): Map<String, ReadingProgress> = withContext(Dispatchers.IO) { lock.withLock {
        ids().forEach { id ->
            try { readLocked(id) }
            catch (e: Exception) { _error.value = "Lokaler Lesestand beschädigt. Sicherung exportieren und Datei prüfen: $id" }
        }
        _pendingCount.value = pending.size
        cache.toMap()
    } }
    suspend fun pendingIds(): List<String> { readAll(); return lock.withLock { pending.toList() } }
    suspend fun write(progress: ReadingProgress, notify: Boolean = true) = withContext(Dispatchers.IO) { lock.withLock {
        val previous = readLocked(progress.bookId)
        val merged = ReadingProgress.merge(previous, progress)!!
        if (merged != previous) {
            if (previous != null && (previous.spineIndex != merged.spineIndex || previous.charOffset != merged.charOffset || previous.scrollFraction != merged.scrollFraction)) runCatching { appendHistoryLocked(previous) }.onFailure { _error.value = "Positionsverlauf konnte nicht gespeichert werden." }
            writeLocked(merged, notify || progress.bookId in pending)
        }
    } }
    /** A local edit made while uploading remains pending. */
    suspend fun acknowledge(uploaded: ReadingProgress) = withContext(Dispatchers.IO) { lock.withLock {
        val current = readLocked(uploaded.bookId)
        if (current == uploaded && current.bookId in pending) writeLocked(current, false)
    } }
    private fun appendHistoryLocked(value: ReadingProgress) {
        val f = File(dir, value.bookId + ".history")
        val arr = if (f.exists()) JSONArray(AtomicFile(f).openRead().bufferedReader().use { it.readText() }) else JSONArray()
        val next = JSONArray()
        for (i in maxOf(0, arr.length() - 19) until arr.length()) next.put(arr.getJSONObject(i))
        next.put(JSONObject(value.toJson()))
        val atomic = AtomicFile(f); val out = atomic.startWrite()
        try { out.write(next.toString().toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    suspend fun history(id: String): List<ReadingProgress> = withContext(Dispatchers.IO) { lock.withLock {
        file(id)
        val f = File(dir, "$id.history")
        if (!f.exists()) emptyList() else JSONArray(AtomicFile(f).openRead().bufferedReader().use { it.readText() }).let { arr ->
            (0 until arr.length()).map { ReadingProgress.fromJson(arr.getJSONObject(it).toString()) }.reversed()
        }
    } }
    suspend fun export(): String = JSONObject().put("format", "folio-progress-backup").put("version", 1)
        .put("progress", JSONArray(readAll().values.map { JSONObject(it.toJson()) })).toString()
    suspend fun import(raw: String): Int {
        require(raw.toByteArray().size <= 8 * 1024 * 1024) { "Sicherung zu groß." }
        val root = JSONObject(raw)
        require(root.optString("format") == "folio-progress-backup" && root.optInt("version") == 1) { "Ungültige Sicherung." }
        val arr = root.getJSONArray("progress")
        require(arr.length() <= 10000) { "Zu viele Lesestände." }
        val values = (0 until arr.length()).map { ReadingProgress.fromJson(arr.getJSONObject(it).toString()).also { p -> file(p.bookId) } }
        values.forEach { write(it) }
        return values.size
    }
}
