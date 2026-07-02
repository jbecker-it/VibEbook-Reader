package de.folio.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import de.folio.reader.ui.library.LibraryScreen
import de.folio.reader.ui.reader.ReaderScreen
import de.folio.reader.ui.settings.SettingsScreen

/**
 * Navigations-Shell. Der Reader läuft immer im Vollbild – auf jedem Formfaktor.
 * Die Bibliothek skaliert über ihr adaptives Grid selbst auf Foldables/Tablets.
 */
@Composable
fun FolioApp() {
    var selectedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val bookId = selectedBookId
    when {
        showSettings -> SettingsScreen(onClose = { showSettings = false })

        bookId != null -> ReaderScreen(
            bookId = bookId,
            onBack = { selectedBookId = null },
        )

        else -> LibraryScreen(
            onBookSelected = { selectedBookId = it },
            onOpenSettings = { showSettings = true },
        )
    }
}
