package de.folio.reader.domain.model

import org.json.JSONObject

/**
 * Geräteübergreifender Lesefortschritt eines Buches.
 *
 * Wird pro Buch als eigene JSON-Datei gespeichert (lokal in filesDir/progress/<id>.json)
 * und bei nächster Gelegenheit per SMB auf das NAS synchronisiert. Konflikte werden per
 * Last-Write-Wins über [updatedAt] aufgelöst.
 */
data class ReadingProgress(
    val bookId: String,
    /** Index im Spine (aktuelles Kapitel). */
    val spineIndex: Int,
    /** Scrollposition innerhalb des Kapitels, 0.0 .. 1.0. */
    val scrollFraction: Float,
    /** Epoch-Millis der letzten Aktualisierung – Grundlage des Konfliktabgleichs. */
    val updatedAt: Long,
    /** Gerät, das diesen Stand zuletzt geschrieben hat (nur informativ). */
    val deviceId: String,
    val finished: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("bookId", bookId)
        put("spineIndex", spineIndex)
        put("scrollFraction", scrollFraction.toDouble())
        put("updatedAt", updatedAt)
        put("deviceId", deviceId)
        put("finished", finished)
        put("schema", SCHEMA_VERSION)
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(raw: String): ReadingProgress {
            val o = JSONObject(raw)
            return ReadingProgress(
                bookId = o.getString("bookId"),
                spineIndex = o.optInt("spineIndex", 0),
                scrollFraction = o.optDouble("scrollFraction", 0.0).toFloat(),
                updatedAt = o.optLong("updatedAt", 0L),
                deviceId = o.optString("deviceId", "unknown"),
                finished = o.optBoolean("finished", false),
            )
        }

        /** Gibt den jeweils neueren der beiden Stände zurück (Last-Write-Wins). */
        fun newer(a: ReadingProgress?, b: ReadingProgress?): ReadingProgress? = when {
            a == null -> b
            b == null -> a
            a.updatedAt >= b.updatedAt -> a
            else -> b
        }
    }
}
