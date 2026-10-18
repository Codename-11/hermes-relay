package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLifecycleChimePolicyTest {

    @Test
    fun ordinaryInAppVoiceModeKeepsLifecycleChimes() {
        assertTrue(shouldPlayVoiceModeLifecycleChime(assistantActivationId = null))
    }

    @Test
    fun androidAssistantActivationSuppressesLifecycleChimes() {
        assertFalse(shouldPlayVoiceModeLifecycleChime(assistantActivationId = "activation-123"))
    }
}
