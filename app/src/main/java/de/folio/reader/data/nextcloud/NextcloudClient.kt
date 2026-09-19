package de.folio.reader.data.nextcloud

import de.folio.reader.domain.model.NextcloudSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

data class RemoteEntry(val relativePath: String, val directory: Boolean, val size: Long, val etag: String)
data class RemoteText(val content: String, val etag: String?)
class DavException(val status: Int) : IOException(when (status) {
    401 -> "Anmeldung fehlgeschlagen. Benutzername und App-Passwort prüfen."
    403 -> "Keine Berechtigung für diesen Nextcloud-Ordner."
    404 -> "Nextcloud-Ordner oder Datei nicht gefunden."
    412 -> "Datei wurde gleichzeitig geändert. Erneut synchronisieren."
    507 -> "Nextcloud-Speicher ist voll."
    in 300..399 -> "Server leitet um. Bitte die endgültige HTTPS-Adresse eintragen."
    else -> "Nextcloud-Anfrage fehlgeschlagen (HTTP $status)."
})

class NextcloudClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {

    private suspend fun execute(settings: NextcloudSettings, path: String, method: String,
        body: String? = null, headers: Map<String, String> = emptyMap()): Response {
        val request = Request.Builder().url(settings.davUrl(path))
            .header("Authorization", Credentials.basic(settings.username.trim(), settings.password, Charsets.UTF_8))
            .method(method, body?.toRequestBody((if (method == "PROPFIND") "application/xml; charset=utf-8" else "application/json; charset=utf-8").toMediaType()))
        headers.forEach { (key, value) -> request.header(key, value) }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                    continuation.resume(response) { response.close() }
                }
            })
        }
    }

    suspend fun testConnection(settings: NextcloudSettings): Result<Unit> = try {
        list(settings, settings.rootPath)
        Result.success(Unit)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) { Result.failure(e) }

    private suspend fun list(settings: NextcloudSettings, path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        execute(settings, path, "PROPFIND", PROPERTIES, mapOf("Depth" to "1")).use { response ->
            if (response.code != 207) throw DavException(response.code)
            val xml = response.body?.byteStream()?.use { readLimited(it, MAX_XML) } ?: throw IOException("Leere Ordnerantwort.")
            parseListing(xml, settings.davUrl(), settings.davUrl(path))
        }
    }

    suspend fun listEpubs(settings: NextcloudSettings): List<RemoteEntry> {
        val pending = java.util.ArrayDeque<String>().apply { add(settings.rootPath.trim('/')) }
        val visited = mutableSetOf<String>()
        val books = mutableListOf<RemoteEntry>()
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val folder = pending.removeFirst()
            check(visited.size < 10000) { "Zu viele Ordner in der Bibliothek." }
            if (!visited.add(folder)) continue
            for (entry in list(settings, folder)) {
                if (entry.directory) {
                    if (!entry.relativePath.substringAfterLast('/').startsWith('.') && entry.relativePath != settings.progressDir.trim('/')) pending.add(entry.relativePath)
                } else if (entry.relativePath.endsWith(".epub", true)) books += entry
            }
        }
        return books
    }

    suspend fun download(settings: NextcloudSettings, path: String, target: File, etag: String = "") = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("download-", ".part", target.parentFile)
        try {
            execute(settings, path, "GET", headers = if (etag.isBlank()) emptyMap() else mapOf("If-Match" to etag)).use { response ->
                if (response.code != 200) throw DavException(response.code)
                val body = response.body ?: throw IOException("Leere Buchantwort.")
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } }
                if (body.contentLength() >= 0 && temporary.length() != body.contentLength()) throw IOException("Unvollständiger Download.")
            }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    suspend fun readTextOrNull(settings: NextcloudSettings, path: String): RemoteText? = withContext(Dispatchers.IO) {
        execute(settings, path, "GET").use { response ->
            if (response.code == 404) return@withContext null
            if (response.code != 200) throw DavException(response.code)
            val content = response.body?.byteStream()?.use { input ->
                readLimited(input, 65536)
            } ?: throw IOException("Leere Fortschrittsdatei.")
            RemoteText(content, response.header("ETag"))
        }
    }

    suspend fun writeText(settings: NextcloudSettings, path: String, text: String, previous: RemoteText?) {
        var parent = ""
        for (segment in NextcloudSettings.segments(path).dropLast(1)) {
            parent = listOf(parent, segment).filter { it.isNotEmpty() }.joinToString("/")
            execute(settings, parent, "MKCOL", "").use { response ->
                if (response.code != 201 && response.code != 405) throw DavException(response.code)
            }
        }
        // Never blindly overwrite progress read without a concurrency token.
        if (previous != null && previous.etag == null) throw IOException("Nextcloud liefert keinen ETag für den Fortschritt.")
        val condition = if (previous == null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to previous.etag!!)
        execute(settings, path, "PUT", text, condition).use { response ->
            if (response.code !in listOf(200, 201, 204)) throw DavException(response.code)
        }
    }

    companion object {
        private const val MAX_XML = 4 * 1024 * 1024
        private fun readLimited(input: java.io.InputStream, limit: Int): String {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit) { "Nextcloud-Antwort zu groß." }
                output.write(buffer, 0, count)
            }
            return output.toString("UTF-8")
        }
        private const val PROPERTIES = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/></d:prop></d:propfind>"""

        internal fun parseListing(xml: String, base: HttpUrl, requested: HttpUrl): List<RemoteEntry> {
            require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "Unsichere XML-Antwort." }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            require(document.documentElement.localName == "multistatus" && document.documentElement.namespaceURI == "DAV:") { "Ungültige WebDAV-Antwort." }
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            val baseSegments = base.pathSegments.filter { it.isNotEmpty() }
            val requestedSegments = requested.pathSegments.filter { it.isNotEmpty() }
            return (0 until responses.length).mapNotNull { index ->
                val item = responses.item(index) as Element
                fun text(element: Element, name: String) = element.getElementsByTagNameNS("DAV:", name).item(0)?.textContent
                val href = text(item, "href") ?: throw IOException("WebDAV-Pfad fehlt.")
                val url = requested.newBuilder().addPathSegment("").build().resolve(href) ?: throw IOException("Ungültiger WebDAV-Pfad.")
                require(url.scheme == base.scheme && url.host == base.host && url.port == base.port) { "Fremder WebDAV-Server in Antwort." }
                val segments = url.pathSegments.filter { it.isNotEmpty() }
                require(segments.none { '/' in it || '\\' in it }) { "Mehrdeutiger WebDAV-Pfad." }
                if (segments == requestedSegments) return@mapNotNull null
                require(segments.take(requestedSegments.size) == requestedSegments && segments.size == requestedSegments.size + 1) { "WebDAV-Antwort außerhalb des angefragten Ordners." }
                require(segments.take(baseSegments.size) == baseSegments) { "Ungültiger WebDAV-Pfad." }
                val propstats = item.getElementsByTagNameNS("DAV:", "propstat")
                val props = (0 until propstats.length).map { propstats.item(it) as Element }
                    .firstOrNull { text(it, "status")?.split(' ')?.getOrNull(1) == "200" }
                    ?: throw IOException("Ordner unvollständig oder nicht lesbar.")
                val relative = segments.drop(baseSegments.size).joinToString("/")
                NextcloudSettings.segments(relative)
                RemoteEntry(relative, props.getElementsByTagNameNS("DAV:", "collection").length > 0,
                    text(props, "getcontentlength")?.toLongOrNull() ?: 0, text(props, "getetag").orEmpty())
            }
        }
    }
}
