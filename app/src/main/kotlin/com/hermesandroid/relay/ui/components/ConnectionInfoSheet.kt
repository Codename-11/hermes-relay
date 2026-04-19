package com.hermesandroid.relay.ui.components

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.AppAnalytics
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.network.ChatMode
import com.hermesandroid.relay.network.ConnectionState
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    monospace: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontFamily = if (monospace) FontFamily.Monospace else null
        )
    }
}

@Composable
private fun StatusChip(text: String, background: Color, contentColor: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = contentColor,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun ChipRow(label: String, chip: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        chip()
    }
}

@Composable
private fun connectionChip(state: ConnectionState) {
    val (label, bg, fg) = when (state) {
        ConnectionState.Connected -> Triple(
            "Connected",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        ConnectionState.Connecting -> Triple(
            "Connecting\u2026",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        ConnectionState.Reconnecting -> Triple(
            "Reconnecting\u2026",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        ConnectionState.Disconnected -> Triple(
            "Disconnected",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    StatusChip(text = label, background = bg, contentColor = fg)
}

@Composable
private fun authStateChip(state: AuthState) {
    val (label, bg, fg) = when (state) {
        is AuthState.Unpaired -> Triple(
            "Unpaired",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        is AuthState.Pairing -> Triple(
            "Pairing\u2026",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        is AuthState.Paired -> Triple(
            "Paired",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        is AuthState.Failed -> Triple(
            "Failed: ${state.reason}",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }
    StatusChip(text = label, background = bg, contentColor = fg)
}

// ---------------------------------------------------------------------------
// 1. SessionInfoSheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionInfoSheet(
    connectionViewModel: ConnectionViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    val authState by connectionViewModel.authState.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val pairingCode by connectionViewModel.pairingCode.collectAsState()
    // Pass 2 (2026-04-18): the old `sessionLabels: List<String>` field was
    // replaced by a structured `agentProfiles: List<Profile>` on the VM
    // (sourced from `auth.ok`'s `profiles` entry, whose items are objects
    // with {name, model, description}). Render the profile names here so
    // the user can confirm which agents the server advertised.
    val agentProfiles by connectionViewModel.agentProfiles.collectAsState()
    val pairedSession by connectionViewModel.currentPairedSession.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Session",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Session authenticates the app with the relay server. " +
                    "The pairing code is consumed once, then the server issues a " +
                    "long-lived token stored locally.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            ChipRow(label = "State") { authStateChip(authState) }

            InfoRow(label = "Relay URL", value = relayUrl, monospace = true)

            ChipRow(label = "Connection") { connectionChip(relayConnectionState) }

            HorizontalDivider()

            // Pairing code — big monospace + copy
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Pairing code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = pairingCode,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
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
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy pairing code"
                        )
                    }
                }
            }

            HorizontalDivider()

            InfoRow(
                label = "Device",
                value = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            )

            InfoRow(
                label = "Session token present",
                value = if (authState is AuthState.Paired) "Yes" else "No"
            )

            InfoRow(
                label = "Agent profiles",
                value = if (agentProfiles.isEmpty()) {
                    "(none)"
                } else {
                    agentProfiles.joinToString(", ") { it.name }
                }
            )

            // Security overhaul (2026-04-11) — show expiry + grants + storage.
            pairedSession?.let { paired ->
                HorizontalDivider()
                val expiryLabel = when {
                    paired.expiresAt == null -> "Never"
                    else -> java.text.DateFormat
                        .getDateInstance(java.text.DateFormat.MEDIUM)
                        .format(java.util.Date(paired.expiresAt * 1000L))
                }
                InfoRow(label = "Expires", value = expiryLabel)

                if (paired.grants.isNotEmpty()) {
                    val grantsLabel = paired.grants.entries.joinToString(", ") { (k, v) ->
                        if (v == null) "$k: never" else k
                    }
                    InfoRow(label = "Channel grants", value = grantsLabel)
                }

                val transportLabel = paired.transportHint?.uppercase() ?: "—"
                InfoRow(label = "Transport", value = transportLabel)

                InfoRow(
                    label = "Key storage",
                    value = if (paired.hasHardwareStorage) "Hardware (StrongBox)" else "Hardware (TEE)"
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        connectionViewModel.clearSession()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(0.5f)
                ) {
                    Text("Clear Session")
                }
                OutlinedButton(
                    onClick = { connectionViewModel.regeneratePairingCode() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Regenerate Code")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2. ApiServerInfoSheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiServerInfoSheet(
    connectionViewModel: ConnectionViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    val apiServerUrl by connectionViewModel.apiServerUrl.collectAsState()
    val apiServerReachable by connectionViewModel.apiServerReachable.collectAsState()
    val chatMode by connectionViewModel.chatMode.collectAsState()
    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()

    // Reactive flag from AuthManager — updated whenever setApiKey/clearApiKey
    // runs and seeded from stored prefs on init.
    val apiKeyPresent by connectionViewModel.authManager.apiKeyPresent.collectAsState()

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "API server",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Chat traffic goes directly from the app to the Hermes " +
                    "API server over HTTP/SSE. The relay is only involved for " +
                    "terminal and bridge channels.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            InfoRow(label = "URL", value = apiServerUrl, monospace = true)

            ChipRow(label = "Reachable") {
                val (label, bg, fg) = if (apiServerReachable) {
                    Triple(
                        "Yes",
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Triple(
                        "No",
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                StatusChip(text = label, background = bg, contentColor = fg)
            }

            InfoRow(label = "Streaming mode", value = chatMode.toString())
            InfoRow(label = "Endpoint preference", value = streamingEndpoint)
            InfoRow(
                label = "API key set",
                value = if (apiKeyPresent) "Yes (hidden)" else "No"
            )
            InfoRow(
                label = "Last health check",
                value = if (apiServerReachable) "Just now (ok)" else "Just now (failed)"
            )

            if (testResult != null) {
                Text(
                    text = testResult!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedButton(
                onClick = {
                    testing = true
                    testResult = "Testing\u2026"
                    connectionViewModel.testApiConnection { success ->
                        testing = false
                        testResult = if (success) "Connection OK" else "Connection failed"
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (testing) "Testing\u2026" else "Test connection")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. RelayInfoSheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayInfoSheet(
    connectionViewModel: ConnectionViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val context = LocalContext.current

    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val isInsecureConnection by connectionViewModel.isInsecureConnection.collectAsState()
    val insecureMode by connectionViewModel.insecureMode.collectAsState()
    val relayEnabled by FeatureFlags.relayEnabled(context)
        .collectAsState(initial = FeatureFlags.isDevBuild)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Relay",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "The relay is a WebSocket server that brokers terminal " +
                    "and bridge channels between the app and the host. Connect " +
                    "automatically after successful pairing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            InfoRow(label = "URL", value = relayUrl, monospace = true)
            ChipRow(label = "Connection state") { connectionChip(relayConnectionState) }

            if (isInsecureConnection) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "ws:// \u2014 traffic not encrypted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            InfoRow(
                label = "Insecure mode allowed",
                value = if (insecureMode) "Yes" else "No"
            )
            InfoRow(
                label = "Relay enabled (feature flag)",
                value = if (relayEnabled) "Yes" else "No"
            )

            Spacer(modifier = Modifier.height(4.dp))

            val isConnected = relayConnectionState == ConnectionState.Connected ||
                relayConnectionState == ConnectionState.Connecting ||
                relayConnectionState == ConnectionState.Reconnecting

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { connectionViewModel.connectRelay() },
                    enabled = relayEnabled && !isConnected,
                    modifier = Modifier.fillMaxWidth(0.5f)
                ) {
                    Text("Connect")
                }
                OutlinedButton(
                    onClick = { connectionViewModel.disconnectRelay() },
                    enabled = relayEnabled && isConnected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 4. AgentInfoSheet
// ---------------------------------------------------------------------------
//
// Consolidated "agent" sheet opened from the Chat top bar. Replaces the three
// separate surfaces that used to split agent state across the UI:
//   - the old AlertDialog on header tap (read-only connection summary)
//   - the ProfilePicker chip (profile dropdown)
//   - the PersonalityPicker chip (personality dropdown)
//
// One bottom sheet, one hierarchy: Profile → Personality → Connection. Mirrors
// ChatViewModel.startStream's precedence rule: a profile with a non-blank
// systemMessage overrides whatever personality is selected. When that case
// triggers, the Personality section visually de-emphasizes (alpha drop) and
// the Profile section footer spells out the override.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentInfoSheet(
    connectionViewModel: ConnectionViewModel,
    chatViewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onNavigateToConnections: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Profile + personality state — same flows the old pickers consumed.
    val agentProfiles by connectionViewModel.agentProfiles.collectAsState()
    val selectedProfile by connectionViewModel.selectedProfile.collectAsState()
    val selectedPersonality by chatViewModel.selectedPersonality.collectAsState()
    val personalityNames by chatViewModel.personalityNames.collectAsState()
    val defaultPersonality by chatViewModel.defaultPersonality.collectAsState()

    // Connection summary state.
    val authState by connectionViewModel.authState.collectAsState()
    val apiServerUrl by connectionViewModel.apiServerUrl.collectAsState()
    val apiServerReachable by connectionViewModel.apiServerReachable.collectAsState()
    val chatMode by connectionViewModel.chatMode.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val pairingCode by connectionViewModel.pairingCode.collectAsState()
    val serverModelName by chatViewModel.serverModelName.collectAsState()

    // Mid-stream gate — mirrors what ProfilePicker's `enabled` flag was doing:
    // a radio tap during an in-flight chat turn would race the request. Apply
    // to BOTH sections (profile + personality) since they both feed startStream.
    val isStreaming by chatViewModel.isStreaming.collectAsState()

    // Session context + analytics for the stats section. Session label
    // derived by matching the currently-active session id against the
    // cached sessions list (same pattern the old header dialog used
    // before consolidation).
    val messages by chatViewModel.messages.collectAsState()
    val currentSessionId by chatViewModel.currentSessionId.collectAsState()
    val sessions by chatViewModel.sessions.collectAsState()
    val currentSession = remember(sessions, currentSessionId) {
        sessions.firstOrNull { it.sessionId == currentSessionId }
    }
    val appStats by AppAnalytics.stats.collectAsState()

    val profileOverridesPersonality =
        selectedProfile?.systemMessage?.isNotBlank() == true
    var endpointsExpanded by remember { mutableStateOf(false) }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHost.current

    // Transient confirmation when the user picks a different profile or
    // personality from inside the sheet. Kept short — these fire on the
    // tap, so a 1-line toast is enough; the UI state update on the next
    // chat turn is the real confirmation. Suspend snackbar dispatch goes
    // through the local coroutine scope so it doesn't block the radio tap.
    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                // verticalScroll wraps content so long content (when the
                // connection block expands its endpoints, or on smaller
                // phones) is reachable. ModalBottomSheet itself does not
                // scroll its children — without this the sheet clips.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- Header: avatar + agent name + live status ----
            AgentSheetHeader(
                selectedProfile = selectedProfile,
                selectedPersonality = selectedPersonality,
                defaultPersonality = defaultPersonality,
                serverModelName = serverModelName,
                apiServerReachable = apiServerReachable,
                chatMode = chatMode,
            )

            HorizontalDivider()

            // ---- Profile section (hidden when server advertises none) ----
            if (agentProfiles.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionLabel(
                        title = "Profile",
                        hint = "Overlay an agent's model + SOUL",
                    )

                    // Default row — clears the profile override.
                    ProfileRadioRow(
                        primary = "Default",
                        secondary = "Server-configured model",
                        selected = selectedProfile == null,
                        enabled = !isStreaming,
                        onSelect = {
                            if (selectedProfile != null) {
                                connectionViewModel.selectProfile(null)
                                toast("Using default model")
                            }
                        },
                    )

                    agentProfiles.forEach { profile ->
                        ProfileRadioRow(
                            primary = profile.name.replaceFirstChar { it.uppercase() },
                            secondary = profile.model,
                            tertiary = profile.description.takeIf { it.isNotBlank() },
                            selected = selectedProfile?.name == profile.name,
                            enabled = !isStreaming,
                            onSelect = {
                                if (selectedProfile?.name != profile.name) {
                                    connectionViewModel.selectProfile(profile)
                                    val display = profile.name.replaceFirstChar { it.uppercase() }
                                    val suffix = if (profile.systemMessage?.isNotBlank() == true) {
                                        " — model + SOUL applied"
                                    } else {
                                        " — model applied"
                                    }
                                    toast("Switched to $display$suffix")
                                }
                            },
                        )
                    }

                    if (profileOverridesPersonality) {
                        Text(
                            text = "This profile's system message overrides the personality below.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                        )
                    }
                }

                HorizontalDivider()
            }

            // ---- Personality section ----
            // De-emphasize when a profile's systemMessage is taking over — the
            // row is still tappable because the user may want to queue the
            // choice for after they clear the profile. No alpha on the entire
            // Column because the section header would look broken.
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.alpha(if (profileOverridesPersonality) 0.55f else 1f),
            ) {
                SectionLabel(
                    title = "Personality",
                    hint = "System-prompt preset on this agent",
                )

                // Default row — maps to selectedPersonality == "default" which
                // the VM resolves to whatever server-side personality is
                // currently active.
                ProfileRadioRow(
                    primary = if (defaultPersonality.isNotBlank()) {
                        "${defaultPersonality.replaceFirstChar { it.uppercase() }} (default)"
                    } else {
                        "Default"
                    },
                    secondary = null,
                    selected = selectedPersonality == "default",
                    enabled = !isStreaming,
                    onSelect = {
                        if (selectedPersonality != "default") {
                            chatViewModel.selectPersonality("default")
                            if (!profileOverridesPersonality) {
                                toast("Using default personality")
                            }
                        }
                    },
                )

                personalityNames
                    .filter { it != defaultPersonality }
                    .forEach { name ->
                        ProfileRadioRow(
                            primary = name.replaceFirstChar { it.uppercase() },
                            secondary = null,
                            selected = selectedPersonality == name,
                            enabled = !isStreaming,
                            onSelect = {
                                if (selectedPersonality != name) {
                                    chatViewModel.selectPersonality(name)
                                    if (!profileOverridesPersonality) {
                                        val display = name.replaceFirstChar { it.uppercase() }
                                        toast("Personality: $display")
                                    }
                                }
                            },
                        )
                    }
            }

            HorizontalDivider()

            // ---- Session + stats section ----
            // Brings back the session/messages readout the old header
            // AlertDialog used to show, plus adds current-session token
            // counters pulled straight from AppAnalytics (no new flows
            // needed — already being collected via ChatViewModel's
            // stream lifecycle hooks).
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(title = "Session", hint = null)

                val sessionLabel = currentSession?.let {
                    it.title?.takeIf { t -> t.isNotBlank() }
                        ?: it.sessionId.take(12)
                } ?: "—"
                InfoRow(label = "Name", value = sessionLabel)
                InfoRow(label = "Messages", value = messages.size.toString())

                // Token counters only — skip internal accumulator fields.
                // Zero values are informative here ("no usage yet this
                // session") so we don't hide them.
                InfoRow(
                    label = "Tokens",
                    value = buildString {
                        append(appStats.currentSessionTokensIn.toString())
                        append(" in · ")
                        append(appStats.currentSessionTokensOut.toString())
                        append(" out")
                    },
                )
                if (appStats.avgResponseTimeMs > 0L) {
                    InfoRow(
                        label = "Avg TTFT",
                        value = "${appStats.avgResponseTimeMs} ms",
                    )
                }
            }

            HorizontalDivider()

            // ---- Connection section (condensed) ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(title = "Connection", hint = null)

                ChipRow(label = "Auth") { authStateChip(authState) }
                ChipRow(label = "API reachable") {
                    val (label, bg, fg) = if (apiServerReachable) {
                        Triple(
                            "Yes",
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        Triple(
                            "No",
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    StatusChip(text = label, background = bg, contentColor = fg)
                }

                // Pairing code only while the user is mid-pairing — no point
                // showing it once we're paired (it's consumed).
                if (authState is AuthState.Pairing && pairingCode.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Pairing code",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = pairingCode,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                            IconButton(onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "Pairing code",
                                                pairingCode,
                                            )
                                        )
                                    )
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy pairing code",
                                )
                            }
                        }
                    }
                }

                // Collapsible endpoint block — keeps the default view tidy.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { endpointsExpanded = !endpointsExpanded }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (endpointsExpanded) "Hide endpoints" else "Show endpoints",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = if (endpointsExpanded) {
                            Icons.Filled.KeyboardArrowUp
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                if (endpointsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoRow(label = "API", value = apiServerUrl, monospace = true)
                        InfoRow(label = "Relay", value = relayUrl, monospace = true)
                        ChipRow(label = "Relay state") {
                            connectionChip(relayConnectionState)
                        }
                        InfoRow(label = "Streaming", value = chatMode.toString())
                    }
                }
            }

            HorizontalDivider()

            // ---- Footer action: jump to Connections settings ----
            TextButton(
                onClick = {
                    onDismiss()
                    onNavigateToConnections()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Manage connections\u2026")
            }
        }
    }
}

// --- AgentInfoSheet helpers ----------------------------------------------

@Composable
private fun SectionLabel(title: String, hint: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Radio-style row used by both Profile and Personality sections. Whole row
 * is a tap target (and selectable() for a11y). Disabled when [enabled] is
 * false — look and behaviour both propagate the gate.
 */
@Composable
private fun ProfileRadioRow(
    primary: String,
    secondary: String?,
    tertiary: String? = null,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val rowAlpha = if (enabled) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .alpha(rowAlpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tertiary != null) {
                Text(
                    text = tertiary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AgentSheetHeader(
    selectedProfile: Profile?,
    selectedPersonality: String,
    defaultPersonality: String,
    serverModelName: String,
    apiServerReachable: Boolean,
    chatMode: ChatMode,
) {
    val agentName = when {
        selectedProfile != null -> selectedProfile.name.replaceFirstChar { it.uppercase() }
        selectedPersonality != "default" -> selectedPersonality.replaceFirstChar { it.uppercase() }
        defaultPersonality.isNotBlank() -> defaultPersonality.replaceFirstChar { it.uppercase() }
        else -> "Hermes"
    }
    val modelLabel = selectedProfile?.model ?: serverModelName
    val isConnecting = !apiServerReachable && chatMode != ChatMode.DISCONNECTED
    val statusText = when {
        apiServerReachable -> "Connected"
        isConnecting -> "Connecting\u2026"
        else -> "Disconnected"
    }
    val statusColor = when {
        apiServerReachable -> MaterialTheme.colorScheme.primary
        isConnecting -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    val customized = selectedProfile != null || selectedPersonality != "default"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .then(
                    if (customized) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        )
                    } else Modifier
                )
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = agentName.firstOrNull()?.uppercase() ?: "H",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = agentName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            if (modelLabel.isNotBlank()) {
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                maxLines = 1,
            )
        }
    }
}
