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
import de.folio.reader.domain.model.SmbSettings
import de.folio.reader.domain.model.ThemeMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val saved by viewModel.smbSettings.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val connectionTest by viewModel.connectionTest.collectAsStateWithLifecycle()

    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("") }
    var progressDir by remember { mutableStateOf(".folio-progress") }
    var seeded by remember { mutableStateOf(false) }

    LaunchedEffect(saved) {
        if (!seeded && saved != SmbSettings()) {
            host = saved.host; share = saved.shareName; user = saved.username
            password = saved.password; domain = saved.domain; rootPath = saved.rootPath
            progressDir = saved.progressDir
            seeded = true
        }
    }

    fun current() = SmbSettings(
        host = host.trim(), shareName = share.trim(), username = user.trim(),
        password = password, domain = domain.trim(), rootPath = rootPath.trim(),
        progressDir = progressDir.trim().ifBlank { ".folio-progress" },
    )

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

            SectionTitle("NAS-Verbindung (SMB)")
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("Host / IP (z. B. 192.168.1.20)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = share, onValueChange = { share = it },
                label = { Text("Freigabename (z. B. Books)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("Benutzername") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Passwort") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = domain, onValueChange = { domain = it },
                label = { Text("Domäne (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = rootPath, onValueChange = { rootPath = it },
                label = { Text("Unterordner mit Büchern (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = progressDir, onValueChange = { progressDir = it },
                label = { Text("Ordner für Lesefortschritt") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            ConnectionStatus(connectionTest)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { viewModel.testConnection(current()) },
                    enabled = host.isNotBlank() && share.isNotBlank(),
                ) { Text("Verbindung testen") }
                Button(
                    onClick = {
                        viewModel.saveSmb(current())
                        viewModel.resetConnectionTest()
                        onClose()
                    },
                ) { Text("Speichern") }
            }

            SectionTitle("Synchronisierung")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Nur über WLAN synchronisieren", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Schont das mobile Datenvolumen beim Buch-Download.",
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
private fun ConnectionStatus(state: ConnectionTest) {
    when (state) {
        ConnectionTest.Idle -> Unit
        ConnectionTest.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp))
            Text("Teste Verbindung …")
        }
        ConnectionTest.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Text("Verbindung erfolgreich", modifier = Modifier.padding(start = 8.dp))
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

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Hell"
    ThemeMode.DARK -> "Dunkel"
    ThemeMode.AMOLED -> "AMOLED"
}
