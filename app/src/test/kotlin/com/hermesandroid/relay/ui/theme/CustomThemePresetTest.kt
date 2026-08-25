package com.hermesandroid.relay.ui.theme

import com.hermesandroid.relay.data.AppearancePreferences
import com.hermesandroid.relay.data.CustomThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomThemePresetTest {
    private val aurora = CustomThemePreset(
        id = "aurora",
        name = "  Aurora  ",
        mode = CustomThemePreset.MODE_DARK,
        backgroundHex = "0b0b0f",
        surfaceHex = "#141421",
        accentHex = "#5b6cff",
        textHex = "#f5f6f7",
        shapeId = "balanced",
    )

    @Test
    fun presetNormalizesStablePersistedValues() {
        val normalized = checkNotNull(aurora.normalized())
        assertEquals("Aurora", normalized.name)
        assertEquals("#0B0B0F", normalized.backgroundHex)
        assertEquals("#5B6CFF", normalized.accentHex)
        assertEquals("balanced", normalized.shapeId)
        assertTrue(normalized.isDark)
        assertEquals("custom:aurora", normalized.appThemeId)
    }

    @Test
    fun invalidPresetFailsClosed() {
        assertNull(aurora.copy(id = "../../escape").normalized())
        assertNull(aurora.copy(textHex = "not-a-color").normalized())
    }

    @Test
    fun persistenceIsBoundedAndRoundTrips() {
        val themes = (0 until CustomThemePreset.MAX_PRESETS + 5).map { index ->
            aurora.copy(id = "theme-$index", name = "Theme $index")
        }
        val decoded = AppearancePreferences.decodeCustomThemes(
            AppearancePreferences.encodeCustomThemes(themes),
        )
        assertEquals(CustomThemePreset.MAX_PRESETS, decoded.size)
        assertEquals("theme-0", decoded.first().id)
        assertNull(AppearancePreferences.upsertCustomTheme(decoded, aurora.copy(id = "overflow")))
        val replaced = checkNotNull(
            AppearancePreferences.upsertCustomTheme(decoded, decoded.first().copy(name = "Updated")),
        )
        assertEquals("Updated", replaced.first().name)
    }

    @Test
    fun customPaletteUsesAuthoredCoreRoles() {
        val palette = checkNotNull(aurora.normalized()).toBrandPalette()
        assertEquals(accentColor("#0B0B0F"), palette.background)
        assertEquals(accentColor("#141421"), palette.navy2)
        assertEquals(accentColor("#5B6CFF"), palette.electric)
        assertEquals(accentColor("#F5F6F7"), palette.ink)
        assertTrue(palette.isDark)
    }
}
