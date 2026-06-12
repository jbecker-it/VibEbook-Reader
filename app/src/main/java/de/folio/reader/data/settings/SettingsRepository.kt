package de.folio.reader.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.folio.reader.domain.model.SmbSettings
import de.folio.reader.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "folio_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val HOST = stringPreferencesKey("smb_host")
        val SHARE = stringPreferencesKey("smb_share")
        val USER = stringPreferencesKey("smb_user")
        val PASS = stringPreferencesKey("smb_pass")
        val DOMAIN = stringPreferencesKey("smb_domain")
        val ROOT = stringPreferencesKey("smb_root")
        val PROGRESS_DIR = stringPreferencesKey("smb_progress_dir")
        val THEME = stringPreferencesKey("theme_mode")
        val WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val smbSettings: Flow<SmbSettings> = context.dataStore.data.map { p ->
        SmbSettings(
            host = p[Keys.HOST].orEmpty(),
            shareName = p[Keys.SHARE].orEmpty(),
            username = p[Keys.USER].orEmpty(),
            password = p[Keys.PASS].orEmpty(),
            domain = p[Keys.DOMAIN].orEmpty(),
            rootPath = p[Keys.ROOT].orEmpty(),
            progressDir = p[Keys.PROGRESS_DIR] ?: ".folio-progress",
        )
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { p ->
        runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: ThemeMode.AMOLED.name) }
            .getOrDefault(ThemeMode.AMOLED)
    }

    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.WIFI_ONLY] ?: true }

    suspend fun currentSmbSettings(): SmbSettings = smbSettings.first()

    suspend fun saveSmbSettings(s: SmbSettings) {
        context.dataStore.edit { p ->
            p[Keys.HOST] = s.host
            p[Keys.SHARE] = s.shareName
            p[Keys.USER] = s.username
            p[Keys.PASS] = s.password
            p[Keys.DOMAIN] = s.domain
            p[Keys.ROOT] = s.rootPath
            p[Keys.PROGRESS_DIR] = s.progressDir
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setWifiOnly(value: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY] = value }
    }

    /** Liefert (und erzeugt einmalig) eine stabile, anonyme Geräte-ID. */
    suspend fun deviceId(): String {
        val existing = context.dataStore.data.map { it[Keys.DEVICE_ID] }.first()
        if (existing != null) return existing
        val generated = "device-" + UUID.randomUUID().toString().take(8)
        context.dataStore.edit { it[Keys.DEVICE_ID] = generated }
        return generated
    }
}
