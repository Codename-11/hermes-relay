package com.hermesandroid.relay.voice

/** Upstream-compatible, API-local note attached to the next model-bound turn. */
internal const val SPEECH_INTERRUPTED_NOTE =
    "[Note: the user interrupted your previous spoken reply before it finished.]"

/**
 * One-shot spoken-interruption latch with upstream's 120-second expiry.
 *
 * The note is consumed only by the next model-bound message and is never added
 * to visible or persisted user text.
 */
internal class SpokenInterruptionLatch(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var interruptedAtMs: Long? = null

    @Synchronized
    fun mark() {
        interruptedAtMs = nowMs()
    }

    @Synchronized
    fun take(): Boolean {
        val markedAt = interruptedAtMs
        interruptedAtMs = null
        return markedAt != null && nowMs() - markedAt < INTERRUPT_TTL_MS
    }

    fun takeNote(): String? = SPEECH_INTERRUPTED_NOTE.takeIf { take() }

    @Synchronized
    fun clear() {
        interruptedAtMs = null
    }

    private companion object {
        const val INTERRUPT_TTL_MS = 120_000L
    }
}

internal fun voiceInterfaceContextPrompt(
    stableContext: String,
    spokenReplyInterrupted: Boolean,
): String = if (spokenReplyInterrupted) {
    "$stableContext\n$SPEECH_INTERRUPTED_NOTE"
} else {
    stableContext
}
