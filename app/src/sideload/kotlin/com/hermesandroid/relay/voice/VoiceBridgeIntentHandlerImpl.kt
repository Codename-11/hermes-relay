package com.hermesandroid.relay.voice

import android.util.Log
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.models.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * === PHASE3-voice-intents (sideload flavor): real voice→bridge handler ===
 *
 * Classifies the transcribed utterance via [VoiceIntentClassifier]. On a
 * hit, it emits a `bridge` envelope through [ChannelMultiplexer] describing
 * the action to take (send SMS, open app, tap, scroll, back, home).
 *
 * ## Envelope wire shape
 *
 * ```
 * { channel: "bridge",
 *   type:    "tool.call",
 *   payload: { tool: "android_send_sms",
 *              args: { contact: "Sam", body: "I'll be 10 min late" },
 *              requires_confirmation: true,
 *              source:  "voice" } }
 * ```
 *
 * The exact envelope contract is owned by Wave 2 sibling safety-rails
 * (`bridge-safety-rails`) — we deliberately keep the payload shape simple +
 * documented so safety-rails can rename fields in a single place if needed.
 *
 * ## v1 confirmation (see interface doc for why)
 *
 * For destructive intents ([VoiceIntent.SendSms]) the impl:
 *
 * 1. Returns [IntentResult.Handled] with `requiresConfirmation=true` and a
 *    `spokenConfirmation` sentence ("About to text Sam: I'll be 10 min
 *    late. Say cancel to stop.").
 * 2. Starts a 5-second countdown coroutine.
 * 3. After 5 s, emits the bridge envelope — unless [cancelPending] was
 *    called first.
 *
 * For safe intents (open app, scroll, tap, back, home), the envelope is
 * emitted immediately and `requiresConfirmation=false`. No countdown.
 *
 * This is simpler than full conversational confirmation (where the user
 * says "yes" / "cancel" and that goes through another STT round). The
 * brief calls that out as a Wave 3 follow-up.
 */
internal class RealVoiceBridgeIntentHandler(
    private val multiplexer: ChannelMultiplexer?,
) : VoiceBridgeIntentHandler {

    companion object {
        private const val TAG = "VoiceBridgevoice-intents"
        /** v1 confirmation delay. Keep small so the UX is "you can cancel
         *  if you panic" not "let's have a conversation". */
        private const val CONFIRMATION_DELAY_MS = 5_000L
    }

    /** Own lifecycle so [cancelPending] can cut in-flight countdowns without
     *  touching the caller's scope. Replaced on each new tryHandle. */
    private val ownScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** The in-flight confirmation countdown, if any. */
    @Volatile
    private var pendingJob: Job? = null

    override suspend fun tryHandle(transcribedText: String): IntentResult {
        val intent = VoiceIntentClassifier.classify(transcribedText)
            ?: return IntentResult.NotApplicable

        // Drop any stale pending action before queueing a new one. Normally
        // the voice state machine won't allow concurrent tries, but belt
        // and braces.
        pendingJob?.cancel()
        pendingJob = null

        return when (intent) {
            is VoiceIntent.SendSms -> handleDestructive(
                label = "Send SMS",
                spokenConfirmation = "About to text ${intent.contact}: ${intent.body}. " +
                    "Say cancel to stop.",
                envelope = buildSmsEnvelope(intent),
            )
            is VoiceIntent.OpenApp -> handleSafe(
                label = "Open App",
                envelope = buildOpenAppEnvelope(intent),
            )
            is VoiceIntent.Tap -> handleSafe(
                label = "Tap",
                envelope = buildTapEnvelope(intent),
            )
            is VoiceIntent.Scroll -> handleSafe(
                label = "Scroll",
                envelope = buildScrollEnvelope(intent),
            )
            VoiceIntent.Back -> handleSafe(
                label = "Navigate back",
                envelope = buildSimpleEnvelope("android_press_back"),
            )
            VoiceIntent.Home -> handleSafe(
                label = "Home",
                envelope = buildSimpleEnvelope("android_press_home"),
            )
        }
    }

    override fun cancelPending() {
        val job = pendingJob
        pendingJob = null
        if (job != null) {
            Log.i(TAG, "cancelPending: user cancelled in-flight confirmation")
            job.cancel()
        }
    }

    // ---------------------------------------------------------------------
    // Dispatch helpers
    // ---------------------------------------------------------------------

    private fun handleDestructive(
        label: String,
        spokenConfirmation: String,
        envelope: Envelope,
    ): IntentResult.Handled {
        pendingJob = ownScope.launch {
            try {
                delay(CONFIRMATION_DELAY_MS)
                dispatch(envelope)
                Log.i(TAG, "$label: dispatched after confirmation window")
            } catch (_: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "$label: cancelled before dispatch")
            }
        }
        return IntentResult.Handled(
            intentLabel = label,
            spokenConfirmation = spokenConfirmation,
            requiresConfirmation = true,
        )
    }

    private fun handleSafe(
        label: String,
        envelope: Envelope,
    ): IntentResult.Handled {
        dispatch(envelope)
        return IntentResult.Handled(
            intentLabel = label,
            spokenConfirmation = null,
            requiresConfirmation = false,
        )
    }

    private fun dispatch(envelope: Envelope) {
        val mux = multiplexer
        if (mux == null) {
            Log.w(TAG, "dispatch: multiplexer null, dropping ${envelope.type}")
            return
        }
        try {
            mux.send(envelope)
        } catch (e: Exception) {
            // Don't crash voice mode if the bridge channel isn't open yet.
            // safety-rails's safety layer will surface a proper user-facing error.
            Log.w(TAG, "dispatch failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------
    // Envelope builders — see class-level comment for the wire shape.
    // ---------------------------------------------------------------------

    /**
     * === PHASE3-tier-C4: direct /send_sms bridge command ===
     *
     * Replaces the pre-C4 implementation which emitted a generic
     * `tool.call` envelope that nothing on the phone side actually
     * handled (which is why the old voice-to-SMS flow fell through to
     * tapping through the SMS app UI). Now emits a real `bridge.command`
     * envelope targeting the `/send_sms` path that [BridgeCommandHandler]
     * routes to [ActionExecutor.sendSms].
     *
     * **One caveat, marked TODO(C4):** the voice classifier captures a
     * *contact name* (e.g. "Sam"), not a phone number. True name→number
     * resolution requires calling `/search_contacts` first, picking the
     * best match, and then calling `/send_sms` with the resolved number.
     * Doing that from this fire-and-forget dispatch path needs response
     * correlation (each `bridge.command` returns a `bridge.response`
     * with a matching `request_id`) — which this impl doesn't currently
     * wire. Until it does, we pass the raw contact name as the `to`
     * field; the phone-side `sendSms` will reject it for failing the
     * number-shape regex, which surfaces as a user-visible error.
     *
     * 3-line sketch of how to fix properly:
     *   1. Emit `/search_contacts` with `query=i.contact`, `request_id=X`.
     *   2. Register a one-shot listener on `request_id=X` → extract the
     *      first `phones` entry from the response payload.
     *   3. Re-emit `/send_sms` with `to=<resolvedNumber>, body=i.body`
     *      and `request_id=Y`; safety-rails confirmation modal handles
     *      the rest.
     *
     * The phone's destructive-verb confirmation still fires via
     * [BridgeCommandHandler]'s hard-coded `/send_sms` gate so even the
     * interim "raw contact name" fallback is safe.
     */
    private fun buildSmsEnvelope(i: VoiceIntent.SendSms): Envelope = Envelope(
        channel = "bridge",
        type = "bridge.command",
        payload = buildJsonObject {
            put("request_id", java.util.UUID.randomUUID().toString())
            put("method", "POST")
            put("path", "/send_sms")
            put("body", buildJsonObject {
                // TODO(C4): resolve contact name → phone number via
                // /search_contacts first; see doc comment above.
                put("to", i.contact)
                put("body", i.body)
            })
            put("source", "voice")
        },
    )
    // === END PHASE3-tier-C4 ===

    private fun buildOpenAppEnvelope(i: VoiceIntent.OpenApp): Envelope = Envelope(
        channel = "bridge",
        type = "tool.call",
        payload = buildJsonObject {
            put("tool", "android_open_app")
            put("args", buildJsonObject { put("app_name", i.appName) })
            put("requires_confirmation", false)
            put("source", "voice")
        },
    )

    private fun buildTapEnvelope(i: VoiceIntent.Tap): Envelope = Envelope(
        channel = "bridge",
        type = "tool.call",
        payload = buildJsonObject {
            put("tool", "android_tap")
            put("args", buildJsonObject { put("target", i.target) })
            put("requires_confirmation", false)
            put("source", "voice")
        },
    )

    private fun buildScrollEnvelope(i: VoiceIntent.Scroll): Envelope = Envelope(
        channel = "bridge",
        type = "tool.call",
        payload = buildJsonObject {
            put("tool", "android_scroll")
            put("args", buildJsonObject {
                put("direction", i.direction.name.lowercase())
            })
            put("requires_confirmation", false)
            put("source", "voice")
        },
    )

    private fun buildSimpleEnvelope(toolName: String): Envelope = Envelope(
        channel = "bridge",
        type = "tool.call",
        payload = buildJsonObject {
            put("tool", toolName)
            put("args", buildJsonObject {})
            put("requires_confirmation", false)
            put("source", "voice")
        },
    )
}

// === END PHASE3-voice-intents (sideload impl) ===
