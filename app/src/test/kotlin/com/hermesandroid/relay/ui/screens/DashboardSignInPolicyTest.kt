package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardSignInPolicyTest {
    @Test
    fun nativeFailuresMapToActionableSecretFreeMessageKinds() {
        assertEquals(
            NativeDashboardSignInMessageKind.CallbackRejected,
            nativeDashboardSignInMessageKind("callback_error"),
        )
        assertEquals(
            NativeDashboardSignInMessageKind.CodeRejected,
            nativeDashboardSignInMessageKind("token_http_400"),
        )
        assertEquals(
            NativeDashboardSignInMessageKind.RateLimited,
            nativeDashboardSignInMessageKind("token_http_429"),
        )
        assertEquals(
            NativeDashboardSignInMessageKind.GatewayUnavailable,
            nativeDashboardSignInMessageKind("token_http_503"),
        )
        assertEquals(
            NativeDashboardSignInMessageKind.ResponseUnsupported,
            nativeDashboardSignInMessageKind("token_shape"),
        )
        assertEquals(
            NativeDashboardSignInMessageKind.Transport,
            nativeDashboardSignInMessageKind("token_transport_socket"),
        )
        assertEquals(
            NativeDashboardSignInMessageKind.SecureStorage,
            nativeDashboardSignInMessageKind("token_store"),
        )
    }
}
