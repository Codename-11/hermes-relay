package com.hermesandroid.relay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val DarkColorScheme = darkColorScheme(
    primary = RelayRefresh.Relay,
    onPrimary = RelayRefresh.Background,
    primaryContainer = RelayRefresh.Electric,
    onPrimaryContainer = RelayRefresh.Paper,
    secondary = RelayRefresh.Purple,
    onSecondary = RelayRefresh.Paper,
    secondaryContainer = RelayRefresh.Navy3,
    onSecondaryContainer = RelayRefresh.Paper,
    tertiary = RelayRefresh.Cyan,
    onTertiary = RelayRefresh.Background,
    tertiaryContainer = RelayRefresh.Purple.copy(alpha = 0.42f),
    onTertiaryContainer = RelayRefresh.Paper,
    background = RelayRefresh.Background,
    onBackground = RelayRefresh.Ink,
    surface = RelayRefresh.Background,
    onSurface = RelayRefresh.Ink,
    surfaceVariant = RelayRefresh.Navy2,
    onSurfaceVariant = RelayRefresh.Muted,
    surfaceContainerLowest = Color(0xFF05060A),
    surfaceContainerLow = Color(0xFF0B0C12),
    surfaceContainer = RelayRefresh.Navy,
    surfaceContainerHigh = RelayRefresh.Navy2,
    surfaceContainerHighest = RelayRefresh.Navy3,
    error = RelayRefresh.Danger,
    onError = RelayRefresh.Background,
    errorContainer = RelayRefresh.Danger.copy(alpha = 0.18f),
    onErrorContainer = RelayRefresh.Paper,
    outline = RelayRefresh.LineStrong,
    outlineVariant = RelayRefresh.Line,
)

private val LightColorScheme = lightColorScheme(
    primary = RelayRefresh.Electric,
    onPrimary = Color.White,
    primaryContainer = RelayRefresh.Relay,
    onPrimaryContainer = RelayRefresh.Background,
    secondary = RelayRefresh.Purple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E7FF),
    onSecondaryContainer = RelayRefresh.Background,
    tertiary = RelayRefresh.Cyan,
    onTertiary = RelayRefresh.Background,
    tertiaryContainer = Color(0xFFDDF8FF),
    onTertiaryContainer = RelayRefresh.Background,
    background = RelayRefresh.Paper,
    onBackground = RelayRefresh.Background,
    surface = RelayRefresh.Paper,
    onSurface = RelayRefresh.Background,
    surfaceVariant = Color(0xFFE9E8F1),
    onSurfaceVariant = Color(0xFF38384A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F2F8),
    surfaceContainer = Color(0xFFEDEBF4),
    surfaceContainerHigh = Color(0xFFE3E1EC),
    surfaceContainerHighest = Color(0xFFDAD7E6),
    error = RelayRefresh.Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFFD9DE),
    onErrorContainer = Color(0xFF410006),
    outline = Color(0xFF777386),
    outlineVariant = Color(0xFFCAC7D8),
)

@Composable
fun HermesRelayTheme(
    themePreference: String = "auto",
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themePreference) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme

    // Compose-wide font scaling. We multiply the user's chosen scale into the
    // current LocalDensity.fontScale (which already reflects the system font
    // size accessibility setting), so our preference stacks on top of the
    // system value rather than overriding it. Every Text/TextField composable
    // reads its sp dimensions through LocalDensity, so this propagates to
    // every screen automatically without rewriting Typography.
    if (fontScale == 1.0f) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    } else {
        val currentDensity = LocalDensity.current
        val scaledDensity = Density(
            density = currentDensity.density,
            fontScale = currentDensity.fontScale * fontScale
        )
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = Typography,
                content = content
            )
        }
    }
}
