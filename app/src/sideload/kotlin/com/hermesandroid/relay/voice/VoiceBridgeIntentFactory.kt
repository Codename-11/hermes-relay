package com.hermesandroid.relay.voice

import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.handlers.LocalDispatchResult
import com.hermesandroid.relay.network.models.Envelope

/**
 * Local in-process bridge dispatcher type. Voice intents call this instead
 * of `multiplexer.send(envelope)` so the action runs through the same
 * `BridgeCommandHandler` dispatch + Tier 5 safety pipeline without
 * round-tripping over WSS. Wired in `RelayApp.kt` to
 * `connectionViewModel.bridgeCommandHandler::handleLocalCommand`. See the
 * `BridgeCommandHandler.handleLocalCommand` KDoc for the full rationale.
 *
 * Returns a [LocalDispatchResult] so voice mode can emit a follow-up
 * chat bubble reflecting the real success/failure of the action after
 * the destructive-verb safety modal resolves. Pre-0.4.0 this was
 * `-> Unit` and voice had no way to tell whether an SMS actually sent.
 */
typealias LocalBridgeDispatcher = suspend (Envelope) -> LocalDispatchResult

/**
 * Callback invoked by [RealVoiceBridgeIntentHandler] after a dispatch
 * completes. VoiceViewModel wires this to `ChatViewModel.recordVoiceIntentResult`
 * so the follow-up bubble appears in the voice-mode transcript without
 * coupling the voice handler to ChatViewModel directly.
 *
 *  - [intentLabel] matches the label on the originating
 *    [IntentResult.Handled] (e.g. "Send SMS", "Open App")
 *  - [result] is the captured [LocalDispatchResult] from the phone-side
 *    dispatch; check [LocalDispatchResult.isSuccess] to branch
 */
typealias VoiceIntentResultCallback = (intentLabel: String, result: LocalDispatchResult) -> Unit

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
 *
 * The [onDispatchResult] callback receives the post-dispatch outcome for
 * every destructive AND safe intent so voice mode can render success/
 * failure feedback in the chat transcript.
 */
fun createVoiceBridgeIntentHandler(
    multiplexer: ChannelMultiplexer?,
    localBridgeDispatcher: LocalBridgeDispatcher? = null,
    onDispatchResult: VoiceIntentResultCallback? = null,
): VoiceBridgeIntentHandler = RealVoiceBridgeIntentHandler(
    multiplexer = multiplexer,
    localBridgeDispatcher = localBridgeDispatcher,
    onDispatchResult = onDispatchResult,
)

// === END PHASE3-voice-intents (sideload factory) ===
