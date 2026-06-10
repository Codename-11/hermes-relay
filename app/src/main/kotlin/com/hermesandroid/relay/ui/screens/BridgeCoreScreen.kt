package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.components.RelayChromeIconButton
import com.hermesandroid.relay.ui.components.RelayHeroPanel
import com.hermesandroid.relay.ui.components.RelayModeStrip
import com.hermesandroid.relay.ui.components.RelayNavTile
import com.hermesandroid.relay.ui.components.RelayPrimaryMode
import com.hermesandroid.relay.ui.components.RelaySectionCaption
import com.hermesandroid.relay.ui.components.RelayStatusPill
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayGridTexture
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.RelayUiState
import com.hermesandroid.relay.viewmodel.statusText

/**
 * Google Play Bridge Core surface.
 *
 * The Play build still presents "Hermes Bridge" as the umbrella for relay
 * integrations, but it deliberately excludes AccessibilityService-backed
 * Device Control. The sideload flavor keeps using [BridgeScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeCoreScreen(
    connectionViewModel: ConnectionViewModel,
    onNavigateToConnections: () -> Unit,
    onNavigateToChat: () -> Unit = {},
    onNavigateToManage: () -> Unit = {},
    onNavigateToTerminal: () -> Unit,
    onNavigateToVoiceSettings: () -> Unit,
    onNavigateToNotificationCompanion: () -> Unit,
    onNavigateToMediaSettings: () -> Unit,
    onNavigateToRelaySessions: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
) {
    val relayState by connectionViewModel.relayUiState.collectAsState()
    val relayConnected = relayState == RelayUiState.Connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bridge") },
                actions = {
                    RelayChromeIconButton(
                        icon = Icons.Filled.Code,
                        contentDescription = "Terminal",
                        onClick = onNavigateToTerminal,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    RelayChromeIconButton(
                        icon = Icons.Filled.Tune,
                        contentDescription = "Settings",
                        onClick = onNavigateToSettings,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RelayRefresh.Background.copy(alpha = 0.96f),
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(RelayRefresh.Background)
                .relayGridTexture(alpha = 0.12f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RelayModeStrip(
                selected = RelayPrimaryMode.Bridge,
                onModeSelected = { mode ->
                    when (mode) {
                        RelayPrimaryMode.Chat -> onNavigateToChat()
                        RelayPrimaryMode.Manage -> onNavigateToManage()
                        RelayPrimaryMode.Bridge -> Unit
                    }
                },
                modifier = Modifier.padding(horizontal = 0.dp, vertical = 0.dp),
            )
            RelayHeroPanel(
                title = if (relayConnected) "Phone bridge is paired" else "Bridge Core is waiting",
                subtitle = if (relayConnected) {
                    "Terminal, voice, notification, media, and relay-session controls share this grant."
                } else {
                    "Pair Relay to use Terminal and phone bridge tools. Chat and Manage continue over standard Hermes API."
                },
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RelayStatusPill(
                            text = relayState.statusText("connected").lowercase(),
                            active = relayConnected,
                        )
                        RelayStatusPill(
                            text = "safety on",
                            active = true,
                        )
                    }
                },
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Hermes Bridge Core",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Relay features for your self-hosted Hermes server: chat, " +
                            "voice, terminal, notifications, media, and session grants.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "This build does not include AccessibilityService, " +
                                "screen reading, taps, typing, screenshots, SMS, calls, " +
                                "or unattended phone control.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Relay",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = relayState.statusText("Connected"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Bridge Core uses the same paired relay session as " +
                            "terminal, notification, media, and voice features.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            RelaySectionCaption(
                title = "Bridge Surface",
                meta = "not hidden in settings",
            )
            RelayNavTile(
                icon = Icons.Filled.Link,
                title = "Connections",
                subtitle = "Pair, switch, and verify relay routes",
                onClick = onNavigateToConnections,
            )
            RelayNavTile(
                icon = Icons.Filled.Code,
                title = "Terminal",
                subtitle = "Attach to your Hermes relay terminal",
                onClick = onNavigateToTerminal,
                selected = relayConnected,
            )
            RelayNavTile(
                icon = Icons.Filled.GraphicEq,
                title = "Voice",
                subtitle = "Provider, model, output voice",
                onClick = onNavigateToVoiceSettings,
            )
            RelayNavTile(
                icon = Icons.Filled.Notifications,
                title = "Notifications",
                subtitle = "Shared app notifications",
                onClick = onNavigateToNotificationCompanion,
            )
            RelayNavTile(
                icon = Icons.Filled.Image,
                title = "Media",
                subtitle = "Inbound attachments and cache behavior",
                onClick = onNavigateToMediaSettings,
            )
            RelayNavTile(
                icon = Icons.Filled.Devices,
                title = "Relay sessions",
                subtitle = "Review active grants for this server",
                onClick = onNavigateToRelaySessions,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BridgeCoreRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
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
            modifier = Modifier.size(18.dp),
        )
    }
}
