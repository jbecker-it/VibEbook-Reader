package de.folio.reader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.folio.reader.data.repository.BookRepository
import de.folio.reader.data.settings.SettingsRepository
import de.folio.reader.di.ApplicationScope
import de.folio.reader.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

 data class ReaderUiState(
    val showResumeMarker: Boolean = false,
    val linkedDocument: String? = null,
    val book: Book? = null, val spineIndex: Int = 0,
    val restoreScrollFraction: Float = 0f, val restoreCharOffset: Int = -1,
    val chapterFraction: Float = 0f, val favorite: Boolean = false, val loading: Boolean = true,
    val layouts: Map<String, Boolean> = emptyMap(), val layoutMode: BookLayoutMode = BookLayoutMode.AUTO,
    val error: String? = null, val remotePosition: ReadingProgress? = null,
    val editionChanged: Boolean = false, val restoreToken: Long = 0, val fragment: String = "",
    val returnPosition: ReadingProgress? = null,
    val zoom: Float = 1f, val history: List<ReadingProgress> = emptyList(), val bookmarks: List<Bookmark> = emptyList(),
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository, private val settingsRepository: SettingsRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val id = MutableStateFlow("")
    @OptIn(ExperimentalCoroutinesApi::class)
    val readerPreferences = id.flatMapLatest { if (it.isBlank()) settingsRepository.readerPreferences else settingsRepository.bookPreferences(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderPreferences())
    val pageLayout = settingsRepository.pageLayout.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PageLayoutMode.AUTO)
    val eInkMode = settingsRepository.eInkMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _state = MutableStateFlow(ReaderUiState())
    val state = _state.asStateFlow()
    private var observeJob: Job? = null
    private var saveJob: Job? = null
    private var release: (() -> Unit)? = null
    @Volatile private var changedAt: Long? = null
    @Volatile private var generation = 0L
    private var lastNavigationAt = 0L
    private val saveLock = Mutex()
    private var appliedPosition: ReadingProgress? = null

    fun load(bookId: String) {
        if (id.value == bookId && observeJob != null) return
        id.value = bookId
        observeJob = viewModelScope.launch {
            var initialized = false
            try { bookRepository.observeBook(bookId).collect { current ->
                if (!initialized) {
                    initialized = true
                    current?.let { release = bookRepository.retain(it) }
                    val saved = current?.progress
                    val editionChanged = saved?.contentRevision?.isNotBlank() == true && current != null && saved.contentRevision != current.contentRevision
                    val root = current?.let { bookRepository.extractionRoot(it) }
                    val indexByPath = if (saved?.chapterPath?.isNotBlank() == true && root != null && current != null) current.spine.indexOfFirst { java.io.File(it).relativeTo(root).invariantSeparatorsPath == saved.chapterPath } else -1
                    _state.value = ReaderUiState(showResumeMarker = (saved?.updatedAt ?: 0) > 0, book = current?.copy(tocJson = bookRepository.navigation(current)), favorite = current?.favorite ?: false, loading = false,
                        spineIndex = (if (indexByPath >= 0) indexByPath else saved?.spineIndex ?: 0).coerceIn(0, (current?.spine?.lastIndex ?: 0).coerceAtLeast(0)),
                        restoreScrollFraction = saved?.scrollFraction ?: 0f, chapterFraction = saved?.scrollFraction ?: 0f,
                        restoreCharOffset = if (editionChanged) -1 else saved?.charOffset ?: -1, editionChanged = editionChanged,
                        layouts = current?.let { bookRepository.readLayouts(it) }.orEmpty(),
                        layoutMode = settingsRepository.bookLayout(bookId).first(), zoom = settingsRepository.bookZoom(bookId))
                    appliedPosition = saved
                    current?.let { bookRepository.opened(it.id) }
                    refreshNavigation()
                    refreshRemote()
                } else if (current != null) {
                    // Keep an opened extraction alive when a newer edition downloads in the background.
                    val opened = _state.value.book
                    _state.update { it.copy(book = if (opened != null) current.copy(spine = opened.spine, contentRevision = opened.contentRevision, tocJson = opened.tocJson) else current, favorite = current.favorite) }
                    val incoming = current.progress
                    if (incoming != null && incoming.deviceId != settingsRepository.deviceId() && incoming.updatedAt > (appliedPosition?.updatedAt ?: 0)) {
                        _state.update { it.copy(remotePosition = incoming) }
                    }
                }
            } } catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(loading = false, error = e.message) } }
        }
        observeJob?.invokeOnCompletion { cause -> if (cause != null && cause !is CancellationException) _state.update { it.copy(loading = false, error = cause.message) } }
    }
    fun bookRoot() = _state.value.book?.let { bookRepository.extractionRoot(it) }
    fun refreshRemote() { action { withTimeoutOrNull(5_000) { bookRepository.syncProgress(id.value) } } }
    fun acceptRemote() { val p = _state.value.remotePosition ?: return; restore(p, false); appliedPosition = p; _state.update { it.copy(remotePosition = null) } }
    fun dismissRemote() { appliedPosition = _state.value.remotePosition; _state.update { it.copy(remotePosition = null) } }
    fun clearError() { _state.update { it.copy(error = null, editionChanged = false) } }
    fun dismissResumeMarker() { _state.update { it.copy(showResumeMarker = false) } }
    fun goToChapter(index: Int, restoreFraction: Float = 0f, fragment: String = "", rememberReturn: Boolean = false) {
        val book = _state.value.book ?: return
        if (book.spine.isEmpty()) return
        _state.update { it.copy(showResumeMarker = false, linkedDocument = null, returnPosition = if (rememberReturn) displayPosition() else it.returnPosition, spineIndex = index.coerceIn(0, book.spine.lastIndex), restoreScrollFraction = restoreFraction,
            restoreCharOffset = -1, chapterFraction = restoreFraction, restoreToken = it.restoreToken + 1, fragment = fragment) }
        scheduleSave()
    }
    fun openLink(path: String, fragment: String) {
        val book = _state.value.book ?: return
        val target = java.io.File(path).canonicalFile; val root = bookRepository.extractionRoot(book) ?: return
        if (!target.path.startsWith(root.canonicalPath + java.io.File.separator) || !target.isFile || target.extension.lowercase() !in setOf("xhtml", "html", "htm", "svg")) return
        saveNow()
        val index = book.spine.indexOf(target.path)
        if (index >= 0) goToChapter(index, 0f, fragment, true)
        else _state.update { it.copy(showResumeMarker = false, linkedDocument = target.path, returnPosition = displayPosition(), fragment = fragment,
            restoreScrollFraction = 0f, chapterFraction = 0f, restoreCharOffset = -1, restoreToken = it.restoreToken + 1) }
    }
    private fun displayPosition(): ReadingProgress? { val s = _state.value; if (s.linkedDocument != null) return s.returnPosition; val book = s.book ?: return null
        return ReadingProgress(book.id, s.spineIndex, s.restoreScrollFraction, s.restoreCharOffset, 0, "display", contentRevision = book.contentRevision) }
    fun returnToPosition() { val p = _state.value.returnPosition ?: return; restore(p); _state.update { it.copy(returnPosition = null) } }
    fun nextChapter() { if (_state.value.linkedDocument != null) { returnToPosition(); return }; val s = _state.value; if (s.spineIndex < (s.book?.spine?.lastIndex ?: 0)) goToChapter(s.spineIndex + 1) }
    fun previousChapter() { if (_state.value.linkedDocument != null) { returnToPosition(); return }; if (_state.value.spineIndex > 0) goToChapter(_state.value.spineIndex - 1, 1f) }
    fun onPosition(fraction: Float, charOffset: Int, fromUser: Boolean) {
        if (!fraction.isFinite()) return
        _state.update { it.copy(showResumeMarker = it.showResumeMarker && !fromUser, chapterFraction = fraction, restoreScrollFraction = fraction, restoreCharOffset = charOffset.coerceAtLeast(-1)) }
        if (fromUser && _state.value.linkedDocument == null) scheduleSave()
    }
    fun setReaderPreferences(value: ReaderPreferences) = action { settingsRepository.saveBookPreferences(id.value, value) }
    fun setZoom(value: Float) { _state.update { it.copy(zoom = value) }; action { settingsRepository.setBookZoom(id.value, value) } }
    fun setLayoutMode(mode: BookLayoutMode) { _state.update { it.copy(layoutMode = mode) }; action { settingsRepository.setBookLayout(id.value, mode) } }
    fun toggleFavorite() = action { bookRepository.toggleFavorite(id.value) }
    fun setFinished() = action { bookRepository.setFinished(id.value, _state.value.book?.progress?.finished != true) }
    fun refreshNavigation() = action { val history = bookRepository.history(id.value); val marks = settingsRepository.bookmarks(id.value); _state.update { it.copy(history = history, bookmarks = marks) } }
    fun addBookmark(title: String = "") = action { snapshot(System.currentTimeMillis())?.let { settingsRepository.addBookmark(it, title) }; refreshNavigation() }
    fun removeBookmark(index: Int) = action { settingsRepository.removeBookmark(id.value, index); refreshNavigation() }
    fun restore(p: ReadingProgress, save: Boolean = true) {
        val book = _state.value.book ?: return
        val root = bookRepository.extractionRoot(book)
        val byPath = if (root != null && p.chapterPath.isNotBlank()) book.spine.indexOfFirst { java.io.File(it).relativeTo(root).invariantSeparatorsPath == p.chapterPath } else -1
        _state.update { it.copy(showResumeMarker = !save, linkedDocument = null, returnPosition = if (save) displayPosition() else it.returnPosition, spineIndex = (if (byPath >= 0) byPath else p.spineIndex).coerceIn(0, book.spine.lastIndex), restoreScrollFraction = p.scrollFraction, chapterFraction = p.scrollFraction,
            restoreCharOffset = if (p.contentRevision.isNotBlank() && p.contentRevision != book.contentRevision) -1 else p.charOffset, fragment = "", restoreToken = it.restoreToken + 1) }
        if (save) scheduleSave()
    }
    fun saveNow() { saveJob?.cancel(); persist() }
    private fun scheduleSave() { generation++; lastNavigationAt = maxOf(System.currentTimeMillis(), lastNavigationAt + 1, (_state.value.book?.progress?.updatedAt ?: 0) + 1); changedAt = lastNavigationAt; saveJob?.cancel(); saveJob = viewModelScope.launch { delay(800); persist() } }
    private suspend fun snapshot(at: Long): ReadingProgress? {
        val s = _state.value; if (s.linkedDocument != null) return null; val book = s.book ?: return null; if (book.spine.isEmpty()) return null
        val root = bookRepository.extractionRoot(book)
        return ReadingProgress(book.id, s.spineIndex, s.restoreScrollFraction, s.restoreCharOffset, at, settingsRepository.deviceId(),
            finished = book.progress?.finished ?: false, contentRevision = book.contentRevision,
            chapterPath = root?.let { java.io.File(book.spine[s.spineIndex]).relativeTo(it).invariantSeparatorsPath }.orEmpty())
    }
    private fun persist() {
        val at = changedAt ?: return; val capturedGeneration = generation
        // Capture the screen synchronously; later turns must not change this write's position.
        val captured = _state.value
        if (captured.linkedDocument != null) return
        appScope.launch { saveLock.withLock {
            if (changedAt == null && capturedGeneration == generation) return@withLock
            try {
                val book = captured.book ?: return@withLock
                val root = bookRepository.extractionRoot(book)
                val p = ReadingProgress(book.id, captured.spineIndex, captured.restoreScrollFraction, captured.restoreCharOffset, at, settingsRepository.deviceId(),
                    finished = book.progress?.finished ?: false, contentRevision = book.contentRevision,
                    chapterPath = root?.let { java.io.File(book.spine[captured.spineIndex]).relativeTo(it).invariantSeparatorsPath }.orEmpty())
                bookRepository.saveProgress(p)
                withContext(Dispatchers.Main.immediate) { appliedPosition = p; if (generation == capturedGeneration) changedAt = null }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(error = "Lesestand konnte nicht gespeichert werden: ${e.message}") } }
        } }
    }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Aktion fehlgeschlagen") } } } }
    override fun onCleared() { saveNow(); release?.invoke(); super.onCleared() }
}
