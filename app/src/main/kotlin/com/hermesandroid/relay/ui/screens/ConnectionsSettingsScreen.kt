package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.ui.components.EndpointsCard
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.RelayUiState
import com.hermesandroid.relay.viewmodel.statusText
import java.util.concurrent.TimeUnit

/**
 * Full-screen manager for the list of Hermes connections. Reachable via
 * Settings → Connections and via the "Manage connections…" button in the
 * connection switcher bottom sheet.
 *
 * Each connection is a card with:
 *  - Label (tappable → rename dialog)
 *  - Hostname + paired status subtitle
 *  - Re-pair / Revoke / Remove actions
 *  - "Active" badge + tonal highlight on the currently-active connection
 *
 * An Extended FAB pinned bottom-right launches the add-connection pairing
 * flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsSettingsScreen(
    connections: List<Connection>,
    activeConnectionId: String?,
    // Live WSS state for the currently-active connection. Surfaced in the
    // active card's subtitle so users see "Connected" / "Reconnecting…" /
    // "Stale — tap to reconnect" in real time, not the static pairedAt
    // timestamp. Ignored for non-active cards (they fall back to pairedAt).
    activeRelayUiState: RelayUiState,
    onReconnectActive: () -> Unit,
    onRenameConnection: (id: String, newLabel: String) -> Unit,
    onRepairConnection: (id: String) -> Unit,
    onRevokeConnection: (id: String) -> Unit,
    onRemoveConnection: (id: String) -> Unit,
    onAddConnection: () -> Unit,
    onBack: () -> Unit,
    // ADR 24 — the active connection's endpoint state. Passed in so the
    // active card's inline-expand reveals the same EndpointsCard the
    // Settings → Connection screen renders (role/probe/prefer chips) instead
    // of a stale "paired X hours ago" stub. `null` = no VM wired, which the
    // card treats as "legacy pairing, no endpoint list available".
    connectionViewModel: ConnectionViewModel? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connections") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddConnection,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                    )
                },
                text = { Text("Add connection") },
            )
        },
    ) { innerPadding ->
        if (connections.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "No connections yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Pair with a Hermes server to add your first connection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(connections, key = { it.id }) { connection ->
                    val isActive = connection.id == activeConnectionId
                    ConnectionCard(
                        connection = connection,
                        isActive = isActive,
                        // Only the active card gets a live state; others
                        // fall back to the persistent pairedAt timestamp
                        // since we don't track WSS state for background
                        // connections.
                        liveState = if (isActive) activeRelayUiState else null,
                        // Endpoint plumbing is only meaningful for the
                        // active connection — EndpointResolver only tracks
                        // probe state for the currently-connected relay.
                        // Non-active cards get null and hide the expand
                        // affordance.
                        activeConnectionViewModel = if (isActive) connectionViewModel else null,
                        onReconnect = onReconnectActive,
                        onRename = { newLabel -> onRenameConnection(connection.id, newLabel) },
                        onRepair = { onRepairConnection(connection.id) },
                        onRevoke = { onRevokeConnection(connection.id) },
                        onRemove = { onRemoveConnection(connection.id) },
                    )
                }
                // Footer spacer so the last card isn't hidden by the FAB.
                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    connection: Connection,
    isActive: Boolean,
    // Live state for the active connection. Null for inactive ones — they
    // render the persistent "Paired 2 days ago" style timestamp instead.
    liveState: RelayUiState?,
    // Only non-null for the active connection. When present, the card
    // grows an inline-expand affordance that reveals the shared
    // EndpointsCard with role/probe/prefer chips — the same component the
    // Settings → Connection detail screen uses. For non-active connections
    // this is null and the card stays flat (matches pre-ADR-24 layout).
    activeConnectionViewModel: ConnectionViewModel?,
    onReconnect: () -> Unit,
    onRename: (String) -> Unit,
    onRepair: () -> Unit,
    onRevoke: () -> Unit,
    onRemove: () -> Unit,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showRevokeConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var endpointsExpanded by remember { mutableStateOf(false) }

    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    // Only the active card gets endpoint state. Flows are collected at the
    // card level (not hoisted) so non-active cards don't pay the
    // DataStore read cost — they get nothing and render flat.
    val endpoints: List<EndpointCandidate> = if (activeConnectionViewModel != null) {
        val list by activeConnectionViewModel.observeDeviceEndpoints()
            .collectAsState(initial = emptyList())
        list
    } else {
        emptyList()
    }
    val activeEndpoint: EndpointCandidate? = if (activeConnectionViewModel != null) {
        val ep by activeConnectionViewModel.activeEndpoint.collectAsState()
        ep
    } else {
        null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = connection.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isActive) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            text = "Active",
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }

            val hostname = Connection.extractDefaultLabel(connection.apiServerUrl)
            // For the active card, surface live WSS state (Connected,
            // Reconnecting…, Stale, etc.) so the subtitle is a real-time
            // reflection rather than a stale "Paired 5 minutes ago". For
            // non-active cards the persistent pairedAt timestamp is all we
            // have, so it stays.
            val pairedStatus = when {
                liveState != null -> liveState.statusText(connectedLabel = "Connected")
                connection.pairedAt != null -> formatPairedRelative(connection.pairedAt)
                else -> "Not paired"
            }
            // ADR 24 — on the active card we also know the active endpoint
            // role + the full set of roles the QR carried; splice both into
            // the subtitle so the list entry matches what the Active card at
            // Settings → Connection shows. Previous version said "2 endpoints"
            // which was accurate but opaque — users couldn't tell at a glance
            // whether their QR had LAN, Tailscale, Public, or some mix.
            val endpointBadge = if (isActive && endpoints.isNotEmpty()) {
                val activeLabel = activeEndpoint?.displayLabel() ?: "resolving…"
                val allRoles = endpoints.map { it.displayLabel() }.distinct()
                val rolesSummary = if (allRoles.size == 1) {
                    allRoles.first()
                } else {
                    allRoles.joinToString(" + ")
                }
                " • Active: $activeLabel • $rolesSummary"
            } else {
                ""
            }
            // Tint the subtitle amber when the live state is Stale so the
            // row visually signals "attention — tap Reconnect" even before
            // the explicit button is read. Other states keep the muted
            // color.
            val statusColor = if (liveState == RelayUiState.Stale) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = "$hostname • $pairedStatus$endpointBadge",
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // ADR 24 — nudge operators on legacy single-endpoint pairings
            // to re-pair so their QR picks up LAN + Tailscale + Public
            // automatically. Only on the active card (non-active don't
            // have endpoint state loaded) and only when exactly 1 endpoint
            // exists — zero means "not multi-endpoint-aware at all" (legacy
            // pre-ADR-24 pairing, expected to be empty), and ≥2 is already
            // what we want.
            if (isActive && endpoints.size == 1) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "Only one endpoint in this pairing — re-pair with " +
                                "Mode = Auto to get LAN + Tailscale + Public in one QR.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRepair) {
                            Text("Re-pair")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Stale-only Reconnect action. Promoted to first position so
                // it reads as the primary recovery affordance when the row
                // needs it, and simply absent otherwise so the Rename/Re-pair/
                // Revoke/Remove sequence isn't disrupted.
                if (liveState == RelayUiState.Stale) {
                    TextButton(onClick = onReconnect) {
                        Text("Reconnect")
                    }
                }
                TextButton(onClick = { showRenameDialog = true }) {
                    Text("Rename")
                }
                TextButton(onClick = onRepair) {
                    Text("Re-pair")
                }
                TextButton(onClick = { showRevokeConfirm = true }) {
                    Text("Revoke")
                }
                TextButton(onClick = { showRemoveConfirm = true }) {
                    Text(
                        text = "Remove",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Inline endpoints expander — only on the active card and only
            // when the paired device actually has endpoints stored (v3+
            // pairings). Legacy pairings get no affordance so we don't
            // pretend they have information we can't render.
            if (activeConnectionViewModel != null && endpoints.isNotEmpty()) {
                HorizontalDivider()
                var preferredRole by remember {
                    mutableStateOf(activeConnectionViewModel.getPreferredEndpointRole())
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = if (endpointsExpanded) "Hide endpoints" else "Show endpoints",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { endpointsExpanded = !endpointsExpanded }) {
                        Icon(
                            imageVector = if (endpointsExpanded) {
                                Icons.Filled.ExpandLess
                            } else {
                                Icons.Filled.ExpandMore
                            },
                            contentDescription = if (endpointsExpanded) {
                                "Collapse endpoints"
                            } else {
                                "Expand endpoints"
                            },
                        )
                    }
                }
                if (endpointsExpanded) {
                    EndpointsCard(
                        endpoints = endpoints,
                        activeEndpoint = activeEndpoint,
                        preferredRole = preferredRole,
                        onPreferEndpoint = { candidate ->
                            activeConnectionViewModel.setPreferredEndpointRole(candidate.role)
                            preferredRole = candidate.role
                        },
                        onClearOverride = {
                            activeConnectionViewModel.setPreferredEndpointRole(null)
                            preferredRole = null
                        },
                        onProbeNow = { activeConnectionViewModel.probeNow() },
                        onViewPin = { candidate ->
                            activeConnectionViewModel.lookupEndpointPin(candidate)
                        },
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameConnectionDialog(
            initialLabel = connection.label,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newLabel ->
                onRename(newLabel)
                showRenameDialog = false
            },
        )
    }

    if (showRevokeConfirm) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirm = false },
            title = { Text("Revoke this connection?") },
            text = {
                Text(
                    "The server session will be invalidated. The connection stays " +
                        "on this device but will need to be re-paired to use again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRevoke()
                        showRevokeConfirm = false
                    },
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove this connection?") },
            text = {
                Text(
                    "The connection will be deleted from this device along with its " +
                        "saved session token. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove()
                        showRemoveConfirm = false
                    },
                ) {
                    Text(
                        text = "Remove",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun RenameConnectionDialog(
    initialLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initialLabel) }
    // Validate on every keystroke so the Save button disables + the
    // supporting text appears without needing a failed submit first.
    val validationError = com.hermesandroid.relay.data.ConnectionValidation.validateLabel(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename connection") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Label") },
                isError = validationError != null && value.isNotEmpty(),
                supportingText = {
                    // Only show the error message once the user has typed
                    // *something* — starting with the initial value we don't
                    // want the dialog to appear with a red "can't be blank"
                    // on first open.
                    if (validationError != null && value.isNotEmpty()) {
                        Text(validationError)
                    }
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = validationError == null,
                onClick = { onConfirm(value.trim()) },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Coarse-grained relative time — "Paired 3d ago". We don't need exact
 * precision here, and rolling our own avoids adding android.text.format or
 * ThreeTenABP dependencies just for one subtitle.
 */
private fun formatPairedRelative(pairedAtMillis: Long): String {
    val deltaMs = System.currentTimeMillis() - pairedAtMillis
    if (deltaMs < 0) return "Paired"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
    val hours = TimeUnit.MILLISECONDS.toHours(deltaMs)
    val days = TimeUnit.MILLISECONDS.toDays(deltaMs)
    return when {
        minutes < 1L -> "Paired just now"
        minutes < 60L -> "Paired ${minutes}m ago"
        hours < 24L -> "Paired ${hours}h ago"
        days < 30L -> "Paired ${days}d ago"
        else -> "Paired ${days / 30L}mo ago"
    }
}
