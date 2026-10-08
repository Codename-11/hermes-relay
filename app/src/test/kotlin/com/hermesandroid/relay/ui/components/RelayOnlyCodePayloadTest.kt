package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.ProxyEndpoint
import com.hermesandroid.relay.data.RelayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayOnlyCodePayloadTest {
    @Test
    fun existingPinnedTrustAndStandardIdentityArePreserved() {
        val route = EndpointCandidate(
            role = "plugin_proxy",
            proxy = ProxyEndpoint(
                url = "https://fixture.test:9443",
                pinSha256 = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            ),
            relay = RelayEndpoint(url = "wss://fixture.test:9443/relay/ws"),
        )
        val payload = relayOnlyCodePayload("wss://fixture.test:9443/relay/ws", " abc123 ", listOf(route))

        assertEquals(listOf(route), payload.endpoints)
        assertEquals("ABC123", payload.relay!!.code)
        assertEquals("", payload.host)
        assertEquals("", payload.key)
        assertNull(payload.dashboardUrl)
    }

    @Test
    fun changingRelayDoesNotReuseAnotherHostPin() {
        val old = EndpointCandidate(
            role = "plugin_proxy",
            proxy = ProxyEndpoint(
                url = "https://old.test:9443",
                pinSha256 = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            ),
        )
        val payload = relayOnlyCodePayload("wss://new.test:8767/ws", "ABC123", listOf(old))

        assertNull(payload.endpoints!!.single().proxy)
        assertEquals("wss://new.test:8767/ws", payload.endpoints!!.single().relay!!.url)
    }
}
