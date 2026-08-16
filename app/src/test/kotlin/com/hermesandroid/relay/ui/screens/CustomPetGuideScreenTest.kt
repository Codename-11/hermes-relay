package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomPetGuideScreenTest {
    @Test
    fun `animated prompt preserves review and privacy boundaries`() {
        val prompt = buildCustomPetPrompt("Mochi", "a tiny violet fox", CustomPetKind.ANIMATED)

        assertTrue(prompt.contains("Mochi"))
        assertTrue(prompt.contains("tiny violet fox"))
        assertTrue(prompt.contains("pet.json"))
        assertTrue(prompt.contains("Do not upload"))
        assertTrue(prompt.contains("Do not install, publish, or share it automatically"))
        assertTrue(prompt.contains("attach the final PNG/WebP or ZIP"))
    }

    @Test
    fun `static prompt requests one image without sprite pack requirements`() {
        val prompt = buildCustomPetPrompt("", "", CustomPetKind.STATIC)

        assertTrue(prompt.contains("single static image"))
        assertTrue(prompt.contains("one transparent PNG or WebP image"))
        assertFalse(prompt.contains("Include idle, walk-left/right"))
    }
}
