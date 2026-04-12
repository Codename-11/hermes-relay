package com.hermesandroid.relay.voice

/**
 * === PHASE3-η (googlePlay flavor): no-op voice→bridge handler ===
 *
 * On the Google Play track we ship the conservative feature description:
 * no accessibility-service usage, no device-control voice routing.
 *
 * This implementation never references any bridge / accessibility class and
 * always returns [IntentResult.NotApplicable] so [VoiceViewModel] falls
 * through to normal chat for every utterance.
 *
 * Sibling: `app/src/sideload/kotlin/.../VoiceBridgeIntentHandlerImpl.kt`
 * ships the real classifier.
 */
internal class NoopVoiceBridgeIntentHandler : VoiceBridgeIntentHandler {

    override suspend fun tryHandle(transcribedText: String): IntentResult {
        // Play APK path: every utterance is chat. No classification runs,
        // no bridge envelopes are sent. Nothing to cancel either.
        return IntentResult.NotApplicable
    }

    override fun cancelPending() {
        // no-op — nothing to cancel on Play.
    }
}

// === END PHASE3-η (googlePlay) ===
