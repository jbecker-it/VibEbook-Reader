package de.folio.reader.data.nextcloud

import de.folio.reader.domain.model.NextcloudSettings
import de.folio.reader.domain.model.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.buffer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
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

data class RemoteEntry(val relativePath: String, val directory: Boolean, val size: Long, val etag: String, val remoteId: String = "", val favorite: Boolean = false)
data class RemoteText(val content: String, val etag: String?)
class DavException(val status: Int, operation: String = "WebDAV") : IOException("$operation (HTTP $status): " + when (status) {
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
            .method(method, body?.toRequestBody((if (method in listOf("PROPFIND", "PROPPATCH")) "application/xml; charset=utf-8" else "application/json; charset=utf-8").toMediaType()))
        headers.forEach { (key, value) -> request.header(key, value) }
        return executeRequest(request.build())
    }

    private suspend fun executeRequest(request: Request): Response {
        val job = coroutineContext[Job]
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
            val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause -> if (cause != null) call.cancel() }
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { cancellation?.dispose(); if (continuation.isActive) continuation.resumeWithException(e) }
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body
                    val managed = if (body == null) response else response.newBuilder().body(object : okhttp3.ResponseBody() {
                        private val managedSource = object : okio.ForwardingSource(body.source()) {
                            override fun close() { try { super.close() } finally { cancellation?.dispose() } }
                        }.buffer()
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source() = managedSource
                    }).build()
                    if (body == null) cancellation?.dispose()
                    continuation.resume(managed) { managed.close() }
                }
            })
        }
    }

    class LoginSession(val server: HttpUrl, val loginUrl: HttpUrl, val endpoint: HttpUrl, val token: String) {
        override fun toString() = "LoginSession(redacted)"
    }
    internal fun sameServer(base: HttpUrl, value: String): HttpUrl = value.toHttpUrl().also {
        require(it.isHttps && it.host == base.host && it.port == base.port && it.username.isEmpty() && it.password.isEmpty()) { "Nextcloud-Anmeldung verweist auf einen anderen Server." }
    }
    suspend fun startLogin(serverAddress: String): LoginSession = withContext(Dispatchers.IO) {
        val base = serverAddress.trim().trimEnd('/').toHttpUrl()
        require(base.isHttps && base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "Eine HTTPS-Serveradresse ohne Zugangsdaten eingeben." }
        val request = Request.Builder().url(base.newBuilder().addPathSegments("index.php/login/v2").build())
            .header("User-Agent", "Folio Reader Android").post(FormBody.Builder().build()).build()
        executeRequest(request).use { r ->
            if (r.code != 200) throw DavException(r.code, "Nextcloud-Anmeldung starten")
            val json = JSONObject(r.body?.byteStream()?.use { readLimited(it, 65536) } ?: throw IOException("Leere Anmeldung."))
            val poll = json.getJSONObject("poll")
            LoginSession(base, sameServer(base, json.getString("login")), sameServer(base, poll.getString("endpoint")), poll.getString("token"))
        }
    }
    suspend fun pollLogin(session: LoginSession): NextcloudSettings? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(session.endpoint).header("User-Agent", "Folio Reader Android")
            .post(FormBody.Builder().add("token", session.token).build()).build()
        executeRequest(request).use { r ->
            if (r.code == 404) return@withContext null
            if (r.code != 200) throw DavException(r.code, "Nextcloud-Anmeldung bestätigen")
            val json = JSONObject(r.body?.byteStream()?.use { readLimited(it, 65536) } ?: throw IOException("Leere Anmeldung."))
            val server = sameServer(session.server, json.getString("server"))
            val settings = NextcloudSettings(server.toString().trimEnd('/'), json.getString("loginName"), json.getString("appPassword"))
            settings.validate()
            resolveAccount(settings)
        }
    }
    suspend fun resolveAccount(settings: NextcloudSettings): NextcloudSettings = withContext(Dispatchers.IO) {
        settings.validate()
        val url = settings.serverUrl.trimEnd('/').toHttpUrl().newBuilder().addPathSegments("ocs/v2.php/cloud/user").addQueryParameter("format", "json").build()
        val request = Request.Builder().url(url).header("OCS-APIRequest", "true")
            .header("Authorization", Credentials.basic(settings.username.trim(), settings.password, Charsets.UTF_8)).build()
        executeRequest(request).use { r ->
            if (r.code != 200) throw DavException(r.code, "Nextcloud-Benutzer prüfen")
            val json = JSONObject(r.body?.byteStream()?.use { readLimited(it, 65536) } ?: throw IOException("Leere Benutzerantwort.")).getJSONObject("ocs")
            require(json.getJSONObject("meta").optInt("statuscode") in listOf(100, 200)) { "Nextcloud-Benutzer konnte nicht geprüft werden." }
            settings.copy(davUser = json.getJSONObject("data").getString("id")).also { it.validate() }
        }
    }

    suspend fun testConnection(settings: NextcloudSettings): Result<Unit> = try {
        list(settings, settings.rootPath)
        val probe = settings.progressDir.trim('/') + "/.folio-probe-" + java.util.UUID.randomUUID() + ".json"
        val payload = "{\"folioProbe\":true}"
        var created = false
        try {
            writeText(settings, probe, payload, null); created = true
            require(readTextOrNull(settings, probe)?.content == payload) { "Fortschrittsordner konnte nicht zurückgelesen werden." }
        } finally {
            if (created) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                execute(settings, probe, "DELETE").use { require(it.code in listOf(200, 204, 404)) { "Testdatei konnte nicht entfernt werden." } }
            }
        }
        Result.success(Unit)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) { Result.failure(e) }

    suspend fun list(settings: NextcloudSettings, path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        execute(settings, path, "PROPFIND", PROPERTIES, mapOf("Depth" to "1")).use { response ->
            if (response.code != 207) throw DavException(response.code, "Ordner lesen / PROPFIND")
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
                } else if (entry.relativePath.endsWith(".epub", true) || entry.relativePath.endsWith(".cbz", true)) books += entry
            }
        }
        return books
    }

    suspend fun download(settings: NextcloudSettings, path: String, target: File, etag: String = "", maxBytes: Long = 1024L * 1024 * 1024) = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("download-", ".part", target.parentFile)
        try {
            execute(settings, path, "GET", headers = if (etag.isBlank()) emptyMap() else mapOf("If-Match" to etag)).use { response ->
                if (response.code != 200) throw DavException(response.code, "Buch laden / GET")
                val body = response.body ?: throw IOException("Leere Buchantwort.")
                require(body.contentLength() <= maxBytes) { "Buch überschreitet das Offline-Speicherlimit." }
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "Buch überschreitet das Offline-Speicherlimit." }
                        output.write(buffer, 0, count)
                    }
                } }
                if (body.contentLength() >= 0 && temporary.length() != body.contentLength()) throw IOException("Unvollständiger Download.")
            }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    suspend fun readTextOrNull(settings: NextcloudSettings, path: String, limit: Int = 65536): RemoteText? = withContext(Dispatchers.IO) {
        // Compression proxies can alter ETags; conditional PUT needs the identity representation.
        // Revalidate even cached 404s, especially after a failed create (If-None-Match: *).
        execute(settings, path, "GET", headers = mapOf(
            "Accept-Encoding" to "identity", "Cache-Control" to "no-cache, no-store"
        )).use { response ->
            if (response.code == 404) return@withContext null
            if (response.code != 200) throw DavException(response.code, "Fortschritt lesen / GET")
            val content = response.body?.byteStream()?.use { input ->
                readLimited(input, limit)
            } ?: throw IOException("Leere Fortschrittsdatei.")
            RemoteText(content, response.header("ETag"))
        }
    }

    /** Server file IDs map to the legacy public progress IDs across devices and renames. */
    suspend fun bookRegistry(settings: NextcloudSettings, discovered: Map<String, String>): Map<String, String> {
        if (discovered.isEmpty()) return emptyMap()
        val path = settings.progressDir.trim('/') + "/catalog.json"
        repeat(5) { attempt ->
            val previous = readTextOrNull(settings, path, 2 * 1024 * 1024)
            val json = previous?.let { JSONObject(it.content) } ?: JSONObject().put("schema", 1).put("books", JSONObject())
            require(json.optInt("schema") == 1) { "Unbekanntes Bibliotheksregister." }
            val books = json.getJSONObject("books")
            require(books.length() <= 10000) { "Bibliotheksregister zu groß." }
            discovered.forEach { (remoteId, id) -> if (!books.has(remoteId)) books.put(remoteId, id) }
            val result = books.keys().asSequence().associateWith { key -> books.getString(key).also { require(it.matches(Regex("[a-f0-9]{32}"))) { "Ungültige Buch-ID im Register." } } }
            if (previous != null && JSONObject(previous.content).getJSONObject("books").toString() == books.toString()) return result
            try { writeText(settings, path, json.toString(), previous); return result }
            catch (e: DavException) { if (e.status != 412 || attempt == 4) throw e }
        }
        error("Unreachable")
    }

    suspend fun setFavorite(settings: NextcloudSettings, path: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        val xml = """<?xml version="1.0"?><d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:set><d:prop><oc:favorite>${if (favorite) 1 else 0}</oc:favorite></d:prop></d:set></d:propertyupdate>"""
        execute(settings, path, "PROPPATCH", xml).use { response ->
            if (response.code != 207) throw DavException(response.code, "Nextcloud-Favorit speichern")
            val text = response.body?.byteStream()?.use { readLimited(it, MAX_XML) }.orEmpty()
            require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
            val ps = doc.getElementsByTagNameNS("DAV:", "propstat")
            require((0 until ps.length).any { i -> val p = ps.item(i) as Element; p.getElementsByTagNameNS("http://owncloud.org/ns", "favorite").length > 0 && p.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent?.split(' ')?.getOrNull(1) == "200" }) { "Nextcloud-Favoriten werden vom Server nicht unterstützt." }
        }
    }

    /** Re-read and merge after a concurrent write; never retry a stale payload/token. */
    suspend fun syncProgress(settings: NextcloudSettings, path: String, bookId: String,
        readLocal: suspend () -> ReadingProgress?): ReadingProgress? {
        repeat(3) { attempt ->
            val remoteFile = readTextOrNull(settings, path)
            val remote = remoteFile?.let { ReadingProgress.fromJson(it.content) }
            val now = System.currentTimeMillis()
            require(remote == null || listOf(remote.updatedAt, remote.favoriteUpdatedAt, remote.finishedUpdatedAt).all { it <= now + 86400000 }) { "Lesestand liegt in der Zukunft. Gerätezeit auf beiden Geräten prüfen." }
            require(remote == null || remote.bookId == bookId) { "Fortschrittsdatei gehört zu einem anderen Buch." }
            val local = readLocal()
            require(local == null || listOf(local.updatedAt, local.favoriteUpdatedAt, local.finishedUpdatedAt).all { it <= now + 86400000 }) { "Lokaler Lesestand liegt in der Zukunft. Gerätezeit prüfen." }
            require(local == null || local.bookId == bookId) { "Lokaler Fortschritt gehört zu einem anderen Buch." }
            val merged = ReadingProgress.merge(local, remote) ?: return null
            if (merged == remote) return merged
            try {
                writeText(settings, path, merged.toJson(), remoteFile)
                return merged
            } catch (e: DavException) {
                if (e.status != 412 || attempt == 2) throw e
            }
        }
        error("Unreachable")
    }

    suspend fun writeText(settings: NextcloudSettings, path: String, text: String, previous: RemoteText?) {
        var parent = ""
        for (segment in NextcloudSettings.segments(path).dropLast(1)) {
            parent = listOf(parent, segment).filter { it.isNotEmpty() }.joinToString("/")
            execute(settings, parent, "MKCOL", "").use { response ->
                if (response.code != 201 && response.code != 405) throw DavException(response.code, "Fortschrittsordner anlegen / MKCOL")
            }
        }
        // Never blindly overwrite progress read without a concurrency token.
        if (previous != null && previous.etag == null) throw IOException("Nextcloud liefert keinen ETag für den Fortschritt.")
        val condition = if (previous == null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to previous.etag!!)
        execute(settings, path, "PUT", text, condition).use { response ->
            if (response.code !in listOf(200, 201, 204)) throw DavException(response.code, "Fortschritt speichern / PUT")
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
        private const val PROPERTIES = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><oc:id/><oc:favorite/></d:prop></d:propfind>"""

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
            require(responses.length > 0) { "Unvollständige WebDAV-Antwort." }
            var foundRoot = false
            val entries = (0 until responses.length).mapNotNull { index ->
                val item = responses.item(index) as Element
                fun text(element: Element, name: String) = element.getElementsByTagNameNS("DAV:", name).item(0)?.textContent
                val href = text(item, "href") ?: throw IOException("WebDAV-Pfad fehlt.")
                val url = requested.newBuilder().addPathSegment("").build().resolve(href) ?: throw IOException("Ungültiger WebDAV-Pfad.")
                require(url.scheme == base.scheme && url.host == base.host && url.port == base.port) { "Fremder WebDAV-Server in Antwort." }
                val segments = url.pathSegments.filter { it.isNotEmpty() }
                require(segments.none { '/' in it || '\\' in it }) { "Mehrdeutiger WebDAV-Pfad." }
                if (segments == requestedSegments) {
                    val status = text(item, "status")
                    require(status == null || status.split(' ').getOrNull(1) == "200") { "Bibliotheksordner nicht lesbar." }
                    val ps = item.getElementsByTagNameNS("DAV:", "propstat")
                    require((0 until ps.length).any { text(ps.item(it) as Element, "status")?.split(' ')?.getOrNull(1) == "200" }) { "Bibliotheksordner nicht vollständig lesbar." }
                    foundRoot = true
                    return@mapNotNull null
                }
                require(segments.take(requestedSegments.size) == requestedSegments && segments.size == requestedSegments.size + 1) { "WebDAV-Antwort außerhalb des angefragten Ordners." }
                require(segments.take(baseSegments.size) == baseSegments) { "Ungültiger WebDAV-Pfad." }
                val propstats = item.getElementsByTagNameNS("DAV:", "propstat")
                val successful = (0 until propstats.length).map { propstats.item(it) as Element }.filter { text(it, "status")?.split(' ')?.getOrNull(1) == "200" }
                require(successful.isNotEmpty()) { "Ordner unvollständig oder nicht lesbar." }
                val props = item.ownerDocument.createElement("properties")
                successful.forEach { props.appendChild(it.cloneNode(true)) }
                val relative = segments.drop(baseSegments.size).joinToString("/")
                NextcloudSettings.segments(relative)
                RemoteEntry(relative, props.getElementsByTagNameNS("DAV:", "collection").length > 0,
                    text(props, "getcontentlength")?.toLongOrNull() ?: 0, text(props, "getetag").orEmpty(),
                    props.getElementsByTagNameNS("http://owncloud.org/ns", "id").item(0)?.textContent.orEmpty(),
                    props.getElementsByTagNameNS("http://owncloud.org/ns", "favorite").item(0)?.textContent == "1")
            }
            require(foundRoot) { "Bibliotheksordner fehlt in WebDAV-Antwort." }
            return entries
        }
    }
}
