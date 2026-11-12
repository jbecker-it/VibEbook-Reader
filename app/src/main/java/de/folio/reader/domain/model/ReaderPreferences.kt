package de.folio.reader.domain.model

data class ReaderPreferences(
    val fontSize: Int = 20,
    val lineHeight: Float = 1.6f,
    val margin: Int = 24,
    val sansSerif: Boolean = false,
    val leftHanded: Boolean = false,
    val wideTapZones: Boolean = false,
    val volumeKeys: Boolean = false,
    val lockOrientation: Boolean = false,
    val keepScreenOn: Boolean = false,
)
