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
    fun armedContinuousRearmsAfterNoSpeechOrDiscard() {
        val idle = voiceCaptureCancellationState(
            VoiceUiState(voiceMode = true, interactionMode = InteractionMode.Continuous,
                state = VoiceState.Listening),
            AssistantSessionNotice.NoSpeech,
        )
        assertEquals(VoiceState.Idle, idle.state)
        assertTrue(shouldRearmContinuousCapture(idle, armed = true, paused = false))
    }

    @Test
    fun pausedExitedAndOtherModesMustNotRearm() {
        val idle = VoiceUiState(
            voiceMode = true, interactionMode = InteractionMode.Continuous,
            state = VoiceState.Idle,
        )
        assertFalse(shouldRearmContinuousCapture(idle, armed = false, paused = false))
        assertFalse(shouldRearmContinuousCapture(idle, armed = true, paused = true))
        assertFalse(shouldRearmContinuousCapture(idle.copy(voiceMode = false), true, false))
        assertFalse(shouldRearmContinuousCapture(idle.copy(state = VoiceState.Thinking), true, false))
        assertFalse(shouldRearmContinuousCapture(idle.copy(interactionMode = InteractionMode.TapToTalk), true, false))
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
