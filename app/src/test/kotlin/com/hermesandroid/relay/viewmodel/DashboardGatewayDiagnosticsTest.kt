package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DashboardGatewayDiagnosticsTest {
    @Before
    fun setUp() {
        DiagnosticsLog.clear()
    }

    @After
    fun tearDown() {
        DiagnosticsLog.clear()
    }

    @Test
    fun `dashboard failure records route and action without exposing host`() {
        recordDashboardGatewayFailure(
            dashboardUrl = "https://private-host.example:9119",
            detail = "Dashboard status probe returned no response.",
        )

        val entry = DiagnosticsLog.recent(setOf(DiagnosticCategory.Endpoint), 1).single()
        assertEquals(DiagnosticSeverity.Error, entry.severity)
        assertEquals("gateway", entry.endpointRole)
        assertEquals("Probe Dashboard / Gateway status", entry.operation)
        assertEquals("https://[host]", entry.configuredUrl)
        assertEquals("https://[host]/api/status", entry.requestUrl)
        assertFalse(entry.toString().contains("private-host.example"))
    }
}
