package com.hermesandroid.relay.ui.components.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetAvatarRuntimeTest {
    @Test
    fun `clip transition retains the previous complete visual until decode finishes`() {
        assertEquals(
            "idle",
            retainPetFrameDuringDecode(
                previous = "idle",
                decoded = null,
                hasRequestedClip = true,
            ),
        )
        assertEquals(
            "walking",
            retainPetFrameDuringDecode(
                previous = "idle",
                decoded = "walking",
                hasRequestedClip = true,
            ),
        )
        assertNull(
            retainPetFrameDuringDecode(
                previous = "idle",
                decoded = null,
                hasRequestedClip = false,
            ),
        )
    }

    @Test
    fun `decode pixel budget rejects invalid oversized and cumulative overflow images`() {
        assertTrue(petBitmapFitsPixelBudget(1536, 2288, 0L, PET_MAX_SPRITE_SHEET_PIXELS))
        assertFalse(petBitmapFitsPixelBudget(20_000, 20_000, 0L, PET_MAX_SPRITE_SHEET_PIXELS))
        assertFalse(petBitmapFitsPixelBudget(0, 208, 0L, PET_MAX_SPRITE_SHEET_PIXELS))
        assertTrue(petBitmapFitsPixelBudget(1024, 1024, 7L * 1024L * 1024L, PET_MAX_FRAME_SEQUENCE_PIXELS))
        assertFalse(petBitmapFitsPixelBudget(1024, 1024, 8L * 1024L * 1024L, PET_MAX_FRAME_SEQUENCE_PIXELS))
    }
}
