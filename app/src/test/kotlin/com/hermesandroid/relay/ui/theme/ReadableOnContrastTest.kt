package com.hermesandroid.relay.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [readableOn] is the bridge between the semantic status tokens (authored for
 * 3:1 dots, icons and chips) and the 4.5:1 floor that applies once the same
 * token is painted as small text. These tests pin both halves of the contract:
 * failing pairs are repaired, and passing pairs are left exactly as authored so
 * dark presets keep their hue.
 */
class ReadableOnContrastTest {
    private val light = BrandPalettes.HermesLight
    private val dark = BrandPalettes.HermesDark

    @Test
    fun `light palette green clears AA on every surface it labels`() {
        // The three containers GitStateScreen actually paints green text on:
        // file rows over the screen background, the summary rail, and the
        // selection rail's Stage button.
        listOf(
            "background" to light.background,
            "surfaceContainer" to light.navy,
            "surfaceContainerHigh" to light.navy2,
        ).forEach { (name, background) ->
            assertTrue(
                "HermesLight green must fail AA on $name before readableOn",
                contrastRatio(light.green, background) < 4.5f,
            )
            val readable = light.green.readableOn(background)
            assertTrue(
                "HermesLight green on $name must clear 4.5:1, was ${contrastRatio(readable, background)}",
                contrastRatio(readable, background) >= 4.5f,
            )
            assertNotEquals("readableOn must replace the failing token on $name", light.green, readable)
        }
    }

    @Test
    fun `already compliant colours are returned unchanged`() {
        // Dark presets clear AA with the authored green, so dark surfaces must
        // stay visually identical to before the fix.
        assertEquals(dark.green, dark.green.readableOn(dark.background))
        assertEquals(dark.green, dark.green.readableOn(dark.navy))
        assertEquals(dark.green, dark.green.readableOn(dark.navy2))
    }

    @Test
    fun `dark backgrounds lighten toward white`() {
        val failing = dark.electric
        assertTrue(
            "precondition: electric must fail AA on the dark background",
            contrastRatio(failing, dark.background) < 4.5f,
        )
        val readable = failing.readableOn(dark.background)
        assertTrue(
            "lightened colour must clear 4.5:1, was ${contrastRatio(readable, dark.background)}",
            contrastRatio(readable, dark.background) >= 4.5f,
        )
        assertTrue("dark backgrounds must be repaired upward", readable.luminance() > failing.luminance())
    }

    @Test
    fun `light backgrounds darken toward black`() {
        val failing = light.green
        val readable = failing.readableOn(light.background)
        assertTrue("light backgrounds must be repaired downward", readable.luminance() < failing.luminance())
    }

    @Test
    fun `min contrast override is honoured`() {
        // Green clears 3:1 on the light background, so a 3:1 request is a no-op.
        assertTrue(contrastRatio(light.green, light.background) >= 3f)
        assertEquals(light.green, light.green.readableOn(light.background, minContrast = 3f))
    }

    @Test
    fun `white on black needs no adjustment`() {
        assertEquals(Color.White, Color.White.readableOn(Color.Black))
    }
}
