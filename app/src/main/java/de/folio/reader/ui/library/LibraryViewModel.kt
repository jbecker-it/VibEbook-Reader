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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab(val label: String) {
    BROWSE("Bibliothek"),
    READING("Lese ich"),
    FAVORITES("Favoriten"),
}

/** Ein Unterordner im Bibliotheks-Browser (spiegelt die SMB-Ordnerstruktur). */
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

    val books: StateFlow<List<Book>> = bookRepository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncStatus: StateFlow<SyncStatus> = syncManager.syncStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus())

    val isConfigured: StateFlow<Boolean> = settingsRepository.smbSettings
        .map { it.isConfigured }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _tab = MutableStateFlow(LibraryTab.BROWSE)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    /** Aktueller Ordner im Browse-Tab ("" = Wurzel der Bibliothek). */
    private val _currentFolder = MutableStateFlow("")
    val currentFolder: StateFlow<String> = _currentFolder.asStateFlow()

    /** Inhalt des aktuellen Ordners: Unterordner + direkt enthaltene Bücher. */
    val browseContent: StateFlow<BrowseContent> =
        combine(books, _currentFolder) { all, current -> buildBrowse(all, current) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseContent())

    /** Angefangene, noch nicht beendete Bücher – zuletzt gelesene zuerst. */
    val readingBooks: StateFlow<List<Book>> = books
        .map { list ->
            list.filter { it.isStarted && !it.isFinished }
                .sortedByDescending { it.progress?.updatedAt ?: 0L }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteBooks: StateFlow<List<Book>> = books
        .map { list -> list.filter { it.favorite }.sortedBy { it.title.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectTab(tab: LibraryTab) {
        _tab.value = tab
    }

    fun openFolder(path: String) {
        _currentFolder.value = path
    }

    /** true, wenn eine Ebene nach oben navigiert wurde (für BackHandler). */
    fun navigateUp(): Boolean {
        val current = _currentFolder.value
        if (current.isEmpty()) return false
        _currentFolder.value = current.substringBeforeLast('/', "")
        return true
    }

    fun syncNow() {
        viewModelScope.launch { syncManager.syncNow() }
    }

    fun downloadBook(id: String) {
        viewModelScope.launch { runCatching { bookRepository.downloadBook(id) } }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch { bookRepository.toggleFavorite(id) }
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
            books = direct.sortedBy { it.title.lowercase() },
        )
    }
}
