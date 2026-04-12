package com.hermesandroid.relay.voice

import com.hermesandroid.relay.network.ChannelMultiplexer

/**
 * === PHASE3-η (googlePlay flavor): factory ===
 *
 * Flavor-specific factory function that [com.hermesandroid.relay.viewmodel.VoiceViewModel]
 * calls exactly once during `initialize()`. The Play build always returns
 * the no-op handler.
 *
 * Both the `googlePlay` and `sideload` flavors export a function with this
 * exact signature + package so `VoiceViewModel` has a single static call
 * site and no reflection.
 *
 * The [multiplexer] parameter is accepted for signature parity with the
 * sideload flavor (which actually uses it to emit bridge envelopes). On
 * Play it is simply ignored.
 */
fun createVoiceBridgeIntentHandler(
    multiplexer: ChannelMultiplexer?,
): VoiceBridgeIntentHandler = NoopVoiceBridgeIntentHandler()

// === END PHASE3-η (googlePlay) ===
