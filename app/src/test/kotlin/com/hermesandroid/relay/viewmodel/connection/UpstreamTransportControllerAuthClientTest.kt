package com.hermesandroid.relay.viewmodel.connection

import android.content.Context
import com.hermesandroid.relay.network.upstream.DashboardBearerAuth
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayConnectionState
import com.hermesandroid.relay.network.upstream.InMemoryDashboardCookieStore
import com.hermesandroid.relay.network.upstream.NativeDashboardTokenStore
import com.hermesandroid.relay.network.upstream.NativeDashboardTokens
import com.hermesandroid.relay.network.upstream.StoredDashboardCookie
import io.mockk.mockk
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class UpstreamTransportControllerAuthClientTest {
    @Test
    fun liveGatewayReadyCannotBeDowngradedByLateDashboardProbe() {
        assertEquals(
            GatewayAvailability.Ready,
            reconcileGatewayAvailability(
                current = GatewayAvailability.Ready,
                probed = GatewayAvailability.Unreachable,
                liveState = GatewayConnectionState.Ready,
            ),
        )
    }

    @Test
    fun retiredGatewayClientCallbackCannotChangeCurrentConnection() {
        val current = mockk<GatewayChatClient>()
        val retired = mockk<GatewayChatClient>()
        val cached = Triple("connection-b", "https://dashboard-b.example", current)

        assertFalse(isCurrentGatewayClientCallback("connection-a", retired, cached))
        assertFalse(isCurrentGatewayClientCallback("connection-b", retired, cached))
        assertTrue(isCurrentGatewayClientCallback("connection-b", current, cached))
    }

    @Test
    fun dashboardCookieStoresRemainConnectionScoped() {
        val requestedKeys = mutableMapOf<String, String>()
        val controller = UpstreamTransportController(
            context = mockk<Context>(relaxed = true),
            activeConnectionIdProvider = { null },
            dashboardUrlProvider = { null },
            gatewayKeepAliveProvider = { false },
            tokenStoreKeyProvider = { connectionId ->
                "token-store-$connectionId".also { requestedKeys[connectionId] = it }
            },
        )

        val firstA = controller.dashboardCookieStoreFor("connection-a")
        val secondA = controller.dashboardCookieStoreFor("connection-a")
        val storeB = controller.dashboardCookieStoreFor("connection-b")

        assertSame(firstA, secondA)
        assertNotSame(firstA, storeB)
        assertEquals("token-store-connection-a", requestedKeys["connection-a"])
        assertEquals("token-store-connection-b", requestedKeys["connection-b"])
    }

    @Test
    fun dashboardHttpClient_isReusedUntilRouteChangesThenDisposed() {
        var dashboardUrl = "https://hermes.example.test"
        val controller = UpstreamTransportController(
            context = mockk<Context>(relaxed = true),
            activeConnectionIdProvider = { null },
            dashboardUrlProvider = { dashboardUrl },
            gatewayKeepAliveProvider = { false },
        )

        val first = controller.dashboardHttpClientForActive(dashboardUrl)
        val reused = controller.dashboardHttpClientForActive(dashboardUrl)
        assertSame(first, reused)

        dashboardUrl = "https://hermes.example.test/alternate"
        val moved = controller.dashboardHttpClientForActive(dashboardUrl)
        assertNotSame(first, moved)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!first.dispatcher.executorService.isShutdown && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue("replaced dashboard client was not disposed", first.dispatcher.executorService.isShutdown)
    }

    @Test
    fun cookieFlowRetiresNativeBearerWithoutClearingImportedCookies() {
        val cookieStore = InMemoryDashboardCookieStore().apply {
            save(
                listOf(
                    StoredDashboardCookie(
                        name = "hermes_session",
                        value = "cookie-session",
                        domain = "hermes.example.test",
                        path = "/",
                        secure = true,
                        httpOnly = true,
                        hostOnly = true,
                        persistent = false,
                        expiresAt = Long.MAX_VALUE,
                    ),
                ),
            )
        }
        val tokenStore = MemoryNativeDashboardTokenStore().apply {
            save(
                NativeDashboardTokens(
                    accessToken = "stale-nous-access",
                    refreshToken = "stale-nous-refresh",
                    provider = "nous",
                ),
            )
        }
        val bearerArguments = mutableListOf<DashboardBearerAuth?>()
        var observedCookieStore: com.hermesandroid.relay.network.upstream.DashboardCookieStore? = null
        val controller = UpstreamTransportController(
            context = mockk<Context>(relaxed = true),
            activeConnectionIdProvider = { "connection-a" },
            dashboardUrlProvider = { "https://hermes.example.test" },
            gatewayKeepAliveProvider = { false },
            dashboardHttpClientFactory = { observedStore, bearer ->
                observedCookieStore = observedStore
                bearerArguments += bearer
                okhttp3.OkHttpClient()
            },
            dashboardTokenStoreFactory = { tokenStore },
            dashboardCookieStoreFactory = { _, _ -> cookieStore },
        )

        controller.dashboardCookieClientForActive("https://hermes.example.test").shutdown()
        controller.retireNativeDashboardAuthentication("connection-a")

        assertNull(bearerArguments.single())
        assertSame(cookieStore, observedCookieStore)
        assertNull(tokenStore.load())
        assertEquals("cookie-session", cookieStore.load().single().value)
    }
}

private class MemoryNativeDashboardTokenStore : NativeDashboardTokenStore {
    override val coordinationKey: String = "cookie-ownership-test"
    private var tokens: NativeDashboardTokens? = null

    override fun load(): NativeDashboardTokens? = tokens

    override fun save(tokens: NativeDashboardTokens) {
        this.tokens = tokens
    }

    override fun clear() {
        tokens = null
    }
}
