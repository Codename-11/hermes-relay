package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.ui.components.ActiveCardAdvancedSection
import com.hermesandroid.relay.ui.components.ActiveCardSecurityPosture
import com.hermesandroid.relay.ui.components.ActiveCardStatusSection
import com.hermesandroid.relay.ui.components.ApiServerInfoSheet
import com.hermesandroid.relay.ui.components.EndpointsCard
import com.hermesandroid.relay.ui.components.InsecureConnectionAckDialog
import com.hermesandroid.relay.ui.components.RelayInfoSheet
import com.hermesandroid.relay.ui.components.SessionInfoSheet
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.RelayUiState
import com.hermesandroid.relay.viewmodel.statusText
import java.util.concurrent.TimeUnit

/**
 * Full-screen manager for the list of Hermes connections. Reachable via
 * Settings → Connections — the single authoritative home for everything
 * connection-related. Replaces the older (deleted) singular
 * `ConnectionSettingsScreen` that used to own pair / manual-URL / insecure
 * toggle / manual-pairing-code; all of that now lives on the active card
 * below under expandable sections.
 *
 * Cards, top to bottom inside the `LazyColumn`:
 *  - **Non-active connection cards** — label + status badge subtitle +
 *    per-card action row (Reconnect/Rename/Re-pair/Revoke/Remove). Flat,
 *    no expanders, no deep content. Users switch to the card to make it
 *    active before drilling in.
 *  - **The active connection card** — everything above, plus an inline
 *    body with three zones:
 *      1. Status rows (API / Relay / Session) — always visible; tap opens
 *         the matching info sheet.
 *      2. Endpoints expander (when the pairing carries endpoints).
 *      3. Advanced expander — manual URL config, insecure toggle, manual
 *         pairing code fallback (the full 3-step flow).
 *      4. Security posture strip (transport badge, Tailscale chip,
 *         hardware keystore badge, Relay sessions row) — always visible.
 *
 * The Extended FAB launches the Add-connection pairing wizard via
 * [onAddConnection] (which in `RelayApp` pre-creates a placeholder and
 * navigates to `Screen.Pair` with `autoStart = "scan"`).
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
    // Opens `PairedDevicesScreen` for the server-side session list. Wired
    // via the "Relay sessions" row inside the active card's security
    // posture strip. Must not be null — the row is always rendered.
    onNavigateToPairedDevices: () -> Unit,
    // ADR 24 — the active connection's endpoint state is read through this
    // VM. `null` means no VM wired (test harness / @Preview); the active
    // card falls back to legacy behavior and hides all deep content.
    connectionViewModel: ConnectionViewModel? = null,
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()

    // Feature flag for the entire relay surface (WSS, voice, bridge).
    // When off, Status section hides the Relay + Session rows and the
    // Advanced section hides relay-specific subsections. ChatReady path
    // (HTTP-only) is unaffected.
    val relayEnabled by FeatureFlags.relayEnabled(context)
        .collectAsState(initial = FeatureFlags.isDevBuild)

    // Kick a WSS reconnect on screen entry in case the user landed here
    // from a Stale chip. Moved here from the deleted singular
    // ConnectionSettingsScreen — same intent, same implementation.
    LaunchedEffect(Unit) {
        connectionViewModel?.reconnectIfStale()
    }

    // Info-sheet + ack-dialog visibility is hoisted to screen scope so
    // the composables survive when the owning card scrolls out of the
    // LazyColumn viewport (items can be disposed). Card-level booleans
    // would dismiss silently under scroll.
    var showSessionInfoSheet by remember { mutableStateOf(false) }
    var showApiInfoSheet by remember { mutableStateOf(false) }
    var showRelayInfoSheet by remember { mutableStateOf(false) }
    var showInsecureAckDialog by remember { mutableStateOf(false) }

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
            // Empty state — the FAB is the only useful action. Shown in
            // practice only during tests / after a wipe; cold start seeds
            // a default connection, so the list is rarely ever truly empty.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No connections yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Tap Add connection to pair with a Hermes server.",
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
                        // Live state only meaningful for the active card;
                        // non-active cards render pairedAt timestamp.
                        liveState = if (isActive) activeRelayUiState else null,
                        // Active-card-only deep content. Null-guard below
                        // means non-active cards stay flat (title + subtitle
                        // + action row) and don't collect any VM flows.
                        activeConnectionViewModel = if (isActive) connectionViewModel else null,
                        relayEnabled = relayEnabled,
                        isDarkTheme = isDarkTheme,
                        onReconnect = onReconnectActive,
                        onRename = { newLabel -> onRenameConnection(connection.id, newLabel) },
                        onRepair = { onRepairConnection(connection.id) },
                        onRevoke = { onRevokeConnection(connection.id) },
                        onRemove = { onRemoveConnection(connection.id) },
                        onOpenApiInfo = { showApiInfoSheet = true },
                        onOpenRelayInfo = { showRelayInfoSheet = true },
                        onOpenSessionInfo = { showSessionInfoSheet = true },
                        onInsecureAckRequested = { showInsecureAckDialog = true },
                        onNavigateToPairedDevices = onNavigateToPairedDevices,
                    )
                }
                // Footer spacer so the last card isn't hidden by the FAB.
                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    // ── Screen-scope dialogs + info sheets ───────────────────────────────
    // Kept here (not at card scope) so a card being disposed by LazyColumn
    // while scrolling can't silently dismiss an open sheet.
    if (connectionViewModel != null) {
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
                onCancel = {
                    // User bailed out of the ack — leave the toggle OFF
                    // in the Advanced section (it never flipped visually
                    // because onCheckedChange only routed through this
                    // dialog for first-enable).
                    showInsecureAckDialog = false
                },
            )
        }
    }
}

/**
 * Per-connection Card. Non-active cards are flat (title + subtitle +
 * actions). The active card grows an inline deep body — status rows,
 * optional endpoints expander, Advanced expander, security posture
 * strip — via the helpers in [ActiveConnectionSections.kt].
 *
 * The `activeConnectionViewModel` non-null check on the active card is
 * the single gate for the deep content. When null (test fixtures,
 * previews), we degrade gracefully to the flat layout — no VM flows
 * are collected and no dialogs fire.
 */
@Composable
private fun ConnectionCard(
    connection: Connection,
    isActive: Boolean,
    liveState: RelayUiState?,
    activeConnectionViewModel: ConnectionViewModel?,
    relayEnabled: Boolean,
    isDarkTheme: Boolean,
    onReconnect: () -> Unit,
    onRename: (String) -> Unit,
    onRepair: () -> Unit,
    onRevoke: () -> Unit,
    onRemove: () -> Unit,
    onOpenApiInfo: () -> Unit,
    onOpenRelayInfo: () -> Unit,
    onOpenSessionInfo: () -> Unit,
    onInsecureAckRequested: () -> Unit,
    onNavigateToPairedDevices: () -> Unit,
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

    // Endpoint flows only start collecting when activeConnectionViewModel
    // is non-null (active card only). Guards below make that a no-op for
    // non-active cards — the `observeDeviceEndpoints()` collector never
    // opens, no DataStore subscriber is registered.
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
            // ── Title row ────────────────────────────────────────────────
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

            // ── Subtitle: hostname + status + endpoints roles ──────────
            val hostname = Connection.extractDefaultLabel(connection.apiServerUrl)
            val pairedStatus = when {
                liveState != null -> liveState.statusText(connectedLabel = "Connected")
                connection.pairedAt != null -> formatPairedRelative(connection.pairedAt)
                else -> "Not paired"
            }
            // ADR 24 — active-only endpoint role summary.
            val endpointBadge = if (isActive && endpoints.isNotEmpty()) {
                val activeLabel = activeEndpoint?.displayLabel() ?: "resolving…"
                val allRoles = endpoints.map { it.displayLabel() }.distinct()
                val rolesSummary = if (allRoles.size == 1) allRoles.first()
                else allRoles.joinToString(" + ")
                " • Active: $activeLabel • $rolesSummary"
            } else {
                ""
            }
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

            // ── Single-endpoint nudge (active only) ──────────────────────
            if (isActive && endpoints.size == 1) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Legacy single-endpoint pairing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            text = "Re-pair with Mode = Auto to get LAN + Tailscale + " +
                                "Public in one QR.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        TextButton(
                            onClick = onRepair,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 0.dp,
                            ),
                        ) {
                            Text("Re-pair")
                        }
                    }
                }
            }

            // ── Action row ───────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (liveState == RelayUiState.Stale) {
                    TextButton(onClick = onReconnect) { Text("Reconnect") }
                }
                TextButton(onClick = { showRenameDialog = true }) { Text("Rename") }
                TextButton(onClick = onRepair) { Text("Re-pair") }
                TextButton(onClick = { showRevokeConfirm = true }) { Text("Revoke") }
                TextButton(onClick = { showRemoveConfirm = true }) {
                    Text(text = "Remove", color = MaterialTheme.colorScheme.error)
                }
            }

            // ── Active-card-only deep content ────────────────────────────
            // Gate everything on activeConnectionViewModel != null so
            // preview fixtures / test harnesses degrade to the flat layout
            // without VM collection.
            if (isActive && activeConnectionViewModel != null) {
                HorizontalDivider()

                // ── Connection health section ────────────────────────────
                SectionHeader(text = "Connection health")
                SectionCaption(text = "Tap any row for details.")

                // Status section (3 tappable rows → info sheets). Always
                // visible on the active card — the "health dashboard"
                // replacing the old Settings-top quick-look card.
                ActiveCardStatusSection(
                    connectionViewModel = activeConnectionViewModel,
                    relayEnabled = relayEnabled,
                    onOpenApiInfo = onOpenApiInfo,
                    onOpenRelayInfo = onOpenRelayInfo,
                    onOpenSessionInfo = onOpenSessionInfo,
                )

                // ── Routes section (conditional on having endpoints) ─────
                // ADR 24 behavior preserved verbatim from pre-refactor;
                // user-facing copy now reads "Routes" instead of
                // "Endpoints" per the shared-vocabulary pass.
                if (endpoints.isNotEmpty()) {
                    HorizontalDivider()
                    SectionHeader(text = "Routes (${endpoints.size})")
                    SectionCaption(
                        text = "The app picks the fastest reachable network " +
                            "automatically and switches when you change networks.",
                    )

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
                            text = if (endpointsExpanded) "Hide routes"
                            else "Show routes",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { endpointsExpanded = !endpointsExpanded }) {
                            Icon(
                                imageVector = if (endpointsExpanded) Icons.Filled.ExpandLess
                                else Icons.Filled.ExpandMore,
                                contentDescription = if (endpointsExpanded) "Collapse routes"
                                else "Expand routes",
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

                HorizontalDivider()

                // ── Advanced section ─────────────────────────────────────
                // Header + caption above the collapsed Advanced card so
                // users understand this branch is a power-user surface,
                // not something they're expected to touch after QR pairing.
                SectionHeader(text = "Advanced")
                SectionCaption(
                    text = "Manual setup — most people don't need this " +
                        "after QR pairing.",
                )

                // Advanced expander: manual URL config + insecure toggle
                // + manual pairing code fallback. Collapsed by default —
                // the canonical path is the Re-pair button up top.
                ActiveCardAdvancedSection(
                    connectionViewModel = activeConnectionViewModel,
                    relayEnabled = relayEnabled,
                    isDarkTheme = isDarkTheme,
                    onInsecureAckRequested = onInsecureAckRequested,
                )

                HorizontalDivider()

                // ── Security section ─────────────────────────────────────
                SectionHeader(text = "Security")

                // Security posture strip: transport badge + Tailscale chip
                // + hardware keystore badge + Relay sessions row.
                ActiveCardSecurityPosture(
                    connectionViewModel = activeConnectionViewModel,
                    onNavigateToPairedDevices = onNavigateToPairedDevices,
                )
            }
        }
    }

    // ── Card-scope dialogs (rename / revoke / remove confirms) ──────────
    // These survive card-level recomposition; they DON'T need screen-
    // scope hoisting because they're tied to a single card and cannot
    // logically open on two cards at once.
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
                ) { Text("Revoke") }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirm = false }) { Text("Cancel") }
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
                    Text(text = "Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") }
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
    var input by remember { mutableStateOf(initialLabel) }
    val validation = com.hermesandroid.relay.data.ConnectionValidation.validateLabel(input)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename connection") },
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
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Inline section header for the active-card deep body. `labelMedium` on
 * `onSurfaceVariant`, 12dp top padding / 4dp bottom, so successive
 * sections visually chunk "Connection health → Routes → Advanced →
 * Security" without needing per-section cards.
 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * Muted caption paired with [SectionHeader] — `bodySmall` on
 * `onSurfaceVariant`, no extra padding so it hugs the header line
 * above. Use for one-line explanatory copy per section.
 */
@Composable
private fun SectionCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Hand-rolled "N minutes ago" / "N hours ago" / "N days ago" formatter
 * for the non-active card subtitle. Could use `DateUtils.getRelativeTimeSpanString`
 * but that returns awkward copy ("in 0 minutes") for small deltas.
 */
private fun formatPairedRelative(pairedAtMillis: Long): String {
    val deltaMs = System.currentTimeMillis() - pairedAtMillis
    if (deltaMs < 0) return "Just paired"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
    val hours = TimeUnit.MILLISECONDS.toHours(deltaMs)
    val days = TimeUnit.MILLISECONDS.toDays(deltaMs)
    return when {
        minutes < 1L -> "Just paired"
        minutes < 60L -> "Paired $minutes minute${if (minutes == 1L) "" else "s"} ago"
        hours < 24L -> "Paired $hours hour${if (hours == 1L) "" else "s"} ago"
        days < 30L -> "Paired $days day${if (days == 1L) "" else "s"} ago"
        else -> "Paired"
    }
}
