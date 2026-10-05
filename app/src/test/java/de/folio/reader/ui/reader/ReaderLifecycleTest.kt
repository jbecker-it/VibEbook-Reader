package de.folio.reader.ui.reader

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import de.folio.reader.data.epub.EpubParser
import de.folio.reader.data.local.BookEntity
import de.folio.reader.data.local.FolioDatabase
import de.folio.reader.data.nextcloud.NextcloudClient
import de.folio.reader.data.progress.ProgressRepository
import de.folio.reader.data.repository.BookRepository
import de.folio.reader.data.settings.CredentialCipher
import de.folio.reader.data.settings.SettingsRepository
import de.folio.reader.domain.model.ReadingProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReaderLifecycleTest {
    private lateinit var db: FolioDatabase
    private lateinit var progress: ProgressRepository
    private lateinit var repository: BookRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private val id = "0123456789abcdef0123456789abcdef"
    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "progress").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, FolioDatabase::class.java).build()
        progress = ProgressRepository(context); settings = SettingsRepository(context, CredentialCipher())
        repository = BookRepository(context, db.bookDao(), NextcloudClient(), EpubParser(), progress, settings)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = File(context.filesDir, "books/$id-original").apply { mkdirs() }
        val chapter = File(dir, "chapter.xhtml").apply { writeText("<html><body>text</body></html>") }
        db.bookDao().upsert(BookEntity(id, "book.epub", "Book", "", null, org.json.JSONArray(listOf(chapter.path)).toString(), true, 42, 0, localOnly = true))
    }
    @After fun cleanup() { scope.cancel(); db.close(); Dispatchers.resetMain() }
    private suspend fun open(): Pair<ReaderViewModel, ViewModelStore> {
        val vm = ReaderViewModel(repository, settings, scope)
        val store = ViewModelStore().apply { put("reader", vm) }
        vm.load(id)
        withTimeout(10000) { vm.state.firstLoaded() }
        return vm to store
    }
    private suspend fun kotlinx.coroutines.flow.StateFlow<ReaderUiState>.firstLoaded() = first { !it.loading }
    @Test fun reopeningUsesFreshProgressAndPassiveReportsDoNotSave() = runBlocking {
        progress.write(ReadingProgress(id, 0, .2f, 20, 100, "other"), false)
        val first = open(); assertEquals(20, first.first.state.value.restoreCharOffset); first.second.clear()
        val newer = ReadingProgress(id, 0, .6f, 60, 200, "other")
        progress.write(newer, false)
        val reopened = open(); assertEquals(60, reopened.first.state.value.restoreCharOffset)
        reopened.first.onPosition(.6f, 60, false); reopened.first.saveNow(); delay(100)
        assertEquals(newer, progress.read(id)); reopened.second.clear()
    }
    @Test fun rapidSavesKeepLatestUserNavigation() = runBlocking {
        val opened = open(); val vm = opened.first
        vm.onPosition(.2f, 20, true); vm.saveNow(); vm.onPosition(.8f, 80, true); vm.saveNow()
        withTimeout(10000) { while (progress.read(id)?.charOffset != 80) delay(10) }
        delay(100); assertEquals(80, progress.read(id)!!.charOffset)
        assertTrue(progress.pendingIds().isEmpty()); opened.second.clear()
    }
    @Test fun diskFailureKeepsDirtyPositionForRetry() = runBlocking {
        val opened = open(); val vm = opened.first
        val folder = File(RuntimeEnvironment.getApplication().filesDir, "progress")
        folder.deleteRecursively(); folder.writeText("blocks writes")
        vm.onPosition(.8f, 80, true); vm.saveNow()
        withTimeout(10000) { vm.state.first { it.error != null } }
        assertNull(progress.read(id)); folder.delete(); folder.mkdirs()
        vm.saveNow()
        withTimeout(10000) { while (progress.read(id)?.charOffset != 80) delay(10) }
        assertEquals(80, progress.read(id)!!.charOffset); opened.second.clear()
    }
    @Test fun nonSpineFootnoteReturnsWithoutOverwritingMainPosition() = runBlocking {
        val base = ReadingProgress(id, 0, .2f, 20, 100, "other")
        progress.write(base, false)
        val opened = open(); val vm = opened.first
        val notes = File(vm.bookRoot(), "notes.xhtml").apply { writeText("<html><body id='note'>Footnote</body></html>") }
        vm.openLink(notes.path, "note"); assertEquals(notes.canonicalPath, vm.state.value.linkedDocument)
        vm.onPosition(.8f, 80, true); vm.saveNow(); delay(100)
        assertEquals(base, progress.read(id))
        vm.returnToPosition(); assertNull(vm.state.value.linkedDocument); assertEquals(20, vm.state.value.restoreCharOffset)
        vm.saveNow(); withTimeout(10000) { while ((progress.read(id)?.updatedAt ?: 0) <= 100) delay(10) }
        assertEquals(20, progress.read(id)!!.charOffset); opened.second.clear()
    }
}
