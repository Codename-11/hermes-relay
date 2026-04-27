package com.axiomlabs.hermesrelay.ui.sphere

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure, platform-agnostic core of the ASCII morphing sphere.
 *
 * No Android, no Compose — only `kotlin.math`. Intended as the single source of
 * truth for the sphere algorithm. Android renders via Compose Canvas; a JS port
 * in `preview/web/` mirrors this file for browser iteration; future renderers
 * (Compose Desktop, terminal TUI) can call this same core.
 */

/** Agent visual state — controls animation parameters and color palette. */
enum class SphereState {
    Idle,
    Thinking,
    Streaming,
    Listening,
    Speaking,
    Error
}

/** Animated parameter bundle — interpolated by the caller for smooth state transitions. */
data class SphereParams(
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

/** Two color poles mixed by a time-varying phase. */
data class SphereColors(
    val r1: Float, val g1: Float, val b1: Float,
    val r2: Float, val g2: Float, val b2: Float
)

fun paramsFor(state: SphereState): SphereParams = when (state) {
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
    SphereState.Listening -> SphereParams(
        breatheSpeed = 0.55f, breatheAmp = 0.035f,
        lightSpeedX = 0.22f, lightSpeedY = 0.16f, lightInfluence = 0.38f,
        coreTightness = 0.78f, turbulenceAmp = 0.05f,
        rippleScale = 0.9f, heartbeatSpeed = 1.2f, radialFlowSpeed = 0.18f
    )
    SphereState.Speaking -> SphereParams(
        breatheSpeed = 0.45f, breatheAmp = 0.05f,
        lightSpeedX = 0.20f, lightSpeedY = 0.14f, lightInfluence = 0.30f,
        coreTightness = 0.55f, turbulenceAmp = 0.07f,
        rippleScale = 1.8f, heartbeatSpeed = 1.8f, radialFlowSpeed = 0.45f
    )
    SphereState.Error -> SphereParams(
        breatheSpeed = 1.2f, breatheAmp = 0.03f,
        lightSpeedX = 0.7f, lightSpeedY = 0.6f, lightInfluence = 0.40f,
        coreTightness = 0.80f, turbulenceAmp = 0.15f,
        rippleScale = 0.5f, heartbeatSpeed = 6.0f, radialFlowSpeed = 0.3f
    )
}

fun colorsFor(state: SphereState): SphereColors = when (state) {
    SphereState.Idle -> SphereColors(
        0.25f, 0.85f, 0.40f,
        0.61f, 0.42f, 0.94f
    )
    SphereState.Thinking -> SphereColors(
        0.30f, 0.55f, 0.95f,
        0.55f, 0.35f, 0.90f
    )
    SphereState.Streaming -> SphereColors(
        0.20f, 0.90f, 0.50f,
        0.25f, 0.80f, 0.85f
    )
    SphereState.Listening -> SphereColors(
        0.35f, 0.55f, 0.95f,
        0.65f, 0.45f, 0.95f
    )
    SphereState.Speaking -> SphereColors(
        0.25f, 0.92f, 0.55f,
        0.30f, 0.85f, 0.88f
    )
    SphereState.Error -> SphereColors(
        0.90f, 0.30f, 0.25f,
        0.85f, 0.50f, 0.20f
    )
}

/** All inputs needed to render one frame — populated by the caller from animation state. */
data class SphereFrame(
    val cols: Int,
    val rows: Int,
    val charAspect: Float,
    val state: SphereState,
    val time: Float,
    val colorPhase: Float,
    // Animated base params (caller smooths transitions)
    val breatheSpeed: Float,
    val breatheAmp: Float,
    val lightSpeedX: Float,
    val lightSpeedY: Float,
    val lightInfluence: Float,
    val coreTightness: Float,
    val turbulenceAmp: Float,
    val rippleScale: Float,
    val heartbeatSpeed: Float,
    val radialFlowSpeed: Float,
    // Animated colors
    val cr1: Float, val cg1: Float, val cb1: Float,
    val cr2: Float, val cg2: Float, val cb2: Float,
    // Intensity + modulation
    val intensity: Float,
    val toolCallBurst: Float,
    val voiceAmplitude: Float,
    val voiceMode: Boolean,
    val voiceRadiusScale: Float,
    // Gaze bias — lets callers aim the sphere's "eye" (bright spot) at a
    // specific direction without moving the sphere body. `lightAngleBlend`
    // blends between natural rotation (0) and the bias override (1).
    // Defaults keep the original behavior for every existing caller.
    val lightAngleBiasX: Float = 0f,
    val lightAngleBiasY: Float = 0f,
    val lightAngleBlend: Float = 0f,
    // Shadow contrast — darkens distBrightness on the hemisphere facing away
    // from the light, making the bright spot ("eye") clearly distinguishable
    // from the shadow side. 0 = uniform pearl shading (legacy behavior, full
    // parity preserved); 1 = shadow side fully uses directionalLight to scale
    // the distBrightness (strong Lambertian contrast).
    val shadowStrength: Float = 0f
)

/** What to draw at one grid cell. RGB + alpha in 0..1. */
data class SphereCell(
    val col: Int,
    val row: Int,
    val char: Char,
    val r: Float,
    val g: Float,
    val b: Float,
    val alpha: Float
)

private val charSets = arrayOf(
    " ·:;=+*#%@",
    " .:;=+*#%@",
    " ·;:+=*%#@",
    " .:;+=*#@%"
)
private const val dataChars = "01<>[]{}|/\\~^"

/**
 * Iterates the `rows × cols` grid for one frame and invokes `onCell` for every
 * cell that should be drawn. Cells outside the drawable region (or excluded by
 * sparsity) are silently skipped — the callback sees only visible glyphs.
 */
fun forEachSphereCell(frame: SphereFrame, onCell: (SphereCell) -> Unit) {
    val amp = frame.voiceAmplitude.coerceIn(0f, 1f)

    // ── Voice modulation of animated base params ─────────────────
    val breatheSpeed = when (frame.state) {
        SphereState.Listening -> lerp(frame.breatheSpeed, frame.breatheSpeed * 1.3f, amp * 0.5f)
        SphereState.Speaking -> lerp(frame.breatheSpeed, frame.breatheSpeed * 2.0f, amp)
        else -> frame.breatheSpeed
    }
    val turbulenceAmp = when (frame.state) {
        SphereState.Listening -> frame.turbulenceAmp + amp * 0.15f
        SphereState.Speaking -> frame.turbulenceAmp + amp * 0.5f
        else -> frame.turbulenceAmp
    }
    val coreWarmth = when (frame.state) {
        SphereState.Speaking -> lerp(0.30f, 1.0f, amp)
        else -> 0.30f
    }
    val wobbleAmplitude = when (frame.state) {
        SphereState.Listening -> 0.06f * (1f + amp * 0.3f)
        SphereState.Speaking -> 0.06f * (1f + amp * 0.8f)
        else -> 0.06f
    }
    val dataRingSpeed = when (frame.state) {
        SphereState.Speaking -> 0.4f * (1f + amp * 3f)
        else -> 0.4f
    }

    val cx = frame.cols / 2f
    val cy = frame.rows / 2f
    val charAspect = frame.charAspect

    // Matches legacy 0.60 envelope so the data ring (1.55×) fits the grid.
    val maxRadiusFromRows = (frame.rows / 2f) * 0.60f
    val maxRadiusFromCols = (frame.cols / 2f) * charAspect * 0.60f
    val baseRadius = minOf(maxRadiusFromRows, maxRadiusFromCols) * frame.voiceRadiusScale
    val t = frame.time

    val breathe = sin(t * breatheSpeed) * frame.breatheAmp
    val breathingRadius = baseRadius * (1f + breathe)

    val noiseJitter1 = fbm(t * 0.05f + 7.3f, 1.7f) * 0.5f
    val noiseJitter2 = fbm(3.1f, t * 0.04f + 13.7f) * 0.5f
    val naturalAngle1 = t * frame.lightSpeedX + noiseJitter1
    val naturalAngle2 = t * frame.lightSpeedY + noiseJitter2
    val blend = frame.lightAngleBlend.coerceIn(0f, 1f)
    val lightAngle1 = naturalAngle1 * (1f - blend) + frame.lightAngleBiasX * blend
    val lightAngle2 = naturalAngle2 * (1f - blend) + frame.lightAngleBiasY * blend
    val lx = sin(lightAngle1) * 0.65f
    val ly = cos(lightAngle2) * 0.65f
    val lz = sqrt((1f - lx * lx - ly * ly).coerceAtLeast(0.01f))

    val heartbeat = sin(t * frame.heartbeatSpeed) * 0.5f + 0.5f

    val pulse = sin(frame.colorPhase) * 0.5f + 0.5f
    val colR = lerp(frame.cr1, frame.cr2, pulse)
    val colG = lerp(frame.cg1, frame.cg2, pulse)
    val colB = lerp(frame.cb1, frame.cb2, pulse)

    val distWeight = 1f - frame.lightInfluence

    val effTurbulence = turbulenceAmp + frame.intensity * 0.04f + frame.toolCallBurst * 0.15f
    val effRadialFlow = frame.radialFlowSpeed + frame.intensity * 0.3f
    val effRipple = frame.rippleScale + frame.intensity * 0.5f + frame.toolCallBurst * 1.0f

    for (row in 0 until frame.rows) {
        for (col in 0 until frame.cols) {
            val dx = (col - cx) * charAspect
            val dy = (row - cy)
            val dist = sqrt(dx * dx + dy * dy)
            val angle = atan2(dy, dx)

            val perimeterNoise = fbm(
                angle * 1.8f + t * 0.08f,
                angle * 0.7f + t * 0.12f
            ) * 2f - 1f
            val distortedRadius = breathingRadius * (1f + perimeterNoise * wobbleAmplitude)
            val glowRadius = distortedRadius * 1.35f
            val dataRingInner = distortedRadius * 1.40f
            val dataRingOuter = distortedRadius * 1.55f
            val normDist = dist / distortedRadius

            if (dist > dataRingOuter) continue

            if (normDist <= 1f) {
                // ── INSIDE SPHERE ────────────────────────────
                val nx = dx / distortedRadius
                val ny2 = dy / distortedRadius
                val nzSq = (1f - nx * nx - ny2 * ny2).coerceAtLeast(0f)
                val nz = sqrt(nzSq)

                val distBrightness = (1f - normDist * normDist * frame.coreTightness)
                    .coerceAtLeast(0.15f)

                val directionalLight = (nx * lx + ny2 * ly + nz * lz)
                    .coerceIn(0f, 1f)

                val structural = fbm(
                    col * 0.25f + t * 0.18f,
                    row * 0.25f + t * 0.13f,
                    octaves = 2
                ) * 0.15f - 0.075f

                val turbulence = fbm(
                    col * 0.8f + t * 0.6f,
                    row * 0.8f + t * 0.45f,
                    octaves = 2
                ) * effTurbulence - effTurbulence * 0.5f

                val radialFlow = fbm(
                    angle * 2f + t * 0.15f,
                    dist * 0.3f - t * effRadialFlow,
                    octaves = 2
                ) * 0.06f - 0.03f

                val ripple = (
                    sin(normDist * 8f - t * 1.2f) * 0.04f * (1f - normDist) +
                        sin(normDist * 5f - t * 0.7f + 2f) * 0.03f * (1f - normDist)
                    ) * effRipple

                val heartbeatFx = heartbeat * 0.05f * (1f - normDist * normDist)

                // Shadow modulation — leave distBrightness alone on the lit
                // side (directionalLight=1 → factor=1), dim it on the shadow
                // side (directionalLight=0 → factor = 1 - shadowStrength).
                val shadowFactor = 1f - frame.shadowStrength * (1f - directionalLight)
                val brightness = distWeight * distBrightness * shadowFactor +
                    frame.lightInfluence * directionalLight +
                    heartbeatFx
                val charNoise = structural + turbulence + radialFlow + ripple

                val rotationPhase = (t * 0.3f + col * 0.17f + row * 0.13f).toInt()
                val chars = charSets[rotationPhase.and(3)]

                val charIdx = ((brightness + charNoise) * (chars.length - 1))
                    .toInt().coerceIn(1, chars.length - 1)
                val ch = chars[charIdx]

                val edgeFade = when {
                    normDist > 0.80f -> {
                        val ef = (normDist - 0.80f) / 0.20f
                        1f - ef * ef
                    }
                    else -> 1f
                }

                val scanline = if (row % 2 == 1) 0.82f else 1f
                val alpha = ((brightness * 0.4f + 0.6f) * edgeFade * scanline)
                    .coerceIn(0.1f, 1f)

                val warmth = (1f - normDist * normDist) * (coreWarmth * 0.40f)
                val lightBoost = directionalLight * 0.08f

                onCell(SphereCell(
                    col = col, row = row, char = ch,
                    r = (colR + lightBoost + warmth).coerceIn(0f, 1f),
                    g = (colG + lightBoost * 0.5f + warmth).coerceIn(0f, 1f),
                    b = (colB + lightBoost + warmth).coerceIn(0f, 1f),
                    alpha = alpha
                ))
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
                onCell(SphereCell(
                    col = col, row = row, char = ch,
                    r = colR.coerceIn(0f, 1f),
                    g = colG.coerceIn(0f, 1f),
                    b = colB.coerceIn(0f, 1f),
                    alpha = alpha
                ))
            } else if (dist >= dataRingInner) {
                // ── DATA RING ────────────────────────────────
                val ringT = (dist - dataRingInner) / (dataRingOuter - dataRingInner)
                val orbitAngle = angle - t * dataRingSpeed + ringT * 1.5f
                val ringNoise = fbm(
                    orbitAngle * 4f + t * 0.3f,
                    ringT * 3f + t * 0.15f,
                    octaves = 2
                )
                if (ringNoise < 0.55f) continue

                val dataIdx = ((orbitAngle * 2f + t * 0.5f) * dataChars.length)
                    .toInt().mod(dataChars.length)
                val ch = dataChars[dataIdx]

                val ringFade = (1f - ringT).coerceIn(0f, 1f)
                val alpha = ringFade * 0.65f
                onCell(SphereCell(
                    col = col, row = row, char = ch,
                    r = (colR * 0.85f).coerceIn(0f, 1f),
                    g = (colG * 0.85f).coerceIn(0f, 1f),
                    b = (colB * 0.85f).coerceIn(0f, 1f),
                    alpha = alpha
                ))
            }
        }
    }
}

// ── Procedural noise (public so renderers can share / verify behavior) ──

fun hash(x: Int, y: Int): Float {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) / 2147483647f
}

fun smoothNoise(x: Float, y: Float): Float {
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

fun fbm(x: Float, y: Float, octaves: Int = 3): Float {
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

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
