package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * v0.4.1 — sideload-only "unattended access" toggle row + one-time
 * scary opt-in dialog + persistent keyguard-detected chip.
 *
 * Visually mirrors [BridgeMasterToggle]'s card style (surfaceVariant
 * background, 14dp corner radius, 16dp padding) so the addition feels
 * native to the existing Bridge tab. Composes three pieces:
 *
 *  1. **Toggle row** — switches the unattended-access opt-in on/off.
 *     When off, no wake-lock or keyguard-dismiss is attempted.
 *  2. **One-time warning dialog** — shown the first time the user
 *     attempts to flip the switch ON. Explains the security model
 *     (relay can drive your phone while you're away), the credential-
 *     lock limitation (Android won't let us dismiss PIN / pattern /
 *     biometric), and how to disable. The dialog is launched from
 *     [onAttemptEnable] when [warningSeen] is false; the parent records
 *     dismissal via [onWarningSeen] which latches the seen flag.
 *  3. **Persistent chip** — when unattended is ON AND the device has
 *     a credential lock detected, a prominent error-tinted card warns
 *     the user that the screen will wake but stop at the lock screen.
 *     Goes away when either flag flips off.
 */
@Composable
fun UnattendedAccessRow(
    enabled: Boolean,
    warningSeen: Boolean,
    credentialLockDetected: Boolean,
    onToggle: (Boolean) -> Unit,
    onWarningSeen: () -> Unit,
    modifier: Modifier = Modifier,
    // v0.4.1 polish: the master toggle is the parent gate. When it's off
    // unattended access can't do anything (the wake-lock acquire path
    // short-circuits regardless of this flag's value), so the Switch
    // should reflect that reality — otherwise users flip it and see no
    // observable change, which reads as a broken control.
    masterEnabled: Boolean = true,
) {
    var showWarning by remember { mutableStateOf(false) }
    var pendingEnableAfterWarning by remember { mutableStateOf(false) }

    // "Effectively on" — the persisted preference AND the master gate.
    // Drives the keyguard warning (no point showing it when master is
    // off — the feature isn't active regardless of lock state).
    val effectivelyOn = enabled && masterEnabled

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Unattended Access",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            !masterEnabled -> "Requires Agent Control — " +
                                "enable the master switch above first."
                            enabled -> "On — agent may wake the screen " +
                                "and act while you're away."
                            else -> "Off — bridge actions only land when " +
                                "the screen is already on."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    enabled = masterEnabled,
                    onCheckedChange = { wantsOn ->
                        if (wantsOn && !warningSeen) {
                            // First enable → show the scary dialog and
                            // defer the actual toggle write until the
                            // user dismisses with "I understand".
                            pendingEnableAfterWarning = true
                            showWarning = true
                        } else {
                            onToggle(wantsOn)
                        }
                    },
                )
            }

            Text(
                text = "Acquires a screen-bright wake lock on each " +
                    "incoming bridge command and asks the system to " +
                    "dismiss the keyguard. Hard-bounded by the bridge " +
                    "auto-disable timer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Keyguard warning is now an inline alert band inside the
            // same card, instead of a second sibling card. Keeps the
            // "one card per concern" principle — a credential-lock
            // advisory belongs to the unattended feature, not to its
            // own standalone surface. Only shown when the feature is
            // effectively active (toggle on AND master on) so it
            // doesn't nag users who have the feature disabled.
            if (effectivelyOn && credentialLockDetected) {
                KeyguardDetectedAlert()
            }
        }
    }

    if (showWarning) {
        UnattendedScaryDialog(
            credentialLockDetected = credentialLockDetected,
            onConfirm = {
                showWarning = false
                onWarningSeen()
                if (pendingEnableAfterWarning) {
                    pendingEnableAfterWarning = false
                    onToggle(true)
                }
            },
            onDismiss = {
                showWarning = false
                pendingEnableAfterWarning = false
                // User backed out — leave the toggle off. The seen flag
                // stays false so the dialog re-appears on the next
                // attempt.
            },
        )
    }
}

/**
 * Inline alert band rendered inside the unattended-access card when the
 * device has a credential lock (PIN / pattern / biometric) configured.
 * Communicates the structural limitation: Android will not let third-
 * party apps dismiss credential locks, so wake events will land the
 * screen on the lock screen rather than the agent's target UI.
 *
 * Uses a [Surface] instead of a [Card] so it doesn't compete with the
 * parent card's elevation / shadow — it reads as "part of this card"
 * rather than "another card under this card".
 */
@Composable
private fun KeyguardDetectedAlert() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Keyguard detected",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "The screen will wake but stop at the lock " +
                        "screen. Android won't let third-party apps " +
                        "dismiss PIN / pattern / biometric locks. Set " +
                        "your lock to None or Swipe in Settings > " +
                        "Security to let the agent reach apps.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * One-time scary opt-in dialog. Per CLAUDE.md and the v0.4.1 spec, this
 * fires the FIRST time the user attempts to enable unattended access and
 * never again afterwards (latched via [BridgeSafetySettings.unattendedWarningSeen]).
 *
 * Three sections explain (a) the security model, (b) the credential-lock
 * limitation, and (c) how to disable. The "I understand" button latches
 * the seen flag and proceeds with the enable; the cancel button backs
 * out without enabling.
 */
@Composable
private fun UnattendedScaryDialog(
    credentialLockDetected: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("Enable Unattended Access?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Unattended access lets your Hermes agent wake " +
                        "the screen and drive your phone while you're not " +
                        "watching it. This is a powerful capability with " +
                        "real risk — make sure you understand it before " +
                        "enabling.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "What it does:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "• Each incoming bridge command holds a screen-" +
                        "bright wake lock so taps and gestures land on a " +
                        "lit display.\n" +
                        "• On lock screens with no credential set (None " +
                        "or Swipe), the system is asked to dismiss the " +
                        "keyguard so the agent can reach app UIs.\n" +
                        "• The 'Hermes has device control' notification — " +
                        "posted by the master Agent Control switch, not " +
                        "this toggle — stays visible so you can see the " +
                        "bridge is running.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Limitation: credential locks cannot be dismissed.",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = if (credentialLockDetected) {
                        "Your device currently has a PIN, pattern, or " +
                            "biometric lock set. Android does not let " +
                            "third-party apps dismiss these — the screen " +
                            "will wake, but stop at the lock screen. To " +
                            "let the agent reach apps, change your lock " +
                            "to None or Swipe in Settings > Security."
                    } else {
                        "If you later set a PIN, pattern, or biometric " +
                            "lock, Android will not let Hermes dismiss it. " +
                            "The screen will wake, but stop at the lock " +
                            "screen, and the bridge will report a " +
                            "'keyguard_blocked' error to the agent."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "How to disable:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "• Flip this same toggle off at any time.\n" +
                        "• The bridge auto-disable timer (default 30 minutes " +
                        "of idle) will turn unattended access off along " +
                        "with the master bridge toggle.\n" +
                        "• Disconnecting from the relay also drops the " +
                        "wake lock immediately.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("I understand — enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun UnattendedAccessRowPreview_Off() {
    MaterialTheme {
        UnattendedAccessRow(
            enabled = false,
            warningSeen = false,
            credentialLockDetected = false,
            onToggle = {},
            onWarningSeen = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun UnattendedAccessRowPreview_OnWithKeyguard() {
    MaterialTheme {
        UnattendedAccessRow(
            enabled = true,
            warningSeen = true,
            credentialLockDetected = true,
            onToggle = {},
            onWarningSeen = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun UnattendedAccessRowPreview_OnNoLock() {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            UnattendedAccessRow(
                enabled = true,
                warningSeen = true,
                credentialLockDetected = false,
                onToggle = {},
                onWarningSeen = {},
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun UnattendedAccessRowPreview_MasterOff() {
    MaterialTheme {
        UnattendedAccessRow(
            enabled = false,
            warningSeen = false,
            credentialLockDetected = false,
            onToggle = {},
            onWarningSeen = {},
            masterEnabled = false,
            modifier = Modifier.padding(16.dp),
        )
    }
}
