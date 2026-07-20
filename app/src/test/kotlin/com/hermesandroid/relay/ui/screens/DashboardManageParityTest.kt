package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.viewmodel.PendingMcpOAuth
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

    @Test
    fun mcpManagePlumbing_usesEffectiveProfileForListAndRowActions() {
        assertEquals(
            "/api/mcp/servers?profile=work%20profile",
            dashboardSectionRequestPath("/api/mcp/servers", "work profile"),
        )
        assertEquals(
            "/api/providers/custom-endpoints",
            dashboardSectionRequestPath("/api/providers/custom-endpoints", "work profile"),
        )
        val scoped = scopeDashboardManageItems(
            "/api/mcp/servers",
            "work profile",
            listOf(DashboardSummaryItem(id = "hosted", title = "Hosted")),
        )
        assertEquals("work profile", scoped.single().profile)
    }

    @Test
    fun oauthCapabilityGate_requiresConfirmedSupportAndNoPendingFlow() {
        assertFalse(canStartMcpOAuth(capabilitySupported = false, pending = null))
        assertTrue(canStartMcpOAuth(capabilitySupported = true, pending = null))
        assertFalse(
            canStartMcpOAuth(
                capabilitySupported = true,
                pending = PendingMcpOAuth("flow-a", "server-a", "work", "connection-a|https://a.example"),
            ),
        )
    }

    @Test
    fun dismissedPendingA_thenRequestedB_keepsDialogBoundToAAndBlocksB() {
        val pendingA = PendingMcpOAuth("flow-a", "server-a", "work", "connection-a|https://a.example")
        val requestedB = DashboardSummaryItem(id = "server-b", title = "Server B", profile = "work")

        val resolved = resolveMcpOAuthDialogItem(requestedB, pendingA)

        assertEquals("server-a", resolved?.id)
        assertEquals("server-a", resolved?.title)
        assertFalse(canStartMcpOAuth(capabilitySupported = true, pending = pendingA))
    }

    @Test
    fun connectionSwitch_holdsPendingFlowWithoutAllowingCrossHostResume() {
        val routeA = mcpOAuthRouteIdentity(
            "connection-a",
            "HTTPS://user:password@Dashboard.Example:443/manage/?ticket=opaque#fragment",
        )
        val routeB = mcpOAuthRouteIdentity("connection-b", "https://other.example/")
        val pendingA = PendingMcpOAuth("opaque-flow-a", "server-a", "work", routeA!!)

        assertEquals("connection-a|https://dashboard.example/manage", routeA)
        assertFalse(canResumeMcpOAuth(pendingA, routeB))
        assertTrue(canResumeMcpOAuth(pendingA, routeA))
    }
}
