package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.RelayRefresh
import kotlin.math.roundToInt

/**
 * Per-session context-window gauge — mirrors the desktop TUI status line's
 * context meter: a slim filled bar plus a `NN% · used/max` token readout,
 * color-graded by fullness (green < 50% · amber 50–80% · orange 80–95% ·
 * red ≥ 95%). The value is session-cumulative and reset per session by the
 * ViewModel, so this always reflects the active conversation.
 *
 * Renders only when [usedFraction] is non-null — i.e. the server's context
 * compressor reported a `context_max`. Pass [usedTokens]/[maxTokens] to show
 * the absolute counts; when they're absent (percent-only servers) the bar
 * shows just the percent.
 */
@Composable
fun ContextMeterBar(
    usedFraction: Float?,
    usedTokens: Int? = null,
    maxTokens: Int? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // The meter's first appearance (null → reported) used to hard-pop the row
    // into the layout, and a session switch (reported → null) made it vanish.
    // Fade + expand it in/out instead. The last CONFIRMED values are retained
    // only so the exit animation has real numbers to render while the source
    // flow is already null — we never invent or guess a value.
    var lastFraction by remember { mutableStateOf<Float?>(null) }
    var lastUsed by remember { mutableStateOf<Int?>(null) }
    var lastMax by remember { mutableStateOf<Int?>(null) }
    if (usedFraction != null) {
        lastFraction = usedFraction
        lastUsed = usedTokens
        lastMax = maxTokens
    }

    AnimatedVisibility(
        visible = usedFraction != null,
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
    ) {
        lastFraction?.let { frac ->
            ContextMeterBarContent(
                usedFraction = frac,
                usedTokens = lastUsed,
                maxTokens = lastMax,
                modifier = modifier,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun ContextMeterBarContent(
    usedFraction: Float,
    usedTokens: Int?,
    maxTokens: Int?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val target = usedFraction.coerceIn(0f, 1f)
    val fill by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(600),
        label = "ctxFill",
    )
    val color = when {
        fill >= 0.95f -> RelayRefresh.Danger
        fill >= 0.80f -> lerp(RelayRefresh.Amber, RelayRefresh.Danger, 0.5f) // orange
        fill >= 0.50f -> RelayRefresh.Amber
        else -> RelayRefresh.Green
    }
    val percent = (target * 100).roundToInt()
    val tokenSuffix = if (usedTokens != null && maxTokens != null && maxTokens > 0) {
        " · ${fmtTokens(usedTokens)}/${fmtTokens(maxTokens)}"
    } else {
        ""
    }
    val label = "$percent%$tokenSuffix"

    val contextUsedDesc = stringResource(R.string.cd_context_used, percent)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .semantics {
                contentDescription = contextUsedDesc +
                    if (tokenSuffix.isNotEmpty()) ", ${fmtTokens(usedTokens!!)} of ${fmtTokens(maxTokens!!)} tokens" else ""
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fill)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (fill >= 0.50f) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onClick != null) {
            // Subtle affordance that the meter is tappable → injected-context audit.
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.cd_view_injected),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** Compact token count: 31000 → "31k", 200000 → "200k", 850 → "850". */
private fun fmtTokens(n: Int): String =
    if (n >= 1000) "${n / 1000}k" else n.toString()
