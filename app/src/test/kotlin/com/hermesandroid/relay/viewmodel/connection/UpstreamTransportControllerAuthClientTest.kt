package com.hermesandroid.relay.viewmodel.connection

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(first.dispatcher.executorService.isShutdown)
    }
}
