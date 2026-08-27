package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardRelayIngressWiringTest {
    @Test
    fun `dashboard ingress uses only the matching authenticated origin`() {
        val ingress = "wss://hermes.example/api/plugins/hermes-relay/transport/ws"

        assertEquals(
            "https://hermes.example",
            dashboardOriginForRelayIngress("https://hermes.example", ingress),
        )
        assertEquals(
            "https://hermes.example/base",
            dashboardOriginForRelayIngress(
                "https://hermes.example/base",
                "wss://hermes.example/base/api/plugins/hermes-relay/transport/ws",
            ),
        )
        assertNull(
            dashboardOriginForRelayIngress("https://other.example", ingress),
        )
        assertNull(
            dashboardOriginForRelayIngress(
                "https://hermes.example/base",
                "wss://hermes.example/api/plugins/hermes-relay/transport/ws",
            ),
        )
        assertNull(
            dashboardOriginForRelayIngress(
                "https://hermes.example",
                "wss://hermes.example:8767/ws",
            ),
        )
    }

    @Test
    fun `dashboard ingress request carries exactly one fresh ticket`() {
        val request = dashboardRelayWebSocketRequest(
            "wss://hermes.example/api/plugins/hermes-relay/transport/ws?ticket=stale",
            "fresh-ticket",
        )

        assertEquals("fresh-ticket", request?.url?.queryParameter("ticket"))
        assertEquals(1, request?.url?.queryParameterValues("ticket")?.size)
    }
}
