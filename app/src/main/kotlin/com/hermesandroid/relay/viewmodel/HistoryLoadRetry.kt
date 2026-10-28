package com.hermesandroid.relay.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Bounded retry for a user-initiated conversation-history load.
 *
 * Opening a session or switching profiles reads the transcript exactly once
 * today, so a single transient failure — the gateway mid-turn, a route still
 * settling after a profile switch, a brief network stall — clears the visible
 * transcript and raises the history-load failure banner even though an
 * immediate retry would succeed. This retries the load a small number of
 * times with backoff before the caller treats the failure as final.
 *
 * Deliberately narrow:
 * - [CancellationException] always propagates; a superseded load must not
 *   keep retrying (callers also re-check their generation guards before
 *   applying a result, so a late success is discarded, never painted).
 * - [isRetryable] lets the caller exclude failures a quick retry cannot fix
 *   (for example a Dashboard sign-in requirement, which must surface its
 *   sign-in flow without a multi-second delay).
 * - The budget is small (three attempts, 1 s → 2 s backoff) so a genuinely
 *   unreachable profile still fails within seconds, with the final attempt's
 *   exception rethrown unchanged for the caller's existing failure handling.
 *
 * [sleeper] is injectable so tests can assert the backoff schedule without
 * real delays.
 */
internal suspend fun <T> loadHistoryWithRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 1_000L,
    maxDelayMs: Long = 4_000L,
    isRetryable: (Throwable) -> Boolean = { true },
    sleeper: suspend (Long) -> Unit = { delay(it) },
    load: suspend () -> T,
): T {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    var delayMs = initialDelayMs
    var attemptsUsed = 0
    while (true) {
        try {
            return load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            attemptsUsed++
            if (attemptsUsed >= maxAttempts || !isRetryable(e)) throw e
            sleeper(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
        }
    }
}
