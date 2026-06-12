package de.folio.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import de.folio.reader.domain.model.ThemeMode

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

@Composable
fun FolioTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val colors = when (themeMode) {
        ThemeMode.AMOLED -> AmoledColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkColors else LightColors
    }
    CompositionLocalProvider(LocalIsAmoled provides (themeMode == ThemeMode.AMOLED)) {
        MaterialTheme(
            colorScheme = colors,
            typography = FolioTypography,
            content = content,
        )
    }
}
