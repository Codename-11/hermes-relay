package com.hermesandroid.relay.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Brand colors for glow effects
private val GlowPurple = Color(0xFF9B6BF0)
private val GlowPurpleDark = Color(0xFF6B35E8)
private val NavySurface = Color(0xFF1E1E34)
private val NavyDeep = Color(0xFF12121E)

/**
 * Purple glow behind an element. Uses a radial gradient from purple to transparent.
 * Only applies in dark theme — in light theme this is a no-op.
 */
fun Modifier.purpleGlow(
    radius: Dp = 20.dp,
    alpha: Float = 0.3f,
    isDarkTheme: Boolean = true
): Modifier {
    if (!isDarkTheme) return this
    return this.drawBehind {
        val radiusPx = radius.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    GlowPurple.copy(alpha = alpha),
                    GlowPurpleDark.copy(alpha = alpha * 0.4f),
                    Color.Transparent
                ),
                center = center,
                radius = radiusPx
            ),
            radius = radiusPx,
            center = center
        )
    }
}

/**
 * Gradient border on a card/surface. Uses a linear gradient from light purple to dark purple.
 * Only applies in dark theme — in light theme this is a no-op.
 */
fun Modifier.gradientBorder(
    width: Dp = 1.dp,
    shape: Shape = RoundedCornerShape(16.dp),
    colors: List<Color> = listOf(GlowPurple, GlowPurpleDark),
    isDarkTheme: Boolean = true
): Modifier {
    if (!isDarkTheme) return this
    return this.border(
        width = width,
        brush = Brush.linearGradient(colors),
        shape = shape
    )
}

/**
 * Radial background gradient — dark center, darker edges.
 * Provides subtle depth instead of flat color.
 * Only applies in dark theme — in light theme this is a no-op.
 */
fun Modifier.radialNavyBackground(
    isDarkTheme: Boolean = true
): Modifier {
    if (!isDarkTheme) return this
    return this.drawBehind {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(NavySurface, NavyDeep),
                center = Offset(size.width / 2f, size.height / 3f),
                radius = size.maxDimension * 0.8f
            )
        )
    }
}

/**
 * Small purple glow on the left edge of an element (used for assistant bubbles).
 * Only applies in dark theme.
 */
fun Modifier.leftEdgeGlow(
    alpha: Float = 0.15f,
    width: Dp = 24.dp,
    isDarkTheme: Boolean = true
): Modifier {
    if (!isDarkTheme) return this
    return this.drawBehind {
        val widthPx = width.toPx()
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    GlowPurple.copy(alpha = alpha),
                    Color.Transparent
                ),
                startX = 0f,
                endX = widthPx
            )
        )
    }
}

/**
 * Composable helper to check dark theme and apply glow modifiers.
 * Use this in @Composable contexts where isSystemInDarkTheme() is available.
 */
@Composable
fun Modifier.purpleGlowAuto(
    radius: Dp = 20.dp,
    alpha: Float = 0.3f
): Modifier = purpleGlow(radius = radius, alpha = alpha, isDarkTheme = isSystemInDarkTheme())

@Composable
fun Modifier.gradientBorderAuto(
    width: Dp = 1.dp,
    shape: Shape = RoundedCornerShape(16.dp),
    colors: List<Color> = listOf(GlowPurple, GlowPurpleDark)
): Modifier = gradientBorder(width = width, shape = shape, colors = colors, isDarkTheme = isSystemInDarkTheme())

@Composable
fun Modifier.radialNavyBackgroundAuto(): Modifier = radialNavyBackground(isDarkTheme = isSystemInDarkTheme())

@Composable
fun Modifier.leftEdgeGlowAuto(
    alpha: Float = 0.15f,
    width: Dp = 24.dp
): Modifier = leftEdgeGlow(alpha = alpha, width = width, isDarkTheme = isSystemInDarkTheme())
