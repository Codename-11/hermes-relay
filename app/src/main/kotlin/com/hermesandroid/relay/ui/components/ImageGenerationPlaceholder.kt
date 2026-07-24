package com.hermesandroid.relay.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ToolCall
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

private const val IMAGE_GENERATION_TOOL = "image_generate"
private const val GRID_COLUMNS = 42
private const val GRID_ROWS = 24

internal fun ToolCall.showsImageGenerationPlaceholder(): Boolean =
    !isComplete && name.trim().lowercase() == IMAGE_GENERATION_TOOL

/**
 * Image generation is a user-visible result lifecycle, not generic tool
 * diagnostics. Keep its active canvas visible even when upstream
 * `display.tool_progress` hides ordinary tool cards.
 */
internal fun ToolCall.isVisibleForToolDisplay(toolDisplay: String): Boolean =
    toolDisplay != "off" || showsImageGenerationPlaceholder()

/**
 * Theme-aware latent diffusion preview for an active Hermes image-generation
 * tool. It specializes the generic tool lifecycle already emitted by vanilla
 * Hermes; no Relay-only protocol or server patch is required.
 */
@Composable
fun ImageGenerationPlaceholder(
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.image_generation_rendering)
    val transition = rememberInfiniteTransition(label = "imageGenerationDiffusion")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "imageGenerationDiffusionPhase",
    )
    val background = MaterialTheme.colorScheme.surfaceVariant
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Box(
        modifier = modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        DiffusionCanvas(
            phase = phase,
            background = background,
            foreground = foreground,
            primary = primary,
            tertiary = tertiary,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )
    }
}

@Composable
private fun DiffusionCanvas(
    phase: Float,
    background: Color,
    foreground: Color,
    primary: Color,
    tertiary: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawRect(background)
        val cellWidth = size.width / GRID_COLUMNS
        val cellHeight = size.height / GRID_ROWS
        val denoise = diffusionDenoise(phase)
        val time = phase * 9f

        repeat(GRID_ROWS) { row ->
            repeat(GRID_COLUMNS) { column ->
                val signal = diffusionSignal(column, row, time, denoise)
                if (signal < 0.2f) return@repeat

                val x = column * cellWidth + cellWidth * 0.5f
                val y = row * cellHeight + cellHeight * 0.5f
                val radius = (cellWidth.coerceAtMost(cellHeight) * (0.14f + signal * 0.24f))
                    .coerceAtLeast(0.7.dp.toPx())
                val warmMix = ((signal - 0.35f) / 0.65f).coerceIn(0f, 1f)
                val base = lerpColor(foreground, primary, warmMix)
                val color = lerpColor(base, tertiary, hash01(column + 17, row - 11) * 0.32f)

                drawRoundRect(
                    color = color.copy(alpha = (0.08f + signal * 0.76f).coerceAtMost(0.84f)),
                    topLeft = Offset(x - radius, y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * 0.45f),
                )
            }
        }
    }
}

internal fun diffusionDenoise(phase: Float): Float {
    val normalized = phase - floor(phase)
    return if (normalized < 0.82f) {
        smoothstep(0.02f, 0.82f, normalized)
    } else {
        1f - smoothstep(0.82f, 1f, normalized)
    }
}

internal fun diffusionSignal(column: Int, row: Int, time: Float, denoise: Float): Float {
    val nx = (column + 0.5f) / GRID_COLUMNS - 0.52f
    val ny = (row + 0.5f) / GRID_ROWS - 0.5f
    val radius = kotlin.math.sqrt(nx * nx * 1.35f + ny * ny)
    val bloom = (1f - radius * 2.35f).coerceIn(0f, 1f)
    val ring = (1f - abs(radius - (0.23f + sin(time * 0.44f) * 0.025f)) * 15f)
        .coerceIn(0f, 1f)
    val latent = (bloom * 0.75f + ring * 0.5f).coerceIn(0f, 1f)
    val staticNoise = hash01(column + floor(time * 3f).toInt() * 19, row - floor(time * 3f).toInt() * 11)
    val livingNoise = hash01(column + floor(time * 7f).toInt(), row + floor(time * 5f).toInt())
    return (
        staticNoise * (1f - denoise) +
            latent * denoise +
            (livingNoise - 0.5f) * (0.42f - denoise * 0.2f)
        ).coerceIn(0f, 1f)
}

private fun hash01(x: Int, y: Int): Float {
    val value = sin(x * 127.1 + y * 311.7) * 43_758.5453
    return (value - floor(value)).toFloat()
}

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpColor(from: Color, to: Color, amount: Float): Color = Color(
    red = from.red + (to.red - from.red) * amount,
    green = from.green + (to.green - from.green) * amount,
    blue = from.blue + (to.blue - from.blue) * amount,
    alpha = from.alpha + (to.alpha - from.alpha) * amount,
)
