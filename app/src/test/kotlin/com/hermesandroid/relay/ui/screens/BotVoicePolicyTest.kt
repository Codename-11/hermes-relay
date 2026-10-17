package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.data.VoiceSettings
import com.hermesandroid.relay.viewmodel.gatewayVoiceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotVoicePolicyTest {
    @Test
    fun voiceWaitsForExactCanonicalSessionAndLoadedHistory() {
        assertFalse(botVoiceReady("bot-session", "main-session", false, null))
        assertFalse(botVoiceReady("bot-session", null, false, null))
        assertFalse(botVoiceReady("bot-session", "bot-session", true, null))
        assertFalse(botVoiceReady("bot-session", "bot-session", false, "History unavailable"))
        assertFalse(botVoiceReady("", "", false, null))
        assertTrue(botVoiceReady("bot-session", "bot-session", false, null))
    }

    @Test
    fun botCallsKeepRelayEnginesOutOfGatewaySession() {
        assertEquals(VoiceEngineMode.HermesVoiceOutput,
            gatewayVoiceEngine(VoiceEngineMode.RealtimeAgent.storageValue, true))
        assertEquals(VoiceEngineMode.HermesVoiceOutput,
            gatewayVoiceEngine(VoiceEngineMode.HermesVoiceOutput.storageValue, true))
        assertEquals(VoiceEngineMode.RealtimeAgent,
            gatewayVoiceEngine(VoiceEngineMode.RealtimeAgent.storageValue, false))
    }

    @Test
    fun unsupportedSelectionsShowFallbackWithoutChangingSavedSettings() {
        val realtime = VoiceSettings(engineMode = VoiceEngineMode.RealtimeAgent.storageValue)
        val relayAudio = VoiceSettings(audioRoute = VoiceAudioRoute.Relay.storageValue)
        assertTrue(botVoiceUsesStandardFallback(realtime))
        assertTrue(botVoiceUsesStandardFallback(relayAudio))
        assertFalse(botVoiceUsesStandardFallback(VoiceSettings()))
        assertEquals(VoiceEngineMode.RealtimeAgent.storageValue, realtime.engineMode)
        assertEquals(VoiceAudioRoute.Relay.storageValue, relayAudio.audioRoute)
    }
}
