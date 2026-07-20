package com.hermesandroid.relay.ui.screens

import android.content.Context
import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.capabilities
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.relay.RelayUrlDeriver
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.ChatRuntimeStatus
import com.hermesandroid.relay.viewmodel.ChatTransportReadiness
import com.hermesandroid.relay.viewmodel.RelayUiState
import com.hermesandroid.relay.viewmodel.StandardVoiceAvailability
import com.hermesandroid.relay.viewmodel.resolveChatRuntimeStatus
import com.hermesandroid.relay.viewmodel.statusText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Level-1 list of Hermes connections. Reachable via Settings → Connections.
 *
 * Each connection is a flat, tappable card — label + an `Active` badge (the
 * active connection) + a one-line status + the steps/timeline capability
 * summary ([ConnectionSurfaceSummary]). Tapping drills into the tabbed
 * [ConnectionDetailScreen] where rename / re-pair / revoke / remove, routes,
 * advanced setup, and relay sessions all live.
 *
 * This screen used to also host the active connection's entire deep body
 * inline (Features / Routes / Advanced / Security + a 5-button action row),
 * which made it overloaded and the list unscannable. That content now lives
 * on the detail screen; this screen is purely the list + the Add-connection FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsSettingsScreen(
    connections: List<Connection>,
    activeConnectionId: String?,
    // Live WSS state for the currently-active connection — surfaced in the
    // active card's summary so users see "Connected" / "Reconnecting…" /
    // "Stale — tap to reconnect" in real time. Ignored for non-active cards.
    activeRelayUiState: RelayUiState,
    onOpenConnection: (id: String) -> Unit,
    onAddConnection: () -> Unit,
    onBack: () -> Unit,
    // `null` means no VM wired (test harness / @Preview); the active card's
    // live summary falls back to the static pairing metadata.
    connectionViewModel: ConnectionViewModel? = null,
) {
    val activeRelayConfigured: Boolean = if (connectionViewModel != null) {
        val configured by connectionViewModel.relayConfigured.collectAsState()
        configured
    } else {
        false
    }
    val startupConnectionId: String? = if (connectionViewModel != null) {
        val startupId by connectionViewModel.startupConnectionId.collectAsState()
        startupId
    } else {
        null
    }
    val scope = rememberCoroutineScope()
    var switchingConnectionId by remember { mutableStateOf<String?>(null) }
    var justSwitchedConnectionId by remember { mutableStateOf<String?>(null) }

    // Kick a WSS reconnect on entry in case the user landed here from a Stale
    // chip (same intent as the old inline screen).
    LaunchedEffect(Unit) {
        connectionViewModel?.reconnectIfStale()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.conn_title))
                        if (connections.isNotEmpty()) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.conn_server_count,
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
    ) { innerPadding ->
        if (connections.isEmpty()) {
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
                Text(text = stringResource(R.string.conn_no_connections), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.conn_no_connections_desc),
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
                        liveState = if (isActive) activeRelayUiState else null,
                        activeConnectionViewModel = if (isActive) connectionViewModel else null,
                        relayConfigured = if (isActive) {
                            activeRelayConfigured
                        } else {
                            connection.hasConfiguredRelay()
                        },
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
                item {
                    Button(
                        onClick = onAddConnection,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                        Text(
                            text = stringResource(R.string.conn_add_connection),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
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
            shape = RoundedCornerShape(12.dp),
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

/**
 * One flat, tappable connection card: title row (label + Active badge +
 * chevron), a one-line status subtitle, and the steps/timeline capability
 * summary. The whole card opens [ConnectionDetailScreen]; the summary rows
 * are display-only (their taps fall through to the card).
 */
@Composable
private fun ConnectionListCard(
    connection: Connection,
    isActive: Boolean,
    liveState: RelayUiState?,
    activeConnectionViewModel: ConnectionViewModel?,
    relayConfigured: Boolean,
    onClick: () -> Unit,
    onSwitch: (() -> Unit)?,
    isSwitching: Boolean,
    justSwitched: Boolean,
) {
    val context = LocalContext.current
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSwitching || justSwitched) 1.5.dp else 0.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Title row ────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isActive) com.hermesandroid.relay.ui.theme.RelayRefresh.Green
                            else MaterialTheme.colorScheme.primary,
                        ),
                )
                Text(
                    text = connection.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                )
                if (isActive) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            text = stringResource(R.string.conn_active).uppercase(),
                            modifier = Modifier.padding(horizontal = 6.dp),
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
            }

            // ── Subtitle: hostname + status ──────────────────────────────
            val hostname = connection.primaryHost.ifBlank { connection.label }
            val dashboardStatus = connection.dashboardLastStatus
            val dashboardSignInRequired =
                dashboardStatus?.authRequired == true && dashboardStatus.authenticated != true
            val connectionStatus = when {
                dashboardSignInRequired -> stringResource(R.string.conn_dashboard_sign_in)
                dashboardStatus?.reachable == true -> stringResource(R.string.conn_dashboard_available)
                dashboardStatus != null && !dashboardStatus.reachable -> stringResource(R.string.conn_dashboard_offline)
                connection.resolvedDashboardUrl.isNotBlank() -> stringResource(R.string.conn_dashboard_unchecked)
                else -> stringResource(R.string.conn_dashboard_missing)
            }
            Text(
                text = if (isSwitching) {
                    stringResource(R.string.conn_connecting_to, connection.label)
                } else {
                    "Dashboard · $hostname"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (isActive) {
                    stringResource(R.string.conn_last_used_now)
                } else {
                    connection.lastUsedAt?.let { formatUsedRelative(context, it) } ?: connectionStatus
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Steps/timeline summary (every card) ──────────────────────
            ConnectionSurfaceSummary(
                connection = connection,
                isActive = isActive,
                liveState = liveState,
                activeConnectionViewModel = activeConnectionViewModel,
                relayConfigured = relayConfigured,
                // The dashboard "Sign in" row, when shown, just opens the
                // detail — the same destination as the card tap.
                onOpenDashboard = onClick,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lan,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = 2.dp),
                )
                Text(
                    text = when {
                        connection.routeCandidates.isNotEmpty() -> connection.routeCandidates
                            .sortedBy { it.priority }
                            .joinToString(" · ") { route ->
                                route.role.replaceFirstChar { it.titlecase() }
                            }
                        connection.resolvedDashboardUrl.isNotBlank() -> stringResource(R.string.conn_dashboard_only_route)
                        else -> stringResource(R.string.conn_no_routes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectionSurfaceSummary(
    connection: Connection,
    isActive: Boolean,
    liveState: RelayUiState?,
    activeConnectionViewModel: ConnectionViewModel?,
    relayConfigured: Boolean,
    onOpenDashboard: () -> Unit,
) {
    val activeApiReachable: Boolean? = if (activeConnectionViewModel != null) {
        val reachable by activeConnectionViewModel.apiServerReachable.collectAsState()
        reachable
    } else {
        null
    }
    val activeApiHealth: ConnectionViewModel.HealthStatus? = if (activeConnectionViewModel != null) {
        val health by activeConnectionViewModel.apiServerHealth.collectAsState()
        health
    } else {
        null
    }
    val activeConnection: Connection? = if (activeConnectionViewModel != null) {
        val current by activeConnectionViewModel.activeConnection.collectAsState()
        current
    } else {
        null
    }
    val activeVoiceReady: Boolean? = if (activeConnectionViewModel != null) {
        val ready by activeConnectionViewModel.voiceReady.collectAsState()
        ready
    } else {
        null
    }
    val gatewayAvailability: GatewayAvailability? = if (activeConnectionViewModel != null) {
        val availability by activeConnectionViewModel.gatewayAvailability.collectAsState()
        availability
    } else {
        null
    }
    val standardVoiceAvailability: StandardVoiceAvailability? =
        if (activeConnectionViewModel != null) {
            val availability by activeConnectionViewModel.standardVoiceAvailability.collectAsState()
            availability
        } else {
            null
        }
    val dashboardStatus = (activeConnection ?: connection).dashboardLastStatus
    val dashboardSignInRequired =
        dashboardStatus?.authRequired == true && dashboardStatus.authenticated != true

    val chatRuntimeStatus: ChatRuntimeStatus? = if (isActive) {
        resolveChatRuntimeStatus(
            gateway = when (gatewayAvailability) {
                GatewayAvailability.Ready -> ChatTransportReadiness.Ready
                GatewayAvailability.Unknown, null -> ChatTransportReadiness.Connecting
                GatewayAvailability.SignInRequired,
                GatewayAvailability.Unreachable,
                GatewayAvailability.Unsupported -> ChatTransportReadiness.Unavailable
            },
            apiSse = when {
                connection.apiServerUrl.isBlank() -> ChatTransportReadiness.NotConfigured
                activeApiReachable == true -> ChatTransportReadiness.Ready
                activeApiHealth == ConnectionViewModel.HealthStatus.Probing -> ChatTransportReadiness.Connecting
                activeApiHealth == ConnectionViewModel.HealthStatus.Unreachable -> ChatTransportReadiness.Unavailable
                else -> ChatTransportReadiness.Connecting
            },
        )
    } else {
        null
    }

    val chatText = when {
        chatRuntimeStatus is ChatRuntimeStatus.Connected -> stringResource(R.string.conn_api_ready)
        chatRuntimeStatus == ChatRuntimeStatus.Connecting -> stringResource(R.string.conn_api_checking)
        gatewayAvailability == GatewayAvailability.SignInRequired -> stringResource(R.string.conn_dashboard_sign_in)
        dashboardStatus?.reachable == true && !dashboardSignInRequired -> stringResource(R.string.conn_dashboard_available)
        connection.capabilities.chatConfigured -> stringResource(R.string.conn_api_configured)
        else -> stringResource(R.string.conn_api_missing)
    }
    val chatTone = when {
        chatRuntimeStatus is ChatRuntimeStatus.Connected -> SummaryTone.Good
        chatRuntimeStatus == ChatRuntimeStatus.Connecting -> SummaryTone.Info
        gatewayAvailability == GatewayAvailability.SignInRequired -> SummaryTone.Info
        dashboardStatus?.reachable == true && !dashboardSignInRequired -> SummaryTone.Good
        connection.capabilities.chatConfigured -> SummaryTone.Neutral
        else -> SummaryTone.Neutral
    }

    val dashboardText = when {
        connection.resolvedDashboardUrl.isBlank() -> stringResource(R.string.conn_dashboard_missing)
        dashboardStatus == null -> stringResource(R.string.conn_dashboard_unchecked)
        !dashboardStatus.reachable -> stringResource(R.string.conn_dashboard_offline)
        dashboardSignInRequired -> stringResource(R.string.conn_dashboard_sign_in)
        dashboardStatus.authenticated == true -> stringResource(R.string.conn_dashboard_signed_in)
        else -> stringResource(R.string.conn_dashboard_available)
    }
    val dashboardTone = when {
        dashboardStatus?.authenticated == true && dashboardStatus.reachable -> SummaryTone.Good
        dashboardSignInRequired -> SummaryTone.Info
        connection.resolvedDashboardUrl.isBlank() || (dashboardStatus != null && !dashboardStatus.reachable) -> SummaryTone.Warning
        else -> SummaryTone.Neutral
    }

    val relayText = when {
        !relayConfigured -> stringResource(R.string.conn_relay_optional)
        liveState != null -> liveState.statusText(connectedLabel = stringResource(R.string.conn_relay_ready))
        connection.pairedAt != null -> stringResource(R.string.conn_relay_paired)
        connection.relayUrl.isNotBlank() -> stringResource(R.string.conn_relay_configured)
        else -> stringResource(R.string.conn_relay_configure)
    }
    val relayTone = when {
        !relayConfigured -> SummaryTone.Neutral
        liveState == RelayUiState.Connected -> SummaryTone.Good
        liveState == RelayUiState.Stale || liveState == RelayUiState.Disconnected -> SummaryTone.Warning
        else -> SummaryTone.Info
    }

    val voiceText = when {
        activeVoiceReady == true -> stringResource(R.string.conn_voice_ready)
        standardVoiceAvailability == StandardVoiceAvailability.SignInRequired -> stringResource(R.string.conn_voice_sign_in)
        standardVoiceAvailability == StandardVoiceAvailability.Unsupported -> stringResource(R.string.conn_voice_unsupported)
        standardVoiceAvailability == StandardVoiceAvailability.Unreachable -> stringResource(R.string.conn_voice_offline)
        standardVoiceAvailability == StandardVoiceAvailability.Unknown && isActive -> stringResource(R.string.conn_voice_checking)
        relayConfigured -> stringResource(R.string.conn_voice_relay)
        else -> stringResource(R.string.conn_voice_optional)
    }
    val voiceTone = when {
        activeVoiceReady == true -> SummaryTone.Good
        standardVoiceAvailability == StandardVoiceAvailability.SignInRequired || relayConfigured -> SummaryTone.Info
        standardVoiceAvailability == StandardVoiceAvailability.Unsupported || standardVoiceAvailability == StandardVoiceAvailability.Unreachable -> SummaryTone.Warning
        else -> SummaryTone.Neutral
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConnectionStatusChip(Icons.Filled.Chat, stringResource(R.string.conn_chat_label), chatText, chatTone, Modifier.weight(1f))
        ConnectionStatusChip(
            Icons.Filled.Dashboard,
            stringResource(R.string.conn_manage_label),
            dashboardText,
            dashboardTone,
            Modifier.weight(1f),
            if (dashboardSignInRequired) onOpenDashboard else null,
        )
        ConnectionStatusChip(
            Icons.Filled.GraphicEq,
            stringResource(R.string.conn_voice_label),
            voiceText,
            voiceTone,
            Modifier.weight(1f),
            if (standardVoiceAvailability == StandardVoiceAvailability.SignInRequired) onOpenDashboard else null,
        )
        ConnectionStatusChip(Icons.Filled.CellTower, stringResource(R.string.conn_relay_label), relayText, relayTone, Modifier.weight(1f))
    }
}

private enum class SummaryTone { Neutral, Good, Info, Warning }

@Composable
private fun ConnectionStatusChip(
    icon: ImageVector,
    label: String,
    value: String,
    tone: SummaryTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val accent = when (tone) {
        SummaryTone.Good -> com.hermesandroid.relay.ui.theme.RelayRefresh.Green
        SummaryTone.Info -> MaterialTheme.colorScheme.primary
        SummaryTone.Warning -> MaterialTheme.colorScheme.error
        SummaryTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun Connection.hasConfiguredRelay(): Boolean {
    val trimmedRelayUrl = relayUrl.trim()
    return pairedAt != null ||
        trimmedRelayUrl.isNotBlank() &&
        !RelayUrlDeriver.isAutoManagedRelayUrl(trimmedRelayUrl, apiServerUrl)
}

private fun formatUsedRelative(context: Context, usedAtMillis: Long): String {
    val relative = if (System.currentTimeMillis() - usedAtMillis < DateUtils.MINUTE_IN_MILLIS) {
        context.getString(R.string.conn_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(
            usedAtMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }
    return context.getString(R.string.conn_last_used_format, relative)
}
