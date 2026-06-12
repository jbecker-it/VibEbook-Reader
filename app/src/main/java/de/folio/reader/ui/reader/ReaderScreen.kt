package de.folio.reader.ui.reader

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.folio.reader.ui.theme.LocalIsAmoled

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    embedded: Boolean,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(key = bookId),
) {
    LaunchedEffect(bookId) { viewModel.load(bookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Beim Verlassen den letzten Stand sichern.
    DisposableEffect(bookId) { onDispose { viewModel.saveNow() } }

    val colorScheme = MaterialTheme.colorScheme
    val isAmoled = LocalIsAmoled.current
    val bgHex = remember(colorScheme.background) { colorScheme.background.toCssHex() }
    val fgHex = remember(colorScheme.onBackground) { colorScheme.onBackground.toCssHex() }
    val linkHex = remember(colorScheme.primary) { colorScheme.primary.toCssHex() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.book?.title ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.surface,
                    titleContentColor = colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            val book = state.book
            if (book != null && book.spine.isNotEmpty()) {
                ChapterBar(
                    current = state.spineIndex,
                    total = book.spine.size,
                    onPrev = viewModel::previousChapter,
                    onNext = viewModel::nextChapter,
                )
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
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
                    forceColors = isAmoled || bgHex == "#000000",
                    onScroll = viewModel::onScroll,
                )
            }
        }
    }
}

@Composable
private fun ChapterBar(
    current: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrev, enabled = current > 0) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Vorheriges Kapitel")
        }
        Text(
            text = "Kapitel ${current + 1} / $total",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onNext, enabled = current < total - 1) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Nächstes Kapitel")
        }
    }
}

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
) {
    val bridge = remember { ScrollBridge(onScroll) }
    val lastLoaded = remember { arrayOfNulls<String>(1) }
    // Wird bei jeder Recomposition aktualisiert, damit onPageFinished stets das
    // passende Skript (inkl. korrekter Wiederherstellungsposition) verwendet.
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
                web.loadUrl("file://$filePath")
            }
        },
    )
}

/** Brücke vom WebView-JavaScript zurück nach Kotlin. */
private class ScrollBridge(private val onScroll: (Float) -> Unit) {
    @JavascriptInterface
    fun onScroll(fraction: Float) {
        onScroll(fraction.coerceIn(0f, 1f))
    }
}

/**
 * Baut das injizierte JS: setzt eine lesefreundliche, themenpassende Darstellung,
 * stellt die gespeicherte Scrollposition wieder her und meldet Scrollbewegungen.
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

    // Kotlin-Float in JS-Zahl
    val frac = restoreFraction.coerceIn(0f, 1f).toString()

    return """
    (function() {
        var style = document.getElementById('folio-style');
        if (!style) {
            style = document.createElement('style');
            style.id = 'folio-style';
            document.head.appendChild(style);
        }
        style.textContent = `
            :root { color-scheme: ${if (forceColors) "dark" else "normal"}; }
            body {
                margin: 0 auto; padding: 28px 20px; max-width: 44rem;
                font-size: 1.12rem; line-height: 1.62;
                -webkit-text-size-adjust: 100%;
                overflow-wrap: break-word; word-wrap: break-word;
            }
            img { max-width: 100%; height: auto; }
            $colorRules
        `;

        function restore() {
            var max = document.documentElement.scrollHeight - window.innerHeight;
            if (max > 0) window.scrollTo(0, Math.round($frac * max));
        }
        // Layout/Bilder können verzögert fertig werden – mehrfach versuchen.
        restore();
        setTimeout(restore, 60);
        setTimeout(restore, 250);
        window.addEventListener('load', restore);

        var ticking = false;
        function report() {
            var max = document.documentElement.scrollHeight - window.innerHeight;
            var frac = max > 0 ? (window.scrollY / max) : 0;
            if (window.AndroidReader && AndroidReader.onScroll) AndroidReader.onScroll(frac);
            ticking = false;
        }
        window.addEventListener('scroll', function() {
            if (!ticking) { window.requestAnimationFrame(report); ticking = true; }
        }, { passive: true });
    })();
    """.trimIndent()
}

private fun Color.toCssHex(): String {
    val argb = toArgb()
    return String.format("#%06X", 0xFFFFFF and argb)
}
