package com.hermesandroid.relay.viewmodel.connection

import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayClientHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotModeControllerTest {
    @Test
    fun refreshAggregatesTwoGatewaysPreservesOfflineCacheAndNeverSwitchesActive() = runBlocking {
        val aHarness = GatewayClientHarness()
        val bHarness = GatewayClientHarness()
        val routeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clients = mapOf(
            "a" to routeClient(aHarness, routeScope),
            "b" to routeClient(bHarness, routeScope),
        )
        aHarness.profilesListPayload = rosterPayload("default")
        bHarness.profilesListPayload = rosterPayload("researcher")
        val connections = MutableStateFlow(
            listOf(
                connection("a", "Laptop", aHarness),
                connection("b", "Home Lab", bHarness),
            ),
        )
        val active = MutableStateFlow<String?>("a")
        val controller = BotModeController(
            scope = this,
            connections = connections,
            activeConnectionId = active,
            dashboardUrlProvider = Connection::resolvedDashboardUrl,
            dashboardClientFactory = { _, url -> DashboardApiClient(url, OkHttpClient()) },
            gatewayLeaseFactory = { connectionId, _, _, _ ->
                UpstreamTransportController.RouteGatewayLease(checkNotNull(clients[connectionId])) {}
            },
        )
        try {
            controller.refreshNow()
            assertEquals(setOf("default", "researcher"), controller.state.value.roster.bots.map { it.profile.name }.toSet())
            assertEquals("a", active.value)

            bHarness.rpcErrors["profiles.list"] = 5006 to "offline"
            controller.refreshNow()
            assertTrue(
                controller.state.value.roster.bots
                    .first { it.profile.name == "researcher" }
                    .stale,
            )
            assertEquals("a", active.value)

            connections.value = connections.value.filterNot { it.id == "b" }
            controller.connectionRemoved("b")
            assertEquals(listOf("default"), controller.state.value.roster.bots.map { it.profile.name })
        } finally {
            clients.values.forEach(GatewayChatClient::shutdown)
            routeScope.cancel()
            aHarness.shutdown()
            bHarness.shutdown()
        }
    }

    private fun routeClient(harness: GatewayClientHarness, scope: CoroutineScope) = GatewayChatClient(
        initialDashboardClient = DashboardApiClient(
            harness.server.url("/").toString().trimEnd('/'),
            OkHttpClient(),
        ),
        fixedSessionProfile = "default",
        okHttpClient = OkHttpClient(),
        callbackDispatcher = { it() },
        scope = scope,
    )

    private fun rosterPayload(profile: String) = buildJsonObject {
        put("profiles", JsonArray(listOf(buildJsonObject {
            put("name", profile)
            put("model", "gpt")
            put("is_default", profile == "default")
        })))
        put("bot_mode_protocol", true)
    }

    private fun connection(id: String, label: String, harness: GatewayClientHarness) = Connection(
        id = id,
        label = label,
        apiServerUrl = "",
        relayUrl = "",
        dashboardUrl = harness.server.url("/").toString().trimEnd('/'),
        tokenStoreKey = "token-$id",
    )
}
