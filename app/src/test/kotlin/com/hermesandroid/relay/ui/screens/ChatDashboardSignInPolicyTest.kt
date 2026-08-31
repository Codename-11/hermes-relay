package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.viewmodel.ChatFailureNotice
import com.hermesandroid.relay.viewmodel.ChatFailureRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDashboardSignInPolicyTest {
    @Test
    fun retainedHistoryKeepsRequiredSignInActionVisible() {
        assertTrue(
            shouldShowRetainedHistoryDashboardSignIn(
                hasMessages = true,
                gatewayAvailability = GatewayAvailability.SignInRequired,
                apiReachable = false,
                supervised = false,
            ),
        )
    }

    @Test
    fun bannerDoesNotCoverUsableFallbackOrUnrelatedOfflineStates() {
        assertFalse(
            shouldShowRetainedHistoryDashboardSignIn(
                hasMessages = true,
                gatewayAvailability = GatewayAvailability.SignInRequired,
                apiReachable = true,
                supervised = false,
            ),
        )
        assertFalse(
            shouldShowRetainedHistoryDashboardSignIn(
                hasMessages = true,
                gatewayAvailability = GatewayAvailability.Unreachable,
                apiReachable = false,
                supervised = false,
            ),
        )
        assertFalse(
            shouldShowRetainedHistoryDashboardSignIn(
                hasMessages = false,
                gatewayAvailability = GatewayAvailability.SignInRequired,
                apiReachable = false,
                supervised = false,
            ),
        )
    }

    @Test
    fun supervisedSurfaceKeepsParentOwnedRecoveryPolicy() {
        assertFalse(
            shouldShowRetainedHistoryDashboardSignIn(
                hasMessages = true,
                gatewayAvailability = GatewayAvailability.SignInRequired,
                apiReachable = false,
                supervised = true,
            ),
        )
    }

    @Test
    fun signInCardSuppressesOnlyDownstreamGatewayAuthAndHistoryFailures() {
        val historyFailure = ChatFailureNotice(
            sessionId = "session",
            turnId = "history-session",
            rawError = "HTTP 401: no_cookie",
            route = ChatFailureRoute.GATEWAY,
        )
        val unrelatedFailure = historyFailure.copy(
            turnId = "turn-2",
            rawError = "Provider returned an invalid response",
        )
        val apiFailure = historyFailure.copy(route = ChatFailureRoute.API_FALLBACK)

        assertFalse(shouldPresentChatFailureDuringDashboardSignIn(historyFailure, true))
        assertTrue(shouldPresentChatFailureDuringDashboardSignIn(unrelatedFailure, true))
        assertTrue(shouldPresentChatFailureDuringDashboardSignIn(apiFailure, true))
        assertTrue(shouldPresentChatFailureDuringDashboardSignIn(historyFailure, false))
    }
}
