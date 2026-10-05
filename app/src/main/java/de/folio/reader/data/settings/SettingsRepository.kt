package de.folio.reader.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.folio.reader.domain.model.PageLayoutMode
import de.folio.reader.domain.model.NextcloudSettings
import de.folio.reader.domain.model.ThemeMode
import de.folio.reader.domain.model.ReaderPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "folio_settings", produceMigrations = {
    listOf(object : androidx.datastore.core.DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences) = currentData.asMap().keys.any { it.name.startsWith("smb_") }
        override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
            currentData.asMap().keys.filter { it.name.startsWith("smb_") }.forEach { remove(it) }
        }
        override suspend fun cleanUp() = Unit
    })
})

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: CredentialCipher,
) {
    private object Keys {
        val SERVER = stringPreferencesKey("nextcloud_server")
        val USER = stringPreferencesKey("nextcloud_user")
        val PASS = stringPreferencesKey("nextcloud_encrypted_password")
        val ROOT = stringPreferencesKey("nextcloud_root")
        val PROGRESS_DIR = stringPreferencesKey("nextcloud_progress_dir")
        val THEME = stringPreferencesKey("theme_mode")
        val PAGE_LAYOUT = stringPreferencesKey("page_layout")
        val WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val EINK = booleanPreferencesKey("eink_mode")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val LIBRARY_BOUND = booleanPreferencesKey("nextcloud_library_bound")
    }

    val nextcloudSettings: Flow<NextcloudSettings> = context.dataStore.data.map { p ->
        NextcloudSettings(
            serverUrl = p[Keys.SERVER].orEmpty(),
            username = p[Keys.USER].orEmpty(),
            password = cipher.decrypt(p[Keys.PASS].orEmpty()),
            davUser = p[stringPreferencesKey("nextcloud_dav_user")].orEmpty(),
            rootPath = p[Keys.ROOT].orEmpty(),
            progressDir = p[Keys.PROGRESS_DIR] ?: ".folio-progress",
        )
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { p ->
        runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: ThemeMode.AMOLED.name) }
            .getOrDefault(ThemeMode.AMOLED)
    }

    val pageLayout: Flow<PageLayoutMode> = context.dataStore.data.map { p ->
        runCatching { PageLayoutMode.valueOf(p[Keys.PAGE_LAYOUT] ?: PageLayoutMode.AUTO.name) }
            .getOrDefault(PageLayoutMode.AUTO)
    }

    fun bookLayout(bookId: String): Flow<de.folio.reader.domain.model.BookLayoutMode> = context.dataStore.data.map { p ->
        runCatching { de.folio.reader.domain.model.BookLayoutMode.valueOf(p[stringPreferencesKey("book_layout_$bookId")] ?: "AUTO") }
            .getOrDefault(de.folio.reader.domain.model.BookLayoutMode.AUTO)
    }

    suspend fun setBookLayout(bookId: String, mode: de.folio.reader.domain.model.BookLayoutMode) {
        context.dataStore.edit { it[stringPreferencesKey("book_layout_$bookId")] = mode.name }
    }

    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.WIFI_ONLY] ?: true }
    val libraryBound = context.dataStore.data.map { it[Keys.LIBRARY_BOUND] ?: false }
    val autoDownload = context.dataStore.data.map { it[booleanPreferencesKey("auto_download")] ?: true }
    val storageBudgetMb = context.dataStore.data.map { (it[intPreferencesKey("storage_budget_mb")] ?: 2048).coerceIn(128, 16384) }
    val nativeFavorites = context.dataStore.data.map { it[booleanPreferencesKey("native_favorites")] ?: false }
    suspend fun setAutoDownload(value: Boolean) { context.dataStore.edit { it[booleanPreferencesKey("auto_download")] = value } }
    suspend fun setStorageBudgetMb(value: Int) { context.dataStore.edit { it[intPreferencesKey("storage_budget_mb")] = value.coerceIn(128, 16384) } }
    suspend fun setNativeFavorites(value: Boolean) { context.dataStore.edit { it[booleanPreferencesKey("native_favorites")] = value } }

    /** E-Ink-Modus: Blättern und Menü ohne Animationen. */
    val eInkMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.EINK] ?: false }

    val readerPreferences: Flow<ReaderPreferences> = context.dataStore.data.map { p ->
        ReaderPreferences(
            fontSize = (p[intPreferencesKey("font_size")] ?: 20).coerceIn(14, 40),
            lineHeight = (p[floatPreferencesKey("line_height")] ?: 1.6f).coerceIn(1.2f, 2.2f),
            margin = (p[intPreferencesKey("margin")] ?: 24).coerceIn(8, 48),
            sansSerif = p[booleanPreferencesKey("sans_serif")] ?: false,
            leftHanded = p[booleanPreferencesKey("left_handed")] ?: false,
            wideTapZones = p[booleanPreferencesKey("wide_tap_zones")] ?: false,
            volumeKeys = p[booleanPreferencesKey("volume_keys")] ?: false,
            lockOrientation = p[booleanPreferencesKey("lock_orientation")] ?: false,
            keepScreenOn = p[booleanPreferencesKey("keep_screen_on")] ?: false,
        )
    }

    fun bookPreferences(id: String): Flow<ReaderPreferences> = readerPreferences.combine(context.dataStore.data) { defaults, p ->
        defaults.copy(fontSize = p[intPreferencesKey("book_font_$id")] ?: defaults.fontSize,
            sansSerif = p[booleanPreferencesKey("book_sans_$id")] ?: defaults.sansSerif,
            margin = p[intPreferencesKey("book_margin_$id")] ?: defaults.margin,
            lineHeight = p[floatPreferencesKey("book_line_$id")] ?: defaults.lineHeight)
    }
    suspend fun saveBookPreferences(id: String, s: ReaderPreferences) { context.dataStore.edit { p ->
        p[intPreferencesKey("book_font_$id")] = s.fontSize.coerceIn(14, 40)
        p[booleanPreferencesKey("book_sans_$id")] = s.sansSerif
        p[intPreferencesKey("book_margin_$id")] = s.margin.coerceIn(8, 48)
        p[floatPreferencesKey("book_line_$id")] = s.lineHeight.coerceIn(1.2f, 2.2f)
    } }
    suspend fun bookZoom(id: String) = context.dataStore.data.first()[floatPreferencesKey("book_zoom_$id")] ?: 1f
    suspend fun setBookZoom(id: String, zoom: Float) { context.dataStore.edit { it[floatPreferencesKey("book_zoom_$id")] = zoom.coerceIn(1f, 3f) } }
    suspend fun libraryLocation(): Pair<String, String> { val p = context.dataStore.data.first(); return (p[stringPreferencesKey("library_tab")] ?: "BROWSE") to (p[stringPreferencesKey("library_folder")] ?: "") }
    suspend fun saveLibraryLocation(tab: String, folder: String) { context.dataStore.edit { it[stringPreferencesKey("library_tab")] = tab; it[stringPreferencesKey("library_folder")] = folder } }
    suspend fun bookmarks(id: String): List<de.folio.reader.domain.model.ReadingProgress> {
        val arr = org.json.JSONArray(context.dataStore.data.first()[stringPreferencesKey("bookmarks_$id")] ?: "[]")
        return (0 until arr.length()).map { de.folio.reader.domain.model.ReadingProgress.fromJson(arr.getJSONObject(it).toString()) }
    }
    suspend fun addBookmark(progress: de.folio.reader.domain.model.ReadingProgress) { context.dataStore.edit { p ->
        val key = stringPreferencesKey("bookmarks_${progress.bookId}")
        val arr = org.json.JSONArray(p[key] ?: "[]")
        require(arr.length() < 200) { "Maximal 200 Lesezeichen pro Buch." }
        arr.put(org.json.JSONObject(progress.toJson())); p[key] = arr.toString()
    } }
    suspend fun removeBookmark(id: String, index: Int) { context.dataStore.edit { p ->
        val key = stringPreferencesKey("bookmarks_$id"); val arr = org.json.JSONArray(p[key] ?: "[]"); arr.remove(index); p[key] = arr.toString()
    } }

    suspend fun saveReaderPreferences(s: ReaderPreferences) {
        context.dataStore.edit { p ->
            p[intPreferencesKey("font_size")] = s.fontSize.coerceIn(14, 40)
            p[floatPreferencesKey("line_height")] = s.lineHeight.coerceIn(1.2f, 2.2f)
            p[intPreferencesKey("margin")] = s.margin.coerceIn(8, 48)
            p[booleanPreferencesKey("sans_serif")] = s.sansSerif
            p[booleanPreferencesKey("left_handed")] = s.leftHanded
            p[booleanPreferencesKey("wide_tap_zones")] = s.wideTapZones
            p[booleanPreferencesKey("volume_keys")] = s.volumeKeys
            p[booleanPreferencesKey("lock_orientation")] = s.lockOrientation
            p[booleanPreferencesKey("keep_screen_on")] = s.keepScreenOn
        }
    }

    suspend fun currentNextcloudSettings(): NextcloudSettings = nextcloudSettings.first()

    suspend fun saveNextcloudSettings(s: NextcloudSettings) {
        s.validate()
        val previous = currentNextcloudSettings()
        if (context.dataStore.data.first()[Keys.LIBRARY_BOUND] == true) {
            require(previous.serverUrl.trimEnd('/') == s.serverUrl.trim().trimEnd('/') &&
                previous.username == s.username.trim() && previous.rootPath.trim('/') == s.rootPath.trim('/') && previous.progressDir.trim('/') == s.progressDir.trim('/')) {
                "Diese Installation ist an eine Bibliothek gebunden. Server, Benutzer und Bücherordner können nicht ohne Datenmigration gewechselt werden. Das App-Passwort kann erneuert werden."
            }
        }
        val encrypted = cipher.encrypt(s.password)
        context.dataStore.edit { p ->
            p[Keys.SERVER] = s.serverUrl.trim().trimEnd('/')
            p[Keys.USER] = s.username.trim()
            p[stringPreferencesKey("nextcloud_dav_user")] = s.davUser
            p[Keys.PASS] = encrypted
            p[Keys.ROOT] = NextcloudSettings.segments(s.rootPath).joinToString("/")
            p[Keys.PROGRESS_DIR] = NextcloudSettings.segments(s.progressDir).joinToString("/")
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun bindLibrary(settings: NextcloudSettings) {
        require(currentNextcloudSettings() == settings) { "Verbindung wurde geändert. Erneut synchronisieren." }
        context.dataStore.edit { it[Keys.LIBRARY_BOUND] = true }
    }

    suspend fun setPageLayout(mode: PageLayoutMode) {
        context.dataStore.edit { it[Keys.PAGE_LAYOUT] = mode.name }
    }

    suspend fun setWifiOnly(value: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY] = value }
    }

    suspend fun setEInkMode(value: Boolean) {
        context.dataStore.edit { it[Keys.EINK] = value }
    }

    /** Liefert (und erzeugt einmalig) eine stabile, anonyme Geräte-ID. */
    suspend fun deviceId(): String {
        var result = ""
        context.dataStore.edit { p -> result = p[Keys.DEVICE_ID] ?: ("device-" + UUID.randomUUID()); p[Keys.DEVICE_ID] = result }
        return result
    }
}
