package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.upstream.models.MessageItem
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual-time tests for [ChatStreamRecovery] — the issue #166 poller that
 * recovers a dropped sessions-stream turn from the persisted transcript.
 *
 * Timing uses the production defaults (5s → ×2 backoff → 30s cap, 30 min
 * window); `runTest` virtual time makes even the 30-minute cap instant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStreamRecoveryTest {

    private val pending = "what's the weather?"

    private fun user(id: String, text: String = pending) = MessageItem(
        id = id,
        role = "user",
        content = JsonPrimitive(text),
    )

    private fun assistant(id: String, text: String) = MessageItem(
        id = id,
        role = "assistant",
        content = JsonPrimitive(text),
    )

    /** Scripted fetch: returns [responses] in order, repeating the last one. */
    private class ScriptedHistory(private val responses: List<() -> List<MessageItem>>) {
        val fetchTimesMs = mutableListOf<Long>()
        private var calls = 0

        fun fetcher(now: () -> Long): suspend () -> List<MessageItem> = {
            fetchTimesMs += now()
            val step = responses[minOf(calls, responses.lastIndex)]
            calls++
            step()
        }

        val fetchCount: Int get() = calls
    }

    @Test
    fun recoversWhenAnswerIsStableAcrossTwoConsecutivePolls() = runTest {
        val script = ScriptedHistory(
            listOf(
                { listOf(user("u1")) },
                { listOf(user("u1"), assistant("a1", "recovered answer")) },
                { listOf(user("u1"), assistant("a1", "recovered answer")) },
            ),
        )
        val intermediate = mutableListOf<List<MessageItem>>()
        var recovered: List<MessageItem>? = null
        var gaveUp: ChatStreamRecovery.GiveUpReason? = null

        val recovery = ChatStreamRecovery(this, script.fetcher { testScheduler.currentTime })
        recovery.start(
            pendingUserText = pending,
            priorUserMessageCount = 0,
            onIntermediateHistory = { intermediate += it },
            onRecovered = { recovered = it },
            onGaveUp = { gaveUp = it },
        )

        advanceTimeBy(5_001) // poll 1 — user only, no answer yet
        assertEquals(1, intermediate.size)
        assertNull(recovered)

        advanceTimeBy(10_000) // poll 2 — answer appears (signature recorded)
        assertEquals(2, intermediate.size)
        assertNull(recovered)

        advanceTimeBy(20_000) // poll 3 — unchanged → stable → recovered
        assertEquals("recovered answer", recovered?.last()?.contentText)
        assertNull(gaveUp)
        assertFalse(recovery.isActive)
    }

    @Test
    fun pollCadenceBacksOffExponentiallyToTheCap() = runTest {
        val script = ScriptedHistory(listOf({ emptyList() }))
        val recovery = ChatStreamRecovery(this, script.fetcher { testScheduler.currentTime })
        recovery.start(pending, 0, {}, {}, {})

        // 5s, +10s, +20s, +30s, +30s… (cap) — cumulative fetch times.
        advanceTimeBy(5_000 + 10_000 + 20_000 + 30_000 + 30_000 + 1)
        assertEquals(
            listOf(5_000L, 15_000L, 35_000L, 65_000L, 95_000L),
            script.fetchTimesMs,
        )
        recovery.cancel()
    }

    @Test
    fun emptyTranscriptCarriesNoInformationAndKeepsPolling() = runTest {
        val script = ScriptedHistory(listOf({ emptyList() }))
        var gaveUp: ChatStreamRecovery.GiveUpReason? = null
        val recovery = ChatStreamRecovery(this, script.fetcher { testScheduler.currentTime })
        recovery.start(pending, 0, {}, {}, { gaveUp = it })

        advanceTimeBy(31L * 60_000)
        assertEquals(ChatStreamRecovery.GiveUpReason.TIMED_OUT, gaveUp)
        assertTrue("should have kept polling to the cap", script.fetchCount > 10)
    }

    @Test
    fun reachableTranscriptWithoutThePendingSendFailsFast() = runTest {
        // Server reachable, but the turn's POST never landed: the transcript
        // still holds only the PREVIOUS exchange (one user row the client
        // already knew about). No new user row → no anchor → waiting cannot
        // produce an answer, so give up quickly (after confirming, not on the
        // very first read) rather than polling to the 30-minute cap.
        val script = ScriptedHistory(
            listOf(
                {
                    listOf(
                        user("u0", "an earlier prompt"),
                        assistant("a0", "an earlier answer"),
                    )
                },
            ),
        )
        var gaveUp: ChatStreamRecovery.GiveUpReason? = null
        var recovered = false
        val recovery = ChatStreamRecovery(this, script.fetcher { testScheduler.currentTime })
        recovery.start(pending, 1, {}, { recovered = true }, { gaveUp = it })

        advanceTimeBy(5_001) // poll 1 — not established, not yet confirmed
        assertNull("must confirm across a couple polls before giving up", gaveUp)
        advanceTimeBy(10_000) // poll 2 — still not established → fail fast
        assertEquals(ChatStreamRecovery.GiveUpReason.RUN_NOT_FOUND, gaveUp)
        assertFalse("stale prior answer must never count as the recovery", recovered)
        assertEquals(2, script.fetchCount)
        assertFalse(recovery.isActive)
    }

    @Test
    fun staleIdenticalEarlierUserMessageIsNotAdoptedWhenSendNeverLanded() = runTest {
        // History the client already knew: "continue" → A, "tell me more" → B.
        // The new send is ALSO "continue" but its POST never reached the
        // server, so the transcript is unchanged. A bare `indexOfLast` text
        // match would anchor on the STALE first "continue" and adopt B (static
        // → instantly "stable"). The positional invariant (this send would be
        // the 3rd user row, but only 2 exist) must refuse to adopt and fail.
        val stale = listOf(
            user("u1", "continue"),
            assistant("a1", "answer A"),
            user("u2", "tell me more"),
            assistant("a2", "answer B"),
        )
        val script = ScriptedHistory(listOf({ stale }))
        var recovered: List<MessageItem>? = null
        var gaveUp: ChatStreamRecovery.GiveUpReason? = null
        val recovery = ChatStreamRecovery(this, script.fetcher { testScheduler.currentTime })
        recovery.start(
            pendingUserText = "continue",
            priorUserMessageCount = 2, // u1 + u2 were known before this send
            onIntermediateHistory = {},
            onRecovered = { recovered = it },
            onGaveUp = { gaveUp = it },
        )

        advanceTimeBy(5_000 + 10_000 + 1) // two polls of the static transcript
        assertNull("must NOT adopt a different turn's answer", recovered)
        assertEquals(ChatStreamRecovery.GiveUpReason.RUN_NOT_FOUND, gaveUp)
        assertFalse(recovery.isActive)
    }

    @Test
    fun repeatedShortPromptAnchorsTheNewTurnNotTheStaleOne() = runTest {
        // "continue" → A is already in history; the new "continue" DID land, so
        // the server appended a second identical user row + its own answer C.
        // The positional anchor must pick the SECOND "continue" and adopt C —
        // never the stale A that an `indexOfLast`-only heuristic risks.
        val landed = listOf(
            user("u1", "continue"),
            assistant("a1", "answer A"),
            user("u2", "continue"),
            assistant("a2", "answer C"),
        )
        val script = ScriptedHistory(listOf({ landed }))
        var recovered: List<MessageItem>? = null
        val recovery = ChatStreamRecovery(this, script.fetcher { testScheduler.currentTime })
        recovery.start(
            pendingUserText = "continue",
            priorUserMessageCount = 1, // only the first "continue" was known
            onIntermediateHistory = {},
            onRecovered = { recovered = it },
            onGaveUp = {},
        )

        advanceTimeBy(5_000 + 10_000 + 1) // stable across two polls
        assertEquals("answer C", recovered?.last()?.contentText)
    }

    @Test
    fun fetchExceptionKeepsPollingUntilTheServerComesBack() = runTest {
        val script = ScriptedHistory(
            listOf(
                { throw IOException("network still down") },
                { listOf(user("u1"), assistant("a1", "late answer")) },
                { listOf(user("u1"), assistant("a1", "late answer")) },
            ),
        )
        var recovered: List<MessageItem>? = null
        val recovery = ChatStreamRecovery(this, script.fetcher { testScheduler.currentTime })
        recovery.start(pending, 0, {}, { recovered = it }, {})

        advanceTimeBy(5_000 + 10_000 + 20_000 + 1)
        assertEquals("late answer", recovered?.last()?.contentText)
    }

    @Test
    fun repeatedProfileHistoryFailuresStopWithAnActionableError() = runTest {
        val script = ScriptedHistory(listOf({ throw IOException("profile database unavailable") }))
        var gaveUp: ChatStreamRecovery.GiveUpReason? = null
        val recovery = ChatStreamRecovery(this, script.fetcher { testScheduler.currentTime })
        recovery.start(pending, 0, {}, {}, { gaveUp = it })

        advanceTimeBy(5_000 + 10_000 + 20_000 + 1)

        assertEquals(ChatStreamRecovery.GiveUpReason.HISTORY_UNAVAILABLE, gaveUp)
        assertEquals(3, script.fetchCount)
        assertFalse(recovery.isActive)
    }

    @Test
    fun growingTranscriptDefersTheFinishUntilStable() = runTest {
        val script = ScriptedHistory(
            listOf(
                { listOf(user("u1"), assistant("a1", "thinking about it")) },
                // Run still going: a new assistant row appended → not stable.
                {
                    listOf(
                        user("u1"),
                        assistant("a1", "thinking about it"),
                        assistant("a2", "final answer"),
                    )
                },
                {
                    listOf(
                        user("u1"),
                        assistant("a1", "thinking about it"),
                        assistant("a2", "final answer"),
                    )
                },
            ),
        )
        var recovered: List<MessageItem>? = null
        val recovery = ChatStreamRecovery(this, script.fetcher { testScheduler.currentTime })
        recovery.start(pending, 0, {}, { recovered = it }, {})

        advanceTimeBy(15_001) // polls 1+2 — signature changed between them
        assertNull(recovered)
        advanceTimeBy(20_000) // poll 3 — stable now
        assertEquals("final answer", recovered?.last()?.contentText)
    }

    @Test
    fun cancelStopsPollingWithoutAnyTerminalCallback() = runTest {
        val script = ScriptedHistory(listOf({ listOf(user("u1")) }))
        var recovered = false
        var gaveUp = false
        val recovery = ChatStreamRecovery(this, script.fetcher { testScheduler.currentTime })
        recovery.start(pending, 0, {}, { recovered = true }, { gaveUp = true })

        advanceTimeBy(5_001)
        assertEquals(1, script.fetchCount)
        recovery.cancel()

        advanceTimeBy(60L * 60_000)
        assertEquals("no polls after cancel", 1, script.fetchCount)
        assertFalse(recovered)
        assertFalse(gaveUp)
        assertFalse(recovery.isActive)
    }
}
