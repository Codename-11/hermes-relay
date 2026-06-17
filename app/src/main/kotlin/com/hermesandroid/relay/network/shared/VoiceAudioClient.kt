package com.hermesandroid.relay.network.shared

import com.hermesandroid.relay.data.VoiceAudioRoute
import java.io.File

/**
 * Transport-neutral STT/TTS contract. The routing seam between the Standard
 * (dashboard) and Relay voice clients — implementations live in `network.upstream`
 * (`StandardHermesVoiceClient`) and `network.relay` (`RelayVoiceAudioClientAdapter`),
 * while this interface and the [AutoVoiceAudioClient] router stay dependency-neutral
 * so neither voice backend leaks across the upstream/relay package fence (ADR 34).
 */
interface VoiceAudioClient {
    val route: VoiceAudioRoute
    suspend fun transcribe(audioFile: File): Result<String>
    suspend fun synthesize(text: String): Result<File>
}

/**
 * Routes each STT/TTS call to the Standard (dashboard) or Relay voice client.
 *
 * Auto preference order is **Relay first, then Standard**: a paired Relay is
 * the purpose-built mobile facade — profile-aware voice config, no dashboard
 * sign-in dependency — so users who installed the plugin keep the richer
 * path. Standard is the zero-plugin route for vanilla Hermes installs and is
 * used whenever Relay isn't configured/paired (or fails mid-call). Power
 * users can force either route in Voice Settings.
 *
 * Depends only on the [VoiceAudioClient] abstraction (both backends are passed
 * in as the interface), so this router carries no upstream or relay imports.
 */
class AutoVoiceAudioClient(
    private val standardClient: VoiceAudioClient,
    private val relayClient: VoiceAudioClient,
    private val routeProvider: () -> VoiceAudioRoute,
    private val standardReadyProvider: () -> Boolean,
    private val relayReadyProvider: () -> Boolean,
) : VoiceAudioClient {
    override val route: VoiceAudioRoute
        get() = routeProvider()

    override suspend fun transcribe(audioFile: File): Result<String> =
        runWithSelectedRoute { it.transcribe(audioFile) }

    override suspend fun synthesize(text: String): Result<File> =
        runWithSelectedRoute { it.synthesize(text) }

    private suspend fun <T> runWithSelectedRoute(
        block: suspend (VoiceAudioClient) -> Result<T>,
    ): Result<T> {
        return when (routeProvider()) {
            VoiceAudioRoute.Standard -> {
                if (!standardReadyProvider()) {
                    Result.failure(
                        IllegalStateException(
                            "Standard Hermes voice is not available — check dashboard sign-in in Manage",
                        ),
                    )
                } else {
                    block(standardClient)
                }
            }
            VoiceAudioRoute.Relay -> {
                if (!relayReadyProvider()) {
                    Result.failure(IllegalStateException("Relay voice is not available"))
                } else {
                    block(relayClient)
                }
            }
            VoiceAudioRoute.Auto -> runAuto(block)
        }
    }

    private suspend fun <T> runAuto(
        block: suspend (VoiceAudioClient) -> Result<T>,
    ): Result<T> {
        var relayFailure: Result<T>? = null
        if (relayReadyProvider()) {
            val result = block(relayClient)
            if (result.isSuccess || !standardReadyProvider()) return result
            relayFailure = result
        }
        if (standardReadyProvider()) {
            val result = block(standardClient)
            if (result.isSuccess) return result
            return relayFailure ?: result
        }
        return relayFailure ?: Result.failure(
            IllegalStateException("Voice needs a reachable Hermes dashboard or Relay voice route"),
        )
    }
}
