package com.hermesandroid.relay.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.data.gatewayRouteUrl
import com.hermesandroid.relay.data.isDashboardOnlyRoute
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.ui.components.sameGatewayRouteBase
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.ChatRuntimeStatus
import com.hermesandroid.relay.viewmodel.ChatTransportPath
import com.hermesandroid.relay.viewmodel.RelayRowState
import com.hermesandroid.relay.viewmodel.RelayUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Level-1 registry of saved Hermes gateways. Reachable via Settings → Gateways.
 *
 * Each gateway is a flat, tappable card — name + an `Active` badge (the
 * active gateway) + gateway reachability + the current route. Tapping drills into the tabbed
 * [ConnectionDetailScreen] where rename / re-pair / revoke / remove, routes,
 * advanced setup, and relay sessions all live.
 *
 * This screen used to also host the active connection's entire deep body
 * inline (Features / Routes / Advanced / Security + a 5-button action row),
 * which made it overloaded and the list unscannable. That content now lives
 * on the detail screen; this screen is purely the registry + Add gateway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsSettingsScreen(
    connections: List<Connection>,
    activeConnectionId: String?,
    // Kept as the reconnect trigger for existing call sites. Registry status
    // itself is Gateway-owned and never inferred from the optional Relay.
    activeRelayUiState: RelayUiState,
    onOpenConnection: (id: String) -> Unit,
    onAddConnection: () -> Unit,
    addConnectionEnabled: Boolean = true,
    onBack: () -> Unit,
    // `null` means no VM wired (test harness / @Preview); the active card falls
    // back to persisted Gateway reachability and route metadata.
    connectionViewModel: ConnectionViewModel? = null,
) {
    val startupConnectionId: String? = if (connectionViewModel != null) {
        val startupId by connectionViewModel.startupConnectionId.collectAsState()
        startupId
    } else {
        null
    }
    val connectionsHydrated: Boolean = if (connectionViewModel != null) {
        val hydrated by connectionViewModel.connectionsHydrated.collectAsState()
        hydrated
    } else {
        true
    }
    val scope = rememberCoroutineScope()
    var switchingConnectionId by remember { mutableStateOf<String?>(null) }
    var justSwitchedConnectionId by remember { mutableStateOf<String?>(null) }

    // Kick a WSS reconnect on entry in case the user landed here from a Stale
    // chip (same intent as the old inline screen).
    LaunchedEffect(activeRelayUiState) {
        connectionViewModel?.reconnectIfStale()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.gateways_title))
                        if (connections.isNotEmpty()) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.gateway_count,
                                    connections.size,
                                    connections.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.conn_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                Button(
                    onClick = onAddConnection,
                    enabled = addConnectionEnabled && connectionsHydrated,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.gateway_add),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                    if (!addConnectionEnabled) {
                        Text(
                            text = stringResource(R.string.conn_add_requires_parent_access),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        if (!connectionsHydrated) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (connections.isEmpty()) {
            // Shown in practice only during tests / after a wipe; cold start
            // seeds a default connection, so the list is rarely truly empty.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(R.string.gateways_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.gateways_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (connections.size > 1 && connectionViewModel != null) {
                    item {
                        StartupConnectionSelector(
                            connections = connections,
                            startupConnectionId = startupConnectionId,
                            onSelect = connectionViewModel::setStartupConnection,
                        )
                    }
                }
                items(connections, key = { it.id }) { connection ->
                    val isActive = connection.id == activeConnectionId
                    ConnectionListCard(
                        connection = connection,
                        isActive = isActive,
                        activeConnectionViewModel = if (isActive) connectionViewModel else null,
                        onClick = { onOpenConnection(connection.id) },
                        onSwitch = if (!isActive && connectionViewModel != null) {
                            {
                                if (switchingConnectionId == null) {
                                    scope.launch {
                                        switchingConnectionId = connection.id
                                        connectionViewModel.switchConnection(connection.id).join()
                                        switchingConnectionId = null
                                        if (connectionViewModel.activeConnectionId.value == connection.id) {
                                            justSwitchedConnectionId = connection.id
                                            delay(800)
                                            if (justSwitchedConnectionId == connection.id) {
                                                justSwitchedConnectionId = null
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            null
                        },
                        isSwitching = switchingConnectionId == connection.id,
                        justSwitched = justSwitchedConnectionId == connection.id,
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}
@Composable
private fun StartupConnectionSelector(
    connections: List<Connection>,
    startupConnectionId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = startupConnectionId
        ?.let { id -> connections.firstOrNull { it.id == id }?.label }
        ?: stringResource(R.string.conn_startup_last_used)

    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = appearanceRoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.AccessTime,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.conn_startup_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = selectedLabel, style = MaterialTheme.typography.bodyLarge)
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.conn_startup_choose),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.conn_startup_last_used))
                        Text(
                            text = stringResource(R.string.conn_startup_recommended),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            connections.forEach { connection ->
                DropdownMenuItem(
                    text = { Text(connection.label) },
                    onClick = {
                        expanded = false
                        onSelect(connection.id)
                    },
                )
            }
        }
    }
}
internal fun gatewayRegistryTransportLabel(url: String): String? = when (
    url.substringBefore("://", missingDelimiterValue = "").lowercase()
) {
    "http" -> "HTTP"
    "https" -> "HTTPS"
    "ws" -> "WS"
    "wss" -> "WSS"
    else -> null
}
/**
 * One flat, tappable gateway card. Registry cards intentionally stop at the
 * saved installation's identity, reachability, and current route; capability
 * inventory and route diagnostics belong to [ConnectionDetailScreen].
 */
@Composable
private fun ConnectionListCard(
    connection: Connection,
    isActive: Boolean,
    activeConnectionViewModel: ConnectionViewModel?,
    onClick: () -> Unit,
    onSwitch: (() -> Unit)?,
    isSwitching: Boolean,
    justSwitched: Boolean,
) {
    val activeConnection: Connection? = if (activeConnectionViewModel != null) {
        val current by activeConnectionViewModel.activeConnection.collectAsState()
        current
    } else {
        null
    }
    val gatewayAvailability: GatewayAvailability? = if (activeConnectionViewModel != null) {
        val availability by activeConnectionViewModel.gatewayAvailability.collectAsState()
        availability
    } else {
        null
    }
    val activeEndpoint: EndpointCandidate? = if (activeConnectionViewModel != null) {
        val endpoint by activeConnectionViewModel.activeEndpoint.collectAsState()
        endpoint
    } else {
        null
    }
    val effectiveDashboardUrl: String = if (activeConnectionViewModel != null) {
        val url by activeConnectionViewModel.effectiveDashboardUrl.collectAsState()
        url
    } else {
        connection.resolvedDashboardUrl
    }
    val selectedProfile: Profile? = if (activeConnectionViewModel != null) {
        val profile by activeConnectionViewModel.selectedProfile.collectAsState()
        profile
    } else {
        null
    }
    val presentedConnection = activeConnection ?: connection
    val presentation = resolveGatewayCardPresentation(
        connection = presentedConnection,
        active = isActive,
        gatewayAvailability = gatewayAvailability,
        activeEndpoint = activeEndpoint,
        effectiveDashboardUrl = effectiveDashboardUrl,
    )
    // Active card: muted indigo wash instead of full-strength primaryContainer —
    // a card-sized fill of the brand blue overwhelmed body text (2026-06-10
    // feedback); small accents keep the vivid blue.
    val targetContainerColor = if (isActive || isSwitching || justSwitched) {
        com.hermesandroid.relay.ui.theme.RelayRefresh.ElectricMuted.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val containerColor by animateColorAsState(targetValue = targetContainerColor, label = "connectionCardColor")
    val borderColor by animateColorAsState(
        targetValue = if (isSwitching || justSwitched) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0f)
        },
        label = "connectionCardBorder",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = appearanceRoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSwitching || justSwitched) 1.5.dp else 0.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(gatewayStatusColor(presentation.status)),
            )
            Icon(
                imageVector = Icons.Filled.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = presentedConnection.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isActive) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.conn_active),
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
                Text(
                    text = gatewayStatusText(presentation.status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = gatewayStatusColor(presentation.status),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(presentation.routeName, presentation.transport)
                        .joinToString(" · ")
                        .ifBlank { stringResource(R.string.gateway_route_not_configured) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActive) {
                    Text(
                        text = listOfNotNull(
                            stringResource(R.string.conn_info_profile),
                            selectedProfile?.name?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.settings_server_default),
                            selectedProfile?.model?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onSwitch != null || isSwitching || justSwitched) {
                OutlinedButton(
                    onClick = { onSwitch?.invoke() },
                    enabled = !isSwitching && !justSwitched,
                ) {
                    when {
                        isSwitching -> {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.conn_switching), modifier = Modifier.padding(start = 6.dp))
                        }
                        justSwitched -> {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.conn_switched), modifier = Modifier.padding(start = 6.dp))
                        }
                        else -> Text(stringResource(R.string.conn_switch))
                    }
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal enum class GatewayCardStatus {
    Online,
    SignInRequired,
    Offline,
    Checking,
    NotChecked,
    LastCheckSignInRequired,
    LastCheckSucceeded,
    LastCheckFailed,
    NoRoute,
    Unavailable,
}
internal data class GatewayCardPresentation(
    val status: GatewayCardStatus,
    val routeName: String?,
    val transport: String?,
)

/** Registry-only summary: one saved installation, its reachability, and its current route. */
internal fun resolveGatewayCardPresentation(
    connection: Connection,
    active: Boolean,
    gatewayAvailability: GatewayAvailability?,
    activeEndpoint: EndpointCandidate?,
    effectiveDashboardUrl: String,
): GatewayCardPresentation {
    val persistedStatus = connection.dashboardLastStatus
    val selectedRoute = activeEndpoint
        ?: connection.preferredRouteRole
            ?.let { preferred ->
                connection.routeCandidates.firstOrNull { it.role.equals(preferred, ignoreCase = true) }
            }
        ?: connection.routeCandidates.minByOrNull { it.priority }
    val selectedGatewayUrl = selectedRoute?.gatewayRouteUrl()
    val effectiveGatewayUrl = effectiveDashboardUrl.trim().trimEnd('/').takeIf { it.isNotBlank() }
    val routeUrl = if (active) {
        effectiveGatewayUrl ?: selectedGatewayUrl
    } else {
        selectedGatewayUrl ?: effectiveGatewayUrl
    }
    val matchingSelectedRoute = selectedRoute?.takeIf {
        selectedGatewayUrl != null && routeUrl != null && sameGatewayRouteBase(selectedGatewayUrl, routeUrl)
    }
    val routeRole = matchingSelectedRoute?.role
        ?: routeUrl?.let { Connection.inferRouteRole(it) }

    val status = if (routeUrl == null) {
        GatewayCardStatus.NoRoute
    } else if (active && gatewayAvailability != null) {
        when (gatewayAvailability) {
            GatewayAvailability.Ready -> GatewayCardStatus.Online
            GatewayAvailability.SignInRequired -> GatewayCardStatus.SignInRequired
            GatewayAvailability.Unreachable -> GatewayCardStatus.Offline
            GatewayAvailability.Unknown -> GatewayCardStatus.Checking
            GatewayAvailability.Unsupported -> GatewayCardStatus.Unavailable
        }
    } else {
        when {
            persistedStatus?.reachable == true &&
                persistedStatus.authRequired == true &&
                persistedStatus.authenticated != true -> GatewayCardStatus.LastCheckSignInRequired
            persistedStatus?.reachable == true -> GatewayCardStatus.LastCheckSucceeded
            persistedStatus != null -> GatewayCardStatus.LastCheckFailed
            else -> GatewayCardStatus.NotChecked
        }
    }

    return GatewayCardPresentation(
        status = status,
        routeName = routeRole?.let { gatewayRegistryRouteLabel(it, matchingSelectedRoute?.displayName) },
        transport = routeUrl?.let(::gatewayRegistryTransportLabel),
    )
}

internal fun gatewayRegistryRouteLabel(role: String, displayName: String? = null): String = when (role.trim().lowercase()) {
    "lan" -> "LAN"
    "tailscale" -> "Tailscale"
    "public", "https" -> "Public"
    "dashboard", "authenticated_dashboard" -> "Dashboard"
    "plugin_proxy", "plugin-proxy" -> "Hermes Secure Link"
    "outbound_broker", "broker", "relay_broker" -> "Hermes Reach"
    else -> displayName?.trim()?.takeIf { it.isNotBlank() }
        ?: role.trim().replaceFirstChar { it.uppercase() }
}

@Composable
private fun gatewayStatusText(status: GatewayCardStatus): String = when (status) {
    GatewayCardStatus.Online -> stringResource(R.string.gateway_status_online)
    GatewayCardStatus.SignInRequired -> stringResource(R.string.gateway_status_sign_in_required)
    GatewayCardStatus.Offline -> stringResource(R.string.gateway_status_offline)
    GatewayCardStatus.Checking -> stringResource(R.string.gateway_status_checking)
    GatewayCardStatus.NotChecked -> stringResource(R.string.gateway_status_not_checked)
    GatewayCardStatus.LastCheckSignInRequired -> stringResource(R.string.gateway_status_last_check_sign_in_required)
    GatewayCardStatus.LastCheckSucceeded -> stringResource(R.string.gateway_status_last_check_succeeded)
    GatewayCardStatus.LastCheckFailed -> stringResource(R.string.gateway_status_last_check_failed)
    GatewayCardStatus.NoRoute -> stringResource(R.string.gateway_status_no_route)
    GatewayCardStatus.Unavailable -> stringResource(R.string.gateway_status_unavailable)
}

@Composable
private fun gatewayStatusColor(status: GatewayCardStatus) = when (status) {
    GatewayCardStatus.Online -> com.hermesandroid.relay.ui.theme.RelayRefresh.Green
    GatewayCardStatus.SignInRequired,
    GatewayCardStatus.Checking -> MaterialTheme.colorScheme.primary
    GatewayCardStatus.Offline,
    GatewayCardStatus.NoRoute,
    GatewayCardStatus.Unavailable -> MaterialTheme.colorScheme.error
    GatewayCardStatus.NotChecked,
    GatewayCardStatus.LastCheckSignInRequired,
    GatewayCardStatus.LastCheckSucceeded,
    GatewayCardStatus.LastCheckFailed -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal enum class DashboardAuthPresentation {
    SignedIn,
    SignInRequired,
    NoSignInRequired,
    Available,
    Unreachable,
    Unchecked,
    Missing,
}

internal enum class CurrentHermesSurface { Gateway, ApiFallback, Connecting, Unavailable, Inactive }

internal data class ConnectionClarityPresentation(
    val dashboardOrigin: String?,
    val dashboardAuth: DashboardAuthPresentation,
    val dashboardAuthProvider: String?,
    val currentSurface: CurrentHermesSurface,
    val currentPath: String?,
    val configuredFallbacks: List<String>,
    val relayConfigured: Boolean,
    val relayState: RelayUiState?,
    val relayPath: String?,
)

/** Pure mapping shared by the list card and Overview so neither invents route state. */
internal fun resolveConnectionClarityPresentation(
    connection: Connection,
    active: Boolean,
    activeEndpoint: EndpointCandidate?,
    effectiveDashboardUrl: String,
    chatRuntimeStatus: ChatRuntimeStatus?,
    relayConfigured: Boolean,
    relayRowState: RelayRowState?,
): ConnectionClarityPresentation {
    val dashboardOrigin = effectiveDashboardUrl.trim().trimEnd('/').takeIf { it.isNotBlank() }
    val dashboardStatus = connection.dashboardLastStatus
    val dashboardAuth = when {
        dashboardOrigin == null -> DashboardAuthPresentation.Missing
        dashboardStatus == null -> DashboardAuthPresentation.Unchecked
        !dashboardStatus.reachable -> DashboardAuthPresentation.Unreachable
        dashboardStatus.authenticated == true -> DashboardAuthPresentation.SignedIn
        dashboardStatus.authRequired == true -> DashboardAuthPresentation.SignInRequired
        dashboardStatus.authRequired == false -> DashboardAuthPresentation.NoSignInRequired
        else -> DashboardAuthPresentation.Available
    }
    val currentSurface = when {
        !active -> CurrentHermesSurface.Inactive
        chatRuntimeStatus is ChatRuntimeStatus.Connected &&
            chatRuntimeStatus.transport == ChatTransportPath.Gateway -> CurrentHermesSurface.Gateway
        chatRuntimeStatus is ChatRuntimeStatus.Connected &&
            chatRuntimeStatus.transport == ChatTransportPath.ApiSse -> CurrentHermesSurface.ApiFallback
        chatRuntimeStatus == ChatRuntimeStatus.Connecting -> CurrentHermesSurface.Connecting
        else -> CurrentHermesSurface.Unavailable
    }
    val canonicalDashboardOwnsGateway = currentSurface == CurrentHermesSurface.Gateway &&
        !connection.authenticatedDashboardOrigin.isNullOrBlank()
    val activePath = when (currentSurface) {
        CurrentHermesSurface.Gateway -> if (canonicalDashboardOwnsGateway) {
            if (dashboardOrigin?.startsWith("https://", ignoreCase = true) == true) {
                "HTTPS Dashboard"
            } else {
                "Dashboard"
            }
        } else {
            activeEndpoint?.displayLabel()
                ?: if (dashboardOrigin?.startsWith("https://", ignoreCase = true) == true) {
                    "HTTPS Dashboard"
                } else {
                    "Dashboard"
                }
        }
        CurrentHermesSurface.ApiFallback -> activeEndpoint?.displayLabel()
        CurrentHermesSurface.Connecting,
        CurrentHermesSurface.Unavailable,
        CurrentHermesSurface.Inactive -> null
    }
    val fallbackLabels = connection.routeCandidates
        .asSequence()
        .filterNot { it.isDashboardOnlyRoute() || it.role.equals("authenticated_dashboard", ignoreCase = true) }
        .filterNot { candidate ->
            currentSurface in setOf(CurrentHermesSurface.Gateway, CurrentHermesSurface.ApiFallback) &&
                !canonicalDashboardOwnsGateway &&
                activeEndpoint?.role?.equals(candidate.role, ignoreCase = true) == true
        }
        .sortedBy { it.priority }
        .map { it.displayLabel() }
        .distinct()
        .toList()
    return ConnectionClarityPresentation(
        dashboardOrigin = dashboardOrigin,
        dashboardAuth = dashboardAuth,
        dashboardAuthProvider = dashboardStatus?.authProvider?.let(::dashboardAuthProviderLabel),
        currentSurface = currentSurface,
        currentPath = activePath,
        configuredFallbacks = fallbackLabels,
        relayConfigured = relayConfigured,
        relayState = relayRowState?.phase,
        relayPath = relayRowState?.activeEndpointRole?.let(::routeRoleLabel),
    )
}

internal fun dashboardAuthProviderLabel(provider: String): String = when (provider.trim().lowercase()) {
    "self-hosted", "self_hosted", "oidc" -> "Self-hosted OIDC"
    "basic", "password" -> "Password"
    "nous" -> "Nous"
    else -> provider.trim().replaceFirstChar { it.uppercase() }
}

private fun routeRoleLabel(role: String): String = when (role.trim().lowercase()) {
    "lan" -> "LAN"
    "tailscale" -> "Tailscale"
    "public", "https" -> "HTTPS"
    "plugin_proxy", "plugin-proxy" -> "Hermes Secure Link"
    else -> role.trim()
}

@Composable
internal fun ConnectionClaritySummary(
    clarity: ConnectionClarityPresentation,
    compact: Boolean,
) {
    val authText = when (clarity.dashboardAuth) {
        DashboardAuthPresentation.SignedIn -> listOfNotNull(
            stringResource(R.string.active_section_signed_in),
            clarity.dashboardAuthProvider,
        ).joinToString(" · ")
        DashboardAuthPresentation.SignInRequired -> stringResource(R.string.active_section_sign_in_required)
        DashboardAuthPresentation.NoSignInRequired -> stringResource(R.string.active_section_no_sign_in_required)
        DashboardAuthPresentation.Available -> stringResource(R.string.active_section_available)
        DashboardAuthPresentation.Unreachable -> stringResource(R.string.active_section_unreachable)
        DashboardAuthPresentation.Unchecked -> stringResource(R.string.active_section_unchecked)
        DashboardAuthPresentation.Missing -> stringResource(R.string.active_section_missing)
    }
    val surfaceText = when (clarity.currentSurface) {
        CurrentHermesSurface.Gateway -> stringResource(R.string.chat_failure_route_gateway)
        CurrentHermesSurface.ApiFallback -> stringResource(R.string.chat_failure_route_api)
        CurrentHermesSurface.Connecting -> stringResource(R.string.active_section_checking)
        CurrentHermesSurface.Unavailable -> stringResource(R.string.active_section_unavailable)
        CurrentHermesSurface.Inactive -> stringResource(R.string.detail_inactive_badge)
    }
    val relayText = when {
        !clarity.relayConfigured -> stringResource(R.string.relay_state_optional)
        clarity.relayState == null -> stringResource(R.string.active_section_configured)
        clarity.relayState == RelayUiState.Connected -> stringResource(R.string.relay_state_ready)
        clarity.relayState == RelayUiState.Connecting -> stringResource(R.string.relay_state_reconnecting)
        clarity.relayState == RelayUiState.Expired -> stringResource(R.string.relay_state_needs_repair)
        else -> stringResource(R.string.relay_state_unavailable)
    }
    val configuredLabel = stringResource(R.string.active_section_configured)
    val fallbackValues = clarity.configuredFallbacks.map { "$it · $configuredLabel" }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        shape = appearanceRoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            ConnectionClarityRow(
                label = stringResource(R.string.dashboard_gateway_title),
                value = clarity.dashboardOrigin ?: stringResource(R.string.active_section_missing),
                detail = authText,
            )
            ConnectionClarityRow(
                label = stringResource(R.string.active_section_using_now),
                value = listOfNotNull(surfaceText, clarity.currentPath).joinToString(" · "),
            )
            ConnectionClarityRow(
                label = stringResource(R.string.active_section_fallbacks),
                value = fallbackValues.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                    ?: stringResource(R.string.active_section_no_fallback_routes),
                detail = if (clarity.configuredFallbacks.isNotEmpty()) {
                    stringResource(R.string.active_section_configured_not_probed)
                } else {
                    null
                },
            )
            ConnectionClarityRow(
                label = stringResource(R.string.active_section_relay_connected_features),
                value = listOfNotNull(relayText, clarity.relayPath).joinToString(" · "),
                detail = stringResource(R.string.active_section_relay_ownership),
            )
        }
    }
}

@Composable
private fun ConnectionClarityRow(
    label: String,
    value: String,
    detail: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Column(modifier = Modifier.weight(0.58f)) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
