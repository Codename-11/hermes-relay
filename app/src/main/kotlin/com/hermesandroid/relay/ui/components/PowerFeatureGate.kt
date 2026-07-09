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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.hermesandroid.relay.R
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.ui.theme.HermesRelayTheme

enum class PowerFeatureGateStatus(
    val labelRes: Int,
    val actionLabelRes: Int,
    val explanationRes: Int,
) {
    RequiresPairing(
        labelRes = R.string.power_feature_requires_pairing_label,
        actionLabelRes = R.string.power_feature_requires_pairing_action,
        explanationRes = R.string.power_feature_requires_pairing_explain,
    ),
    PairingExpired(
        labelRes = R.string.power_feature_pairing_expired_label,
        actionLabelRes = R.string.power_feature_pairing_expired_action,
        explanationRes = R.string.power_feature_pairing_expired_explain,
    ),
    Unavailable(
        labelRes = R.string.power_feature_unavailable_label,
        actionLabelRes = R.string.power_feature_unavailable_action,
        explanationRes = R.string.power_feature_unavailable_explain,
    ),
    DashboardSignInRequired(
        labelRes = R.string.power_feature_dashboard_signin_label,
        actionLabelRes = R.string.power_feature_dashboard_signin_action,
        explanationRes = R.string.power_feature_dashboard_signin_explain,
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
            val backDesc = stringResource(R.string.power_feature_back_desc)
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = backDesc,
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
    val resolvedLabel = stringResource(status.labelRes)
    val resolvedExplanation = stringResource(status.explanationRes)
    val resolvedActionLabel = stringResource(status.actionLabelRes)
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
                        text = resolvedLabel,
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
                text = resolvedExplanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Button(
                onClick = onPrimaryAction,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(resolvedActionLabel)
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

