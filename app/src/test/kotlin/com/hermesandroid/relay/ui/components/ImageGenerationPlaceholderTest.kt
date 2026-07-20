package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationPlaceholderTest {

    @Test
    fun `active image generation uses diffusion placeholder`() {
        val active = ToolCall(
            id = "image-1",
            name = " image_generate ",
            args = null,
            result = null,
            success = null,
        )

        assertTrue(active.showsImageGenerationPlaceholder())
        assertFalse(active.copy(isComplete = true, success = true).showsImageGenerationPlaceholder())
        assertFalse(active.copy(name = "video_generate").showsImageGenerationPlaceholder())
    }

    @Test
    fun `diffusion field changes across the animation cycle`() {
        val noisy = diffusionSignal(column = 12, row = 8, time = 0.25f, denoise = diffusionDenoise(0.08f))
        val resolved = diffusionSignal(column = 12, row = 8, time = 3.5f, denoise = diffusionDenoise(0.62f))

        assertTrue(noisy in 0f..1f)
        assertTrue(resolved in 0f..1f)
        assertNotEquals(noisy, resolved)
    }

    @Test
    fun `denoise cycle resolves then resets`() {
        val early = diffusionDenoise(0.05f)
        val resolved = diffusionDenoise(0.82f)
        val reset = diffusionDenoise(0.99f)

        assertTrue(early < resolved)
        assertTrue(reset < resolved)
    }
}
