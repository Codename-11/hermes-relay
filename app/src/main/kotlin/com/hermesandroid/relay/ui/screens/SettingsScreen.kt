package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Info
// === PHASE3-safety-rails: bridge safety entry-point ===
import androidx.compose.material.icons.filled.Security
// === END PHASE3-safety-rails ===
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.ui.components.ConnectionStatusRow
import com.hermesandroid.relay.viewmodel.asBadgeState
import com.hermesandroid.relay.viewmodel.statusText
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

/**
 * Root Settings destination. After the 2026-04-11 split, Settings is a
 * lightweight category list — the heavy lifting for each category lives in
 * a dedicated sub-screen reached by navigation. The top card shows live
 * API / Relay / Session status and taps through to the Connection
 * sub-screen for pairing and manual configuration.
 *
 * The old mega-file version of this screen (≈2609 lines) carried every
 * setting inline in a single scrolling Column. That was painful to navigate
 * on-device and a maintenance hotspot; see the `ConnectionSettingsScreen`,
 * `ChatSettingsScreen`, `AppearanceSettingsScreen`, etc. files for where
 * the content went. The split follows the `VoiceSettingsScreen` pattern
 * that was already in the repo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connectionViewModel: ConnectionViewModel,
    // Needed by the Active Agent summary card at the top of the screen — it
    // reads the current personality pick so the subtitle can render
    // `connection · model · personality` without re-reading ChatViewModel
    // state from a different place.
    chatViewModel: ChatViewModel,
    // Tapping the Active Agent card jumps to Chat AND auto-opens the
    // consolidated AgentInfoSheet. Plumbing lives in RelayApp via a
    // parameterised Chat route (openAgentSheet=true).
    onNavigateToChatWithAgentSheet: () -> Unit,
    // Multi-connection: entry point for the Connections manager. Kept at
    // the top of the category list so switching server connections is one
    // tap from the bottom nav, not buried behind "Connection → Paired
    // devices".
    onNavigateToConnections: () -> Unit,
    onNavigateToConnectionSettings: () -> Unit,
    onNavigateToChatSettings: () -> Unit,
    onNavigateToMediaSettings: () -> Unit,
    onNavigateToAppearanceSettings: () -> Unit,
    onNavigateToAnalytics: () -> Unit,
    onNavigateToVoiceSettings: () -> Unit,
    onNavigateToNotificationCompanion: () -> Unit,
    // === PHASE3-safety-rails: bridge safety entry-point ===
    onNavigateToBridgeSafety: () -> Unit,
    // === END PHASE3-safety-rails ===
    onNavigateToPairedDevices: () -> Unit,
    onNavigateToDeveloperSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()

    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val apiHealth by connectionViewModel.apiServerHealth.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()
    val apiUrl by connectionViewModel.apiServerUrl.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val relayUiState by connectionViewModel.relayUiState.collectAsState()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    // Active Agent card inputs — personality + profile drive the title,
    // ring-accent, and subtitle. Kept next to the other top-level
    // collectAsState calls so the data-gather stays in one block.
    val selectedProfile by connectionViewModel.selectedProfile.collectAsState()
    val selectedPersonality by chatViewModel.selectedPersonality.collectAsState()
    val defaultPersonality by chatViewModel.defaultPersonality.collectAsState()
    val devOptionsUnlocked by FeatureFlags.devOptionsUnlocked(context)
        .collectAsState(initial = FeatureFlags.isDevBuild)
    val relayFeatureEnabled by FeatureFlags.relayEnabled(context)
        .collectAsState(initial = FeatureFlags.isDevBuild)

    // Kick a WSS reconnect when Settings first composes so the Active
    // Connection card doesn't flash red/Disconnected on cold entry. Without
    // this, the relay row rendered with whatever state ConnectionManager
    // held after the last resume — which on fresh launch is often
    // Disconnected since RelayApp's ON_RESUME fires before the tab even
    // exists on screen. Matches ConnectionSettingsScreen's behavior so the
    // two paths converge.
    LaunchedEffect(Unit) {
        connectionViewModel.reconnectIfStale()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Active Agent summary ───────────────────────────────────
            // Mirrors the ChatScreen TopAppBar title block (avatar + name
            // + one-line `connection · model · personality` subtitle).
            // Tapping jumps to Chat AND auto-opens AgentInfoSheet so
            // users can change Connection / Profile / Personality without
            // having to navigate to Chat first and then hunt for the
            // agent-name header.
            ActiveAgentCard(
                agentName = agentDisplayName(
                    selectedPersonality = selectedPersonality,
                    defaultPersonality = defaultPersonality,
                ),
                connectionLabel = activeConnection?.label ?: "No connection",
                model = selectedProfile?.model ?: "default",
                personalityLabel = personalityDisplayLabel(
                    selectedPersonality = selectedPersonality,
                    defaultPersonality = defaultPersonality,
                ),
                isCustomized = selectedProfile != null || selectedPersonality != "default",
                onClick = onNavigateToChatWithAgentSheet,
                isDarkTheme = isDarkTheme,
            )

            // ── Connection quick-look card ─────────────────────────────
            // Live status summary for API + relay + session, tappable as a
            // shortcut into the Connection sub-screen where the full
            // pairing / manual configuration UX lives.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    )
                    .clickable(onClick = onNavigateToConnectionSettings),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Active Connection",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            // Connection label lands right under the title so
                            // users with multiple saved connections can see at
                            // a glance which one the status rows describe. Fall
                            // back to "No connection" only when the store has
                            // no active entry — normal startup seeds one.
                            Text(
                                text = activeConnection?.label ?: "No connection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ConnectionStatusRow(
                        label = "API",
                        isConnected = apiReachable,
                        isProbing = apiHealth == ConnectionViewModel.HealthStatus.Probing,
                        statusText = when {
                            apiUrl.isBlank() -> "Not configured"
                            apiHealth == ConnectionViewModel.HealthStatus.Probing -> "Checking…"
                            apiReachable -> apiUrl
                            else -> "Unreachable"
                        }
                    )

                    if (relayFeatureEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        // All state-resolution rules live in ConnectionViewModel's
                        // relayUiState flow — this row just maps that resolved
                        // state onto the row's badge + text. Settings shows the
                        // full relay URL in the Connected case; other screens
                        // show "Connected" via a different label.
                        ConnectionStatusRow(
                            label = "Relay",
                            state = relayUiState.asBadgeState(),
                            statusText = relayUiState.statusText(connectedLabel = relayUrl),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        ConnectionStatusRow(
                            label = "Session",
                            isConnected = authState is AuthState.Paired,
                            isConnecting = authState is AuthState.Pairing,
                            statusText = when (authState) {
                                is AuthState.Paired -> "Paired"
                                is AuthState.Pairing -> "Pairing…"
                                is AuthState.Failed -> "Failed"
                                is AuthState.Unpaired -> "Not paired"
                            }
                        )
                    }
                }
            }

            // ── Category list ──────────────────────────────────────────
            // Multi-connection: Connections sits at the very top of the
            // list so users who just want to switch server connections
            // don't have to hunt for it. `Icons.Filled.Devices` is already
            // imported for the "Paired devices" row below — reusing it
            // here is visually fine since both rows cover server / device
            // relationships.
            SettingsCategoryRow(
                icon = Icons.Filled.Devices,
                title = "Connections",
                subtitle = "Switch or manage server connections",
                onClick = onNavigateToConnections,
                isDarkTheme = isDarkTheme,
            )

            SettingsCategoryRow(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "Chat",
                subtitle = "Reasoning, tool display, endpoints, message length",
                onClick = onNavigateToChatSettings,
                isDarkTheme = isDarkTheme,
            )

            SettingsCategoryRow(
                icon = Icons.Filled.GraphicEq,
                title = "Voice mode",
                subtitle = "Interaction mode, silence threshold, providers",
                onClick = onNavigateToVoiceSettings,
                isDarkTheme = isDarkTheme,
            )

            // === PHASE3-notif-listener-followup: notification companion entry-point ===
            SettingsCategoryRow(
                icon = Icons.Filled.Notifications,
                title = "Notification companion",
                subtitle = "Let your assistant triage notifications you've shared",
                onClick = onNavigateToNotificationCompanion,
                isDarkTheme = isDarkTheme,
            )
            // === END PHASE3-notif-listener-followup ===

            // === PHASE3-safety-rails: bridge safety entry-point ===
            SettingsCategoryRow(
                icon = Icons.Filled.Security,
                title = "Bridge safety",
                subtitle = "Blocklist, destructive-verb confirmation, auto-disable",
                onClick = onNavigateToBridgeSafety,
                isDarkTheme = isDarkTheme,
            )
            // === END PHASE3-safety-rails ===

            SettingsCategoryRow(
                icon = Icons.Filled.Image,
                title = "Media",
                subtitle = "Inbound attachment size, auto-fetch, cache cap",
                onClick = onNavigateToMediaSettings,
                isDarkTheme = isDarkTheme,
            )

            SettingsCategoryRow(
                icon = Icons.Filled.Palette,
                title = "Appearance",
                subtitle = "Theme, font size, animations",
                onClick = onNavigateToAppearanceSettings,
                isDarkTheme = isDarkTheme,
            )

            SettingsCategoryRow(
                icon = Icons.Filled.Devices,
                title = "Paired devices",
                subtitle = "Active sessions, revoke, extend",
                onClick = onNavigateToPairedDevices,
                isDarkTheme = isDarkTheme,
            )

            SettingsCategoryRow(
                icon = Icons.Filled.Analytics,
                title = "Analytics",
                subtitle = "Stats for nerds — TTFT, tokens, health",
                onClick = onNavigateToAnalytics,
                isDarkTheme = isDarkTheme,
            )

            if (devOptionsUnlocked) {
                SettingsCategoryRow(
                    icon = Icons.Filled.Code,
                    title = "Developer options",
                    subtitle = "Feature flags, data management, experimental",
                    onClick = onNavigateToDeveloperSettings,
                    isDarkTheme = isDarkTheme,
                )
            }

            SettingsCategoryRow(
                icon = Icons.Filled.Info,
                title = "About",
                subtitle = "Version, credits, docs",
                onClick = onNavigateToAbout,
                isDarkTheme = isDarkTheme,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Compact summary card of the currently active agent (Connection + Profile
 * + Personality) rendered at the very top of SettingsScreen. Tapping
 * navigates to the Chat tab with the AgentInfoSheet pre-opened — that sheet
 * is the canonical place to actually change any of these three dimensions.
 *
 * Visual parity with the ChatScreen TopAppBar title block: 32dp avatar with
 * an optional 1.5dp primary-color accent ring when the user has overridden
 * either the profile or the personality; single-line subtitle joining the
 * three tokens with a middle-dot separator.
 */
@Composable
private fun ActiveAgentCard(
    agentName: String,
    connectionLabel: String,
    model: String,
    personalityLabel: String,
    isCustomized: Boolean,
    onClick: () -> Unit,
    isDarkTheme: Boolean,
) {
    val subtitle = "$connectionLabel \u00B7 $model \u00B7 $personalityLabel"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBorder(
                shape = RoundedCornerShape(12.dp),
                isDarkTheme = isDarkTheme,
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar — small 32dp variant of the 40dp ChatScreen top-bar
            // avatar. Ring width shrinks to 1.5dp so the overall footprint
            // stays at 32dp without the inner Surface collapsing.
            val ringWidth = if (isCustomized) 1.5.dp else 0.dp
            val innerSize = 32.dp - (ringWidth * 2)
            Box(modifier = Modifier.size(32.dp)) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .then(
                            if (isCustomized) {
                                Modifier.border(
                                    width = ringWidth,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                )
                            } else Modifier
                        )
                        .padding(ringWidth),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.size(innerSize),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = if (agentName.isNotBlank()) {
                                    agentName.first().uppercase()
                                } else "H",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agentName.ifBlank { "Hermes" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Resolve the agent display name the same way ChatScreen's top bar does:
 * when the user has not overridden the personality ("default") and the
 * server has advertised a default personality name, use that; otherwise use
 * whatever is currently selected. The result is title-cased.
 */
private fun agentDisplayName(
    selectedPersonality: String,
    defaultPersonality: String,
): String {
    val name = if (selectedPersonality == "default" && defaultPersonality.isNotBlank()) {
        defaultPersonality
    } else {
        selectedPersonality
    }
    return name.replaceFirstChar { it.uppercase() }
}

/**
 * Title-cased personality label for the subtitle token. Falls back to the
 * server default (when the user hasn't picked one) and finally to a literal
 * "Default" so the subtitle never renders with a blank middle token.
 */
private fun personalityDisplayLabel(
    selectedPersonality: String,
    defaultPersonality: String,
): String = when {
    selectedPersonality != "default" ->
        selectedPersonality.replaceFirstChar { it.uppercase() }
    defaultPersonality.isNotBlank() ->
        defaultPersonality.replaceFirstChar { it.uppercase() }
    else -> "Default"
}

/**
 * One row in the root Settings category list. Matches the visual style of
 * the existing Voice navigation row that was previously inline in the
 * mega-SettingsScreen.
 */
@Composable
private fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isDarkTheme: Boolean,
) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
