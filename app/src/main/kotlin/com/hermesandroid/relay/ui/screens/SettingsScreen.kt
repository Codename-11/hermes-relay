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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.network.ConnectionState
import com.hermesandroid.relay.ui.components.ConnectionStatusRow
import com.hermesandroid.relay.ui.theme.gradientBorder
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
    onNavigateToConnectionSettings: () -> Unit,
    onNavigateToChatSettings: () -> Unit,
    onNavigateToMediaSettings: () -> Unit,
    onNavigateToAppearanceSettings: () -> Unit,
    onNavigateToAnalytics: () -> Unit,
    onNavigateToVoiceSettings: () -> Unit,
    onNavigateToNotificationCompanion: () -> Unit,
    onNavigateToPairedDevices: () -> Unit,
    onNavigateToDeveloperSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()

    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()
    val apiUrl by connectionViewModel.apiServerUrl.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val devOptionsUnlocked by FeatureFlags.devOptionsUnlocked(context)
        .collectAsState(initial = FeatureFlags.isDevBuild)
    val relayFeatureEnabled by FeatureFlags.relayEnabled(context)
        .collectAsState(initial = FeatureFlags.isDevBuild)

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
                        Text(
                            text = "Connection",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ConnectionStatusRow(
                        label = "API",
                        isConnected = apiReachable,
                        statusText = apiUrl.ifBlank { "Not configured" }
                    )

                    if (relayFeatureEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        ConnectionStatusRow(
                            label = "Relay",
                            isConnected = relayConnectionState == ConnectionState.Connected,
                            isConnecting = relayConnectionState == ConnectionState.Connecting ||
                                relayConnectionState == ConnectionState.Reconnecting,
                            statusText = relayUrl.ifBlank { "Not configured" }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        ConnectionStatusRow(
                            label = "Session",
                            isConnected = authState is AuthState.Paired,
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

            // === PHASE3-ε-followup: notification companion entry-point ===
            SettingsCategoryRow(
                icon = Icons.Filled.Notifications,
                title = "Notification companion",
                subtitle = "Let your assistant triage notifications you've shared",
                onClick = onNavigateToNotificationCompanion,
                isDarkTheme = isDarkTheme,
            )
            // === END PHASE3-ε-followup ===

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
