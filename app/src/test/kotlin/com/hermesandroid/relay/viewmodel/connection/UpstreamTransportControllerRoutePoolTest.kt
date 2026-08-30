package com.hermesandroid.relay.viewmodel.connection

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class UpstreamTransportControllerRoutePoolTest {
    private val controller = UpstreamTransportController(
        context = mockk<Context>(relaxed = true),
        activeConnectionIdProvider = { "a" },
        dashboardUrlProvider = { "http://a.invalid" },
        gatewayKeepAliveProvider = { false },
        trustedDashboardUrlProvider = { id -> "http://$id.invalid" },
    )

    @Test
    fun exactConnectionAndProfileOwnOneClient() {
        val first = controller.acquireGatewayRoute("a", "http://a.invalid", "default")
        val repeated = controller.acquireGatewayRoute("a", "http://a.invalid", "default")
        val sibling = controller.acquireGatewayRoute("a", "http://a.invalid", "writer")
        val remoteTwin = controller.acquireGatewayRoute("b", "http://b.invalid", "default")

        assertSame(first.client, repeated.client)
        assertNotSame(first.client, sibling.client)
        assertNotSame(first.client, remoteTwin.client)

        first.close()
        repeated.close()
        sibling.close()
        remoteTwin.close()
    }

    @Test
    fun connectionRemovalDisposesOnlyThatConnectionsRoutes() {
        val a = controller.acquireGatewayRoute("a", "http://a.invalid", "default", retain = true)
        val b = controller.acquireGatewayRoute("b", "http://b.invalid", "default", retain = true)
        val oldA = a.client
        val oldB = b.client

        controller.disposeConnectionRouteClients("a")
        a.close()
        val newA = controller.acquireGatewayRoute("a", "http://a.invalid", "default")
        val sameB = controller.acquireGatewayRoute("b", "http://b.invalid", "default")

        assertNotSame(oldA, newA.client)
        assertSame(oldB, sameB.client)

        b.close()
        newA.close()
        sameB.close()
    }

    @Test
    fun staleLeaseCannotEvictRouteReplacement() {
        val old = controller.acquireGatewayRoute("a", "http://a.invalid", "default", retain = true)
        val replacement = controller.acquireGatewayRoute(
            "a",
            "http://a.invalid/alternate",
            "default",
        )
        assertNotSame(old.client, replacement.client)

        old.close()
        val repeated = controller.acquireGatewayRoute(
            "a",
            "http://a.invalid/alternate",
            "default",
        )
        assertSame(replacement.client, repeated.client)

        replacement.close()
        repeated.close()
    }

    @Test
    fun sessionRestClientIsReusedOnlyForTheExactConnectionAndRoute() {
        val first = controller.dashboardSessionClientFor("a", "http://a.invalid")
        val repeated = controller.dashboardSessionClientFor("a", "http://a.invalid")
        val moved = controller.dashboardSessionClientFor("a", "http://a.invalid/alternate")
        val remote = controller.dashboardSessionClientFor("b", "http://b.invalid")

        assertSame(first, repeated)
        assertNotSame(first, moved)
        assertNotSame(moved, remote)
    }

    @Test
    fun connectionRemovalRetiresItsSessionRestClient() {
        val previous = controller.dashboardSessionClientFor("a", "http://a.invalid")

        controller.disposeConnectionRouteClients("a")
        val replacement = controller.dashboardSessionClientFor("a", "http://a.invalid")

        assertNotSame(previous, replacement)
    }

    @Test
    fun dashboardWrappersShareOneHttpStackUntilExactRouteIsRetired() {
        var httpStackBuilds = 0
        val scoped = UpstreamTransportController(
            context = mockk<Context>(relaxed = true),
            activeConnectionIdProvider = { "a" },
            dashboardUrlProvider = { "http://a.invalid" },
            gatewayKeepAliveProvider = { false },
            trustedDashboardUrlProvider = { "http://$it.invalid" },
            dashboardHttpClientFactory = { _, _ ->
                httpStackBuilds += 1
                okhttp3.OkHttpClient()
            },
        )

        val first = scoped.dashboardClientFor("a", "http://a.invalid/")
        val second = scoped.dashboardClientFor("a", "http://a.invalid")
        first.shutdown()
        second.shutdown()

        assertEquals(1, httpStackBuilds)

        scoped.disposeConnectionRouteClients("a")
        scoped.dashboardClientFor("a", "http://a.invalid")

        assertEquals(2, httpStackBuilds)
    }

    @Test
    fun borrowedWrapperShutdownLeavesSharedRouteUsableAndADisposalLeavesBUsable() = runBlocking {
        val serverA = MockWebServer().apply { start() }
        val serverB = MockWebServer().apply { start() }
        try {
            repeat(2) {
                serverA.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"auth_required":false}"""),
                )
            }
            repeat(2) {
                serverB.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"auth_required":false}"""),
                )
            }
            val scoped = UpstreamTransportController(
                context = mockk<Context>(relaxed = true),
                activeConnectionIdProvider = { "a" },
                dashboardUrlProvider = { serverA.url("/").toString() },
                gatewayKeepAliveProvider = { false },
                trustedDashboardUrlProvider = { id ->
                    if (id == "a") serverA.url("/").toString() else serverB.url("/").toString()
                },
            )

            val firstA = scoped.dashboardClientFor("a", serverA.url("/").toString())
            firstA.shutdown()
            scoped.dashboardClientFor("a", serverA.url("/").toString())
                .getStatus().getOrThrow()

            val firstB = scoped.dashboardClientFor("b", serverB.url("/").toString())
            firstB.getStatus().getOrThrow()
            scoped.disposeConnectionRouteClients("a")
            firstB.getStatus().getOrThrow()
            Unit
        } finally {
            serverA.shutdown()
            serverB.shutdown()
        }
    }

    @Test
    fun disposingConnectionCancelsItsSharedDashboardCalls() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            start()
        }
        try {
            val scoped = UpstreamTransportController(
                context = mockk<Context>(relaxed = true),
                activeConnectionIdProvider = { "a" },
                dashboardUrlProvider = { server.url("/").toString() },
                gatewayKeepAliveProvider = { false },
                trustedDashboardUrlProvider = { server.url("/").toString() },
            )
            val client = scoped.dashboardClientFor("a", server.url("/").toString())
            val request = async(Dispatchers.IO) { client.getStatus() }
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)

            scoped.disposeConnectionRouteClients("a")

            assertTrue(withTimeout(5_000L) { request.await() }.isFailure)
        } finally {
            server.shutdown()
        }
    }
}
