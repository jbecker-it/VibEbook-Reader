package de.folio.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import de.folio.reader.data.settings.SettingsRepository
import de.folio.reader.data.sync.SyncManager
import de.folio.reader.domain.model.ThemeMode
import de.folio.reader.ui.FolioApp
import de.folio.reader.ui.theme.FolioTheme
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var bookRepository: de.folio.reader.data.repository.BookRepository
    private val importedBook = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val importError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    @Inject lateinit var syncManager: SyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        importIntent(intent)
        setContent {
            val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.AMOLED)
            val eInk by settingsRepository.eInkMode.collectAsState(initial = false)
            FolioTheme(themeMode = themeMode, eInk = eInk) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val imported by importedBook.collectAsState()
                    val error by importError.collectAsState()
                    FolioApp(importedBookId = imported, onImportConsumed = { importedBook.value = null })
                    error?.let { androidx.compose.material3.AlertDialog(onDismissRequest = { importError.value = null }, title = { androidx.compose.material3.Text("Import fehlgeschlagen") }, text = { androidx.compose.material3.Text(it) }, confirmButton = { androidx.compose.material3.TextButton(onClick = { importError.value = null }) { androidx.compose.material3.Text("OK") } }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); importIntent(intent) }
    private fun importIntent(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        intent.action = null // Do not repeat a successful import on Activity recreation.
        lifecycleScope.launch { try { importedBook.value = bookRepository.importLocal(uri) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { importError.value = e.message ?: "Datei konnte nicht importiert werden." } }
    }

    override fun onStart() {
        super.onStart()
        // Beim Öffnen der App (bzw. Rückkehr in den Vordergrund) die
        // Lesefortschritte automatisch mit dem Nextcloud abgleichen.
        syncManager.syncProgressNow()
    }
}
