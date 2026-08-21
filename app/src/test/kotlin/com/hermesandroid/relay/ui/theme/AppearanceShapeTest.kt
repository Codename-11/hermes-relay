package com.hermesandroid.relay.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceShapeTest {
    @Test
    fun `material token mapping remains stable for every mode`() {
        assertEquals(listOf(8.dp, 12.dp, 18.dp, 24.dp, 30.dp), appearanceShapeScale("soft").tokens())
        assertEquals(listOf(5.dp, 8.dp, 12.dp, 16.dp, 20.dp), appearanceShapeScale("balanced").tokens())
        assertEquals(listOf(2.dp, 4.dp, 6.dp, 8.dp, 10.dp), appearanceShapeScale("sharp").tokens())
    }

    @Test
    fun `semantic radii preserve hierarchy while soft preserves shipped values`() {
        assertEquals(24.dp, appearanceShapeScale("soft").radius(24.dp))
        assertEquals(16.dp, appearanceShapeScale("balanced").radius(24.dp))
        assertEquals(8.dp, appearanceShapeScale("sharp").radius(24.dp))

        AppearanceShape.entries.forEach { mode ->
            val scale = appearanceShapeScale(mode.id)
            assert(scale.radius(8.dp) < scale.radius(12.dp))
            assert(scale.radius(12.dp) < scale.radius(24.dp))
        }
    }

    @Test
    fun `unknown persisted mode falls back to soft`() {
        assertEquals(AppearanceShape.SOFT, appearanceShapeScale("future-mode").mode)
    }

    @Test
    fun `composer uses a constrained semantic range`() {
        assertEquals(20.dp, appearanceShapeScale("soft").composerRadius())
        assertEquals(18.dp, appearanceShapeScale("balanced").composerRadius())
        assertEquals(16.dp, appearanceShapeScale("sharp").composerRadius())
    }

    private fun AppearanceShapeScale.tokens() = listOf(extraSmall, small, medium, large, extraLarge)
}
