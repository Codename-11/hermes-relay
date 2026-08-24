package com.hermesandroid.relay.network.shared

import com.hermesandroid.relay.data.VoiceAudioRoute
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoVoiceAudioClientSupervisionTest {
    @Test
    fun `route override forces standard even when auto prefers ready relay`() = runTest {
        val standard = FakeVoiceClient(VoiceAudioRoute.Standard, "standard")
        val relay = FakeVoiceClient(VoiceAudioRoute.Relay, "relay")
        val router = AutoVoiceAudioClient(
            standardClient = standard,
            relayClient = relay,
            routeProvider = { VoiceAudioRoute.Auto },
            standardReadyProvider = { true },
            relayReadyProvider = { true },
        )

        assertEquals("relay", router.transcribe(File("voice.wav")).getOrThrow())
        router.setRouteOverride(VoiceAudioRoute.Standard)
        assertEquals(VoiceAudioRoute.Standard, router.effectiveRoute)
        assertEquals("standard", router.transcribe(File("voice.wav")).getOrThrow())
        router.setRouteOverride(null)
        assertEquals("relay", router.transcribe(File("voice.wav")).getOrThrow())
    }

    private class FakeVoiceClient(
        override val route: VoiceAudioRoute,
        private val transcript: String,
    ) : VoiceAudioClient {
        override suspend fun transcribe(audioFile: File): Result<String> = Result.success(transcript)
        override suspend fun synthesize(text: String): Result<File> = Result.success(File("voice.mp3"))
    }
}
