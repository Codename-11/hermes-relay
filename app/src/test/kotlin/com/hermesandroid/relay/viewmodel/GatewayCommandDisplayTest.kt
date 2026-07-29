package com.hermesandroid.relay.viewmodel

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GatewayCommandDisplayTest {

    @Test
    fun safeDisplayPrefersBoundedServerLiteralOverExpandedMessage() {
        val expanded = "private skill body ".repeat(500)
        val result = buildJsonObject {
            put("type", "skill")
            put("display", "/skill release-check")
            put("message", expanded)
        }

        val visible = safeGatewayCommandDisplay(result, "/skill release-check")

        assertEquals("/skill release-check", visible)
        assertFalse(visible.contains("private skill body"))
    }

    @Test
    fun safeDisplayFallsBackToLiteralInvocationForOlderHermes() {
        val result = buildJsonObject {
            put("type", "send")
            put("message", "expanded private prompt")
        }

        assertEquals(
            "/skill release-check",
            safeGatewayCommandDisplay(result, "/skill release-check"),
        )
    }

    @Test
    fun safeDisplayCapsHostileServerText() {
        val result = buildJsonObject {
            put("display", "x".repeat(10_000))
        }

        assertEquals(2_000, safeGatewayCommandDisplay(result, "/skill safe").length)
    }
}
