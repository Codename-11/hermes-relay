package com.hermesandroid.relay.viewmodel.connection

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class UpstreamTransportControllerAuthClientTest {
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
}
