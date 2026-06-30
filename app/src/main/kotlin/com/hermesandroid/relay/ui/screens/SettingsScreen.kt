package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.hermesandroid.relay.ui.theme.LocalBrand
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Message
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.ui.components.AgentAvatarFace
import com.hermesandroid.relay.ui.components.AgentInfoSheet
import com.hermesandroid.relay.ui.components.LocalAgentIconPath
import com.hermesandroid.relay.ui.components.ProfileInspectorCard
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.RelayUiState

/**
 * Root Settings destination. After the 2026-04-11 split, Settings is a
 * lightweight category list — the heavy lifting for each category lives in
 * a dedicated sub-screen reached by navigation. The top card shows live
 * API / Dashboard / Relay status and opens the agent info sheet for the
 * active connection, profile, and personality.
 *
 * The old mega-file version of this screen (≈2609 lines) carried every
 * setting inline in a single scrolling Column. That was painful to navigate
 * on-device and a maintenance hotspot; see the `ConnectionSettingsScreen`,
 * `ChatSettingsScreen`, `AppearanceSettingsScreen`, etc. files for where
 * the content went. The split follows the `VoiceSettingsScreen` pattern
 * that was already in the repo.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    connectionViewModel: ConnectionViewModel,
    /** Header back affordance — Settings is a pushed destination, not a tab. */
    onBack: (() -> Unit)? = null,
    // Needed by the Active Agent summary card at the top of the screen — it
    // reads the current personality pick so the subtitle can render
    // `connection · model · personality` without re-reading ChatViewModel
    // state from a different place.
    chatViewModel: ChatViewModel,
    // (The `onNavigateToChatWithAgentSheet` param that used to live here
    // was removed as part of the 2026-04-21 pairing-audit fix. Tapping the
    // Active Agent card now opens the consolidated AgentInfoSheet INLINE
    // on this screen — the previous redirect-to-Chat-then-open design
    // confused users, who expected dismissing the sheet to drop them back
    // on Settings, not on a different tab. The local state + AgentInfoSheet
    // block at the bottom of this composable drives the flow directly.)
    // Connections manager — the unified home for everything connection-
    // related. Kept at the top of the category list so switching server
    // connections is one tap from the bottom nav. The former "Active
    // Connection quick-look card" that lived here (and navigated into a
    // second singular detail screen) was removed on 2026-04-21 — the
    // plural Connections screen's active card now owns the full status
    // + manual URL + insecure toggle + manual pairing code surface via
    // expandable sections, so there's nothing left to link to twice.
    onNavigateToConnections: () -> Unit,
    onNavigateToManage: () -> Unit,
    onNavigateToChatSettings: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToBridge: () -> Unit,
    onNavigateToMediaSettings: () -> Unit,
    onNavigateToAppearanceSettings: () -> Unit,
    onNavigateToAnalytics: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToVoiceSettings: () -> Unit,
    onNavigateToNotificationCompanion: () -> Unit,
    onNavigateToProactiveSettings: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    // === PHASE3-safety-rails: bridge safety entry-point ===
    onNavigateToBridgeSafety: () -> Unit,
    // === END PHASE3-safety-rails ===
    onNavigateToPairedDevices: () -> Unit,
    onNavigateToDeveloperSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
    // Profile Inspector — opens the full-screen viewer showing Config,
    // SOUL, Memory, and Skills for the currently-active profile. Called
    // with the profile name so RelayApp can build the nav route. The
    // card itself is disabled (half-alpha, no-op onClick) when no
    // profile is selected yet — it stays visible so the feature is
    // discoverable before a pair-and-pick happens.
    onNavigateToProfileInspector: (profileName: String) -> Unit,
) {
    val context = LocalContext.current
    val isDarkTheme = LocalBrand.current.isDark

    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    // Active Agent card inputs — personality + profile drive the title,
    // ring-accent, and subtitle.
    val selectedProfile by connectionViewModel.selectedProfile.collectAsState()
    val agentProfiles by connectionViewModel.agentProfiles.collectAsState()
    val profileDisplayAlias by connectionViewModel.profileDisplayAlias.collectAsState()
    val selectedPersonality by chatViewModel.selectedPersonality.collectAsState()
    val defaultPersonality by chatViewModel.defaultPersonality.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()
    val relayUiState by connectionViewModel.relayUiState.collectAsState()
    val apiServerReachable by connectionViewModel.apiServerReachable.collectAsState()
    val apiServerHealth by connectionViewModel.apiServerHealth.collectAsState()
    val devOptionsUnlocked by FeatureFlags.devOptionsUnlocked(context)
        .collectAsState(initial = FeatureFlags.isDevBuild)
    val relayPaired = authState is AuthState.Paired
    val dashboardStatus = activeConnection?.dashboardLastStatus
    val dashboardSignInRequired =
        dashboardStatus?.authRequired == true && dashboardStatus.authenticated != true
    // Status pills are exception-only: a pill appears only when the surface
    // needs attention (missing / checking / offline / sign-in). When it's
    // healthy the pill is null so the card + agent summary stay clean.
    val apiPill: SettingsStatusPillModel? = when {
        activeConnection?.apiServerUrl.isNullOrBlank() -> SettingsStatusPillModel(
            label = "API missing",
            tone = SettingsStatusTone.Warning,
        )
        apiServerHealth == ConnectionViewModel.HealthStatus.Probing -> SettingsStatusPillModel(
            label = "API checking",
            tone = SettingsStatusTone.Info,
        )
        apiServerReachable -> null
        apiServerHealth == ConnectionViewModel.HealthStatus.Unreachable -> SettingsStatusPillModel(
            label = "API offline",
            tone = SettingsStatusTone.Warning,
        )
        else -> null
    }
    val dashboardPill: SettingsStatusPillModel? = when {
        activeConnection?.resolvedDashboardUrl.isNullOrBlank() -> SettingsStatusPillModel(
            label = "Dashboard missing",
            tone = SettingsStatusTone.Warning,
        )
        dashboardStatus == null -> null
        !dashboardStatus.reachable -> SettingsStatusPillModel(
            label = "Dashboard offline",
            tone = SettingsStatusTone.Warning,
        )
        dashboardSignInRequired -> SettingsStatusPillModel(
            label = "Dashboard sign in",
            tone = SettingsStatusTone.Info,
        )
        dashboardStatus.authenticated == true -> null
        else -> null
    }
    val relayPill: SettingsStatusPillModel? = when (relayUiState) {
        RelayUiState.Connected -> null
        RelayUiState.Connecting -> SettingsStatusPillModel(
            label = "Relay reconnecting",
            tone = SettingsStatusTone.Info,
        )
        RelayUiState.Stale -> SettingsStatusPillModel(
            label = "Relay stale",
            tone = SettingsStatusTone.Warning,
        )
        RelayUiState.Expired -> SettingsStatusPillModel(
            label = "Pairing expired",
            tone = SettingsStatusTone.Warning,
        )
        RelayUiState.Disconnected -> SettingsStatusPillModel(
            label = if (relayPaired) "Relay offline" else "Requires pairing",
            tone = SettingsStatusTone.Warning,
        )
        RelayUiState.NotConfigured -> null
    }
    // The Power tools below all ride the relay plugin. Rather than stamp an
    // identical badge on every card (noise, not signal), the dependency is
    // surfaced ONCE on the section header as a single plugin-state badge.
    val pluginBadge = when {
        !relayPaired ->
            SettingsStatusPillModel(label = "Plugin required", tone = SettingsStatusTone.Info)
        relayUiState == RelayUiState.Disconnected ->
            SettingsStatusPillModel(label = "Plugin offline", tone = SettingsStatusTone.Warning)
        relayUiState == RelayUiState.Stale ->
            SettingsStatusPillModel(label = "Plugin stale", tone = SettingsStatusTone.Warning)
        relayUiState == RelayUiState.Connecting ->
            SettingsStatusPillModel(label = "Plugin connecting", tone = SettingsStatusTone.Info)
        else ->
            SettingsStatusPillModel(label = "Plugin active", tone = SettingsStatusTone.Good)
    }

    // Kick a WSS reconnect when Settings first composes so the Connections
    // subpage's active-card relay row doesn't flash Disconnected on cold
    // entry. ConnectionsSettingsScreen runs the same `reconnectIfStale()`
    // on its own entry, but firing here too means the first "Settings →
    // Connections" navigation lands on an already-warm reconnect attempt
    // rather than triggering it on arrival.
    LaunchedEffect(Unit) {
        connectionViewModel.reconnectIfStale()
    }

    // AgentInfoSheet visibility — driven by a tap on the Active Agent
    // card at the top of the category list. Previously this tapped
    // route was `onNavigateToChatWithAgentSheet` (navigate to Chat +
    // pass ?openAgentSheet=true), which caused the sheet to open on a
    // different tab and left the user on Chat after dismissing it. Now
    // the sheet renders inline over Settings so closing drops the user
    // back where they started.
    var showAgentSheet by remember { mutableStateOf(false) }
    var showProfileLockDialog by remember { mutableStateOf(false) }
    // What's New / Changelog — opens the full release history as a
    // self-contained full-screen Dialog (no nav route). Always available, not
    // gated on the post-update "seen" state that drives the auto dialog.
    var showChangelog by remember { mutableStateOf(false) }

    // Profile lock state — this card/dialog is the ONE surface that always
    // lists every profile, so it does NOT gate on isProfileLocked.
    val isProfileLocked by connectionViewModel.isProfileLocked.collectAsState()
    val lockedProfileName by connectionViewModel.lockedProfileName.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
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
            // Tapping opens AgentInfoSheet inline so users can change
            // Connection / Profile / Personality without leaving Settings.
            val effectiveProfile = AgentDisplay.effectiveDisplayProfile(
                selectedProfile = selectedProfile,
                profiles = agentProfiles,
            )
            ActiveAgentCard(
                agentName = AgentDisplay.agentName(
                    profile = effectiveProfile,
                    selectedPersonality = selectedPersonality,
                    defaultPersonality = defaultPersonality,
                    connectionLabel = activeConnection?.label,
                    localDisplayAlias = profileDisplayAlias,
                ),
                connectionLabel = activeConnection?.label ?: "No connection",
                model = effectiveProfile?.model ?: "default",
                personalityLabel = AgentDisplay.personalityLabel(
                    selectedPersonality = selectedPersonality,
                    defaultPersonality = defaultPersonality,
                ),
                isCustomized = selectedProfile != null || selectedPersonality != "default",
                statusPills = listOfNotNull(apiPill, dashboardPill, relayPill),
                onClick = { showAgentSheet = true },
                isDarkTheme = isDarkTheme,
            )

            // ── Inspect Agent ──────────────────────────────────────────
            // Opens the full-screen ProfileInspectorScreen for the
            // currently-active profile. When no explicit override is
            // selected (server-configured model), fall back to the
            // `default` profile — the relay always advertises one, and
            // it IS the effective agent. Still falls back to disabled
            // when no profiles have loaded yet (unpaired / pre-auth).
            val inspectorTarget = selectedProfile
                ?: agentProfiles.firstOrNull { it.name == "default" }
                ?: agentProfiles.firstOrNull()
            ProfileInspectorCard(
                activeProfile = inspectorTarget,
                onClick = { profileName -> onNavigateToProfileInspector(profileName) },
                isDarkTheme = isDarkTheme,
            )

            // ── Profile lock ───────────────────────────────────────────
            // Pin the app to ONE profile. When locked, the profile pickers
            // elsewhere collapse to a single locked row; this card's dialog
            // is the only surface that still lists every profile.
            val lockedDisplayName: String? = when {
                !isProfileLocked -> null
                lockedProfileName == null ||
                    AgentDisplay.isServerDefaultAlias(lockedProfileName) ||
                    lockedProfileName == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY ->
                    "Server default"
                else ->
                    agentProfiles
                        .firstOrNull { it.name == lockedProfileName }
                        ?.let { AgentDisplay.profileDisplayName(it) }
                        ?: lockedProfileName!!.replaceFirstChar { it.uppercase() }
            }
            ProfileLockCard(
                lockedDisplayName = lockedDisplayName,
                onClick = { showProfileLockDialog = true },
                isDarkTheme = isDarkTheme,
            )

            // ── Quick Controls ─────────────────────────────────────────
            // The switches flipped most often, pinned to the top-level Settings
            // landing instead of buried in a sub-screen. Persistent connection is
            // connection-level (not chat-specific), so it belongs here beside the
            // agent / profile cards. Extensible — add more frequently-toggled
            // switches in QuickControlsCard.
            QuickControlsCard(
                connectionViewModel = connectionViewModel,
                isDarkTheme = isDarkTheme,
            )

            // (The "Active Connection quick-look card" that used to live
            // here — showing API / Relay / Session status rows with a
            // clickable shortcut into a separate singular-connection
            // detail screen — was removed on 2026-04-21. It was the
            // second "connection status" surface on a screen that already
            // had the Active Agent card above it, and it pointed at a
            // near-identically-named screen (`ConnectionSettings` singular
            // vs `ConnectionsSettings` plural) which users couldn't tell
            // apart. Everything the card did — status rows, pair, manual
            // URL, insecure toggle, manual pairing code — now lives inline
            // on the active card of the Connections subpage, reached via
            // the "Connections" category row below. See ADR on the
            // connection-settings unification.)

            // ── Category list ──────────────────────────────────────────
            // Connections sits ABOVE the Hermes section: it's the foundational
            // layer everything else points at (standard + plugin), not a Hermes
            // feature — and the home for multi-connection.
            SettingsCategoryRow(
                icon = Icons.Filled.Devices,
                title = "Connections",
                subtitle = "API, dashboard, relay pairing, and routes",
                onClick = onNavigateToConnections,
                isDarkTheme = isDarkTheme,
            )

            SettingsSectionHeader("Hermes")

            SettingsCategoryRow(
                icon = Icons.Filled.Link,
                title = "Hermes management",
                subtitle = "Dashboard features: skills, cron, MCP, profiles, models",
                badge = dashboardPill,
                onClick = onNavigateToManage,
                isDarkTheme = isDarkTheme,
            )

            SettingsCategoryRow(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "Chat",
                subtitle = "API chat behavior, endpoints, tool display, message length",
                badge = apiPill,
                onClick = onNavigateToChatSettings,
                isDarkTheme = isDarkTheme,
            )

            SettingsCategoryRow(
                icon = Icons.Filled.GraphicEq,
                title = "Voice mode",
                subtitle = "Dashboard voice, realtime relay options, providers",
                onClick = onNavigateToVoiceSettings,
                isDarkTheme = isDarkTheme,
            )

            SettingsCategoryRow(
                icon = Icons.AutoMirrored.Filled.Message,
                title = "Threads",
                subtitle = "Let the agent start conversations with you (off by default)",
                onClick = onNavigateToProactiveSettings,
                isDarkTheme = isDarkTheme,
            )

            SettingsSectionHeader("Power tools", trailing = pluginBadge)

            SettingsCategoryRow(
                icon = Icons.Filled.Code,
                title = "Terminal",
                subtitle = "Server shell access through a paired relay session",                onClick = onNavigateToTerminal,
                isDarkTheme = isDarkTheme,
            )

            SettingsCategoryRow(
                icon = Icons.Filled.PhoneAndroid,
                title = if (BuildFlavor.isSideload) "Bridge" else "Bridge Core",
                subtitle = if (BuildFlavor.isSideload) {
                    "Relay-granted phone bridge controls"
                } else {
                    "Relay features without sideload device control"
                },                onClick = onNavigateToBridge,
                isDarkTheme = isDarkTheme,
            )

            SettingsCategoryRow(
                icon = Icons.Filled.Devices,
                title = "Relay sessions",
                subtitle = "Phones paired with this server, revoke, extend",                onClick = onNavigateToPairedDevices,
                isDarkTheme = isDarkTheme,
            )

            // === PHASE3-notif-listener-followup: notification companion entry-point ===
            SettingsCategoryRow(
                icon = Icons.Filled.Notifications,
                title = "Notification companion",
                subtitle = "Shared phone notifications for paired relay tools",                onClick = onNavigateToNotificationCompanion,
                isDarkTheme = isDarkTheme,
            )
            // === END PHASE3-notif-listener-followup ===

            SettingsCategoryRow(
                icon = Icons.Filled.Image,
                title = "Media",
                subtitle = "Relay inbound attachments, auto-fetch, cache cap",                onClick = onNavigateToMediaSettings,
                isDarkTheme = isDarkTheme,
            )

            if (BuildFlavor.isSideload) {
                // === PHASE3-safety-rails: bridge safety entry-point ===
                SettingsCategoryRow(
                    icon = Icons.Filled.Security,
                    title = "Bridge safety",
                    subtitle = "Blocklist, destructive-verb confirmation, auto-disable",
                    badge = SettingsStatusPillModel(
                        label = "Sideload",
                        tone = SettingsStatusTone.Info,
                    ),
                    onClick = onNavigateToBridgeSafety,
                    isDarkTheme = isDarkTheme,
                )
                // === END PHASE3-safety-rails ===
            }

            SettingsSectionHeader("App")

            SettingsCategoryRow(
                icon = Icons.Filled.Security,
                title = "Permissions",
                subtitle = "Android grants, optional features, and sideload capability status",
                onClick = onNavigateToPermissions,
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
                icon = Icons.Filled.Analytics,
                title = "Analytics",
                subtitle = "Usage stats, TTFT, tokens, health",
                onClick = onNavigateToAnalytics,
                isDarkTheme = isDarkTheme,
            )

            SettingsCategoryRow(
                icon = Icons.Filled.Info,
                title = "Diagnostics",
                subtitle = "Status checks, plus recent API, relay, session, and voice activity",
                onClick = onNavigateToDiagnostics,
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
                icon = Icons.Filled.NewReleases,
                title = "What's New",
                subtitle = "Release notes and full changelog",
                onClick = { showChangelog = true },
                isDarkTheme = isDarkTheme,
            )

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

    // Agent info sheet — same consolidated surface ChatScreen uses from
    // its TopAppBar tap target, hoisted here so tapping the Active Agent
    // card on Settings opens it in-place. ModalBottomSheet is a popup, so
    // placement as a sibling to Scaffold is cosmetic — visual stacking is
    // handled by the underlying window manager, not the Compose tree.
    if (showAgentSheet) {
        AgentInfoSheet(
            connectionViewModel = connectionViewModel,
            chatViewModel = chatViewModel,
            onDismiss = { showAgentSheet = false },
            onNavigateToConnections = onNavigateToConnections,
            onNavigateToProfileInspector = onNavigateToProfileInspector,
        )
    }

    if (showProfileLockDialog) {
        ProfileLockDialog(
            profiles = agentProfiles,
            isLocked = isProfileLocked,
            lockedProfileName = lockedProfileName,
            onLock = { profile -> connectionViewModel.lockProfile(profile) },
            onUnlock = { connectionViewModel.unlockProfile() },
            onDismiss = { showProfileLockDialog = false },
        )
    }

    // Full-screen changelog. Hosted as a self-contained Dialog (no nav route)
    // so it stacks over Settings and dismisses back here — mirroring the
    // showAgentSheet inline-surface pattern above. (Diagnostics moved to its
    // own nav route — see Screen.Diagnostics.)
    if (showChangelog) {
        Dialog(
            onDismissRequest = { showChangelog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                ChangelogScreen(onClose = { showChangelog = false })
            }
        }
    }
}

/**
 * Compact summary card of the currently active agent (Connection + Profile
 * + Personality) rendered at the very top of SettingsScreen. Tapping opens
 * AgentInfoSheet inline — that sheet is the canonical place to actually
 * change any of these three dimensions.
 *
 * Visual parity with the ChatScreen TopAppBar title block: 32dp avatar with
 * an optional 1.5dp primary-color accent ring when the user has overridden
 * either the profile or the personality; single-line subtitle joining the
 * three tokens with a middle-dot separator.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveAgentCard(
    agentName: String,
    connectionLabel: String,
    model: String,
    personalityLabel: String,
    isCustomized: Boolean,
    onClick: () -> Unit,
    isDarkTheme: Boolean,
    statusPills: List<SettingsStatusPillModel>,
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
                            if (isCustomized && LocalAgentIconPath.current.isNullOrBlank()) {
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
                        AgentAvatarFace(
                            name = agentName,
                            letterStyle = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    statusPills.forEach { pill ->
                        SettingsStatusPill(pill)
                    }
                }
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
 * Entry card for the per-connection profile lock. Subtitle reflects the live
 * lock state: the locked profile's display name when pinned, or the generic
 * "Pin the app to one agent profile" prompt when unlocked. Tapping opens
 * [ProfileLockDialog].
 */
@Composable
private fun ProfileLockCard(
    lockedDisplayName: String?,
    onClick: () -> Unit,
    isDarkTheme: Boolean,
) {
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
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = if (lockedDisplayName != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Profile lock",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = lockedDisplayName?.let { "Locked to $it" }
                        ?: "Pin the app to one agent profile",
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
 * Quick Controls card on the top-level Settings landing — the switches the user
 * flips most often (Persistent connection, turn-complete alerts), kept out of
 * the per-feature sub-screens so they're one tap from the Settings root. Wired
 * straight to the same ConnectionViewModel flows the sub-screens use.
 */
@Composable
private fun QuickControlsCard(
    connectionViewModel: ConnectionViewModel,
    isDarkTheme: Boolean,
) {
    val gatewayKeepAlive by connectionViewModel.gatewayKeepAlive.collectAsState()
    val notifyTurnComplete by connectionViewModel.notifyTurnComplete.collectAsState()
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
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Quick Controls",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            // Persistent connection — connection-level keep-alive: holds the app
            // process up via a notification so the gateway chat socket (and, for
            // relay-paired setups, device control + notification mirroring) stays
            // reachable in the background. Off by default; uses more battery.
            QuickControlToggle(
                title = "Persistent connection",
                subtitle = if (gatewayKeepAlive) {
                    "Keeping your connection to Hermes open in the background"
                } else {
                    "Connect on demand only · saves battery"
                },
                checked = gatewayKeepAlive,
                onCheckedChange = { connectionViewModel.setGatewayKeepAlive(it) },
            )
            HorizontalDivider()
            QuickControlToggle(
                title = "Turn-complete alerts",
                subtitle = if (notifyTurnComplete) {
                    "Notify when a reply finishes while the app is in the background"
                } else {
                    "No alert when a backgrounded reply finishes"
                },
                checked = notifyTurnComplete,
                onCheckedChange = { connectionViewModel.setNotifyTurnComplete(it) },
            )
        }
    }
}

@Composable
private fun QuickControlToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * The one surface that ALWAYS lists every profile (it never gates on the lock
 * state — it's how the user picks the target or unlocks). A master "Lock to a
 * profile" toggle reveals a radio list of "Server default" + every advertised
 * profile. When the stored lock target isn't present in the list, a banner
 * names the missing profile with an inline Unlock affordance.
 */
@Composable
private fun ProfileLockDialog(
    profiles: List<Profile>,
    isLocked: Boolean,
    lockedProfileName: String?,
    onLock: (Profile?) -> Unit,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Selectable rows: a synthetic "Server default" sentinel + the advertised
    // profiles, minus the synthetic "default" alias (folded into Server default).
    val selectableProfiles = profiles.filterNot { AgentDisplay.isServerDefaultAlias(it.name) }

    // Is the stored lock target Server default (sentinel / "default" alias / null)?
    val lockedIsServerDefault = lockedProfileName == null ||
        AgentDisplay.isServerDefaultAlias(lockedProfileName) ||
        lockedProfileName == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY
    val lockedProfile = if (lockedIsServerDefault) {
        null
    } else {
        selectableProfiles.firstOrNull { it.name == lockedProfileName }
    }
    // Locked to a named profile the server no longer advertises.
    val lockedProfileMissing = isLocked && !lockedIsServerDefault && lockedProfile == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profile lock") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Pin the app to one agent profile. While locked, the " +
                        "profile pickers elsewhere collapse to a single locked row.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (lockedProfileMissing) {
                    Surface(
                        color = RelayRefresh.Amber.copy(alpha = 0.15f),
                        contentColor = RelayRefresh.Amber,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, RelayRefresh.Amber.copy(alpha = 0.5f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Locked profile '" +
                                    (lockedProfileName ?: "") +
                                    "' not found on this server.",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            TextButton(
                                onClick = onUnlock,
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text("Unlock")
                            }
                        }
                    }
                }

                // Master toggle. Off = unlocked; flipping on locks to the
                // current effective target (Server default by default, or the
                // already-stored target when it still resolves).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Lock to a profile",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = isLocked,
                        onCheckedChange = { checked ->
                            if (checked) {
                                // Lock to the existing target if it still
                                // resolves, else Server default.
                                onLock(lockedProfile)
                            } else {
                                onUnlock()
                            }
                        },
                    )
                }

                if (isLocked) {
                    HorizontalDivider()
                    // Server default option.
                    ProfileLockOptionRow(
                        label = "Server default",
                        secondary = "Use this connection's default profile",
                        selected = lockedIsServerDefault,
                        onSelect = { onLock(null) },
                    )
                    selectableProfiles.forEach { profile ->
                        ProfileLockOptionRow(
                            label = AgentDisplay.profileDisplayName(profile)
                                ?: profile.name.replaceFirstChar { it.uppercase() },
                            secondary = profile.model.takeIf { it.isNotBlank() },
                            selected = !lockedIsServerDefault &&
                                lockedProfileName == profile.name,
                            onSelect = { onLock(profile) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun ProfileLockOptionRow(
    label: String,
    secondary: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class SettingsStatusPillModel(
    val label: String,
    val tone: SettingsStatusTone = SettingsStatusTone.Neutral,
)

private enum class SettingsStatusTone {
    Neutral,
    Good,
    Info,
    Warning,
}

@Composable
private fun SettingsStatusPill(pill: SettingsStatusPillModel) {
    // Soft "chip" treatment that matches the translucent, bordered language
    // of the chat/manage/bridge mode strip (relaySelectedPanel) instead of a
    // solid full-strength fill — a tinted wash + hairline accent border + cream
    // label reads cleaner and stays in-theme across tones.
    val hue = when (pill.tone) {
        SettingsStatusTone.Good -> RelayRefresh.Electric
        SettingsStatusTone.Info -> RelayRefresh.Purple
        SettingsStatusTone.Warning -> RelayRefresh.Amber
        SettingsStatusTone.Neutral -> RelayRefresh.Muted
    }
    val contentColor = when (pill.tone) {
        SettingsStatusTone.Good, SettingsStatusTone.Info -> RelayRefresh.Paper
        SettingsStatusTone.Warning -> RelayRefresh.Amber
        SettingsStatusTone.Neutral -> RelayRefresh.Muted
    }
    Surface(
        color = hue.copy(alpha = 0.18f),
        contentColor = contentColor,
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, hue.copy(alpha = 0.55f)),
    ) {
        Text(
            text = pill.label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SettingsSectionHeader(
    label: String,
    trailing: SettingsStatusPillModel? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            SettingsStatusPill(trailing)
        }
    }
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
    badge: SettingsStatusPillModel? = null,
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    SettingsStatusPill(badge)
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
