package de.folio.reader.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.folio.reader.ui.library.LibraryScreen
import de.folio.reader.ui.reader.ReaderScreen
import de.folio.reader.ui.settings.SettingsScreen

/** Each reader entry owns its ViewModel; leaving it releases its book and observers. */
@Composable
fun FolioApp(importedBookId: String? = null, onImportConsumed: () -> Unit = {}, eInk: Boolean = false) {
    val nav = rememberNavController()
    androidx.compose.runtime.LaunchedEffect(importedBookId) { importedBookId?.let { nav.navigate("reader/$it"); onImportConsumed() } }
    NavHost(navController = nav, startDestination = "library",
        enterTransition = { if (eInk) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.fadeIn() },
        exitTransition = { if (eInk) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.fadeOut() },
        popEnterTransition = { if (eInk) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.fadeIn() },
        popExitTransition = { if (eInk) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.fadeOut() }) {
        composable("library") { LibraryScreen(onBookSelected = { nav.navigate("reader/$it") }, onOpenSettings = { nav.navigate("settings") }) }
        composable("settings") { SettingsScreen(onClose = { nav.popBackStack() }) }
        composable("reader/{bookId}") { entry -> ReaderScreen(bookId = entry.arguments?.getString("bookId").orEmpty(), onBack = { nav.popBackStack() }) }
    }
}
