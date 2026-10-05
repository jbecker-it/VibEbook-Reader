package de.folio.reader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.folio.reader.data.repository.BookRepository
import de.folio.reader.data.settings.SettingsRepository
import de.folio.reader.data.sync.SyncManager
import de.folio.reader.data.sync.SyncStatus
import de.folio.reader.domain.model.Book
import de.folio.reader.domain.model.isFinished
import de.folio.reader.domain.model.isStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

enum class LibraryTab(val label: String) {
    BROWSE("Bibliothek"),
    READING("Lese ich"),
    FAVORITES("Favoriten"),
}

/** Ein Unterordner im Bibliotheks-Browser (spiegelt die Nextcloud-Ordnerstruktur). */
data class FolderItem(val path: String, val name: String, val bookCount: Int)

data class BrowseContent(
    val folders: List<FolderItem> = emptyList(),
    val books: List<Book> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager,
) : ViewModel() {

    val eInkMode = settingsRepository.eInkMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val downloads = mutableSetOf<String>()
    private val downloadLock = kotlinx.coroutines.sync.Mutex()
    private val _downloadTitle = MutableStateFlow<String?>(null); val downloadTitle = _downloadTitle.asStateFlow()
    private val _openedBook = MutableStateFlow<String?>(null); val openedBook = _openedBook.asStateFlow()
    fun consumedOpenedBook() { _openedBook.value = null }
    private val _query = MutableStateFlow(""); val query = _query.asStateFlow()
    private val _sort = MutableStateFlow("Titel"); val sort = _sort.asStateFlow()
    private val _readFilter = MutableStateFlow("Alle"); val readFilter = _readFilter.asStateFlow()
    fun cycleReadFilter() { _readFilter.value = when (_readFilter.value) { "Alle" -> "Ungelesen"; "Ungelesen" -> "Gelesen"; else -> "Alle" } }
    private val _offlineOnly = MutableStateFlow(false); val offlineOnly = _offlineOnly.asStateFlow()
    fun search(value: String) { _query.value = value }
    fun cycleSort() { _sort.value = when (_sort.value) { "Titel" -> "Zuletzt gelesen"; "Zuletzt gelesen" -> "Zuletzt hinzugefügt"; else -> "Titel" } }
    fun toggleOfflineFilter() { _offlineOnly.value = !_offlineOnly.value }
    private fun rememberLocation() { viewModelScope.launch { settingsRepository.saveLibraryLocation(_tab.value.name, _currentFolder.value) } }
    private fun sorted(list: List<Book>, order: String): List<Book> = when (order) { "Zuletzt gelesen" -> list.sortedByDescending { maxOf(it.lastOpenedAt, it.progress?.updatedAt ?: 0) }; "Zuletzt hinzugefügt" -> list.sortedByDescending { it.addedAt }; else -> list.sortedBy { it.title.lowercase() } }
    private val mutationLock = kotlinx.coroutines.sync.Mutex()
    private var undo: (suspend () -> Unit)? = null
    private val _notice = MutableStateFlow<String?>(null); val notice = _notice.asStateFlow()
    fun undoLast() { val action = undo ?: return; undo = null; runLibraryAction(serial = true) { action(); _notice.value = null } }

    val books: StateFlow<List<Book>> = bookRepository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredBooks = combine(books, _query, _offlineOnly, _sort, _readFilter) { all, q, offline, order, read ->
        sorted(all.filter { (!offline || it.downloaded) && (read == "Alle" || (read == "Gelesen") == it.isFinished) && (q.isBlank() || (it.title + " " + it.author + " " + it.relativePath).contains(q, true)) }, order)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncStatus: StateFlow<SyncStatus> = syncManager.syncStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus())

    val isConfigured: StateFlow<Boolean> = settingsRepository.nextcloudSettings
        .map { it.isConfigured }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _tab = MutableStateFlow(LibraryTab.BROWSE)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    /** Aktueller Ordner im Browse-Tab ("" = Wurzel der Bibliothek). */
    private val _currentFolder = MutableStateFlow("")
    val currentFolder: StateFlow<String> = _currentFolder.asStateFlow()

    init { viewModelScope.launch { val location = settingsRepository.libraryLocation(); _tab.value = runCatching { LibraryTab.valueOf(location.first) }.getOrDefault(LibraryTab.BROWSE); _currentFolder.value = location.second } }
    /** Inhalt des aktuellen Ordners: Unterordner + direkt enthaltene Bücher. */
    val browseContent: StateFlow<BrowseContent> =
        combine(filteredBooks, _currentFolder, _query) { all, current, query -> if (query.isNotBlank()) BrowseContent(books = all) else buildBrowse(all, current) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseContent())

    /** Angefangene, noch nicht beendete Bücher – zuletzt gelesene zuerst. */
    val readingBooks: StateFlow<List<Book>> = filteredBooks
        .map { list ->
            list.filter { it.isStarted && !it.isFinished }
                .sortedByDescending { maxOf(it.lastOpenedAt, it.progress?.updatedAt ?: 0L) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteBooks: StateFlow<List<Book>> = filteredBooks
        .map { list -> list.filter { it.favorite } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectTab(tab: LibraryTab) {
        _tab.value = tab
        rememberLocation()
    }

    fun openFolder(path: String) {
        _currentFolder.value = path
        rememberLocation()
    }

    /** true, wenn eine Ebene nach oben navigiert wurde (für BackHandler). */
    fun navigateUp(): Boolean {
        val current = _currentFolder.value
        if (current.isEmpty()) return false
        _currentFolder.value = current.substringBeforeLast('/', "")
        rememberLocation()
        return true
    }

    fun syncNow() {
        viewModelScope.launch { syncManager.syncNow() }
    }

    fun downloadBook(id: String) {
        if (!downloads.add(id)) return
        runLibraryAction {
            try {
                downloadLock.withLock {
                    _downloadTitle.value = books.value.firstOrNull { it.id == id }?.title ?: "Buch"
                    try { bookRepository.downloadBook(id); _openedBook.value = id }
                    finally { _downloadTitle.value = null }
                }
            } finally { downloads.remove(id) }
        }
    }

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError = _actionError.asStateFlow()

    fun removeMissingBook(id: String) = runLibraryAction { bookRepository.removeLocalCopy(id) }
    fun importBook(uri: android.net.Uri) = runLibraryAction { _openedBook.value = bookRepository.importLocal(uri) }

    fun setFinished(id: String, finished: Boolean) = runLibraryAction(serial = true) { bookRepository.setFinished(id, finished); undo = { bookRepository.setFinished(id, !finished) }; _notice.value = if (finished) "Als gelesen markiert" else "Als ungelesen markiert" }

    private fun runLibraryAction(serial: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { if (serial) mutationLock.lock(); try { block(); _actionError.value = null } finally { if (serial) mutationLock.unlock() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _actionError.value = e.message ?: "Aktion fehlgeschlagen" }
        }
    }

    fun toggleFavorite(id: String) {
        runLibraryAction(serial = true) { bookRepository.toggleFavorite(id); undo = { bookRepository.toggleFavorite(id) }; _notice.value = "Favorit geändert" }
    }

    private fun buildBrowse(all: List<Book>, current: String): BrowseContent {
        val prefix = if (current.isEmpty()) "" else "$current/"
        val direct = mutableListOf<Book>()
        val folderCounts = linkedMapOf<String, Int>()

        for (book in all) {
            if (prefix.isNotEmpty() && !book.relativePath.startsWith(prefix)) continue
            val remainder = book.relativePath.removePrefix(prefix)
            val slash = remainder.indexOf('/')
            if (slash >= 0) {
                val name = remainder.substring(0, slash)
                folderCounts[name] = (folderCounts[name] ?: 0) + 1
            } else {
                direct += book
            }
        }

        val folders = folderCounts.entries
            .sortedBy { it.key.lowercase() }
            .map { (name, count) ->
                FolderItem(
                    path = if (current.isEmpty()) name else "$current/$name",
                    name = name,
                    bookCount = count,
                )
            }
        return BrowseContent(
            folders = folders,
            books = direct,
        )
    }
}
