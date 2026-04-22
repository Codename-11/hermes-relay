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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.PairingPreferences

/**
 * TTL picker shown after a successful QR parse and *before* the WSS auth
 * handshake kicks off. Lets the user pick how long the pairing should last.
 *
 * **Design philosophy (Bailey's explicit calls):**
 *
 *  - **Never is always selectable.** We do NOT gate "Never expire" on
 *    transport security. Users on LAN, Tailscale, VPN, or TLS all need the
 *    option, and we trust the user's judgment. A brief warning sits under
 *    the option; we do not block it.
 *
 *  - **Tailscale is informational only.** [isTailscaleDetected] changes the
 *    helper line ("Transport: Tailscale detected") and nudges the default
 *    selection for fresh installs, but does not change what's available.
 *
 *  - **Last selection persists.** The user's previous choice is seeded via
 *    [initialTtlSeconds] which the caller pulls from
 *    [PairingPreferences.getPairTtlSeconds]. On confirm the caller persists
 *    the new choice back.
 *
 * **Default selection logic** (caller should compute via
 * [defaultTtlSeconds] before opening the dialog):
 *  1. If the QR payload's relay block has `ttlSeconds`, use that
 *  2. Else if transport hint is `"wss"` OR Tailscale is detected → 30 days
 *  3. Else if `ws://` without Tailscale → 7 days
 *  4. Fall back → 30 days
 *
 * The picker always shows so the user can override — the user's trust model
 * is the only one that matters and we force a confirmation step.
 */
@Composable
fun SessionTtlPickerDialog(
    initialTtlSeconds: Long,
    isTailscaleDetected: Boolean,
    transportHint: String?,
    onConfirm: (ttlSeconds: Long) -> Unit,
    onCancel: () -> Unit,
) {
    val options = ttlPickerOptions()
    val startIndex = options.indexOfFirst { it.seconds == initialTtlSeconds }
        .coerceAtLeast(defaultOptionIndex(options))

    var selectedIndex by remember { mutableStateOf(startIndex) }

    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Keep this pairing for…",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Your phone will reconnect automatically during " +
                        "this window. After it expires you'll need to re-pair.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Transport hint / Tailscale helper line
                val helperLine = when {
                    isTailscaleDetected -> "Transport: Tailscale detected"
                    transportHint.equals("wss", ignoreCase = true) -> "Transport: TLS (wss://)"
                    transportHint.equals("ws", ignoreCase = true) -> "Transport: plain ws://"
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

                Column {
                    options.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedIndex == index,
                                    onClick = { selectedIndex = index },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedIndex == index,
                                onClick = { selectedIndex = index }
                            )
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
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
                            text = "This device will stay paired until you " +
                                "revoke it manually from Relay sessions. " +
                                "Only choose this if you control the network " +
                                "— LAN, Tailscale, VPN, or TLS.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(options[selectedIndex].seconds) }
            ) {
                Text("Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}

/**
 * A single entry in the TTL picker. `seconds == 0` means "never expire"
 * (wire contract alignment — matches `ttl_seconds: 0` on the QR payload).
 */
data class TtlOption(val label: String, val seconds: Long)

/** The canonical set of TTL options shown in the picker. */
fun ttlPickerOptions(): List<TtlOption> = listOf(
    TtlOption("1 day", 24L * 60 * 60),
    TtlOption("7 days", 7L * 24 * 60 * 60),
    TtlOption("30 days", 30L * 24 * 60 * 60),
    TtlOption("90 days", 90L * 24 * 60 * 60),
    TtlOption("1 year", 365L * 24 * 60 * 60),
    TtlOption("Never expire", PairingPreferences.TTL_NEVER),
)

/** Fallback default when no previous selection and no QR hint — 30 days. */
private fun defaultOptionIndex(options: List<TtlOption>): Int =
    options.indexOfFirst { it.seconds == PairingPreferences.DEFAULT_TTL_SECONDS }
        .coerceAtLeast(0)

/**
 * Compute the default TTL for a new pair based on:
 *  - QR payload's `ttlSeconds` (operator intent via `hermes-pair --ttl`)
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
