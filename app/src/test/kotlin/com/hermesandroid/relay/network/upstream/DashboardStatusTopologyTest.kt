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
        assertEquals(false, status.componentHealth.supported)
        assertEquals(emptyList<String>(), status.componentHealth.components.map { it.name })
    }

    @Test
    fun parseStatus_readsComponentHealthRollup() {
        val root = json.decodeFromString<JsonObject>(
            """{
                "auth_required": false,
                "overall": "degraded",
                "components": {
                  "gateway": {"status": "ok"},
                  "storage": {
                    "status": "degraded",
                    "message": "state DB unavailable",
                    "healthy": false,
                    "unhandled_5xx_count_5m": 2
                  },
                  "platforms": {"configured": 3, "connected": 1}
                }
            }""",
        )

        val status = DashboardApiClient.parseStatus(root)

        assertEquals(true, status.componentHealth.supported)
        assertEquals("degraded", status.componentHealth.overall)
        assertEquals(listOf("gateway", "platforms", "storage"), status.componentHealth.components.map { it.name })
        val storage = status.componentHealth.components.first { it.name == "storage" }
        assertEquals("degraded", storage.status)
        assertEquals("state DB unavailable", storage.message)
        assertEquals(false, storage.healthy)
        assertEquals(2, storage.unhandled5xxCount5m)
        val platforms = status.componentHealth.components.first { it.name == "platforms" }
        assertEquals(3, platforms.configured)
        assertEquals(1, platforms.connected)
    }
}
