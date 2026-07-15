package com.hermesandroid.relay.ui.components

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import com.hermesandroid.relay.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.AppAnalytics
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.ProfilePresentation
import com.hermesandroid.relay.data.ProfilePresentationPolicy
import com.hermesandroid.relay.data.ProfilePresence
import com.hermesandroid.relay.data.ProfilePresenceResolver
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.network.upstream.ChatMode
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.relay.ConnectionState
import com.hermesandroid.relay.ui.UiMessageBus
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
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
            stringResource(R.string.conn_info_connected),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        ConnectionState.Connecting -> Triple(
            stringResource(R.string.conn_info_connecting),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        ConnectionState.Reconnecting -> Triple(
            stringResource(R.string.conn_info_reconnecting),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        ConnectionState.Disconnected -> Triple(
            stringResource(R.string.conn_info_disconnected),
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
            stringResource(R.string.conn_info_unpaired),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        is AuthState.Pairing -> Triple(
            stringResource(R.string.conn_info_pairing),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        is AuthState.Paired -> Triple(
            stringResource(R.string.conn_info_paired),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        is AuthState.Failed -> Triple(
            stringResource(R.string.conn_info_failed_reason, state.reason),
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
    // Pre-resolve strings for non-Composable callbacks/lambdas
    val pairingCodeLabel = stringResource(R.string.conn_info_pairing_code)
    val copyPairingCodeDesc = stringResource(R.string.conn_info_copy_pairing_code)
    val grantNeverFmt = stringResource(R.string.conn_info_grant_never)
    val channelGrantsLabel = stringResource(R.string.conn_info_channel_grants)
    val transportLabel = stringResource(R.string.conn_info_transport)
    val testingText = stringResource(R.string.conn_info_testing)
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.conn_info_session_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.conn_info_session_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            ChipRow(label = stringResource(R.string.conn_info_state)) { authStateChip(authState) }

            InfoRow(label = stringResource(R.string.conn_info_relay_url), value = relayUrl, monospace = true)

            ChipRow(label = stringResource(R.string.conn_info_connection)) { connectionChip(relayConnectionState) }

            HorizontalDivider()

            // Pairing code — big monospace + copy
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.conn_info_pairing_code),
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
                                    ClipData.newPlainText(pairingCodeLabel, pairingCode)
                                )
                            )
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.conn_info_copy_pairing_code)
                        )
                    }
                }
            }

            HorizontalDivider()

            InfoRow(
                label = stringResource(R.string.conn_info_device),
                value = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            )

            InfoRow(
                label = stringResource(R.string.conn_info_session_token_present),
                value = if (authState is AuthState.Paired) stringResource(R.string.conn_info_yes) else stringResource(R.string.conn_info_no)
            )

            InfoRow(
                label = stringResource(R.string.conn_info_agent_profiles),
                value = if (agentProfiles.isEmpty()) {
                    stringResource(R.string.conn_info_none)
                } else {
                    agentProfiles.joinToString(", ") { it.name }
                }
            )

            // Security overhaul (2026-04-11) — show expiry + grants + storage.
            pairedSession?.let { paired ->
                HorizontalDivider()
                val expiryLabel = when {
                    paired.expiresAt == null -> stringResource(R.string.conn_info_never)
                    else -> java.text.DateFormat
                        .getDateInstance(java.text.DateFormat.MEDIUM)
                        .format(java.util.Date(paired.expiresAt * 1000L))
                }
                InfoRow(label = stringResource(R.string.conn_info_expires), value = expiryLabel)

                if (paired.grants.isNotEmpty()) {
                    val grantsLabel = paired.grants.entries.joinToString(", ") { (k, v) ->
                        if (v == null) String.format(grantNeverFmt, k) else k
                    }
                    InfoRow(label = channelGrantsLabel, value = grantsLabel)
                }

                val transportLabelText = paired.transportHint?.uppercase() ?: "—"
                InfoRow(label = transportLabel, value = transportLabelText)

                InfoRow(
                    label = stringResource(R.string.conn_info_key_storage),
                    value = if (paired.hasHardwareStorage) stringResource(R.string.conn_info_hardware_strongbox) else stringResource(R.string.conn_info_hardware_tee)
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
                    Text(stringResource(R.string.conn_info_clear_session))
                }
                OutlinedButton(
                    onClick = { connectionViewModel.regeneratePairingCode() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.conn_info_regenerate_code))
                }
            }

            HorizontalDivider()
            DiagnosticsLogPanel(
                categories = setOf(
                    DiagnosticCategory.Session,
                    DiagnosticCategory.Auth,
                    DiagnosticCategory.Relay,
                ),
                limit = 6,
                showCategory = true,
            )
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
    val testingText = stringResource(R.string.conn_info_testing)

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.conn_info_api_server_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.conn_info_api_server_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            InfoRow(label = stringResource(R.string.conn_info_url), value = apiServerUrl, monospace = true)

            ChipRow(label = stringResource(R.string.conn_info_reachable)) {
                val (label, bg, fg) = if (apiServerReachable) {
                    Triple(
                        stringResource(R.string.conn_info_yes),
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Triple(
                        stringResource(R.string.conn_info_no),
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                StatusChip(text = label, background = bg, contentColor = fg)
            }

            InfoRow(label = stringResource(R.string.conn_info_streaming_mode), value = chatMode.toString())
            InfoRow(label = stringResource(R.string.conn_info_route_preference), value = streamingEndpoint)
            InfoRow(
                label = stringResource(R.string.conn_info_api_key_set),
                value = if (apiKeyPresent) stringResource(R.string.conn_info_yes_hidden) else stringResource(R.string.conn_info_no)
            )
            InfoRow(
                label = stringResource(R.string.conn_info_last_health_check),
                value = if (apiServerReachable) stringResource(R.string.conn_info_just_now_ok) else stringResource(R.string.conn_info_just_now_failed)
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
                    testResult = testingText
                    connectionViewModel.testApiConnection { _success, message ->
                        testing = false
                        testResult = message
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (testing) stringResource(R.string.conn_info_testing) else stringResource(R.string.conn_info_test_connection))
            }

            HorizontalDivider()
            DiagnosticsLogPanel(
                categories = setOf(
                    DiagnosticCategory.Api,
                    DiagnosticCategory.Auth,
                    DiagnosticCategory.Endpoint,
                ),
                limit = 6,
                showCategory = true,
            )
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.conn_info_relay_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.conn_info_relay_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            InfoRow(label = stringResource(R.string.conn_info_url), value = relayUrl, monospace = true)
            ChipRow(label = stringResource(R.string.conn_info_connection_state)) { connectionChip(relayConnectionState) }

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
                        text = stringResource(R.string.conn_info_ws_unencrypted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            InfoRow(
                label = stringResource(R.string.conn_info_insecure_mode_allowed),
                value = if (insecureMode) stringResource(R.string.conn_info_yes) else stringResource(R.string.conn_info_no)
            )
            InfoRow(
                label = stringResource(R.string.conn_info_relay_enabled_flag),
                value = if (relayEnabled) stringResource(R.string.conn_info_yes) else stringResource(R.string.conn_info_no)
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
                    Text(stringResource(R.string.conn_info_connect))
                }
                OutlinedButton(
                    onClick = { connectionViewModel.disconnectRelay() },
                    enabled = relayEnabled && isConnected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.conn_info_disconnect))
                }
            }

            HorizontalDivider()
            DiagnosticsLogPanel(
                categories = setOf(
                    DiagnosticCategory.Relay,
                    DiagnosticCategory.Endpoint,
                    DiagnosticCategory.Session,
                    DiagnosticCategory.Voice,
                ),
                limit = 8,
                showCategory = true,
            )
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
    onNavigateToProfileInspector: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Profile + personality state — same flows the old pickers consumed.
    val agentProfiles by connectionViewModel.agentProfiles.collectAsState()
    val selectedProfile by connectionViewModel.selectedProfile.collectAsState()
    val profilePresentation by connectionViewModel.profilePresentation.collectAsState()
    var showProfileManager by remember { mutableStateOf(false) }
    val profileDisplayAlias by connectionViewModel.profileDisplayAlias.collectAsState()
    // Profile lock — when set, the picker below collapses to a single static
    // "Locked to <name>" row. Only the dedicated Settings control still lists
    // every profile (to change the lock target or unlock).
    val isProfileLocked by connectionViewModel.isProfileLocked.collectAsState()
    val lockedProfileName by connectionViewModel.lockedProfileName.collectAsState()
    val selectedPersonality by chatViewModel.selectedPersonality.collectAsState()
    val personalityNames by chatViewModel.personalityNames.collectAsState()
    val defaultPersonality by chatViewModel.defaultPersonality.collectAsState()
    val availableModels by chatViewModel.availableModels.collectAsState()
    val selectedModelOverride by chatViewModel.selectedModelOverride.collectAsState()
    val modelProviders by chatViewModel.modelProviders.collectAsState()
    val yoloEnabled by chatViewModel.yoloEnabled.collectAsState()
    val fastEnabled by chatViewModel.fastEnabled.collectAsState()
    // YOLO / Fast are gateway-only. This says whether the gateway is present (or
    // still being probed) so we can SHOW those controls — present-but-loading
    // reads as "checking", not "you don't have this". Only a definitively
    // unreachable gateway (SSE-only connection) hides them.
    val gatewayAvailability by connectionViewModel.gatewayAvailability.collectAsState()
    // Capability snapshot for the transport-tier ladder in SessionPathDetails.
    val serverCapabilities by connectionViewModel.serverCapabilities.collectAsState()

    // Pull the gateway's curated provider/model list (model.options) when the
    // sheet opens — the real switchable models, grouped by provider.
    LaunchedEffect(Unit) { chatViewModel.refreshModelOptions() }
    // Re-pull server-supplied personalities (list + default + active) on open so
    // a server-side change shows without an app reload.
    LaunchedEffect(Unit) { chatViewModel.refreshPersonalities() }
    // Re-pull the SSE-fallback model list + skill catalog on open so server-side
    // changes surface without an app reload (gateway model groups are covered by
    // refreshModelOptions above; skills feed the command palette).
    LaunchedEffect(Unit) { chatViewModel.refreshModels() }
    LaunchedEffect(Unit) { chatViewModel.refreshSkills() }
    // Pull the host's agent profiles from the dashboard so they appear in the
    // Profile picker even on a dashboard-only (non-relay) connection.
    LaunchedEffect(Unit) { connectionViewModel.refreshDashboardProfiles() }

    // "Still settling" window for the picker LISTS (models / personalities). The
    // refreshes above fire on open; show a brief loading cue while a list is
    // empty — but BOUND it, because a server that genuinely has none (very common
    // for named personalities) must not spin forever. After this window an empty
    // list honestly reads as "none", while the section itself (Server default /
    // None) is always present so the capability never looks absent.
    var pickerListsSettling by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(5000)
        pickerListsSettling = false
    }

    // Connection summary state.
    val authState by connectionViewModel.authState.collectAsState()
    val apiServerUrl by connectionViewModel.apiServerUrl.collectAsState()
    val apiServerReachable by connectionViewModel.apiServerReachable.collectAsState()
    val chatMode by connectionViewModel.chatMode.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val pairingCode by connectionViewModel.pairingCode.collectAsState()
    val serverModelName by chatViewModel.serverModelName.collectAsState()
    val gatewayCurrentModel by chatViewModel.gatewayCurrentModel.collectAsState()
    val gatewayCurrentProvider by chatViewModel.gatewayCurrentProvider.collectAsState()

    // Session-path state — drives the glanceable transport/route summary +
    // capability chips at the top of the Connection section. The resolver
    // turns the user's preference into the concrete transport actually in use
    // ("gateway" / "sessions" / "completions" / "runs"); `activeEndpoint`
    // gives the friendly route label (LAN / Tailscale / Public / custom).
    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()
    val activeEndpoint by connectionViewModel.activeEndpoint.collectAsState()
    val voiceReady by connectionViewModel.voiceReady.collectAsState()

    // Multi-connection switcher state — folded into this sheet in place of
    // the separate top-bar ConnectionChip (see 2026-04-20 DEVLOG). Read
    // through the store so the sheet picks up add/remove/rename events
    // without needing an explicit re-collect.
    val allConnections by connectionViewModel.connectionStore.connections.collectAsState()
    val activeConnectionId by connectionViewModel.connectionStore
        .activeConnectionId.collectAsState()

    // Mid-stream gate — mirrors what ProfilePicker's `enabled` flag was doing:
    // a radio tap during an in-flight chat turn would race the request. Apply
    // to BOTH sections (profile + personality) since they both feed startStream.
    val isStreaming by chatViewModel.isStreaming.collectAsState()
    // Gateway turns are profile-bound upstream and can continue after Android
    // detaches their visible callbacks. SSE transports cannot safely do that.
    val profileSwitchEnabled = !isStreaming || chatViewModel.streamingEndpoint == "gateway"

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
    val effectiveDisplayProfile = AgentDisplay.effectiveDisplayProfile(
        selectedProfile = selectedProfile,
        profiles = agentProfiles,
    )
    val serverResolvedAgentName = AgentDisplay.agentName(
        profile = effectiveDisplayProfile,
        selectedPersonality = selectedPersonality,
        defaultPersonality = defaultPersonality,
        connectionLabel = null,
    )

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // Pre-resolve strings for non-composable contexts (toast function)
    val usingServerDefaultToast = stringResource(R.string.conn_info_using_server_default)
    val switchedToProfileToast = stringResource(R.string.conn_info_switched_to_profile)
    val switchedToProfileModelToast = stringResource(R.string.conn_info_switched_to_profile_model)
    val switchedToProfileSoulToast = stringResource(R.string.conn_info_switched_to_profile_soul)
    val personalityClearedToast = stringResource(R.string.conn_info_personality_cleared)
    val personalityToast = stringResource(R.string.conn_info_personality)
    val usingServerDefaultModelToast = stringResource(R.string.conn_info_using_server_default_model)
    val modelToast = stringResource(R.string.conn_info_model)
    val switchedToConnectionToast = stringResource(R.string.conn_info_switched_to_connection)
    val copyPairingCodeDesc = stringResource(R.string.conn_info_copy_pairing_code)
    val pairingCodeLabel = stringResource(R.string.conn_info_pairing_code)

    // Transient confirmation when the user picks a different profile or
    // personality from inside the sheet. Routed to the top info-banner
    // (UiMessageBus) instead of the snackbar so these frequent tap acks slide
    // in quietly rather than popping an obtrusive overlay.
    fun toast(message: String) {
        UiMessageBus.info(message)
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
            // Detail view shows the provider next to the model (desktop-style);
            // the chat composer pill stays model-only.
            val currentProviderLabel = modelProviders
                .firstOrNull { gatewayCurrentProvider.isNotBlank() && it.slug.equals(gatewayCurrentProvider, ignoreCase = true) }
                ?.name
                ?.takeIf { it.isNotBlank() }
            AgentSheetHeader(
                profile = effectiveDisplayProfile,
                selectedPersonality = selectedPersonality,
                defaultPersonality = defaultPersonality,
                localDisplayAlias = profileDisplayAlias,
                serverModelName = serverModelName,
                // The SESSION's live model — so the header model pairs with the
                // (session-scoped) provider label instead of mixing the global
                // default with the session provider.
                sessionModelName = selectedModelOverride ?: gatewayCurrentModel,
                modelProviderLabel = currentProviderLabel,
                apiServerReachable = apiServerReachable,
                chatMode = chatMode,
                isCustomized = selectedProfile != null ||
                    selectedPersonality != "default" ||
                    profileDisplayAlias != null,
            )

            DisplayAliasSection(
                currentAlias = profileDisplayAlias,
                fallbackName = serverResolvedAgentName,
                onSave = connectionViewModel::setProfileDisplayAlias,
            )

            AgentIconRow(connectionViewModel)

            HorizontalDivider()

            // ---- Profile section (hidden when server advertises none) ----
            if (agentProfiles.isNotEmpty()) {
                // Apparent "server default" profile — the one the server
                // would use if the phone sent no override. We infer this
                // from the runtime metadata: the single profile (if any)
                // whose gateway is reporting running. Used to add a tiny
                // caption to that row so users can tell which of their
                // profiles the server is actively serving — especially
                // important when one of the profiles is literally named
                // "default" so "Server default" vs "default" would be
                // otherwise indistinguishable.
                val serverDefaultProfile = agentProfiles
                    .firstOrNull { AgentDisplay.isServerDefaultAlias(it.name) }
                val selectedKey = AgentDisplay.profileSessionKey(selectedProfile?.name)
                val visibleProfileKeys = ProfilePresentationPolicy
                    .visibleKeys(agentProfiles, profilePresentation, selectedKey)
                val apparentActiveProfile = agentProfiles
                    .firstOrNull { it.gatewayRunning }

                CollapsiblePickerSection(
                    title = stringResource(R.string.conn_info_profile),
                    hint = stringResource(R.string.conn_info_profile_hint),
                    currentValue = profileDisplayAlias
                        ?: AgentDisplay.profileDisplayName(selectedProfile)
                        ?: stringResource(R.string.conn_info_server_default),
                ) {

                  if (isProfileLocked) {
                    // Pinned to one profile — collapse the whole radio list to a
                    // single static, non-interactive row. The lock target is the
                    // raw stored token: the sentinel means Server default, any
                    // other value is a profile name (resolved to its display name).
                    val lockedDisplayName = when {
                        lockedProfileName == null ->
                            stringResource(R.string.conn_info_server_default)
                        AgentDisplay.isServerDefaultAlias(lockedProfileName) ||
                            lockedProfileName == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY ->
                            stringResource(R.string.conn_info_server_default)
                        else ->
                            agentProfiles
                                .firstOrNull { it.name == lockedProfileName }
                                ?.let { AgentDisplay.profileDisplayName(it) }
                                ?: lockedProfileName!!.replaceFirstChar { it.uppercase() }
                    }
                    LockedProfileRow(lockedDisplayName = lockedDisplayName)
                  } else {

                    val profileHostReachable = gatewayAvailability == GatewayAvailability.Ready
                    val defaultPresence = serverDefaultProfile?.let { profile ->
                        ProfilePresenceResolver.resolve(profile, profileHostReachable)
                    }
                    val defaultDotColor = defaultPresence?.let { presence ->
                        when (presence) {
                            ProfilePresence.ONLINE -> MaterialTheme.colorScheme.primary
                            ProfilePresence.AVAILABLE -> MaterialTheme.colorScheme.tertiary
                            ProfilePresence.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        }
                    }
                    val defaultDotA11y = serverDefaultProfile?.let { profile ->
                        when (defaultPresence) {
                            ProfilePresence.ONLINE -> stringResource(R.string.conn_info_profile_online_desc)
                            ProfilePresence.AVAILABLE -> stringResource(R.string.conn_info_profile_available_desc)
                            else -> stringResource(R.string.conn_info_profile_offline_desc)
                        }
                    }
                    val defaultDisplay = if (selectedProfile == null && profileDisplayAlias != null) {
                        profileDisplayAlias
                    } else {
                        serverDefaultProfile?.let { profile ->
                            AgentDisplay.profileDisplayName(profile)
                            ?: profile.name.replaceFirstChar { it.uppercase() }
                        }
                    }
                    val defaultSecondary = serverDefaultProfile?.let { profile ->
                        listOfNotNull(
                            defaultDisplay,
                            profile.model.takeIf { it.isNotBlank() },
                        ).joinToString(" \u2022 ")
                    } ?: stringResource(R.string.conn_info_use_default_profile)
                    val soulBg = MaterialTheme.colorScheme.primaryContainer
                    val soulFg = MaterialTheme.colorScheme.onPrimaryContainer
                    val skillsBg = MaterialTheme.colorScheme.surfaceVariant
                    val skillsFg = MaterialTheme.colorScheme.onSurfaceVariant

                    // Pre-resolve strings for Profile section
                    val serverDefaultPrimary = stringResource(R.string.conn_info_server_default)
                    val usesDefaultProfileTertiary = stringResource(R.string.conn_info_uses_default_profile)
                    val skillsBadgeText = stringResource(R.string.conn_info_skills_count)

                    // Render the default alias and named profiles through the same
                    // saved order so non-default profiles are not second-class.
                    visibleProfileKeys.forEach { profileKey ->
                      if (profileKey == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY) {
                        ProfileRadioRow(
                        primary = serverDefaultPrimary,
                        secondary = defaultSecondary,
                        tertiary = usesDefaultProfileTertiary,
                        selected = selectedProfile == null,
                        enabled = profileSwitchEnabled,
                        leadingDotColor = defaultDotColor,
                        leadingDotContentDescription = defaultDotA11y,
                        secondaryTrailing = serverDefaultProfile?.let { profile ->
                            {
                                defaultPresence?.let { presence ->
                                    ProfileMetadataBadge(
                                        text = stringResource(
                                            when (presence) {
                                                ProfilePresence.ONLINE -> R.string.conn_info_profile_online
                                                ProfilePresence.AVAILABLE -> R.string.conn_info_profile_available
                                                ProfilePresence.OFFLINE -> R.string.conn_info_profile_offline
                                            },
                                        ),
                                        background = when (presence) {
                                            ProfilePresence.ONLINE -> MaterialTheme.colorScheme.primary
                                            ProfilePresence.AVAILABLE -> MaterialTheme.colorScheme.tertiaryContainer
                                            ProfilePresence.OFFLINE -> MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        contentColor = when (presence) {
                                            ProfilePresence.ONLINE -> MaterialTheme.colorScheme.onPrimary
                                            ProfilePresence.AVAILABLE -> MaterialTheme.colorScheme.onTertiaryContainer
                                            ProfilePresence.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                    if (profile.skillCount > 0) {
                                        ProfileMetadataBadge(
                                            text = skillsBadgeText.format(profile.skillCount),
                                            background = skillsBg,
                                            contentColor = skillsFg,
                                        )
                                    }
                                    if (profile.hasSoul) {
                                        ProfileMetadataBadge(
                                            text = stringResource(R.string.conn_info_soul),
                                            background = soulBg,
                                            contentColor = soulFg,
                                        )
                                    }
                            }
                        },
                        onSelect = {
                            if (selectedProfile != null) {
                                connectionViewModel.selectProfile(null)
                                // Gateway turns carry no per-request profile —
                                // hot-swap the live session server-side too.
                                chatViewModel.activateGatewayProfile(null)
                                toast(usingServerDefaultToast)
                            }
                        },
                        )
                      } else {
                        val profile = agentProfiles.firstOrNull { it.name == profileKey }
                            ?: return@forEach
                        // Presence is distinct from selection: several profile
                        // gateways may be Online, while Available profiles can
                        // still start/resume chats lazily through tui_gateway.
                        val presence = ProfilePresenceResolver.resolve(profile, profileHostReachable)
                        val dotColor = when (presence) {
                            ProfilePresence.ONLINE -> MaterialTheme.colorScheme.primary
                            ProfilePresence.AVAILABLE -> MaterialTheme.colorScheme.tertiary
                            ProfilePresence.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        }
                        val dotA11y = when (presence) {
                            ProfilePresence.ONLINE -> stringResource(R.string.conn_info_profile_online_desc)
                            ProfilePresence.AVAILABLE -> stringResource(R.string.conn_info_profile_available_desc)
                            ProfilePresence.OFFLINE -> stringResource(R.string.conn_info_profile_offline_desc)
                        }
                        val isApparentActive =
                            apparentActiveProfile?.name == profile.name
                        // Emphasize the description as the primary label
                        // when present — it's what users recognise (e.g.
                        // "Victor") better than the profile key. Fall back
                        // to the capitalised profile name when description
                        // is blank.
                        val hasDescription = profile.description.isNotBlank()
                        // Headline is the profile NAME (easy to scan); the
                        // description + model ride one subtitle line.
                        val primaryLabel = if (
                            selectedProfile?.name == profile.name &&
                            profileDisplayAlias != null
                        ) {
                            profileDisplayAlias.orEmpty()
                        } else {
                            profile.name.replaceFirstChar { it.uppercase() }
                        }
                        // Secondary line: `modelname • Running` / `• Idle`.
                        // The dot itself carries the same information via
                        // colour; the text label is the a11y-visible
                        // complement so a screen reader user also gets
                        // the status without relying on colour.
                        val secondaryLine = listOfNotNull(
                            profile.description.takeIf { hasDescription },
                            profile.model.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").takeIf { it.isNotBlank() }
                        // Tertiary caption: the profile identifier (when
                        // we promoted the description to primary) plus an
                        // "active on server" hint when this row matches
                        // the apparent default.
                        val tertiaryLine: String? = null

                        // Pre-resolve strings for profile badges
                        val presenceBadgeText = when (presence) {
                            ProfilePresence.ONLINE -> stringResource(R.string.conn_info_profile_online)
                            ProfilePresence.AVAILABLE -> stringResource(R.string.conn_info_profile_available)
                            ProfilePresence.OFFLINE -> stringResource(R.string.conn_info_profile_offline)
                        }
                        val skillsBadgeTextFormat = stringResource(R.string.conn_info_skills_count)
                        val soulBadgeText = stringResource(R.string.conn_info_soul)
                        val profileApiActiveSuffix = stringResource(R.string.conn_info_profile_api_active_suffix)
                        val profileModelSoulSuffix = stringResource(R.string.conn_info_profile_model_soul_suffix)
                        val profileModelSuffix = stringResource(R.string.conn_info_profile_model_suffix)

                        ProfileRadioRow(
                            primary = primaryLabel,
                            secondary = secondaryLine,
                            // Long descriptions (e.g. "Sentinel —
                            // Infrastructure / Security / Reliability ·
                            // gpt-5.5") truncate to two lines + tap-to-expand
                            // so they never crunch the badge FlowRow below.
                            secondaryExpandable = true,
                            // Cleaner card: drop the verbose "profile: … ·
                            // compatibility overlay · active" caption now that
                            // the name is the headline.
                            tertiary = null,
                            selected = selectedProfile?.name == profile.name,
                            enabled = profileSwitchEnabled,
                            contentAlpha = 1f,
                            leadingDotColor = dotColor,
                            leadingDotContentDescription = dotA11y,
                            secondaryTrailing = {
                                ProfileMetadataBadge(
                                    text = presenceBadgeText,
                                    background = when (presence) {
                                        ProfilePresence.ONLINE -> MaterialTheme.colorScheme.primary
                                        ProfilePresence.AVAILABLE -> MaterialTheme.colorScheme.tertiaryContainer
                                        ProfilePresence.OFFLINE -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = when (presence) {
                                        ProfilePresence.ONLINE -> MaterialTheme.colorScheme.onPrimary
                                        ProfilePresence.AVAILABLE -> MaterialTheme.colorScheme.onTertiaryContainer
                                        ProfilePresence.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                if (profile.skillCount > 0) {
                                    ProfileMetadataBadge(
                                        text = skillsBadgeTextFormat.format(profile.skillCount),
                                        background = skillsBg,
                                        contentColor = skillsFg,
                                    )
                                }
                                if (profile.hasSoul) {
                                    ProfileMetadataBadge(
                                        text = soulBadgeText,
                                        background = soulBg,
                                        contentColor = soulFg,
                                    )
                                }
                            },
                            onSelect = {
                                if (selectedProfile?.name != profile.name) {
                                    connectionViewModel.selectProfile(profile)
                                    // Gateway turns carry no per-request profile;
                                    // hot-swap the live session server-side so the
                                    // agent (SOUL+model+skills) changes in place.
                                    chatViewModel.activateGatewayProfile(profile)
                                    val display = primaryLabel
                                    val suffix = if (profile.hasIsolatedApi) {
                                        profileApiActiveSuffix
                                    } else if (profile.systemMessage?.isNotBlank() == true) {
                                        profileModelSoulSuffix
                                    } else {
                                        profileModelSuffix
                                    }
                                    toast(switchedToProfileToast.format(display) + suffix)
                                }
                            },
                        )
                      }
                    }

                    TextButton(
                        onClick = { showProfileManager = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(imageVector = Icons.Filled.Tune, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.conn_info_manage_profiles))
                    }

                    val inspectProfileText = stringResource(R.string.conn_info_inspect_profile)
                    val inspectorTarget = selectedProfile
                        ?: serverDefaultProfile
                        ?: visibleProfileKeys
                            .asSequence()
                            .mapNotNull { key -> agentProfiles.firstOrNull { it.name == key } }
                            .firstOrNull()
                    inspectorTarget?.let { profile ->
                        TextButton(
                            onClick = {
                                onDismiss()
                                onNavigateToProfileInspector(profile.name)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(inspectProfileText.format(AgentDisplay.profileDisplayName(profile) ?: profile.name))
                        }
                    }

                    if (profileOverridesPersonality) {
                        Text(
                            text = stringResource(R.string.conn_info_profile_overrides_personality),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                        )
                    }
                  } // end else (not locked)
                }

                HorizontalDivider()
            } else if (pickerListsSettling) {
                // Profiles are optional host config — a server may legitimately
                // have none. Show the section while they might still be loading so
                // it doesn't feel absent; once settled with none it cleanly
                // disappears (the user is simply on the server default).
                CollapsiblePickerSection(
                    title = stringResource(R.string.conn_info_profile),
                    hint = stringResource(R.string.conn_info_profile_hint),
                    currentValue = stringResource(R.string.conn_info_server_default),
                ) {
                    PickerLoadingRow(stringResource(R.string.conn_info_loading_profiles))
                }
                HorizontalDivider()
            }

            // ---- Personality section ----
            // De-emphasize when a profile's systemMessage is taking over — the
            // row is still tappable because the user may want to queue the
            // choice for after they clear the profile. No alpha on the entire
            // Column because the section header would look broken.
            CollapsiblePickerSection(
                title = stringResource(R.string.conn_info_personality_title),
                hint = stringResource(R.string.conn_info_personality_hint),
                currentValue = AgentDisplay.personalityLabel(selectedPersonality, defaultPersonality),
                modifier = Modifier.alpha(if (profileOverridesPersonality) 0.55f else 1f),
            ) {

                // Pre-resolve strings for Personality section
                val nonePrimary = stringResource(R.string.conn_info_none)
                val noPersonalityOverlay = stringResource(R.string.conn_info_no_personality_overlay)
                val loadingPersonalities = stringResource(R.string.conn_info_loading_personalities)
                val defaultSuffix = stringResource(R.string.conn_info_default_suffix)

                // None row — the explicit "no personality overlay" state
                // (upstream `/personality none`). On the gateway this is
                // server-applied; on SSE it just sends no persona prompt. There
                // is intentionally no synthetic client "Default" row — upstream's
                // active value is `none` or a named personality, and the server's
                // configured default (if any) shows below as the row tagged
                // "(default)", highlighted whenever it's the active one.
                ProfileRadioRow(
                    primary = nonePrimary,
                    secondary = noPersonalityOverlay,
                    selected = selectedPersonality == "none" || selectedPersonality == "neutral",
                    enabled = !isStreaming,
                    onSelect = {
                        if (selectedPersonality != "none") {
                            chatViewModel.selectPersonality("none")
                            if (!profileOverridesPersonality) {
                                toast(personalityClearedToast)
                            }
                        }
                    },
                )

                if (personalityNames.isEmpty() && pickerListsSettling) {
                    // Named personalities still loading — bounded so a server with
                    // none (common) doesn't spin forever; "None" above is always valid.
                    PickerLoadingRow(loadingPersonalities)
                }

                personalityNames.forEach { name ->
                    val isServerDefault = name.equals(defaultPersonality, ignoreCase = true)
                    ProfileRadioRow(
                        primary = name.replaceFirstChar { it.uppercase() } +
                            if (isServerDefault) defaultSuffix else "",
                        secondary = null,
                        selected = selectedPersonality == name,
                        enabled = !isStreaming,
                        onSelect = {
                            if (selectedPersonality != name) {
                                chatViewModel.selectPersonality(name)
                                if (!profileOverridesPersonality) {
                                    val display = name.replaceFirstChar { it.uppercase() }
                                    toast(personalityToast.format(display))
                                }
                            }
                        },
                    )
                }

                // When a profile SOUL is active AND the user has picked a
                // non-default personality, make the precedence explicit
                // here at the point the confusion would otherwise land.
                // The mirror caption in the Profile section tells the same
                // story from the other direction — both are kept because a
                // user scanning either section should see the constraint.
                if (profileOverridesPersonality &&
                    selectedPersonality != "default" && selectedPersonality != "none"
                ) {
                    Text(
                        text = stringResource(R.string.conn_info_soul_overrides_personality),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                }
            }

            // ---- Model section (host-side provider model) ----
            // Switches the model for THIS session. On the gateway this fires a
            // `/model` dispatch (the rich model-info card lands in chat); on SSE
            // the pick rides the next request body. Hidden when the server
            // advertises no models. Locked mid-turn — the gateway rejects a
            // switch while a turn runs, and SSE would race the in-flight request.
            // SSE fallback model list — /v1/models plus the configured profiles'
            // models (used only when the gateway model.options groups aren't
            // available, e.g. on an SSE transport).
            val sseModelOptions = remember(availableModels, agentProfiles, selectedModelOverride) {
                (availableModels.mapNotNull(AgentDisplay::displayModelName) +
                    agentProfiles.mapNotNull { AgentDisplay.displayModelName(it.model) } +
                    listOfNotNull(AgentDisplay.displayModelName(selectedModelOverride)))
                    .distinct()
            }
            // Always show the Model picker — choosing a model is always possible
            // (Server default at minimum). While the provider/model list is still
            // being fetched we show a "loading" row rather than hiding the whole
            // section (an absent section read as "no model control at all").
            run {
                HorizontalDivider()

                // Pre-resolve strings for Model section
                val modelTitle = stringResource(R.string.conn_info_model_title)
                val modelHint = stringResource(R.string.conn_info_model_hint)
                val serverDefaultModel = stringResource(R.string.conn_info_server_default)
                val loadingModels = stringResource(R.string.conn_info_loading_models)

                CollapsiblePickerSection(
                    title = modelTitle,
                    hint = modelHint,
                    currentValue = selectedModelOverride ?: serverDefaultModel,
                ) {
                    ProfileRadioRow(
                        primary = serverDefaultModel,
                        secondary = AgentDisplay.displayModelName(serverModelName),
                        selected = selectedModelOverride == null,
                        enabled = !isStreaming,
                        onSelect = {
                            if (selectedModelOverride != null) {
                                chatViewModel.selectModel(null)
                                toast(usingServerDefaultModelToast)
                            }
                        },
                    )
                    if (modelProviders.isEmpty() && sseModelOptions.isEmpty() && pickerListsSettling) {
                        // List still loading — honest, BOUNDED "more coming" cue
                        // (settles to nothing if the server exposes no extra models).
                        PickerLoadingRow(loadingModels)
                    }
                    if (modelProviders.isNotEmpty()) {
                        // Gateway: the curated provider→model groups the desktop
                        // picker uses (grok / kimi / gpt-5.5 …). Each provider's
                        // models are grouped under its name; the switch carries
                        // `--provider <slug>`.
                        modelProviders.forEach { provider ->
                            if (provider.models.isNotEmpty()) {
                                Text(
                                    text = provider.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                                )
                                provider.models.forEach { model ->
                                    ProfileRadioRow(
                                        primary = model,
                                        secondary = null,
                                        selected = selectedModelOverride == model,
                                        enabled = !isStreaming,
                                        onSelect = {
                                            if (selectedModelOverride != model) {
                                                chatViewModel.selectModel(model, provider.slug)
                                                toast(modelToast.format(model))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        sseModelOptions.forEach { model ->
                            ProfileRadioRow(
                                primary = model,
                                secondary = null,
                                selected = selectedModelOverride == model,
                                enabled = !isStreaming,
                                onSelect = {
                                    if (selectedModelOverride != model) {
                                        chatViewModel.selectModel(model)
                                        toast(modelToast.format(model))
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ---- Safety & speed section ----
            // YOLO (approval bypass) + Fast (priority tier) are standard upstream
            // gateway features (they mirror the desktop config.set yolo/fast,
            // session-scoped, tracked live via session.info). NEVER hidden: live
            // controls when the gateway is usable, "Checking…" while a value
            // loads, or cleanly disabled WITH the reason when the gateway isn't
            // reachable / needs sign-in — so the capability is always visible.
            run {
                // Pre-resolve strings for Safety & Speed section
                val gatewayUnavailableApiServer = stringResource(R.string.conn_info_gateway_unavailable_api_server)
                val gatewayUnavailableSignIn = stringResource(R.string.conn_info_gateway_unavailable_sign_in)
                val safetySpeedTitle = stringResource(R.string.conn_info_safety_speed_title)
                val yoloModeTitle = stringResource(R.string.conn_info_yolo_mode_title)
                val yoloModeDesc = stringResource(R.string.conn_info_yolo_mode_desc)
                val approvalsOff = stringResource(R.string.conn_info_approvals_off)
                val fastModeTitle = stringResource(R.string.conn_info_fast_mode_title)
                val fastModeDesc = stringResource(R.string.conn_info_fast_mode_desc)

                val gatewayUnavailableReason: String? = when (gatewayAvailability) {
                    GatewayAvailability.Unreachable -> gatewayUnavailableApiServer
                    GatewayAvailability.SignInRequired -> gatewayUnavailableSignIn
                    // Ready, or Unknown (still probing) → available / loading.
                    else -> null
                }
                val gatewayControlsAvailable = gatewayUnavailableReason == null
                // Ready (socket up) → a null value is just "unconfirmed for this
                // draft, settles on the next message". Unknown (still probing)
                // keeps the "Checking…" spinner.
                val gatewayReady = gatewayAvailability == GatewayAvailability.Ready

                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(title = safetySpeedTitle, hint = null)
                    if (gatewayUnavailableReason != null) {
                        Text(
                            text = gatewayUnavailableReason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }

                    // YOLO — bypasses command approvals. On-state is loud.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(yoloModeTitle, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = yoloModeDesc,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (yoloEnabled == true) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        GatewayToggleControl(
                            available = gatewayControlsAvailable,
                            gatewayReady = gatewayReady,
                            value = yoloEnabled,
                            enabled = !isStreaming,
                            label = "agentSheetYolo",
                            onChange = { chatViewModel.setYolo(it) },
                        )
                    }
                    if (yoloEnabled == true) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = approvalsOff,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }

                    // Fast — priority service tier (model-gated server-side).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(fastModeTitle, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = fastModeDesc,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GatewayToggleControl(
                            available = gatewayControlsAvailable,
                            gatewayReady = gatewayReady,
                            value = fastEnabled,
                            enabled = !isStreaming,
                            label = "agentSheetFast",
                            onChange = { chatViewModel.setFast(it) },
                        )
                    }
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
                // Pre-resolve strings for Session section
                val sessionTitle = stringResource(R.string.conn_info_session_title)
                val sessionNameLabel = stringResource(R.string.conn_info_name)
                val messagesLabel = stringResource(R.string.conn_info_messages)
                val tokensLabel = stringResource(R.string.conn_info_tokens)
                val tokensInOut = stringResource(R.string.conn_info_tokens_in_out, appStats.currentSessionTokensIn, appStats.currentSessionTokensOut)
                val avgTtftLabel = stringResource(R.string.conn_info_avg_ttft)

                SectionLabel(title = sessionTitle, hint = null)

                val sessionLabel = currentSession?.let {
                    it.title?.takeIf { t -> t.isNotBlank() }
                        ?: it.sessionId.take(12)
                } ?: "—"
                InfoRow(label = sessionNameLabel, value = sessionLabel)
                InfoRow(label = messagesLabel, value = messages.size.toString())

                // Token counters only — skip internal accumulator fields.
                // Zero values are informative here ("no usage yet this
                // session") so we don't hide them.
                InfoRow(
                    label = tokensLabel,
                    value = tokensInOut,
                )
                if (appStats.avgResponseTimeMs > 0L) {
                    InfoRow(
                        label = avgTtftLabel,
                        value = stringResource(R.string.conn_info_ttft_ms, appStats.avgResponseTimeMs),
                    )
                }
            }

            HorizontalDivider()

            // ---- Connection section (condensed) ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Pre-resolve strings for Connection section
                val connectionTitle = stringResource(R.string.conn_info_connection_title)
                val switchServersHint = stringResource(R.string.conn_info_switch_servers_hint)
                val authLabel = stringResource(R.string.conn_info_auth)
                val apiReachableLabel = stringResource(R.string.conn_info_api_reachable)
                val pairedLabel = stringResource(R.string.conn_info_paired)
                val hermesLabel = stringResource(R.string.conn_info_hermes)

                SectionLabel(
                    title = connectionTitle,
                    hint = if (allConnections.size >= 2) switchServersHint else null,
                )

                // ---- Session-path summary (always-visible, top of section) ----
                // Glanceable "which transport/route is this session on" line +
                // honest capability chips. Replaces the buried raw-enum readout
                // that used to live only inside the routes expander. The
                // resolved transport, route, and capability gating are computed
                // from live state below; the richer technical detail folds into
                // SessionPathDetails (the single expander further down).
                val sessionTransport = sessionPathTransport(
                    connectionViewModel.resolveStreamingEndpoint(streamingEndpoint),
                )
                val routeLabel = activeEndpoint?.displayLabel()
                    ?: com.hermesandroid.relay.data.Connection
                        .extractDefaultLabel(apiServerUrl)
                        .takeIf { it.isNotBlank() }
                val relayConnected = relayConnectionState == ConnectionState.Connected
                val threadsActive = connectionViewModel.proactiveEnabled.collectAsState().value &&
                    connectionViewModel.authState.collectAsState().value is
                        com.hermesandroid.relay.auth.AuthState.Paired
                val sessionCaps = sessionCapabilities(
                    transport = sessionTransport,
                    gatewayAvailability = gatewayAvailability,
                    relayConnected = relayConnected,
                    relayConfigured = relayUrl.isNotBlank(),
                    voiceReady = voiceReady,
                    threadsActive = threadsActive,
                )
                SessionPathSummary(
                    transport = sessionTransport,
                    routeLabel = routeLabel,
                    capabilities = sessionCaps,
                )

                // Multi-connection switcher. Renders inline as a radio list
                // (mirrors the Profile + Personality sections above) when the
                // user has ≥2 paired connections. Replaces the separate top-
                // bar ConnectionChip that used to be the only switch surface
                // — folding it here keeps all agent/connection controls in
                // one place, matching Bailey's ask in the 2026-04-20 audit.
                //
                // Single-connection case shows no radio list (would be a
                // redundant "only one row, already selected" waste of
                // vertical space). The Auth / API reachable / endpoints
                // block below always renders regardless.
                if (allConnections.size >= 2) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        allConnections.forEach { connection ->
                            val isActive = connection.id == activeConnectionId
                            val hostname = com.hermesandroid.relay.data.Connection
                                .extractDefaultLabel(connection.apiServerUrl)
                            val statusLine = when {
                                connection.pairedAt == null -> stringResource(R.string.conn_info_hostname_hermes, hostname)
                                else -> stringResource(R.string.conn_info_hostname_paired, hostname)
                            }
                            ProfileRadioRow(
                                primary = connection.label,
                                secondary = statusLine,
                                selected = isActive,
                                enabled = !isStreaming,
                                onSelect = {
                                    if (!isActive) {
                                        connectionViewModel.switchConnection(connection.id)
                                        toast(switchedToConnectionToast.format(connection.label))
                                    }
                                },
                            )
                        }
                    }
                }

                ChipRow(label = authLabel) { authStateChip(authState) }
                ChipRow(label = apiReachableLabel) {
                    val (label, bg, fg) = if (apiServerReachable) {
                        Triple(
                            stringResource(R.string.conn_info_yes),
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        Triple(
                            stringResource(R.string.conn_info_no),
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
                            text = pairingCodeLabel,
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
                                                pairingCodeLabel,
                                                pairingCode,
                                            )
                                        )
                                    )
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = copyPairingCodeDesc,
                                )
                            }
                        }
                    }
                }

                // Session details — the single progressive-disclosure expander
                // that ABSORBS the old "Show routes" block. Shows the friendly
                // transport + route, relay state, the full honest capability
                // list (✓ / — with a reason), and the technical API/Relay URLs.
                // There is intentionally one expander here, not two.
                SessionPathDetails(
                    transport = sessionTransport,
                    routeLabel = routeLabel,
                    relayConnectionState = relayConnectionState,
                    capabilities = sessionCaps,
                    apiServerUrl = apiServerUrl,
                    relayUrl = relayUrl,
                    streamingEndpoint = streamingEndpoint,
                    gatewayAvailability = gatewayAvailability,
                    serverCapabilities = serverCapabilities,
                )
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
                Text(stringResource(R.string.conn_info_manage_connections))
            }
        }
    }

    if (showProfileManager) {
        ProfileDisplayManagerDialog(
            profiles = agentProfiles,
            presentation = profilePresentation,
            selectedProfileName = selectedProfile?.name,
            onMove = connectionViewModel::moveProfile,
            onHiddenChange = connectionViewModel::setProfileHidden,
            onReset = connectionViewModel::resetProfilePresentation,
            onDismiss = { showProfileManager = false },
        )
    }
}

// --- AgentInfoSheet helpers ----------------------------------------------

@Composable
private fun ProfileDisplayManagerDialog(
    profiles: List<Profile>,
    presentation: ProfilePresentation,
    selectedProfileName: String?,
    onMove: (String?, Int) -> Unit,
    onHiddenChange: (String?, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val orderedKeys = ProfilePresentationPolicy.orderedKeys(profiles, presentation)
    val selectedKey = AgentDisplay.profileSessionKey(selectedProfileName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conn_info_manage_profiles)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.conn_info_manage_profiles_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                orderedKeys.forEachIndexed { index, key ->
                    val isServerDefault = key == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY
                    val profile = profiles.firstOrNull { it.name == key }
                    val label = if (isServerDefault) {
                        stringResource(R.string.conn_info_server_default)
                    } else {
                        profile?.let(AgentDisplay::profileDisplayName)
                            ?: key.replaceFirstChar { it.uppercase() }
                    }
                    val hidden = key in presentation.hidden
                    ProfileDisplayManagerRow(
                        label = label,
                        hidden = hidden,
                        canHide = hidden || selectedKey != key,
                        canMoveUp = index > 0,
                        canMoveDown = index < orderedKeys.lastIndex,
                        onMoveUp = { onMove(profile?.name, -1) },
                        onMoveDown = { onMove(profile?.name, 1) },
                        onHiddenChange = { onHiddenChange(profile?.name, it) },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text(stringResource(R.string.conn_info_reset_profile_display)) }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
        },
    )
}

@Composable
private fun ProfileDisplayManagerRow(
    label: String,
    hidden: Boolean,
    canHide: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onHiddenChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.conn_info_move_profile_up, label))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.conn_info_move_profile_down, label))
        }
        IconButton(onClick = { onHiddenChange(!hidden) }, enabled = canHide) {
            Icon(
                imageVector = if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = stringResource(
                    if (hidden) R.string.conn_info_show_profile else R.string.conn_info_hide_profile,
                    label,
                ),
            )
        }
    }
}

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
 * A space-saving picker section: a tappable header (the [SectionLabel] plus the
 * current value and a chevron) that collapses its option rows by default and
 * expands them on tap — a dropdown for the agent sheet's Profile / Personality
 * / Model lists so the sheet doesn't render every option at once. Selecting an
 * option (inside [content]) updates [currentValue] in the header; callers may
 * collapse on select by toggling their own state if desired, but leaving it
 * open lets the user see the new selection land.
 */
@Composable
private fun CollapsiblePickerSection(
    title: String,
    hint: String?,
    currentValue: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val collapseDesc = stringResource(R.string.conn_info_collapse, title)
    val expandDesc = stringResource(R.string.conn_info_expand, title)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SectionLabel(title = title, hint = hint)
            }
            if (!expanded && currentValue.isNotBlank()) {
                Text(
                    text = currentValue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 150.dp)
                        .padding(end = 8.dp),
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) collapseDesc else expandDesc,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun DisplayAliasSection(
    currentAlias: String?,
    fallbackName: String,
    onSave: (String?) -> Unit,
) {
    var draft by remember { mutableStateOf(currentAlias.orEmpty()) }
    LaunchedEffect(currentAlias) {
        draft = currentAlias.orEmpty()
    }
    val normalizedDraft = AgentDisplay.localDisplayAlias(draft)
    val hasChange = normalizedDraft != currentAlias

    val localDisplayNameTitle = stringResource(R.string.conn_info_local_display_name)
    val phoneLabelHint = stringResource(R.string.conn_info_phone_label)
    val notSetValue = stringResource(R.string.conn_info_not_set)
    val nameLabel = stringResource(R.string.conn_info_name)
    val clearText = stringResource(R.string.conn_info_clear)
    val saveText = stringResource(R.string.conn_info_save)

    CollapsiblePickerSection(
        title = localDisplayNameTitle,
        hint = phoneLabelHint,
        currentValue = currentAlias ?: notSetValue,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(nameLabel) },
            placeholder = { Text(fallbackName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    draft = ""
                    onSave(null)
                },
                enabled = currentAlias != null,
            ) {
                Text(clearText)
            }
            OutlinedButton(
                onClick = { onSave(normalizedDraft) },
                enabled = hasChange,
            ) {
                Text(saveText)
            }
        }
    }
}

/**
 * Radio-style row used by both Profile and Personality sections. Whole row
 * is a tap target (and selectable() for a11y). Disabled when [enabled] is
 * false — look and behaviour both propagate the gate.
 *
 * Layout hierarchy inside the text column:
 *   1. [primary]   — the name, on its own line.
 *   2. [secondary] — a metadata/description line. When [secondaryExpandable]
 *      is set it truncates to [SECONDARY_COLLAPSED_LINES] with an ellipsis and
 *      becomes tap-to-expand, so a long description (e.g. "Sentinel —
 *      Infrastructure / Security / Reliability · gpt-5.5") can never push the
 *      badges off-screen or crunch them.
 *   3. [secondaryTrailing] badges — rendered in a FlowRow on their OWN line
 *      below the description, so WHOLE pills wrap to the next line rather than
 *      the badge text wrapping internally ("141\nskills" / vertical "S O U L").
 *   4. [tertiary]  — an optional caption.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileRadioRow(
    primary: String,
    secondary: String?,
    tertiary: String? = null,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    /**
     * Extra alpha multiplier applied to the whole row on top of the
     * disabled-state dimming. Used by the Profile section to hint that a
     * gateway-off profile is selectable-but-probably-stale (50%). 1f is
     * neutral.
     */
    contentAlpha: Float = 1f,
    /**
     * Optional status dot rendered between the RadioButton and the text
     * column. 6 dp circle; hide by leaving null.
     */
    leadingDotColor: Color? = null,
    /**
     * Optional accessibility content description for the leading status
     * dot. Screen readers announce this string so users don't have to
     * rely on the dot's colour to know the profile's runtime state.
     * Pass e.g. "Gateway running" / "Gateway idle". Null skips the
     * a11y announcement (and renders the dot as a decoration-only).
     */
    leadingDotContentDescription: String? = null,
    /**
     * When true the [secondary] line is treated as a potentially-long
     * description: it truncates to [SECONDARY_COLLAPSED_LINES] with an
     * ellipsis and gains a tap-to-expand affordance (progressive disclosure)
     * so the full text is reachable without crunching the badges. Default
     * false keeps the short single-line caption behaviour every other caller
     * relies on.
     */
    secondaryExpandable: Boolean = false,
    /**
     * Optional trailing chip/badge slot. Rendered in a FlowRow on its own
     * line BELOW the [secondary] description (not inline with it), so whole
     * badges wrap gracefully and never compete with the description for
     * width. Slot-based so each call site composes its own badges.
     */
    secondaryTrailing: (@Composable () -> Unit)? = null,
) {
    val rowAlpha = (if (enabled) 1f else 0.5f) * contentAlpha
    // Local expand state for a long [secondary] description. Only consulted
    // when [secondaryExpandable]; tapping the description toggles it. Scoped
    // to this row's identity so a list re-order doesn't carry the flag across.
    var descriptionExpanded by remember(primary, secondary) { mutableStateOf(false) }
    // Whether the collapsed description is actually being clipped — drives the
    // "More" affordance so a short description that fits doesn't get a pointless
    // expand link. Reported by the Text's onTextLayout below.
    var descriptionOverflows by remember(primary, secondary) { mutableStateOf(false) }
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
        if (leadingDotColor != null) {
            Spacer(modifier = Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(leadingDotColor)
                    .then(
                        if (leadingDotContentDescription != null) {
                            Modifier.semantics {
                                contentDescription = leadingDotContentDescription
                            }
                        } else {
                            Modifier
                        }
                    ),
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = primary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Description line on its own row. When expandable, truncate +
            // make the text a tap-to-toggle target so the full description is
            // reachable without pushing the badges off-screen. The whole-row
            // selectable() still drives selection; this inner clickable only
            // toggles disclosure, so a tap on the description text expands it
            // rather than selecting the row.
            if (secondary != null) {
                val collapsed = secondaryExpandable && !descriptionExpanded
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (collapsed) SECONDARY_COLLAPSED_LINES else Int.MAX_VALUE,
                    overflow = if (collapsed) TextOverflow.Ellipsis else TextOverflow.Clip,
                    // Only flag overflow from the COLLAPSED measure — once
                    // expanded the text fits by definition, so don't clobber
                    // the flag (the "Show less" affordance still wants it true).
                    onTextLayout = { layout ->
                        if (collapsed) descriptionOverflows = layout.hasVisualOverflow
                    },
                    modifier = if (secondaryExpandable && enabled) {
                        Modifier.clickable { descriptionExpanded = !descriptionExpanded }
                    } else {
                        Modifier
                    },
                )
                // "More" / "Show less" affordance — only when the collapsed
                // description is actually clipped (or already expanded), so a
                // short description that fits gets no pointless expand link.
                if (secondaryExpandable && enabled && (descriptionOverflows || descriptionExpanded)) {
                    val showLessText = stringResource(R.string.conn_info_show_less)
                    val moreText = stringResource(R.string.conn_info_more)
                    Text(
                        text = if (descriptionExpanded) showLessText else moreText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            descriptionExpanded = !descriptionExpanded
                        },
                    )
                }
            }
            // Badges get their OWN line in a FlowRow so whole pills wrap to the
            // next line on a narrow row — never crunched, never split internally.
            if (secondaryTrailing != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    secondaryTrailing()
                }
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

/**
 * Single static, non-interactive row shown in place of the profile radio list
 * when the connection is locked to one profile. There is intentionally no
 * onSelect — the only way to change the target or unlock is the dedicated
 * "Profile lock" control in Settings, which always lists every profile.
 */
@Composable
private fun LockedProfileRow(lockedDisplayName: String) {
    val lockedToText = stringResource(R.string.conn_info_locked_to, lockedDisplayName)
    val manageLockHint = stringResource(R.string.conn_info_manage_lock_hint)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = lockedToText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = manageLockHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Lines a collapsed (truncated) [ProfileRadioRow] description shows before its
 *  tap-to-expand affordance reveals the rest. Two keeps the badge FlowRow on
 *  screen even when the description is long. */
private const val SECONDARY_COLLAPSED_LINES = 2

/**
 * Tiny pill rendered inline with a profile row's secondary line. Used for
 * "N skills" and "SOUL" indicators. Kept compact and visually subordinate
 * to the model name — ~labelSmall, rounded to the max, padding trimmed.
 */
@Composable
private fun ProfileMetadataBadge(
    text: String,
    background: Color,
    contentColor: Color,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        // A badge must read as one whole pill. maxLines=1 + softWrap=false keeps
        // "141 skills" / "SOUL" on a single line so a width crunch can never break
        // it into "141\nskills" or vertical "S O U L" — the FlowRow in
        // ProfileRadioRow wraps WHOLE pills to the next line instead.
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** A small spinner + label row used as a BOUNDED "list still loading" cue under
 *  a picker section whose section header is always present. */
@Composable
private fun PickerLoadingRow(text: String) {
    Row(
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Trailing control for a standard upstream gateway toggle (YOLO / Fast) that is
 * NEVER hidden:
 *  - not [available] (gateway unreachable / needs sign-in) → a cleanly disabled
 *    switch; the reason is shown once at the section level;
 *  - [available] but [value] still unknown (null):
 *     - [gatewayReady] → the value re-confirms from `session.info` on the user's
 *       NEXT message (it's reset on a new chat / profile switch), so instead of
 *       an indefinite spinner the placeholder says so honestly;
 *     - still probing (Unknown) → the "Checking…" spinner pose;
 *  - [available] with a confirmed [value] → the live switch.
 */
@Composable
private fun GatewayToggleControl(
    available: Boolean,
    gatewayReady: Boolean,
    value: Boolean?,
    enabled: Boolean,
    label: String,
    onChange: (Boolean) -> Unit,
) {
    if (!available) {
        Switch(checked = value == true, enabled = false, onCheckedChange = {})
        return
    }
    val confirmsOnNextMessage = stringResource(R.string.conn_info_confirms_on_next_message)
    val checkingText = stringResource(R.string.conn_info_checking)
    LoadedFadeIn(
        value = value,
        label = label,
        placeholder = {
            if (gatewayReady) {
                // Socket is up; the value is just unconfirmed for THIS draft.
                // Honest + subtle: tell the user it settles on their next turn
                // rather than spinning forever.
                Text(
                    text = confirmsOnNextMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = checkingText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) { enabledValue ->
        Switch(
            checked = enabledValue,
            enabled = enabled,
            onCheckedChange = onChange,
        )
    }
}

@Composable
private fun AgentSheetHeader(
    profile: Profile?,
    selectedPersonality: String,
    defaultPersonality: String,
    localDisplayAlias: String?,
    serverModelName: String,
    sessionModelName: String? = null,
    modelProviderLabel: String? = null,
    apiServerReachable: Boolean,
    chatMode: ChatMode,
    isCustomized: Boolean,
) {
    val agentName = AgentDisplay.agentName(
        profile = profile,
        selectedPersonality = selectedPersonality,
        defaultPersonality = defaultPersonality,
        connectionLabel = null,
        localDisplayAlias = localDisplayAlias,
    )
    // Session model wins so it pairs with the (session-scoped) provider label;
    // falls back to the profile model, then the server default.
    val modelLabel = AgentDisplay.displayModelName(sessionModelName)
        ?: AgentDisplay.displayModelName(profile?.model)
        ?: AgentDisplay.displayModelName(serverModelName)
    // The global default — surfaced as a quiet caption only when THIS session
    // runs something different (the always-visible global-vs-session split).
    val serverDefaultLabel = AgentDisplay.displayModelName(serverModelName)
    val isConnecting = !apiServerReachable && chatMode != ChatMode.DISCONNECTED
    val connectedText = stringResource(R.string.conn_info_connected)
    val connectingText = stringResource(R.string.conn_info_connecting)
    val disconnectedText = stringResource(R.string.conn_info_disconnected)
    val statusText = when {
        apiServerReachable -> connectedText
        isConnecting -> connectingText
        else -> disconnectedText
    }
    val statusColor = when {
        apiServerReachable -> MaterialTheme.colorScheme.primary
        isConnecting -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .then(
                    if (isCustomized && LocalAgentIconPath.current.isNullOrBlank()) {
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
                AgentAvatarFace(
                    name = agentName,
                    letterStyle = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = agentName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            // Fade the model in when it's CONFIRMED (from /api/config), rather
            // than popping. While still connecting we show a skeleton (honest
            // "loading"); once connected with no model reported we render
            // nothing rather than claim a model we don't have.
            LoadedFadeIn(
                value = modelLabel?.takeIf { it.isNotBlank() },
                label = "agentSheetModel",
                placeholder = {
                    // Never collapse the model line — show a loading pose, not an
                    // empty slot (which reads as "no model").
                    RelaySkeletonLine(width = 104.dp, height = 12.dp)
                },
            ) { label ->
                Text(
                    text = modelProviderLabel?.takeIf { it.isNotBlank() }
                        ?.let { "$label · $it" } ?: label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // Global-vs-session split: when the session runs a different model
            // than the server's global default, name the default here so the
            // scope is obvious at a glance (matches a mid-session switch).
            if (modelLabel != null && serverDefaultLabel != null &&
                !modelLabel.equals(serverDefaultLabel, ignoreCase = true)
            ) {
                val serverDefaultModelText = stringResource(R.string.conn_info_server_default_model, serverDefaultLabel)
                Text(
                    text = serverDefaultModelText,
                    style = MaterialTheme.typography.labelSmall,
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
