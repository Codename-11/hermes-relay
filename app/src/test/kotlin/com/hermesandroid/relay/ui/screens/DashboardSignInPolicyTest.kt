package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.network.upstream.DashboardAuthProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun clientLocalNativeFailuresUseEmbeddedCookieFallback() {
        listOf(
            NativeDashboardSignInMessageKind.Transport,
            NativeDashboardSignInMessageKind.SecureStorage,
            NativeDashboardSignInMessageKind.ResponseUnsupported,
        ).forEach {
            assertTrue(it.name, nativeDashboardSignInCanUseEmbeddedFallback(it))
        }
    }

    @Test
    fun providerAndGatewayRejectionsDoNotStartAnotherAuthorizationAttempt() {
        listOf(
            NativeDashboardSignInMessageKind.CallbackRejected,
            NativeDashboardSignInMessageKind.CodeRejected,
            NativeDashboardSignInMessageKind.GatewayRejected,
            NativeDashboardSignInMessageKind.RateLimited,
            NativeDashboardSignInMessageKind.GatewayUnavailable,
            NativeDashboardSignInMessageKind.AttemptInactive,
        ).forEach {
            assertFalse(it.name, nativeDashboardSignInCanUseEmbeddedFallback(it))
        }
    }

    @Test
    fun nativeCapabilityRoutesBasicAndRedirectProvidersThroughSystemBrowser() {
        val nativeFlows = listOf("cookie", "native_pkce")
        val basic = DashboardAuthProvider(name = "basic", supportsPassword = true)
        val selfHosted = DashboardAuthProvider(name = "self-hosted")

        assertEquals(
            DashboardProviderEntryMode.NativeBrowser,
            dashboardProviderEntryMode(basic, nativeFlows),
        )
        assertEquals(
            DashboardProviderEntryMode.NativeBrowser,
            dashboardProviderEntryMode(selfHosted, nativeFlows),
        )
        assertEquals(
            DashboardProviderEntryMode.Password,
            dashboardProviderEntryMode(basic, listOf("cookie")),
        )
        assertEquals(
            DashboardProviderEntryMode.EmbeddedBrowser,
            dashboardProviderEntryMode(selfHosted, listOf("cookie")),
        )
    }
}
