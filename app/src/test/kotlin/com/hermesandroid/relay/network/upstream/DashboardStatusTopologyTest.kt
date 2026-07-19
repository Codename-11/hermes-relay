package com.hermesandroid.relay.network.upstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardStatusTopologyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseStatus_readsMultiplexTopology() {
        val root = json.decodeFromString<JsonObject>(
            """{
                "auth_required": false,
                "gateway_mode": "multiplex",
                "profiles": ["default", "coder"]
            }""",
        )

        val status = DashboardApiClient.parseStatus(root)

        assertEquals("multiplex", status.gatewayMode)
        assertEquals(listOf("default", "coder"), status.profiles)
    }

    @Test
    fun parseStatus_keepsOlderPayloadCompatible() {
        val status = DashboardApiClient.parseStatus(
            json.decodeFromString<JsonObject>("""{"auth_required": true}"""),
        )

        assertNull(status.gatewayMode)
        assertEquals(emptyList<String>(), status.profiles)
    }
}
