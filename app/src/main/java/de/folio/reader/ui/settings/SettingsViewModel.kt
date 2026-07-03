package de.folio.reader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.folio.reader.data.repository.BookRepository
import de.folio.reader.data.settings.SettingsRepository
import de.folio.reader.data.smb.SmbClient
import de.folio.reader.domain.model.PageLayoutMode
import de.folio.reader.domain.model.SmbSettings
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
    private val smbClient: SmbClient,
    private val bookRepository: BookRepository,
) : ViewModel() {

    val smbSettings: StateFlow<SmbSettings> = settingsRepository.smbSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmbSettings())

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

    fun saveSmb(settings: SmbSettings) {
        viewModelScope.launch { settingsRepository.saveSmbSettings(settings) }
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

    fun testConnection(settings: SmbSettings) {
        viewModelScope.launch {
            _connectionTest.value = ConnectionTest.Testing
            settingsRepository.saveSmbSettings(settings)
            smbClient.testConnection(settings)
                .onSuccess { _connectionTest.value = ConnectionTest.Success }
                .onFailure { _connectionTest.value = ConnectionTest.Failure(it.message ?: "Unbekannter Fehler") }
        }
    }

    fun resetConnectionTest() {
        _connectionTest.value = ConnectionTest.Idle
    }
}
