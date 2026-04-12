package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Phase 3 — safety-rails `bridge-safety-rails`
 *
 * Destructive-verb confirmation modal content. Rendered inside a
 * [BridgeStatusOverlay] SYSTEM_ALERT_WINDOW ComposeView — NOT a Compose
 * `Dialog`, because Dialogs require an Activity window and we're sitting
 * on a WindowManager overlay. The parent overlay supplies the dim + the
 * full-screen-touch-interceptor; this composable only draws the card.
 *
 * Shows the agent's exact requested action + the flagged verb so the
 * user isn't guessing what they're allowing. Two buttons:
 *  - Deny (primary-tonal, safe default) — caller maps to false
 *  - Allow (tonal with a warning tint) — caller maps to true
 *
 * Kept UI-layer stateless: both `onAllow` and `onDeny` return directly.
 * The callers in [BridgeStatusOverlay] update the overlay registry and
 * resolve the pending `CompletableDeferred`.
 */
@Composable
fun DestructiveVerbConfirmDialog(
    method: String,
    verb: String,
    fullText: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    // Root-fills-overlay-with-center-alignment. The overlay already applies
    // FLAG_DIM_BEHIND so we only need to draw the card itself.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.92f)
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFFA726),
                    )
                    Text(
                        text = "Confirm destructive action",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Text(
                    text = "The Hermes agent is about to ${verbPhrase(method, verb)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = labelFor(method),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = fullText.ifBlank { "(no text)" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Text(
                    text = "If you didn't expect this, tap Deny.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onDeny,
                    ) {
                        Text("Deny")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onAllow,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE53935),
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Allow")
                    }
                }
            }
        }
    }
}

private fun labelFor(method: String): String = when (method) {
    "/tap_text" -> "Target text (tap)"
    "/type" -> "Text to type"
    else -> "Payload"
}

private fun verbPhrase(method: String, verb: String): String {
    val action = when (method) {
        "/tap_text" -> "tap a button containing"
        "/type" -> "type text containing"
        else -> "perform an action with"
    }
    return if (verb.isBlank()) "$action a destructive keyword" else "$action the word \"$verb\""
}

/**
 * Small floating status chip shown in the top-right of the screen when
 * the user has opted in via `BridgeSafetySettings.statusOverlayEnabled`.
 * Pure visual — no gesture handling, the parent overlay is FLAG_NOT_FOCUSABLE
 * so taps pass through to whatever's underneath.
 *
 * Kept in this file so [BridgeStatusOverlay] only imports one component
 * package.
 */
@Composable
fun BridgeStatusOverlayChip() {
    Box(
        modifier = Modifier
            .background(
                color = Color(0xFF1A1A2E).copy(alpha = 0.85f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .widthIn(min = 8.dp, max = 8.dp)
                    .background(Color(0xFFE53935), RoundedCornerShape(50))
            )
            Text(
                text = "Hermes active",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun BridgeStatusOverlayChipPreview() {
    MaterialTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            BridgeStatusOverlayChip()
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0x99000000)
@Composable
private fun DestructiveVerbConfirmDialogPreview_TapText() {
    MaterialTheme {
        DestructiveVerbConfirmDialog(
            method = "/tap_text",
            verb = "Send",
            fullText = "Send $500 to Alice",
            onAllow = {},
            onDeny = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0x99000000)
@Composable
private fun DestructiveVerbConfirmDialogPreview_Type() {
    MaterialTheme {
        DestructiveVerbConfirmDialog(
            method = "/type",
            verb = "delete",
            fullText = "delete all messages in #general",
            onAllow = {},
            onDeny = {},
        )
    }
}
