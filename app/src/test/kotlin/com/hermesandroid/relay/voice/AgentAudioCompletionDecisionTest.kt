package com.hermesandroid.relay.voice

import com.hermesandroid.relay.viewmodel.decideAgentAudioCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAudioCompletionDecisionTest {

    @Test
    fun `does not finish before assistant stream stops`() {
        val decision = decideAgentAudioCompletion(
            voiceMode = true,
            observerStopped = false,
            pendingTtsWork = 0,
            hasPendingSynthFiles = false,
            realtimePlaybackRemainingMs = 0,
            realtimeTailGuardRemainingMs = 0,
        )

        assertFalse(decision.finishNow)
        assertNull(decision.retryDelayMs)
    }

    @Test
    fun `retries while tts pipeline still has work`() {
        val decision = decideAgentAudioCompletion(
            voiceMode = true,
            observerStopped = true,
            pendingTtsWork = 1,
            hasPendingSynthFiles = false,
            realtimePlaybackRemainingMs = 0,
            realtimeTailGuardRemainingMs = 0,
        )

        assertFalse(decision.finishNow)
        assertEquals(100L, decision.retryDelayMs)
    }

    @Test
    fun `retries for provider streamed pcm drain in any interaction mode`() {
        val decision = decideAgentAudioCompletion(
            voiceMode = true,
            observerStopped = true,
            pendingTtsWork = 0,
            hasPendingSynthFiles = false,
            realtimePlaybackRemainingMs = 1_250,
            realtimeTailGuardRemainingMs = 350,
        )

        assertFalse(decision.finishNow)
        assertEquals(1_250L, decision.retryDelayMs)
    }

    @Test
    fun `finishes when stream tts and audio are drained`() {
        val decision = decideAgentAudioCompletion(
            voiceMode = true,
            observerStopped = true,
            pendingTtsWork = 0,
            hasPendingSynthFiles = false,
            realtimePlaybackRemainingMs = 0,
            realtimeTailGuardRemainingMs = 0,
        )

        assertTrue(decision.finishNow)
        assertNull(decision.retryDelayMs)
    }
}
