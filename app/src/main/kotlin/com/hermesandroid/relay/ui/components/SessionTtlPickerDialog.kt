package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.PairingPreferences

/** Duration confirmation. Host-approved pairing policy remains authoritative. */
@Composable
fun SessionTtlPickerDialog(
    initialTtlSeconds: Long,
    isTailscaleDetected: Boolean,
    transportHint: String?,
    onConfirm: (ttlSeconds: Long) -> Unit,
    onCancel: () -> Unit,
    options: List<TtlOption> = ttlPickerOptions(),
    @StringRes titleRes: Int = R.string.ttl_title,
    @StringRes bodyRes: Int = R.string.ttl_body,
    @StringRes confirmRes: Int = R.string.ttl_pair,
    busy: Boolean = false,
    error: String? = null,
    onRenew: (() -> Unit)? = null,
) {
    val startIndex = options.indexOfFirst { it.seconds == initialTtlSeconds }
        .takeIf { it >= 0 } ?: defaultOptionIndex(options)

    var selectedIndex by remember(options, initialTtlSeconds) { mutableStateOf(startIndex) }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        icon = {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (busy) Text(stringResource(R.string.paired_devices_updating))
                if (options.isEmpty()) Text(stringResource(R.string.paired_devices_no_shorter_duration))
                Text(
                    text = stringResource(bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Transport hint / Tailscale helper line
                val helperLine = when {
                    isTailscaleDetected -> stringResource(R.string.ttl_helper_tailscale)
                    transportHint.equals("wss", ignoreCase = true) -> stringResource(R.string.ttl_helper_tls)
                    transportHint.equals("ws", ignoreCase = true) -> stringResource(R.string.ttl_helper_plain)
                    else -> null
                }
                if (helperLine != null) {
                    Text(
                        text = helperLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(4.dp))

                Column(Modifier.selectableGroup()) {
                    options.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selectedIndex == index,
                                    enabled = !busy,
                                    onClick = { selectedIndex = index },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedIndex == index,
                                onClick = null,
                                enabled = !busy
                            )
                            Text(
                                text = stringResource(option.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                if (onRenew != null) {
                    TextButton(onClick = onRenew, enabled = !busy) {
                        Text(stringResource(R.string.paired_devices_pair_again))
                    }
                }

                // Never-expire warning — inline, not a gate. Shown only when
                // the user has Never selected.
                val neverIndex = options.indexOfFirst { it.seconds == PairingPreferences.TTL_NEVER }
                if (selectedIndex == neverIndex) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .height(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.ttl_never_expire_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && options.isNotEmpty(),
                onClick = { onConfirm(options[selectedIndex].seconds) }
            ) {
                Text(stringResource(confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(stringResource(R.string.ttl_cancel))
            }
        }
    )
}

/**
 * A single entry in the TTL picker. `seconds == 0` means "never expire"
 * (wire contract alignment — matches `ttl_seconds: 0` on the QR payload).
 */
data class TtlOption(@StringRes val labelRes: Int, val seconds: Long)

/** The canonical set of TTL options shown in the picker. */
fun ttlPickerOptions(): List<TtlOption> = listOf(
    TtlOption(R.string.ttl_option_1d, 24L * 60 * 60),
    TtlOption(R.string.ttl_option_7d, 7L * 24 * 60 * 60),
    TtlOption(R.string.ttl_option_30d, 30L * 24 * 60 * 60),
    TtlOption(R.string.ttl_option_90d, 90L * 24 * 60 * 60),
    TtlOption(R.string.ttl_option_1y, 365L * 24 * 60 * 60),
    TtlOption(R.string.ttl_option_never, PairingPreferences.TTL_NEVER),
)

/** Fallback default when no previous selection and no QR hint — 30 days. */
private fun defaultOptionIndex(options: List<TtlOption>): Int =
    options.indexOfFirst { it.seconds == PairingPreferences.DEFAULT_TTL_SECONDS }
        .coerceAtLeast(0)

/**
 * Compute the default TTL for a new pair based on:
 *  - QR payload's `ttlSeconds` (operator intent via `hermes pair --ttl`)
 *  - Transport hint (`"wss"` → 30d, `"ws"` → 7d)
 *  - Tailscale detected → 30d
 *  - Fallback → 30d
 *
 * Called by [com.hermesandroid.relay.ui.screens.SettingsScreen] right before
 * opening the picker, passed in as `initialTtlSeconds`.
 */
fun defaultTtlSeconds(
    qrTtlSeconds: Long?,
    transportHint: String?,
    isTailscaleDetected: Boolean,
): Long {
    if (qrTtlSeconds != null) return qrTtlSeconds
    val isWss = transportHint.equals("wss", ignoreCase = true)
    val isWs = transportHint.equals("ws", ignoreCase = true)
    return when {
        isWss || isTailscaleDetected -> 30L * 24 * 60 * 60
        isWs -> 7L * 24 * 60 * 60
        else -> PairingPreferences.DEFAULT_TTL_SECONDS
    }
}
