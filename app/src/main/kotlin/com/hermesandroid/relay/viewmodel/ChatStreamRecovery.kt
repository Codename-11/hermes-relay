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
 * Finish condition: an assistant message that postdates the pending user
 * message, is non-empty, and is stable across two consecutive polls (the
 * signature also folds in the transcript length, so a still-running run that
 * keeps appending tool rows after an intermediate assistant message defers the
 * finish). Intermediate persisted rows are surfaced through
 * [onIntermediateHistory] as they appear — progressive recovery.
 *
 * Fail-fast: a non-empty transcript that does NOT contain the pending user
 * message means the server is reachable but the run never started (the POST
 * died before the server persisted the turn) — waiting cannot help, so it
 * gives up immediately with [GiveUpReason.RUN_NOT_FOUND]. An EMPTY transcript
 * carries no information (the history read maps fetch failures to an empty
 * list too), so it keeps polling.
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
    )

    enum class GiveUpReason {
        /** Server reachable but the pending user message was never persisted. */
        RUN_NOT_FOUND,

        /** The recovery window elapsed without a stable answer. */
        TIMED_OUT,
    }

    private var job: Job? = null

    val isActive: Boolean
        get() = job?.isActive == true

    /**
     * Start polling. Exactly one poll loop per instance — a second [start]
     * replaces the first. Exactly one terminal callback fires per loop
     * ([onRecovered] or [onGaveUp]); cancellation fires none.
     */
    fun start(
        pendingUserText: String,
        onIntermediateHistory: (List<MessageItem>) -> Unit,
        onRecovered: (List<MessageItem>) -> Unit,
        onGaveUp: (GiveUpReason) -> Unit,
    ) {
        job?.cancel()
        val pending = pendingUserText.trim()
        job = scope.launch {
            var delayMs = timing.pollIntervalMs
            var elapsedMs = 0L
            var lastSignature: String? = null
            var lastSurfacedCount = -1
            while (elapsedMs < timing.recoveryWindowMs) {
                delay(delayMs)
                elapsedMs += delayMs
                delayMs = (delayMs * 2).coerceAtMost(timing.maxPollIntervalMs)

                val items = try {
                    fetchHistory()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue // unreachable — keep waiting for the network
                }
                if (items.isEmpty()) continue

                val anchor = anchorIndex(items, pending)
                if (anchor < 0) {
                    onGaveUp(GiveUpReason.RUN_NOT_FOUND)
                    return@launch
                }

                val signature = answerSignature(items, anchor)
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
            onGaveUp(GiveUpReason.TIMED_OUT)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Index of the persisted pending user message, or -1 when absent. */
    private fun anchorIndex(items: List<MessageItem>, pendingUserText: String): Int =
        items.indexOfLast { it.role == "user" && it.contentText?.trim() == pendingUserText }

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
