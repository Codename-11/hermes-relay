package com.hermesandroid.relay.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ASCII morphing sphere — the visual embodiment of the AI agent.
 *
 * Inspired by Amp Code's Supernova orb. Renders a sphere from monospace
 * characters with layered procedural effects driven by [SphereState]:
 *
 * - **Hybrid brightness**: concentric distance-based zones + orbiting directional
 *   light that shifts the highlight across the surface ("the eye")
 * - **Dual noise**: structural FBM (slow undulation) + turbulence (fast shimmer)
 * - **Breathing radius**: slow expand/contract
 * - **Core heartbeat**: brightness throb near center
 * - **Radial flow**: outward energy drift
 * - **Ripple waves**: concentric brightness rings radiating outward
 * - **State-driven colors**: palette shifts per agent state
 *
 * Pure Compose Canvas — no OpenGL, no external libraries.
 */

/** Agent visual state — controls animation parameters and color palette. */
enum class SphereState {
    /** Calm breathing, slow wandering eye, gentle ripples. Present, waiting. */
    Idle,
    /** Faster pulse, tighter core, rapid eye scanning. Processing. */
    Thinking,
    /** Energy radiates outward, strong ripples, focused eye. Speaking. */
    Streaming,
    /** Red shift, erratic motion. Something wrong. */
    Error
}

// ── State parameter system ───────────────────────────────────────────

private data class SphereParams(
    val breatheSpeed: Float,
    val breatheAmp: Float,
    val lightSpeedX: Float,
    val lightSpeedY: Float,
    val lightInfluence: Float,
    val coreTightness: Float,
    val turbulenceAmp: Float,
    val rippleScale: Float,
    val heartbeatSpeed: Float,
    val radialFlowSpeed: Float
)

private data class SphereColors(
    val r1: Float, val g1: Float, val b1: Float, // color pole 1
    val r2: Float, val g2: Float, val b2: Float  // color pole 2
)

private fun paramsFor(state: SphereState) = when (state) {
    SphereState.Idle -> SphereParams(
        breatheSpeed = 0.5f, breatheAmp = 0.04f,
        lightSpeedX = 0.25f, lightSpeedY = 0.18f, lightInfluence = 0.35f,
        coreTightness = 0.75f, turbulenceAmp = 0.06f,
        rippleScale = 1.0f, heartbeatSpeed = 1.0f, radialFlowSpeed = 0.2f
    )
    SphereState.Thinking -> SphereParams(
        breatheSpeed = 0.8f, breatheAmp = 0.02f,
        lightSpeedX = 0.5f, lightSpeedY = 0.35f, lightInfluence = 0.30f,
        coreTightness = 0.90f, turbulenceAmp = 0.12f,
        rippleScale = 1.5f, heartbeatSpeed = 4.0f, radialFlowSpeed = 0.1f
    )
    SphereState.Streaming -> SphereParams(
        breatheSpeed = 0.3f, breatheAmp = 0.06f,
        lightSpeedX = 0.15f, lightSpeedY = 0.10f, lightInfluence = 0.25f,
        coreTightness = 0.60f, turbulenceAmp = 0.08f,
        rippleScale = 2.0f, heartbeatSpeed = 1.5f, radialFlowSpeed = 0.5f
    )
    SphereState.Error -> SphereParams(
        breatheSpeed = 1.2f, breatheAmp = 0.03f,
        lightSpeedX = 0.7f, lightSpeedY = 0.6f, lightInfluence = 0.40f,
        coreTightness = 0.80f, turbulenceAmp = 0.15f,
        rippleScale = 0.5f, heartbeatSpeed = 6.0f, radialFlowSpeed = 0.3f
    )
}

private fun colorsFor(state: SphereState) = when (state) {
    SphereState.Idle -> SphereColors(
        0.25f, 0.85f, 0.40f,   // green
        0.61f, 0.42f, 0.94f    // purple
    )
    SphereState.Thinking -> SphereColors(
        0.30f, 0.55f, 0.95f,   // blue
        0.55f, 0.35f, 0.90f    // purple
    )
    SphereState.Streaming -> SphereColors(
        0.20f, 0.90f, 0.50f,   // green
        0.25f, 0.80f, 0.85f    // teal
    )
    SphereState.Error -> SphereColors(
        0.90f, 0.30f, 0.25f,   // red
        0.85f, 0.50f, 0.20f    // orange
    )
}

// ── Main composable ──────────────────────────────────────────────────

@Composable
fun MorphingSphere(
    modifier: Modifier = Modifier,
    state: SphereState = SphereState.Idle,
    intensity: Float = 0f,
    toolCallBurst: Float = 0f,
    fixedTime: Float? = null,
    fixedColorPhase: Float? = null
) {
    // ── Animated state parameters (smooth 800ms transitions) ─────
    val targetP = remember(state) { paramsFor(state) }
    val targetC = remember(state) { colorsFor(state) }
    val spec = tween<Float>(800, easing = FastOutSlowInEasing)

    val breatheSpeed by animateFloatAsState(targetP.breatheSpeed, spec, label = "bSpd")
    val breatheAmp by animateFloatAsState(targetP.breatheAmp, spec, label = "bAmp")
    val lightSpeedX by animateFloatAsState(targetP.lightSpeedX, spec, label = "lsX")
    val lightSpeedY by animateFloatAsState(targetP.lightSpeedY, spec, label = "lsY")
    val lightInfluence by animateFloatAsState(targetP.lightInfluence, spec, label = "lInf")
    val coreTightness by animateFloatAsState(targetP.coreTightness, spec, label = "core")
    val turbulenceAmp by animateFloatAsState(targetP.turbulenceAmp, spec, label = "turb")
    val rippleScale by animateFloatAsState(targetP.rippleScale, spec, label = "rip")
    val heartbeatSpeed by animateFloatAsState(targetP.heartbeatSpeed, spec, label = "hb")
    val radialFlowSpeed by animateFloatAsState(targetP.radialFlowSpeed, spec, label = "rf")

    val cr1 by animateFloatAsState(targetC.r1, spec, label = "cr1")
    val cg1 by animateFloatAsState(targetC.g1, spec, label = "cg1")
    val cb1 by animateFloatAsState(targetC.b1, spec, label = "cb1")
    val cr2 by animateFloatAsState(targetC.r2, spec, label = "cr2")
    val cg2 by animateFloatAsState(targetC.g2, spec, label = "cg2")
    val cb2 by animateFloatAsState(targetC.b2, spec, label = "cb2")

    // ── Continuous time animations ──────────────────────────────
    val transition = rememberInfiniteTransition(label = "sphere")
    val animatedTime by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_000_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )
    val animatedColorPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2832f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "colorPulse"
    )

    val time = fixedTime ?: animatedTime
    val colorPhase = fixedColorPhase ?: animatedColorPhase

    // Multiple character sets that rotate over time for surface "activity"
    val charSets = arrayOf(
        " ·:;=+*#%@",   // technical dots
        " .:;=+*#%@",   // classic with semicolons
        " ·;:+=*%#@",   // shuffled mid-range
        " .:;+=*#@%"    // variant ordering
    )
    // Data ring characters (orbit the sphere like processing data)
    val dataChars = "01<>[]{}|/\\~^"

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
        val charSize = (cellW * 1.3f).coerceAtMost(cellH * 1.1f)
        paint.textSize = charSize

        val cx = cols / 2f
        val cy = rows / 2f
        val charAspect = cellW / cellH

        // Reduced from 0.72 so data ring (1.55x) fits within grid
        val maxRadiusFromRows = (rows / 2f) * 0.60f
        val maxRadiusFromCols = (cols / 2f) * charAspect * 0.60f
        val baseRadius = minOf(maxRadiusFromRows, maxRadiusFromCols)
        val t = time

        // ── Breathing ────────────────────────────────────────────
        val breathe = sin(t * breatheSpeed) * breatheAmp
        val breathingRadius = baseRadius * (1f + breathe)

        // ── Orbiting directional light ("the eye") ──────────────
        // Lissajous orbit (different X/Y speeds) + noise jitter
        // for organic, non-repeating path. lx/ly at ±0.65 creates
        // strong enough asymmetry that the highlight visibly shifts.
        val noiseJitter1 = fbm(t * 0.05f + 7.3f, 1.7f) * 0.5f
        val noiseJitter2 = fbm(3.1f, t * 0.04f + 13.7f) * 0.5f
        val lightAngle1 = t * lightSpeedX + noiseJitter1
        val lightAngle2 = t * lightSpeedY + noiseJitter2
        val lx = sin(lightAngle1) * 0.65f
        val ly = cos(lightAngle2) * 0.65f
        val lz = sqrt((1f - lx * lx - ly * ly).coerceAtLeast(0.01f))

        // ── Core heartbeat ──────────────────────────────────────
        val heartbeat = sin(t * heartbeatSpeed) * 0.5f + 0.5f

        // ── Color palette (animated poles + phase oscillation) ───
        val pulse = sin(colorPhase) * 0.5f + 0.5f
        val colR = lerp(cr1, cr2, pulse)
        val colG = lerp(cg1, cg2, pulse)
        val colB = lerp(cb1, cb2, pulse)

        val distWeight = 1f - lightInfluence

        // Intensity/tool call modulation of state params
        val effTurbulence = turbulenceAmp + intensity * 0.04f + toolCallBurst * 0.15f
        val effRadialFlow = radialFlowSpeed + intensity * 0.3f
        val effRipple = rippleScale + intensity * 0.5f + toolCallBurst * 1.0f

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val dx = (col - cx) * charAspect
                val dy = (row - cy)
                val dist = sqrt(dx * dx + dy * dy)
                val angle = atan2(dy, dx)

                // ── Perimeter (subtle 6% wobble) ────────────────
                val perimeterNoise = fbm(
                    angle * 1.8f + t * 0.08f,
                    angle * 0.7f + t * 0.12f
                ) * 2f - 1f
                val distortedRadius = breathingRadius * (1f + perimeterNoise * 0.06f)
                val glowRadius = distortedRadius * 1.35f
                val dataRingInner = distortedRadius * 1.40f
                val dataRingOuter = distortedRadius * 1.55f
                val normDist = dist / distortedRadius

                if (dist > dataRingOuter) continue

                val px = col * cellW
                val py = row * cellH + cellH * 0.8f

                if (normDist <= 1f) {
                    // ── INSIDE SPHERE ────────────────────────────

                    // Surface normal
                    val nx = dx / distortedRadius
                    val ny2 = dy / distortedRadius
                    val nzSq = (1f - nx * nx - ny2 * ny2).coerceAtLeast(0f)
                    val nz = sqrt(nzSq)

                    // Distance-based brightness (concentric zones)
                    val distBrightness = (1f - normDist * normDist * coreTightness)
                        .coerceAtLeast(0.15f)

                    // Directional light (shifts highlight across surface)
                    val directionalLight = (nx * lx + ny2 * ly + nz * lz)
                        .coerceIn(0f, 1f)

                    // Structural noise (slow undulation)
                    val structural = fbm(
                        col * 0.25f + t * 0.18f,
                        row * 0.25f + t * 0.13f,
                        octaves = 2
                    ) * 0.15f - 0.075f

                    // Turbulence (fast shimmer, boosted by intensity + tool calls)
                    val turbulence = fbm(
                        col * 0.8f + t * 0.6f,
                        row * 0.8f + t * 0.45f,
                        octaves = 2
                    ) * effTurbulence - effTurbulence * 0.5f

                    // Radial flow (outward energy drift, faster when streaming)
                    val radialFlow = fbm(
                        angle * 2f + t * 0.15f,
                        dist * 0.3f - t * effRadialFlow,
                        octaves = 2
                    ) * 0.06f - 0.03f

                    // Ripple waves (stronger during streaming/tool calls)
                    val ripple = (
                        sin(normDist * 8f - t * 1.2f) * 0.04f * (1f - normDist) +
                        sin(normDist * 5f - t * 0.7f + 2f) * 0.03f * (1f - normDist)
                    ) * effRipple

                    // Core heartbeat (subtle glow, concentrated at center)
                    val heartbeatFx = heartbeat * 0.05f * (1f - normDist * normDist)

                    // ── Hybrid brightness ────────────────────────
                    val brightness = distWeight * distBrightness +
                        lightInfluence * directionalLight +
                        heartbeatFx
                    val charNoise = structural + turbulence + radialFlow + ripple

                    // Character rotation: cycle through char sets over time
                    // Each cell picks a set based on position + time, creating
                    // surface "activity" where characters shift independently
                    val rotationPhase = (t * 0.3f + col * 0.17f + row * 0.13f).toInt()
                    val chars = charSets[rotationPhase.and(3)] // mod 4 via bitmask

                    val charIdx = ((brightness + charNoise) * (chars.length - 1))
                        .toInt().coerceIn(1, chars.length - 1)
                    val ch = chars[charIdx]

                    // Edge fade (quadratic, starts at 0.80)
                    val edgeFade = when {
                        normDist > 0.80f -> {
                            val ef = (normDist - 0.80f) / 0.20f
                            1f - ef * ef
                        }
                        else -> 1f
                    }

                    // Scanline: dimming on odd rows (CRT/holographic feel)
                    val scanline = if (row % 2 == 1) 0.82f else 1f

                    val alpha = ((brightness * 0.4f + 0.6f) * edgeFade * scanline)
                        .coerceIn(0.1f, 1f)

                    // Core warmth (center bleeds towards white)
                    val warmth = (1f - normDist * normDist) * 0.12f
                    val lightBoost = directionalLight * 0.08f

                    paint.color = android.graphics.Color.argb(
                        (alpha * 255).toInt().coerceIn(0, 255),
                        ((colR + lightBoost + warmth) * 255).toInt().coerceIn(0, 255),
                        ((colG + lightBoost * 0.5f + warmth) * 255).toInt().coerceIn(0, 255),
                        ((colB + lightBoost + warmth) * 255).toInt().coerceIn(0, 255)
                    )

                    drawContext.canvas.nativeCanvas.drawText(ch.toString(), px, py, paint)

                } else if (dist <= glowRadius) {
                    // ── GLOW / DEBRIS ZONE ───────────────────────

                    val glowT = (dist - distortedRadius) / (glowRadius - distortedRadius)
                    val glowFalloff = (1f - glowT).coerceIn(0f, 1f)

                    val sparsityNoise = fbm(
                        angle * 3.5f + t * 0.25f,
                        dist * 0.4f + t * 0.08f,
                        octaves = 2
                    )
                    val sparsityThreshold = 0.35f + glowT * 0.25f
                    if (sparsityNoise < sparsityThreshold) continue

                    val debrisChars = "·:;- "
                    val debrisIdx = ((1f - glowFalloff) * (debrisChars.length - 1))
                        .toInt().coerceIn(0, debrisChars.length - 1)
                    val ch = debrisChars[debrisIdx]
                    if (ch == ' ') continue

                    val alpha = glowFalloff * 0.85f

                    paint.color = android.graphics.Color.argb(
                        (alpha * 255).toInt().coerceIn(0, 255),
                        (colR * 255).toInt().coerceIn(0, 255),
                        (colG * 255).toInt().coerceIn(0, 255),
                        (colB * 255).toInt().coerceIn(0, 255)
                    )

                    drawContext.canvas.nativeCanvas.drawText(ch.toString(), px, py, paint)

                } else if (dist >= dataRingInner) {
                    // ── DATA RING ────────────────────────────────
                    // Sparse orbiting characters like processing data.
                    // Angle offset by time = characters appear to orbit.

                    val ringT = (dist - dataRingInner) / (dataRingOuter - dataRingInner)

                    // Orbiting: offset angle by time (different layers at different speeds)
                    val orbitAngle = angle - t * 0.4f + ringT * 1.5f
                    // Sparsity: only render ~15% of ring positions
                    val ringNoise = fbm(
                        orbitAngle * 4f + t * 0.3f,
                        ringT * 3f + t * 0.15f,
                        octaves = 2
                    )
                    if (ringNoise < 0.55f) continue

                    // Pick character from data set, cycling with orbit
                    val dataIdx = ((orbitAngle * 2f + t * 0.5f) * dataChars.length)
                        .toInt().mod(dataChars.length)
                    val ch = dataChars[dataIdx]

                    // Fade: bright at inner edge, fading outward
                    val ringFade = (1f - ringT).coerceIn(0f, 1f)
                    val alpha = ringFade * 0.65f

                    paint.color = android.graphics.Color.argb(
                        (alpha * 255).toInt().coerceIn(0, 255),
                        (colR * 0.85f * 255).toInt().coerceIn(0, 255),
                        (colG * 0.85f * 255).toInt().coerceIn(0, 255),
                        (colB * 0.85f * 255).toInt().coerceIn(0, 255)
                    )

                    drawContext.canvas.nativeCanvas.drawText(ch.toString(), px, py, paint)
                }
            }
        }
    }
}

// ── Procedural noise ─────────────────────────────────────────────────

private fun hash(x: Int, y: Int): Float {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) / 2147483647f
}

private fun smoothNoise(x: Float, y: Float): Float {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val xf = x - xi
    val yf = y - yi
    val u = xf * xf * (3f - 2f * xf)
    val v = yf * yf * (3f - 2f * yf)
    val n00 = hash(xi, yi)
    val n10 = hash(xi + 1, yi)
    val n01 = hash(xi, yi + 1)
    val n11 = hash(xi + 1, yi + 1)
    return lerp(lerp(n00, n10, u), lerp(n01, n11, u), v)
}

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

// ── Previews ─────────────────────────────────────────────────────────

@Preview(name = "Idle", showBackground = true, backgroundColor = 0xFF0D0D0D, widthDp = 360, heightDp = 640)
@Composable
private fun PreviewIdle() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        MorphingSphere(Modifier.fillMaxSize(), state = SphereState.Idle, fixedTime = 5f, fixedColorPhase = 0.5f)
    }
}

@Preview(name = "Thinking", showBackground = true, backgroundColor = 0xFF0D0D0D, widthDp = 360, heightDp = 640)
@Composable
private fun PreviewThinking() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        MorphingSphere(Modifier.fillMaxSize(), state = SphereState.Thinking, fixedTime = 5f, fixedColorPhase = 1.5f)
    }
}

@Preview(name = "Streaming", showBackground = true, backgroundColor = 0xFF0D0D0D, widthDp = 360, heightDp = 640)
@Composable
private fun PreviewStreaming() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        MorphingSphere(Modifier.fillMaxSize(), state = SphereState.Streaming, fixedTime = 5f, fixedColorPhase = 2.5f)
    }
}

@Preview(name = "Error", showBackground = true, backgroundColor = 0xFF0D0D0D, widthDp = 360, heightDp = 640)
@Composable
private fun PreviewError() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        MorphingSphere(Modifier.fillMaxSize(), state = SphereState.Error, fixedTime = 5f, fixedColorPhase = 3.0f)
    }
}

@Preview(name = "Compact", showBackground = true, backgroundColor = 0xFF0D0D0D, widthDp = 200, heightDp = 200)
@Composable
private fun PreviewCompact() {
    Box(Modifier.size(200.dp).background(Color(0xFF0D0D0D))) {
        MorphingSphere(Modifier.fillMaxSize(), fixedTime = 8f, fixedColorPhase = 2.0f)
    }
}
