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

object RelayRefresh {
    val Ink = Color(0xFFF7F6F0)
    val Paper = Color(0xFFF7F3EA)
    val Muted = Color(0xFFA7A4B7)
    val Dim = Color(0xFF68647D)
    val Background = Color(0xFF08090D)
    val Navy = Color(0xFF121426)
    val Navy2 = Color(0xFF191B31)
    val Navy3 = Color(0xFF22243C)
    val Relay = Color(0xFFAEBFFF)
    val Purple = Color(0xFF8C5CFF)
    // Brand blue. Deepened from the original neon 0xFF111DFF (2026-06-16
    // feedback: the default blue read too bright next to the calmer tab
    // chrome) to a richer cobalt that sits closer to the chat/manage/bridge
    // mode-strip accent.
    val Electric = Color(0xFF0E18D6)

    /**
     * Softened Electric for large filled surfaces (e.g. the active
     * connection card). Full-strength Electric stays for small accents and
     * the alpha-blended selected panels, where the saturation reads as brand
     * rather than glare — 2026-06-10/11 feedback: the blue was right
     * everywhere except as a full-card fill against body text.
     */
    val ElectricMuted = Color(0xFF4F5BD5)
    val Cyan = Color(0xFF6BDCFF)
    val Green = Color(0xFF58D36F)
    val Amber = Color(0xFFF2B14B)
    val Danger = Color(0xFFFF6B78)
    val Line = Color(0x24F7F6F0)
    val LineStrong = Color(0x47F7F6F0)
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
