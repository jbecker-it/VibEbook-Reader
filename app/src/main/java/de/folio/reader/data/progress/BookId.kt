package de.folio.reader.data.progress

import java.security.MessageDigest

/**
 * Erzeugt aus dem relativen NAS-Pfad eine stabile ID. Da der Pfad auf jedem Gerät
 * gleich ist, erhält dasselbe Buch überall dieselbe ID – Voraussetzung dafür, dass
 * die Fortschrittsdateien geräteübergreifend zueinander passen.
 */
object BookId {
    fun fromPath(relativePath: String): String {
        val normalized = relativePath.replace('\\', '/').trim('/')
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
