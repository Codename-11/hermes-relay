package com.hermesandroid.relay.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ASCII art sphere inspired by the AMP Code CLI.
 *
 * Renders rows of monospace characters (`. : - = + * # % @`) arranged
 * in a sphere shape. Characters cycle organically over time and the
 * sphere boundary subtly morphs. Pure Compose Canvas — no OpenGL.
 */
@Composable
fun MorphingSphere(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "sphere")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_000_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    // Slow color pulse: green → purple → green
    val colorPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2832f, // 2π
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "colorPulse"
    )

    val density = " .:-=+*#%@"
    // Monospace chars are ~1.8x taller than wide. Use more columns to compensate
    // so the sphere appears circular, not egg-shaped.
    val cols = 52
    val rows = 30

    val paint = remember {
        Paint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
    }

    Canvas(modifier = modifier.fillMaxSize().clipToBounds()) {
        val canvasW = size.width
        val canvasH = size.height

        // Size each character cell to fit the grid centered in the canvas
        val cellW = canvasW / cols
        val cellH = canvasH / rows
        // Size characters to fill cells tightly — less gap between rows
        val charSize = (cellW * 0.95f).coerceAtMost(cellH * 0.85f)
        paint.textSize = charSize

        // Sphere is centered in the grid
        val cx = cols / 2f
        val cy = rows / 2f

        // Character cell aspect ratio (width / height) — monospace chars are tall
        val charAspect = cellW / cellH

        // Radius in row-units (vertical). The sphere is measured in rows,
        // and column-extent is scaled by charAspect to make it circular on screen.
        val baseRadius = (rows / 2f) * 0.85f

        // Color pulse: interpolate green → purple
        val pulse = (sin(colorPhase) * 0.5f + 0.5f) // 0..1
        val r = lerp(0.25f, 0.61f, pulse) // green→purple R
        val g = lerp(0.85f, 0.42f, pulse) // green→purple G
        val b = lerp(0.40f, 0.94f, pulse) // green→purple B

        val t = time

        for (row in 0 until rows) {
            val ny = (row - cy) / baseRadius // normalized Y: -1..1

            // Sphere radius at this Y slice (circle equation)
            val sliceRadiusSq = 1f - ny * ny
            if (sliceRadiusSq <= 0f) continue
            val sliceRadius = sqrt(sliceRadiusSq)

            // Subtle boundary morph per row
            val morph = 1f + 0.03f * sin(t * 0.5f + row * 0.4f) +
                         0.02f * sin(t * 0.3f + row * 0.7f)

            // Slice radius in column-units, corrected for character aspect ratio
            val sliceCols = sliceRadius * baseRadius * morph / charAspect

            for (col in 0 until cols) {
                val dx = col - cx // distance from center in columns
                if (kotlin.math.abs(dx) > sliceCols) continue

                // Normalized X on sphere surface (corrected for aspect)
                val nxNorm = dx * charAspect / baseRadius
                // Approximate Z from sphere surface: z = sqrt(1 - x² - y²)
                val zSq = 1f - nxNorm * nxNorm - ny * ny
                val nz = if (zSq > 0f) sqrt(zSq) else 0f

                // Simple diffuse lighting (light from upper-right-front)
                val lightDot = (nxNorm * 0.3f + ny * (-0.4f) + nz * 0.86f)
                    .coerceIn(0f, 1f)

                // Map brightness to ASCII density (boosted for denser fill)
                val brightness = lightDot * 0.6f + 0.3f // higher ambient = denser characters

                // Time-based character cycling: shift the character selection
                val charNoise = sin(t * 0.4f + col * 1.3f + row * 0.9f) * 0.12f +
                                sin(t * 0.25f + col * 0.7f - row * 1.1f) * 0.08f
                val charIndex = ((brightness + charNoise) * (density.length - 1))
                    .toInt().coerceIn(0, density.length - 1)

                val ch = density[charIndex]
                if (ch == ' ') continue

                // Position on canvas
                val px = col * cellW
                val py = row * cellH + cellH * 0.8f // baseline offset

                // Brightness-based alpha: brighter chars more opaque
                val alpha = (brightness * 0.6f + 0.35f).coerceIn(0.3f, 0.95f)

                // Edge fade: only the very outermost characters fade slightly
                val edgeDist = kotlin.math.abs(dx) / sliceCols
                val edgeFade = if (edgeDist > 0.92f) 0.5f + 0.5f * (1f - (edgeDist - 0.92f) / 0.08f) else 1f

                paint.color = android.graphics.Color.argb(
                    (alpha * edgeFade * 255).toInt().coerceIn(0, 255),
                    (r * 255).toInt(),
                    (g * 255).toInt(),
                    (b * 255).toInt()
                )

                drawContext.canvas.nativeCanvas.drawText(
                    ch.toString(), px, py, paint
                )
            }
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
