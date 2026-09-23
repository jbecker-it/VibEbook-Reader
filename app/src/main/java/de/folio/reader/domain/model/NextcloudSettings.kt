package de.folio.reader.domain.model

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class NextcloudSettings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val rootPath: String = "",
    val progressDir: String = ".folio-progress",
) {
    val isConfigured: Boolean get() = runCatching { validate() }.isSuccess

    fun validate() {
        val url = serverUrl.trim().toHttpUrl()
        require(url.isHttps) { "Bitte eine HTTPS-Serveradresse verwenden." }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Serveradresse ohne Zugangsdaten, Abfrage oder Fragment eingeben."
        }
        require(username.isNotBlank() && ':' !in username && '/' !in username) { "Nextcloud-Benutzernamen eingeben." }
        require(password.isNotBlank()) { "Ein Nextcloud-App-Passwort ist erforderlich." }
        segments(rootPath)
        require(segments(progressDir).isNotEmpty()) { "Fortschrittsordner eingeben." }
    }

    fun davUrl(path: String = ""): HttpUrl {
        validate()
        val base = serverUrl.trim().trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("remote.php/dav/files").addPathSegment(username.trim())
        segments(path).forEach { base.addPathSegment(it) }
        return base.build()
    }

    // Credentials must never appear in logs or exception messages.
    override fun toString() = "NextcloudSettings(credentials=redacted)"

    companion object {
        fun segments(path: String): List<String> = path.trim('/').split('/').filter { it.isNotEmpty() }.also { parts ->
            require(parts.none { it == "." || it == ".." || '\\' in it || it.any(Char::isISOControl) }) {
                "Ungültiger Ordnerpfad."
            }
        }
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
enum class PageLayoutMode { AUTO, SINGLE, DOUBLE }
