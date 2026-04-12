package com.hermesandroid.relay.ui.screens

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.data.PairingPreferences
import com.hermesandroid.relay.network.ConnectionState
import com.hermesandroid.relay.ui.components.ApiServerInfoSheet
import com.hermesandroid.relay.ui.components.ConnectionStatusRow
import com.hermesandroid.relay.ui.components.HermesPairingPayload
import com.hermesandroid.relay.ui.components.InsecureConnectionAckDialog
import com.hermesandroid.relay.ui.components.PairingWalkthroughDialog
import com.hermesandroid.relay.ui.components.QrPairingScanner
import com.hermesandroid.relay.ui.components.RelayInfoSheet
import com.hermesandroid.relay.ui.components.SessionInfoSheet
import com.hermesandroid.relay.ui.components.SessionTtlPickerDialog
import com.hermesandroid.relay.ui.components.SettingsExpandableCard
import com.hermesandroid.relay.ui.components.TransportSecurityBadge
import com.hermesandroid.relay.ui.components.TransportSecuritySize
import com.hermesandroid.relay.ui.components.defaultTtlSeconds
import com.hermesandroid.relay.ui.components.isUrlSecure
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.util.classifyError
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Dedicated Connection settings screen. Hosts the pairing card, manual
 * configuration (API URL, API key, relay URL, insecure toggle), and the
 * bridge pairing-code card gated by feature flags. Reached from the root
 * SettingsScreen category list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onNavigateToPairedDevices: () -> Unit,
) {
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()
    val apiServerUrl by connectionViewModel.apiServerUrl.collectAsState()
    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val pairingCode by connectionViewModel.pairingCode.collectAsState()
    val apiKeyPresent by connectionViewModel.authManager.apiKeyPresent.collectAsState()
    val insecureMode by connectionViewModel.insecureMode.collectAsState()
    val isInsecureConnection by connectionViewModel.isInsecureConnection.collectAsState()

    // Security overhaul (2026-04-11):
    val currentPairedSession by connectionViewModel.currentPairedSession.collectAsState()
    val pairedDevices by connectionViewModel.pairedDevices.collectAsState()
    val isTailscaleDetected by connectionViewModel.isTailscaleDetected.collectAsState()
    val insecureAckSeen by connectionViewModel.insecureAckSeen.collectAsState()
    val insecureReason by connectionViewModel.insecureReason.collectAsState()

    val isDarkTheme = isSystemInDarkTheme()

    var apiUrlInput by remember(apiServerUrl) { mutableStateOf(apiServerUrl) }
    var apiKeyInput by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var relayUrlInput by remember(relayUrl) { mutableStateOf(relayUrl) }
    var isTesting by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showManualCodeDialog by remember { mutableStateOf(false) }
    var manualCodeInput by remember { mutableStateOf("") }
    var manualPairingInProgress by remember { mutableStateOf(false) }
    var manualPairingError by remember { mutableStateOf<String?>(null) }
    var manualPairingAttempt by remember { mutableStateOf(0) }

    // Tap-for-info sheets — mirror the chat "tap agent name" overlay
    // pattern so users can dig into what each Connection status row means.
    var showSessionInfoSheet by remember { mutableStateOf(false) }
    var showApiInfoSheet by remember { mutableStateOf(false) }
    var showRelayInfoSheet by remember { mutableStateOf(false) }

    // Full-screen guided pairing walkthrough — 4-step wizard triggered from
    // the Connection card, for users setting up from scratch.
    var showPairingWalkthrough by remember { mutableStateOf(false) }

    // Manual config: pairing code input. Relay reachability state now
    // lives on the ViewModel (relayReachableResult StateFlow) — the old
    // relayTestInProgress / relayTestResult locals were removed when Save
    // & Test was rewired to ConnectionViewModel.testRelayReachable().
    var manualConfigCodeInput by remember { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Feature flags
    val relayEnabled by FeatureFlags.relayEnabled(context).collectAsState(initial = FeatureFlags.isDevBuild)

    // "Stale" = we have a paired session token but the WS is not currently
    // open. Happens after backgrounding, network flaps, or manual disconnect.
    // Shown as amber "Stale — tap to reconnect" instead of red "Disconnected"
    // because the fix is a single reconnect, not a re-pair.
    val isRelayStale = authState is AuthState.Paired &&
        relayConnectionState == ConnectionState.Disconnected

    // Auto-reconnect when entering the Settings screen in a stale state.
    // Keeps 90% of users out of the contradictory "Paired + Disconnected"
    // state without requiring a manual tap.
    //
    // [isAutoReconnecting] is a screen-local gate that unifies the brief
    // "Stale → Connecting → Connected" transition into one consistent
    // "Reconnecting..." label so users don't see the row flash between
    // states. Flipped off when we either reach Connected, or after a
    // 5s timeout (at which point we fall through to the real
    // "Stale — tap to reconnect" affordance).
    var isAutoReconnecting by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        connectionViewModel.reconnectIfStale()
        kotlinx.coroutines.delay(5000)
        isAutoReconnecting = false
    }
    LaunchedEffect(relayConnectionState) {
        if (relayConnectionState == ConnectionState.Connected) {
            isAutoReconnecting = false
        }
    }

    // Connection section expand state — seeded from the current pair/reach
    // status on first composition, then driven by the user. rememberSaveable
    // preserves user intent across config changes so tapping "collapse" when
    // the connection drops doesn't re-open the card.
    val manualConfigExpandedDefault =
        !(apiReachable && (authState is AuthState.Paired || !relayEnabled))
    var manualConfigExpanded by rememberSaveable { mutableStateOf(manualConfigExpandedDefault) }
    var bridgePairingExpanded by rememberSaveable { mutableStateOf(false) }

    // --- Security overhaul state (2026-04-11) ------------------------------
    //
    // After a successful QR parse we stash the payload and open a TTL picker
    // dialog. The picker's confirm callback then completes the pair (applies
    // the code, connects, etc.). This matches Bailey's request that the user
    // always gets a confirmation step so the trust model is explicit.
    var pendingQrPayload by remember { mutableStateOf<HermesPairingPayload?>(null) }

    // The insecure ack dialog opens the first time the user toggles insecure
    // mode on. We stash the "new state" so we can revert the toggle if they
    // cancel the dialog without picking a reason.
    var showInsecureAckDialog by remember { mutableStateOf(false) }

    // Camera permission launcher for QR scanning
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showQrScanner = true
        } else {
            Toast.makeText(context, "Camera permission needed to scan QR codes", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection") },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Connection section ──────────────────────────────────────────
            //
            // Merged layout as of 2026-04-11. Replaces the separate "API
            // Server", "Relay Server", and "Pairing" cards. The primary
            // card is the one-button QR pairing flow; manual configuration
            // and the Phase-3 bridge code live in collapsibles below.

            // Card 1 — Pair with your server (primary, always visible)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Pair with your server",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Scan a QR from `/hermes-relay-pair` (in any Hermes chat) or `hermes-pair` (shell). One scan configures chat and terminal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Primary scan button — full width, dominant
                    Button(
                        onClick = {
                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Scan Pairing QR")
                    }

                    // Secondary: guided 4-step walkthrough for users who
                    // want a stepper-driven setup (or can't scan the QR).
                    OutlinedButton(
                        onClick = { showPairingWalkthrough = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Route,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Guided setup")
                    }

                    // Tertiary: quick re-pair for power users who already
                    // have URLs set and just need to enter a fresh code.
                    TextButton(
                        onClick = {
                            manualCodeInput = ""
                            showManualCodeDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Already configured? Enter code only")
                    }

                    HorizontalDivider()

                    // Unified status summary. Each row is tappable to open a
                    // detail bottom sheet — same pattern as chat's
                    // tap-agent-name info overlay.
                    ConnectionStatusRow(
                        label = "API Server",
                        isConnected = apiReachable,
                        statusText = if (apiReachable) "Reachable" else "Unreachable",
                        onClick = { showApiInfoSheet = true },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (relayEnabled) {
                        ConnectionStatusRow(
                            label = "Relay",
                            isConnected = relayConnectionState == ConnectionState.Connected,
                            // Treat "stale" AND the initial auto-reconnect
                            // window as the amber in-flight state so the
                            // row renders in amber instead of red — a tap
                            // will bring it back, no re-pair needed.
                            isConnecting = relayConnectionState == ConnectionState.Connecting ||
                                relayConnectionState == ConnectionState.Reconnecting ||
                                isRelayStale ||
                                isAutoReconnecting,
                            statusText = when {
                                relayConnectionState == ConnectionState.Connected -> "Connected"
                                // During the initial auto-reconnect window
                                // (screen just opened, stale session is being
                                // revived), show ONE consistent label even as
                                // the underlying state bounces from
                                // Disconnected → Connecting → Connected. Avoids
                                // the flash of "Stale — tap to reconnect"
                                // followed immediately by "Connecting..." that
                                // users were seeing on every Settings entry.
                                isAutoReconnecting && relayConnectionState != ConnectionState.Connected -> "Reconnecting..."
                                relayConnectionState == ConnectionState.Connecting -> "Connecting..."
                                relayConnectionState == ConnectionState.Reconnecting -> "Reconnecting..."
                                isRelayStale -> "Stale — tap to reconnect"
                                else -> "Disconnected"
                            },
                            onClick = {
                                // When stale, the primary affordance is
                                // reconnect. Otherwise fall through to
                                // the info bottom sheet.
                                if (isRelayStale) {
                                    connectionViewModel.connectRelay()
                                } else {
                                    showRelayInfoSheet = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        ConnectionStatusRow(
                            label = "Session",
                            isConnected = authState is AuthState.Paired,
                            isConnecting = authState is AuthState.Pairing,
                            statusText = when (authState) {
                                is AuthState.Paired -> "Paired"
                                is AuthState.Pairing -> "Pairing..."
                                is AuthState.Unpaired -> "Unpaired"
                                is AuthState.Failed -> "Failed: ${(authState as AuthState.Failed).reason}"
                            },
                            onClick = { showSessionInfoSheet = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Secondary actions — only shown when paired. Promotes
                    // Reconnect to primary when the session is stale so the
                    // user has an explicit button if they don't realize the
                    // status row itself is tappable.
                    if (authState is AuthState.Paired) {
                        HorizontalDivider()
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isRelayStale) {
                                Button(
                                    onClick = { connectionViewModel.connectRelay() }
                                ) {
                                    Text("Reconnect")
                                }
                            }
                            OutlinedButton(
                                onClick = { connectionViewModel.clearSession() }
                            ) {
                                Text("Clear Session")
                            }
                            OutlinedButton(
                                onClick = {
                                    connectionViewModel.clearSession()
                                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                }
                            ) {
                                Text("Re-pair")
                            }
                        }
                    }

                    // --- Security overhaul row set --------------------------
                    //
                    // Transport security badge + Tailscale chip + Paired
                    // Devices entry point. Rendered at the bottom of Card 1
                    // so they're part of the "what's my pairing posture"
                    // mental model instead of buried in manual config.

                    HorizontalDivider()

                    // Transport security badge — reflects the currently-
                    // configured relay URL, not the live connection state.
                    // Secure = wss://, insecure = ws://. Reason comes from
                    // the insecure ack dialog.
                    val transportSecure = isUrlSecure(relayUrl)
                    TransportSecurityBadge(
                        isSecure = transportSecure,
                        reason = insecureReason.ifBlank { null },
                        size = TransportSecuritySize.Row,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Tailscale detected chip — purely informational. Only
                    // shows when detected; absence implies "not on Tailscale".
                    if (isTailscaleDetected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Tailscale detected",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }

                    // Hardware-backed storage badge — shows the 🛡 when
                    // the session token is in StrongBox.
                    if (currentPairedSession?.hasHardwareStorage == true) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Session token stored in hardware keystore",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Paired Devices entry point. Navigates to
                    // PairedDevicesScreen which fetches GET /sessions. Shows
                    // a cached count when we've loaded the list before.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToPairedDevices() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Paired Devices",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = if (pairedDevices.isNotEmpty()) {
                                    "${pairedDevices.size} active"
                                } else {
                                    "View and revoke paired phones"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Card 2 — Manual configuration (collapsible)
            SettingsExpandableCard(
                title = "Manual configuration",
                expanded = manualConfigExpanded,
                onToggle = { manualConfigExpanded = !manualConfigExpanded },
                isDarkTheme = isDarkTheme
            ) {
                // API Server URL
                OutlinedTextField(
                    value = apiUrlInput,
                    onValueChange = { apiUrlInput = it },
                    label = { Text("API Server URL") },
                    placeholder = { Text("http://your-server:8642") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // API Key (optional)
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("API Key (optional)") },
                    placeholder = {
                        Text(
                            if (apiKeyPresent) "•••• already set (leave blank to keep)"
                            else "Leave empty if not configured"
                        )
                    },
                    supportingText = {
                        Text(
                            if (apiKeyPresent && apiKeyInput.isBlank()) {
                                "A key is already stored — leave blank to keep it, or type to replace"
                            } else {
                                "Only needed if Hermes is configured with API_SERVER_KEY"
                            }
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector = if (apiKeyVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (apiKeyVisible) "Hide" else "Show"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Save & Test
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            connectionViewModel.updateApiServerUrl(apiUrlInput)
                            if (apiKeyInput.isNotBlank()) {
                                connectionViewModel.updateApiKey(apiKeyInput)
                            }
                            isTesting = true
                            connectionViewModel.testApiConnection { success ->
                                isTesting = false
                                Toast.makeText(
                                    context,
                                    if (success) "API server reachable" else "Cannot reach API server",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        enabled = apiUrlInput.isNotBlank() && !isTesting
                    ) {
                        Text("Save & Test")
                    }

                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                // Relay-only fields
                if (relayEnabled) {
                    HorizontalDivider()

                    OutlinedTextField(
                        value = relayUrlInput,
                        onValueChange = {
                            relayUrlInput = it
                            // Clear any stale Save & Test result — the
                            // previous probe was for a different URL.
                            connectionViewModel.clearRelayReachableResult()
                        },
                        label = { Text("Relay URL") },
                        placeholder = { Text("wss://your-server:8767") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Save & Test + Disconnect.
                    //
                    // The Connect button was removed in the Option B cycle
                    // — it was the source of the "try to connect while
                    // unpaired → auth fails → rate-limited for 5 min" trap.
                    // "Save & Test" persists the typed URL AND probes the
                    // relay's /health endpoint over unauthenticated HTTP,
                    // with no WSS handshake and no auth envelope. Green =
                    // the URL points at a live hermes-relay; red = doesn't.
                    // Actual WSS connect happens through the pair flow
                    // (Scan Pairing QR) or auto-reconnect, both of which
                    // already have valid pair context.
                    val relayReachable by connectionViewModel.relayReachableResult.collectAsState()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                connectionViewModel.testRelayReachable(relayUrlInput)
                            },
                            enabled = relayUrlInput.isNotBlank() &&
                                relayReachable !is ConnectionViewModel.RelayReachable.Probing
                        ) {
                            Text("Save & Test")
                        }
                        OutlinedButton(
                            onClick = { connectionViewModel.disconnectRelay() },
                            enabled = relayConnectionState != ConnectionState.Disconnected
                        ) {
                            Text("Disconnect")
                        }
                    }

                    // Save & Test result — one of (Probing / Ok with
                    // version + client count / Fail with reason). Cleared
                    // when the user edits the URL (via the OutlinedTextField
                    // onValueChange below).
                    when (val r = relayReachable) {
                        is ConnectionViewModel.RelayReachable.Probing -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Probing /health…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is ConnectionViewModel.RelayReachable.Ok -> {
                            Text(
                                text = "✓ Reachable — hermes-relay v${r.version} (${r.clients} client, ${r.sessions} session${if (r.sessions == 1) "" else "s"})",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4CAF50)
                            )
                        }
                        is ConnectionViewModel.RelayReachable.Fail -> {
                            // RelayReachable.Fail only carries a String today
                            // (no throwable). Re-wrap so the classifier can
                            // still map it to human copy by keyword — a full
                            // ViewModel refactor to surface the Throwable is
                            // out of scope for this pass.
                            // TODO: plumb raw Throwable through RelayReachable.Fail.
                            val humanErr = classifyError(
                                Exception(r.message),
                                context = "save_and_test",
                            )
                            Column {
                                Text(
                                    text = "✗ ${humanErr.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = humanErr.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        null -> { /* idle — no row */ }
                    }

                    // Insecure active warning
                    if (isInsecureConnection && relayConnectionState == ConnectionState.Connected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Insecure connection — traffic is not encrypted",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // Insecure mode toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Allow insecure connections",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Enable ws:// and http:// for local dev/testing only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = insecureMode,
                            onCheckedChange = { enabled ->
                                if (enabled && !insecureAckSeen) {
                                    // First time enabling insecure mode —
                                    // show the one-time threat model dialog
                                    // so the user understands what they're
                                    // opting into. Leave the toggle OFF
                                    // until they confirm a reason.
                                    showInsecureAckDialog = true
                                } else {
                                    connectionViewModel.setInsecureMode(enabled)
                                }
                            }
                        )
                    }

                    HorizontalDivider()

                    // Direct pairing — enter the server-issued code and pair
                    // without going through the dialog/walkthrough. The same
                    // applyServerIssuedCodeAndReset path; just inline here
                    // for power users who already have everything set.
                    Text(
                        text = "Pairing code",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = manualConfigCodeInput,
                        onValueChange = { manualConfigCodeInput = it.uppercase() },
                        label = { Text("Code from hermes-pair / /hermes-relay-pair") },
                        placeholder = { Text("e.g. ABC123") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    val trimmedManualCode = manualConfigCodeInput.trim().uppercase()
                    val manualCodeValid = trimmedManualCode.length in 4..12 &&
                        trimmedManualCode.all { it.isLetterOrDigit() }

                    Button(
                        onClick = {
                            connectionViewModel.authManager
                                .applyServerIssuedCodeAndReset(trimmedManualCode)
                            connectionViewModel.disconnectRelay()
                            connectionViewModel.connectRelay(relayUrlInput)
                            Toast.makeText(
                                context,
                                "Pairing — check Session status",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        enabled = manualCodeValid && relayUrlInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pair now")
                    }
                }
            }

            // Card 3 — Bridge pairing code (collapsible, relay-gated)
            if (relayEnabled) {
                SettingsExpandableCard(
                    title = "Bridge pairing code",
                    expanded = bridgePairingExpanded,
                    onToggle = { bridgePairingExpanded = !bridgePairingExpanded },
                    isDarkTheme = isDarkTheme
                ) {
                    Text(
                        text = "For Phase 3 bridge feature — the host approves this code to enable Android tool control. Not used for initial pairing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Pairing Code",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = pairingCode,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = MaterialTheme.typography.headlineMedium.fontSize * 0.15
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        IconButton(onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText("Pairing code", pairingCode)
                                    )
                                )
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy pairing code"
                            )
                        }

                        IconButton(onClick = { connectionViewModel.regeneratePairingCode() }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Generate new code"
                            )
                        }
                    }
                }
            }
            // ── end Connection section ──────────────────────────────────────
        }
    }

    // Guided pairing walkthrough — full-screen 4-step wizard
    if (showPairingWalkthrough) {
        PairingWalkthroughDialog(
            connectionViewModel = connectionViewModel,
            onDismiss = { showPairingWalkthrough = false }
        )
    }

    // Connection info bottom sheets — tap-to-reveal details for each row
    // in Settings → Connection. Mirrors the chat tap-agent-name overlay
    // pattern.
    if (showSessionInfoSheet) {
        SessionInfoSheet(
            connectionViewModel = connectionViewModel,
            onDismiss = { showSessionInfoSheet = false }
        )
    }
    if (showApiInfoSheet) {
        ApiServerInfoSheet(
            connectionViewModel = connectionViewModel,
            onDismiss = { showApiInfoSheet = false }
        )
    }
    if (showRelayInfoSheet) {
        RelayInfoSheet(
            connectionViewModel = connectionViewModel,
            onDismiss = { showRelayInfoSheet = false }
        )
    }

    // Manual pairing-code entry dialog — fallback for users who can't scan
    // the QR (headless server, tiny terminal, bad lighting). Mirrors the QR
    // scanner's applyServerIssuedCode path; URLs are still configured via
    // Manual configuration below. Tracks pairing progress/error inline so
    // the user gets clear feedback without having to scroll to the Session
    // status row.
    if (showManualCodeDialog) {
        val relayUrlBlank = relayUrlInput.isBlank()
        val trimmedCode = manualCodeInput.trim().uppercase()
        val codeValid = trimmedCode.length in 4..12 &&
            trimmedCode.all { it.isLetterOrDigit() }
        val canSubmit = codeValid && !relayUrlBlank && !manualPairingInProgress

        val focusRequester = remember { FocusRequester() }
        val haptic = LocalHapticFeedback.current

        // Auto-focus the code field the moment the dialog opens so the soft
        // keyboard pops immediately. One-shot per open — re-opens will get
        // a fresh FocusRequester via remember.
        LaunchedEffect(showManualCodeDialog) {
            focusRequester.requestFocus()
        }

        // Extract "host:port" from the relay URL for a compact display —
        // the full wss://foo.example.com:8767/ws wraps awkwardly in the
        // dialog's narrow text area.
        val relayHostLabel = remember(relayUrlInput) {
            try {
                val uri = java.net.URI(relayUrlInput.trim())
                val host = uri.host ?: relayUrlInput
                if (uri.port > 0) "$host:${uri.port}" else host
            } catch (_: Exception) {
                relayUrlInput
            }
        }

        // Shared submit action — used by both the Pair button and the IME
        // Go action so "Done" on the keyboard submits like tapping Pair.
        val submitPairing = {
            manualPairingError = null
            manualPairingInProgress = true
            manualPairingAttempt += 1
            // Atomic reset avoids the race between clearSession's async
            // code regeneration and applyServerIssuedCode's mirror write,
            // and forces authenticate() to use the new code instead of a
            // stale session token.
            connectionViewModel.authManager
                .applyServerIssuedCodeAndReset(trimmedCode)
            connectionViewModel.disconnectRelay()
            connectionViewModel.connectRelay(relayUrlInput)
        }

        // Observe the authState transition after each Pair attempt. Keyed on
        // the attempt counter so retrying cancels and restarts the watcher.
        LaunchedEffect(manualPairingAttempt) {
            if (manualPairingAttempt == 0) return@LaunchedEffect
            try {
                val terminal = withTimeout(15_000) {
                    connectionViewModel.authState.first {
                        it is AuthState.Paired || it is AuthState.Failed
                    }
                }
                when (terminal) {
                    is AuthState.Paired -> {
                        manualPairingInProgress = false
                        manualPairingError = null
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showManualCodeDialog = false
                        Toast.makeText(
                            context,
                            "Paired successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is AuthState.Failed -> {
                        manualPairingInProgress = false
                        manualPairingError = "Server rejected code: ${terminal.reason}"
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    else -> {
                        manualPairingInProgress = false
                    }
                }
            } catch (_: TimeoutCancellationException) {
                manualPairingInProgress = false
                manualPairingError =
                    "No response from relay. Check the Relay URL and that the server is running."
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            } catch (e: Exception) {
                manualPairingInProgress = false
                // Classifier distinguishes "relay unreachable" vs "code
                // rejected" vs "auth rejected" — much better than the old
                // "Error: Network is unreachable" blob.
                val humanErr = classifyError(e, context = "pair")
                manualPairingError = humanErr.body
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }

        AlertDialog(
            onDismissRequest = {
                if (!manualPairingInProgress) showManualCodeDialog = false
            },
            title = { Text("Enter pairing code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Type the code printed by `hermes-pair` or `/hermes-relay-pair`.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = manualCodeInput,
                        onValueChange = {
                            manualCodeInput = it.uppercase()
                            // Clear stale error as soon as the user edits
                            if (manualPairingError != null) manualPairingError = null
                        },
                        label = { Text("Pairing code") },
                        placeholder = { Text("e.g. ABC123") },
                        singleLine = true,
                        enabled = !manualPairingInProgress,
                        isError = manualPairingError != null,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = MaterialTheme.typography.headlineSmall.fontSize * 0.15
                        ),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Go,
                            autoCorrectEnabled = false
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = { if (canSubmit) submitPairing() }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )

                    when {
                        manualPairingError != null -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = manualPairingError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        manualPairingInProgress -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Connecting to relay and pairing…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        relayUrlBlank -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Relay URL not set. Configure it first, then come back.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(
                                    onClick = {
                                        showManualCodeDialog = false
                                        manualConfigExpanded = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 0.dp)
                                ) {
                                    Text("Open Manual configuration")
                                }
                            }
                        }
                        else -> {
                            Text(
                                text = "Will connect to: $relayHostLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canSubmit,
                    onClick = { submitPairing() }
                ) {
                    Text(
                        if (manualPairingError != null) "Retry"
                        else if (manualPairingInProgress) "Pairing…"
                        else "Pair"
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showManualCodeDialog = false },
                    enabled = !manualPairingInProgress
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // QR Scanner overlay
    //
    // On successful parse we stash the payload and open the TTL picker —
    // the picker's confirm callback completes the pair. This ensures the
    // user always gets an explicit "Keep this pairing for…" confirmation
    // step so the trust model (who has access, for how long) is visible.
    if (showQrScanner) {
        QrPairingScanner(
            onPairingDetected = { payload ->
                showQrScanner = false
                pendingQrPayload = payload
            },
            onDismiss = { showQrScanner = false }
        )
    }

    // TTL picker — opens when pendingQrPayload becomes non-null. Confirm
    // persists URLs/keys/code and kicks the relay connect; cancel drops the
    // payload and leaves the user on Settings unchanged.
    pendingQrPayload?.let { payload ->
        val defaultTtl = defaultTtlSeconds(
            qrTtlSeconds = payload.relay?.ttlSeconds,
            transportHint = payload.relay?.transportHint,
            isTailscaleDetected = isTailscaleDetected,
        )
        // Previously-selected TTL from DataStore (seeded from the default).
        // collectAsState with a default so we don't block composition while
        // DataStore spins up its first emit.
        val persistedTtl by PairingPreferences.pairTtlSeconds(context)
            .collectAsState(initial = PairingPreferences.DEFAULT_TTL_SECONDS)
        val initialTtl = if (payload.relay?.ttlSeconds != null) defaultTtl else persistedTtl

        SessionTtlPickerDialog(
            initialTtlSeconds = initialTtl,
            isTailscaleDetected = isTailscaleDetected,
            transportHint = payload.relay?.transportHint,
            onConfirm = { ttl ->
                // Apply the scanned values
                apiUrlInput = payload.serverUrl
                apiKeyInput = payload.key
                connectionViewModel.updateApiServerUrl(payload.serverUrl)
                connectionViewModel.updateApiKey(payload.key)

                payload.relay?.let { relay ->
                    connectionViewModel.updateRelayUrl(relay.url)
                    if (relay.url.startsWith("ws://")) {
                        connectionViewModel.setInsecureMode(true)
                    }
                    // Wipe any TOFU pin for the new host + apply the code.
                    // applyServerIssuedCodeAndReset now takes the relay URL
                    // so the cert-pin store can remove its per-host entry
                    // for legitimate cert rotation.
                    connectionViewModel.authManager.applyServerIssuedCodeAndReset(
                        code = relay.code,
                        relayUrl = relay.url,
                    )
                    // Propagate per-channel grants into the next auth.
                    connectionViewModel.authManager.setPendingGrants(relay.grants)
                }
                // Stash TTL for the next authenticate() call.
                connectionViewModel.authManager.setPendingTtlSeconds(ttl)

                // Kick off the WSS handshake now — AuthManager is holding a
                // fresh server-issued code so the pair-context gate on
                // connectRelay will let it through. Without this call, the
                // Settings QR flow stashed credentials but never actually
                // opened the WSS, forcing the user to hit Reconnect
                // manually or reopen Settings to hit reconnectIfStale.
                payload.relay?.let { relay ->
                    connectionViewModel.disconnectRelay()
                    connectionViewModel.connectRelay(relay.url)
                }

                pendingQrPayload = null

                isTesting = true
                connectionViewModel.testApiConnection { success ->
                    isTesting = false
                    val msg = if (success) {
                        if (payload.relay != null)
                            "Paired — API + relay configured"
                        else
                            "Paired — server reachable"
                    } else {
                        "Paired — but server unreachable"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = { pendingQrPayload = null }
        )
    }

    // Insecure ack dialog — first time the user flips the "Allow insecure"
    // toggle on. Only gates the toggle itself; all actual pairing flows
    // trust the user's judgment.
    if (showInsecureAckDialog) {
        InsecureConnectionAckDialog(
            onConfirm = { reason ->
                connectionViewModel.setInsecureAckComplete(reason)
                connectionViewModel.setInsecureMode(true)
                showInsecureAckDialog = false
            },
            onCancel = {
                showInsecureAckDialog = false
                // Leave the toggle off — the user bailed out of the ack.
            }
        )
    }
}
