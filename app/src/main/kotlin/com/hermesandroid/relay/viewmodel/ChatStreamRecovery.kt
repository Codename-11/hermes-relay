package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.upstream.models.MessageItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Client-side answer recovery for a sessions-endpoint chat stream that died on
 * a transport error while the server kept working (issue #166).
 *
 * On slow local models + delegating skills, a turn can outlive the phone's SSE
 * socket (screen-off / Doze / Wi-Fi power-save kill it long before OkHttp's
 * read timeout). Upstream `api_server` behavior on that disconnect: the SSE
 * writer dies but the agent run continues in an uncancellable executor thread
 * and the FINAL ANSWER IS PERSISTED to the session store. So instead of
 * finalizing the turn as an error, this poller re-reads the session transcript
 * (the native upstream `/api/sessions/{id}/messages` route — standard-path
 * safe, no server changes) until the answer lands.
 *
 * Cadence: first poll after [Timing.pollIntervalMs], doubling each poll up to
 * [Timing.maxPollIntervalMs], for at most [Timing.recoveryWindowMs] of slept
 * time. Elapsed time is accumulated from the delays (not wall-clock reads) so
 * the loop is virtual-time friendly in tests.
 *
 * **Anchoring (positional, not text-only).** The pending send, if it landed,
 * is the `(priorUserMessageCount + 1)`-th user-role row in the server
 * transcript — i.e. the first user row AFTER the ones the client already knew
 * about. The candidate answer is the last non-blank assistant row after that
 * anchor. Anchoring by position (with a content-match sanity check) — rather
 * than a bare `indexOfLast` text search — is what stops a short repeated
 * prompt ("yes", "ok", "continue") from matching a STALE identical earlier row
 * and adopting a DIFFERENT turn's (static, therefore instantly "stable")
 * answer when this send never actually reached the server.
 *
 * Finish condition: an assistant message that postdates the anchor, is
 * non-empty, and is stable across two consecutive polls (the signature also
 * folds in the transcript length, so a still-running run that keeps appending
 * tool rows after an intermediate assistant message defers the finish).
 * Intermediate persisted rows are surfaced through [onIntermediateHistory] as
 * they appear — progressive recovery.
 *
 * Fail-fast: when the anchor can't be established — the transcript still holds
 * only the user rows the client already knew about (the POST died before the
 * server persisted the turn), or the positional row's content diverges from
 * the pending send (the transcript was edited/forked out from under us) — the
 * poller never adopts an answer, because a wrong answer is worse than an error.
 * It confirms the state across two consecutive polls (guarding against a
 * transient read mid-persist) and then gives up with [GiveUpReason.RUN_NOT_FOUND]
 * instead of polling to the 30-minute cap, since no answer for this turn can
 * ever arrive. An EMPTY transcript carries no information (the history read
 * maps fetch failures to an empty list too), so it keeps polling.
 */
class ChatStreamRecovery(
    private val scope: CoroutineScope,
    private val fetchHistory: suspend () -> List<MessageItem>,
    private val timing: Timing = Timing(),
) {

    data class Timing(
        val pollIntervalMs: Long = 5_000L,
        val maxPollIntervalMs: Long = 30_000L,
        val recoveryWindowMs: Long = 30L * 60_000L,
        val maxConsecutiveFetchFailures: Int = 3,
    )

    enum class GiveUpReason {
        /**
         * The pending send couldn't be located as a new user row after the
         * ones the client already knew about — the POST never persisted the
         * turn, or the transcript diverged. No answer can arrive; resend.
         */
        RUN_NOT_FOUND,

        /** The profile-scoped transcript could not be read repeatedly. */
        HISTORY_UNAVAILABLE,

        /** The recovery window elapsed without a stable answer. */
        TIMED_OUT,
    }

    /** Whether a poll could establish the anchor for the pending send. */
    private sealed interface Anchor {
        /** The `(priorUserCount + 1)`-th user row exists and matches. */
        data class Found(val index: Int) : Anchor

        /**
         * The anchor can't be proven: too few user rows (send not persisted)
         * or a positional row whose content diverges (edited/forked history).
         */
        data object NotEstablished : Anchor
    }

    private var job: Job? = null

    val isActive: Boolean
        get() = job?.isActive == true

    /**
     * Start polling. Exactly one poll loop per instance — a second [start]
     * replaces the first. Exactly one terminal callback fires per loop
     * ([onRecovered] or [onGaveUp]); cancellation fires none.
     *
     * @param priorUserMessageCount how many user-role messages the client knew
     *   existed BEFORE this turn's pending send (excluding the in-flight pair).
     *   Drives the positional anchor — see the class KDoc.
     */
    fun start(
        pendingUserText: String,
        priorUserMessageCount: Int,
        onIntermediateHistory: (List<MessageItem>) -> Unit,
        onRecovered: (List<MessageItem>) -> Unit,
        onGaveUp: (GiveUpReason) -> Unit,
    ) {
        job?.cancel()
        val pending = pendingUserText.trim()
        val priorUsers = priorUserMessageCount.coerceAtLeast(0)
        job = scope.launch {
            var delayMs = timing.pollIntervalMs
            var elapsedMs = 0L
            var lastSignature: String? = null
            var lastSurfacedCount = -1
            var unanchoredPolls = 0
            var consecutiveFetchFailures = 0
            while (elapsedMs < timing.recoveryWindowMs) {
                delay(delayMs)
                elapsedMs += delayMs
                delayMs = (delayMs * 2).coerceAtMost(timing.maxPollIntervalMs)

                val items = try {
                    fetchHistory()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    consecutiveFetchFailures++
                    if (consecutiveFetchFailures >= timing.maxConsecutiveFetchFailures) {
                        onGaveUp(GiveUpReason.HISTORY_UNAVAILABLE)
                        return@launch
                    }
                    continue
                }
                consecutiveFetchFailures = 0
                if (items.isEmpty()) continue

                when (val anchor = resolveAnchor(items, pending, priorUsers)) {
                    is Anchor.NotEstablished -> {
                        // The pending send isn't (verifiably) persisted as a new
                        // user row. A transient read while the server persists
                        // could look like this, so require the state to hold
                        // across two consecutive polls before giving up — but
                        // never poll to the cap for an answer that can't arrive.
                        lastSignature = null
                        if (++unanchoredPolls >= 2) {
                            onGaveUp(GiveUpReason.RUN_NOT_FOUND)
                            return@launch
                        }
                    }

                    is Anchor.Found -> {
                        unanchoredPolls = 0
                        val signature = answerSignature(items, anchor.index)
                        if (signature != null && signature == lastSignature) {
                            onRecovered(items)
                            return@launch
                        }
                        lastSignature = signature

                        if (items.size != lastSurfacedCount) {
                            lastSurfacedCount = items.size
                            onIntermediateHistory(items)
                        }
                    }
                }
            }
            onGaveUp(GiveUpReason.TIMED_OUT)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * Resolve the positional anchor for the pending send. The send, if it
     * landed, is the `(priorUserCount + 1)`-th user-role row; that row must
     * ALSO match the pending text (secondary sanity check for edits/forks).
     * Any other shape is [Anchor.NotEstablished] — never adopt a guess.
     */
    private fun resolveAnchor(
        items: List<MessageItem>,
        pendingUserText: String,
        priorUserCount: Int,
    ): Anchor {
        val userIndices = items.indices.filter { items[it].role == "user" }
        if (userIndices.size <= priorUserCount) return Anchor.NotEstablished
        val anchorPos = userIndices[priorUserCount]
        if (items[anchorPos].contentText?.trim() != pendingUserText) return Anchor.NotEstablished
        return Anchor.Found(anchorPos)
    }

    /**
     * Stability signature of the candidate answer: the last non-blank
     * assistant message after the anchor, or null while none exists. The
     * transcript size is folded in so new rows (tool results of a
     * still-running run) change the signature and defer the finish.
     */
    private fun answerSignature(items: List<MessageItem>, anchor: Int): String? {
        val answer = items.drop(anchor + 1).lastOrNull {
            it.role == "assistant" && !it.contentText.isNullOrBlank()
        } ?: return null
        return "${items.size}|${answer.id}|${answer.contentText?.length}"
    }
}
