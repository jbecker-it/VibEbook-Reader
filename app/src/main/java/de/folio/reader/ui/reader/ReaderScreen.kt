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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.folio.reader.domain.model.PageLayoutMode
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

    var menuVisible by rememberSaveable { mutableStateOf(false) }

    BackHandler { onBack() }
    DisposableEffect(bookId) { onDispose { viewModel.saveNow() } }
    HideSystemBars(hidden = !menuVisible)

    val colorScheme = MaterialTheme.colorScheme
    val bgHex = remember(colorScheme.background) { colorScheme.background.toCssHex() }
    val fgHex = remember(colorScheme.onBackground) { colorScheme.onBackground.toCssHex() }
    val linkHex = remember(colorScheme.primary) { colorScheme.primary.toCssHex() }
    val forceColors = bgHex != "#FFFFFF"

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
            state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

            book == null || book.spine.isEmpty() -> Text(
                text = "Dieses Buch konnte nicht geöffnet werden.",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = colorScheme.onSurfaceVariant,
            )

            else -> EpubWebView(
                filePath = book.spine[state.spineIndex.coerceIn(0, book.spine.lastIndex)],
                restoreFraction = state.restoreScrollFraction,
                restoreCharOffset = state.restoreCharOffset,
                twoPage = twoPage,
                smoothTurns = !eInk,
                backgroundHex = bgHex,
                textHex = fgHex,
                linkHex = linkHex,
                forceColors = forceColors,
                onPosition = viewModel::onPosition,
                onToggleMenu = { menuVisible = !menuVisible },
                onNextChapter = viewModel::nextChapter,
                onPrevChapter = viewModel::previousChapter,
            )
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
// WebView mit Spalten-Pagination und Zeichen-Ankern
// ---------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EpubWebView(
    filePath: String,
    restoreFraction: Float,
    restoreCharOffset: Int,
    twoPage: Boolean,
    smoothTurns: Boolean,
    backgroundHex: String,
    textHex: String,
    linkHex: String,
    forceColors: Boolean,
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
    val lastLoaded = remember { arrayOfNulls<String>(1) }
    val injection = remember { arrayOf("") }
    injection[0] = buildInjection(
        restoreFraction, restoreCharOffset, twoPage, smoothTurns,
        backgroundHex, textHex, linkHex, forceColors,
    )

    // Layout-/Themewechsel ohne Neuladen anwenden: erneut injizieren – das
    // Skript repaginiert und hält die Position über den Zeichen-Anker.
    LaunchedEffect(twoPage, smoothTurns, backgroundHex, textHex, forceColors) {
        webViewRef[0]?.evaluateJavascript(injection[0], null)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.builtInZoomControls = false
                settings.textZoom = 100
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addJavascriptInterface(bridge, "AndroidReader")
                setBackgroundColor(android.graphics.Color.parseColor(backgroundHex))
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(injection[0], null)
                    }
                }
                webViewRef[0] = this
            }
        },
        update = { web ->
            web.setBackgroundColor(android.graphics.Color.parseColor(backgroundHex))
            if (lastLoaded[0] != filePath) {
                lastLoaded[0] = filePath
                web.loadUrl(Uri.fromFile(File(filePath)).toString())
            }
        },
    )
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
private fun buildInjection(
    restoreFraction: Float,
    restoreCharOffset: Int,
    twoPage: Boolean,
    smoothTurns: Boolean,
    backgroundHex: String,
    textHex: String,
    linkHex: String,
    forceColors: Boolean,
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
    val colorScheme = if (forceColors) "dark" else "normal"

    return """
    (function() {
        var F = window.__folio = window.__folio || {};
        var firstRun = !F.ready;

        F.twoPage = $twoPage;
        F.smoothTurns = $smoothTurns;
        F.colorRules = ${jsString(colorRules)};
        F.colorScheme = "$colorScheme";
        if (firstRun) {
            F.fraction = $frac;          // Kapitel-Anteil 0..1 (Fallback)
            F.anchor = $restoreCharOffset; // Zeichen-Offset, -1 = keiner
            F.screen = 0;
            F.screens = 1;
            F.step = 1;
            F.PH = 24;
        }

        if (firstRun && !document.querySelector('meta[name=viewport]')) {
            var m = document.createElement('meta');
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

        F.layout = function() {
            var W = window.innerWidth, H = window.innerHeight;
            var GAP = 48, PH = 24, PV = 28;
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
            F.setScreen(target, false);
        };

        F.setScreen = function(i, smooth) {
            i = Math.max(0, Math.min(F.screens - 1, i));
            F.screen = i;
            if (F.screens > 1) F.fraction = i / (F.screens - 1);
            var a = F.offsetForPage(i);
            if (a >= 0) F.anchor = a;
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
                F.setScreen(F.screen + 1, true);
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
                F.setScreen(F.screen - 1, true);
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
                if (x <= 0.3) F.prev();
                else if (x >= 0.7) F.next();
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
