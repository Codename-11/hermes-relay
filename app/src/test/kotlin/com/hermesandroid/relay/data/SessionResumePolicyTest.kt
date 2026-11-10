package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionResumePolicyTest {
    private val ttl = SessionResumePolicy.RESUME_TTL_MS
    private val now = 1_700_000_000_000L

    @Test
    fun resumesWhenActivityWithinTtl() {
        val activity = now - ttl + 1
        assertEquals(
            "sess-1",
            SessionResumePolicy.sessionIdToRestore(
                savedSessionId = "sess-1",
                lastActivityAtMs = activity,
                nowMs = now,
            ),
        )
    }

    @Test
    fun blankWhenActivityExactlyExpired() {
        assertNull(
            SessionResumePolicy.sessionIdToRestore(
                savedSessionId = "sess-1",
                lastActivityAtMs = now - ttl,
                nowMs = now,
            ),
        )
    }

    @Test
    fun blankWhenActivityOlderThanTtl() {
        assertNull(
            SessionResumePolicy.sessionIdToRestore(
                savedSessionId = "sess-1",
                lastActivityAtMs = now - ttl - 1,
                nowMs = now,
            ),
        )
    }

    @Test
    fun blankWhenNoSavedId() {
        assertNull(
            SessionResumePolicy.sessionIdToRestore(
                savedSessionId = null,
                lastActivityAtMs = now,
                nowMs = now,
            ),
        )
    }

    @Test
    fun blankWhenActivityUnknown() {
        assertNull(
            SessionResumePolicy.sessionIdToRestore(
                savedSessionId = "sess-1",
                lastActivityAtMs = null,
                nowMs = now,
            ),
        )
    }

    @Test
    fun blankWhenActivityNonPositive() {
        assertNull(
            SessionResumePolicy.sessionIdToRestore(
                savedSessionId = "sess-1",
                lastActivityAtMs = 0L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun prefersDirectoryActivityWhenStoredMissing() {
        assertEquals(
            "sess-1",
            SessionResumePolicy.sessionIdToRestore(
                savedSessionId = "sess-1",
                lastActivityAtMs = null,
                directoryLastActivityAtMs = now - 60_000L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun blankWhenDirectoryActivityAlsoStale() {
        assertNull(
            SessionResumePolicy.sessionIdToRestore(
                savedSessionId = "sess-1",
                lastActivityAtMs = null,
                directoryLastActivityAtMs = now - ttl - 5_000L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun clockSkewNegativeAgeStillResumes() {
        assertEquals(
            "sess-1",
            SessionResumePolicy.sessionIdToRestore(
                savedSessionId = "sess-1",
                lastActivityAtMs = now + 5_000L,
                nowMs = now,
            ),
        )
    }
}
