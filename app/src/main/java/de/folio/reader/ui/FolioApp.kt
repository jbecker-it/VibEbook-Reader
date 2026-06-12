package de.folio.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.folio.reader.ui.library.LibraryScreen
import de.folio.reader.ui.reader.ReaderScreen
import de.folio.reader.ui.settings.SettingsScreen

@Composable
fun FolioApp(widthSizeClass: WindowWidthSizeClass) {
    val twoPane = widthSizeClass != WindowWidthSizeClass.Compact

    var selectedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(onClose = { showSettings = false })
        return
    }

    if (twoPane) {
        Row(modifier = Modifier.fillMaxSize()) {
            LibraryScreen(
                selectedBookId = selectedBookId,
                onBookSelected = { selectedBookId = it },
                onOpenSettings = { showSettings = true },
                showsDetailPane = true,
                modifier = Modifier.width(360.dp),
            )
            VerticalDivider()
            Box(modifier = Modifier.fillMaxSize()) {
                val id = selectedBookId
                if (id != null) {
                    ReaderScreen(
                        bookId = id,
                        embedded = true,
                        onBack = { selectedBookId = null },
                    )
                } else {
                    EmptyDetail()
                }
            }
        }
    } else {
        val id = selectedBookId
        if (id != null) {
            ReaderScreen(
                bookId = id,
                embedded = false,
                onBack = { selectedBookId = null },
            )
        } else {
            LibraryScreen(
                selectedBookId = null,
                onBookSelected = { selectedBookId = it },
                onOpenSettings = { showSettings = true },
                showsDetailPane = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun EmptyDetail() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoStories,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Wähle links ein Buch, um zu lesen.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
