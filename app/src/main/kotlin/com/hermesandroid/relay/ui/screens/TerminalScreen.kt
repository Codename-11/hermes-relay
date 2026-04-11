package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.network.ConnectionState
import com.hermesandroid.relay.ui.components.ConnectionStatusBadge
import com.hermesandroid.relay.ui.components.ExtraKeysToolbar
import com.hermesandroid.relay.ui.components.TerminalWebView
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.TerminalViewModel

// xterm.js theme background — kept in one place so the Compose shell and the
// WebView content don't flash against each other.
private val TerminalBackground = Color(0xFF1A1A2E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    terminalViewModel: TerminalViewModel,
    connectionViewModel: ConnectionViewModel
) {
    val connectionState by connectionViewModel.relayConnectionState.collectAsState()
    val terminalState by terminalViewModel.state.collectAsState()

    val isConnected = connectionState == ConnectionState.Connected
    val isConnecting = connectionState == ConnectionState.Connecting ||
        connectionState == ConnectionState.Reconnecting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground)
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "Terminal",
                        style = MaterialTheme.typography.titleMedium
                    )
                    val subtitle = when {
                        terminalState.attached && terminalState.sessionName != null ->
                            terminalState.sessionName ?: ""
                        terminalState.attaching -> "attaching\u2026"
                        !isConnected -> "relay disconnected"
                        terminalState.error != null -> terminalState.error ?: ""
                        else -> "ready"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            actions = {
                ConnectionStatusBadge(
                    isConnected = isConnected && terminalState.attached,
                    isConnecting = isConnecting || terminalState.attaching,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { terminalViewModel.reattach() },
                    enabled = isConnected && !terminalState.attaching
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Reattach terminal"
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(TerminalBackground)
        ) {
            // The WebView is always present — it renders a blank terminal
            // until the relay attaches. An overlay explains state when
            // we're not usable yet.
            TerminalWebView(
                viewModel = terminalViewModel,
                modifier = Modifier.fillMaxSize()
            )

            val showOverlay = !isConnected || (!terminalState.attached && terminalState.error != null)
            if (showOverlay) {
                TerminalOverlay(
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    error = terminalState.error
                )
            }
        }

        ExtraKeysToolbar(
            ctrlActive = terminalState.ctrlActive,
            altActive = terminalState.altActive,
            onEsc = { terminalViewModel.sendKey(TerminalViewModel.SpecialKey.ESC) },
            onTab = { terminalViewModel.sendKey(TerminalViewModel.SpecialKey.TAB) },
            onCtrlToggle = { terminalViewModel.toggleCtrl() },
            onAltToggle = { terminalViewModel.toggleAlt() },
            onArrow = { key -> terminalViewModel.sendKey(key) },
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
        )
    }
}

@Composable
private fun TerminalOverlay(
    isConnected: Boolean,
    isConnecting: Boolean,
    error: String?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }

            val title = when {
                error != null -> "Terminal error"
                isConnecting -> "Connecting to relay\u2026"
                !isConnected -> "Relay disconnected"
                else -> "Ready"
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.9f)
            )

            val detail = when {
                error != null -> error
                !isConnected -> "Open Settings to configure your relay server. The terminal channel needs an active WSS connection."
                else -> null
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
