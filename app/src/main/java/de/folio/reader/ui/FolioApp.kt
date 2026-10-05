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
fun FolioApp() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "library") {
        composable("library") { LibraryScreen(onBookSelected = { nav.navigate("reader/$it") }, onOpenSettings = { nav.navigate("settings") }) }
        composable("settings") { SettingsScreen(onClose = { nav.popBackStack() }) }
        composable("reader/{bookId}") { entry -> ReaderScreen(bookId = entry.arguments?.getString("bookId").orEmpty(), onBack = { nav.popBackStack() }) }
    }
}
