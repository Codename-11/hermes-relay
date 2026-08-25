package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceModeControlTest {
    @Test
    fun `dual mode theme displays persisted preference`() {
        assertEquals("auto", resolvedAppearanceModeSelection("auto", ThemeMode.BOTH))
        assertEquals("light", resolvedAppearanceModeSelection("light", ThemeMode.BOTH))
        assertEquals("dark", resolvedAppearanceModeSelection("dark", ThemeMode.BOTH))
    }

    @Test
    fun `fixed themes display their actual mode`() {
        assertEquals("light", resolvedAppearanceModeSelection("auto", ThemeMode.LIGHT_ONLY))
        assertEquals("dark", resolvedAppearanceModeSelection("auto", ThemeMode.DARK_ONLY))
    }
}
