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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var syncManager: SyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.AMOLED)
            FolioTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FolioApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Beim Öffnen der App (bzw. Rückkehr in den Vordergrund) die
        // Lesefortschritte automatisch mit dem NAS abgleichen.
        syncManager.syncProgressNow()
    }
}
