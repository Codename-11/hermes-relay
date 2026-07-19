package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.capabilities
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.ui.components.ActiveCardAdvancedSection
import com.hermesandroid.relay.ui.components.ActiveCardFeaturesSection
import com.hermesandroid.relay.ui.components.ActiveCardRoutesSection
import com.hermesandroid.relay.ui.components.ActiveCardSecurityPosture
import com.hermesandroid.relay.ui.components.ApiServerInfoSheet
import com.hermesandroid.relay.ui.components.InsecureConnectionAckDialog
import com.hermesandroid.relay.ui.components.RelayInfoSheet
import com.hermesandroid.relay.ui.components.SessionInfoSheet
import com.hermesandroid.relay.ui.theme.LocalBrand
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.RelayUiState

/**
 * Tabbed detail for a single Hermes connection — the level-2 screen the
 * (slim) [ConnectionsSettingsScreen] list drills into. Replaces the old
 * "everything crammed into the active card" body.
 *
 * Layout:
 *  - TopAppBar: back + connection label + an `Active` badge (active conn) +
 *    an overflow `⋮` menu (Rename / Re-pair / Revoke / Remove).
 *  - When this connection is the **active** one, a 4-tab segmented bar:
 *    **Overview** (status header + the steps/timeline capability list) ·
 *    **Routes** (ADR 24 endpoint management) · **Advanced** (manual URL /
 *    insecure / manual pairing) · **Security** (transport posture + the
 *    prominent Relay sessions entry).
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

    val connections by connectionViewModel.connections.collectAsState()
    val activeConnectionId by connectionViewModel.activeConnectionId.collectAsState()
    val relayUiState by connectionViewModel.relayUiState.collectAsState()
    val relayEnabled by FeatureFlags.relayEnabled(context)
        .collectAsState(initial = FeatureFlags.isDevBuild)

    val connection = connections.firstOrNull { it.id == connectionId }
    // Connection was removed (e.g. via the overflow menu) — leave the screen.
    LaunchedEffect(connection == null) {
        if (connection == null) onBack()
    }
    if (connection == null) return

    val isActive = connectionId == activeConnectionId

    // Screen-scoped sheet/dialog visibility (survives tab switches + scroll).
    var showSessionInfoSheet by remember { mutableStateOf(false) }
    var showApiInfoSheet by remember { mutableStateOf(false) }
    var showRelayInfoSheet by remember { mutableStateOf(false) }
    var showInsecureAckDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showRevokeConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val tabs = if (isActive) {
        listOf(DetailTab.Overview, DetailTab.Routes, DetailTab.Advanced, DetailTab.Security)
    } else {
        listOf(DetailTab.Overview)
    }
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
                                    DetailTab.Advanced -> stringResource(R.string.detail_tab_advanced)
                                    DetailTab.Security -> stringResource(R.string.detail_tab_security)
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
                                relayEnabled = relayEnabled,
                                onReconnect = onReconnect,
                                onRepair = { onRepair(connectionId) },
                                onOpenApiInfo = { showApiInfoSheet = true },
                                onOpenDashboard = onNavigateToManage,
                                onOpenRelayInfo = { showRelayInfoSheet = true },
                                onOpenSessionInfo = { showSessionInfoSheet = true },
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
                        onEditDashboard = {
                            selectedTab = tabs.indexOf(DetailTab.Advanced)
                        },
                    )

                    DetailTab.Advanced -> ActiveCardAdvancedSection(
                        connectionViewModel = connectionViewModel,
                        relayEnabled = relayEnabled,
                        isDarkTheme = isDarkTheme,
                        onPairRelay = { onRepair(connectionId) },
                        onInsecureAckRequested = { showInsecureAckDialog = true },
                    )

                    DetailTab.Security -> ActiveCardSecurityPosture(
                        connectionViewModel = connectionViewModel,
                        onNavigateToPairedDevices = onNavigateToPairedDevices,
                        onRevokeRelay = { showRevokeConfirm = true },
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // ── Screen-scope sheets + dialogs ────────────────────────────────────
    if (showSessionInfoSheet) {
        SessionInfoSheet(
            connectionViewModel = connectionViewModel,
            onDismiss = { showSessionInfoSheet = false },
        )
    }
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

private enum class DetailTab {
    Overview,
    Routes,
    Advanced,
    Security,
}

/**
 * Overview for the **active** connection: a one-line status header followed
 * by the steps/timeline capability list ([ActiveCardFeaturesSection]) and
 * quick actions. The timeline is intentionally the hero of this tab.
 */
@Composable
private fun ActiveOverview(
    connectionViewModel: ConnectionViewModel,
    connection: Connection,
    relayUiState: RelayUiState,
    relayEnabled: Boolean,
    onReconnect: () -> Unit,
    onRepair: () -> Unit,
    onOpenApiInfo: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenRelayInfo: () -> Unit,
    onOpenSessionInfo: () -> Unit,
) {
    val hostname = connection.primaryHost.ifBlank { connection.label }
    val dashboardReady = connection.dashboardLastStatus?.reachable == true

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Dashboard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.detail_dashboard_primary),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = connection.resolvedDashboardUrl.ifBlank { hostname },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (dashboardReady) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = if (dashboardReady) {
                        stringResource(R.string.detail_core_ready)
                    } else {
                        stringResource(R.string.detail_core_configured)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (dashboardReady) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    Text(
        text = stringResource(R.string.detail_overview_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ActiveCardFeaturesSection(
        connectionViewModel = connectionViewModel,
        relayEnabled = relayEnabled,
        onOpenApiInfo = onOpenApiInfo,
        onOpenDashboard = onOpenDashboard,
        onOpenRelayInfo = onOpenRelayInfo,
        onOpenSessionInfo = onOpenSessionInfo,
        onPairRelay = onRepair,
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
