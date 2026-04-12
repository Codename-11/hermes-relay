package com.hermesandroid.relay.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Animated connection status indicator — a colored dot with an optional pulsing ring.
 *
 * - **Connected (green):** solid dot + slow heartbeat pulse (1.5 s)
 * - **Connecting / Reconnecting (amber):** solid dot + faster pulse (0.8 s)
 * - **Disconnected (red):** solid dot, no pulse
 */
@Composable
fun ConnectionStatusBadge(
    isConnected: Boolean,
    isConnecting: Boolean = false,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp
) {
    val dotColor: Color
    val showPulse: Boolean
    val pulseDurationMs: Int
    val statusLabel: String

    when {
        isConnected -> {
            dotColor = Color(0xFF4CAF50) // Material green 500
            showPulse = true
            pulseDurationMs = 1500
            statusLabel = "Connected"
        }
        isConnecting -> {
            dotColor = Color(0xFFFFA726) // Material amber/orange 400
            showPulse = true
            pulseDurationMs = 800
            statusLabel = "Connecting"
        }
        else -> {
            dotColor = MaterialTheme.colorScheme.error
            showPulse = false
            pulseDurationMs = 1500 // unused but required for val init
            statusLabel = "Disconnected"
        }
    }

    // Pulse animation — only runs when showPulse is true
    val pulseScale: Float
    val pulseAlpha: Float

    if (showPulse) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        pulseScale = infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = pulseDurationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulseScale"
        ).value
        pulseAlpha = infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = pulseDurationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulseAlpha"
        ).value
    } else {
        pulseScale = 1f
        pulseAlpha = 0f
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = statusLabel }
            .drawBehind {
                // Pulse ring (expanding, fading circle outline)
                if (showPulse && pulseAlpha > 0f) {
                    val ringRadius = (this.size.minDimension / 2f) * pulseScale
                    drawCircle(
                        color = dotColor.copy(alpha = pulseAlpha),
                        radius = ringRadius,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Solid inner dot
                drawCircle(color = dotColor)
            }
    )
}

/**
 * A row showing [ConnectionStatusBadge] alongside a text label and status text.
 *
 * When [onClick] is non-null, the row is rendered as a tappable surface with
 * a trailing chevron so the user gets a clear "tap for details" affordance —
 * otherwise users have no way to tell that the row opens a drawer.
 * When null, the row is static and no chevron is shown.
 *
 * The old `onTest` trailing-button slot is still supported for call sites
 * that want an inline Test button inside the row — distinct from the whole-row
 * clickable behavior.
 */
@Composable
fun ConnectionStatusRow(
    label: String,
    isConnected: Boolean,
    isConnecting: Boolean = false,
    statusText: String,
    onTest: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Clip + clickable gives us the full Material ripple on the row surface
    // plus rounded corners so the tap target looks like a list item rather
    // than a raw strip. If the caller also passed a clickable via modifier
    // (legacy call sites that haven't been migrated to onClick yet), that
    // still works — Modifier.clickable is additive.
    val interactiveModifier = if (onClick != null) {
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    } else {
        Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
    }
    Row(
        modifier = modifier.then(interactiveModifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ConnectionStatusBadge(
            isConnected = isConnected,
            isConnecting = isConnecting
        )

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false)
        )

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                isConnected -> Color(0xFF4CAF50)
                isConnecting -> Color(0xFFFFA726)
                else -> MaterialTheme.colorScheme.error
            }
        )

        if (onTest != null) {
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(onClick = onTest) {
                Text("Test")
            }
        }

        // Chevron affordance for tappable rows. Users couldn't previously
        // tell that API / Relay / Session rows were tappable — the drawer
        // opening was invisible until they stumbled onto the tap target.
        if (onClick != null && onTest == null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
