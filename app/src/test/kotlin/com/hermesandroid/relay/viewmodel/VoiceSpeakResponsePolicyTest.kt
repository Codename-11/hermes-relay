package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeakResponsePolicyTest {
    @Test
    fun `settled chat response can speak without active Voice Mode`() {
        assertTrue(
            canSpeakSettledResponse(
                state = VoiceUiState(voiceMode = false, state = VoiceState.Idle),
                providerRealtimeAgentTurnActive = false,
            ),
        )
    }

    @Test
    fun `busy voice output and provider turns reject overlapping speech`() {
        assertFalse(
            canSpeakSettledResponse(
                state = VoiceUiState(voiceMode = false, state = VoiceState.Speaking),
                providerRealtimeAgentTurnActive = false,
            ),
        )
        assertFalse(
            canSpeakSettledResponse(
                state = VoiceUiState(voiceMode = false, state = VoiceState.Idle),
                providerRealtimeAgentTurnActive = true,
            ),
        )
    }

    @Test
    fun `message narration owns completion outside Voice Mode`() {
        assertTrue(ownsVoiceAudioCompletion(voiceMode = false, responseSpeechActive = true))
        assertTrue(ownsVoiceAudioCompletion(voiceMode = true, responseSpeechActive = false))
        assertFalse(ownsVoiceAudioCompletion(voiceMode = false, responseSpeechActive = false))
    }
}
