package app.astiko.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's EFFECTIVE dark flag (system mode + the in-app
 * System/Light/Dark override, resolved in MainActivity).
 * isSystemInDarkTheme() alone would ignore the in-app override.
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * The map's EFFECTIVE dark flag (the app theme resolved against the
 * in-app map override, computed in MainActivity). Map screens read this
 * to pick a matching basemap style, so a forced map theme can diverge
 * from the app theme.
 */
val LocalMapDarkTheme = staticCompositionLocalOf { false }

/**
 * Astiko's fixed brand schemes, M3 tonal-spot palettes seeded from
 * AstikoBlue (hue 263 / chroma 61, generated with
 * @material/material-color-utilities). Dynamic color is intentionally
 * off. The app's identity is the blue, not the user's wallpaper.
 * Tertiary is a teal accent, neutrals are blue-tinted.
 */
private val LightColorScheme =
    lightColorScheme(
        primary = Color(0xFF005DB9),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD6E3FF),
        onPrimaryContainer = Color(0xFF001B3E),
        secondary = Color(0xFF565F71),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDAE2F9),
        onSecondaryContainer = Color(0xFF131C2B),
        tertiary = Color(0xFF186A60),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFA6F0E4),
        onTertiaryContainer = Color(0xFF00201C),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFAF9FD),
        onBackground = Color(0xFF1A1B1E),
        surface = Color(0xFFFAF9FD),
        onSurface = Color(0xFF1A1B1E),
        surfaceVariant = Color(0xFFE0E2EC),
        onSurfaceVariant = Color(0xFF44474E),
        surfaceTint = Color(0xFF005DB9),
        inverseSurface = Color(0xFF2F3033),
        inverseOnSurface = Color(0xFFF1F0F4),
        inversePrimary = Color(0xFFAAC7FF),
        outline = Color(0xFF74777F),
        outlineVariant = Color(0xFFC4C6D0),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFAF9FD),
        surfaceDim = Color(0xFFDBD9DD),
        surfaceContainer = Color(0xFFEFEDF1),
        surfaceContainerHigh = Color(0xFFE9E7EC),
        surfaceContainerHighest = Color(0xFFE3E2E6),
        surfaceContainerLow = Color(0xFFF4F3F7),
        surfaceContainerLowest = Color(0xFFFFFFFF),
    )

private val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFAAC7FF),
        onPrimary = Color(0xFF002F64),
        primaryContainer = Color(0xFF00458D),
        onPrimaryContainer = Color(0xFFD6E3FF),
        secondary = Color(0xFFBEC6DC),
        onSecondary = Color(0xFF283141),
        secondaryContainer = Color(0xFF3E4759),
        onSecondaryContainer = Color(0xFFDAE2F9),
        tertiary = Color(0xFF8AD4C8),
        onTertiary = Color(0xFF003732),
        tertiaryContainer = Color(0xFF005049),
        onTertiaryContainer = Color(0xFFA6F0E4),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF121316),
        onBackground = Color(0xFFE3E2E6),
        surface = Color(0xFF121316),
        onSurface = Color(0xFFE3E2E6),
        surfaceVariant = Color(0xFF44474E),
        onSurfaceVariant = Color(0xFFC4C6D0),
        surfaceTint = Color(0xFFAAC7FF),
        inverseSurface = Color(0xFFE3E2E6),
        inverseOnSurface = Color(0xFF2F3033),
        inversePrimary = Color(0xFF005DB9),
        outline = Color(0xFF8E9099),
        outlineVariant = Color(0xFF44474E),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF38393C),
        surfaceDim = Color(0xFF121316),
        surfaceContainer = Color(0xFF1E1F23),
        surfaceContainerHigh = Color(0xFF292A2D),
        surfaceContainerHighest = Color(0xFF343538),
        surfaceContainerLow = Color(0xFF1A1B1E),
        surfaceContainerLowest = Color(0xFF0D0E11),
    )

@Composable
fun AstikoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    mapDarkTheme: Boolean = darkTheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalMapDarkTheme provides mapDarkTheme,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content,
        )
    }
}
