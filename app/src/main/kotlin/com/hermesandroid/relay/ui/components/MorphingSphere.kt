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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ASCII morphing sphere inspired by the Amp Code "Supernova" orb.
 *
 * Renders a sphere from monospace characters with:
 * - Noise-distorted perimeter (organic, blobby boundary)
 * - Glow/debris layer beyond the edge (characters bleed outward)
 * - Zone-based character density (core: @#%*+ / edge: =:- / glow: .:-)
 * - FBM procedural noise for organic motion (not simple sine waves)
 * - 3D diffuse lighting for depth
 * - Green ↔ purple color pulse
 *
 * Pure Compose Canvas — no OpenGL, no external noise libraries.
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

    // Characters ordered by visual density: space (empty) → @ (densest)
    val chars = " .:-=+*#%@"

    // Grid sized to fit sphere + glow region
    val cols = 58
    val rows = 34

    val paint = remember {
        Paint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
    }

    Canvas(modifier = modifier.fillMaxSize().clipToBounds()) {
        val canvasW = size.width
        val canvasH = size.height

        val cellW = canvasW / cols
        val cellH = canvasH / rows
        val charSize = (cellW * 0.95f).coerceAtMost(cellH * 0.85f)
        paint.textSize = charSize

        val cx = cols / 2f
        val cy = rows / 2f
        val charAspect = cellW / cellH

        // Sphere radius (smaller than grid to leave room for glow)
        val maxRadiusFromRows = (rows / 2f) * 0.72f
        val maxRadiusFromCols = (cols / 2f) * charAspect * 0.72f
        val baseRadius = minOf(maxRadiusFromRows, maxRadiusFromCols)

        val t = time

        // Color pulse: green ↔ purple
        val pulse = (sin(colorPhase) * 0.5f + 0.5f)
        val colR = lerp(0.25f, 0.61f, pulse)
        val colG = lerp(0.85f, 0.42f, pulse)
        val colB = lerp(0.40f, 0.94f, pulse)

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                // Position relative to center (aspect-corrected so sphere is circular)
                val dx = (col - cx) * charAspect
                val dy = (row - cy)
                val dist = sqrt(dx * dx + dy * dy)
                val angle = atan2(dy, dx)

                // --- Noise-distorted perimeter ---
                // Sample FBM at this angle to get organic radius variation.
                // The angle is scaled and offset by time so the distortion evolves.
                val perimeterNoise = fbm(
                    angle * 1.8f + t * 0.08f,
                    angle * 0.7f + t * 0.12f
                ) * 2f - 1f // remap 0..1 → -1..1
                val distortedRadius = baseRadius * (1f + perimeterNoise * 0.18f)

                // Glow extends 35% beyond the distorted edge
                val glowRadius = distortedRadius * 1.35f

                // How deep into the sphere (1 = center, 0 = edge, <0 = outside)
                val normalizedDist = dist / distortedRadius

                if (dist > glowRadius) continue // beyond glow — skip

                val px = col * cellW
                val py = row * cellH + cellH * 0.8f // baseline offset

                if (normalizedDist <= 1f) {
                    // ── INSIDE SPHERE ──────────────────────────────

                    // Approximate sphere normal for 3D lighting
                    val nx = dx / distortedRadius
                    val ny2 = dy / distortedRadius
                    val nzSq = (1f - nx * nx - ny2 * ny2).coerceAtLeast(0f)
                    val nz = sqrt(nzSq)

                    // Diffuse light from upper-right-front
                    val light = (nx * 0.3f + ny2 * (-0.4f) + nz * 0.86f)
                        .coerceIn(0f, 1f)
                    val brightness = light * 0.55f + 0.35f

                    // Noise-based character variation (organic, non-periodic)
                    val charNoise = fbm(
                        col * 0.25f + t * 0.18f,
                        row * 0.25f + t * 0.13f,
                        octaves = 2
                    ) * 0.3f - 0.15f

                    val charIdx = ((brightness + charNoise) * (chars.length - 1))
                        .toInt().coerceIn(1, chars.length - 1)
                    val ch = chars[charIdx]

                    // Smooth edge fade (wider transition zone for softer boundary)
                    val edgeFade = when {
                        normalizedDist > 0.85f -> {
                            val t2 = (normalizedDist - 0.85f) / 0.15f
                            1f - t2 * t2 // quadratic falloff
                        }
                        else -> 1f
                    }

                    val alpha = ((brightness * 0.55f + 0.4f) * edgeFade)
                        .coerceIn(0f, 0.95f)

                    // Intensity-based color: brighter regions are slightly more vivid
                    val intensityBoost = brightness * 0.2f
                    paint.color = android.graphics.Color.argb(
                        (alpha * 255).toInt().coerceIn(0, 255),
                        ((colR + intensityBoost) * 255).toInt().coerceIn(0, 255),
                        ((colG + intensityBoost * 0.5f) * 255).toInt().coerceIn(0, 255),
                        ((colB + intensityBoost) * 255).toInt().coerceIn(0, 255)
                    )

                    drawContext.canvas.nativeCanvas.drawText(ch.toString(), px, py, paint)

                } else {
                    // ── GLOW / DEBRIS ZONE ─────────────────────────
                    // Characters bleed past the sphere boundary, fading with distance

                    // How far past the edge (0 = at edge, 1 = at glow limit)
                    val glowT = (dist - distortedRadius) / (glowRadius - distortedRadius)
                    val glowFalloff = (1f - glowT).coerceIn(0f, 1f)

                    // Noise-driven sparsity: only render some debris positions
                    val sparsityNoise = fbm(
                        angle * 3.5f + t * 0.25f,
                        dist * 0.4f + t * 0.08f,
                        octaves = 2
                    )
                    // More sparse further from edge
                    val sparsityThreshold = 0.35f + glowT * 0.25f
                    if (sparsityNoise < sparsityThreshold) continue

                    // Debris uses light characters only
                    val debrisChars = "-:. "
                    val debrisIdx = ((1f - glowFalloff) * (debrisChars.length - 1))
                        .toInt().coerceIn(0, debrisChars.length - 1)
                    val ch = debrisChars[debrisIdx]
                    if (ch == ' ') continue

                    // Exponential falloff for glow alpha
                    val alpha = glowFalloff * glowFalloff * 0.55f

                    paint.color = android.graphics.Color.argb(
                        (alpha * 255).toInt().coerceIn(0, 255),
                        (colR * 255).toInt().coerceIn(0, 255),
                        (colG * 255).toInt().coerceIn(0, 255),
                        (colB * 255).toInt().coerceIn(0, 255)
                    )

                    drawContext.canvas.nativeCanvas.drawText(ch.toString(), px, py, paint)
                }
            }
        }
    }
}

// ── Procedural noise ─────────────────────────────────────────────────
// Hash-based value noise with FBM layering. Lightweight alternative to
// OpenSimplex — no lookup tables, no external libraries. The hash gives
// pseudo-random values per integer lattice point, smoothNoise interpolates
// between them with a smooth-step curve, and fbm layers multiple octaves
// for fractal detail.

/** Integer lattice hash → pseudo-random float in 0..1 */
private fun hash(x: Int, y: Int): Float {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) / 2147483647f
}

/** Smooth interpolated noise at any 2D point */
private fun smoothNoise(x: Float, y: Float): Float {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val xf = x - xi
    val yf = y - yi
    // Hermite smooth-step for artifact-free interpolation
    val u = xf * xf * (3f - 2f * xf)
    val v = yf * yf * (3f - 2f * yf)

    val n00 = hash(xi, yi)
    val n10 = hash(xi + 1, yi)
    val n01 = hash(xi, yi + 1)
    val n11 = hash(xi + 1, yi + 1)

    return lerp(
        lerp(n00, n10, u),
        lerp(n01, n11, u),
        v
    )
}

/** Fractal Brownian Motion — layers noise at increasing frequency/decreasing amplitude */
private fun fbm(x: Float, y: Float, octaves: Int = 3): Float {
    var value = 0f
    var amplitude = 0.5f
    var frequency = 1f
    for (i in 0 until octaves) {
        value += amplitude * smoothNoise(x * frequency, y * frequency)
        amplitude *= 0.5f
        frequency *= 2f
    }
    return value
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
