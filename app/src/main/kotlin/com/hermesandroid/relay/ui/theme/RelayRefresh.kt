package com.hermesandroid.relay.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Brand token façade. Every color is now a getter over [activePalette], which
 * `HermesRelayTheme` swaps to match the active [AppTheme] + light/dark mode.
 * Because [activePalette] is snapshot-backed, every `RelayRefresh.X` read —
 * whether in composition or in a draw lambda — subscribes to it and recomposes
 * (or re-draws) when the theme changes. This is how the 150+ pre-existing call
 * sites became theme-reactive without edits, fixing the formerly hardcoded-dark
 * chat and Manage surfaces.
 *
 * New code should prefer [LocalBrand] for composition-correct scoping; this
 * façade is kept for the legacy `RelayRefresh.X` call sites.
 */
object RelayRefresh {
    // Snapshot-backed so reads track theme changes. Set only by HermesRelayTheme.
    private val activePaletteState = mutableStateOf(BrandPalettes.HermesDark)
    var activePalette: BrandPalette
        get() = activePaletteState.value
        set(value) { activePaletteState.value = value }

    val Ink: Color get() = activePalette.ink
    val Paper: Color get() = activePalette.paper
    val Muted: Color get() = activePalette.muted
    val Dim: Color get() = activePalette.dim
    val Background: Color get() = activePalette.background
    val Navy: Color get() = activePalette.navy
    val Navy2: Color get() = activePalette.navy2
    val Navy3: Color get() = activePalette.navy3
    val Relay: Color get() = activePalette.relay
    val Purple: Color get() = activePalette.purple
    val Electric: Color get() = activePalette.electric

    /** Softened Electric for large filled surfaces (e.g. the active connection card). */
    val ElectricMuted: Color get() = activePalette.electricMuted
    val Cyan: Color get() = activePalette.cyan
    val Green: Color get() = activePalette.green
    val Amber: Color get() = activePalette.amber
    val Danger: Color get() = activePalette.danger
    val Line: Color get() = activePalette.line
    val LineStrong: Color get() = activePalette.lineStrong

    // Non-color layout tokens are theme-independent (for now).
    val CardRadius = 8.dp
    val Mono = FontFamily.Monospace
}

fun Modifier.relayPanel(
    shape: Shape = RoundedCornerShape(RelayRefresh.CardRadius),
    background: Color = RelayRefresh.Navy2.copy(alpha = 0.78f),
    borderColor: Color = RelayRefresh.Line,
): Modifier = this
    .background(background, shape)
    .border(1.dp, borderColor, shape)

fun Modifier.relaySelectedPanel(
    shape: Shape = RoundedCornerShape(RelayRefresh.CardRadius),
): Modifier = this
    .background(
        Brush.linearGradient(
            listOf(
                RelayRefresh.Electric.copy(alpha = 0.52f),
                RelayRefresh.Purple.copy(alpha = 0.18f),
            ),
        ),
        shape,
    )
    .border(1.dp, RelayRefresh.Electric.copy(alpha = 0.72f), shape)

fun Modifier.relayGridTexture(
    grid: Dp = 42.dp,
    dot: Dp = 10.dp,
    alpha: Float = 0.18f,
): Modifier = drawBehind {
    val gridPx = grid.toPx().coerceAtLeast(1f)
    val dotPx = dot.toPx().coerceAtLeast(1f)
    var x = 0f
    while (x <= size.width) {
        drawLine(
            color = Color.White.copy(alpha = 0.018f * alpha * 5f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f,
        )
        x += gridPx
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(
            color = Color.White.copy(alpha = 0.026f * alpha * 5f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
        y += gridPx
    }
    var dy = 0f
    while (dy <= size.height) {
        var dx = 0f
        while (dx <= size.width) {
            drawCircle(
                color = RelayRefresh.Relay.copy(alpha = 0.16f * alpha * 5f),
                radius = 1.15f,
                center = Offset(dx + 1f, dy + 1f),
            )
            dx += dotPx
        }
        dy += dotPx
    }
}

@Composable
fun RelayTextureBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .background(RelayRefresh.Background)
            .relayGridTexture(alpha = 0.18f),
        content = content,
    )
}

@Composable
fun RelayDottedOverlay(
    modifier: Modifier = Modifier,
    alpha: Float = 0.16f,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val step = 10.dp.toPx()
        var y = 0f
        while (y <= size.height) {
            var x = 0f
            while (x <= size.width) {
                drawCircle(
                    color = RelayRefresh.Relay.copy(alpha = alpha),
                    radius = 1.1f,
                    center = Offset(x + 1f, y + 1f),
                )
                x += step
            }
            y += step
        }
    }
}

@Composable
fun relayMetadataStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontFamily = RelayRefresh.Mono,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )
