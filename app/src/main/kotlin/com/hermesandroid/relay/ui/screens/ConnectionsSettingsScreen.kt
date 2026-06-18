package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.hermesandroid.relay.ui.components.ActiveCardFeaturesSection
import com.hermesandroid.relay.ui.components.ActiveCardSecurityPosture
import com.hermesandroid.relay.ui.components.ApiServerInfoSheet
import com.hermesandroid.relay.ui.components.EndpointsCard
import com.hermesandroid.relay.ui.components.RouteEditorDialog
import com.hermesandroid.relay.ui.components.InsecureConnectionAckDialog
import com.hermesandroid.relay.ui.components.RelayInfoSheet
import com.hermesandroid.relay.ui.components.SessionInfoSheet
import com.hermesandroid.relay.network.relay.RelayUrlDeriver
import com.hermesandroid.relay.network.shared.RouteProbeOutcome
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.RelayUiState
import com.hermesandroid.relay.viewmodel.StandardVoiceAvailability
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
    onNavigateToManage: () -> Unit,
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
    val activeRelayConfigured: Boolean = if (connectionViewModel != null) {
        val configured by connectionViewModel.relayConfigured.collectAsState()
        configured
    } else {
        false
    }

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
                    text = "Tap Add connection to connect to Standard Hermes.",
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
                        relayConfigured = if (isActive) {
                            activeRelayConfigured
                        } else {
                            connection.hasConfiguredRelay()
                        },
                        isDarkTheme = isDarkTheme,
                        onReconnect = onReconnectActive,
                        onRename = { newLabel -> onRenameConnection(connection.id, newLabel) },
                        onRepair = { onRepairConnection(connection.id) },
                        onRevoke = { onRevokeConnection(connection.id) },
                        onRemove = { onRemoveConnection(connection.id) },
                        onOpenApiInfo = { showApiInfoSheet = true },
                        onOpenDashboard = onNavigateToManage,
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
    relayConfigured: Boolean,
    isDarkTheme: Boolean,
    onReconnect: () -> Unit,
    onRename: (String) -> Unit,
    onRepair: () -> Unit,
    onRevoke: () -> Unit,
    onRemove: () -> Unit,
    onOpenApiInfo: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenRelayInfo: () -> Unit,
    onOpenSessionInfo: () -> Unit,
    onInsecureAckRequested: () -> Unit,
    onNavigateToPairedDevices: () -> Unit,
) {
    val context = LocalContext.current
    var showRenameDialog by remember { mutableStateOf(false) }
    var showRevokeConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var endpointsExpanded by remember { mutableStateOf(false) }

    // Active card: muted indigo wash instead of the full-strength Electric
    // primaryContainer — a card-sized fill of the brand blue overwhelmed the
    // body text (2026-06-10 feedback); small accents keep the vivid blue.
    val containerColor = if (isActive) {
        com.hermesandroid.relay.ui.theme.RelayRefresh.ElectricMuted.copy(alpha = 0.42f)
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
    val isTailscaleDetected: Boolean = if (activeConnectionViewModel != null) {
        val detected by activeConnectionViewModel.isTailscaleDetected.collectAsState()
        detected
    } else {
        false
    }
    val routeProbeStatus: ConnectionViewModel.RouteProbeStatus =
        if (activeConnectionViewModel != null) {
            val status by activeConnectionViewModel.routeProbeStatus.collectAsState()
            status
        } else {
            ConnectionViewModel.RouteProbeStatus.Idle
        }
    val routeProbeOutcomes: Map<String, RouteProbeOutcome> =
        if (activeConnectionViewModel != null) {
            val outcomes by activeConnectionViewModel.routeProbeOutcomes.collectAsState()
            outcomes
        } else {
            emptyMap()
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
            val hasStandardApi = connection.apiServerUrl.isNotBlank()
            val pairedStatus = when {
                liveState != null &&
                    (connection.pairedAt != null || liveState != RelayUiState.NotConfigured) ->
                    liveState.statusText(connectedLabel = "Connected")
                connection.pairedAt != null -> formatPairedRelative(connection.pairedAt)
                hasStandardApi -> "Standard · Relay not paired"
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

            ConnectionSurfaceSummary(
                connection = connection,
                isActive = isActive,
                liveState = liveState,
                activeConnectionViewModel = activeConnectionViewModel,
                relayConfigured = relayConfigured,
                onOpenDashboard = onOpenDashboard,
            )

            // ── Single-endpoint nudge (active only) ──────────────────────
            if (isActive && connection.pairedAt != null && endpoints.size == 1) {
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
                TextButton(onClick = onRepair) {
                    Text(if (connection.pairedAt == null) "Pair Relay" else "Re-pair")
                }
                if (connection.pairedAt != null) {
                    TextButton(onClick = { showRevokeConfirm = true }) { Text("Revoke") }
                }
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

                // ── Features section ────────────────────────────────────
                SectionHeader(text = "Features")
                SectionCaption(
                    text = "What this connection can do. Routes only choose how the phone reaches Hermes.",
                )

                ActiveCardFeaturesSection(
                    connectionViewModel = activeConnectionViewModel,
                    relayEnabled = relayEnabled,
                    onOpenApiInfo = onOpenApiInfo,
                    onOpenDashboard = onOpenDashboard,
                    onOpenRelayInfo = onOpenRelayInfo,
                    onOpenSessionInfo = onOpenSessionInfo,
                )

                // ── Routes section (conditional on having endpoints) ─────
                // ADR 24 behavior preserved verbatim from pre-refactor;
                // user-facing copy now reads "Routes" instead of
                // "Endpoints" per the shared-vocabulary pass.
                if (endpoints.isNotEmpty()) {
                    HorizontalDivider()
                    SectionHeader(text = "Route")
                    SectionCaption(
                        text = "Choose how this phone reaches Hermes. Features stay separate from the selected route.",
                    )

                    var preferredRole by remember(connection.id) {
                        mutableStateOf(activeConnectionViewModel.getPreferredEndpointRole())
                    }
                    // Live transient override — set by "Use now" (or by
                    // preference restoration, in which case it equals
                    // preferredRole). A manual switch is the differing case.
                    val manualOverrideRole by
                        activeConnectionViewModel.manualRouteOverride.collectAsState()
                    val manualSwitchActive = manualOverrideRole != null &&
                        !manualOverrideRole.equals(preferredRole, ignoreCase = true)
                    var routeEditorOpen by remember(connection.id) { mutableStateOf(false) }
                    var routeEditorOriginal by remember(connection.id) {
                        mutableStateOf<EndpointCandidate?>(null)
                    }
                    val hasTailscaleRoute = endpoints.any {
                        it.role.equals("tailscale", ignoreCase = true)
                    }
                    val tailscalePreferred =
                        preferredRole?.equals("tailscale", ignoreCase = true) == true
                    val routeNeedsAttention =
                        activeEndpoint == null && liveState != RelayUiState.Connected
                    val showTailscaleUnavailableHint =
                        hasTailscaleRoute &&
                            !isTailscaleDetected &&
                            (tailscalePreferred || routeNeedsAttention)
                    val tailscaleLaunchIntent = remember(context) {
                        context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                    }
                    val isRouteProbing =
                        routeProbeStatus is ConnectionViewModel.RouteProbeStatus.Probing
                    // Last user-triggered probe finished with NO winner: say
                    // so explicitly. The old UI sat on "Resolving" forever
                    // and showed the (internal) relay URL underneath, which
                    // read as "stuck on the internal route".
                    val probeCameUpEmpty = activeEndpoint == null &&
                        routeProbeStatus is ConnectionViewModel.RouteProbeStatus.Done &&
                        routeProbeStatus.winner == null
                    val activeRouteLabel = when {
                        activeEndpoint != null -> activeEndpoint.displayLabel()
                        isRouteProbing -> "Checking routes…"
                        probeCameUpEmpty -> "No route reachable"
                        else -> "Resolving"
                    }
                    // Full URL (scheme included) — http vs https is the
                    // difference between a working route and a TLS-failing
                    // one, so never hide it. With no resolved route, show the
                    // saved API URL the app is actually falling back to.
                    val activeRouteHost = activeEndpoint?.api?.url
                        ?: "Using saved URL: ${
                            connection.apiServerUrl.ifBlank { connection.relayUrl }
                        }"
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Current: $activeRouteLabel",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (probeCameUpEmpty) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (isRouteProbing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        Text(
                            text = activeRouteHost,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (probeCameUpEmpty) {
                            Text(
                                text = "None of the saved routes answered a health " +
                                    "probe. Expand the routes below for per-route " +
                                    "reasons.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (showTailscaleUnavailableHint) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "Tailscale route is not active on this phone",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    text = "Connect this phone in Tailscale, then re-check routes.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (tailscaleLaunchIntent != null) {
                                        TextButton(
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(tailscaleLaunchIntent)
                                                }
                                            },
                                            contentPadding =
                                                androidx.compose.foundation.layout.PaddingValues(
                                                    horizontal = 0.dp,
                                                ),
                                        ) {
                                            Text("Open Tailscale")
                                        }
                                    }
                                    TextButton(
                                        onClick = { activeConnectionViewModel.probeNow() },
                                        enabled = !isRouteProbing,
                                        contentPadding =
                                            androidx.compose.foundation.layout.PaddingValues(
                                                horizontal = 0.dp,
                                            ),
                                    ) {
                                        Text(if (isRouteProbing) "Checking…" else "Re-check")
                                    }
                                }
                            }
                        }
                    }
                    if (isTailscaleDetected && !hasTailscaleRoute) {
                        // Inverse of the hint above: the phone is on Tailscale
                        // but this connection has nothing to roam to. This is
                        // the strongest signal a user wants remote access and
                        // simply never configured it — offer the route editor
                        // directly instead of hoping they find Show routes.
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "Phone is on Tailscale — no Tailscale route yet",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    text = "Add your server's Tailscale URL so Hermes " +
                                        "keeps working when this phone leaves the " +
                                        "server's network.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                TextButton(
                                    onClick = {
                                        routeEditorOriginal = null
                                        routeEditorOpen = true
                                    },
                                    contentPadding =
                                        androidx.compose.foundation.layout.PaddingValues(
                                            horizontal = 0.dp,
                                        ),
                                ) {
                                    Text("Add Tailscale route")
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { activeConnectionViewModel.probeNow() },
                            enabled = !isRouteProbing,
                        ) {
                            Text(if (isRouteProbing) "Checking…" else "Re-check")
                        }
                        if (preferredRole != null || manualSwitchActive) {
                            TextButton(
                                onClick = {
                                    // Clears both layers: the persisted
                                    // preference and any live manual switch.
                                    activeConnectionViewModel.setPreferredEndpointRole(null)
                                    preferredRole = null
                                },
                            ) {
                                Text("Auto")
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = if (endpointsExpanded) "Hide available routes"
                            else "Show available routes (${endpoints.size})",
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
                            isProbing = isRouteProbing,
                            outcomeFor = { candidate ->
                                routeProbeOutcomes[activeConnectionViewModel.routeOutcomeKey(candidate)]
                            },
                            preferredRole = preferredRole,
                            manualOverrideRole = manualOverrideRole,
                            onUseNow = { candidate ->
                                // Transient switch — no preference write.
                                activeConnectionViewModel.useRouteNow(candidate.role)
                            },
                            onCancelUseNow = {
                                activeConnectionViewModel.useRouteNow(null)
                            },
                            onPreferEndpoint = { candidate ->
                                activeConnectionViewModel.setPreferredEndpointRole(candidate.role)
                                preferredRole = candidate.role
                            },
                            onClearPreferred = {
                                activeConnectionViewModel.setPreferredEndpointRole(null)
                                preferredRole = null
                            },
                            onProbeNow = { activeConnectionViewModel.probeNow() },
                            onViewPin = { candidate ->
                                activeConnectionViewModel.lookupEndpointPin(candidate)
                            },
                            onAddRoute = {
                                routeEditorOriginal = null
                                routeEditorOpen = true
                            },
                            onEditRoute = { candidate ->
                                routeEditorOriginal = candidate
                                routeEditorOpen = true
                            },
                            onRemoveRoute = { candidate ->
                                activeConnectionViewModel.removeExtraRoute(candidate)
                            },
                        )
                    }
                    // Rendered outside the routes expander so the "Add
                    // Tailscale route" nudge above can open it while the
                    // routes list is collapsed.
                    if (routeEditorOpen) {
                        RouteEditorDialog(
                            original = routeEditorOriginal,
                            onSave = { role, apiUrl, onResult ->
                                activeConnectionViewModel.saveExtraRoute(
                                    role = role,
                                    apiUrl = apiUrl,
                                    original = routeEditorOriginal,
                                    onResult = onResult,
                                )
                            },
                            onDismiss = { routeEditorOpen = false },
                        )
                    }
                }

                HorizontalDivider()

                // ── Advanced section ─────────────────────────────────────
                // Header + caption above the collapsed Advanced card so
                // users understand this branch is a power-user surface,
                // not something they're expected to touch after Standard setup.
                SectionHeader(text = "Advanced")
                SectionCaption(
                    text = "Manual setup — most people don't need this " +
                        "after Standard Hermes setup.",
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

    val apiText = when {
        connection.apiServerUrl.isBlank() -> "Missing"
        activeApiHealth == ConnectionViewModel.HealthStatus.Probing -> "Checking"
        activeApiReachable == true -> "Ready"
        isActive && activeApiReachable == false -> "Offline"
        else -> "Configured"
    }
    val apiTone = when (apiText) {
        "Ready" -> SummaryTone.Good
        "Offline", "Missing" -> SummaryTone.Warning
        else -> SummaryTone.Neutral
    }

    val dashboardText = when {
        connection.resolvedDashboardUrl.isBlank() -> "Missing"
        dashboardStatus == null -> "Unchecked"
        !dashboardStatus.reachable -> "Offline"
        dashboardSignInRequired -> "Sign in"
        dashboardStatus.authenticated == true -> "Signed in"
        else -> "Available"
    }
    val dashboardTone = when (dashboardText) {
        "Signed in", "Available" -> SummaryTone.Good
        "Sign in" -> SummaryTone.Info
        "Offline", "Missing" -> SummaryTone.Warning
        else -> SummaryTone.Neutral
    }

    val relayText = when {
        !relayConfigured -> "Optional"
        liveState != null -> liveState.statusText(connectedLabel = "Ready")
        connection.pairedAt != null -> "Paired"
        connection.relayUrl.isNotBlank() -> "Configured"
        else -> "Configure"
    }
    val relayTone = when {
        !relayConfigured -> SummaryTone.Neutral
        liveState == RelayUiState.Connected -> SummaryTone.Good
        liveState == RelayUiState.Stale || liveState == RelayUiState.Disconnected -> SummaryTone.Warning
        else -> SummaryTone.Info
    }

    val voiceText = when {
        activeVoiceReady == true -> "Ready"
        standardVoiceAvailability == StandardVoiceAvailability.SignInRequired -> "Sign in"
        standardVoiceAvailability == StandardVoiceAvailability.Unsupported -> "Unsupported"
        standardVoiceAvailability == StandardVoiceAvailability.Unreachable -> "Offline"
        standardVoiceAvailability == StandardVoiceAvailability.Unknown && isActive -> "Checking"
        relayConfigured -> "Relay"
        else -> "Optional"
    }
    val voiceTone = when (voiceText) {
        "Ready" -> SummaryTone.Good
        "Sign in", "Relay" -> SummaryTone.Info
        "Unsupported", "Offline" -> SummaryTone.Warning
        else -> SummaryTone.Neutral
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnectionSurfacePill(
                label = "API",
                value = apiText,
                tone = apiTone,
                modifier = Modifier.weight(1f),
            )
            ConnectionSurfacePill(
                label = "Dashboard",
                value = dashboardText,
                tone = dashboardTone,
                modifier = Modifier.weight(1f),
                onClick = if (dashboardSignInRequired) onOpenDashboard else null,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnectionSurfacePill(
                label = "Voice",
                value = voiceText,
                tone = voiceTone,
                modifier = Modifier.weight(1f),
                onClick = if (voiceText == "Sign in") onOpenDashboard else null,
            )
            ConnectionSurfacePill(
                label = "Relay",
                value = relayText,
                tone = relayTone,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private enum class SummaryTone { Neutral, Good, Info, Warning }

@Composable
private fun ConnectionSurfacePill(
    label: String,
    value: String,
    tone: SummaryTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val container = when (tone) {
        SummaryTone.Good -> MaterialTheme.colorScheme.primaryContainer
        SummaryTone.Info -> MaterialTheme.colorScheme.tertiaryContainer
        SummaryTone.Warning -> MaterialTheme.colorScheme.errorContainer
        SummaryTone.Neutral -> MaterialTheme.colorScheme.surface
    }
    val content = when (tone) {
        SummaryTone.Good -> MaterialTheme.colorScheme.onPrimaryContainer
        SummaryTone.Info -> MaterialTheme.colorScheme.onTertiaryContainer
        SummaryTone.Warning -> MaterialTheme.colorScheme.onErrorContainer
        SummaryTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.then(
            if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            },
        ),
        color = container,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = content,
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
