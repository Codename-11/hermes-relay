package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.network.shared.GptLiveStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSettingsLayoutTest {
    private val gptLive = GptLiveStatus(mode = "gpt-live", available = true)
    private val chained = GptLiveStatus(mode = "chained", available = false)

    @Test
    fun `hermes voice waits for the host before choosing cards`() {
        assertEquals(
            VoiceSettingsLayout.Loading,
            voiceSettingsLayout(VoiceEngineMode.HermesVoiceOutput, false, null, true),
        )
    }

    @Test
    fun `host gpt-live shows the gpt-live page even when unavailable`() {
        assertEquals(
            VoiceSettingsLayout.GptLive,
            voiceSettingsLayout(VoiceEngineMode.HermesVoiceOutput, true, gptLive, false),
        )
        assertEquals(
            VoiceSettingsLayout.GptLive,
            voiceSettingsLayout(VoiceEngineMode.HermesVoiceOutput, true, gptLive.copy(available = false), false),
        )
    }

    @Test
    fun `chained or unreachable host shows hermes voice cards`() {
        assertEquals(
            VoiceSettingsLayout.HermesVoice,
            voiceSettingsLayout(VoiceEngineMode.HermesVoiceOutput, true, chained, true),
        )
        assertEquals(
            VoiceSettingsLayout.HermesVoice,
            voiceSettingsLayout(VoiceEngineMode.HermesVoiceOutput, true, null, true),
        )
    }

    @Test
    fun `realtime agent needs a paired relay and ignores gpt-live`() {
        assertEquals(
            VoiceSettingsLayout.RealtimeAgent,
            voiceSettingsLayout(VoiceEngineMode.RealtimeAgent, false, gptLive, true),
        )
        assertEquals(
            VoiceSettingsLayout.None,
            voiceSettingsLayout(VoiceEngineMode.RealtimeAgent, true, null, false),
        )
    }
}
