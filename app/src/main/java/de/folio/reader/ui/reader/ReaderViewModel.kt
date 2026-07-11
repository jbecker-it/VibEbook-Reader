package de.folio.reader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.folio.reader.data.repository.BookRepository
import de.folio.reader.data.settings.SettingsRepository
import de.folio.reader.di.ApplicationScope
import de.folio.reader.domain.model.Book
import de.folio.reader.domain.model.PageLayoutMode
import de.folio.reader.domain.model.ReadingProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class ReaderUiState(
    val book: Book? = null,
    val spineIndex: Int = 0,
    /** Beim Laden des Kapitels wiederherzustellende Position als Anteil (0..1). */
    val restoreScrollFraction: Float = 0f,
    /** Wortgenauer Anker (Zeichen-Offset im Kapiteltext); -1 = keiner. */
    val restoreCharOffset: Int = -1,
    /** Live-Position im aktuellen Kapitel (0..1) – für die Prozentanzeige. */
    val chapterFraction: Float = 0f,
    val favorite: Boolean = false,
    val loading: Boolean = true,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** Ein- oder zweiseitiges Layout (aus den Einstellungen). */
    val pageLayout: StateFlow<PageLayoutMode> = settingsRepository.pageLayout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PageLayoutMode.AUTO)

    /** E-Ink-Modus: keine Blätter-/Menü-Animationen. */
    val eInkMode: StateFlow<Boolean> = settingsRepository.eInkMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var loadedId: String? = null
    private var saveJob: Job? = null
    private var lastScrollFraction: Float = 0f
    private var lastCharOffset: Int = -1

    fun load(bookId: String) {
        if (loadedId == bookId) return
        loadedId = bookId
        _state.value = ReaderUiState(loading = true)
        viewModelScope.launch {
            // Vor dem Öffnen einmal mit dem NAS abgleichen, um den neuesten Stand
            // zu holen – aber begrenzt, damit ein nicht erreichbares NAS das
            // Öffnen nicht blockiert.
            runCatching {
                withTimeoutOrNull(5_000) { bookRepository.syncProgress(bookId) }
            }
            val book = bookRepository.observeBook(bookId).first()
            val saved = book?.progress
            val maxIndex = ((book?.spine?.size ?: 1) - 1).coerceAtLeast(0)
            val restore = saved?.scrollFraction ?: 0f
            val anchor = saved?.charOffset ?: -1
            _state.value = ReaderUiState(
                book = book,
                spineIndex = (saved?.spineIndex ?: 0).coerceIn(0, maxIndex),
                restoreScrollFraction = restore,
                restoreCharOffset = anchor,
                chapterFraction = restore,
                favorite = book?.favorite ?: false,
                loading = false,
            )
            lastScrollFraction = restore
            lastCharOffset = anchor
        }
    }

    /**
     * [restoreFraction]: 0 = Kapitelanfang, 1 = Kapitelende (Rückwärtsblättern).
     * Bei Kapitelwechseln gibt es keinen sinnvollen Zeichen-Anker → -1.
     */
    fun goToChapter(index: Int, restoreFraction: Float = 0f) {
        val book = _state.value.book ?: return
        if (book.spine.isEmpty()) return
        val clamped = index.coerceIn(0, book.spine.size - 1)
        if (clamped == _state.value.spineIndex &&
            restoreFraction == _state.value.restoreScrollFraction
        ) return
        lastScrollFraction = restoreFraction
        lastCharOffset = -1
        _state.update {
            it.copy(
                spineIndex = clamped,
                restoreScrollFraction = restoreFraction,
                restoreCharOffset = -1,
                chapterFraction = restoreFraction,
            )
        }
        scheduleSave()
    }

    fun nextChapter() = goToChapter(_state.value.spineIndex + 1, restoreFraction = 0f)

    /** Rückwärts über die Kapitelgrenze: ans Ende des vorherigen Kapitels springen. */
    fun previousChapter() = goToChapter(_state.value.spineIndex - 1, restoreFraction = 1f)

    /**
     * Vom WebView gemeldete Position: Anteil im Kapitel plus wortgenauer
     * Zeichen-Anker der aktuellen Seite. Die Restore-Felder werden mitgeführt,
     * damit ein neu erzeugtes WebView (z.B. beim Auf-/Zuklappen eines
     * Foldables) an der aktuellen Seite weitermacht – nicht am Kapitelanfang.
     */
    fun onPosition(fraction: Float, charOffset: Int) {
        lastScrollFraction = fraction
        lastCharOffset = charOffset
        _state.update {
            it.copy(
                chapterFraction = fraction,
                restoreScrollFraction = fraction,
                restoreCharOffset = charOffset,
            )
        }
        scheduleSave()
    }

    fun toggleFavorite() {
        val book = _state.value.book ?: return
        _state.update { it.copy(favorite = !it.favorite) }
        viewModelScope.launch { bookRepository.toggleFavorite(book.id) }
    }

    /**
     * Sofort speichern – beim Verlassen des Readers und bei ON_STOP (App in
     * den Hintergrund, Display gewechselt). Läuft im App-Scope, damit der
     * Schreibvorgang das Aufräumen des ViewModels überlebt – sonst geht die
     * Position beim schnellen Schließen verloren.
     */
    fun saveNow() {
        saveJob?.cancel()
        persist()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1_200) // debouncen – nicht bei jedem Blättern schreiben
            persist()
        }
    }

    private fun persist() {
        val s = _state.value
        val book = s.book ?: return
        if (book.spine.isEmpty()) return
        // Werte jetzt festhalten – der Schreibvorgang läuft asynchron weiter.
        val fraction = lastScrollFraction
        val anchor = lastCharOffset
        val finished = s.spineIndex >= book.spine.lastIndex && fraction > 0.98f
        appScope.launch {
            bookRepository.saveProgress(
                ReadingProgress(
                    bookId = book.id,
                    spineIndex = s.spineIndex,
                    scrollFraction = fraction,
                    charOffset = anchor,
                    updatedAt = System.currentTimeMillis(),
                    deviceId = settingsRepository.deviceId(),
                    finished = finished,
                )
            )
        }
    }
}
