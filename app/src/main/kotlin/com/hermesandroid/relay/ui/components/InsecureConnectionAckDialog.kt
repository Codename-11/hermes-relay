package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R

/**
 * One-time acknowledgment dialog shown the first time the user flips
 * "Allow insecure connections" on. Explains the threat model in plain
 * language and asks the user to confirm *why* they're enabling it.
 *
 * The selected reason is stored in
 * [com.hermesandroid.relay.data.PairingPreferences.setInsecureReason]
 * and drives the label on the [TransportSecurityBadge]. It is **not** used
 * to gate anything — Bailey's explicit call is "trust the user's judgment,
 * just display what they told us".
 *
 * State management is owned by the caller:
 *  - Caller tracks `showDialog` as a boolean
 *  - On dismiss without a reason, caller should revert the toggle
 *  - On confirm with a reason, caller persists both the reason and
 *    `insecureAckSeen = true`, then leaves the toggle enabled
 */
@Composable
fun InsecureConnectionAckDialog(
    onConfirm: (reason: String) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedReason by remember { mutableStateOf<String?>(null) }

    val reasonLan = stringResource(R.string.insecure_ack_reason_lan)
    val reasonTailscale = stringResource(R.string.insecure_ack_reason_tailscale)
    val reasonDev = stringResource(R.string.insecure_ack_reason_dev)
    val reasonOptions = listOf(
        "lan_only" to reasonLan,
        "tailscale_vpn" to reasonTailscale,
        "local_dev" to reasonDev,
    )

    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(R.string.insecure_ack_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.insecure_ack_body_1),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.insecure_ack_body_2),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(4.dp))

                Column {
                    reasonOptions.forEach { (key, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedReason == key,
                                    onClick = { selectedReason = key },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedReason == key,
                                onClick = { selectedReason = key }
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedReason != null,
                onClick = { selectedReason?.let(onConfirm) }
            ) {
                Text(stringResource(R.string.insecure_ack_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.insecure_ack_cancel))
            }
        }
    )
}
