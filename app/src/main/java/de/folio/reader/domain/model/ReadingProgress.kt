package de.folio.reader.domain.model

import org.json.JSONObject

/**
 * Geräteübergreifende Metadaten eines Buches: Leseposition, „fertig gelesen"
 * und Favorit.
 *
 * Wird pro Buch als eigene JSON-Datei gespeichert (lokal in
 * filesDir/progress/<id>.json) und bei nächster Gelegenheit per Nextcloud auf das Nextcloud
 * synchronisiert. Der Abgleich erfolgt FELDWEISE per Last-Write-Wins:
 * Leseposition hängt an [updatedAt], manuelle Lesestatus-Änderungen an
 * [finishedUpdatedAt], der Favorit an [favoriteUpdatedAt]. So überschreibt Weiterlesen auf Gerät B nicht das
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
    /** Eigener Zeitstempel für explizites Gelesen/Ungelesen; 0 = automatische Erkennung. */
    val finishedUpdatedAt: Long = 0L,
    val positionRevision: Long = 0L,
    val favoriteRevision: Long = 0L,
    val finishedRevision: Long = 0L,
    val favoriteDeviceId: String = "",
    val finishedDeviceId: String = "",
    val contentRevision: String = "",
    val chapterPath: String = "",
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
        put("finishedUpdatedAt", finishedUpdatedAt)
        put("positionRevision", positionRevision)
        put("favoriteRevision", favoriteRevision)
        put("finishedRevision", finishedRevision)
        put("favoriteDeviceId", favoriteDeviceId)
        put("finishedDeviceId", finishedDeviceId)
        put("contentRevision", contentRevision)
        put("chapterPath", chapterPath)
        put("schema", SCHEMA_VERSION)
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 5

        fun fromJson(raw: String): ReadingProgress {
            val o = JSONObject(raw)
            require(o.optInt("schema", 1) in 1..SCHEMA_VERSION) { "Fortschrittsformat ist neuer als diese App. Bitte aktualisieren." }
            val fraction = o.optDouble("scrollFraction", 0.0).toFloat()
            require(fraction.isFinite() && fraction in 0f..1f && o.optInt("spineIndex", 0) >= 0 && o.optInt("charOffset", -1) >= -1) { "Ungültige Leseposition." }
            return ReadingProgress(
                bookId = o.getString("bookId"),
                spineIndex = o.optInt("spineIndex", 0),
                scrollFraction = fraction,
                charOffset = o.optInt("charOffset", -1),
                updatedAt = o.optLong("updatedAt", 0L),
                deviceId = o.optString("deviceId", "unknown"),
                finished = o.optBoolean("finished", false),
                favorite = o.optBoolean("favorite", false),
                favoriteUpdatedAt = o.optLong("favoriteUpdatedAt", 0L),
                finishedUpdatedAt = o.optLong("finishedUpdatedAt", 0L),
                positionRevision = o.optLong("positionRevision", 0L),
                favoriteRevision = o.optLong("favoriteRevision", 0L),
                finishedRevision = o.optLong("finishedRevision", 0L),
                favoriteDeviceId = o.optString("favoriteDeviceId", ""),
                finishedDeviceId = o.optString("finishedDeviceId", ""),
                contentRevision = o.optString("contentRevision", ""),
                chapterPath = o.optString("chapterPath", ""),
            )
        }

        /**
         * Feldweiser Merge zweier Stände: Leseposition inkl. [charOffset]
         * vom Stand mit dem neueren [updatedAt], manueller Lesestatus und Favorit
         * jeweils mit eigenem Zeitstempel. Automatische Erkennung (Zeitstempel 0)
         * überschreibt keine explizite Entscheidung. Das Ergebnis kann Felder beider Seiten mischen.
         */
        fun merge(a: ReadingProgress?, b: ReadingProgress?): ReadingProgress? {
            if (a == null) return b
            if (b == null) return a
            require(a.bookId == b.bookId) { "Lesestände gehören zu verschiedenen Büchern." }
            val readingBase = maxOf(a, b, compareBy<ReadingProgress>({ it.updatedAt }, { it.positionRevision }, { it.deviceId }, { it.spineIndex }, { it.scrollFraction }, { it.charOffset }, { it.contentRevision }, { it.chapterPath }))
            val favoriteBase = maxOf(a, b, compareBy<ReadingProgress>({ it.favoriteUpdatedAt }, { it.favoriteRevision }, { it.favoriteDeviceId }, { it.favorite }))
            val finishedBase = if (a.finishedUpdatedAt == 0L && b.finishedUpdatedAt == 0L) readingBase
                else maxOf(a, b, compareBy<ReadingProgress>({ it.finishedUpdatedAt }, { it.finishedRevision }, { it.finishedDeviceId }, { it.finished }))
            return readingBase.copy(
                finished = finishedBase.finished,
                finishedUpdatedAt = finishedBase.finishedUpdatedAt,
                favorite = favoriteBase.favorite,
                favoriteUpdatedAt = favoriteBase.favoriteUpdatedAt,
                favoriteRevision = favoriteBase.favoriteRevision,
                favoriteDeviceId = favoriteBase.favoriteDeviceId,
                finishedRevision = finishedBase.finishedRevision,
                finishedDeviceId = finishedBase.finishedDeviceId,
            )
        }
    }
}
