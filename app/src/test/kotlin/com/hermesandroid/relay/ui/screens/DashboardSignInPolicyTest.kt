package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardSignInPolicyTest {
    @Test
    fun transportFailure_usesActionableRetryCopy_withoutChangingOtherFailures() {
        val retry = "Tap sign in to retry"

        assertEquals(
            retry,
            nativeDashboardSignInActionMessage(
                failureStage = "token_transport_socket",
                errorMessage = "secret transport detail",
                fallbackMessage = "fallback",
                transportRetryMessage = retry,
            ),
        )
        assertEquals(
            "HTTP 400",
            nativeDashboardSignInActionMessage(
                failureStage = "token_http_400",
                errorMessage = "HTTP 400",
                fallbackMessage = "fallback",
                transportRetryMessage = retry,
            ),
        )
        assertEquals(
            "fallback",
            nativeDashboardSignInActionMessage(
                failureStage = "token_shape",
                errorMessage = null,
                fallbackMessage = "fallback",
                transportRetryMessage = retry,
            ),
        )
    }
}
