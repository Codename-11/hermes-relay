package com.hermesandroid.relay.diagnostics

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticGuidanceTest {

    @Test
    fun connectionRefusalPointsToTheOwningListener() {
        val guidance = NetworkDiagnosticGuidance.forThrowable(
            ConnectException("Failed to connect"),
            target = "Relay",
        )

        assertEquals(
            "Verify Relay is running and listening on the configured host and port.",
            guidance,
        )
    }

    @Test
    fun wrappedNetworkFailuresUseTheRootCause() {
        val guidance = NetworkDiagnosticGuidance.forThrowable(
            IllegalStateException("probe failed", UnknownHostException("missing.example")),
            target = "Dashboard",
        )

        assertTrue(guidance.orEmpty().contains("hostname"))
    }

    @Test
    fun transportFailuresHaveDistinctNextSteps() {
        assertTrue(
            NetworkDiagnosticGuidance.forThrowable(SocketTimeoutException(), "API")
                .orEmpty().contains("routing or firewall"),
        )
        assertTrue(
            NetworkDiagnosticGuidance.forThrowable(NoRouteToHostException(), "API")
                .orEmpty().contains("network path"),
        )
        assertTrue(
            NetworkDiagnosticGuidance.forThrowable(SSLHandshakeException("bad cert"), "Relay")
                .orEmpty().contains("TLS"),
        )
    }

    @Test
    fun httpStatusGuidanceIsSpecificAndUnknownSuccessHasNone() {
        assertTrue(NetworkDiagnosticGuidance.forHttpStatus(401, "API").orEmpty().contains("credentials"))
        assertTrue(NetworkDiagnosticGuidance.forHttpStatus(404, "Relay").orEmpty().contains("route"))
        assertTrue(NetworkDiagnosticGuidance.forHttpStatus(429, "Relay").orEmpty().contains("backoff"))
        assertTrue(NetworkDiagnosticGuidance.forHttpStatus(503, "API").orEmpty().contains("server logs"))
        assertNull(NetworkDiagnosticGuidance.forHttpStatus(204, "API"))
    }
}
