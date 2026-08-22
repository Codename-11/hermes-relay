package com.hermesandroid.relay.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCandidateIsolationTest {
    @Test
    fun candidateNeverQueriesStableReleaseChannel() {
        assertFalse(
            UpdateChecker.shouldQueryStableReleases(
                isSideload = true,
                isCandidate = true,
            )
        )
    }

    @Test
    fun stableSideloadKeepsReleaseChecks() {
        assertTrue(
            UpdateChecker.shouldQueryStableReleases(
                isSideload = true,
                isCandidate = false,
            )
        )
        assertFalse(
            UpdateChecker.shouldQueryStableReleases(
                isSideload = false,
                isCandidate = false,
            )
        )
    }
}
