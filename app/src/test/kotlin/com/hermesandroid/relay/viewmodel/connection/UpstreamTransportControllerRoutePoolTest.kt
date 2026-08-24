package com.hermesandroid.relay.viewmodel.connection

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

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
}
