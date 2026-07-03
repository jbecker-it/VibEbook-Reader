package de.folio.reader.domain.model

import org.json.JSONObject

/**
 * Geräteübergreifende Metadaten eines Buches: Leseposition, „fertig gelesen"
 * und Favorit.
 *
 * Wird pro Buch als eigene JSON-Datei gespeichert (lokal in
 * filesDir/progress/<id>.json) und bei nächster Gelegenheit per SMB auf das NAS
 * synchronisiert. Der Abgleich erfolgt FELDWEISE per Last-Write-Wins:
 * Leseposition/finished hängen an [updatedAt], der Favorit an
 * [favoriteUpdatedAt]. So überschreibt Weiterlesen auf Gerät B nicht das
 * Favorisieren auf Gerät A – und umgekehrt.
 */
data class ReadingProgress(
    val bookId: String,
    /** Index im Spine (aktuelles Kapitel). */
    val spineIndex: Int,
    /** Position innerhalb des Kapitels als Anteil 0.0 .. 1.0 (Fallback). */
    val scrollFraction: Float,
    /**
     * Wortgenauer Anker: Zeichen-Offset des ersten sichtbaren Worts im
     * Kapiteltext. Geräteunabhängig, da alle Geräte dieselbe EPUB-Datei
     * rendern; -1 = unbekannt (dann greift [scrollFraction]).
     */
    val charOffset: Int = -1,
    /** Epoch-Millis der letzten Lese-Aktualisierung. */
    val updatedAt: Long,
    /** Gerät, das diesen Stand zuletzt geschrieben hat (nur informativ). */
    val deviceId: String,
    val finished: Boolean = false,
    val favorite: Boolean = false,
    /** Epoch-Millis der letzten Favoriten-Änderung (eigener Konfliktzeitstempel). */
    val favoriteUpdatedAt: Long = 0L,
) {
    fun toJson(): String = JSONObject().apply {
        put("bookId", bookId)
        put("spineIndex", spineIndex)
        put("scrollFraction", scrollFraction.toDouble())
        put("charOffset", charOffset)
        put("updatedAt", updatedAt)
        put("deviceId", deviceId)
        put("finished", finished)
        put("favorite", favorite)
        put("favoriteUpdatedAt", favoriteUpdatedAt)
        put("schema", SCHEMA_VERSION)
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 3

        fun fromJson(raw: String): ReadingProgress {
            val o = JSONObject(raw)
            return ReadingProgress(
                bookId = o.getString("bookId"),
                spineIndex = o.optInt("spineIndex", 0),
                scrollFraction = o.optDouble("scrollFraction", 0.0).toFloat(),
                charOffset = o.optInt("charOffset", -1),
                updatedAt = o.optLong("updatedAt", 0L),
                deviceId = o.optString("deviceId", "unknown"),
                finished = o.optBoolean("finished", false),
                favorite = o.optBoolean("favorite", false),
                favoriteUpdatedAt = o.optLong("favoriteUpdatedAt", 0L),
            )
        }

        /**
         * Feldweiser Merge zweier Stände: Leseposition inkl. [charOffset]
         * (+finished) vom Stand
         * mit dem neueren [updatedAt], Favorit vom Stand mit dem neueren
         * [favoriteUpdatedAt]. Das Ergebnis kann Felder beider Seiten mischen.
         */
        fun merge(a: ReadingProgress?, b: ReadingProgress?): ReadingProgress? {
            if (a == null) return b
            if (b == null) return a
            val readingBase = if (a.updatedAt >= b.updatedAt) a else b
            val favoriteBase = if (a.favoriteUpdatedAt >= b.favoriteUpdatedAt) a else b
            return readingBase.copy(
                favorite = favoriteBase.favorite,
                favoriteUpdatedAt = favoriteBase.favoriteUpdatedAt,
            )
        }
    }
}
