package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Clean "thread-spool" glyph for the agent **Threads** lane — a message trunk that
 * branches and curls down into a second message (a threaded conversation), drawn with
 * round-capped strokes so it stays crisp at small sizes.
 *
 * Deliberately NOT a phone glyph: a Thread is a source-tagged chat session the agent can
 * start, not "the phone". Brand-tinted via [tint]. Drawn on a [Canvas] (not an
 * `ImageVector`) so the geometry is exact and predictable at any size — used both as the
 * small in-row source tag and the drawer-header affordance.
 */
@Composable
fun ThreadSpoolGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Black,
) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val strokeW = s * 0.10f
        val trunkX = s * 0.30f
        val cap = StrokeCap.Round

        // Upper message — a short bar branching off the top of the trunk.
        drawLine(
            color = tint,
            start = Offset(trunkX, s * 0.33f),
            end = Offset(s * 0.74f, s * 0.33f),
            strokeWidth = strokeW,
            cap = cap,
        )

        // Trunk + spool curl: straight down, then a rounded quarter-turn to the right.
        val spool = Path().apply {
            moveTo(trunkX, s * 0.18f)
            lineTo(trunkX, s * 0.66f)
            cubicTo(
                trunkX, s * 0.77f,
                trunkX + s * 0.06f, s * 0.83f,
                trunkX + s * 0.17f, s * 0.83f,
            )
        }
        drawPath(
            path = spool,
            color = tint,
            style = Stroke(width = strokeW, cap = cap, join = StrokeJoin.Round),
        )

        // Lower message — a short bar off the end of the curl.
        drawLine(
            color = tint,
            start = Offset(trunkX + s * 0.17f, s * 0.83f),
            end = Offset(s * 0.74f, s * 0.83f),
            strokeWidth = strokeW,
            cap = cap,
        )
    }
}

/**
 * Sources that should surface as a distinct **Thread** lane in the session drawer.
 *
 * Slice 1 covers the agent Thread only (`source == "phone"`, the registered platform name
 * — see ADR 12). The broader "show every chat's source/platform in the drawer" goal
 * (Discord/Slack/API chips, sort/hide-by-source) is deferred until after the Threads
 * surface ships; extend this predicate / add a source→label map there.
 */
internal fun isThreadSource(source: String?): Boolean =
    source?.trim()?.lowercase() == "phone"
