package de.folio.reader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.folio.reader.data.repository.BookRepository
import de.folio.reader.data.settings.SettingsRepository
import de.folio.reader.data.nextcloud.NextcloudClient
import de.folio.reader.domain.model.PageLayoutMode
import de.folio.reader.domain.model.NextcloudSettings
import de.folio.reader.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Testing : ConnectionTest
    data object Success : ConnectionTest
    data class Failure(val message: String) : ConnectionTest
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val nextcloudClient: NextcloudClient,
    private val bookRepository: BookRepository,
) : ViewModel() {
    val readerPreferences = settingsRepository.readerPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), de.folio.reader.domain.model.ReaderPreferences())
    fun setReaderPreferences(value: de.folio.reader.domain.model.ReaderPreferences) {
        viewModelScope.launch { settingsRepository.saveReaderPreferences(value) }
    }

    val nextcloudSettings: StateFlow<NextcloudSettings> = settingsRepository.nextcloudSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NextcloudSettings())

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.AMOLED)

    val pageLayout: StateFlow<PageLayoutMode> = settingsRepository.pageLayout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PageLayoutMode.AUTO)

    val wifiOnly: StateFlow<Boolean> = settingsRepository.wifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val eInkMode: StateFlow<Boolean> = settingsRepository.eInkMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _connectionTest = MutableStateFlow<ConnectionTest>(ConnectionTest.Idle)
    val connectionTest: StateFlow<ConnectionTest> = _connectionTest.asStateFlow()

    fun saveNextcloud(settings: NextcloudSettings, onSaved: () -> Unit) {
        viewModelScope.launch {
            try {
                settingsRepository.saveNextcloudSettings(settings)
                onSaved()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { _connectionTest.value = ConnectionTest.Failure(e.message ?: "Speichern fehlgeschlagen") }
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setPageLayout(mode: PageLayoutMode) {
        viewModelScope.launch { settingsRepository.setPageLayout(mode) }
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch { settingsRepository.setWifiOnly(value) }
    }

    fun setEInkMode(value: Boolean) {
        viewModelScope.launch { settingsRepository.setEInkMode(value) }
    }

    fun testConnection(settings: NextcloudSettings) {
        viewModelScope.launch {
            _connectionTest.value = ConnectionTest.Testing
            nextcloudClient.testConnection(settings)
                .onSuccess { _connectionTest.value = ConnectionTest.Success }
                .onFailure { _connectionTest.value = ConnectionTest.Failure(it.message ?: "Unbekannter Fehler") }
        }
    }

    fun resetConnectionTest() {
        _connectionTest.value = ConnectionTest.Idle
    }
}
