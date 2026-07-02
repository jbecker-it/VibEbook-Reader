package de.folio.reader.ui.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlin.math.roundToInt

/**
 * Vollbild-Reader. Tippen in der Bildschirmmitte blendet das Menü ein/aus,
 * Tippen links/rechts blättert seitenweise (an Kapitelgrenzen weiter zum
 * nächsten/vorherigen Kapitel). Solange das Menü verborgen ist, sind auch die
 * Systemleisten ausgeblendet.
 */
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(key = bookId),
) {
    LaunchedEffect(bookId) { viewModel.load(bookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var menuVisible by rememberSaveable { mutableStateOf(false) }

    BackHandler { onBack() }
    DisposableEffect(bookId) { onDispose { viewModel.saveNow() } }
    HideSystemBars(hidden = !menuVisible)

    val colorScheme = MaterialTheme.colorScheme
    val bgHex = remember(colorScheme.background) { colorScheme.background.toCssHex() }
    val fgHex = remember(colorScheme.onBackground) { colorScheme.onBackground.toCssHex() }
    val linkHex = remember(colorScheme.primary) { colorScheme.primary.toCssHex() }
    val forceColors = bgHex != "#FFFFFF"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background),
    ) {
        val book = state.book
        when {
            state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

            book == null || book.spine.isEmpty() -> Text(
                text = "Dieses Buch konnte nicht geöffnet werden.",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = colorScheme.onSurfaceVariant,
            )

            else -> EpubWebView(
                filePath = book.spine[state.spineIndex.coerceIn(0, book.spine.lastIndex)],
                restoreFraction = state.restoreScrollFraction,
                backgroundHex = bgHex,
                textHex = fgHex,
                linkHex = linkHex,
                forceColors = forceColors,
                onScroll = viewModel::onScroll,
                onToggleMenu = { menuVisible = !menuVisible },
                onNextChapter = viewModel::nextChapter,
                onPrevChapter = viewModel::previousChapter,
            )
        }

        // Oberes Overlay: Zurück, Titel, Favorit
        AnimatedVisibility(
            visible = menuVisible && book != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopOverlay(
                title = book?.title ?: "",
                favorite = state.favorite,
                onBack = onBack,
                onToggleFavorite = viewModel::toggleFavorite,
            )
        }

        // Unteres Overlay: Kapitelnavigation + Slider + Prozent
        AnimatedVisibility(
            visible = menuVisible && book != null && book.spine.isNotEmpty(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
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
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
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
// WebView
// ---------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EpubWebView(
    filePath: String,
    restoreFraction: Float,
    backgroundHex: String,
    textHex: String,
    linkHex: String,
    forceColors: Boolean,
    onScroll: (Float) -> Unit,
    onToggleMenu: () -> Unit,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
) {
    val bridge = remember { ReaderBridge(Handler(Looper.getMainLooper())) }
    // Callbacks bei jeder Recomposition aktuell halten (Bridge lebt so lange wie der WebView).
    bridge.scrollListener = onScroll
    bridge.toggleMenuListener = onToggleMenu
    bridge.nextChapterListener = onNextChapter
    bridge.prevChapterListener = onPrevChapter

    val lastLoaded = remember { arrayOfNulls<String>(1) }
    val injection = remember { arrayOf("") }
    injection[0] = buildInjection(restoreFraction, backgroundHex, textHex, linkHex, forceColors)

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.builtInZoomControls = false
                settings.textZoom = 100
                isVerticalScrollBarEnabled = true
                addJavascriptInterface(bridge, "AndroidReader")
                setBackgroundColor(android.graphics.Color.parseColor(backgroundHex))
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(injection[0], null)
                    }
                }
            }
        },
        update = { web ->
            web.setBackgroundColor(android.graphics.Color.parseColor(backgroundHex))
            if (lastLoaded[0] != filePath) {
                lastLoaded[0] = filePath
                // Uri.fromFile kodiert Sonderzeichen/Leerzeichen im Pfad korrekt.
                web.loadUrl(Uri.fromFile(File(filePath)).toString())
            }
        },
    )
}

/**
 * Brücke vom WebView-JavaScript nach Kotlin. JS-Aufrufe kommen auf einem
 * Hintergrund-Thread an und werden auf den Main-Thread gehoben.
 */
private class ReaderBridge(private val handler: Handler) {
    var scrollListener: (Float) -> Unit = {}
    var toggleMenuListener: () -> Unit = {}
    var nextChapterListener: () -> Unit = {}
    var prevChapterListener: () -> Unit = {}

    @JavascriptInterface
    fun onScroll(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        handler.post { scrollListener(f) }
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
 * Injiziertes JS: lesefreundliches, themenpassendes Styling; Wiederherstellung
 * der Scrollposition; Scroll-Meldungen; Tap-Zonen (links = zurückblättern,
 * Mitte = Menü, rechts = vorblättern) mit Kapitelwechsel an den Grenzen.
 */
private fun buildInjection(
    restoreFraction: Float,
    backgroundHex: String,
    textHex: String,
    linkHex: String,
    forceColors: Boolean,
): String {
    val colorRules = if (forceColors) {
        """
        html, body { background: $backgroundHex !important; color: $textHex !important; }
        p, div, span, li, td, th, h1, h2, h3, h4, h5, h6, blockquote, figcaption {
            color: $textHex !important; background-color: transparent !important;
        }
        a { color: $linkHex !important; }
        """.trimIndent()
    } else {
        "html, body { background: $backgroundHex; color: $textHex; } a { color: $linkHex; }"
    }

    val frac = restoreFraction.coerceIn(0f, 1f).toString()
    val colorScheme = if (forceColors) "dark" else "normal"

    return """
    (function() {
        var style = document.getElementById('folio-style');
        if (!style) {
            style = document.createElement('style');
            style.id = 'folio-style';
            document.head.appendChild(style);
        }
        style.textContent = `
            :root { color-scheme: $colorScheme; }
            body {
                margin: 0 auto; padding: 40px 22px 48px 22px; max-width: 44rem;
                font-size: 1.12rem; line-height: 1.62;
                -webkit-text-size-adjust: 100%;
                overflow-wrap: break-word; word-wrap: break-word;
            }
            img { max-width: 100%; height: auto; }
            $colorRules
        `;

        function maxScroll() {
            return Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
        }

        function restore() {
            var max = maxScroll();
            if (max > 0) window.scrollTo(0, Math.round($frac * max));
        }
        restore();
        setTimeout(restore, 60);
        setTimeout(restore, 250);
        window.addEventListener('load', restore);

        if (!window.__folioInit) {
            window.__folioInit = true;

            var ticking = false;
            function report() {
                var max = maxScroll();
                var f = max > 0 ? (window.scrollY / max) : 1;
                if (window.AndroidReader && AndroidReader.onScroll) AndroidReader.onScroll(f);
                ticking = false;
            }
            window.addEventListener('scroll', function() {
                if (!ticking) { window.requestAnimationFrame(report); ticking = true; }
            }, { passive: true });

            function pageStep() { return Math.max(60, window.innerHeight * 0.9); }
            function pageForward() {
                if (window.scrollY >= maxScroll() - 6) {
                    AndroidReader.onNextChapter();
                } else {
                    window.scrollBy({ top: pageStep(), left: 0, behavior: 'smooth' });
                }
            }
            function pageBackward() {
                if (window.scrollY <= 6) {
                    AndroidReader.onPrevChapter();
                } else {
                    window.scrollBy({ top: -pageStep(), left: 0, behavior: 'smooth' });
                }
            }

            document.addEventListener('click', function(e) {
                var t = e.target;
                if (t && t.closest && t.closest('a')) return; // Links normal folgen
                var x = e.clientX / window.innerWidth;
                if (x <= 0.3) pageBackward();
                else if (x >= 0.7) pageForward();
                else AndroidReader.onToggleMenu();
            }, true);
        }
    })();
    """.trimIndent()
}

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
