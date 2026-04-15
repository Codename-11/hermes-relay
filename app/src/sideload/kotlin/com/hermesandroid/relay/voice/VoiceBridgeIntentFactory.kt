package com.hermesandroid.relay.voice

import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.models.Envelope

/**
 * Local in-process bridge dispatcher type. Voice intents call this instead
 * of `multiplexer.send(envelope)` so the action runs through the same
 * `BridgeCommandHandler` dispatch + Tier 5 safety pipeline without
 * round-tripping over WSS. Wired in `RelayApp.kt` to
 * `connectionViewModel.bridgeCommandHandler::handleLocalCommand`. See the
 * `BridgeCommandHandler.handleLocalCommand` KDoc for the full rationale.
 */
typealias LocalBridgeDispatcher = suspend (Envelope) -> Unit

/**
 * === PHASE3-voice-intents (sideload flavor): factory ===
 *
 * Flavor-specific factory function that [com.hermesandroid.relay.viewmodel.VoiceViewModel]
 * calls exactly once during `initialize()`. The sideload build returns the
 * real classifier-backed handler that dispatches bridge actions in-process
 * via the supplied [localBridgeDispatcher].
 *
 * Both the `googlePlay` and `sideload` flavors export a function with this
 * exact signature + package so `VoiceViewModel` has a single static call
 * site and no reflection.
 *
 * The [multiplexer] parameter is retained for signature parity with the
 * googlePlay flavor and for future non-bridge envelopes that might need to
 * go over the relay. Bridge actions specifically use [localBridgeDispatcher].
 */
fun createVoiceBridgeIntentHandler(
    multiplexer: ChannelMultiplexer?,
    localBridgeDispatcher: LocalBridgeDispatcher? = null,
): VoiceBridgeIntentHandler = RealVoiceBridgeIntentHandler(
    multiplexer = multiplexer,
    localBridgeDispatcher = localBridgeDispatcher,
)

// === END PHASE3-voice-intents (sideload factory) ===
