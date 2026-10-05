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
    private val updateClient: de.folio.reader.data.nextcloud.UpdateClient,
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

    private val _update = MutableStateFlow<de.folio.reader.data.nextcloud.UpdateClient.Update?>(null); val update = _update.asStateFlow()
    fun checkUpdate() = action { _update.value = updateClient.check(); _message.value = if (_update.value == null) "Version ${de.folio.reader.BuildConfig.VERSION_NAME} ist aktuell." else "Neue Version verfügbar." }
    val libraryBound = settingsRepository.libraryBound.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val autoDownload = settingsRepository.autoDownload.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val storageBudget = settingsRepository.storageBudgetMb.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 2048)
    val nativeFavorites = settingsRepository.nativeFavorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val _storageUsed = MutableStateFlow(0L); val storageUsed = _storageUsed.asStateFlow()
    private val _message = MutableStateFlow<String?>(null); val message = _message.asStateFlow()
    private val _loginResult = MutableStateFlow<NextcloudSettings?>(null); val loginResult = _loginResult.asStateFlow()
    private val _loginUrl = MutableStateFlow<String?>(null); val loginUrl = _loginUrl.asStateFlow()
    private val _loginPending = MutableStateFlow(false); val loginPending = _loginPending.asStateFlow()
    private var loginJob: kotlinx.coroutines.Job? = null
    private var testJob: kotlinx.coroutines.Job? = null
    private val _folders = MutableStateFlow<List<String>>(emptyList()); val folders = _folders.asStateFlow()
    init { action { _storageUsed.value = bookRepository.storageBytes() } }
    fun setAutoDownload(value: Boolean) = action { settingsRepository.setAutoDownload(value) }
    fun setStorageBudget(value: Int) = action { settingsRepository.setStorageBudgetMb(value) }
    fun setNativeFavorites(value: Boolean) = action { settingsRepository.setNativeFavorites(value) }
    fun browseFolders(settings: NextcloudSettings, path: String) = action { _folders.value = nextcloudClient.list(settings, path).filter { it.directory }.map { it.relativePath }.sorted() }
    fun startLogin(server: String) {
        loginJob?.cancel(); _loginPending.value = true
        loginJob = viewModelScope.launch { try {
            val session = nextcloudClient.startLogin(server); _loginUrl.value = session.loginUrl.toString()
            val result = kotlinx.coroutines.withTimeout(20 * 60 * 1000L) {
                var found: NextcloudSettings? = null
                while (found == null) { found = nextcloudClient.pollLogin(session); if (found == null) kotlinx.coroutines.delay(1500) }
                found
            }
            _loginResult.value = result; _message.value = "Anmeldung übernommen. Bücherordner wählen, Verbindung testen und speichern."
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { _message.value = e.message }
        finally { _loginPending.value = false } }
    }
    fun cancelLogin() { loginJob?.cancel(); _loginPending.value = false }
    fun consumedLoginUrl() { _loginUrl.value = null }
    fun exportProgress(context: android.content.Context, uri: android.net.Uri) = action {
        val raw = bookRepository.exportProgress()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(raw) } ?: error("Sicherung konnte nicht geschrieben werden.") }
        _message.value = "Lesestände exportiert."
    }
    fun importProgress(context: android.content.Context, uri: android.net.Uri) = action {
        val raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytesLimited(8 * 1024 * 1024); bytes.toString(Charsets.UTF_8)
        } ?: error("Sicherung konnte nicht gelesen werden.") }
        _message.value = "${bookRepository.importProgress(raw)} Lesestände zusammengeführt."
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "Sicherung zu groß." }; out.write(buffer, 0, n) }; return out.toByteArray()
    }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { _message.value = e.message ?: "Aktion fehlgeschlagen" } } }

    private val _connectionTest = MutableStateFlow<ConnectionTest>(ConnectionTest.Idle)
    val connectionTest: StateFlow<ConnectionTest> = _connectionTest.asStateFlow()

    fun saveNextcloud(settings: NextcloudSettings, onSaved: () -> Unit) {
        viewModelScope.launch {
            try {
                settingsRepository.saveNextcloudSettings(nextcloudClient.resolveAccount(settings))
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
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _connectionTest.value = ConnectionTest.Testing
            nextcloudClient.testConnection(try { nextcloudClient.resolveAccount(settings) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { _connectionTest.value = ConnectionTest.Failure(e.message ?: "Benutzerprüfung fehlgeschlagen"); return@launch })
                .onSuccess { _connectionTest.value = ConnectionTest.Success }
                .onFailure { _connectionTest.value = ConnectionTest.Failure(it.message ?: "Unbekannter Fehler") }
        }
    }

    fun resetConnectionTest() {
        testJob?.cancel()
        _connectionTest.value = ConnectionTest.Idle
    }
}
