package com.hermesandroid.relay.voice

import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.handlers.LocalDispatchResult
import com.hermesandroid.relay.network.models.Envelope

/**
 * Local in-process bridge dispatcher type. The Play flavor never invokes
 * this — phone-control actions are sideload-only — but the typealias has
 * to exist in the googlePlay source set so the shared `VoiceViewModel`
 * call site compiles regardless of active flavor.
 *
 * Return type mirrors the sideload flavor's updated shape so the shared
 * typealias binding site in VoiceViewModel compiles against either source
 * set without #if gating.
 */
typealias LocalBridgeDispatcher = suspend (Envelope) -> LocalDispatchResult

/** @see com.hermesandroid.relay.voice.VoiceIntentResultCallback in the sideload flavor. */
typealias VoiceIntentResultCallback = (
    intentLabel: String,
    result: LocalDispatchResult,
    androidToolName: String?,
    androidToolArgsJson: String,
) -> Unit

/** @see com.hermesandroid.relay.voice.VoiceIntentCountdownCallback in the sideload flavor. */
typealias VoiceIntentCountdownCallback = (intentLabel: String, durationMs: Long) -> Unit

/**
 * === PHASE3-voice-intents (googlePlay flavor): factory ===
 *
 * Flavor-specific factory function that [com.hermesandroid.relay.viewmodel.VoiceViewModel]
 * calls exactly once during `initialize()`. The Play build always returns
 * the no-op handler.
 *
 * Both the `googlePlay` and `sideload` flavors export a function with this
 * exact signature + package so `VoiceViewModel` has a single static call
 * site and no reflection.
 *
 * All parameters accepted for signature parity with sideload and silently
 * ignored — the Play APK deliberately never references any bridge or
 * accessibility class so the conservative Play feature description stays
 * honest.
 */
fun createVoiceBridgeIntentHandler(
    multiplexer: ChannelMultiplexer?,
    localBridgeDispatcher: LocalBridgeDispatcher? = null,
    onDispatchResult: VoiceIntentResultCallback? = null,
    onCountdownStart: VoiceIntentCountdownCallback? = null,
): VoiceBridgeIntentHandler = NoopVoiceBridgeIntentHandler()

// === END PHASE3-voice-intents (googlePlay) ===
