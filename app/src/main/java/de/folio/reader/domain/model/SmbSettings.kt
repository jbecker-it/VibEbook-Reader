package de.folio.reader.domain.model

/** Verbindungsdaten für die NAS-Freigabe. */
data class SmbSettings(
    val host: String = "",
    val shareName: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    /** Unterordner innerhalb der Freigabe, der die Bücher enthält ("" = Wurzel). */
    val rootPath: String = "",
    /** Ordner innerhalb der Freigabe für Fortschrittsdateien. */
    val progressDir: String = ".folio-progress",
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && shareName.isNotBlank()
}

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/**
 * Seitenlayout im Reader. AUTO wählt ab ~600 dp Fensterbreite (aufgeklapptes
 * Foldable, Tablet quer) automatisch die zweiseitige Darstellung.
 */
enum class PageLayoutMode { AUTO, SINGLE, DOUBLE }
