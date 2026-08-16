package com.hermesandroid.relay.runtime

import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.data.VoiceSettings
import com.hermesandroid.relay.viewmodel.StandardVoiceAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRuntimeReadinessTest {
    @Test
    fun standardRoute_preservesDashboardOnlyReadiness() {
        val readiness = resolveVoiceActivationReadiness(
            settings = VoiceSettings(audioRoute = VoiceAudioRoute.Standard.storageValue),
            chatReady = true,
            standardAvailability = StandardVoiceAvailability.Ready,
            relayReady = false,
            profileSettled = true,
        )

        assertEquals(
            HermesVoiceActivationReadiness.Ready(HermesVoiceActivationRoute.Standard),
            readiness,
        )
    }

    @Test
    fun standardRoute_surfacesManageSignInWithoutWaitingForRelay() {
        val readiness = resolveVoiceActivationReadiness(
            settings = VoiceSettings(audioRoute = VoiceAudioRoute.Standard.storageValue),
            chatReady = true,
            standardAvailability = StandardVoiceAvailability.SignInRequired,
            relayReady = true,
            profileSettled = true,
        )

        assertTrue(readiness is HermesVoiceActivationReadiness.Blocked)
    }

    @Test
    fun autoRoute_matchesRelayFirstAudioRouting() {
        val readiness = resolveVoiceActivationReadiness(
            settings = VoiceSettings(audioRoute = VoiceAudioRoute.Auto.storageValue),
            chatReady = true,
            standardAvailability = StandardVoiceAvailability.Ready,
            relayReady = true,
            profileSettled = true,
        )

        assertEquals(
            HermesVoiceActivationReadiness.Ready(HermesVoiceActivationRoute.RelayAudio),
            readiness,
        )
    }

    @Test
    fun realtimeRoute_usesRelayReadinessAndRetainsSelectedProfileGate() {
        val waitingForProfile = resolveVoiceActivationReadiness(
            settings = VoiceSettings(engineMode = VoiceEngineMode.RealtimeAgent.storageValue),
            chatReady = false,
            standardAvailability = StandardVoiceAvailability.Unknown,
            relayReady = true,
            profileSettled = false,
        )
        val ready = resolveVoiceActivationReadiness(
            settings = VoiceSettings(engineMode = VoiceEngineMode.RealtimeAgent.storageValue),
            chatReady = false,
            standardAvailability = StandardVoiceAvailability.Unknown,
            relayReady = true,
            profileSettled = true,
        )

        assertTrue(waitingForProfile is HermesVoiceActivationReadiness.Waiting)
        assertEquals(
            HermesVoiceActivationReadiness.Ready(HermesVoiceActivationRoute.Realtime),
            ready,
        )
    }
}
