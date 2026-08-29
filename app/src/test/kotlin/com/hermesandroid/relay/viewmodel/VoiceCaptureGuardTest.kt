package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.assistant.AssistantSessionNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureGuardTest {

    @Test
    fun discardsQuickReleaseBeforeSendingAudio() {
        assertTrue(shouldDiscardVoiceCapture(durationMs = 120L, pcmBytes = 8_000))
    }

    @Test
    fun discardsEmptyRecorderOutput() {
        assertTrue(shouldDiscardVoiceCapture(durationMs = 600L, pcmBytes = 0))
    }

    @Test
    fun acceptsSettledShortUtterance() {
        assertFalse(shouldDiscardVoiceCapture(durationMs = 420L, pcmBytes = 12_000))
    }

    @Test
    fun noSpeechReturnsToRetryableIdleWithDurableFeedback() {
        val state = voiceNoSpeechState(
            VoiceUiState(
                voiceMode = true,
                state = VoiceState.Transcribing,
                transcribedText = "stale",
            )
        )

        assertEquals(VoiceState.Idle, state.state)
        assertEquals(AssistantSessionNotice.NoSpeech, state.assistantNotice)
        assertNull(state.error)
        assertNull(state.transcribedText)
        assertTrue(state.voiceMode)
    }

    @Test
    fun unrelatedCaptureCancellationDoesNotClaimNoSpeech() {
        val state = voiceCaptureCancellationState(
            VoiceUiState(
                voiceMode = true,
                state = VoiceState.Listening,
                assistantNotice = AssistantSessionNotice.NoSpeech,
            )
        )

        assertEquals(VoiceState.Idle, state.state)
        assertNull(state.assistantNotice)
    }
}
