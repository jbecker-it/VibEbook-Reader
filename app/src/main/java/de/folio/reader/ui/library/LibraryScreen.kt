package de.folio.reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import de.folio.reader.domain.model.Book
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    selectedBookId: String?,
    onBookSelected: (String) -> Unit,
    onOpenSettings: () -> Unit,
    showsDetailPane: Boolean,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isConfigured by viewModel.isConfigured.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Folio", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(
                        onClick = { viewModel.syncNow() },
                        enabled = isConfigured && !isSyncing,
                    ) {
                        if (isSyncing) {
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
        when {
            !isConfigured -> EmptyState(
                icon = { Icon(Icons.Outlined.CloudOff, null) },
                title = "Noch kein NAS verbunden",
                message = "Hinterlege in den Einstellungen deine SMB-Zugangsdaten, um deine Bibliothek zu laden.",
                actionLabel = "Zu den Einstellungen",
                onAction = onOpenSettings,
                modifier = Modifier.padding(inner),
            )

            books.isEmpty() -> EmptyState(
                icon = { Icon(Icons.Outlined.MenuBook, null) },
                title = "Bibliothek ist leer",
                message = if (isSyncing) "Suche Bücher auf dem NAS …"
                else "Tippe auf Synchronisieren, um Bücher vom NAS zu laden.",
                actionLabel = if (isSyncing) null else "Jetzt synchronisieren",
                onAction = { viewModel.syncNow() },
                modifier = Modifier.padding(inner),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize().padding(inner),
            ) {
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        selected = showsDetailPane && book.id == selectedBookId,
                        onClick = {
                            if (book.downloaded) onBookSelected(book.id)
                            else viewModel.downloadBook(book.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    book: Book,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
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
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (selected) Modifier.border(2.dp, accent, RoundedCornerShape(10.dp))
                    else Modifier
                ),
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
        }

        val percent = book.readPercent()
        if (percent > 0f && book.downloaded) {
            LinearProgressIndicator(
                progress = { percent },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
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

private fun Book.readPercent(): Float {
    val p = progress ?: return 0f
    val count = spine.size.coerceAtLeast(1)
    return ((p.spineIndex + p.scrollFraction) / count).coerceIn(0f, 1f)
}

@Composable
private fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
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
