package com.hermesandroid.relay.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.hermesandroid.relay.data.AppearancePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutStyleTest {
    @Test
    fun `unknown or missing ids fall back to clean`() {
        assertEquals(LayoutStyle.CLEAN, LayoutStyle.fromId(null))
        assertEquals(LayoutStyle.CLEAN, LayoutStyle.fromId("compact"))
        assertEquals(LayoutStyle.CLASSIC, LayoutStyle.fromId("classic"))
    }

    @Test
    fun `persisted appearance decodes the layout style`() {
        val missing = AppearancePreferences.decode(mutablePreferencesOf())
        assertEquals(LayoutStyle.CLEAN.id, missing.layoutStyleId)

        val classic = AppearancePreferences.decode(
            mutablePreferencesOf(AppearancePreferences.layoutStyleKey to "classic"),
        )
        assertEquals(LayoutStyle.CLASSIC.id, classic.layoutStyleId)

        val invalid = AppearancePreferences.decode(
            mutablePreferencesOf(AppearancePreferences.layoutStyleKey to "dense"),
        )
        assertEquals(LayoutStyle.CLEAN.id, invalid.layoutStyleId)
    }

    @Test
    fun `clean typography keeps metadata in the body face`() {
        val body = FontFamily.Serif
        assertEquals(FontFamily.Monospace, appTypography(body).labelSmall.fontFamily)
        assertEquals(body, appTypography(body, monospaceMetadata = false).labelSmall.fontFamily)
    }

    @Test
    fun `clean and material you themes are selectable and follow light and dark`() {
        listOf(AppThemes.Clean, AppThemes.MaterialYou).forEach { theme ->
            assertEquals(theme, AppThemes.byId(theme.id))
            assertEquals(ThemeMode.BOTH, theme.mode)
            assertTrue(theme.darkPalette.isDark)
            assertFalse(theme.lightPalette.isDark)
        }
    }

    @Test
    fun `clean palettes keep body text readable`() {
        listOf(BrandPalettes.CleanDark, BrandPalettes.CleanLight).forEach { palette ->
            assertTrue(contrastRatio(palette.ink, palette.background) >= 7f)
            assertTrue(contrastRatio(palette.muted, palette.background) >= 4.5f)
        }
    }

    @Test
    fun `dynamic color schemes map onto brand tokens`() {
        val scheme = BrandPalettes.CleanDark.toColorScheme()
        val palette = scheme.toBrandPalette(isDark = true)
        assertEquals(scheme.surface, palette.background)
        assertEquals(scheme.onSurface, palette.ink)
        assertEquals(scheme.primary, palette.relay)
        assertEquals(scheme.error, palette.danger)
    }
}
