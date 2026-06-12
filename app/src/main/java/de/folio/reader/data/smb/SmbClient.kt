package de.folio.reader.data.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import de.folio.reader.domain.model.SmbSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.EnumSet

/** Eine Datei oder ein Ordner auf der Freigabe. */
data class SmbEntry(
    /** Pfad relativ zum konfigurierten Wurzelordner, mit "/" getrennt. */
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
)

/**
 * Dünner, blockierender Wrapper um smbj. Jede öffentliche Methode öffnet eine
 * eigene Verbindung über [withShare] und schließt sie wieder – robust gegenüber
 * Verbindungsabbrüchen im mobilen Netz. Für Massenoperationen (Sync) kann ein
 * Block direkt mit [session] gebündelt werden.
 */
class SmbClient {

    /** SMB nutzt Backslashes; wir arbeiten intern mit "/" und konvertieren hier. */
    private fun smbPath(vararg parts: String): String =
        parts.filter { it.isNotBlank() }
            .joinToString("\\") { it.trim('/', '\\') }
            .replace('/', '\\')

    private inline fun <T> withShare(settings: SmbSettings, block: (DiskShare) -> T): T {
        if (!settings.isConfigured) throw SmbException("NAS ist nicht konfiguriert.")
        return try {
            SMBClient().connect(settings.host).use { connection ->
                val auth = if (settings.username.isBlank()) {
                    AuthenticationContext.anonymous()
                } else {
                    AuthenticationContext(
                        settings.username,
                        settings.password.toCharArray(),
                        settings.domain.ifBlank { null },
                    )
                }
                val session = connection.authenticate(auth)
                (session.connectShare(settings.shareName) as DiskShare).use { share ->
                    block(share)
                }
            }
        } catch (e: SmbException) {
            throw e
        } catch (e: Exception) {
            throw SmbException("SMB-Fehler: ${e.message}", e)
        }
    }

    /** Prüft, ob Verbindung und Anmeldung funktionieren. */
    suspend fun testConnection(settings: SmbSettings): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { withShare(settings) { it.list(smbPath(settings.rootPath)) } }
            .map { }
    }

    /** Listet rekursiv alle EPUB-Dateien unterhalb des Wurzelordners. */
    suspend fun listEpubs(settings: SmbSettings): List<SmbEntry> = withContext(Dispatchers.IO) {
        withShare(settings) { share ->
            val result = mutableListOf<SmbEntry>()
            walk(share, settings.rootPath.trim('/', '\\'), result)
            result.filter { it.name.endsWith(".epub", ignoreCase = true) }
        }
    }

    private fun walk(share: DiskShare, dir: String, out: MutableList<SmbEntry>) {
        val listing = try {
            share.list(dir.replace('/', '\\'))
        } catch (_: Exception) {
            return
        }
        for (item in listing) {
            val name = item.fileName
            if (name == "." || name == "..") continue
            val rel = if (dir.isBlank()) name else "$dir/$name"
            val isDir = item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            if (isDir) {
                // versteckte Ordner (z. B. Fortschritts-Ordner) überspringen
                if (name.startsWith(".")) continue
                walk(share, rel, out)
            } else {
                // rel ist bereits relativ zur Freigabe.
                out += SmbEntry(
                    relativePath = rel,
                    name = name,
                    isDirectory = false,
                    size = item.endOfFile,
                    modified = item.lastWriteTime?.toEpochMillis() ?: 0L,
                )
            }
        }
    }

    /** Lädt eine Datei (Pfad relativ zur Freigabe) in [target]. */
    suspend fun download(settings: SmbSettings, sharePath: String, target: File) =
        withContext(Dispatchers.IO) {
            withShare(settings) { share ->
                target.parentFile?.mkdirs()
                openRead(share, sharePath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                }
            }
        }

    /** Liest eine kleine Datei vollständig als String; null wenn nicht vorhanden. */
    suspend fun readTextOrNull(settings: SmbSettings, sharePath: String): String? =
        withContext(Dispatchers.IO) {
            withShare(settings) { share ->
                val p = sharePath.replace('/', '\\')
                if (!share.fileExists(p)) return@withShare null
                openRead(share, sharePath).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }

    /** Schreibt Text in eine Datei (Pfad relativ zur Freigabe), Ordner werden angelegt. */
    suspend fun writeText(settings: SmbSettings, sharePath: String, content: String) =
        withContext(Dispatchers.IO) {
            withShare(settings) { share ->
                ensureParentDirs(share, sharePath)
                val file = share.openFile(
                    sharePath.replace('/', '\\'),
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.noneOf(SMB2CreateOptions::class.java),
                )
                file.use { f ->
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    f.write(bytes, 0L)
                    f.flush()
                }
            }
        }

    private fun openRead(share: DiskShare, sharePath: String): InputStream {
        val file = share.openFile(
            sharePath.replace('/', '\\'),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(SMB2CreateOptions::class.java),
        )
        return file.inputStream
    }

    private fun ensureParentDirs(share: DiskShare, sharePath: String) {
        val parts = sharePath.replace('\\', '/').split('/').dropLast(1)
        var current = ""
        for (part in parts) {
            if (part.isBlank()) continue
            current = if (current.isEmpty()) part else "$current/$part"
            val winPath = current.replace('/', '\\')
            if (!share.folderExists(winPath)) {
                runCatching { share.mkdir(winPath) }
            }
        }
    }
}
