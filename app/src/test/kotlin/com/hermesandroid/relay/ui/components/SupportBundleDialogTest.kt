package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.reliability.ReliabilityEnvironment
import com.hermesandroid.relay.reliability.ReliabilityKind
import com.hermesandroid.relay.reliability.ReliabilityOwner
import com.hermesandroid.relay.reliability.ReliabilityReport
import com.hermesandroid.relay.reliability.ReliabilitySeverity
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticLogEntry
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportBundleDialogTest {
    @Test
    fun emptyStateCannotShare() {
        val state = buildSupportReviewState(emptyList())
        assertEquals(0, state.recordCount)
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

        assertEquals(1, state.recordCount)
        assertTrue(state.shareEnabled)
        assertTrue(state.text.contains("Focus controls stopped responding"))
        assertTrue(state.text.contains("Owner:   Voice"))
    }

    @Test
    fun diagnosticsOnlyStateIsReviewableAndRedacted() {
        val environment = ReliabilityEnvironment(
            "1.13.2",
            51,
            "sideload",
            "Example",
            "Phone",
            "16",
            36,
        )
        val diagnostic = DiagnosticLogEntry(
            timestampMs = 1_788_102_000_000,
            category = DiagnosticCategory.Auth,
            severity = DiagnosticSeverity.Warning,
            title = "Dashboard browser sign-in",
            detail = "stage=continue_requested token=do-not-share",
            operation = "dashboard_native_pkce",
            endpointRole = "public",
            configuredUrl = "https://[host]/gateway",
        )

        val state = buildSupportReviewState(
            reports = emptyList(),
            diagnostics = listOf(diagnostic),
            environment = environment,
        )

        assertEquals(1, state.recordCount)
        assertTrue(state.shareEnabled)
        assertTrue(state.text.contains("Recent in-app diagnostics"))
        assertTrue(state.text.contains("stage=continue_requested"))
        assertTrue(state.text.contains("1.13.2 (code 51) sideload"))
        assertFalse(state.text.contains("do-not-share"))
    }
}
