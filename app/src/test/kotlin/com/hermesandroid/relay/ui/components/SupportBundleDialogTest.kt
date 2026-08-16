package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.reliability.ReliabilityEnvironment
import com.hermesandroid.relay.reliability.ReliabilityKind
import com.hermesandroid.relay.reliability.ReliabilityOwner
import com.hermesandroid.relay.reliability.ReliabilityReport
import com.hermesandroid.relay.reliability.ReliabilitySeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportBundleDialogTest {
    @Test
    fun emptyStateCannotShare() {
        val state = buildSupportReviewState(emptyList())
        assertEquals(0, state.reportCount)
        assertFalse(state.shareEnabled)
    }

    @Test
    fun populatedStateShowsExactlyTheBundleThatCanBeShared() {
        val report = ReliabilityReport(
            reportId = "rpt-visible",
            appSessionId = "app-visible",
            timeIso = "2026-08-04T12:00:00Z",
            kind = ReliabilityKind.RecoverableProductError,
            owner = ReliabilityOwner.Voice,
            severity = ReliabilitySeverity.Error,
            summary = "Focus controls stopped responding",
            recovery = "Voice closed and chat remained available",
            reportRecommended = true,
            environment = ReliabilityEnvironment("1.6.1", 38, "googlePlay", "Example", "Phone", "13", 33),
        )

        val state = buildSupportReviewState(listOf(report))

        assertEquals(1, state.reportCount)
        assertTrue(state.shareEnabled)
        assertTrue(state.text.contains("Focus controls stopped responding"))
        assertTrue(state.text.contains("Owner:   Voice"))
    }
}
