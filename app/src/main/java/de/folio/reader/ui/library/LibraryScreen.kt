package de.folio.reader.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import de.folio.reader.data.sync.SyncStatus
import de.folio.reader.domain.model.Book
import de.folio.reader.domain.model.isFinished
import de.folio.reader.domain.model.readFraction
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookSelected: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val workerStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val downloadTitle by viewModel.downloadTitle.collectAsStateWithLifecycle()
    val syncStatus = if (downloadTitle != null) SyncStatus(running = true, message = "Lade „$downloadTitle“ …") else workerStatus
    val isConfigured by viewModel.isConfigured.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val currentFolder by viewModel.currentFolder.collectAsStateWithLifecycle()
    val browse by viewModel.browseContent.collectAsStateWithLifecycle()
    val reading by viewModel.readingBooks.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteBooks.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val eInk by viewModel.eInkMode.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val offlineOnly by viewModel.offlineOnly.collectAsStateWithLifecycle()
    val readFilter by viewModel.readFilter.collectAsStateWithLifecycle()
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var filterVisible by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var syncDetails by remember { mutableStateOf(false) }
    if (syncDetails) androidx.compose.material3.AlertDialog(onDismissRequest = { syncDetails = false }, title = { Text("Synchronisierung") }, text = { Text(syncStatus.message ?: "Lokal gespeichert", Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { androidx.compose.material3.TextButton(onClick = { syncDetails = false }) { Text("Schließen") } })
    val openedBook by viewModel.openedBook.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) {
        notice?.let { value ->
            if (snackbar.showSnackbar(value, actionLabel = "Rückgängig", withDismissAction = true) == SnackbarResult.ActionPerformed) viewModel.undoLast()
            else viewModel.dismissNotice(value)
        }
    }
    actionError?.let { error ->
        AlertDialog(onDismissRequest = viewModel::dismissActionError, title = { Text("Aktion fehlgeschlagen") },
            text = { Text(error, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = viewModel::dismissActionError) { Text("Schließen") } })
    }
    if (filterVisible) {
        AlertDialog(onDismissRequest = { filterVisible = false }, title = { Text("Sortieren und filtern") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sortierung", fontWeight = FontWeight.SemiBold)
                listOf("Titel", "Zuletzt gelesen", "Zuletzt hinzugefügt").forEach { value ->
                    TextButton(onClick = { viewModel.selectSort(value) }, modifier = Modifier.fillMaxWidth()) { Text((if (sort == value) "✓ " else "") + value) }
                }
                HorizontalDivider()
                Text("Lesestatus", fontWeight = FontWeight.SemiBold)
                Row { listOf("Alle", "Ungelesen", "Gelesen").forEach { value ->
                    TextButton(onClick = { viewModel.selectReadFilter(value) }) { Text((if (readFilter == value) "✓ " else "") + value) }
                } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Nur heruntergeladene Bücher", Modifier.weight(1f))
                    Switch(checked = offlineOnly, onCheckedChange = { viewModel.toggleOfflineFilter() })
                }
            } }, confirmButton = { TextButton(onClick = { filterVisible = false }) { Text("Fertig") } },
            dismissButton = { TextButton(onClick = { viewModel.selectSort("Titel"); viewModel.selectReadFilter("Alle"); if (offlineOnly) viewModel.toggleOfflineFilter() }) { Text("Zurücksetzen") } })
    }
    androidx.compose.runtime.LaunchedEffect(openedBook) { openedBook?.let { viewModel.consumedOpenedBook(); onBookSelected(it) } }
    val gridStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importBook) }
    fun open(book: Book) { if (book.downloaded) onBookSelected(book.id) else viewModel.downloadBook(book.id) }
    var removal by remember { mutableStateOf<Book?>(null) }
    removal?.let { selected ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text("Lokale Buchdateien löschen?") },
            text = { Text("Die lokale Kopie von „${selected.title}“ wird entfernt. Lesestand und Nextcloud-Datei bleiben erhalten. Bei Cloud-Büchern kannst du sie später erneut laden.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.removeMissingBook(selected.id); removal = null }) { Text("Lokal löschen") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removal = null }) { Text("Behalten") } },
        )
    }

    // Zurück-Geste: erst Ordner hoch, dann normal.
    BackHandler(enabled = tab == LibraryTab.BROWSE && currentFolder.isNotEmpty()) {
        viewModel.navigateUp()
    }

    BackHandler(enabled = searchVisible) { searchVisible = false; viewModel.search("") }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Folio", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) viewModel.search("") }) { Icon(Icons.Outlined.Search, "Suchen") }
                    IconButton(onClick = { filterVisible = true }) { Icon(Icons.Outlined.FilterList, "Sortieren und filtern") }
                    IconButton(
                        onClick = { viewModel.syncNow() },
                        enabled = isConfigured && !syncStatus.running,
                    ) {
                        if (syncStatus.running && !eInk) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.padding(2.dp),
                            )
                        } else {
                            Icon(Icons.Outlined.Sync, contentDescription = "Synchronisieren")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Outlined.MoreVert, "Weitere Aktionen") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            reading.firstOrNull { it.downloaded }?.let { latest ->
                                DropdownMenuItem(text = { Text("Weiterlesen", maxLines = 1) }, onClick = { menuExpanded = false; onBookSelected(latest.id) })
                            }
                            DropdownMenuItem(text = { Text("Buch importieren") }, onClick = { menuExpanded = false; importLauncher.launch(arrayOf("application/epub+zip", "application/vnd.comicbook+zip", "application/zip", "application/x-cbz")) })
                            DropdownMenuItem(text = { Text("Synchronisierungsdetails") }, onClick = { menuExpanded = false; syncDetails = true })
                            DropdownMenuItem(text = { Text("Einstellungen") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { menuExpanded = false; onOpenSettings() })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            val statusMessage = syncStatus.message.orEmpty()
            if (syncStatus.running || (statusMessage.isNotBlank() && statusMessage != "Lokal gespeichert" && !statusMessage.startsWith("Synchronisiert um "))) {
                Box(Modifier.fillMaxWidth().clickable { syncDetails = true }) { SyncBanner(syncStatus, eInk) }
            }
            if (searchVisible) {
                OutlinedTextField(value = query, onValueChange = viewModel::search, singleLine = true,
                    label = { Text("Titel, Autor oder Ordner") },
                    trailingIcon = { IconButton(onClick = { searchVisible = false; viewModel.search("") }) { Icon(Icons.Outlined.Close, "Suche schließen") } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (sort != "Titel" || readFilter != "Alle" || offlineOnly) {
                TextButton(onClick = { filterVisible = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(listOfNotNull(sort.takeIf { it != "Titel" }, readFilter.takeIf { it != "Alle" }, "Offline".takeIf { offlineOnly }).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            when {
                !isConfigured && books.isEmpty() -> EmptyState(
                    icon = { Icon(Icons.Outlined.CloudOff, null) },
                    title = "Noch kein Nextcloud verbunden",
                    message = "Hinterlege in den Einstellungen deine Nextcloud-Zugangsdaten, um deine Bibliothek zu laden.",
                    actionLabel = "Zu den Einstellungen",
                    onAction = onOpenSettings,
                )

                books.isEmpty() -> EmptyState(
                    icon = { Icon(Icons.Outlined.MenuBook, null) },
                    title = "Bibliothek ist leer",
                    message = if (syncStatus.running) "Suche Bücher auf dem Nextcloud …"
                    else "Tippe auf Synchronisieren, um Bücher vom Nextcloud zu laden.",
                    actionLabel = if (syncStatus.running) null else "Jetzt synchronisieren",
                    onAction = { viewModel.syncNow() },
                )

                else -> {
                    TabRow(selectedTabIndex = tab.ordinal) {
                        LibraryTab.entries.forEach { t ->
                            Tab(
                                selected = tab == t,
                                onClick = { viewModel.selectTab(t) },
                                text = { Text(t.label) },
                            )
                        }
                    }
                    gridStates.SaveableStateProvider(tab.name + ":" + currentFolder + ":" + query.isNotBlank()) { when (tab) {
                        LibraryTab.BROWSE -> BrowseGrid(
                            content = browse,
                            currentFolder = currentFolder,
                            onOpenFolder = viewModel::openFolder,
                            onNavigateUp = { viewModel.navigateUp() },
                            onBookClick = ::open,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onSetFinished = viewModel::setFinished,
                            onRemove = { removal = it },
                        )

                        LibraryTab.READING -> BookGrid(
                            books = reading,
                            emptyMessage = "Du liest gerade nichts. Such dir im Bibliothek-Tab etwas Schönes aus!",
                            onBookClick = ::open,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onSetFinished = viewModel::setFinished,
                            onRemove = { removal = it },
                        )

                        LibraryTab.FAVORITES -> BookGrid(
                            books = favorites,
                            emptyMessage = "Noch keine Favoriten. Tippe auf das Herz eines Covers, um es hier abzulegen.",
                            onBookClick = ::open,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onSetFinished = viewModel::setFinished,
                            onRemove = { removal = it },
                        )
                    } }
                }
            }
        }
    }
}

@Composable
private fun SyncBanner(status: SyncStatus, eInk: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = status.message ?: if (status.running) "Synchronisiere …" else "Lokal gespeichert",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val fraction = status.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        } else if (!eInk && status.running) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun BrowseGrid(
    content: BrowseContent,
    currentFolder: String,
    onOpenFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onBookClick: (Book) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSetFinished: (String, Boolean) -> Unit,
    onRemove: (Book) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (currentFolder.isNotEmpty()) {
            item(key = "breadcrumb", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onNavigateUp() },
                ) {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Ordner hoch")
                    }
                    Text(
                        text = "/$currentFolder",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        items(content.folders, key = { "dir:${it.path}" }, span = { GridItemSpan(maxLineSpan) }) { folder ->
            FolderRow(folder = folder, onClick = { onOpenFolder(folder.path) })
        }

        items(content.books, key = { it.id }) { book ->
            BookCard(
                book = book,
                onClick = { onBookClick(book) },
                onToggleFavorite = { onToggleFavorite(book.id) },
                onToggleFinished = { onSetFinished(book.id, !book.isFinished) },
                onRemove = { onRemove(book) },
            )
        }
    }
}

@Composable
private fun BookGrid(
    books: List<Book>,
    emptyMessage: String,
    onBookClick: (Book) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSetFinished: (String, Boolean) -> Unit,
    onRemove: (Book) -> Unit,
) {
    if (books.isEmpty()) {
        EmptyState(
            icon = { Icon(Icons.Outlined.MenuBook, null) },
            title = "Hier ist noch nichts",
            message = emptyMessage,
            actionLabel = null,
            onAction = {},
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(books, key = { it.id }) { book ->
            BookCard(
                book = book,
                onClick = { onBookClick(book) },
                onToggleFavorite = { onToggleFavorite(book.id) },
                onToggleFinished = { onSetFinished(book.id, !book.isFinished) },
                onRemove = { onRemove(book) },
            )
        }
    }
}

@Composable
private fun FolderRow(folder: FolderItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${folder.bookCount} ${if (folder.bookCount == 1) "Buch" else "Bücher"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun BookCard(
    book: Book,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleFinished: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    if (details) {
        AlertDialog(onDismissRequest = { details = false }, title = { Text(book.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(book.relativePath)
                Text("Datei: ${book.sizeBytes / (1024 * 1024)} MB" + if (book.downloaded) " · offline verfügbar" else " · nicht heruntergeladen")
                if (book.localOnly) Text("Lokaler Import · ohne Cloud-Abgleich")
                if (book.missingRemotely) Text("Nur lokal · nicht in Nextcloud gefunden")
                if (book.downloadError.isNotBlank()) { Text("Downloadproblem", fontWeight = FontWeight.SemiBold); Text(book.downloadError) }
            } }, confirmButton = { TextButton(onClick = { details = false }) { Text("Schließen") } },
            dismissButton = { if (book.downloadError.isNotBlank()) TextButton(onClick = { details = false; onClick() }) { Text(if (book.downloaded) "Öffnen" else "Erneut laden") } })
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val cover = book.coverPath?.let { File(it) }?.takeIf { it.exists() }
            if (cover != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(cover).build(),
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!book.downloaded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.CloudOff,
                        contentDescription = "Nicht heruntergeladen",
                        tint = Color.White,
                    )
                }
            }

            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                Surface(shape = CircleShape, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface)) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreVert, "Buchaktionen") }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Buchdetails") }, onClick = { menu = false; details = true })
                    if (book.downloadError.isNotBlank()) DropdownMenuItem(text = { Text("Downloadproblem") }, onClick = { menu = false; details = true })
                    if (book.downloaded) DropdownMenuItem(text = { Text("Lokale Kopie entfernen") }, onClick = { menu = false; onRemove() })
                }
            }
            if (book.downloadError.isNotBlank()) {
                Surface(modifier = Modifier.align(Alignment.BottomStart).padding(6.dp), shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface)) {
                    IconButton(onClick = { details = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ErrorOutline, "Downloadproblem anzeigen") }
                }
            }

            // Favoriten-Herz oben rechts
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable(onClick = onToggleFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (book.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (book.favorite) "Aus Favoriten entfernen" else "Zu Favoriten",
                    tint = if (book.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }

            val fraction = book.readFraction
            if (!book.isFinished && fraction > 0.005f && book.downloaded) {
                // Fortschrittsbalken am unteren Cover-Rand
                LinearProgressIndicator(
                    progress = { fraction },
                    trackColor = Color.Black.copy(alpha = 0.35f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(5.dp),
                )
            }

            // Gelesen-Schalter direkt auf dem Cover, passend zum Favoriten-Herz.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .toggleable(value = book.isFinished, role = Role.Checkbox,
                        onValueChange = { onToggleFinished() })
                    .semantics { stateDescription = if (book.isFinished) "Gelesen" else "Ungelesen" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (book.isFinished) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (book.isFinished) "Als ungelesen markieren" else "Als gelesen markieren",
                    tint = if (book.isFinished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Text(
            text = book.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (book.author.isNotBlank()) {
            Text(
                text = book.author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (book.localOnly || book.missingRemotely) Text(if (book.localOnly) "Lokaler Import" else "Nur lokal", style = MaterialTheme.typography.bodySmall, maxLines = 1)

    }
}

@Composable
private fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (actionLabel != null) {
            androidx.compose.material3.TextButton(
                onClick = onAction,
                modifier = Modifier.padding(top = 16.dp),
            ) { Text(actionLabel) }
        }
    }
}
