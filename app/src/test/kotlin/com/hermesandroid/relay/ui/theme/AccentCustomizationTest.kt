package com.hermesandroid.relay.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentCustomizationTest {
    @Test
    fun `accent values normalize to stable persisted RGB`() {
        assertEquals("#5B6CFF", normalizeAccentHex(" 5b6cff "))
        assertNull(normalizeAccentHex("javascript:red"))
    }

    @Test
    fun `accent changes brand colors but preserves surfaces and semantics`() {
        val base = BrandPalettes.HermesDark
        val customized = base.withAccent("#E05A33")

        assertNotEquals(base.electric, customized.electric)
        assertEquals(base.background, customized.background)
        assertEquals(base.danger, customized.danger)
    }

    @Test
    fun `derived primary always has readable material content color`() {
        listOf("#000000", "#FFFFFF", "#5B6CFF", "#B07A12").forEach { accent ->
            val scheme = BrandPalettes.HermesLight.withAccent(accent).toColorScheme()
            assertTrue("$accent contrast", contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5f)
        }
        assertEquals(Color.White, readableContentColor(Color.Black))
    }

    @Test
    fun `all authored palettes and accents keep material text pairs readable`() {
        val palettes = AppThemes.ALL.flatMap { theme ->
            listOf("${theme.id}-dark" to theme.darkPalette, "${theme.id}-light" to theme.lightPalette)
        }
        val accents = listOf<String?>(null) + AccentSwatches

        palettes.forEach { (paletteName, palette) ->
            accents.forEach { accent ->
                val scheme = palette.withAccent(accent).toColorScheme()
                val pairs = listOf(
                    "primary" to (scheme.onPrimary to scheme.primary),
                    "primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                    "secondary" to (scheme.onSecondary to scheme.secondary),
                    "secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                    "tertiary" to (scheme.onTertiary to scheme.tertiary),
                    "tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                    "error" to (scheme.onError to scheme.error),
                    "errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
                    "surface" to (scheme.onSurface to scheme.surface),
                    "surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
                )
                pairs.forEach { (name, colors) ->
                    val renderedBackground = colors.second.compositeOver(scheme.surface)
                    val renderedForeground = colors.first.compositeOver(renderedBackground)
                    assertTrue(
                        "$paletteName/$accent/$name contrast",
                        contrastRatio(renderedForeground, renderedBackground) >= 4.5f,
                    )
                }
            }
        }
    }
}
