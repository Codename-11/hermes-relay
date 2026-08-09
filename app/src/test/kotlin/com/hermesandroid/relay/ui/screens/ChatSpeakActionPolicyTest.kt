package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.viewmodel.VoiceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSpeakActionPolicyTest {
    @Test
    fun `configured idle voice offers Speak outside Voice Mode`() {
        assertTrue(shouldOfferChatSpeakAction(voiceReady = true, voiceState = VoiceState.Idle))
    }

    @Test
    fun `unconfigured or busy voice does not offer Speak`() {
        assertFalse(shouldOfferChatSpeakAction(voiceReady = false, voiceState = VoiceState.Idle))
        assertFalse(shouldOfferChatSpeakAction(voiceReady = true, voiceState = VoiceState.Speaking))
    }
}
