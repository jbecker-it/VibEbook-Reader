package de.folio.reader.ui.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.folio.reader.domain.model.PageLayoutMode
import de.folio.reader.domain.model.ReaderPreferences
import android.view.KeyEvent
import java.io.File
import kotlin.math.roundToInt

/**
 * Paginierter Vollbild-Reader (CSS-Spalten, kein Scrollen). Blättern per
 * Wischgeste oder Tippen links/rechts, nahtlos über Kapitelgrenzen. Tippen in
 * der Mitte blendet das Menü ein/aus.
 *
 * Geräteübergreifende Position: primär über einen wortgenauen Zeichen-Anker
 * (Offset im Kapiteltext – identische EPUB-Datei ⇒ identische Offsets auf
 * allen Geräten), sekundär über den Kapitel-Anteil 0..1 als Fallback.
 */
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    LaunchedEffect(bookId) { viewModel.load(bookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pageLayout by viewModel.pageLayout.collectAsStateWithLifecycle()
    val eInk by viewModel.eInkMode.collectAsStateWithLifecycle()
    val readerPreferences by viewModel.readerPreferences.collectAsStateWithLifecycle()
    val readerView = LocalView.current
    DisposableEffect(readerPreferences.lockOrientation, readerPreferences.keepScreenOn) {
        val activity = readerView.context.findActivity()
        val oldOrientation = activity?.requestedOrientation
        val oldKeepScreenOn = readerView.keepScreenOn
        readerView.keepScreenOn = readerPreferences.keepScreenOn
        if (readerPreferences.lockOrientation) activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose {
            readerView.keepScreenOn = oldKeepScreenOn
            if (oldOrientation != null) activity?.requestedOrientation = oldOrientation
        }
    }

    var menuVisible by rememberSaveable { mutableStateOf(false) }
    var typographyVisible by remember { mutableStateOf(false) }
    var bookmarkTitle by remember { mutableStateOf("") }
    var navigationVisible by remember { mutableStateOf(false) }
    var externalLink by remember { mutableStateOf<String?>(null) }
    val linkContext = androidx.compose.ui.platform.LocalContext.current
    externalLink?.let { url -> androidx.compose.material3.AlertDialog(onDismissRequest = { externalLink = null },
        title = { Text("Link im Browser öffnen?") }, text = { Text(Uri.parse(url).host.orEmpty()) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { runCatching { linkContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }; externalLink = null }) { Text("Öffnen") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { externalLink = null }) { Text("Abbrechen") } }) }
    state.remotePosition?.let {
        androidx.compose.material3.AlertDialog(onDismissRequest = viewModel::dismissRemote,
            title = { Text("Auf anderem Gerät weitergelesen") }, text = { Text("Ein neuerer Lesestand ist verfügbar. Übernehmen?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = viewModel::acceptRemote) { Text("Übernehmen") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = viewModel::dismissRemote) { Text("Hier bleiben") } })
    }
    if (state.error != null || state.editionChanged) {
        androidx.compose.material3.AlertDialog(onDismissRequest = viewModel::clearError, title = { Text(if (state.error != null) "Hinweis" else "Neue Buchfassung") },
            text = { Text(state.error ?: "Die Datei wurde ersetzt. Der Stand wird über Kapitel und Prozent wiederhergestellt; bitte die genaue Stelle prüfen.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { viewModel.saveNow(); viewModel.clearError() }) { Text("OK") } })
    }
    if (navigationVisible) {
        val toc = remember(state.book?.tocJson) { org.json.JSONArray(state.book?.tocJson ?: "[]") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { navigationVisible = false }, title = { Text("Inhalt und Lesezeichen") },
            text = { androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .5f).dp)) {
                item { androidx.compose.material3.OutlinedTextField(value = bookmarkTitle, onValueChange = { bookmarkTitle = it.take(120) }, singleLine = true, label = { Text("Lesezeichenname (optional)") }) }
                item { androidx.compose.material3.TextButton(onClick = { viewModel.addBookmark(bookmarkTitle); bookmarkTitle = "" }, enabled = state.linkedDocument == null) { Text("Hier ein Lesezeichen setzen") } }
                item { androidx.compose.material3.TextButton(onClick = viewModel::setFinished) { Text(if (state.book?.progress?.finished == true) "Als ungelesen markieren" else "Als gelesen markieren") } }
                item { if (state.returnPosition != null) androidx.compose.material3.TextButton(onClick = { viewModel.returnToPosition(); navigationVisible = false }) { Text("Zur vorherigen Lesestelle") } }
                item { Text("Lesezeichen") }
                items(state.bookmarks.size) { index -> val bookmark = state.bookmarks[index]; val p = bookmark.position; Row {
                    androidx.compose.material3.TextButton(onClick = { viewModel.restore(p); navigationVisible = false }) { Text(bookmark.title) }
                    androidx.compose.material3.TextButton(onClick = { viewModel.removeBookmark(index) }) { Text("×") }
                } }
                item { Text("Letzte Positionen") }
                items(minOf(state.history.size, 10)) { index -> val p = state.history[index]; androidx.compose.material3.TextButton(onClick = { viewModel.restore(p); navigationVisible = false }) { Text("Kapitel ${p.spineIndex + 1} · ${(p.scrollFraction * 100).roundToInt()} %") } }
                item { Text("Inhaltsverzeichnis") }
                if (toc.length() == 0) items(state.book?.spine?.size ?: 0) { i -> androidx.compose.material3.TextButton(onClick = { viewModel.goToChapter(i, rememberReturn = true); navigationVisible = false }) { Text("Kapitel ${i + 1}") } }
                else items(toc.length()) { i -> val item = toc.getJSONObject(i)
                    androidx.compose.material3.TextButton(onClick = { viewModel.openLink(item.getString("path"), item.optString("fragment")); navigationVisible = false }) { Text("  ".repeat(item.optInt("depth").coerceIn(0, 8)) + item.getString("label")) }
                }
            } }, confirmButton = { androidx.compose.material3.TextButton(onClick = { navigationVisible = false }) { Text("Schließen") } })
    }
    if (typographyVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { typographyVisible = false },
            title = { Text("Schrift und Darstellung") },
            text = {
                Column {
                    Text("Darstellung für dieses Buch")
                    de.folio.reader.domain.model.BookLayoutMode.entries.forEach { mode ->
                        androidx.compose.material3.TextButton(onClick = { viewModel.setLayoutMode(mode) }) {
                            Text((if (state.layoutMode == mode) "✓ " else "") + mode.label)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.TextButton(onClick = { viewModel.setReaderPreferences(readerPreferences.copy(fontSize = readerPreferences.fontSize - 2)) }) { Text("A−") }
                        Text("${readerPreferences.fontSize}")
                        androidx.compose.material3.TextButton(onClick = { viewModel.setReaderPreferences(readerPreferences.copy(fontSize = readerPreferences.fontSize + 2)) }) { Text("A+") }
                    }
                    androidx.compose.material3.TextButton(onClick = { viewModel.setReaderPreferences(readerPreferences.copy(sansSerif = !readerPreferences.sansSerif)) }) { Text(if (readerPreferences.sansSerif) "Schrift: Sans-Serif" else "Schrift: Serif") }
                    Text("Weitere Lese- und Tastenoptionen in den Einstellungen.")
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { typographyVisible = false }) { Text("Fertig") } },
        )
    }

    BackHandler { if (menuVisible) menuVisible = false else onBack() }
    DisposableEffect(bookId) { onDispose { viewModel.saveNow() } }

    // Auch bei ON_STOP speichern: App in den Hintergrund, Display aus oder
    // Foldable zugeklappt – nicht nur beim Navigieren zurück.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.saveNow()
            if (event == Lifecycle.Event.ON_START) viewModel.refreshRemote()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    HideSystemBars(hidden = !menuVisible)

    val colorScheme = MaterialTheme.colorScheme
    val bgHex = remember(colorScheme.background) { colorScheme.background.toCssHex() }
    val fgHex = remember(colorScheme.onBackground) { colorScheme.onBackground.toCssHex() }
    val linkHex = remember(colorScheme.primary) { colorScheme.primary.toCssHex() }
    val forceColors = eInk || bgHex != "#FFFFFF"

    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    val twoPage = when (pageLayout) {
        PageLayoutMode.DOUBLE -> true
        PageLayoutMode.SINGLE -> false
        PageLayoutMode.AUTO -> windowWidthDp >= 600
    }

    // E-Ink: Menü ohne Animationen ein-/ausblenden.
    val enterTop = if (eInk) EnterTransition.None else slideInVertically { -it } + fadeIn()
    val exitTop = if (eInk) ExitTransition.None else slideOutVertically { -it } + fadeOut()
    val enterBottom = if (eInk) EnterTransition.None else slideInVertically { it } + fadeIn()
    val exitBottom = if (eInk) ExitTransition.None else slideOutVertically { it } + fadeOut()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background),
    ) {
        val book = state.book
        when {
            state.loading -> if (eInk) Text("Buch öffnen …", Modifier.align(Alignment.Center)) else CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

            book == null || book.spine.isEmpty() -> Text(
                text = "Dieses Buch konnte nicht geöffnet werden.",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = colorScheme.onSurfaceVariant,
            )

            else -> androidx.compose.runtime.key(book.id, state.layoutMode, state.restoreToken) { EpubWebView(
                filePath = state.linkedDocument ?: book.spine[state.spineIndex.coerceIn(0, book.spine.lastIndex)],
                bookRoot = viewModel.bookRoot(), initialZoom = state.zoom, onZoom = viewModel::setZoom,
                fragment = state.fragment, onLink = viewModel::openLink, onExternalLink = { externalLink = it },
                fixedLayout = if (state.linkedDocument != null) false else when (state.layoutMode) {
                    de.folio.reader.domain.model.BookLayoutMode.ORIGINAL -> true
                    de.folio.reader.domain.model.BookLayoutMode.REFLOWABLE -> false
                    else -> state.layouts[book.spine[state.spineIndex.coerceIn(0, book.spine.lastIndex)]]
                },
                restoreFraction = state.restoreScrollFraction,
                restoreCharOffset = state.restoreCharOffset,
                showResumeMarker = state.showResumeMarker,
                onDismissResumeMarker = viewModel::dismissResumeMarker,
                twoPage = twoPage,
                smoothTurns = !eInk,
                backgroundHex = bgHex,
                textHex = fgHex,
                linkHex = linkHex,
                forceColors = forceColors,
                preferences = readerPreferences,
                menuVisible = menuVisible,
                onPosition = viewModel::onPosition,
                onToggleMenu = { menuVisible = !menuVisible },
                onNextChapter = viewModel::nextChapter,
                onPrevChapter = viewModel::previousChapter,
            ) }
        }

        AnimatedVisibility(
            visible = menuVisible && book != null,
            enter = enterTop,
            exit = exitTop,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopOverlay(
                title = book?.title ?: "",
                favorite = state.favorite,
                onBack = onBack,
                onToggleFavorite = viewModel::toggleFavorite,
                onTypography = { typographyVisible = true },
                onNavigation = { viewModel.refreshNavigation(); navigationVisible = true },
            )
        }

        AnimatedVisibility(
            visible = menuVisible && book != null && book.spine.isNotEmpty(),
            enter = enterBottom,
            exit = exitBottom,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomOverlay(
                spineIndex = state.spineIndex,
                spineCount = book?.spine?.size ?: 1,
                chapterFraction = state.chapterFraction,
                onPrev = viewModel::previousChapter,
                onNext = viewModel::nextChapter,
                onSeekChapter = { viewModel.goToChapter(it, rememberReturn = true) },
            )
        }
    }
}

@Composable
private fun TopOverlay(
    title: String,
    favorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTypography: () -> Unit,
    onNavigation: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Zurück")
            }
            androidx.compose.material3.TextButton(onClick = onTypography) { Text("Aa") }
            androidx.compose.material3.TextButton(onClick = onNavigation) { Text("Inhalt") }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (favorite) "Aus Favoriten entfernen" else "Zu Favoriten",
                    tint = if (favorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun BottomOverlay(
    spineIndex: Int,
    spineCount: Int,
    chapterFraction: Float,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeekChapter: (Int) -> Unit,
) {
    val percent = (((spineIndex + chapterFraction) / spineCount.coerceAtLeast(1)) * 100f)
        .roundToInt().coerceIn(0, 100)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onPrev, enabled = spineIndex > 0) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "Vorheriges Kapitel")
                }
                Text(
                    text = "Kapitel ${spineIndex + 1} / $spineCount  ·  $percent %",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onNext, enabled = spineIndex < spineCount - 1) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "Nächstes Kapitel")
                }
            }
            if (spineCount > 1) {
                var sliderPosition by remember(spineIndex) {
                    mutableFloatStateOf(spineIndex.toFloat())
                }
                Slider(
                    value = sliderPosition,
                    onValueChange = { sliderPosition = it },
                    onValueChangeFinished = { onSeekChapter(sliderPosition.roundToInt()) },
                    valueRange = 0f..(spineCount - 1).toFloat(),
                    steps = (spineCount - 2).coerceAtLeast(0),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// WebView mit Spalten-Pagination und Zeichen-Ankern
// ---------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EpubWebView(
    filePath: String,
    bookRoot: File?, initialZoom: Float, onZoom: (Float) -> Unit, fragment: String, onLink: (String, String) -> Unit, onExternalLink: (String) -> Unit,
    fixedLayout: Boolean?,
    restoreFraction: Float,
    restoreCharOffset: Int,
    showResumeMarker: Boolean,
    onDismissResumeMarker: () -> Unit,
    twoPage: Boolean,
    smoothTurns: Boolean,
    backgroundHex: String,
    textHex: String,
    linkHex: String,
    forceColors: Boolean,
    preferences: ReaderPreferences,
    menuVisible: Boolean,
    onPosition: (Float, Int, Boolean) -> Unit,
    onToggleMenu: () -> Unit,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
) {
    val bridge = remember { ReaderBridge(Handler(Looper.getMainLooper())).apply { zoomValue = initialZoom } }
    bridge.zoomListener = onZoom
    bridge.positionListener = onPosition
    bridge.dismissResumeMarkerListener = onDismissResumeMarker
    bridge.documentUrl = Uri.fromFile(File(filePath)).toString()
    bridge.toggleMenuListener = onToggleMenu
    bridge.nextChapterListener = onNextChapter
    bridge.prevChapterListener = onPrevChapter

    val webViewRef = remember { arrayOfNulls<WebView>(1) }
    DisposableEffect(Unit) {
        onDispose {
            bridge.documentUrl = null
            webViewRef[0]?.apply { stopLoading(); removeJavascriptInterface("AndroidReader"); destroy() }
            webViewRef[0] = null
        }
    }
    val lastLoaded = remember { arrayOfNulls<String>(1) }
    val injection = remember { arrayOf("") }
    injection[0] = buildInjection(
        restoreFraction, restoreCharOffset, twoPage, smoothTurns,
        backgroundHex, textHex, linkHex, forceColors,
        preferences,
        fixedLayout, fragment, showResumeMarker,
    )

    // Layout-/Themewechsel ohne Neuladen anwenden: erneut injizieren – das
    // Skript repaginiert und hält die Position über den Zeichen-Anker.
    LaunchedEffect(twoPage, smoothTurns, backgroundHex, textHex, forceColors, preferences) {
        webViewRef[0]?.evaluateJavascript(injection[0], null)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.blockNetworkLoads = true
                settings.builtInZoomControls = false
                settings.textZoom = 100
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                installReaderBridge(bridge)
                setBackgroundColor(android.graphics.Color.parseColor(backgroundHex))
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                        val uri = request.url
                        if (request.hasGesture() && uri.scheme in setOf("https", "http")) { onExternalLink(uri.toString()); return true }
                        val root = bookRoot ?: return true
                        if (uri.scheme == "file") runCatching {
                            val target = File(uri.path.orEmpty()).canonicalFile
                            if (target.path.startsWith(root.canonicalPath + File.separator)) onLink(target.path, uri.fragment.orEmpty())
                        }
                        return true
                    }
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        val root = bookRoot?.canonicalPath?.plus(File.separator)
                        val file = if (request.url.scheme == "file") runCatching { File(request.url.path.orEmpty()).canonicalFile }.getOrNull() else null
                        if (root == null || file == null || !file.path.startsWith(root)) return android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                        if (request.isForMainFrame || file.extension.lowercase() in setOf("html", "xhtml", "htm", "svg")) {
                            return try { require(file.length() <= 16 * 1024 * 1024) { "Kapitel zu groß." }; android.webkit.WebResourceResponse(if (file.extension.equals("svg", true) && !request.isForMainFrame) "image/svg+xml" else "text/html", "UTF-8", de.folio.reader.data.epub.EpubSafety.sanitize(file.readText(), file.extension.equals("svg", true)).byteInputStream()) }
                            catch (_: Exception) { android.webkit.WebResourceResponse("text/plain", "UTF-8", "Kapitel konnte nicht geladen werden.".byteInputStream()) }
                        }
                        return null
                    }
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(injection[0], null)
                    }
                }
                webViewRef[0] = this
            }
        },
        update = { web ->
            web.setOnKeyListener { _, keyCode, event ->
                val command = ReaderKeys.command(keyCode, menuVisible, preferences.volumeKeys)
                if (command != null) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) web.evaluateJavascript("window.__folio && window.__folio.$command();", null)
                    true
                } else if (keyCode == KeyEvent.KEYCODE_MENU || (!menuVisible && keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
                    if (event.action == KeyEvent.ACTION_UP) onToggleMenu()
                    true
                } else false
            }
            web.keepScreenOn = preferences.keepScreenOn
            web.setBackgroundColor(android.graphics.Color.parseColor(backgroundHex))
            if (lastLoaded[0] != filePath) {
                lastLoaded[0] = filePath
                web.loadUrl(Uri.fromFile(File(filePath)).toString())
                web.requestFocus()
            }
        },
    )
}

// A concrete parameter avoids lint treating Compose remember's inferred bridge as generic T.
private fun WebView.installReaderBridge(bridge: ReaderBridge) {
    addJavascriptInterface(bridge, "AndroidReader")
}

/** Brücke vom WebView-JavaScript nach Kotlin (JS-Thread → Main-Thread). */
private class ReaderBridge(private val handler: Handler) {
    var documentUrl: String? = null
    private fun isCurrentDocument(sourceUrl: String) =
        Uri.parse(sourceUrl).buildUpon().fragment(null).build().toString() == documentUrl
    var positionListener: (Float, Int, Boolean) -> Unit = { _, _, _ -> }
    var dismissResumeMarkerListener: () -> Unit = {}
    @Volatile var zoomValue = 1f
    var zoomListener: (Float) -> Unit = {}

    @JavascriptInterface
    fun getZoom(): Float = zoomValue

    @JavascriptInterface
    fun setZoom(value: Float) { if (value.isFinite()) { zoomValue = value.coerceIn(1f, 3f); handler.post { zoomListener(zoomValue) } } }
    var toggleMenuListener: () -> Unit = {}
    var nextChapterListener: () -> Unit = {}
    var prevChapterListener: () -> Unit = {}

    @JavascriptInterface
    fun onPosition(fraction: Float, charOffset: Int, fromUser: Boolean, sourceUrl: String) {
        if (!fraction.isFinite()) return
        val f = fraction.coerceIn(0f, 1f)
        handler.post { if (isCurrentDocument(sourceUrl)) positionListener(f, charOffset, fromUser) }
    }

    @JavascriptInterface
    fun onResumeMarkerDismissed(sourceUrl: String) {
        handler.post { if (isCurrentDocument(sourceUrl)) dismissResumeMarkerListener() }
    }

    @JavascriptInterface
    fun onToggleMenu() {
        handler.post { toggleMenuListener() }
    }

    @JavascriptInterface
    fun onNextChapter(sourceUrl: String) {
        handler.post { if (isCurrentDocument(sourceUrl)) nextChapterListener() }
    }

    @JavascriptInterface
    fun onPrevChapter(sourceUrl: String) {
        handler.post { if (isCurrentDocument(sourceUrl)) prevChapterListener() }
    }
}

/**
 * Injiziertes JS: Spalten-Pagination, Gesten, Positions-Meldungen.
 *
 * Wortgenaue Anker: Alle Textknoten des Kapitels werden einmal pro Layout
 * indiziert (Knoten + kumulierter Zeichen-Start). Für jede Seite wird per
 * Binärsuche der Zeichen-Offset des ersten sichtbaren Worts bestimmt und an
 * Kotlin gemeldet; beim Wiederherstellen wird umgekehrt die Seite gesucht, die
 * den gespeicherten Offset enthält. Da alle Geräte dieselbe EPUB-Datei rendern,
 * sind die Offsets geräteunabhängig – unabhängig von Displaygröße und Layout.
 * Fallback bleibt der Kapitel-Anteil 0..1 (alte Fortschrittsdateien).
 */
internal fun buildInjection(
    restoreFraction: Float,
    restoreCharOffset: Int,
    twoPage: Boolean,
    smoothTurns: Boolean,
    backgroundHex: String,
    textHex: String,
    linkHex: String,
    forceColors: Boolean,
    preferences: ReaderPreferences,
    fixedLayout: Boolean? = null,
    restoreFragment: String = "",
    showResumeMarker: Boolean = false,
): String {
    val frac = restoreFraction.coerceIn(0f, 1f).toString()
    val colorRules = if (forceColors) {
        "html,body{background:$backgroundHex !important;color:$textHex !important;}" +
            "p,div,span,li,td,th,h1,h2,h3,h4,h5,h6,blockquote,figcaption" +
            "{color:$textHex !important;background-color:transparent !important;}" +
            "a{color:$linkHex !important;}"
    } else {
        "html,body{background:$backgroundHex;color:$textHex;}a{color:$linkHex;}"
    }
    val colorScheme = if (backgroundHex == "#FFFFFF") "light" else "dark"

    return """
    (function() {
        var F = window.__folio = window.__folio || {};
        var firstRun = !F.ready;

        F.twoPage = $twoPage;
        F.smoothTurns = $smoothTurns;
        F.leftHanded = ${preferences.leftHanded};
        F.zone = ${if (preferences.wideTapZones) "0.4" else "0.3"};
        F.colorRules = ${jsString(colorRules)};
        F.colorScheme = "$colorScheme";
        F.declaredFixed = ${fixedLayout?.toString() ?: "null"};
        if (firstRun) {
            F.fraction = $frac;          // Kapitel-Anteil 0..1 (Fallback)
            F.fragment = ${jsString(restoreFragment)};
            F.anchor = $restoreCharOffset; // Zeichen-Offset, -1 = keiner
            F.resumeMarker = $showResumeMarker;
            F.resumeOffset = F.anchor;
            F.screen = 0;
            F.screens = 1;
            F.step = 1;
            F.PH = 24;
            F.zoom = window.AndroidReader && AndroidReader.getZoom ? AndroidReader.getZoom() : 1;
            F.panX = 0; F.panY = 0;
        }

        if (firstRun) {
            // Publisher transforms can shrink a large artwork/text canvas together.
            // Capture once, before our stylesheet; never compound our own screen transform.
            var authored = getComputedStyle(document.body);
            var origin = authored.transformOrigin.split(' ');
            F.authorTransform = authored.transform === 'none' ? '' :
                ' translate(' + origin[0] + ',' + origin[1] + ') ' + authored.transform +
                ' translate(' + (-parseFloat(origin[0])) + 'px,' + (-parseFloat(origin[1])) + 'px)';
            var m = document.querySelector('meta[name=viewport]');
            var viewport = m ? m.content : '';
            var width = viewport.match(/(?:^|[,;\s])width\s*=\s*([\d.]+)/i);
            var height = viewport.match(/(?:^|[,;\s])height\s*=\s*([\d.]+)/i);
            F.pageWidth = width ? Number(width[1]) : 0;
            F.pageHeight = height ? Number(height[1]) : 0;
            // Older PDF-to-EPUB exports often omit rendition/viewport metadata.
            // Require a sized canvas containing artwork AND positioned text, not just an illustration.
            var canvas = null;
            var candidates = Array.from(document.querySelectorAll('div,section')).concat([document.body]);
            for (var candidate of candidates) {
                if (!candidate || !candidate.querySelector('img,svg')) continue;
                var cs = getComputedStyle(candidate);
                var positionedText = Array.from(candidate.querySelectorAll('p,span,div')).some(function(node) {
                    return node.textContent.trim() && getComputedStyle(node).position === 'absolute';
                });
                var cw = Math.max(parseFloat(cs.width), candidate.scrollWidth);
                var ch = Math.max(parseFloat(cs.height), candidate.scrollHeight);
                if (positionedText && cw > 200 && ch > 200 && cs.width.endsWith('px') && cs.height.endsWith('px')) {
                    canvas = {width:cw,height:ch};
                    break;
                }
            }
            F.fixed = F.declaredFixed === true || (F.declaredFixed !== false &&
                ((F.pageWidth > 0 && F.pageHeight > 0) || canvas !== null));
            if (F.fixed && !(F.pageWidth > 0 && F.pageHeight > 0) && canvas) {
                F.pageWidth = canvas.width; F.pageHeight = canvas.height;
            }
            if (!m) m = document.createElement('meta');
            m.name = 'viewport';
            m.content = 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no';
            document.head.appendChild(m);
        }

        // ---- Textknoten-Index für Zeichen-Anker --------------------------
        F.buildIndex = function() {
            F.nodes = [];
            F.textLen = 0;
            var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
            var n;
            while ((n = w.nextNode())) {
                if (n.length === 0) continue;
                F.nodes.push({ node: n, start: F.textLen });
                F.textLen += n.length;
            }
        };

        F.rangeAtOffset = function(off) {
            if (!F.nodes || !F.nodes.length) return null;
            off = Math.max(0, Math.min(F.textLen - 1, off));
            var lo = 0, hi = F.nodes.length - 1;
            while (lo < hi) {
                var mid = (lo + hi + 1) >> 1;
                if (F.nodes[mid].start <= off) lo = mid; else hi = mid - 1;
            }
            var e = F.nodes[lo];
            var local = off - e.start;
            var r = document.createRange();
            try {
                r.setStart(e.node, local);
                r.setEnd(e.node, Math.min(e.node.length, local + 1));
            } catch (err) { return null; }
            return r;
        };

        F.pageOfRange = function(r) {
            var rect = r.getBoundingClientRect();
            if (rect.width === 0 && rect.height === 0 && rect.left === 0 && rect.top === 0) return -1;
            var docX = rect.left + window.scrollX; // dokumentabsolut, scroll-unabhängig
            return Math.max(0, Math.min(F.screens - 1, Math.floor((docX - F.PH + 2) / F.step)));
        };

        F.pageOfOffset = function(off) {
            for (var probe = 0; probe < 40; probe++) {
                var r = F.rangeAtOffset(off + probe);
                if (!r) return -1;
                var p = F.pageOfRange(r);
                if (p >= 0) return p;
            }
            return -1;
        };

        /** Zeichen-Offset des ersten sichtbaren Worts auf Seite i (Binärsuche). */
        F.offsetForPage = function(page) {
            if (!F.textLen) return -1;
            var lo = 0, hi = F.textLen - 1, ans = -1;
            while (lo <= hi) {
                var mid = (lo + hi) >> 1;
                var p = F.pageOfOffset(mid);
                if (p < 0) { lo = mid + 1; continue; }
                if (p >= page) { ans = mid; hi = mid - 1; } else { lo = mid + 1; }
            }
            return ans;
        };

        // A separate, noninteractive overlay leaves text nodes, pagination and selection intact.
        F.clearResumeMarker = function() {
            var wasVisible = F.resumeMarker;
            F.resumeMarker = false; F.resumeMarkerKey = null;
            var overlay = document.getElementById('folio-resume-marker');
            if (overlay) overlay.remove();
            if (wasVisible && window.AndroidReader && AndroidReader.onResumeMarkerDismissed)
                AndroidReader.onResumeMarkerDismissed(location.href);
        };
        F.rangeBetweenOffsets = function(start, end) {
            if (start < 0 || end > F.textLen || end <= start) return null;
            var range = F.rangeAtOffset(start), last = F.rangeAtOffset(end - 1);
            if (!range || !last) return null;
            range.setEnd(last.endContainer, last.endOffset);
            return range;
        };
        F.resumeWordRange = function() {
            var offset = F.resumeOffset;
            if (offset < 0 || offset >= F.textLen) return null;
            var start = Math.max(0, offset - 128), end = Math.min(F.textLen, offset + 256);
            var snippet = F.rangeBetweenOffsets(start, end);
            if (!snippet) return null;
            var text = snippet.toString(), words = [];
            if (window.Intl && Intl.Segmenter) {
                words = Array.from(new Intl.Segmenter(undefined, {granularity:'word'}).segment(text))
                    .filter(function(part) { return part.isWordLike; });
            } else {
                var pattern;
                try { pattern = new RegExp('[\\p{L}\\p{N}\\p{M}]+(?:[’\x27-][\\p{L}\\p{N}\\p{M}]+)*', 'gu'); }
                catch (_) { pattern = /[^\s.,;:!?()\[\]«»„“”"']+/g; }
                var match;
                while ((match = pattern.exec(text))) words.push({index:match.index, segment:match[0]});
            }
            for (var word of words) {
                var wordStart = start + word.index, wordEnd = wordStart + word.segment.length;
                if (wordEnd <= offset) continue;
                var range = F.rangeBetweenOffsets(wordStart, wordEnd);
                if (!range) continue;
                // Adjacent paragraphs need not have whitespace text nodes between them.
                var at = F.rangeAtOffset(Math.max(offset, wordStart));
                var block = at && at.startContainer.parentElement.closest('p,li,td,th,h1,h2,h3,h4,h5,h6,blockquote,figcaption,div,section');
                if (block) {
                    var bounds = document.createRange(); bounds.selectNodeContents(block);
                    if (range.compareBoundaryPoints(Range.START_TO_START, bounds) < 0) range.setStart(bounds.startContainer, bounds.startOffset);
                    if (range.compareBoundaryPoints(Range.END_TO_END, bounds) > 0) range.setEnd(bounds.endContainer, bounds.endOffset);
                }
                if (Array.from(range.getClientRects()).some(function(r) {
                    return r.width > 0 && r.height > 0 && r.right > 0 && r.left < innerWidth && r.bottom > 0 && r.top < innerHeight;
                })) return range;
            }
            return null;
        };
        F.paintResumeMarker = function() {
            if (!F.resumeMarker) return;
            var range = F.fixed ? null : F.resumeWordRange();
            var rects = range ? Array.from(range.getClientRects()).filter(function(r) {
                return r.width > 0 && r.height > 0 && r.right > 0 && r.left < innerWidth && r.bottom > 0 && r.top < innerHeight;
            }).map(function(r) { return [r.left, r.top, r.width, r.height].map(function(v) { return Math.round(v * 10) / 10; }); })
                .filter(function(r, i, all) { return all.findIndex(function(other) { return other.join(',') === r.join(','); }) === i; }) : [];
            var lines = [];
            rects.forEach(function(r) {
                var line = lines.find(function(b) { return Math.abs(b[1] - r[1]) <= 2 && Math.abs(b[3] - r[3]) <= 2 && r[0] <= b[0] + b[2] + 2 && r[0] + r[2] >= b[0] - 2; });
                if (!line) { lines.push(r.slice()); return; }
                var left = Math.min(line[0], r[0]), top = Math.min(line[1], r[1]);
                var right = Math.max(line[0] + line[2], r[0] + r[2]), bottom = Math.max(line[1] + line[3], r[1] + r[3]);
                line[0] = left; line[1] = top; line[2] = right - left; line[3] = bottom - top;
            });
            rects = lines;
            var key = JSON.stringify(['$textHex', rects]);
            var overlay = document.getElementById('folio-resume-marker');
            if (overlay && F.resumeMarkerKey === key) return;
            if (overlay) overlay.remove();
            overlay = document.createElement('folio-resume-overlay'); overlay.id = 'folio-resume-marker';
            overlay.setAttribute('role', 'note');
            overlay.setAttribute('aria-label', range ? 'Letzte Lesestelle: ' + range.toString() : 'Zuletzt gelesene Seite');
            overlay.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483646;';
            if (rects.length) rects.forEach(function(r) {
                var box = document.createElement('folio-resume-word'); box.setAttribute('aria-hidden', 'true');
                box.style.cssText = 'position:absolute;box-sizing:border-box;outline:2px solid $textHex;background:rgba(128,128,128,.18)!important;' +
                    'left:' + r[0] + 'px;top:' + r[1] + 'px;width:' + r[2] + 'px;height:' + r[3] + 'px;';
                overlay.appendChild(box);
            });
            else {
                var label = document.createElement('folio-resume-label'); label.textContent = 'Hier weiterlesen';
                label.style.cssText = 'position:absolute;top:12px;left:12px;padding:6px 10px;background:#fff!important;color:#000!important;border:2px solid #000;font:16px sans-serif;';
                overlay.appendChild(label);
            }
            // Outside body: never include the badge in the chapter's text index or publisher transform.
            document.documentElement.appendChild(overlay); F.resumeMarkerKey = key;
        };

        // ---- Layout / Pagination ------------------------------------------
        F.applyStyle = function(C, GAP, H, PH, PV) {
            var style = document.getElementById('folio-style');
            if (!style) {
                style = document.createElement('style');
                style.id = 'folio-style';
                document.head.appendChild(style);
            }
            style.textContent =
                'html,body{margin:0;padding:0;}' +
                'html{overscroll-behavior:none;}' +
                '::-webkit-scrollbar{display:none;}' +
                ':root{color-scheme:' + F.colorScheme + ';}' +
                'body{box-sizing:border-box;' +
                    'height:' + H + 'px;width:' + window.innerWidth + 'px;' +
                    'padding:' + PV + 'px ' + PH + 'px;' +
                    'column-width:' + C + 'px;column-gap:' + GAP + 'px;column-fill:auto;' +
                    'touch-action:none;' +
                    'font-size:1.08rem;line-height:1.6;-webkit-text-size-adjust:100%;' +
                    'overflow-wrap:break-word;word-wrap:break-word;}' +
                'img,svg,video{max-width:100%;max-height:' + (H - 2 * PV) + 'px;' +
                    'height:auto;object-fit:contain;break-inside:avoid;}' +
                'table{max-width:100%;}' +
                F.colorRules;
        };

        // High-contrast, discrete controls: no animated zoom or dragging needed on E-Ink.
        F.changeZoom = function(delta) {
            var levels = [1, 1.5, 2, 3];
            var index = levels.indexOf(F.zoom);
            var oldZoom = F.zoom;
            F.zoom = levels[Math.max(0, Math.min(levels.length - 1, index + delta))];
            F.panX = (F.panX + window.innerWidth / 2) * F.zoom / oldZoom - window.innerWidth / 2;
            F.panY = (F.panY + window.innerHeight / 2) * F.zoom / oldZoom - window.innerHeight / 2;
            if (window.AndroidReader && AndroidReader.setZoom) AndroidReader.setZoom(F.zoom);
            F.layout();
        };
        F.pan = function(dx, dy) {
            F.panX += dx * window.innerWidth * 0.75;
            F.panY += dy * window.innerHeight * 0.75;
            F.layout();
        };
        F.panel = function(direction) {
            var scale = Math.min(window.innerWidth / F.pageWidth, window.innerHeight / F.pageHeight) * F.zoom;
            var maxX = Math.max(0, F.pageWidth * scale - window.innerWidth);
            var maxY = Math.max(0, F.pageHeight * scale - window.innerHeight);
            if (direction > 0) {
                if (F.panX < maxX - 1) F.panX = Math.min(maxX, F.panX + window.innerWidth * .75);
                else if (F.panY < maxY - 1) { F.panX = 0; F.panY = Math.min(maxY, F.panY + window.innerHeight * .75); }
                else { F.next(); return; }
            } else {
                if (F.panX > 1) F.panX = Math.max(0, F.panX - window.innerWidth * .75);
                else if (F.panY > 1) { F.panX = maxX; F.panY = Math.max(0, F.panY - window.innerHeight * .75); }
                else { F.prev(); return; }
            }
            F.layout();
        };
        F.zoomControls = function() {
            var controls = document.getElementById('folio-zoom');
            var focused = controls && controls.contains(document.activeElement) ? document.activeElement.getAttribute('aria-label') : null;
            if (controls) controls.remove();
            controls = document.createElement('div');
            controls.id = 'folio-zoom';
            controls.style.cssText = 'position:fixed;bottom:8px;left:8px;z-index:2147483647;display:flex;flex-wrap:wrap;max-width:240px;gap:4px;background:white;color:black;padding:4px;border:1px solid black;';
            if (F.zoomRight) { controls.style.left = 'auto'; controls.style.right = '8px'; }
            function button(text, label, action, disabled) {
                var b = document.createElement('button');
                b.textContent = text; b.setAttribute('aria-label', label); b.disabled = !!disabled;
                b.style.cssText = 'min-width:48px;min-height:48px;font:18px sans-serif;color:black;background:white;border:1px solid black;touch-action:manipulation;';
                b.onclick = function(e) { e.stopPropagation(); action(); };
                controls.appendChild(b);
            }
            button(F.zoomOpen ? '×' : 'Zoom', F.zoomOpen ? 'Zoomregler schließen' : 'Zoomregler öffnen', function() { F.zoomOpen = !F.zoomOpen; F.zoomControls(); });
            if (F.zoomOpen) {
                button('−', 'Verkleinern', function() { F.changeZoom(-1); }, F.zoom === 1);
                button(Math.round(F.zoom * 100) + '%', 'Ganze Seite anzeigen', function() { F.zoom = 1; F.panX = 0; F.panY = 0; F.changeZoom(0); });
                button('⇄', 'Zoomregler versetzen', function() { F.zoomRight = !F.zoomRight; F.zoomControls(); });
                button('+', 'Vergrößern', function() { F.changeZoom(1); }, F.zoom === 3);
                if (F.zoom > 1) {
                    button('←', 'Ausschnitt nach links', function() { F.pan(-1, 0); });
                    button('↑', 'Ausschnitt nach oben', function() { F.pan(0, -1); });
                    button('↓', 'Ausschnitt nach unten', function() { F.pan(0, 1); });
                    button('→', 'Ausschnitt nach rechts', function() { F.pan(1, 0); });
                    button('‹', 'Vorheriger Ausschnitt', function() { F.panel(-1); });
                    button('›', 'Nächster Ausschnitt', function() { F.panel(1); });
                }
            }
            // Sibling of body: never part of the publisher's transformed canvas or text index.
            document.documentElement.appendChild(controls);
            if (focused) Array.from(controls.querySelectorAll('button')).find(function(b) { return b.getAttribute('aria-label') === focused; })?.focus({preventScroll:true});
        };

        // A fixed page is one canvas: preserve publisher CSS and scale every layer together.
        F.layoutFixed = function(W, H) {
            var body = document.body;
            if (!(F.pageWidth > 0 && F.pageHeight > 0)) {
                var svg = body.querySelector('svg[viewBox]');
                var box = svg && svg.viewBox.baseVal;
                var css = getComputedStyle(body);
                var img = body.querySelector('img');
                F.pageWidth = box && box.width > 0 ? box.width : parseFloat(css.width);
                F.pageHeight = box && box.height > 0 ? box.height : parseFloat(css.height);
                if (!(F.pageHeight > 0) && img && img.naturalWidth > 0) {
                    F.pageWidth = img.naturalWidth; F.pageHeight = img.naturalHeight;
                }
                if (!(F.pageWidth > 0 && F.pageHeight > 0)) return;
            }
            var scale = Math.min(W / F.pageWidth, H / F.pageHeight) * F.zoom;
            F.panX = Math.max(0, Math.min(Math.max(0, F.pageWidth * scale - W), F.panX));
            F.panY = Math.max(0, Math.min(Math.max(0, F.pageHeight * scale - H), F.panY));
            var x = F.pageWidth * scale <= W ? (W - F.pageWidth * scale) / 2 : -F.panX;
            var y = F.pageHeight * scale <= H ? (H - F.pageHeight * scale) / 2 : -F.panY;
            var style = document.getElementById('folio-style');
            if (!style) {
                style = document.createElement('style'); style.id = 'folio-style';
                document.head.appendChild(style);
            }
            style.textContent = 'html{margin:0!important;padding:0!important;overflow:hidden!important;background:#fff!important;}' +
                'body{position:absolute!important;left:0!important;top:0!important;margin:0!important;' +
                'width:' + F.pageWidth + 'px!important;height:' + F.pageHeight + 'px!important;' +
                'transform-origin:0 0!important;transform:translate(' + x + 'px,' + y + 'px) scale(' + scale + ')' + F.authorTransform + '!important;' +
                'overflow:visible!important;touch-action:none;-webkit-text-size-adjust:100%;}' +
                '::-webkit-scrollbar{display:none;}';
            F.screen = 0; F.screens = 1; F.step = W; F.anchor = -1;
            window.scrollTo(0, 0);
            F.zoomControls();
            F.paintResumeMarker();
            if (window.AndroidReader) AndroidReader.onPosition(F.fraction, -1, false, location.href);
        };

        F.layout = function() {
            var W = window.innerWidth, H = window.innerHeight;
            // Während eines Display-Wechsels (Foldable auf-/zuklappen) kann das
            // Fenster kurz 0 groß sein – dann nicht layouten, sonst landet man
            // am Kapitelanfang. Kurz darauf erneut versuchen.
            if (W <= 0 || H <= 0 || !document.body) {
                clearTimeout(F.retryTimer);
                F.retryTimer = setTimeout(F.layout, 120);
                return;
            }
            if (F.fixed) { F.layoutFixed(W, H); return; }
            var GAP = 48, PH = ${preferences.margin}, PV = 28;
            document.body.style.setProperty('font-size', '${preferences.fontSize}px', 'important');
            document.body.style.setProperty('font-family', '${if (preferences.sansSerif) "sans-serif" else "serif"}', 'important');
            document.body.style.setProperty('line-height', '${preferences.lineHeight}', 'important');
            var k = F.twoPage ? 2 : 1;
            var C = Math.floor((W - 2 * PH - (k - 1) * GAP) / k);
            F.PH = PH;
            F.applyStyle(C, GAP, H, PH, PV);

            F.step = k * (C + GAP);
            var sw = document.body.scrollWidth;
            var cols = Math.max(1, Math.round((sw - 2 * PH + GAP) / (C + GAP)));
            F.screens = Math.max(1, Math.ceil(cols / k));

            F.buildIndex();

            // Zielseite: primär Zeichen-Anker, sonst Kapitel-Anteil.
            var target = -1;
            if (F.anchor >= 0 && F.textLen > 0) target = F.pageOfOffset(F.anchor);
            if (target < 0) {
                target = F.screens <= 1 ? 0 : Math.round(F.fraction * (F.screens - 1));
            }
            if (F.fragment) {
                var element = document.getElementById(F.fragment);
                if (element) { var range = document.createRange(); range.selectNodeContents(element); var page = F.pageOfRange(range); if (page >= 0) { F.fragment = null; F.setScreen(page, false, true); return; } }
            }
            F.setScreen(target, false, false);
        };

        /**
         * [fromUser]: true bei echtem Blättern/Springen – nur dann wird der
         * Zeichen-Anker neu bestimmt. Layout-Wiederherstellungen (Resize,
         * Fold/Unfold, Nachpaginieren) lassen den Anker unverändert, sonst
         * driftet die Position mit jedem Re-Layout um Seiten weiter.
         */
        F.setScreen = function(i, smooth, fromUser) {
            i = Math.max(0, Math.min(F.screens - 1, i));
            F.screen = i;
            if (F.screens > 1) F.fraction = i / (F.screens - 1);
            if (fromUser) {
                F.clearResumeMarker();
                F.anchor = F.offsetForPage(i);
            } else if (F.anchor < 0 && F.textLen > 0) {
                // Initialize a new chapter's first page without inventing a reading timestamp.
                // The original resumeOffset remains unknown for legacy saved positions.
                F.anchor = F.offsetForPage(i);
            }
            window.scrollTo({
                left: i * F.step,
                top: 0,
                behavior: (smooth && F.smoothTurns) ? 'smooth' : 'auto',
            });
            F.paintResumeMarker();
            if (window.AndroidReader && AndroidReader.onPosition) {
                AndroidReader.onPosition(F.fraction, F.anchor, fromUser, location.href);
            }
        };

        F.next = function() {
            F.clearResumeMarker();
            if (F.screen < F.screens - 1) {
                F.setScreen(F.screen + 1, true, true);
            } else {
                F.fraction = 1;
                if (window.AndroidReader) {
                    AndroidReader.onPosition(1, F.anchor, true, location.href);
                    AndroidReader.onNextChapter(location.href);
                }
            }
        };

        F.prev = function() {
            F.clearResumeMarker();
            if (F.screen > 0) {
                F.setScreen(F.screen - 1, true, true);
            } else {
                if (window.AndroidReader) AndroidReader.onPrevChapter(location.href);
            }
        };

        // ---- Gesten & Lifecycle (einmal pro Dokument) ----------------------
        if (firstRun) {
            F.ready = true;

            document.addEventListener('click', function(e) {
                var t = e.target;
                if (t && t.closest && t.closest('#folio-zoom')) return;
                if (window.getSelection && !window.getSelection().isCollapsed) { F.selectionAt = Date.now(); return; }
                if (Date.now() - (F.selectionAt || 0) < 400) return;
                if (t && t.closest && t.closest('a')) return; // Links normal folgen
                var x = e.clientX / window.innerWidth;
                if (Date.now() - (F.lastSwipe || 0) < 400) return;
                if (x <= F.zone) { if (F.leftHanded) F.next(); else F.prev(); }
                else if (x >= 1 - F.zone) { if (F.leftHanded) F.prev(); else F.next(); }
                else if (window.AndroidReader) AndroidReader.onToggleMenu();
            }, true);

            var touchX = 0, touchY = 0, touchT = 0;
            document.addEventListener('touchstart', function(e) {
                if (e.target.closest && e.target.closest('#folio-zoom')) { touchT = 0; return; }
                if (e.touches.length !== 1) return;
                touchX = e.touches[0].clientX;
                touchY = e.touches[0].clientY;
                touchT = Date.now();
            }, { passive: true });
            document.addEventListener('touchend', function(e) {
                if (!touchT || (e.target.closest && e.target.closest('#folio-zoom'))) return;
                var c = e.changedTouches[0];
                if (!c) return;
                var dx = c.clientX - touchX;
                var dy = c.clientY - touchY;
                var dt = Date.now() - touchT;
                if (dt < 600 && Math.abs(dx) > 60 && Math.abs(dx) > 1.5 * Math.abs(dy)) {
                    F.lastSwipe = Date.now();
                    if (dx < 0) F.next(); else F.prev();
                }
            }, { passive: true });

            window.addEventListener('resize', function() {
                clearTimeout(F.resizeTimer);
                F.resizeTimer = setTimeout(function() { F.layout(); }, 150);
            });

            // Bilder/Schriften laden verzögert – mehrstufig nachpaginieren;
            // der Zeichen-Anker hält die Position dabei wortgenau.
            setTimeout(F.layout, 0);
            setTimeout(F.layout, 150);
            setTimeout(F.layout, 450);
            window.addEventListener('load', function() { F.layout(); });
            if (document.fonts) document.fonts.ready.then(function() { F.layout(); });
        } else {
            F.layout();
        }
    })();
    """.trimIndent()
}

/** Kapselt einen String sicher als JS-Literal. */
private fun jsString(s: String): String = org.json.JSONObject.quote(s)

// ---------------------------------------------------------------------------
// Systemleisten im Lesemodus ausblenden
// ---------------------------------------------------------------------------

@Composable
private fun HideSystemBars(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
            else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun androidx.compose.ui.graphics.Color.toCssHex(): String {
    val argb = toArgb()
    return String.format("#%06X", 0xFFFFFF and argb)
}
