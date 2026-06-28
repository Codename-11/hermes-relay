package com.hermesandroid.relay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * App theme root.
 *
 * Resolves the selected [AppTheme] + light/dark [themePreference] into a single
 * [BrandPalette], then:
 *  - derives the Material [androidx.compose.material3.ColorScheme] from it,
 *  - publishes it on [LocalBrand] for composition-correct new code, and
 *  - mirrors it into the [RelayRefresh] façade so the 150+ legacy `RelayRefresh.X`
 *    call sites repaint with the active theme.
 *
 * @param appThemeId id from [AppThemes]; defaults to the Hermes Relay brand.
 * @param themePreference mode axis — "auto" / "light" / "dark". Only meaningful
 *   for [ThemeMode.BOTH] themes; fixed-mode themes ignore it.
 * @param appFontId id from [AppFont]; selects the body typeface for the whole
 *   app. Defaults to Inter. Code/metadata styles stay monospaced regardless.
 */
@Composable
fun HermesRelayTheme(
    appThemeId: String = AppThemes.DEFAULT_ID,
    themePreference: String = "auto",
    fontScale: Float = 1.0f,
    appFontId: String = AppFont.DEFAULT.id,
    content: @Composable () -> Unit
) {
    val appTheme = AppThemes.byId(appThemeId)
    val useDarkTheme = appTheme.resolveDark(themePreference, isSystemInDarkTheme())
    val palette = appTheme.paletteFor(useDarkTheme)
    val colorScheme = palette.toColorScheme()

    // Build the Material typography from the selected font. Remembered per id so
    // a recomposition (e.g. theme/mode change) doesn't rebuild the FontFamily
    // graph; a font-pick changes appFontId, which re-themes every Text live.
    val typography = remember(appFontId) { appTypography(AppFont.byId(appFontId).fontFamily()) }

    // Mirror into the legacy façade after commit so snapshot reads in existing
    // call sites observe the active palette. SideEffect runs post-composition,
    // avoiding a state-write-during-composition; the default theme matches the
    // façade's initial value, so the common path has no first-frame flash.
    SideEffect { RelayRefresh.activePalette = palette }

    CompositionLocalProvider(LocalBrand provides palette) {
        // Compose-wide font scaling. We multiply the user's chosen scale into the
        // current LocalDensity.fontScale (which already reflects the system font
        // size accessibility setting), so our preference stacks on top of the
        // system value rather than overriding it. Every Text/TextField composable
        // reads its sp dimensions through LocalDensity, so this propagates to
        // every screen automatically without rewriting Typography.
        if (fontScale == 1.0f) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = typography,
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
}
