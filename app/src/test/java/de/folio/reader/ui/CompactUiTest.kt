package de.folio.reader.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import de.folio.reader.domain.model.Book
import de.folio.reader.domain.model.ThemeMode
import de.folio.reader.ui.library.BookCard
import de.folio.reader.ui.reader.BottomOverlay
import de.folio.reader.ui.reader.TopOverlay
import de.folio.reader.ui.settings.SettingsOverview
import de.folio.reader.ui.theme.FolioTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Regression coverage for the reported space and discoverability problems on narrow displays. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactUiTest {
    @get:Rule val compose = createComposeRule()
    private val error = "Serverantwort: Die Buchdatei konnte nicht heruntergeladen werden. Bitte Verbindung prüfen."
    private fun book(downloaded: Boolean = true) = Book("book", "Roman.epub", "Ein langer Buchtitel", "Autor", null, emptyList(), downloaded, 1234567, null, downloadError = error)

    @Test fun bookActionsStayReachableWithoutPermanentButtonsOrErrorParagraphs() {
        var removed = false
        compose.setContent { FolioTheme(ThemeMode.LIGHT, eInk = true) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { BookCard(book(), {}, {}, {}, { removed = true }) }
                    Column(Modifier.weight(1f)) { BookCard(book(false).copy(id = "other", downloadError = "", title = "Zweites Buch"), {}, {}, {}, {}) }
                }
            }
        } }
        compose.onNodeWithText(error).assertDoesNotExist()
        compose.onNodeWithText("Lokale Kopie entfernen").assertDoesNotExist()
        screenshot("library-cards")
        compose.onAllNodesWithContentDescription("Buchaktionen")[0].performClick()
        compose.onNodeWithText("Lokale Kopie entfernen").assertIsDisplayed().performClick()
        assertTrue(removed)
        compose.onNodeWithContentDescription("Downloadproblem anzeigen").performClick()
        compose.onNodeWithText(error).assertIsDisplayed()
    }

    @Test fun settingsOverviewShowsDistinctCategoriesOnNarrowDisplay() {
        var selected = ""
        compose.setContent { FolioTheme(ThemeMode.LIGHT, eInk = true) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                SettingsOverview(connected = true, onSelect = { selected = it })
            }
        } }
        listOf("Nextcloud & Synchronisierung", "Darstellung", "Bedienung", "Offline-Speicher", "Lesestände sichern", "App & Updates").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("App-Passwort").assertDoesNotExist()
        screenshot("settings-overview")
        compose.onNodeWithText("Bedienung").performClick()
        assertEquals("Bedienung", selected)
    }

    @Test fun readerBarsStayCompactAndChapterSelectionRequiresExplicitConfirmation() {
        var target = -1
        compose.setContent { FolioTheme(ThemeMode.LIGHT, eInk = true) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).testTag("bars")) {
                TopOverlay("Ein langer Buchtitel", favorite = false, finished = false, canBookmark = true,
                    onToggleFinished = {}, onBookmark = {}, onBack = {}, onToggleFavorite = {}, onTypography = {}, onNavigation = {})
                BottomOverlay(1, 10, .5f, {}, {}, { target = it })
            }
        } }
        val height = compose.onNodeWithTag("bars").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Reader bars must occupy less than 160 dp, actual $height", height < 160f)
        compose.onNodeWithText("Kapitel wählen").assertDoesNotExist()
        screenshot("reader-bars")
        compose.onNodeWithText("Kapitel 2 / 10  ·  15 %").performClick()
        compose.onNodeWithText("Kapitel wählen").assertIsDisplayed()
        assertEquals(-1, target)
        compose.onNodeWithText("Abbrechen").performClick()
        assertEquals(-1, target)
        compose.onNodeWithText("Kapitel 2 / 10  ·  15 %").performClick()
        compose.onNodeWithText("Öffnen").performClick()
        assertEquals(1, target)
    }

    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val target = File("build/reports/ui/$name.png").apply { parentFile.mkdirs() }
        target.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
