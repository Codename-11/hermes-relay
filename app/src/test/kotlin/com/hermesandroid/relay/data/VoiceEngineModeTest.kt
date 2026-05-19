package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceEngineModeTest {

    @Test
    fun fromStorage_defaultsToStableEngine() {
        assertEquals(
            VoiceEngineMode.HermesVoiceOutput,
            VoiceEngineMode.fromStorage(null),
        )
        assertEquals(
            VoiceEngineMode.HermesVoiceOutput,
            VoiceEngineMode.fromStorage("missing"),
        )
    }

    @Test
    fun fromStorage_readsRealtimeAgent() {
        assertEquals(
            VoiceEngineMode.RealtimeAgent,
            VoiceEngineMode.fromStorage("realtime_agent"),
        )
    }
}
