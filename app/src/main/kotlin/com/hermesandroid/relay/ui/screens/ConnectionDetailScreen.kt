package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.capabilities
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.data.gatewayRouteUrl
import com.hermesandroid.relay.ui.components.ActiveCardAdvancedSection
import com.hermesandroid.relay.ui.components.ActiveCardRoutesSection
import com.hermesandroid.relay.ui.components.ActiveCardSecurityPosture
import com.hermesandroid.relay.ui.components.ApiServerInfoSheet
import com.hermesandroid.relay.ui.components.DashboardAddressEditorDialog
import com.hermesandroid.relay.ui.components.InsecureConnectionAckDialog
import com.hermesandroid.relay.ui.components.RelayInfoSheet
import com.hermesandroid.relay.ui.components.sameGatewayRouteBase
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.theme.LocalBrand
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.RelayUiState
import com.hermesandroid.relay.viewmodel.StandardVoiceAvailability
import kotlinx.coroutines.launch

/**
 * Tabbed detail for a single Hermes connection — the level-2 screen the
 * (slim) [ConnectionsSettingsScreen] list drills into. Replaces the old
 * "everything crammed into the active card" body.
 *
 * Layout:
 *  - TopAppBar: back + connection label + an `Active` badge (active conn) +
 *    an overflow `⋮` menu (Rename / Re-pair / Revoke / Remove).
 *  - When this connection is the **active** one, a 4-tab segmented bar:
 *    **Overview** (current route + capability outcomes) · **Routes** (ADR 24
 *    endpoint management) · **Access** (transport posture + Relay sessions) ·
 *    **Advanced** (manual URL / insecure / manual pairing).
 *  - When this connection is **not** active, only Overview shows — the deep
 *    live content reads the single active-connection VM state, so we surface
 *    a "Switch to this connection" CTA instead of stale/foreign data.
 *
 * All deep sections are the existing reusable composables in
 * `ActiveConnectionSections.kt`; this screen is orchestration + the
 * screen-scoped info sheets / confirm dialogs (hoisted here so a tab switch
 * or scroll can't silently dismiss an open sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionDetailScreen(
    connectionId: String,
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onRename: (id: String, newLabel: String) -> Unit,
    onRepair: (id: String) -> Unit,
    onRevoke: (id: String) -> Unit,
    onRemove: (id: String) -> Unit,
    onSwitchToConnection: (id: String) -> Unit,
    onNavigateToManage: () -> Unit,
    onNavigateToPairedDevices: () -> Unit,
) {
    val context = LocalContext.current
    val isDarkTheme = LocalBrand.current.isDark
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val routeResetFailed = stringResource(R.string.active_section_use_selected_route_failed)

    val connections by connectionViewModel.connections.collectAsState()
    val connectionsHydrated by connectionViewModel.connectionsHydrated.collectAsState()
    val activeConnectionId by connectionViewModel.activeConnectionId.collectAsState()
    val relayUiState by connectionViewModel.relayUiState.collectAsState()
    val connection = connections.firstOrNull { it.id == connectionId }
    if (!connectionsHydrated) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    // Connection was removed (e.g. via the overflow menu) — leave the screen
    // only after the store has authoritatively hydrated.
    LaunchedEffect(connectionsHydrated, connection == null) {
        if (connectionsHydrated && connection == null) onBack()
    }
    if (connection == null) return

    val isActive = connectionId == activeConnectionId

    // Screen-scoped sheet/dialog visibility (survives tab switches + scroll).
    var showApiInfoSheet by remember { mutableStateOf(false) }
    var showRelayInfoSheet by remember { mutableStateOf(false) }
    var showInsecureAckDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDashboardEditor by remember { mutableStateOf(false) }
    var showRevokeConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val tabs = detailTabs(isActive)
    // Reset selection when the active/non-active shape changes so we never
    // index past the available tabs.
    var selectedTab by remember(isActive) { mutableStateOf(0) }
    val safeIndex = selectedTab.coerceIn(0, tabs.lastIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = connection.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isActive) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ) {
                                Text(
                                    text = stringResource(R.string.detail_active),
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.detail_more_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.detail_rename)) },
                            onClick = {
                                menuExpanded = false
                                showRenameDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (connection.pairedAt == null) stringResource(R.string.detail_pair_relay) else stringResource(R.string.detail_repair))
                            },
                            onClick = {
                                menuExpanded = false
                                onRepair(connectionId)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.QrCodeScanner,
                                    contentDescription = null,
                                )
                            },
                        )
                        if (connection.pairedAt != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_revoke)) },
                                onClick = {
                                    menuExpanded = false
                                    showRevokeConfirm = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.detail_remove), color = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                menuExpanded = false
                                showRemoveConfirm = true
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (tabs.size > 1) {
                TabRow(selectedTabIndex = safeIndex) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = safeIndex == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(when (tab) {
                                    DetailTab.Overview -> stringResource(R.string.detail_tab_overview)
                                    DetailTab.Routes -> stringResource(R.string.detail_tab_routes)
                                    DetailTab.Access -> stringResource(R.string.detail_tab_access)
                                    DetailTab.Advanced -> stringResource(R.string.detail_tab_advanced)
                                })
                            },
                        )
                    }
                }
            }

            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tabs[safeIndex]) {
                    DetailTab.Overview -> {
                        if (isActive) {
                            ActiveOverview(
                                connectionViewModel = connectionViewModel,
                                connection = connection,
                                relayUiState = relayUiState,
                                onReconnect = onReconnect,
                                onRepair = { onRepair(connectionId) },
                                onOpenApiInfo = { showApiInfoSheet = true },
                                onOpenDashboard = onNavigateToManage,
                                onOpenRelayInfo = { showRelayInfoSheet = true },
                                onOpenRoutes = {
                                    selectedTab = tabs.indexOf(DetailTab.Routes)
                                        .takeIf { it >= 0 }
                                        ?: selectedTab
                                },
                            )
                        } else {
                            InactiveOverview(
                                connection = connection,
                                onSwitch = { onSwitchToConnection(connectionId) },
                                onRepair = { onRepair(connectionId) },
                            )
                        }
                    }

                    DetailTab.Routes -> ActiveCardRoutesSection(
                        connectionViewModel = connectionViewModel,
                        connection = connection,
                        liveState = relayUiState,
                        onEditDashboard = { showDashboardEditor = true },
                    )

                    DetailTab.Access -> ActiveCardSecurityPosture(
                        connectionViewModel = connectionViewModel,
                        onNavigateToPairedDevices = onNavigateToPairedDevices,
                        onRevokeRelay = { showRevokeConfirm = true },
                        onOpenDashboardSignIn = onNavigateToManage,
                        onUseSelectedRoute = {
                            connectionViewModel.useSelectedDashboardRoute { result ->
                                result.onFailure {
                                    scope.launch {
                                        snackbarHost.showSnackbar(routeResetFailed)
                                    }
                                }
                            }
                        },
                    )

                    DetailTab.Advanced -> ActiveCardAdvancedSection(
                        connectionViewModel = connectionViewModel,
                        isDarkTheme = isDarkTheme,
                        onPairRelay = { onRepair(connectionId) },
                        onInsecureAckRequested = { showInsecureAckDialog = true },
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // ── Screen-scope sheets + dialogs ────────────────────────────────────
    if (showApiInfoSheet) {
        ApiServerInfoSheet(
            connectionViewModel = connectionViewModel,
            onDismiss = { showApiInfoSheet = false },
        )
    }
    if (showRelayInfoSheet) {
        RelayInfoSheet(
            connectionViewModel = connectionViewModel,
            onDismiss = { showRelayInfoSheet = false },
        )
    }
    if (showInsecureAckDialog) {
        InsecureConnectionAckDialog(
            onConfirm = { reason ->
                connectionViewModel.setInsecureAckComplete(reason)
                connectionViewModel.setInsecureMode(true)
                showInsecureAckDialog = false
            },
            onCancel = { showInsecureAckDialog = false },
        )
    }
    if (showDashboardEditor) {
        DashboardAddressEditorDialog(
            initialUrl = connection.resolvedDashboardUrl,
            onSave = { dashboardUrl, onResult ->
                connectionViewModel.updateDashboardAddress(dashboardUrl, onResult)
            },
            onDismiss = { showDashboardEditor = false },
        )
    }
    if (showRenameDialog) {
        RenameConnectionDialog(
            initialLabel = connection.label,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newLabel ->
                onRename(connectionId, newLabel)
                showRenameDialog = false
            },
        )
    }
    if (showRevokeConfirm) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirm = false },
            title = { Text(stringResource(R.string.detail_revoke_title)) },
            text = {
                Text(stringResource(R.string.detail_revoke_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRevoke(connectionId)
                        showRevokeConfirm = false
                    },
                ) { Text(stringResource(R.string.detail_revoke)) }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirm = false }) { Text(stringResource(R.string.detail_cancel)) }
            },
        )
    }
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text(stringResource(R.string.detail_remove_title)) },
            text = {
                Text(stringResource(R.string.detail_remove_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // The LaunchedEffect above pops the screen once the
                        // connection disappears from the list.
                        onRemove(connectionId)
                        showRemoveConfirm = false
                    },
                ) {
                    Text(stringResource(R.string.detail_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text(stringResource(R.string.detail_cancel)) }
            },
        )
    }
}

internal enum class DetailTab {
    Overview,
    Routes,
    Access,
    Advanced,
}

internal fun detailTabs(isActive: Boolean): List<DetailTab> = if (isActive) {
    listOf(DetailTab.Overview, DetailTab.Routes, DetailTab.Access, DetailTab.Advanced)
} else {
    listOf(DetailTab.Overview)
}

/**
 * Overview for the **active** connection: the selected route, the three
 * standard upstream outcomes, and optional Relay/API drill-down rows.
 */
@Composable
private fun ActiveOverview(
    connectionViewModel: ConnectionViewModel,
    connection: Connection,
    relayUiState: RelayUiState,
    onReconnect: () -> Unit,
    onRepair: () -> Unit,
    onOpenApiInfo: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenRelayInfo: () -> Unit,
    onOpenRoutes: () -> Unit,
) {
    val gatewayAvailability by connectionViewModel.gatewayAvailability.collectAsState()
    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val apiHealth by connectionViewModel.apiServerHealth.collectAsState()
    val activeEndpoint by connectionViewModel.activeEndpoint.collectAsState()
    val effectiveDashboardUrl by connectionViewModel.effectiveDashboardUrl.collectAsState()
    val relayConfigured by connectionViewModel.relayConfigured.collectAsState()
    val standardVoiceAvailability by connectionViewModel.standardVoiceAvailability.collectAsState()
    val usingApiFallback = apiReachable && gatewayAvailability in setOf(
        GatewayAvailability.SignInRequired,
        GatewayAvailability.Unreachable,
        GatewayAvailability.Unsupported,
    )
    val currentRouteUrl = if (usingApiFallback) {
        activeEndpoint?.api?.url ?: connection.apiServerUrl
    } else {
        effectiveDashboardUrl
    }

    val route = resolveDetailRoutePresentation(
        activeEndpoint = activeEndpoint,
        effectiveDashboardUrl = currentRouteUrl,
    )
    val routeStatus = when {
        gatewayAvailability == GatewayAvailability.Ready || usingApiFallback ->
            OverviewStatus(stringResource(R.string.active_section_reachable), OverviewTone.Good)
        gatewayAvailability == GatewayAvailability.SignInRequired ->
            OverviewStatus(stringResource(R.string.active_section_sign_in), OverviewTone.Info)
        gatewayAvailability == GatewayAvailability.Unknown ||
            apiHealth == ConnectionViewModel.HealthStatus.Probing ->
            OverviewStatus(stringResource(R.string.active_section_checking), OverviewTone.Neutral)
        gatewayAvailability == GatewayAvailability.Unsupported ->
            OverviewStatus(stringResource(R.string.active_section_unsupported), OverviewTone.Warning)
        else -> OverviewStatus(stringResource(R.string.active_section_unreachable), OverviewTone.Warning)
    }
    val chatStatus = when {
        gatewayAvailability == GatewayAvailability.Ready || usingApiFallback ->
            OverviewStatus(stringResource(R.string.active_section_ready), OverviewTone.Good)
        gatewayAvailability == GatewayAvailability.SignInRequired ->
            OverviewStatus(stringResource(R.string.active_section_sign_in), OverviewTone.Info)
        gatewayAvailability == GatewayAvailability.Unreachable && !apiReachable ->
            OverviewStatus(stringResource(R.string.active_section_offline), OverviewTone.Warning)
        else -> OverviewStatus(stringResource(R.string.active_section_checking), OverviewTone.Neutral)
    }
    val dashboardStatus = connection.dashboardLastStatus
    val dashboardSignInRequired =
        dashboardStatus?.authRequired == true && dashboardStatus.authenticated != true
    val manageStatus = when {
        connection.resolvedDashboardUrl.isBlank() ->
            OverviewStatus(stringResource(R.string.active_section_missing), OverviewTone.Warning)
        dashboardStatus == null ->
            OverviewStatus(stringResource(R.string.active_section_unchecked), OverviewTone.Neutral)
        !dashboardStatus.reachable ->
            OverviewStatus(stringResource(R.string.active_section_offline), OverviewTone.Warning)
        dashboardSignInRequired ->
            OverviewStatus(stringResource(R.string.active_section_sign_in), OverviewTone.Info)
        else -> OverviewStatus(stringResource(R.string.active_section_ready), OverviewTone.Good)
    }
    val voiceStatus = when (standardVoiceAvailability) {
        StandardVoiceAvailability.Ready ->
            OverviewStatus(stringResource(R.string.active_section_ready), OverviewTone.Good)
        StandardVoiceAvailability.SignInRequired ->
            OverviewStatus(stringResource(R.string.active_section_sign_in), OverviewTone.Info)
        StandardVoiceAvailability.Unsupported ->
            OverviewStatus(stringResource(R.string.active_section_unsupported), OverviewTone.Warning)
        StandardVoiceAvailability.Unreachable ->
            OverviewStatus(stringResource(R.string.active_section_offline), OverviewTone.Warning)
        StandardVoiceAvailability.Unknown ->
            OverviewStatus(stringResource(R.string.active_section_checking), OverviewTone.Neutral)
    }
    val relayStatus = when (relayUiState) {
        RelayUiState.NotConfigured ->
            OverviewStatus(stringResource(R.string.relay_state_optional), OverviewTone.Neutral)
        RelayUiState.Connected ->
            OverviewStatus(stringResource(R.string.relay_state_ready), OverviewTone.Good)
        RelayUiState.Connecting ->
            OverviewStatus(stringResource(R.string.relay_state_reconnecting), OverviewTone.Info)
        RelayUiState.Stale,
        RelayUiState.Disconnected ->
            OverviewStatus(stringResource(R.string.relay_state_unavailable), OverviewTone.Warning)
        RelayUiState.Expired ->
            OverviewStatus(stringResource(R.string.relay_state_needs_repair), OverviewTone.Warning)
    }
    val apiStatus = when (
        resolveOptionalApiPresentation(
            gatewayReady = gatewayAvailability == GatewayAvailability.Ready,
            apiReachable = apiReachable,
            apiConfigured = connection.apiServerUrl.isNotBlank(),
            apiProbing = apiHealth == ConnectionViewModel.HealthStatus.Probing,
        )
    ) {
        OptionalApiPresentation.Checking ->
            OverviewStatus(stringResource(R.string.active_section_checking), OverviewTone.Neutral)
        OptionalApiPresentation.Ready ->
            OverviewStatus(stringResource(R.string.active_section_ready), OverviewTone.Good)
        OptionalApiPresentation.Optional ->
            OverviewStatus(stringResource(R.string.relay_state_optional), OverviewTone.Neutral)
        OptionalApiPresentation.Offline ->
            OverviewStatus(stringResource(R.string.active_section_offline), OverviewTone.Warning)
    }

    Text(
        text = stringResource(R.string.detail_current_route),
        style = MaterialTheme.typography.titleSmall,
    )
    CurrentRouteOverviewCard(
        route = route,
        status = routeStatus,
        onEdit = onOpenRoutes,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OverviewCapabilityCard(
            icon = Icons.Filled.Chat,
            label = stringResource(R.string.conn_chat_label),
            status = chatStatus,
            modifier = Modifier.weight(1f),
        )
        OverviewCapabilityCard(
            icon = Icons.Filled.Dashboard,
            label = stringResource(R.string.conn_manage_label),
            status = manageStatus,
            modifier = Modifier.weight(1f),
            onClick = onOpenDashboard,
        )
        OverviewCapabilityCard(
            icon = Icons.Filled.GraphicEq,
            label = stringResource(R.string.conn_voice_label),
            status = voiceStatus,
            modifier = Modifier.weight(1f),
            onClick = if (
                standardVoiceAvailability == StandardVoiceAvailability.SignInRequired
            ) {
                onOpenDashboard
            } else {
                null
            },
        )
    }

    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    OverviewOptionalRow(
        icon = Icons.Filled.Link,
        label = stringResource(R.string.active_section_relay_connected_features),
        description = stringResource(R.string.active_section_relay_optional_summary),
        status = relayStatus,
        onClick = if (relayConfigured) onOpenRelayInfo else onRepair,
    )
    HorizontalDivider()
    OverviewOptionalRow(
        icon = Icons.Filled.Code,
        label = stringResource(R.string.api_fallback_title),
        description = stringResource(R.string.active_section_api_not_required),
        status = apiStatus,
        onClick = onOpenApiInfo,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (relayUiState == RelayUiState.Stale) {
            Button(onClick = onReconnect) { Text(stringResource(R.string.detail_reconnect)) }
        }
    }
}

private enum class OverviewTone { Neutral, Good, Info, Warning }

internal enum class OptionalApiPresentation { Checking, Ready, Optional, Offline }

internal fun resolveOptionalApiPresentation(
    gatewayReady: Boolean,
    apiReachable: Boolean,
    apiConfigured: Boolean,
    apiProbing: Boolean,
): OptionalApiPresentation = when {
    apiProbing -> OptionalApiPresentation.Checking
    apiReachable -> OptionalApiPresentation.Ready
    gatewayReady || !apiConfigured -> OptionalApiPresentation.Optional
    else -> OptionalApiPresentation.Offline
}

private data class OverviewStatus(
    val text: String,
    val tone: OverviewTone,
)

@Composable
private fun overviewStatusColor(status: OverviewStatus): Color = when (status.tone) {
    OverviewTone.Good -> com.hermesandroid.relay.ui.theme.RelayRefresh.Green
    OverviewTone.Info -> MaterialTheme.colorScheme.primary
    OverviewTone.Warning -> MaterialTheme.colorScheme.error
    OverviewTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal data class DetailRoutePresentation(
    val label: String,
    val address: String,
)

internal fun resolveDetailRoutePresentation(
    activeEndpoint: EndpointCandidate?,
    effectiveDashboardUrl: String,
): DetailRoutePresentation {
    val candidateGatewayUrl = activeEndpoint?.gatewayRouteUrl()
    val address = effectiveDashboardUrl.trim().trimEnd('/').takeIf { it.isNotBlank() }
        ?: candidateGatewayUrl.orEmpty()
    val candidateOwnsAddress = candidateGatewayUrl != null &&
        address.isNotBlank() &&
        sameGatewayRouteBase(candidateGatewayUrl, address)
    val inferredRole = activeEndpoint?.role
        ?.lowercase()
        ?.takeIf { candidateOwnsAddress }
        ?: Connection.inferRouteRole(address)
    val role = when (inferredRole) {
        "lan" -> "LAN"
        "tailscale" -> "Tailscale"
        "public" -> "Public"
        "https" -> "Public"
        "dashboard", "authenticated_dashboard" -> "Dashboard"
        else -> activeEndpoint?.displayLabel()?.takeIf { candidateOwnsAddress } ?: "Gateway"
    }
    val transport = when {
        address.startsWith("https://", ignoreCase = true) -> "HTTPS"
        address.startsWith("http://", ignoreCase = true) -> "HTTP"
        else -> null
    }
    val label = transport?.let { if (role.equals(it, ignoreCase = true)) role else "$role ($it)" }
        ?: role
    return DetailRoutePresentation(label = label, address = address)
}

@Composable
private fun CurrentRouteOverviewCard(
    route: DetailRoutePresentation,
    status: OverviewStatus,
    onEdit: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = route.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = status.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = overviewStatusColor(status),
                    )
                }
                TextButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.active_section_edit))
                }
            }
            Text(
                text = route.address.ifBlank { stringResource(R.string.active_section_not_configured) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OverviewCapabilityCard(
    icon: ImageVector,
    label: String,
    status: OverviewStatus,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.then(
            if (onClick != null) {
                Modifier.clickable(onClickLabel = label, onClick = onClick)
            } else {
                Modifier
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status.text,
                style = MaterialTheme.typography.labelMedium,
                color = overviewStatusColor(status),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OverviewOptionalRow(
    icon: ImageVector,
    label: String,
    description: String,
    status: OverviewStatus,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = status.text,
            style = MaterialTheme.typography.labelSmall,
            color = overviewStatusColor(status),
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Overview for a **non-active** connection. The deep live content reads the
 * single active-connection VM state, so rather than show stale/foreign data
 * we surface the path to make this connection active.
 */
@Composable
private fun InactiveOverview(
    connection: Connection,
    onSwitch: () -> Unit,
    onRepair: () -> Unit,
) {
    val hostname = connection.primaryHost.ifBlank { connection.label }
    val statusLine = when {
        connection.pairedAt != null -> stringResource(R.string.detail_paired_relay_configured)
        connection.capabilities.chatConfigured -> stringResource(R.string.detail_standard_not_paired)
        else -> stringResource(R.string.detail_not_configured)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = hostname,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(R.string.detail_inactive_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onSwitch) { Text(stringResource(R.string.detail_switch_to)) }
                TextButton(onClick = onRepair) {
                    Text(if (connection.pairedAt == null) stringResource(R.string.detail_pair_relay) else stringResource(R.string.detail_repair))
                }
            }
        }
    }
}

@Composable
private fun RenameConnectionDialog(
    initialLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf(initialLabel) }
    val validation = com.hermesandroid.relay.data.ConnectionValidation.validateLabel(input)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    isError = validation != null,
                    supportingText = {
                        if (validation != null) Text(validation)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input.trim()) },
                enabled = validation == null && input.trim() != initialLabel,
            ) {
                Text(stringResource(R.string.detail_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.detail_cancel)) }
        },
    )
}
