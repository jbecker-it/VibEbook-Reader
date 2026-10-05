package de.folio.reader.data.nextcloud

import de.folio.reader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class UpdateClient @Inject constructor() {
    data class Update(val version: Int, val url: String)
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()
        val request = Request.Builder().url("https://api.github.com/repos/jbecker-it/VibEbook-Reader/releases?per_page=30")
            .header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { r ->
            require(r.isSuccessful) { "Updateprüfung fehlgeschlagen (HTTP ${r.code})." }
            val body = r.body ?: error("Leere Updateantwort.")
            val raw = body.byteStream().use { input -> val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 2 * 1024 * 1024) { "Updateantwort zu groß." }; out.write(buffer, 0, n) }; out.toString("UTF-8") }
            val releases = JSONArray(raw)
            (0 until releases.length()).mapNotNull { i ->
                val release = releases.getJSONObject(i)
                val version = release.optString("tag_name").removePrefix("folio-1.0.").toIntOrNull()
                if (release.optBoolean("draft") || version == null || version <= BuildConfig.VERSION_CODE) null else {
                    val assets = release.optJSONArray("assets") ?: JSONArray()
                    (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == "folio.apk" }?.optString("browser_download_url")
                        ?.takeIf { it.startsWith("https://github.com/jbecker-it/VibEbook-Reader/releases/download/") }?.let { Update(version, it) }
                }
            }.maxByOrNull { it.version }
        }
    }
}
