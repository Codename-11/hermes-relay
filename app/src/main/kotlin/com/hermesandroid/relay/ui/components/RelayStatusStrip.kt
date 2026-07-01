package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayMetadataStyle
import com.hermesandroid.relay.ui.theme.relayPanel
import kotlin.math.abs

@Composable
fun RelayStatusStrip(
    leadingBadge: @Composable () -> Unit,
    routeLabel: String,
    trailing: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** Optional security marker rendered just before the route label. */
    securityGlyph: (@Composable () -> Unit)? = null,
    /**
     * When true, the strip shows an amber "Reconnecting…" cue in place of the
     * route label. This is where a **routine** in-progress reconnect surfaces —
     * the top chrome stays empty so chat content never shifts (see
     * [com.hermesandroid.relay.viewmodel.ConnectionStatusSurface.None]).
     */
    reconnecting: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .padding(start = 14.dp, end = 14.dp, top = 3.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(999.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .relayPanel(
                shape = RoundedCornerShape(999.dp),
                background = RelayRefresh.Navy2.copy(alpha = 0.88f),
                borderColor = RelayRefresh.Line,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingBadge()
                if (securityGlyph != null) {
                    securityGlyph()
                }
                // Route is in flux mid-reconnect, so the amber cue replaces the
                // route label rather than stacking beside it in the 22dp strip.
                when {
                    reconnecting -> ReconnectingCue(modifier = Modifier.weight(1f))
                    routeLabel.isNotBlank() -> Text(
                        text = "· $routeLabel",
                        style = relayMetadataStyle(),
                        color = RelayRefresh.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = trailing,
                style = relayMetadataStyle(),
                color = RelayRefresh.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Amber "· Reconnecting…" cue with a softly pulsing dot. This is the *only*
 * connection-status surface for a routine in-progress reconnect — the top
 * banner/toast stay empty so chat content never shifts (see
 * [com.hermesandroid.relay.viewmodel.ConnectionStatusSurface]). Pulse is
 * frame-throttled via [rememberAmbientPhase] to avoid pinning the window at
 * panel refresh.
 */
@Composable
private fun ReconnectingCue(modifier: Modifier = Modifier) {
    val phase = rememberAmbientPhase(periodMillis = 1200)
    val triangle = 1f - abs(2f * phase - 1f)
    val dotAlpha = 0.4f + 0.6f * triangle
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .alpha(dotAlpha)
                .background(RelayRefresh.Amber),
        )
        Text(
            text = "Reconnecting…",
            style = relayMetadataStyle(),
            color = RelayRefresh.Amber,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
