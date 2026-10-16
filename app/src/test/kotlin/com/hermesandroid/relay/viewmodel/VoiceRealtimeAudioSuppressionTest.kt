package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRealtimeAudioSuppressionTest {
    @Test
    fun stoppedResponseRemainsMutedAfterNewResponseAudio() {
        val fence = RealtimeAudioSuppressionFence()

        fence.suppress(responseId = "response-old")

        assertTrue(fence.shouldSuppress(responseId = "response-old"))
        assertFalse(fence.shouldSuppress(responseId = "response-new"))
        assertTrue(fence.shouldSuppress(responseId = "response-old"))
    }

    @Test
    fun standardVoiceOutputIgnoresRealtimeResponseFence() {
        val fence = RealtimeAudioSuppressionFence().apply {
            suppress(responseId = "response-old")
        }

        assertFalse(
            shouldSuppressRealtimeAudio(
                applyResponseFence = false,
                fastSuppressed = true,
                fence = fence,
                responseId = "response-old",
            ),
        )
    }

    @Test
    fun unknownStoppedResponseFailsClosedUntilSessionReset() {
        val fence = RealtimeAudioSuppressionFence()

        fence.suppress(responseId = null)
        assertTrue(fence.shouldSuppress(responseId = null))
        assertTrue(fence.shouldSuppress(responseId = "response-new"))

        fence.reset()

        assertFalse(fence.shouldSuppress(responseId = null))
        assertFalse(fence.shouldSuppress(responseId = "response-new"))
    }
}
