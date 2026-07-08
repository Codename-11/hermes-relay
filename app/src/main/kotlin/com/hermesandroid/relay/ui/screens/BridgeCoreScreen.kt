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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.components.RelayChromeIconButton
import com.hermesandroid.relay.ui.components.RelayHeroPanel
import com.hermesandroid.relay.ui.components.RelayNavTile
import com.hermesandroid.relay.ui.components.RelayReturnStrip
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
    returnTitle: String? = null,
    returnSubtitle: String = "",
    returnLabel: String = "",
    onReturn: (() -> Unit)? = null,
) {
    val relayState by connectionViewModel.relayUiState.collectAsState()
    val relayConnected = relayState == RelayUiState.Connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bridge_core_title)) },
                navigationIcon = {
                    RelayChromeIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.bridge_core_back_to_chat),
                        onClick = onNavigateToChat,
                    )
                },
                actions = {
                    RelayChromeIconButton(
                        icon = Icons.Filled.Code,
                        contentDescription = stringResource(R.string.bridge_core_terminal),
                        onClick = onNavigateToTerminal,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    RelayChromeIconButton(
                        icon = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.bridge_core_settings),
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
            if (returnTitle != null && onReturn != null) {
                RelayReturnStrip(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    title = returnTitle,
                    subtitle = returnSubtitle,
                    label = returnLabel.ifBlank { stringResource(R.string.bridge_core_back) },
                    onClick = onReturn,
                )
            }
            RelayHeroPanel(
                title = if (relayConnected) stringResource(R.string.bridge_core_paired_title) else stringResource(R.string.bridge_core_waiting_title),
                subtitle = if (relayConnected) {
                    stringResource(R.string.bridge_core_paired_subtitle)
                } else {
                    stringResource(R.string.bridge_core_waiting_subtitle)
                },
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RelayStatusPill(
                            text = relayState.statusText(stringResource(R.string.bridge_core_connected_label)).lowercase(),
                            active = relayConnected,
                        )
                        RelayStatusPill(
                            text = stringResource(R.string.bridge_core_safety_on),
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
                        text = stringResource(R.string.bridge_core_hermes_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.bridge_core_hermes_body),
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
                            text = stringResource(R.string.bridge_core_no_access_body),
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
                        text = stringResource(R.string.bridge_core_relay_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = relayState.statusText(stringResource(R.string.bridge_core_connected_label2)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.bridge_core_relay_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            RelaySectionCaption(
                title = stringResource(R.string.bridge_core_surface_title),
                meta = stringResource(R.string.bridge_core_surface_meta),
            )
            RelayNavTile(
                icon = Icons.Filled.Link,
                title = stringResource(R.string.bridge_core_connections),
                subtitle = stringResource(R.string.bridge_core_connections_sub),
                onClick = onNavigateToConnections,
            )
            RelayNavTile(
                icon = Icons.Filled.Code,
                title = stringResource(R.string.bridge_core_terminal_tile),
                subtitle = stringResource(R.string.bridge_core_terminal_sub),
                onClick = onNavigateToTerminal,
                selected = relayConnected,
            )
            RelayNavTile(
                icon = Icons.Filled.GraphicEq,
                title = stringResource(R.string.bridge_core_voice),
                subtitle = stringResource(R.string.bridge_core_voice_sub),
                onClick = onNavigateToVoiceSettings,
            )
            RelayNavTile(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.bridge_core_notifications),
                subtitle = stringResource(R.string.bridge_core_notifications_sub),
                onClick = onNavigateToNotificationCompanion,
            )
            RelayNavTile(
                icon = Icons.Filled.Image,
                title = stringResource(R.string.bridge_core_media),
                subtitle = stringResource(R.string.bridge_core_media_sub),
                onClick = onNavigateToMediaSettings,
            )
            RelayNavTile(
                icon = Icons.Filled.Devices,
                title = stringResource(R.string.bridge_core_relay_sessions),
                subtitle = stringResource(R.string.bridge_core_relay_sessions_sub),
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
