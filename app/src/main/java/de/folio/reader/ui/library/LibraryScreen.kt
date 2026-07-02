package de.folio.reader.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val isConfigured by viewModel.isConfigured.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val currentFolder by viewModel.currentFolder.collectAsStateWithLifecycle()
    val browse by viewModel.browseContent.collectAsStateWithLifecycle()
    val reading by viewModel.readingBooks.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteBooks.collectAsStateWithLifecycle()

    // Zurück-Geste: erst Ordner hoch, dann normal.
    BackHandler(enabled = tab == LibraryTab.BROWSE && currentFolder.isNotEmpty()) {
        viewModel.navigateUp()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Folio", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(
                        onClick = { viewModel.syncNow() },
                        enabled = isConfigured && !syncStatus.running,
                    ) {
                        if (syncStatus.running) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.padding(2.dp),
                            )
                        } else {
                            Icon(Icons.Outlined.Sync, contentDescription = "Synchronisieren")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Einstellungen")
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
            if (syncStatus.running) SyncBanner(syncStatus)

            when {
                !isConfigured -> EmptyState(
                    icon = { Icon(Icons.Outlined.CloudOff, null) },
                    title = "Noch kein NAS verbunden",
                    message = "Hinterlege in den Einstellungen deine SMB-Zugangsdaten, um deine Bibliothek zu laden.",
                    actionLabel = "Zu den Einstellungen",
                    onAction = onOpenSettings,
                )

                books.isEmpty() -> EmptyState(
                    icon = { Icon(Icons.Outlined.MenuBook, null) },
                    title = "Bibliothek ist leer",
                    message = if (syncStatus.running) "Suche Bücher auf dem NAS …"
                    else "Tippe auf Synchronisieren, um Bücher vom NAS zu laden.",
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
                    when (tab) {
                        LibraryTab.BROWSE -> BrowseGrid(
                            content = browse,
                            currentFolder = currentFolder,
                            onOpenFolder = viewModel::openFolder,
                            onNavigateUp = { viewModel.navigateUp() },
                            onBookClick = { book ->
                                if (book.downloaded) onBookSelected(book.id)
                                else viewModel.downloadBook(book.id)
                            },
                            onToggleFavorite = viewModel::toggleFavorite,
                        )

                        LibraryTab.READING -> BookGrid(
                            books = reading,
                            emptyMessage = "Du liest gerade nichts. Such dir im Bibliothek-Tab etwas Schönes aus!",
                            onBookClick = { onBookSelected(it.id) },
                            onToggleFavorite = viewModel::toggleFavorite,
                        )

                        LibraryTab.FAVORITES -> BookGrid(
                            books = favorites,
                            emptyMessage = "Noch keine Favoriten. Tippe auf das Herz eines Covers, um es hier abzulegen.",
                            onBookClick = { book ->
                                if (book.downloaded) onBookSelected(book.id)
                                else viewModel.downloadBook(book.id)
                            },
                            onToggleFavorite = viewModel::toggleFavorite,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncBanner(status: SyncStatus) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = status.message ?: "Synchronisiere …",
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
        } else {
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
private fun BookCard(
    book: Book,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
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

            // Favoriten-Herz oben rechts
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(onClick = onToggleFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (book.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (book.favorite) "Aus Favoriten entfernen" else "Zu Favoriten",
                    tint = if (book.favorite) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }

            val fraction = book.readFraction
            when {
                book.isFinished -> {
                    // Fertig-Haken unten rechts
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Fertig gelesen",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f)),
                    )
                }

                fraction > 0.005f && book.downloaded -> {
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
