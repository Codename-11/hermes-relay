package com.hermesandroid.relay.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.RelayRefresh
import kotlin.math.roundToInt

/**
 * Ambient context-window meter — a 2dp hairline strip seated at the seam
 * between the TopAppBar and RelayModeStrip. The Telegram answer: zero-tap,
 * invisible until it matters.
 *
 * Silent below 50% usage (composes to nothing — no reserved height, the
 * seam simply stays a seam). From 50% the fill tracks
 * [usedFraction] through Relay → Amber (≥75%) → Danger (≥90%), the same
 * caution ladder the sudo countdown and voice badge use. Amber/Danger are
 * deliberate direct RelayRefresh reads — identical in both schemes.
 *
 * Render only when the gateway usage carries a context_max (the compressor
 * is present); pass null otherwise and the strip vanishes.
 */
@Composable
fun ContextMeterBar(usedFraction: Float?, modifier: Modifier = Modifier) {
    if (usedFraction == null || usedFraction < 0.5f) return

    val fill by animateFloatAsState(
        targetValue = usedFraction.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "ctxFill",
    )
    val color = when {
        fill >= 0.9f -> RelayRefresh.Danger
        fill >= 0.75f -> RelayRefresh.Amber
        else -> RelayRefresh.Relay.copy(alpha = 0.8f)
    }
    val percent = (usedFraction.coerceIn(0f, 1f) * 100).roundToInt()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
            .semantics { contentDescription = "Context $percent% used" },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fill)
                .fillMaxHeight()
                .background(color),
        )
    }
}
