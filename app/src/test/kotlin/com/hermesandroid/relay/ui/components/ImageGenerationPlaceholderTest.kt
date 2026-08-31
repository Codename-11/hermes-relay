package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationPlaceholderTest {

    @Test
    fun `duration label is stable and clamps negative elapsed time`() {
        assertEquals("12.4s", formatGenerationDuration(12_440))
        assertEquals("0.0s", formatGenerationDuration(-100))
    }

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
        assertTrue(active.copy(name = "willow_create_image").showsImageGenerationPlaceholder())
        assertFalse(active.copy(isComplete = true, success = true).showsImageGenerationPlaceholder())
        assertFalse(active.copy(name = "video_generate").showsImageGenerationPlaceholder())
    }

    @Test
    fun `active image generation remains visible when generic tools are hidden`() {
        val active = ToolCall(
            id = "image-1",
            name = "image_generate",
            args = null,
            result = null,
            success = null,
        )

        assertTrue(active.isVisibleForToolDisplay("off"))
        assertFalse(active.copy(name = "terminal").isVisibleForToolDisplay("off"))
        assertFalse(active.copy(isComplete = true).isVisibleForToolDisplay("off"))
        assertTrue(active.copy(name = "terminal").isVisibleForToolDisplay("compact"))
    }

    @Test
    fun `image progress owns the bubble until a media result arrives`() {
        val active = ToolCall(
            id = "image-1",
            name = "image_generate",
            args = null,
            result = null,
            success = null,
        )
        val completed = active.copy(isComplete = true, success = true)

        assertTrue(
            shouldShowImageGenerationPlaceholder(
                toolCalls = listOf(active),
                isStreaming = true,
                hasMediaResult = false,
            )
        )
        assertTrue(
            shouldShowImageGenerationPlaceholder(
                toolCalls = listOf(completed),
                isStreaming = true,
                hasMediaResult = false,
            )
        )
        assertFalse(
            shouldShowImageGenerationPlaceholder(
                toolCalls = listOf(completed),
                isStreaming = true,
                hasMediaResult = true,
            )
        )
    }

    @Test
    fun `completed or failed turn cannot leave a stale image canvas`() {
        val completed = ToolCall(
            id = "image-1",
            name = "image_generate",
            args = null,
            result = null,
            success = true,
            isComplete = true,
        )

        assertFalse(
            shouldShowImageGenerationPlaceholder(
                toolCalls = listOf(completed),
                isStreaming = false,
                hasMediaResult = false,
            )
        )
        assertFalse(
            shouldShowImageGenerationPlaceholder(
                toolCalls = listOf(completed.copy(success = false)),
                isStreaming = true,
                hasMediaResult = false,
            )
        )
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

    @Test
    fun `rubiks sphere animates exactly one outer slice at a time`() {
        (0..100).forEach { frame ->
            val angles = rubiksSliceAngles(frame / 100f)
            val activeSlices = listOf(angles.topY, angles.frontZ, angles.rightX)
                .count { kotlin.math.abs(it) > 0.0001f }

            assertTrue("overlapping slices at frame $frame", activeSlices <= 1)
        }
    }

    @Test
    fun `rotate preference cycles all image generation styles`() {
        assertEquals(
            ImageGenerationVisualStyle.LatentGrid,
            resolveImageGenerationVisualStyle("rotate", 0),
        )
        assertEquals(
            ImageGenerationVisualStyle.ParticleOrb,
            resolveImageGenerationVisualStyle("rotate", 1),
        )
        assertEquals(
            ImageGenerationVisualStyle.Constellation,
            resolveImageGenerationVisualStyle("rotate", 2),
        )
        assertEquals(
            ImageGenerationVisualStyle.LatentGrid,
            resolveImageGenerationVisualStyle("rotate", 3),
        )
    }

    @Test
    fun `pinned image generation preference ignores rotation index`() {
        assertEquals(
            ImageGenerationVisualStyle.ParticleOrb,
            resolveImageGenerationVisualStyle("sphere", 99),
        )
        assertEquals(
            ImageGenerationVisualStyle.Constellation,
            resolveImageGenerationVisualStyle("nodes", 0),
        )
    }
}
