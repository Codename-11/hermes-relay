package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.ui.theme.HermesRelayTheme

enum class PowerFeatureGateStatus(
    val label: String,
    val actionLabel: String,
    val explanation: String,
) {
    RequiresPairing(
        label = "Requires pairing",
        actionLabel = "Pair to unlock",
        explanation = "This feature requires the Relay plugin. Make sure it is installed " +
            "and running on your Hermes server, then pair this device to unlock it.",
    ),
    PairingExpired(
        label = "Pairing expired",
        actionLabel = "Pair again",
        explanation = "Your relay session is no longer accepted. Pair again to get a fresh grant.",
    ),
    Unavailable(
        label = "Unavailable on this server",
        actionLabel = "View connection",
        explanation = "This Hermes server isn't exposing the Relay plugin (or it's an older " +
            "version). Install or update the Relay plugin on the server to use this feature.",
    ),
    DashboardSignInRequired(
        label = "Dashboard sign-in required",
        actionLabel = "Open sign-in",
        explanation = "This standard dashboard feature needs a dashboard session before it can load.",
    );

    companion object {
        fun fromRelayAuth(authState: AuthState): PowerFeatureGateStatus {
            return when (authState) {
                is AuthState.Failed -> {
                    val reason = authState.reason.lowercase()
                    if ("expired" in reason || "token" in reason || "session" in reason) {
                        PairingExpired
                    } else {
                        RequiresPairing
                    }
                }
                else -> RequiresPairing
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerFeatureGateScreen(
    title: String,
    summary: String,
    status: PowerFeatureGateStatus,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PowerFeatureGateCard(
                title = title,
                summary = summary,
                status = status,
                onPrimaryAction = onPrimaryAction,
            )
        }
    }
}

@Composable
fun PowerFeatureGateCard(
    title: String,
    summary: String,
    status: PowerFeatureGateStatus,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = status.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Button(
                onClick = onPrimaryAction,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(status.actionLabel)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PowerFeatureGatePreview() {
    HermesRelayTheme {
        PowerFeatureGateCard(
            title = "Terminal",
            summary = "Open a server shell through your paired relay session.",
            status = PowerFeatureGateStatus.RequiresPairing,
            onPrimaryAction = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PowerFeatureGateExpiredPreview() {
    HermesRelayTheme {
        PowerFeatureGateCard(
            title = "Bridge",
            summary = "Let Hermes send approved bridge commands to this phone.",
            status = PowerFeatureGateStatus.PairingExpired,
            onPrimaryAction = {},
        )
    }
}

