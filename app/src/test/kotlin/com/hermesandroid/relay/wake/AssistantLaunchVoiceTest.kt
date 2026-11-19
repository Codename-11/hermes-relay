package com.hermesandroid.relay.wake

import com.hermesandroid.relay.assistant.assistantLaunchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantLaunchVoiceTest {
    @Test
    fun voiceFirstDefault_isOff() {
        assertFalse(WakeWordPreferences().assistLaunchVoice)
    }

    @Test
    fun assistLaunch_withVoiceFirst_usesDirectVoiceSource() {
        assertEquals(
            WakeWordActivationSource.AssistantLaunch,
            assistantLaunchSource(voiceFirst = true),
        )
    }

    @Test
    fun assistLaunch_withoutVoiceFirst_keepsPlainChatSource() {
        assertEquals(
            WakeWordActivationSource.SystemAssistant,
            assistantLaunchSource(voiceFirst = false),
        )
    }

    @Test
    fun onlySystemAssistantSource_skipsVoiceStart() {
        assertFalse(WakeWordActivationSource.SystemAssistant.startsVoiceInApp)
        assertTrue(WakeWordActivationSource.AssistantLaunch.startsVoiceInApp)
        assertTrue(WakeWordActivationSource.ForegroundService.startsVoiceInApp)
    }
}
