package de.folio.reader.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    val pageStates = rememberSaveableStateHolder()
    fun back() { if (section == null) onClose() else section = null }
    androidx.activity.compose.BackHandler { back() }
    var credentialsVisible by rememberSaveable { mutableStateOf(false) }
    var manualVisible by rememberSaveable { mutableStateOf(false) }
    var advancedVisible by rememberSaveable { mutableStateOf(false) }

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
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("/" + pickerPath)
                if (pickerPath.isNotEmpty()) androidx.compose.material3.TextButton(onClick = { pickerPath = pickerPath.substringBeforeLast('/', ""); viewModel.browseFolders(current(), pickerPath) }) { Text("Eine Ebene höher") }
                folders.forEach { folder -> androidx.compose.material3.TextButton(onClick = { pickerPath = folder; viewModel.browseFolders(current(), folder) }) { Text(folder.substringAfterLast('/')) } }
                message?.let { Text(it) }
            } }, confirmButton = { androidx.compose.material3.TextButton(onClick = { if (pickerProgress) progressDir = pickerPath else rootPath = pickerPath; picker = false }) { Text("Diesen Ordner verwenden") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { picker = false }) { Text("Abbrechen") } })
    }
    if (!picker) message?.let { value ->
        AlertDialog(onDismissRequest = viewModel::dismissMessage, title = { Text("Hinweis") },
            text = { Text(value, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("Schließen") } })
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(section ?: "Einstellungen", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") } })
    }) { inner ->
        pageStates.SaveableStateProvider(section ?: "overview") {
            Column(Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                when (section) {
                    null -> SettingsOverview(connected = saved.isConfigured, onSelect = { section = it })
                    "Darstellung" -> {
                        SettingsGroup("Farben und E-Ink") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemeMode.entries.forEach { mode -> FilterChip(selected = themeMode == mode, onClick = { viewModel.setTheme(mode) }, label = { Text(mode.label()) }) }
                            }
                            PreferenceSwitch("E-Ink-Modus", eInkMode, viewModel::setEInkMode, "Ohne Animationen, mit klaren Kontrasten.")
                        }
                        SettingsGroup("Schrift und Abstände") {
                            Text("Standardwerte fürs Lesen. Im Buch öffnet Aa die individuelle Darstellung.", style = MaterialTheme.typography.bodySmall)
                            PreferenceStepper("Schriftgröße: ${reader.fontSize}", { viewModel.setReaderPreferences(reader.copy(fontSize = reader.fontSize - 2)) }, { viewModel.setReaderPreferences(reader.copy(fontSize = reader.fontSize + 2)) })
                            PreferenceStepper("Zeilenabstand: ${String.format(java.util.Locale.ROOT, "%.1f", reader.lineHeight)}", { viewModel.setReaderPreferences(reader.copy(lineHeight = reader.lineHeight - 0.1f)) }, { viewModel.setReaderPreferences(reader.copy(lineHeight = reader.lineHeight + 0.1f)) })
                            PreferenceStepper("Seitenrand: ${reader.margin}", { viewModel.setReaderPreferences(reader.copy(margin = reader.margin - 4)) }, { viewModel.setReaderPreferences(reader.copy(margin = reader.margin + 4)) })
                            PreferenceSwitch("Serifenlose Schrift", reader.sansSerif, { viewModel.setReaderPreferences(reader.copy(sansSerif = it)) })
                        }
                        SettingsGroup("Seitenlayout") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PageLayoutMode.entries.forEach { mode -> FilterChip(selected = pageLayout == mode, onClick = { viewModel.setPageLayout(mode) }, label = { Text(mode.label()) }) }
                            }
                            Text("Automatisch zeigt ab Tablet-/Foldable-Breite zwei Seiten nebeneinander.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    "Bedienung" -> {
                        SettingsGroup("Blättern") {
                            Text("Links/rechts tippen zum Blättern, Mitte für das Menü. Seitentasten und Steuerkreuz werden unterstützt.", style = MaterialTheme.typography.bodySmall)
                            PreferenceSwitch("Linkshändig", reader.leftHanded, { viewModel.setReaderPreferences(reader.copy(leftHanded = it)) }, "Tauscht die linke und rechte Tap-Zone.")
                            PreferenceSwitch("Breite Tap-Zonen", reader.wideTapZones, { viewModel.setReaderPreferences(reader.copy(wideTapZones = it)) }, "40 % links · 20 % Menü · 40 % rechts")
                            PreferenceSwitch("Lautstärketasten zum Blättern", reader.volumeKeys, { viewModel.setReaderPreferences(reader.copy(volumeKeys = it)) })
                        }
                        SettingsGroup("Display") {
                            PreferenceSwitch("Ausrichtung beim Lesen sperren", reader.lockOrientation, { viewModel.setReaderPreferences(reader.copy(lockOrientation = it)) })
                            PreferenceSwitch("Display beim Lesen eingeschaltet lassen", reader.keepScreenOn, { viewModel.setReaderPreferences(reader.copy(keepScreenOn = it)) })
                        }
                    }
                    "Nextcloud & Synchronisierung" -> {
                        SettingsGroup("Verbindung") {
                            if (bound && !credentialsVisible) {
                                Text(saved.serverUrl, style = MaterialTheme.typography.bodyMedium)
                                Text("Konto: ${saved.username}\nBücher: /${saved.rootPath}", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { credentialsVisible = true }) { Text("Anmeldung erneuern") }
                            } else {
                                OutlinedTextField(value = serverUrl, readOnly = bound, onValueChange = { serverUrl = it; davUser = "" }, label = { Text("Nextcloud-Adresse (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                if (loginPending) {
                                    Text("Anmeldung im Browser bestätigen …")
                                    OutlinedButton(onClick = viewModel::cancelLogin) { Text("Anmeldung abbrechen") }
                                } else Button(onClick = { viewModel.startLogin(serverUrl) }, enabled = serverUrl.startsWith("https://")) { Text("Im Browser anmelden") }
                                TextButton(onClick = { manualVisible = !manualVisible }) { Text(if (manualVisible) "App-Passwort ausblenden" else "Manuell mit App-Passwort") }
                                if (manualVisible) {
                                    Text("App-Passwort in Nextcloud unter Persönliche Einstellungen → Sicherheit erstellen.", style = MaterialTheme.typography.bodySmall)
                                    OutlinedTextField(value = user, readOnly = bound, onValueChange = { user = it; davUser = "" }, label = { Text("Benutzername") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("App-Passwort") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                                }
                                if (bound) Text("Die Bibliothek bleibt mit diesem Konto verbunden.", style = MaterialTheme.typography.bodySmall)
                            }
                            if (!bound) {
                                OutlinedTextField(value = rootPath, onValueChange = { rootPath = it }, label = { Text("Bücherordner (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedButton(onClick = { pickerProgress = false; pickerPath = rootPath; viewModel.browseFolders(current(), pickerPath); picker = true }, enabled = current().isConfigured) { Text("Ordner auswählen") }
                            }
                            TextButton(onClick = { advancedVisible = !advancedVisible }) { Text(if (advancedVisible) "Erweitert ausblenden" else "Erweitert") }
                            if (advancedVisible) {
                                OutlinedTextField(value = progressDir, readOnly = bound, onValueChange = { progressDir = it }, label = { Text("Ordner für Lesefortschritt") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                if (!bound) OutlinedButton(onClick = { pickerProgress = true; pickerPath = progressDir; viewModel.browseFolders(current(), pickerPath); picker = true }, enabled = current().isConfigured) { Text("Fortschrittsordner wählen") }
                                Text("Ordner sind relativ zu deinen Nextcloud-Dateien.", style = MaterialTheme.typography.bodySmall)
                            }
                            ConnectionStatus(connectionTest, eInkMode)
                            OutlinedButton(onClick = { viewModel.testConnection(current()) }, enabled = current().isConfigured && connectionTest != ConnectionTest.Testing, modifier = Modifier.fillMaxWidth()) { Text("Verbindung testen") }
                            if (!bound || credentialsVisible) Button(onClick = { viewModel.saveNextcloud(current(), onClose) }, enabled = current().isConfigured && connectionTest != ConnectionTest.Testing, modifier = Modifier.fillMaxWidth()) { Text("Verbindung speichern") }
                        }
                        SettingsGroup("Synchronisierung") {
                            PreferenceSwitch("Nur ungetaktete Netzwerke", wifiOnly, viewModel::setWifiOnly, "Zum Beispiel WLAN ohne Datenlimit. Gilt auch für den Lesestand.")
                            PreferenceSwitch("Favoriten in Nextcloud markieren", nativeFavorites, viewModel::setNativeFavorites, "Überträgt die Favoritenmarkierung zusätzlich nach Nextcloud Files.")
                        }
                    }
                    "Offline-Speicher" -> SettingsGroup("Lokale Bücher") {
                        Text("${storageUsed / (1024 * 1024)} MB auf diesem Gerät", style = MaterialTheme.typography.titleMedium)
                        PreferenceSwitch("Automatisch herunterladen", autoDownload, viewModel::setAutoDownload, "Ausgeschaltet lädt ein Tipp auf ein Buch nur diesen Titel und öffnet ihn danach.")
                        PreferenceStepper("Limit: $storageBudget MB", { viewModel.setStorageBudget(storageBudget - 512) }, { viewModel.setStorageBudget(storageBudget + 512) })
                        Text("Lokale Kopien entfernst du im ⋮-Menü des jeweiligen Buchs.", style = MaterialTheme.typography.bodySmall)
                    }
                    "Lesestände sichern" -> SettingsGroup("Sicherung") {
                        Text("Lesestände als Datei sichern oder eine vorhandene Sicherung zusammenführen.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { exportLauncher.launch("folio-lesestaende.json") }, modifier = Modifier.fillMaxWidth()) { Text("Sicherung exportieren") }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }, modifier = Modifier.fillMaxWidth()) { Text("Sicherung importieren") }
                    }
                    "App & Updates" -> SettingsGroup("Folio ${de.folio.reader.BuildConfig.VERSION_NAME}") {
                        OutlinedButton(onClick = viewModel::checkUpdate, modifier = Modifier.fillMaxWidth()) { Text("Auf Updates prüfen") }
                        update?.let { newer -> Button(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(newer.url))) }, modifier = Modifier.fillMaxWidth()) { Text("Version 1.0.${newer.version} herunterladen") } }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsOverview(connected: Boolean, onSelect: (String) -> Unit) {
    val entries = listOf(
        "Nextcloud & Synchronisierung" to if (connected) "Verbindung, Netzwerke und Favoriten" else "Nextcloud verbinden",
        "Darstellung" to "E-Ink, Schrift und Seitenlayout",
        "Bedienung" to "Tap-Zonen, Tasten und Display",
        "Offline-Speicher" to "Downloads und Speicherlimit",
        "Lesestände sichern" to "Sicherung exportieren oder importieren",
        "App & Updates" to "Version und Aktualisierungen",
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.forEach { (title, subtitle) ->
            Surface(onClick = { onSelect(title) }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
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
private fun PreferenceSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
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
