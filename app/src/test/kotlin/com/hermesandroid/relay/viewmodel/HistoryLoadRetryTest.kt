package com.hermesandroid.relay.viewmodel

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [loadHistoryWithRetry] — the bounded retry behind user-initiated
 * session opens and profile/session switches. The sleeper is scripted, so the
 * backoff schedule is asserted without real delays.
 */
class HistoryLoadRetryTest {

    /** Scripted load: runs [steps] in order, repeating the last one. */
    private class ScriptedLoad(private val steps: List<() -> String>) {
        var calls = 0
            private set

        suspend fun load(): String {
            val step = steps[minOf(calls, steps.lastIndex)]
            calls++
            return step()
        }
    }

    @Test
    fun firstSuccessIsReturnedWithoutRetrying() = runTest {
        val scripted = ScriptedLoad(listOf({ "history" }))
        val sleeps = mutableListOf<Long>()

        val result = loadHistoryWithRetry(sleeper = { sleeps += it }) { scripted.load() }

        assertEquals("history", result)
        assertEquals(1, scripted.calls)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun transientFailuresAreRetriedWithDoublingBackoff() = runTest {
        val scripted = ScriptedLoad(
            listOf(
                { throw IOException("gateway busy") },
                { throw IOException("gateway busy") },
                { "history" },
            ),
        )
        val sleeps = mutableListOf<Long>()

        val result = loadHistoryWithRetry(sleeper = { sleeps += it }) { scripted.load() }

        assertEquals("history", result)
        assertEquals(3, scripted.calls)
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }

    @Test
    fun finalFailureIsRethrownAfterMaxAttempts() = runTest {
        val failures = listOf(
            IOException("first"),
            IOException("second"),
            IOException("third"),
        )
        var index = 0
        val sleeps = mutableListOf<Long>()

        val thrown = try {
            loadHistoryWithRetry(sleeper = { sleeps += it }) {
                throw failures[minOf(index++, failures.lastIndex)]
            }
            null
        } catch (e: IOException) {
            e
        }

        assertSame(failures[2], thrown)
        assertEquals(3, index)
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }

    @Test
    fun nonRetryableFailureIsNotRetried() = runTest {
        val failure = IllegalStateException("sign-in required")
        var calls = 0
        val sleeps = mutableListOf<Long>()

        val thrown = try {
            loadHistoryWithRetry(
                isRetryable = { it !is IllegalStateException },
                sleeper = { sleeps += it },
            ) {
                calls++
                throw failure
            }
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertSame(failure, thrown)
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun cancellationPropagatesImmediately() = runTest {
        var calls = 0
        val sleeps = mutableListOf<Long>()

        val thrown = try {
            loadHistoryWithRetry(sleeper = { sleeps += it }) {
                calls++
                throw CancellationException("superseded")
            }
            null
        } catch (e: CancellationException) {
            e
        }

        assertTrue(thrown is CancellationException)
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun backoffIsCappedAtMaxDelay() = runTest {
        val sleeps = mutableListOf<Long>()

        try {
            loadHistoryWithRetry(
                maxAttempts = 5,
                sleeper = { sleeps += it },
            ) {
                throw IOException("still down")
            }
        } catch (_: IOException) {
            // Expected: every attempt failed.
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 4_000L), sleeps)
    }
}
