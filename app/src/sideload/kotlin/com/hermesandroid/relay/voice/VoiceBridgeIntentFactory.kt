package com.hermesandroid.relay.voice

import com.hermesandroid.relay.network.ChannelMultiplexer

/**
 * === PHASE3-voice-intents (sideload flavor): factory ===
 *
 * Flavor-specific factory function that [com.hermesandroid.relay.viewmodel.VoiceViewModel]
 * calls exactly once during `initialize()`. The sideload build returns the
 * real classifier-backed handler that emits bridge envelopes.
 *
 * Both the `googlePlay` and `sideload` flavors export a function with this
 * exact signature + package so `VoiceViewModel` has a single static call
 * site and no reflection.
 */
fun createVoiceBridgeIntentHandler(
    multiplexer: ChannelMultiplexer?,
): VoiceBridgeIntentHandler = RealVoiceBridgeIntentHandler(multiplexer)

// === END PHASE3-voice-intents (sideload factory) ===
