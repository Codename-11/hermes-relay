package com.hermesandroid.relay.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// Brand palette — derived from assets/logo.svg
private val HermesPrimary = Color(0xFF6B35E8)       // Logo primary purple
private val HermesPrimaryLight = Color(0xFF9B6BF0)  // Logo accent purple
private val HermesPrimaryDark = Color(0xFF4A1DB8)   // Deeper variant for containers
private val HermesNavy = Color(0xFF1A1A2E)           // Logo background navy
private val HermesNavySurface = Color(0xFF1E1E34)    // Slightly lifted surface
private val HermesNavyVariant = Color(0xFF2A2A44)    // Card/surface variant

private val DarkColorScheme = darkColorScheme(
    primary = HermesPrimaryLight,
    onPrimary = Color(0xFF1A0049),
    primaryContainer = HermesPrimary,
    onPrimaryContainer = Color(0xFFE8DEFF),
    secondary = Color(0xFFB8AACC),
    onSecondary = Color(0xFF2B2040),
    secondaryContainer = Color(0xFF413558),
    onSecondaryContainer = Color(0xFFE8DEFF),
    tertiary = Color(0xFF9B6BF0),
    onTertiary = Color(0xFF1A0049),
    tertiaryContainer = Color(0xFF3D1F8C),
    onTertiaryContainer = Color(0xFFE8DEFF),
    background = HermesNavy,
    onBackground = Color(0xFFE4E1E9),
    surface = HermesNavy,
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = HermesNavyVariant,
    onSurfaceVariant = Color(0xFFC9C3D4),
    surfaceContainerLowest = Color(0xFF151524),
    surfaceContainerLow = Color(0xFF1C1C30),
    surfaceContainer = HermesNavySurface,
    surfaceContainerHigh = Color(0xFF24243C),
    surfaceContainerHighest = Color(0xFF2E2E48),
    outline = Color(0xFF5A5470),
    outlineVariant = Color(0xFF3D3854)
)

private val LightColorScheme = lightColorScheme(
    primary = HermesPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF1A0049),
    secondary = Color(0xFF5E5474),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEFF),
    onSecondaryContainer = Color(0xFF1B1030),
    tertiary = HermesPrimaryDark,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8DEFF),
    onTertiaryContainer = Color(0xFF1A0049),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1B1B22),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1B1B22),
    surfaceVariant = Color(0xFFEAE4F2),
    onSurfaceVariant = Color(0xFF48444E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F2FC),
    surfaceContainer = Color(0xFFF1ECF6),
    surfaceContainerHigh = Color(0xFFEBE6F0),
    surfaceContainerHighest = Color(0xFFE5E0EA),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCBC4D0)
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

    val colorScheme = when {
        // Dynamic colors available on Android 12+ (API 31)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDarkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        useDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

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
