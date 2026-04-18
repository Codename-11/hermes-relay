package com.hermesandroid.relay.ui.screens

import android.content.ClipData
import android.widget.Toast
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
import com.hermesandroid.relay.network.ConnectionState
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.components.ApiServerInfoSheet
import com.hermesandroid.relay.ui.components.ConnectionStatusRow
import com.hermesandroid.relay.ui.components.InsecureConnectionAckDialog
import com.hermesandroid.relay.ui.components.RelayInfoSheet
import com.hermesandroid.relay.ui.components.SessionInfoSheet
import com.hermesandroid.relay.ui.components.SettingsExpandableCard
import com.hermesandroid.relay.ui.components.TransportSecurityBadge
import com.hermesandroid.relay.ui.components.TransportSecuritySize
import com.hermesandroid.relay.ui.components.isUrlSecure
import com.hermesandroid.relay.ui.showHumanError
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.util.classifyError
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.RelayUiState
import com.hermesandroid.relay.viewmodel.asBadgeState
import com.hermesandroid.relay.viewmodel.statusText
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
    onNavigateToPair: () -> Unit,
) {
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()
    val apiServerUrl by connectionViewModel.apiServerUrl.collectAsState()
    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val apiHealth by connectionViewModel.apiServerHealth.collectAsState()
    val relayHealth by connectionViewModel.relayServerHealth.collectAsState()
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

    // Pairing wizard now lives at the dedicated Screen.Pair route — this
    // page just navigates to it. The old `Dialog` wrapper wasn't actually
    // filling the window (Settings cards were leaking through underneath
    // the chooser tiles + camera viewport on first-run-from-Settings), so
    // we route to a Scaffolded full-screen `PairScreen` instead.

    var showManualCodeDialog by remember { mutableStateOf(false) }
    var manualCodeInput by remember { mutableStateOf("") }
    var manualPairingInProgress by remember { mutableStateOf(false) }
    var manualPairingError by remember { mutableStateOf<String?>(null) }
    var manualPairingAttempt by remember { mutableStateOf(0) }

    // === MANUAL-PAIR-FOLLOWUP: in-card connect state ===
    // Card 3's "Connect" button has its own in-flight state, separate from
    // the dialog flow above so the spinner only blocks the card's own
    // Connect action and not the rest of the screen.
    var card3ConnectInProgress by remember { mutableStateOf(false) }
    var card3ConnectAttempt by remember { mutableStateOf(0) }
    var card3ExplainerExpanded by rememberSaveable { mutableStateOf(false) }
    // === END MANUAL-PAIR-FOLLOWUP ===

    // Tap-for-info sheets — mirror the chat "tap agent name" overlay
    // pattern so users can dig into what each Connection status row means.
    var showSessionInfoSheet by remember { mutableStateOf(false) }
    var showApiInfoSheet by remember { mutableStateOf(false) }
    var showRelayInfoSheet by remember { mutableStateOf(false) }

    // Manual config: pairing code input. Relay reachability state now
    // lives on the ViewModel (relayReachableResult StateFlow) — the old
    // relayTestInProgress / relayTestResult locals were removed when Save
    // & Test was rewired to ConnectionViewModel.testRelayReachable().
    var manualConfigCodeInput by remember { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // === MANUAL-PAIR-FOLLOWUP: snackbar host for Card 3 Connect feedback ===
    val snackbarHost = LocalSnackbarHost.current
    // === END MANUAL-PAIR-FOLLOWUP ===

    // Feature flags
    val relayEnabled by FeatureFlags.relayEnabled(context).collectAsState(initial = FeatureFlags.isDevBuild)

    // Resolved relay UI state lives in ConnectionViewModel so every screen
    // renders the relay row identically. The grace-window-to-Stale logic
    // and the amber "Reconnecting…" flash-suppression that used to live
    // here (as `isAutoReconnecting` + `isRelayStale`) are now centralized.
    // We still kick `reconnectIfStale()` on screen entry so the WSS
    // handshake starts right away — the VM's grace timer then hides the
    // Disconnected→Connecting transition.
    val relayUiState by connectionViewModel.relayUiState.collectAsState()
    LaunchedEffect(Unit) {
        connectionViewModel.reconnectIfStale()
    }

    // === MANUAL-PAIR-FOLLOWUP: observe authState for Card 3 Connect ===
    // Tracks the in-flight "Connect" tap from Card 3 (Manual pairing code
    // fallback). Keyed on the attempt counter so each tap restarts the
    // watcher with a fresh 15s budget. Surfaces success / failure through
    // the global snackbar host so the user gets feedback without having to
    // scroll up to the Session status row.
    LaunchedEffect(card3ConnectAttempt) {
        if (card3ConnectAttempt == 0) return@LaunchedEffect
        try {
            val terminal = withTimeout(15_000) {
                connectionViewModel.authState.first {
                    it is AuthState.Paired || it is AuthState.Failed
                }
            }
            card3ConnectInProgress = false
            when (terminal) {
                is AuthState.Paired -> {
                    snackbarHost.showSnackbar("Paired successfully")
                }
                is AuthState.Failed -> {
                    val human = classifyError(
                        IllegalStateException(terminal.reason),
                        context = "pair",
                    )
                    snackbarHost.showHumanError(human)
                }
                else -> Unit
            }
        } catch (_: TimeoutCancellationException) {
            card3ConnectInProgress = false
            val human = classifyError(
                java.io.IOException("No response from relay"),
                context = "pair",
            )
            snackbarHost.showHumanError(human)
        } catch (e: Exception) {
            card3ConnectInProgress = false
            snackbarHost.showHumanError(classifyError(e, context = "pair"))
        }
    }
    // === END MANUAL-PAIR-FOLLOWUP ===

    // Connection section expand state. Both manual cards stay collapsed by
    // default — the primary path is now the chooser-driven Pair wizard
    // (Screen.Pair) which fully replaces the need to hand-type URLs and
    // codes for the common case. rememberSaveable preserves user intent
    // across config changes so a manual expand survives a rotation.
    var manualConfigExpanded by rememberSaveable { mutableStateOf(false) }
    var bridgePairingExpanded by rememberSaveable { mutableStateOf(false) }

    // The insecure ack dialog opens the first time the user toggles insecure
    // mode on. We stash the "new state" so we can revert the toggle if they
    // cancel the dialog without picking a reason.
    var showInsecureAckDialog by remember { mutableStateOf(false) }

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

                    // Primary action — open the shared 3-step pairing wizard
                    // (Scan → Confirm → Verify). Same wizard onboarding uses,
                    // so first-run and re-pair stay perfectly aligned.
                    Button(
                        onClick = onNavigateToPair,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Pair with QR")
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
                        isProbing = apiHealth == ConnectionViewModel.HealthStatus.Probing,
                        statusText = when {
                            apiHealth == ConnectionViewModel.HealthStatus.Probing -> "Checking…"
                            apiReachable -> "Reachable"
                            else -> "Unreachable"
                        },
                        onClick = { showApiInfoSheet = true },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (relayEnabled) {
                        // Row state comes straight from ConnectionViewModel's
                        // resolved RelayUiState flow. Tap behavior: a Stale
                        // state offers a direct reconnect; every other state
                        // falls through to the relay info bottom sheet.
                        ConnectionStatusRow(
                            label = "Relay",
                            state = relayUiState.asBadgeState(),
                            statusText = relayUiState.statusText(connectedLabel = "Connected"),
                            onClick = {
                                if (relayUiState == RelayUiState.Stale) {
                                    connectionViewModel.connectRelay()
                                    // Immediate toast acknowledges the tap
                                    // even if the handshake takes a beat —
                                    // the row itself flips to Connecting
                                    // within milliseconds via relayUiState.
                                    android.widget.Toast.makeText(
                                        context,
                                        "Reconnecting to relay…",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
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
                            if (relayUiState == RelayUiState.Stale) {
                                Button(
                                    onClick = {
                                        connectionViewModel.connectRelay()
                                        android.widget.Toast.makeText(
                                            context,
                                            "Reconnecting to relay…",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
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
                                    onNavigateToPair()
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

            // Card 3 — Manual pairing code (collapsible, relay-gated).
            // Fallback for when the QR scan flow can't be used (no camera,
            // host can't render a QR, etc). The code below is sent as the
            // pairing_code in AuthManager.authenticate() when no QR-issued
            // code is set, and the relay must have it pre-registered.
            // Bridge control itself is gated by the master toggle on the
            // Bridge tab, NOT by this code.
            if (relayEnabled) {
                SettingsExpandableCard(
                    title = "Manual pairing code (fallback)",
                    expanded = bridgePairingExpanded,
                    onToggle = { bridgePairingExpanded = !bridgePairingExpanded },
                    isDarkTheme = isDarkTheme
                ) {
                    // === MANUAL-PAIR-FOLLOWUP: step-by-step body ===
                    // Three numbered steps walk the user through the manual
                    // (host-side --register-code) flow end-to-end. Replaces
                    // the previous "code + copy + regen" stub which left
                    // users guessing what to do with the code.

                    Text(
                        text = "Use this when you can't scan the pairing QR. " +
                            "Follow the three steps — they're meant to be done " +
                            "in order on whatever machine you have shell access to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // ── Step 1 — Copy the code ───────────────────────────
                    ManualPairStep(
                        number = 1,
                        title = "Copy the code below",
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = pairingCode,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = MaterialTheme.typography.headlineMedium.fontSize * 0.15
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "Pairing code",
                                                pairingCode
                                            )
                                        )
                                    )
                                    snackbarHost.showSnackbar("Pairing code copied")
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "Copy pairing code"
                                )
                            }

                            IconButton(
                                onClick = { connectionViewModel.regeneratePairingCode() }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Generate new code"
                                )
                            }
                        }
                    }

                    // ── Step 2 — Register the code on the host ───────────
                    ManualPairStep(
                        number = 2,
                        title = "On the host running Hermes-Relay, run:",
                    ) {
                        // Monospace shell-command surface — visually distinct
                        // from the body copy so the user understands it's a
                        // command they should paste into their shell.
                        androidx.compose.material3.Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "hermes-pair --register-code $pairingCode",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        val cmd = "hermes-pair --register-code $pairingCode"
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(
                                                    ClipData.newPlainText(
                                                        "hermes-pair command",
                                                        cmd
                                                    )
                                                )
                                            )
                                            snackbarHost.showSnackbar("Command copied")
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = "Copy hermes-pair command",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // ── Step 3 — Connect ─────────────────────────────────
                    ManualPairStep(
                        number = 3,
                        title = "Come back here and tap Connect",
                    ) {
                        val canConnect = !card3ConnectInProgress &&
                            relayUrlInput.isNotBlank() &&
                            pairingCode.isNotBlank()
                        Button(
                            onClick = {
                                card3ConnectInProgress = true
                                card3ConnectAttempt += 1
                                // Mirror the existing manual-pair dialog
                                // path: clobber any stale session, then
                                // kick a fresh authenticate() with the
                                // currently-displayed pairing code as the
                                // server-issued code.
                                connectionViewModel.authManager
                                    .applyServerIssuedCodeAndReset(pairingCode)
                                connectionViewModel.disconnectRelay()
                                connectionViewModel.connectRelay(relayUrlInput)
                            },
                            enabled = canConnect,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (card3ConnectInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Connecting…")
                            } else {
                                Text("Connect")
                            }
                        }
                        if (relayUrlInput.isBlank()) {
                            Text(
                                text = "Relay URL not set — open Manual configuration above to set it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    HorizontalDivider()

                    // ── "How does this work?" expandable explainer ───────
                    TextButton(
                        onClick = { card3ExplainerExpanded = !card3ExplainerExpanded },
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text(
                            text = if (card3ExplainerExpanded)
                                "Hide explanation"
                            else
                                "How does this work?",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (card3ExplainerExpanded) {
                        Text(
                            text = "This is a fallback for when you can't scan " +
                                "the pairing QR — for example, no camera, the " +
                                "host can't render a QR, or you only have SSH " +
                                "access from a single device. The canonical " +
                                "flow is the QR scan from `/hermes-relay-pair` " +
                                "or `hermes-pair`.\n\n" +
                                "How it works: the phone generates a 6-character " +
                                "code locally. You paste that code into the host's " +
                                "`hermes-pair --register-code` command, which " +
                                "pre-registers it with the relay. When you tap " +
                                "Connect here, the phone presents the same code " +
                                "to the relay and gets a long-lived session " +
                                "token in return.\n\n" +
                                "Bridge / device-control is gated by the master " +
                                "toggle on the Bridge tab, NOT by this pairing " +
                                "code. Pairing only authorizes the relay session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // === END MANUAL-PAIR-FOLLOWUP ===
                }
            }
            // ── end Connection section ──────────────────────────────────────
        }
    }

    // (Pair flow lives at Screen.Pair / PairScreen.kt now — navigated to
    // via onNavigateToPair() above. Kept out of this composable so the
    // wizard gets a real Scaffolded window instead of a half-fullscreen
    // Dialog leaking the cards behind it.)

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

    // (QR scanner + TTL picker live inside ConnectionWizard, which is
    // hosted by Screen.Pair / PairScreen.kt.)

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

// === MANUAL-PAIR-FOLLOWUP: numbered-step row helper ===
/**
 * One row in the Manual pairing code (fallback) card. Renders a small
 * circular step badge on the left and the step's body on the right.
 *
 * Kept private to this file because it's tightly coupled to Card 3's
 * layout — number badge sizing, monospace command surface, etc. If we
 * ever want this on another screen, lift it into [ui.components].
 */
@Composable
private fun ManualPairStep(
    number: Int,
    title: String,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Step badge — small filled circle with the step number centered.
        androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.size(24.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}
// === END MANUAL-PAIR-FOLLOWUP ===
