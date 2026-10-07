package de.folio.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import de.folio.reader.domain.model.ThemeMode
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.ExperimentalMaterial3Api

private val AmoledColors = darkColorScheme(
    primary = Accent,
    onPrimary = AmoledBackground,
    background = AmoledBackground,
    onBackground = AmoledOnSurface,
    surface = AmoledSurface,
    onSurface = AmoledOnSurface,
    surfaceVariant = AmoledSurfaceVariant,
    onSurfaceVariant = AmoledOnSurfaceMuted,
    outline = AmoledOutline,
    outlineVariant = AmoledOutline,
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    outline = DarkOutline,
)

private val LightColors = lightColorScheme(
    primary = AccentDark,
    onPrimary = LightSurface,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    outline = LightOutline,
)

/** Signalisiert dem Reader, dass der AMOLED-Modus aktiv ist. */
val LocalIsAmoled = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolioTheme(
    themeMode: ThemeMode,
    eInk: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (eInk) lightColorScheme(
        primary = Color.Black, onPrimary = Color.White, secondary = Color.Black,
        onSecondary = Color.White, background = Color.White, onBackground = Color.Black,
        surface = Color.White, onSurface = Color.Black, surfaceVariant = Color.White,
        onSurfaceVariant = Color.Black, outline = Color.Black, outlineVariant = Color.Black,
        primaryContainer = Color.White, onPrimaryContainer = Color.Black,
        secondaryContainer = Color.White, onSecondaryContainer = Color.Black,
        error = Color.Black, onError = Color.White, surfaceTint = Color.White,
    ) else when (themeMode) {
        ThemeMode.AMOLED -> AmoledColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkColors else LightColors
    }
    CompositionLocalProvider(LocalIsAmoled provides (!eInk && themeMode == ThemeMode.AMOLED),
        LocalRippleConfiguration provides if (eInk) null else LocalRippleConfiguration.current) {
        MaterialTheme(
            colorScheme = colors,
            typography = FolioTypography,
            content = content,
        )
    }
}
