package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import com.hermesandroid.relay.ui.theme.relayMetadataStyle
import com.hermesandroid.relay.ui.theme.relayPanel
import kotlin.math.abs

@Composable
fun RelayStatusStrip(
    leadingBadge: @Composable () -> Unit,
    routeLabel: String,
    trailing: String = "",
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onModelClick: (() -> Unit)? = null,
    onEffortClick: (() -> Unit)? = null,
    modelLabel: String? = null,
    effortLabel: String? = null,
    profileLabel: String? = null,
    /** Optional security marker rendered just before the route label. */
    securityGlyph: (@Composable () -> Unit)? = null,
    /**
     * When true, the strip shows the active chat connection's pending state
     * instead of its route label. Optional Relay availability is independent.
     */
    reconnecting: Boolean = false,
    maxContentWidth: Dp? = null,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = modifier
                .then(maxContentWidth?.let { Modifier.widthIn(max = it) } ?: Modifier)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                )
                .padding(start = 14.dp, end = 14.dp, top = 3.dp, bottom = 4.dp)
                .clip(appearanceRoundedCornerShape(16.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .relayPanel(
                    shape = appearanceRoundedCornerShape(16.dp),
                    background = RelayRefresh.Navy2.copy(alpha = 0.88f),
                    borderColor = RelayRefresh.Line,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 22.dp)
                    .padding(horizontal = 14.dp, vertical = 2.dp),
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
                Spacer(modifier = Modifier.width(6.dp))
                if (onModelClick != null && !modelLabel.isNullOrBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusPill(
                            label = modelLabel,
                            contentDescription = stringResource(R.string.cd_select_model),
                            onClick = onModelClick,
                        )

                        if (onEffortClick != null && !effortLabel.isNullOrBlank()) {
                            StatusPill(
                                label = effortLabel,
                                contentDescription = stringResource(R.string.chat_select_reasoning_effort),
                                onClick = onEffortClick,
                            )
                        }

                        if (!profileLabel.isNullOrBlank()) {
                            Text(
                                text = profileLabel,
                                style = relayMetadataStyle(),
                                color = RelayRefresh.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
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
    }
}

/**
 * Footer model/reasoning pill. Bounded on narrow phones and large font scale:
 * the label ellipses inside a fixed max width instead of pushing the route
 * readout or sibling pill off screen, while the whole pill keeps an explicit
 * minimum touch target and its own content description (the text node alone is
 * truncated, so it can't carry the full accessible name). Font scaling stays fully
 * enabled — only the width is capped.
 */
@Composable
private fun StatusPill(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
            .border(
                BorderStroke(
                    0.5.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                ),
                RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
            }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Amber connection-progress cue with a softly pulsing dot. This is the *only*
 * surface for a routine in-progress relay reconnect — the top of the app stays
 * empty (chat/agent status rides the chat header subtitle) so nothing shifts.
 * Pulse is frame-throttled via [rememberAmbientPhase] to avoid pinning the
 * window at panel refresh.
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
            text = stringResource(R.string.session_path_connecting),
            style = relayMetadataStyle(),
            color = RelayRefresh.Amber,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
