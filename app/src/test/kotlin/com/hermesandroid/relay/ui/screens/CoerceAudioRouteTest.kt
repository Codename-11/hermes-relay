package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.data.VoiceEngineMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the top-level [coerceAudioRoute] helper in
 * `VoiceSettingsScreen.kt`.
 *
 * Contract (see the helper's KDoc):
 *  - [VoiceAudioRoute.Relay] with `relayVoiceReady == false` coerces to
 *    [VoiceAudioRoute.Auto] (a stale Relay pick after Relay was unpaired must
 *    not stay persisted).
 *  - Every other (engine, route, ready) combination passes the route through
 *    unchanged — the engine argument never influences the audio-route result.
 */
class CoerceAudioRouteTest {

    // --- Relay + not ready → Auto -------------------------------------------

    @Test
    fun relayWhenNotReady_coercesToAuto_hermesEngine() {
        assertEquals(
            VoiceAudioRoute.Auto,
            coerceAudioRoute(
                engine = VoiceEngineMode.HermesVoiceOutput,
                route = VoiceAudioRoute.Relay,
                relayVoiceReady = false,
            ),
        )
    }

    @Test
    fun relayWhenNotReady_coercesToAuto_realtimeEngine() {
        assertEquals(
            VoiceAudioRoute.Auto,
            coerceAudioRoute(
                engine = VoiceEngineMode.RealtimeAgent,
                route = VoiceAudioRoute.Relay,
                relayVoiceReady = false,
            ),
        )
    }

    // --- Relay + ready → Relay (unchanged) ----------------------------------

    @Test
    fun relayWhenReady_passesThrough_hermesEngine() {
        assertEquals(
            VoiceAudioRoute.Relay,
            coerceAudioRoute(
                engine = VoiceEngineMode.HermesVoiceOutput,
                route = VoiceAudioRoute.Relay,
                relayVoiceReady = true,
            ),
        )
    }

    @Test
    fun relayWhenReady_passesThrough_realtimeEngine() {
        assertEquals(
            VoiceAudioRoute.Relay,
            coerceAudioRoute(
                engine = VoiceEngineMode.RealtimeAgent,
                route = VoiceAudioRoute.Relay,
                relayVoiceReady = true,
            ),
        )
    }

    // --- Standard always passes through, regardless of readiness ------------

    @Test
    fun standard_passesThrough_whenRelayNotReady() {
        for (engine in VoiceEngineMode.values()) {
            assertEquals(
                "Standard must never be coerced (engine=$engine, ready=false)",
                VoiceAudioRoute.Standard,
                coerceAudioRoute(engine, VoiceAudioRoute.Standard, relayVoiceReady = false),
            )
        }
    }

    @Test
    fun standard_passesThrough_whenRelayReady() {
        for (engine in VoiceEngineMode.values()) {
            assertEquals(
                "Standard must never be coerced (engine=$engine, ready=true)",
                VoiceAudioRoute.Standard,
                coerceAudioRoute(engine, VoiceAudioRoute.Standard, relayVoiceReady = true),
            )
        }
    }

    // --- Auto always passes through (it self-resolves at runtime) -----------

    @Test
    fun auto_passesThrough_regardlessOfReadiness() {
        for (engine in VoiceEngineMode.values()) {
            for (ready in listOf(true, false)) {
                assertEquals(
                    "Auto is always valid (engine=$engine, ready=$ready)",
                    VoiceAudioRoute.Auto,
                    coerceAudioRoute(engine, VoiceAudioRoute.Auto, relayVoiceReady = ready),
                )
            }
        }
    }
}
