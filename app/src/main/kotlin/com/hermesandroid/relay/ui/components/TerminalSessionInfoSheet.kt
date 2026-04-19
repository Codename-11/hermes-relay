package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.PairedSession
import com.hermesandroid.relay.viewmodel.TerminalViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Bottom-sheet dialog showing full metadata for a single terminal tab plus
 * quick-action buttons.
 *
 * Mirrors the UX of Chat's agent-info dialog (tap title → open info), but
 * uses a [ModalBottomSheet] instead of an AlertDialog because terminal
 * sessions have substantially more metadata (PID, shell, grid, transport,
 * grants, expiry) and the bottom-sheet form factor handles the longer
 * content gracefully without forcing scroll-inside-modal weirdness.
 *
 * The sheet reads its data from two sources:
 *
 *  - [tab] — per-tab state from [TerminalViewModel.activeTab]. Drives the
 *    tab-specific rows: tab number, session name, PID, shell, grid size,
 *    tmux availability.
 *
 *  - [pairedSession] — the relay-wide session metadata from
 *    [com.hermesandroid.relay.auth.AuthManager.currentPairedSession]. Drives
 *    transport security, expiry, and grant chips. May be null briefly during
 *    re-pair.
 *
 * Action buttons:
 *  - **Reattach** — calls [onReattach] then dismisses. The reattach itself is
 *    no-op safe if the tab is already attached (the ViewModel queues a fresh
 *    `terminal.attach` envelope).
 *  - **Close tab** — only enabled when there's more than one tab open
 *    ([TerminalViewModel.closeTab] no-ops on the last tab anyway). Calls
 *    [onCloseTab] then dismisses the sheet.
 *  - **Done** — pure dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalSessionInfoSheet(
    tab: TerminalViewModel.TabState,
    pairedSession: PairedSession?,
    canCloseTab: Boolean,
    onStart: () -> Unit,
    onReattach: () -> Unit,
    onCloseTab: () -> Unit,
    onKillTab: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // Vertical scroll guards against small screens — between per-tab
        // metadata, session chips, grants, and the action rows this column
        // can outrun the sheet's default half-screen height.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header — tab number + truncated session name. The session_name
            // is monospaced because it's an opaque-ish identifier and looks
            // wrong in a proportional font.
            Column {
                Text(
                    text = "Tab ${tab.tabId}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = tab.sessionName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Connection status chips — attach state + tmux availability.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatusChip(
                    label = when {
                        tab.attached -> "Attached"
                        tab.attaching -> "Attaching…"
                        tab.error != null -> "Error"
                        !tab.userStarted -> "Not started"
                        else -> "Detached"
                    },
                    isPositive = tab.attached,
                )
                if (tab.tmuxAvailable) {
                    StatusChip(label = "tmux", isPositive = true)
                }
            }

            HorizontalDivider()

            // Per-tab metadata.
            InfoRow("PID", tab.pid?.toString() ?: "—")
            InfoRow("Shell", tab.shell ?: "—", monospace = true)
            InfoRow("Grid", "${tab.cols} × ${tab.rows}")
            if (tab.error != null) {
                InfoRow("Last error", tab.error, valueColor = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider()

            // Relay session metadata — pulled from the shared paired session
            // because terminal piggybacks on the same session token as chat.
            // The transport badge is the same component used in
            // PairedDevicesScreen + ConnectionInfoSheet for visual parity.
            if (pairedSession != null) {
                Text(
                    text = "Relay session",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TransportSecurityBadge(
                    isSecure = pairedSession.transportHint == "wss",
                    reason = null,
                    size = TransportSecuritySize.Row,
                )
                InfoRow(
                    label = "Expires",
                    value = formatExpiry(pairedSession.expiresAt),
                )
                if (pairedSession.grants.isNotEmpty()) {
                    Text(
                        text = "Channel grants",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        for ((channel, expiry) in pairedSession.grants) {
                            GrantChipLocal(channel = channel, expiresAt = expiry)
                        }
                    }
                }
            } else {
                Text(
                    text = "No paired session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Primary action row — Start / Reattach is contextual on tab state.
            // Un-started tabs get "Start session" (spawns the shell on the
            // relay). Already-started tabs get "Reattach" (rebinds the PTY
            // stream — safe to call while attached too).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!tab.userStarted) {
                    OutlinedButton(
                        onClick = {
                            onStart()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Start session")
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            onReattach()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Reattach")
                    }
                }
                OutlinedButton(
                    onClick = {
                        onCloseTab()
                        onDismiss()
                    },
                    enabled = canCloseTab,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Close tab")
                }
            }

            // Destructive action — separate row, error-tinted, so users
            // don't misfire on the soft "Close tab" (which preserves the
            // tmux session). Kill tears down the tmux server-side, so the
            // shell and any running commands die. Visible whenever the tab
            // has actually been started; a never-started tab has nothing
            // to kill.
            if (tab.userStarted) {
                OutlinedButton(
                    onClick = {
                        onKillTab()
                        onDismiss()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Kill session")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusChip(label: String, isPositive: Boolean) {
    val (bg, fg) = if (isPositive) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) to MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun GrantChipLocal(channel: String, expiresAt: Long?) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = buildString {
                append(channel)
                append(" · ")
                append(if (expiresAt == null) "never" else formatExpiryShort(expiresAt))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatExpiry(epochSeconds: Long?): String {
    if (epochSeconds == null) return "Never"
    return try {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(epochSeconds * 1000L))
    } catch (_: Exception) {
        "—"
    }
}

private fun formatExpiryShort(epochSeconds: Long): String {
    return try {
        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochSeconds * 1000L))
    } catch (_: Exception) {
        "—"
    }
}
