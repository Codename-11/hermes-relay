package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.SupervisedCapabilities
import com.hermesandroid.relay.data.SupervisedModePolicy
import com.hermesandroid.relay.voice.VoiceCommandAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisedVoiceCommandPolicyTest {
    @Test
    fun `normal mode preserves every voice command`() {
        VoiceCommandAction.entries.forEach { action ->
            assertTrue(isVoiceCommandAllowed(action, SupervisedModePolicy()))
        }
    }

    @Test
    fun `supervised mode gates new chat and cancellation independently`() {
        val policy = SupervisedModePolicy(
            enabled = true,
            pinnedProfileName = "willow",
            capabilities = SupervisedCapabilities(
                voice = true,
                newChat = false,
                cancelResponse = false,
            ),
        )

        assertFalse(isVoiceCommandAllowed(VoiceCommandAction.StartNewChat, policy))
        assertFalse(isVoiceCommandAllowed(VoiceCommandAction.StopResponse, policy))
        assertFalse(isVoiceCommandAllowed(VoiceCommandAction.CancelBackgroundTask, policy))
        assertTrue(isVoiceCommandAllowed(VoiceCommandAction.EndVoiceChat, policy))
    }
}
