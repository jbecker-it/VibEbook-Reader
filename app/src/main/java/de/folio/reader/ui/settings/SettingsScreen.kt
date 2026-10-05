package de.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.folio.reader.domain.model.PageLayoutMode
import de.folio.reader.domain.model.NextcloudSettings
import de.folio.reader.domain.model.ThemeMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val saved by viewModel.nextcloudSettings.collectAsStateWithLifecycle()
    val bound by viewModel.libraryBound.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val pageLayout by viewModel.pageLayout.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val eInkMode by viewModel.eInkMode.collectAsStateWithLifecycle()
    val connectionTest by viewModel.connectionTest.collectAsStateWithLifecycle()
    val reader by viewModel.readerPreferences.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val autoDownload by viewModel.autoDownload.collectAsStateWithLifecycle()
    val storageBudget by viewModel.storageBudget.collectAsStateWithLifecycle()
    val storageUsed by viewModel.storageUsed.collectAsStateWithLifecycle()
    val nativeFavorites by viewModel.nativeFavorites.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val loginResult by viewModel.loginResult.collectAsStateWithLifecycle()
    val loginUrl by viewModel.loginUrl.collectAsStateWithLifecycle()
    val loginPending by viewModel.loginPending.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { viewModel.exportProgress(context, it) } }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importProgress(context, it) } }
    LaunchedEffect(loginUrl) { loginUrl?.let { url ->
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        viewModel.consumedLoginUrl()
    } }
    androidx.activity.compose.BackHandler { onClose() }

    var serverUrl by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("") }
    var progressDir by remember { mutableStateOf(".folio-progress") }
    var seeded by remember { mutableStateOf(false) }
    var davUser by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var pickerPath by remember { mutableStateOf("") }
    var pickerProgress by remember { mutableStateOf(false) }
    LaunchedEffect(serverUrl, user, password, rootPath, progressDir) { viewModel.resetConnectionTest() }
    LaunchedEffect(loginResult) { loginResult?.let { serverUrl = it.serverUrl; user = it.username; password = it.password; davUser = it.davUser } }

    LaunchedEffect(saved) {
        if (!seeded && saved != NextcloudSettings()) {
            serverUrl = saved.serverUrl; user = saved.username
            password = saved.password; rootPath = saved.rootPath
            progressDir = saved.progressDir; davUser = saved.davUser
            seeded = true
        }
    }

    fun current() = NextcloudSettings(
        serverUrl = serverUrl.trim(), username = user.trim(),
        password = password, rootPath = rootPath.trim('/'),
        progressDir = progressDir.trim().ifBlank { ".folio-progress" }, davUser = davUser,
    )

    if (picker) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { picker = false }, title = { Text(if (pickerProgress) "Fortschrittsordner wählen" else "Bücherordner wählen") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("/" + pickerPath)
                if (pickerPath.isNotEmpty()) androidx.compose.material3.TextButton(onClick = { pickerPath = pickerPath.substringBeforeLast('/', ""); viewModel.browseFolders(current(), pickerPath) }) { Text("Eine Ebene höher") }
                folders.forEach { folder -> androidx.compose.material3.TextButton(onClick = { pickerPath = folder; viewModel.browseFolders(current(), folder) }) { Text(folder.substringAfterLast('/')) } }
                message?.let { Text(it) }
            } }, confirmButton = { androidx.compose.material3.TextButton(onClick = { if (pickerProgress) progressDir = pickerPath else rootPath = pickerPath; picker = false }) { Text("Diesen Ordner verwenden") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { picker = false }) { Text("Abbrechen") } })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Folio ${de.folio.reader.BuildConfig.VERSION_NAME}")
            OutlinedButton(onClick = viewModel::checkUpdate) { Text("Auf Updates prüfen") }
            update?.let { newer -> OutlinedButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(newer.url))) }) { Text("Version 1.0.${newer.version} herunterladen") } }
            SectionTitle("Darstellung")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { viewModel.setTheme(mode) },
                        label = { Text(mode.label()) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("E-Ink-Modus", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Blättern und Menü ohne Animationen – für E-Reader-Displays.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = eInkMode, onCheckedChange = viewModel::setEInkMode)
            }

            SectionTitle("Seitenlayout")
            Text("Lesen: Links/rechts tippen zum Blättern, Mitte für das Menü. Seitentasten und Steuerkreuz werden unterstützt.")
            PreferenceStepper("Schriftgröße: ${reader.fontSize}", { viewModel.setReaderPreferences(reader.copy(fontSize = reader.fontSize - 2)) }, { viewModel.setReaderPreferences(reader.copy(fontSize = reader.fontSize + 2)) })
            PreferenceStepper("Zeilenabstand: ${String.format(java.util.Locale.ROOT, "%.1f", reader.lineHeight)}", { viewModel.setReaderPreferences(reader.copy(lineHeight = reader.lineHeight - 0.1f)) }, { viewModel.setReaderPreferences(reader.copy(lineHeight = reader.lineHeight + 0.1f)) })
            PreferenceStepper("Seitenrand: ${reader.margin}", { viewModel.setReaderPreferences(reader.copy(margin = reader.margin - 4)) }, { viewModel.setReaderPreferences(reader.copy(margin = reader.margin + 4)) })
            PreferenceSwitch("Serifenlose Schrift", reader.sansSerif) { viewModel.setReaderPreferences(reader.copy(sansSerif = it)) }
            PreferenceSwitch("Linkshändig (Tap-Zonen tauschen)", reader.leftHanded) { viewModel.setReaderPreferences(reader.copy(leftHanded = it)) }
            PreferenceSwitch("Breite Tap-Zonen (40 / 20 / 40 %)", reader.wideTapZones) { viewModel.setReaderPreferences(reader.copy(wideTapZones = it)) }
            PreferenceSwitch("Lautstärketasten zum Blättern", reader.volumeKeys) { viewModel.setReaderPreferences(reader.copy(volumeKeys = it)) }
            PreferenceSwitch("Ausrichtung beim Lesen sperren", reader.lockOrientation) { viewModel.setReaderPreferences(reader.copy(lockOrientation = it)) }
            PreferenceSwitch("Display beim Lesen eingeschaltet lassen", reader.keepScreenOn) { viewModel.setReaderPreferences(reader.copy(keepScreenOn = it)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageLayoutMode.entries.forEach { mode ->
                    FilterChip(
                        selected = pageLayout == mode,
                        onClick = { viewModel.setPageLayout(mode) },
                        label = { Text(mode.label()) },
                    )
                }
            }
            Text(
                text = "Automatisch zeigt ab Tablet-/Foldable-Breite zwei Seiten nebeneinander.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Nextcloud-Verbindung")
            message?.let { Text(it) }
            if (loginPending) {
                Text("Anmeldung im Browser bestätigen …")
                OutlinedButton(onClick = viewModel::cancelLogin) { Text("Anmeldung abbrechen") }
            } else OutlinedButton(onClick = { viewModel.startLogin(serverUrl) }, enabled = serverUrl.startsWith("https://")) { Text("Mit Nextcloud im Browser anmelden") }
            Text("In Nextcloud unter Persönliche Einstellungen → Sicherheit ein App-Passwort erstellen. Ordner sind relativ zu deinen Nextcloud-Dateien.")
            OutlinedTextField(
                value = serverUrl, readOnly = bound, onValueChange = { serverUrl = it; davUser = ""; viewModel.resetConnectionTest() },
                label = { Text("Serveradresse (https://cloud.example.com)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user, readOnly = bound, onValueChange = { user = it; davUser = ""; viewModel.resetConnectionTest() },
                label = { Text("Benutzername") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it; viewModel.resetConnectionTest() },
                label = { Text("App-Passwort") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = rootPath, readOnly = bound, onValueChange = { rootPath = it; viewModel.resetConnectionTest() },
                label = { Text("Unterordner mit Büchern (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = progressDir, readOnly = bound, onValueChange = { progressDir = it; viewModel.resetConnectionTest() },
                label = { Text("Ordner für Lesefortschritt") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(onClick = { pickerProgress = false; pickerPath = rootPath; viewModel.browseFolders(current(), pickerPath); picker = true }, enabled = current().isConfigured && !bound) { Text("Bücherordner auswählen") }
            OutlinedButton(onClick = { pickerProgress = true; pickerPath = progressDir; viewModel.browseFolders(current(), pickerPath); picker = true }, enabled = current().isConfigured && !bound) { Text("Fortschrittsordner auswählen") }
            if (bound) Text("Bibliothek fest verbunden. App-Passwort erneuern oder im Browser für dasselbe Konto anmelden.")
            ConnectionStatus(connectionTest, eInkMode)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { viewModel.testConnection(current()) },
                    enabled = current().isConfigured && connectionTest != ConnectionTest.Testing,
                ) { Text("Verbindung testen") }
                Button(
                    onClick = {
                        viewModel.saveNextcloud(current(), onClose)
                    },
                    enabled = current().isConfigured && connectionTest != ConnectionTest.Testing,
                ) { Text("Speichern") }
            }

            SectionTitle("Offline-Speicher")
            Text("${storageUsed / (1024 * 1024)} MB lokal · Limit $storageBudget MB")
            PreferenceSwitch("Neue Bücher automatisch herunterladen", autoDownload, viewModel::setAutoDownload)
            Text("Bei deaktiviertem Schalter lädt ein Tipp auf das Buch nur diesen Titel und öffnet ihn danach.")
            PreferenceStepper("Speicherlimit", { viewModel.setStorageBudget(storageBudget - 512) }, { viewModel.setStorageBudget(storageBudget + 512) })
            PreferenceSwitch("Favoriten auch in Nextcloud markieren", nativeFavorites, viewModel::setNativeFavorites)
            Text("Aktiviert: Folio schreibt seine Favoritenmarkierung zusätzlich in Nextcloud Files. Folio-Lesestände bleiben die gemeinsame Quelle für deine Geräte.")
            SectionTitle("Lesestände sichern")
            OutlinedButton(onClick = { exportLauncher.launch("folio-lesestaende.json") }) { Text("Sicherung exportieren") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text("Sicherung importieren und zusammenführen") }
            SectionTitle("Synchronisierung")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Nur ungetaktete Netzwerke", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Zum Beispiel WLAN ohne Datenlimit. Gilt auch für den Fortschrittsabgleich.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = wifiOnly, onCheckedChange = viewModel::setWifiOnly)
            }
        }
    }
}

@Composable
private fun ConnectionStatus(state: ConnectionTest, eInk: Boolean) {
    when (state) {
        ConnectionTest.Idle -> Unit
        ConnectionTest.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (eInk) Text("…", Modifier.padding(end = 8.dp)) else CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp))
            Text("Teste Verbindung …")
        }
        ConnectionTest.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Text("Bücher lesbar · Lesestand speicherbar", modifier = Modifier.padding(start = 8.dp))
        }
        is ConnectionTest.Failure -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Error, null, tint = MaterialTheme.colorScheme.error)
            Text(state.message, modifier = Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun PreferenceSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PreferenceStepper(label: String, less: () -> Unit, more: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = less) { Text("−") }
        OutlinedButton(onClick = more) { Text("+") }
    }
}

private fun PageLayoutMode.label() = when (this) {
    PageLayoutMode.AUTO -> "Automatisch"
    PageLayoutMode.SINGLE -> "Einseitig"
    PageLayoutMode.DOUBLE -> "Zweiseitig"
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Hell"
    ThemeMode.DARK -> "Dunkel"
    ThemeMode.AMOLED -> "AMOLED"
}
