package com.hermesandroid.relay.viewmodel.connection

import com.hermesandroid.relay.data.BotGroupMessage
import com.hermesandroid.relay.data.BotGroupRoom
import com.hermesandroid.relay.data.BotModeRoster
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotModeRosterAggregationTest {
    private val a = connection("a", "Laptop")
    private val b = connection("b", "Home Lab")
    private val connections = MutableStateFlow(listOf(a, b))
    private val active = MutableStateFlow<String?>("a")
    private val controller = BotModeController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        connections = connections,
        activeConnectionId = active,
        dashboardUrlProvider = Connection::resolvedDashboardUrl,
        dashboardClientFactory = { _, url -> DashboardApiClient(url) },
        gatewayLeaseFactory = { _, _, _, _ -> error("not used by aggregation tests") },
    )

    @Test
    fun sameInstallCollapsesBeforeHandleCountingAndActiveRouteWins() {
        val state = aggregate(
            snapshot(a, "install-1", bots = listOf(bot("default"))),
            snapshot(b, "install-1", bots = listOf(bot("default"))),
        )

        assertEquals(1, state.roster.bots.size)
        assertEquals("a", state.roster.bots.single().route?.connectionId)
        assertEquals("default", state.roster.bots.single().handle)
    }

    @Test
    fun sameNameOnDifferentInstallsUsesSourceQualifiedHandles() {
        val state = aggregate(
            snapshot(a, "install-a", bots = listOf(bot("default"), bot("unique"))),
            snapshot(b, "install-b", bots = listOf(bot("default"))),
        )

        assertEquals(
            setOf("default-laptop", "default-home-lab", "unique"),
            state.roster.bots.mapTo(linkedSetOf(), BotRosterEntry::handle),
        )
    }

    @Test
    fun missingInstallIdsNeverCollapseAndOfflineRowsStayStale() {
        val state = aggregate(
            snapshot(a, null, bots = listOf(bot("worker")), stale = true),
            snapshot(b, null, bots = listOf(bot("worker"))),
        )

        assertEquals(2, state.roster.bots.size)
        assertTrue(state.roster.bots.first { it.route?.connectionId == "a" }.stale)
        assertFalse(state.roster.bots.first { it.route?.connectionId == "b" }.stale)
    }

    @Test
    fun groupProjectionDeduplicatesByRoomIdentityAndKeepsAllSources() {
        val old = room(revision = 1, text = "old")
        val fresh = room(revision = 2, text = "fresh")
        val state = aggregate(
            snapshot(a, "install-a", groups = listOf(old)),
            snapshot(b, "install-b", groups = listOf(fresh)),
        )

        assertEquals(1, state.roster.groups.size)
        assertEquals("fresh", state.roster.groups.single().latestMessage?.text)
        assertEquals(setOf("a", "b"), state.roster.groups.single().sourceConnectionIds)
    }

    private fun aggregate(vararg snapshots: BotModeGatewaySnapshot) = controller.aggregateForTest(
        fleet = connections.value,
        snapshots = snapshots.associateBy { it.connection.id },
        loading = false,
    )

    private fun snapshot(
        connection: Connection,
        installId: String?,
        bots: List<BotRosterEntry> = emptyList(),
        groups: List<BotGroupRoom> = emptyList(),
        stale: Boolean = false,
    ) = BotModeGatewaySnapshot(
        connection = connection,
        dashboardUrl = connection.resolvedDashboardUrl,
        installId = installId,
        roster = BotModeRoster(bots = bots, groups = groups, botModeProtocolSupported = true),
        stale = stale,
        error = if (stale) "offline" else null,
    )

    private fun bot(name: String) = BotRosterEntry(
        profile = Profile(name = name, model = "gpt"),
        displayName = name,
    )

    private fun room(revision: Long, text: String) = BotGroupRoom(
        key = "id:room-1",
        roomId = "room-1",
        name = "Council",
        revision = revision,
        messages = listOf(
            BotGroupMessage(
                senderName = "Bot",
                senderKind = "member",
                text = text,
                atMs = revision,
            ),
        ),
    )

    private fun connection(id: String, label: String) = Connection(
        id = id,
        label = label,
        apiServerUrl = "",
        relayUrl = "",
        dashboardUrl = "http://$id.invalid",
        tokenStoreKey = "token-$id",
    )
}
