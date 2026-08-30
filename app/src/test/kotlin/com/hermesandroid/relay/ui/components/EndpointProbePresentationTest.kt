package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.network.shared.RouteProbeOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointProbePresentationTest {
    @Test
    fun `superseded cancellation is not rendered as unreachable`() {
        assertTrue(
            RouteProbeOutcome(
                reachable = false,
                detail = "InterruptedIOException",
                atMillis = 1L,
            ).isSupersededProbeFailure(),
        )
        assertTrue(
            RouteProbeOutcome(
                reachable = false,
                detail = "Canceled",
                atMillis = 1L,
            ).isSupersededProbeFailure(),
        )
    }

    @Test
    fun `real network failure stays visible`() {
        assertFalse(
            RouteProbeOutcome(
                reachable = false,
                detail = "Connection refused",
                atMillis = 1L,
            ).isSupersededProbeFailure(),
        )
    }

    @Test
    fun `split surface truth keeps dashboard healthy while optional services fail`() {
        val reachable = RouteProbeOutcome(reachable = true, atMillis = 1L)
        val unreachable = RouteProbeOutcome(
            reachable = false,
            detail = "Connection refused",
            atMillis = 1L,
        )

        assertTrue(
            routeSurfaceProbePresentation("https://hermes.example.com", reachable) ==
                RouteSurfaceProbePresentation.Reachable,
        )
        assertTrue(
            routeSurfaceProbePresentation("http://hermes.lan:8642", unreachable) ==
                RouteSurfaceProbePresentation.Unreachable,
        )
        assertTrue(
            routeSurfaceProbePresentation("ws://hermes.lan:8767", unreachable) ==
                RouteSurfaceProbePresentation.Unreachable,
        )
        assertTrue(
            "missing optional API/Relay surfaces stay neutral",
            routeSurfaceProbePresentation(null, null) ==
                RouteSurfaceProbePresentation.NotConfigured,
        )
    }

    @Test
    fun `selected route does not claim fresh reachability without a probe`() {
        val superseded = RouteProbeOutcome(
            reachable = false,
            detail = "superseded",
            atMillis = 1L,
        )

        assertTrue(
            routeReachabilityPresentation(isProbing = false, outcome = null) ==
                RouteReachabilityPresentation.NotChecked,
        )
        assertTrue(
            routeReachabilityPresentation(isProbing = false, outcome = superseded) ==
                RouteReachabilityPresentation.NotChecked,
        )
        assertTrue(
            routeReachabilityPresentation(isProbing = true, outcome = null) ==
                RouteReachabilityPresentation.Checking,
        )
        assertTrue(
            routeReachabilityPresentation(
                isProbing = false,
                outcome = RouteProbeOutcome(reachable = true, atMillis = 1L),
            ) == RouteReachabilityPresentation.Reachable,
        )
        assertTrue(
            routeReachabilityPresentation(
                isProbing = false,
                outcome = RouteProbeOutcome(reachable = false, detail = "timeout", atMillis = 1L),
            ) == RouteReachabilityPresentation.Unreachable,
        )
    }
}
