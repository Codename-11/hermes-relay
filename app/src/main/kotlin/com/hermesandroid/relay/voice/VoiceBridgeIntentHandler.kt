package com.hermesandroid.relay.voice

/**
 * === PHASE3-voice-intents: voice → bridge intent routing ===
 *
 * Handles transcribed voice utterances that look like phone-control intents
 * ("text Sam I'll be 10 min late", "open camera", "scroll down"...) and
 * routes them through the bridge channel instead of the chat channel.
 *
 * ### Cross-flavor pattern
 *
 * This interface lives in `main/` because [com.hermesandroid.relay.viewmodel.VoiceViewModel]
 * needs a stable reference at compile time. Implementations live in **flavor
 * source sets** and are selected by Gradle at build time:
 *
 * - `app/src/googlePlay/kotlin/.../VoiceBridgeIntentHandlerImpl.kt` — a no-op
 *   that always returns [IntentResult.NotApplicable]. The Play APK never
 *   references any `AccessibilityService` / bridge-control class, keeping the
 *   Play track's conservative feature description honest.
 * - `app/src/sideload/kotlin/.../VoiceBridgeIntentHandlerImpl.kt` — the real
 *   keyword classifier + bridge tool emission.
 *
 * Both flavors must provide a top-level factory function
 * `createVoiceBridgeIntentHandler(...)` in
 * `com.hermesandroid.relay.voice.VoiceBridgeIntentFactory`. [VoiceViewModel]
 * calls it exactly once during `initialize()`. No reflection.
 *
 * ### v1 confirmation model (Wave 2)
 *
 * Full conversational confirmation ("I'm about to text Sam... say yes or
 * cancel") requires a multi-turn voice state machine we don't have yet.
 * For Wave 2 v1 the sideload impl does a simpler thing: it speaks a 5-second
 * TTS announcement, starts a countdown, and executes unless the user taps
 * the Cancel button on the overlay. This is documented as a v1 simplification;
 * Wave 3 swaps in real voice confirmation. See Phase 3 plan, Wave 3 row for
 * "voice-bridge-confirmation".
 */
interface VoiceBridgeIntentHandler {

    /**
     * Attempt to handle [transcribedText] as a phone-control intent.
     *
     * @return [IntentResult.Handled] if the text was recognized and a bridge
     *   action was dispatched (possibly pending confirmation). Voice mode
     *   should show the spoken confirmation via [IntentResult.Handled.spokenConfirmation]
     *   and NOT fall through to chat.
     * @return [IntentResult.NotApplicable] if the text is not a phone-control
     *   intent. Voice mode should fall through to the normal
     *   `chatVm.sendMessage(text)` path.
     */
    suspend fun tryHandle(transcribedText: String): IntentResult

    /**
     * Cancel any in-flight action that's waiting on the v1 confirmation
     * countdown. Called from the overlay's Cancel button. No-op if nothing
     * is pending.
     */
    fun cancelPending()
}

/**
 * Result of a [VoiceBridgeIntentHandler.tryHandle] call.
 */
sealed class IntentResult {
    /**
     * The text was recognized as a phone-control intent and a bridge action
     * was dispatched (or queued behind the v1 countdown).
     *
     * @property intentLabel Short human-readable label for the recognized
     *   intent ("Send SMS", "Open App", "Tap", "Scroll", "Navigate back",
     *   "Home"). Shown in the voice overlay as "Intent: X".
     * @property spokenConfirmation The TTS confirmation sentence the voice
     *   layer should speak back to the user BEFORE the countdown fires. If
     *   null, no confirmation is spoken (used for safe low-stakes actions
     *   like "scroll down" where a 5 s delay would just feel broken).
     * @property requiresConfirmation True for destructive / irreversible
     *   actions (send SMS, call, send email) where the v1 countdown matters.
     *   False for safe intents like scroll / tap / open-app that execute
     *   immediately.
     */
    data class Handled(
        val intentLabel: String,
        val spokenConfirmation: String? = null,
        val requiresConfirmation: Boolean = false,
    ) : IntentResult()

    /** The text is not a phone-control intent. Fall through to chat. */
    data object NotApplicable : IntentResult()
}

// === END PHASE3-voice-intents ===
