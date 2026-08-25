package com.hermesandroid.relay.viewmodel.connection

import com.hermesandroid.relay.data.BotChatTarget
import com.hermesandroid.relay.data.BotGatewayRosterStatus
import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.data.BotGatewayRouteKey
import com.hermesandroid.relay.data.BotGroupRoom
import com.hermesandroid.relay.data.BotModeRoster
import com.hermesandroid.relay.data.BotModeState
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.GatewayProfileAuthChoice
import com.hermesandroid.relay.data.GatewayProfileCreateRequest
import com.hermesandroid.relay.data.GatewayProfilePatch
import com.hermesandroid.relay.data.GatewayProfileSection
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class BotModeGatewaySnapshot(
    val connection: Connection,
    val dashboardUrl: String,
    val installId: String?,
    val roster: BotModeRoster,
    val stale: Boolean,
    val error: String?,
)

class BotModeController(
    private val scope: CoroutineScope,
    private val connections: StateFlow<List<Connection>>,
    private val activeConnectionId: StateFlow<String?>,
    private val dashboardUrlProvider: (Connection) -> String,
    private val dashboardClientFactory: (connectionId: String, dashboardUrl: String) -> DashboardApiClient,
    private val gatewayLeaseFactory: (
        connectionId: String,
        dashboardUrl: String,
        profileName: String,
        retain: Boolean,
    ) -> UpstreamTransportController.RouteGatewayLease,
) {
    private val refreshMutex = Mutex()
    private val refreshGeneration = AtomicLong(0L)
    private val snapshots = linkedMapOf<String, BotModeGatewaySnapshot>()
    private val _state = MutableStateFlow(BotModeState())
    val state: StateFlow<BotModeState> = _state.asStateFlow()

    fun refresh() {
        scope.launch { refreshNow() }
    }

    suspend fun refreshNow() {
        refreshMutex.withLock {
            val generation = refreshGeneration.incrementAndGet()
            val fleet = connections.value.toList()
            val liveIds = fleet.mapTo(linkedSetOf(), Connection::id)
            snapshots.keys.retainAll(liveIds)
            _state.value = aggregateForTest(
                fleet = fleet,
                snapshots = snapshots,
                loading = fleet.isNotEmpty(),
            )
            if (fleet.isEmpty()) {
                _state.value = BotModeState(error = "Connect to Hermes to use Bot Mode")
                return
            }

            val limiter = Semaphore(3)
            val results = coroutineScope {
                fleet.map { connection ->
                    async {
                        limiter.withPermit { loadConnection(connection) }
                    }
                }.awaitAll()
            }
            if (refreshGeneration.get() != generation) return
            val currentIds = connections.value.mapTo(linkedSetOf(), Connection::id)
            results.filter { it.connection.id in currentIds }.forEach { result ->
                snapshots[result.connection.id] = result
            }
            snapshots.keys.retainAll(currentIds)
            _state.value = aggregateForTest(
                fleet = connections.value,
                snapshots = snapshots,
                loading = false,
            )
        }
    }

    private suspend fun loadConnection(connection: Connection): BotModeGatewaySnapshot {
        val dashboardUrl = dashboardUrlProvider(connection).trim()
        val prior = snapshots[connection.id]
        if (dashboardUrl.isBlank()) {
            return prior?.copy(
                connection = connection,
                stale = true,
                error = "Gateway is not configured",
            ) ?: BotModeGatewaySnapshot(
                connection = connection,
                dashboardUrl = dashboardUrl,
                installId = null,
                roster = BotModeRoster(),
                stale = true,
                error = "Gateway is not configured",
            )
        }
        val statusClient = dashboardClientFactory(connection.id, dashboardUrl)
        val installId = try {
            statusClient.getStatus().getOrNull()?.installId?.trim()?.takeIf(String::isNotEmpty)
        } finally {
            statusClient.shutdown()
        }
        val rosterResult = gatewayLeaseFactory(connection.id, dashboardUrl, "default", false).use { lease ->
            lease.client.listBotModeRoster()
        }
        return rosterResult.fold(
            onSuccess = { roster ->
                BotModeGatewaySnapshot(
                    connection = connection,
                    dashboardUrl = dashboardUrl,
                    installId = installId ?: prior?.installId,
                    roster = roster,
                    stale = false,
                    error = null,
                )
            },
            onFailure = { error ->
                prior?.copy(
                    connection = connection,
                    dashboardUrl = dashboardUrl,
                    installId = installId ?: prior.installId,
                    stale = true,
                    error = error.message ?: "Gateway unavailable",
                ) ?: BotModeGatewaySnapshot(
                    connection = connection,
                    dashboardUrl = dashboardUrl,
                    installId = installId,
                    roster = BotModeRoster(),
                    stale = true,
                    error = error.message ?: "Gateway unavailable",
                )
            },
        )
    }

    suspend fun ensureCanonicalBotChat(route: BotGatewayRoute): Result<BotChatTarget> {
        val connection = connectionFor(route)
            ?: return Result.failure(IllegalStateException("The Bot's gateway was removed"))
        val dashboardUrl = dashboardUrlProvider(connection).trim()
        if (dashboardUrl.isBlank()) {
            return Result.failure(IllegalStateException("The Bot's gateway is not configured"))
        }
        return gatewayLeaseFactory(connection.id, dashboardUrl, route.profileName, false).use { lease ->
            lease.client.ensureCanonicalBotChat(route.profileName)
        }.mapCatching { target ->
                check(connectionFor(route) != null) { "The Bot's gateway was removed while opening Bot Chat" }
                target
            }
    }

    fun acquireGateway(route: BotGatewayRoute): Result<UpstreamTransportController.RouteGatewayLease> {
        val connection = connectionFor(route)
            ?: return Result.failure(IllegalStateException("The Bot's gateway was removed"))
        val dashboardUrl = dashboardUrlProvider(connection).trim()
        if (dashboardUrl.isBlank()) {
            return Result.failure(IllegalStateException("The Bot's gateway is not configured"))
        }
        return Result.success(gatewayLeaseFactory(connection.id, dashboardUrl, route.profileName, true))
    }

    fun dashboardClient(route: BotGatewayRoute): Result<DashboardApiClient> {
        val connection = connectionFor(route)
            ?: return Result.failure(IllegalStateException("The Bot's gateway was removed"))
        val dashboardUrl = dashboardUrlProvider(connection).trim()
        if (dashboardUrl.isBlank()) {
            return Result.failure(IllegalStateException("The Bot's gateway is not configured"))
        }
        return Result.success(dashboardClientFactory(connection.id, dashboardUrl))
    }

    suspend fun createBot(
        connectionId: String,
        name: String,
        title: String,
        description: String,
    ): Result<String> {
        val connection = connections.value.firstOrNull { it.id == connectionId }
            ?: return Result.failure(IllegalStateException("The target gateway was removed"))
        val dashboardUrl = dashboardUrlProvider(connection).trim()
        if (dashboardUrl.isBlank()) {
            return Result.failure(IllegalStateException("The target gateway is not configured"))
        }
        val lease = gatewayLeaseFactory(connection.id, dashboardUrl, "default", false)
        val client = lease.client
        val cleanTitle = title.trim().ifBlank { name.trim() }.take(128)
        val result = try {
            client.createProfile(
            GatewayProfileCreateRequest(
                name = name.trim(),
                description = description.trim().takeIf(String::isNotBlank),
                cloneFrom = "default",
                authChoice = GatewayProfileAuthChoice.Shared,
            ),
            ).mapCatching { created ->
            check(connections.value.any { it.id == connectionId }) {
                "The target gateway was removed while creating the Bot"
            }
            val configured = client.configureProfile(
                created.name,
                GatewayProfilePatch(
                    uiMeta = buildJsonObject {
                        put("hermes-bots", buildJsonObject {
                            put("title", cleanTitle)
                            put("created", System.currentTimeMillis())
                        })
                    },
                ),
            ).getOrThrow()
            check(GatewayProfileSection.UiMeta in configured.applied) {
                "The profile was created, but Bot Mode metadata was not saved"
            }
                created.name
            }
        } finally {
            lease.close()
        }
        if (result.isSuccess) refreshNow()
        return result
    }

    fun connectionRemoved(connectionId: String) {
        snapshots.remove(connectionId)
        refreshGeneration.incrementAndGet()
        _state.value = aggregateForTest(connections.value, snapshots, loading = false)
    }

    private fun connectionFor(route: BotGatewayRoute): Connection? =
        connections.value.firstOrNull { it.id == route.connectionId }

    internal fun aggregateForTest(
        fleet: List<Connection>,
        snapshots: Map<String, BotModeGatewaySnapshot>,
        loading: Boolean,
    ): BotModeState {
        val order = fleet.mapIndexed { index, connection -> connection.id to index }.toMap()
        val activeId = activeConnectionId.value
        val routed = snapshots.values.flatMap { snapshot ->
            snapshot.roster.bots.map { bot ->
                bot.copy(
                    route = BotGatewayRoute(
                        key = BotGatewayRouteKey(
                            connectionId = snapshot.connection.id,
                            profileName = bot.profile.name,
                        ),
                        connectionLabel = snapshot.connection.label,
                        installId = snapshot.installId,
                    ),
                    stale = snapshot.stale,
                )
            }
        }
        val collapsed = routed
            .groupBy { bot ->
                val route = checkNotNull(bot.route)
                "${route.installId ?: "connection:${route.connectionId}"}::${bot.profile.name}"
            }
            .values
            .map { candidates ->
                candidates.sortedWith(
                    compareByDescending<BotRosterEntry> { it.route?.connectionId == activeId }
                        .thenBy { it.stale }
                        .thenBy { order[it.route?.connectionId] ?: Int.MAX_VALUE },
                ).first()
            }
        val duplicateNames = collapsed.groupingBy { it.profile.name }.eachCount()
        val bots = collapsed.map { bot ->
            val route = checkNotNull(bot.route)
            bot.copy(
                handle = if ((duplicateNames[bot.profile.name] ?: 0) > 1) {
                    "${handleSlug(bot.profile.name)}-${handleSlug(route.connectionLabel)}"
                } else {
                    handleSlug(bot.profile.name)
                },
            )
        }.sortedByDescending(BotRosterEntry::latestActivityAtMs)

        val groups = snapshots.values
            .flatMap { snapshot ->
                snapshot.roster.groups.map { group -> Triple(snapshot, group, group.roomId ?: group.key) }
            }
            .groupBy { it.third }
            .values
            .map { candidates ->
                val selected = candidates.maxWithOrNull(
                    compareBy<Triple<BotModeGatewaySnapshot, BotGroupRoom, String>> { it.second.revision }
                        .thenBy { it.second.latestActivityAtMs },
                ) ?: error("group candidate list cannot be empty")
                selected.second.copy(
                    sourceConnectionIds = candidates.mapTo(linkedSetOf()) { it.first.connection.id },
                    stale = candidates.all { it.first.stale },
                )
            }
            .sortedByDescending(BotGroupRoom::latestActivityAtMs)

        val statuses = fleet.map { connection ->
            val snapshot = snapshots[connection.id]
            BotGatewayRosterStatus(
                connectionId = connection.id,
                label = connection.label,
                installId = snapshot?.installId,
                loading = loading && snapshot == null,
                stale = snapshot?.stale == true,
                error = snapshot?.error,
                botCount = snapshot?.roster?.bots?.size ?: 0,
            )
        }
        val errors = statuses.mapNotNull(BotGatewayRosterStatus::error)
        return BotModeState(
            loading = loading,
            roster = BotModeRoster(
                bots = bots,
                groups = groups,
                botModeProtocolSupported = snapshots.values.any {
                    it.roster.botModeProtocolSupported
                },
            ),
            gateways = statuses,
            error = errors.takeIf { it.size == statuses.size && bots.isEmpty() }
                ?.firstOrNull(),
        )
    }

    private fun handleSlug(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(64)
        .ifBlank { "bot" }
}
