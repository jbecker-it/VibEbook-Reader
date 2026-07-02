package de.folio.reader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.folio.reader.data.repository.BookRepository
import de.folio.reader.data.settings.SettingsRepository
import de.folio.reader.domain.model.Book
import de.folio.reader.domain.model.ReadingProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReaderUiState(
    val book: Book? = null,
    val spineIndex: Int = 0,
    /** Beim Laden des Kapitels wiederherzustellende Scrollposition (0..1). */
    val restoreScrollFraction: Float = 0f,
    /** Live-Position im aktuellen Kapitel (0..1) – für die Prozentanzeige. */
    val chapterFraction: Float = 0f,
    val favorite: Boolean = false,
    val loading: Boolean = true,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var loadedId: String? = null
    private var saveJob: Job? = null
    private var lastScrollFraction: Float = 0f

    fun load(bookId: String) {
        if (loadedId == bookId) return
        loadedId = bookId
        _state.value = ReaderUiState(loading = true)
        viewModelScope.launch {
            // Vor dem Öffnen einmal mit dem NAS abgleichen, um den neuesten Stand zu holen.
            runCatching { bookRepository.syncProgress(bookId) }
            val book = bookRepository.observeBook(bookId).first()
            val saved = book?.progress
            val maxIndex = ((book?.spine?.size ?: 1) - 1).coerceAtLeast(0)
            val restore = saved?.scrollFraction ?: 0f
            _state.value = ReaderUiState(
                book = book,
                spineIndex = (saved?.spineIndex ?: 0).coerceIn(0, maxIndex),
                restoreScrollFraction = restore,
                chapterFraction = restore,
                favorite = book?.favorite ?: false,
                loading = false,
            )
            lastScrollFraction = restore
        }
    }

    /** [restoreFraction]: 0 = Kapitelanfang, 1 = Kapitelende (Rückwärtsblättern). */
    fun goToChapter(index: Int, restoreFraction: Float = 0f) {
        val book = _state.value.book ?: return
        if (book.spine.isEmpty()) return
        val clamped = index.coerceIn(0, book.spine.size - 1)
        if (clamped == _state.value.spineIndex && restoreFraction == _state.value.restoreScrollFraction) return
        lastScrollFraction = restoreFraction
        _state.update {
            it.copy(
                spineIndex = clamped,
                restoreScrollFraction = restoreFraction,
                chapterFraction = restoreFraction,
            )
        }
        scheduleSave()
    }

    fun nextChapter() = goToChapter(_state.value.spineIndex + 1, restoreFraction = 0f)

    /** Rückwärts über die Kapitelgrenze: ans Ende des vorherigen Kapitels springen. */
    fun previousChapter() = goToChapter(_state.value.spineIndex - 1, restoreFraction = 1f)

    /** Vom WebView gemeldete Scrollposition (0..1) innerhalb des Kapitels. */
    fun onScroll(fraction: Float) {
        lastScrollFraction = fraction
        _state.update { it.copy(chapterFraction = fraction) }
        scheduleSave()
    }

    fun toggleFavorite() {
        val book = _state.value.book ?: return
        _state.update { it.copy(favorite = !it.favorite) }
        viewModelScope.launch { bookRepository.toggleFavorite(book.id) }
    }

    /** Sofort speichern – vom Reader-Screen beim Verlassen aufgerufen. */
    fun saveNow() {
        saveJob?.cancel()
        viewModelScope.launch { persist() }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1_200) // debouncen – nicht bei jedem Pixel speichern
            persist()
        }
    }

    private suspend fun persist() {
        val s = _state.value
        val book = s.book ?: return
        if (book.spine.isEmpty()) return
        val finished = s.spineIndex >= book.spine.lastIndex && lastScrollFraction > 0.98f
        bookRepository.saveProgress(
            ReadingProgress(
                bookId = book.id,
                spineIndex = s.spineIndex,
                scrollFraction = lastScrollFraction,
                updatedAt = System.currentTimeMillis(),
                deviceId = settingsRepository.deviceId(),
                finished = finished,
            )
        )
    }
}
