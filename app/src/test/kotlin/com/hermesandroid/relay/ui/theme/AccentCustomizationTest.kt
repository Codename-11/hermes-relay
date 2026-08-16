package com.hermesandroid.relay.ui.theme

import androidx.compose.ui.graphics.Color
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
}
