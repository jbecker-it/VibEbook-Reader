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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReaderUiState(
    val book: Book? = null,
    val spineIndex: Int = 0,
    /** Beim Öffnen wiederherzustellende Scrollposition (einmalig pro Kapitel). */
    val restoreScrollFraction: Float = 0f,
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
            val maxIndex = (book?.spine?.size ?: 1) - 1
            _state.value = ReaderUiState(
                book = book,
                spineIndex = (saved?.spineIndex ?: 0).coerceIn(0, maxIndex.coerceAtLeast(0)),
                restoreScrollFraction = saved?.scrollFraction ?: 0f,
                loading = false,
            )
            lastScrollFraction = saved?.scrollFraction ?: 0f
        }
    }

    fun goToChapter(index: Int) {
        val book = _state.value.book ?: return
        val clamped = index.coerceIn(0, book.spine.size - 1)
        _state.value = _state.value.copy(spineIndex = clamped, restoreScrollFraction = 0f)
        lastScrollFraction = 0f
        scheduleSave()
    }

    fun nextChapter() = goToChapter(_state.value.spineIndex + 1)
    fun previousChapter() = goToChapter(_state.value.spineIndex - 1)

    /** Vom WebView gemeldete Scrollposition (0..1) innerhalb des Kapitels. */
    fun onScroll(fraction: Float) {
        lastScrollFraction = fraction
        scheduleSave()
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

    /** Sofort speichern – vom Reader-Screen beim Verlassen aufgerufen. */
    fun saveNow() {
        saveJob?.cancel()
        viewModelScope.launch { persist() }
    }
}
