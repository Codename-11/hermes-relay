package com.hermesandroid.relay.ui.screens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardManageParityTest {
    @Test
    fun oauthMcpRow_exposesAuthenticateOnlyForAuthoritativeOauthField() {
        val oauth = Json.parseToJsonElement(
            """{"name":"hosted","url":"https://mcp.example/mcp","transport":"http","auth":"oauth","enabled":true}""",
        ).jsonObject
        val header = Json.parseToJsonElement(
            """{"name":"hosted","url":"https://mcp.example/mcp","transport":"http","auth":"header","enabled":true}""",
        ).jsonObject

        assertTrue(dashboardActionsFor(oauth).any { it.kind == DashboardActionKind.AuthenticateMcp })
        assertFalse(dashboardActionsFor(header).any { it.kind == DashboardActionKind.AuthenticateMcp })
    }

    @Test
    fun customEndpointSummary_neverContainsSecretAndRequiresConfirmationForMutations() {
        val root = Json.parseToJsonElement(
            """{"endpoints":[{"id":"local","name":"Local","base_url":"https://llm.example/v1","model":"qwen","has_api_key":true,"api_key_preview":"sk-…1234","is_current":false}]}""",
        )

        val row = summarizeCustomEndpoints(root).single()

        assertEquals("https://llm.example/v1", row.subtitle)
        assertFalse(row.meta.orEmpty().contains("sk-"))
        assertTrue(row.actions.first { it.kind == DashboardActionKind.ActivateCustomEndpoint }.destructive)
        assertTrue(row.actions.first { it.kind == DashboardActionKind.DeleteCustomEndpoint }.destructive)
    }
}
