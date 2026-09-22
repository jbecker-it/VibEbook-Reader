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
    viewModel: ReaderViewModel = hiltViewModel(key = bookId),
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

            else -> androidx.compose.runtime.key(book.id, state.layoutMode) { EpubWebView(
                filePath = book.spine[state.spineIndex.coerceIn(0, book.spine.lastIndex)],
                fixedLayout = when (state.layoutMode) {
                    de.folio.reader.domain.model.BookLayoutMode.ORIGINAL -> true
                    de.folio.reader.domain.model.BookLayoutMode.REFLOWABLE -> false
                    else -> state.layouts[book.spine[state.spineIndex.coerceIn(0, book.spine.lastIndex)]]
                },
                restoreFraction = state.restoreScrollFraction,
                restoreCharOffset = state.restoreCharOffset,
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
                onSeekChapter = { viewModel.goToChapter(it) },
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
    fixedLayout: Boolean?,
    restoreFraction: Float,
    restoreCharOffset: Int,
    twoPage: Boolean,
    smoothTurns: Boolean,
    backgroundHex: String,
    textHex: String,
    linkHex: String,
    forceColors: Boolean,
    preferences: ReaderPreferences,
    menuVisible: Boolean,
    onPosition: (Float, Int) -> Unit,
    onToggleMenu: () -> Unit,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
) {
    val bridge = remember { ReaderBridge(Handler(Looper.getMainLooper())) }
    bridge.positionListener = onPosition
    bridge.toggleMenuListener = onToggleMenu
    bridge.nextChapterListener = onNextChapter
    bridge.prevChapterListener = onPrevChapter

    val webViewRef = remember { arrayOfNulls<WebView>(1) }
    DisposableEffect(Unit) {
        onDispose {
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
        fixedLayout,
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
                        return request.url.buildUpon().fragment(null).build().toString() != Uri.fromFile(File(lastLoaded[0] ?: filePath)).toString()
                    }
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        val root = File(ctx.filesDir, "books").canonicalPath + File.separator
                        val allowed = request.url.scheme == "file" && runCatching {
                            File(request.url.path.orEmpty()).canonicalPath.startsWith(root)
                        }.getOrDefault(false)
                        return if (allowed) null else android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
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
    var positionListener: (Float, Int) -> Unit = { _, _ -> }
    var toggleMenuListener: () -> Unit = {}
    var nextChapterListener: () -> Unit = {}
    var prevChapterListener: () -> Unit = {}

    @JavascriptInterface
    fun onPosition(fraction: Float, charOffset: Int) {
        val f = fraction.coerceIn(0f, 1f)
        handler.post { positionListener(f, charOffset) }
    }

    @JavascriptInterface
    fun onToggleMenu() {
        handler.post { toggleMenuListener() }
    }

    @JavascriptInterface
    fun onNextChapter() {
        handler.post { nextChapterListener() }
    }

    @JavascriptInterface
    fun onPrevChapter() {
        handler.post { prevChapterListener() }
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
            F.anchor = $restoreCharOffset; // Zeichen-Offset, -1 = keiner
            F.screen = 0;
            F.screens = 1;
            F.step = 1;
            F.PH = 24;
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
            var scale = Math.min(W / F.pageWidth, H / F.pageHeight);
            var x = (W - F.pageWidth * scale) / 2;
            var y = (H - F.pageHeight * scale) / 2;
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
            if (window.AndroidReader) AndroidReader.onPosition(F.fraction, -1);
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
                F.anchor = F.offsetForPage(i);
            }
            window.scrollTo({
                left: i * F.step,
                top: 0,
                behavior: (smooth && F.smoothTurns) ? 'smooth' : 'auto',
            });
            if (window.AndroidReader && AndroidReader.onPosition) {
                AndroidReader.onPosition(F.fraction, F.anchor);
            }
        };

        F.next = function() {
            if (F.screen < F.screens - 1) {
                F.setScreen(F.screen + 1, true, true);
            } else {
                F.fraction = 1;
                if (window.AndroidReader) {
                    AndroidReader.onPosition(1, F.anchor);
                    AndroidReader.onNextChapter();
                }
            }
        };

        F.prev = function() {
            if (F.screen > 0) {
                F.setScreen(F.screen - 1, true, true);
            } else {
                if (window.AndroidReader) AndroidReader.onPrevChapter();
            }
        };

        // ---- Gesten & Lifecycle (einmal pro Dokument) ----------------------
        if (firstRun) {
            F.ready = true;

            document.addEventListener('click', function(e) {
                var t = e.target;
                if (t && t.closest && t.closest('a')) return; // Links normal folgen
                var x = e.clientX / window.innerWidth;
                if (Date.now() - (F.lastSwipe || 0) < 400) return;
                if (x <= F.zone) { if (F.leftHanded) F.next(); else F.prev(); }
                else if (x >= 1 - F.zone) { if (F.leftHanded) F.prev(); else F.next(); }
                else if (window.AndroidReader) AndroidReader.onToggleMenu();
            }, true);

            var touchX = 0, touchY = 0, touchT = 0;
            document.addEventListener('touchstart', function(e) {
                if (e.touches.length !== 1) return;
                touchX = e.touches[0].clientX;
                touchY = e.touches[0].clientY;
                touchT = Date.now();
            }, { passive: true });
            document.addEventListener('touchend', function(e) {
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
        } else {
            F.layout();
        }
    })();
    """.trimIndent()
}

/** Kapselt einen String sicher als JS-Literal. */
private fun jsString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

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
