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
 * Four-state connection indicator. The fourth state — [Probing] — is the
 * one that fixes the resume-lag flash: when the app comes back to the
 * foreground, every status starts as Probing instead of inheriting whatever
 * the StateFlow happened to hold from the last session. The badge then
 * resolves to Connected or Disconnected once a fresh health probe lands.
 *
 * Old call sites that still use the boolean overload keep working — they
 * just never enter the Probing state, which is fine for the local-first
 * indicators (e.g. terminal session badges) where there's nothing to revalidate.
 */
enum class BadgeState {
    /** Verified connected by the most recent probe. Green pulse. */
    Connected,

    /** Connect/handshake in flight. Amber pulse. */
    Connecting,

    /** Verified disconnected by the most recent probe. Red, no pulse. */
    Disconnected,

    /** Status not yet verified — fresh foreground, no probe has landed. Gray pulse. */
    Probing,
}

/**
 * Animated connection status indicator — a colored dot with an optional pulsing ring.
 *
 * - **Connected (green):** solid dot + slow heartbeat pulse (1.5 s)
 * - **Connecting / Reconnecting (amber):** solid dot + faster pulse (0.8 s)
 * - **Probing (gray):** solid dot + slow pulse (1.2 s) — initial state on resume
 * - **Disconnected (red):** solid dot, no pulse
 */
@Composable
fun ConnectionStatusBadge(
    state: BadgeState,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
) {
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error

    val dotColor: Color
    val showPulse: Boolean
    val pulseDurationMs: Int
    val statusLabel: String

    when (state) {
        BadgeState.Connected -> {
            dotColor = Color(0xFF4CAF50) // Material green 500
            showPulse = true
            pulseDurationMs = 1500
            statusLabel = "Connected"
        }
        BadgeState.Connecting -> {
            dotColor = Color(0xFFFFA726) // Material amber/orange 400
            showPulse = true
            pulseDurationMs = 800
            statusLabel = "Connecting"
        }
        BadgeState.Probing -> {
            dotColor = onSurfaceVariant
            showPulse = true
            pulseDurationMs = 1200
            statusLabel = "Checking status"
        }
        BadgeState.Disconnected -> {
            dotColor = errorColor
            showPulse = false
            pulseDurationMs = 1500 // unused but required for val init
            statusLabel = "Disconnected"
        }
    }

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
                if (showPulse && pulseAlpha > 0f) {
                    val ringRadius = (this.size.minDimension / 2f) * pulseScale
                    drawCircle(
                        color = dotColor.copy(alpha = pulseAlpha),
                        radius = ringRadius,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
                drawCircle(color = dotColor)
            }
    )
}

/**
 * Boolean overload — preserved for legacy call sites (terminal, bridge,
 * chat header, etc.) that don't need a Probing state. New code should pass
 * a [BadgeState] directly so it can express the "checking status" pose.
 */
@Composable
fun ConnectionStatusBadge(
    isConnected: Boolean,
    isConnecting: Boolean = false,
    isProbing: Boolean = false,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
) {
    val state = when {
        isConnected -> BadgeState.Connected
        isConnecting -> BadgeState.Connecting
        isProbing -> BadgeState.Probing
        else -> BadgeState.Disconnected
    }
    ConnectionStatusBadge(state = state, modifier = modifier, size = size)
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
    isProbing: Boolean = false,
    statusText: String,
    onTest: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state = when {
        isConnected -> BadgeState.Connected
        isConnecting -> BadgeState.Connecting
        isProbing -> BadgeState.Probing
        else -> BadgeState.Disconnected
    }
    ConnectionStatusRow(
        label = label,
        state = state,
        statusText = statusText,
        onTest = onTest,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * Four-state overload of [ConnectionStatusRow]. Prefer this for any new
 * call site that needs to render the Probing state — the boolean overload
 * is kept around for legacy call sites.
 */
@Composable
fun ConnectionStatusRow(
    label: String,
    state: BadgeState,
    statusText: String,
    onTest: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
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
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Small top padding on the badge so it sits visually centered with
        // a single-line label+status, but stays aligned to the top for
        // multi-line errors (where CenterVertically would drop it into the
        // middle of a three-line block).
        Box(modifier = Modifier.padding(top = 4.dp)) {
            ConnectionStatusBadge(state = state)
        }

        // Label keeps its intrinsic width (no weight). Previously carried
        // `weight(1f, fill = false)`, which collapsed it to 1 char wide
        // when an unweighted statusText greedily claimed the whole row.
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )

        // Status text gets the weight so long errors wrap inside their own
        // allocation instead of squeezing the label. `fill = false` lets
        // short statuses (e.g. "Reachable") sit naturally without forcing
        // the row to span full width.
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = when (state) {
                BadgeState.Connected -> Color(0xFF4CAF50)
                BadgeState.Connecting -> Color(0xFFFFA726)
                BadgeState.Probing -> MaterialTheme.colorScheme.onSurfaceVariant
                BadgeState.Disconnected -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier.weight(1f, fill = false),
        )

        if (onTest != null) {
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(onClick = onTest) {
                Text("Test")
            }
        }

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
