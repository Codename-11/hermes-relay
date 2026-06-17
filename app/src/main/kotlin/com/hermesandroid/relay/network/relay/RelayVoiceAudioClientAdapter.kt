package com.hermesandroid.relay.network.relay

import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.network.shared.VoiceAudioClient
import java.io.File

/**
 * Adapts the relay-only [RelayVoiceClient] (same package) to the neutral
 * [VoiceAudioClient] routing seam in `network.shared`. Relay → shared is an
 * allowed dependency direction under the ADR 34 package fence.
 */
class RelayVoiceAudioClientAdapter(
    private val relayVoiceClient: RelayVoiceClient,
) : VoiceAudioClient {
    override val route: VoiceAudioRoute = VoiceAudioRoute.Relay

    override suspend fun transcribe(audioFile: File): Result<String> =
        relayVoiceClient.transcribe(audioFile)

    override suspend fun synthesize(text: String): Result<File> =
        relayVoiceClient.synthesize(text)
}
