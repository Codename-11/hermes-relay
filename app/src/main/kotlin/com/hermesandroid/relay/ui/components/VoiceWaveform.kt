package com.hermesandroid.relay.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.viewmodel.VoiceState
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Reactive layered-sine-wave visualizer.
 *
 * Mounted below the [MorphingSphere] in voice-mode overlay. Renders three
 * overlapping sine waves at different frequencies, coupled to the live mic
 * amplitude, and color-keyed to the current [VoiceState] so the waveform
 * shifts between the user's voice (soft blue/purple) and the agent's voice
 * (vivid green/teal) automatically.
 *
 * Design goals:
 *  - Siri / ChatGPT voice smooth, not a spiky equalizer
 *  - Subtle accent to the sphere, not a competing focal point
 *  - Color palette must exactly match MorphingSphere's Listening/Speaking
 *    states so sphere + waveform read as one cohesive element
 *  - Never fully disappears during silence — baseline ~10% envelope keeps
 *    the component alive so the UI doesn't flicker on/off with voice gaps
 *
 * Pure Compose Canvas — no external libraries.
 */

// Exact hex values from MorphingSphere palette (see colorsFor() in that file).
// Listening poles (cool soft blue/purple) and Speaking poles (vivid green/teal).
private val ListeningPrimary = Color(0xFF597EF2)   // soft blue
private val ListeningSecondary = Color(0xFFA573F2) // soft purple
private val SpeakingPrimary = Color(0xFF40EB8C)    // vivid green
private val SpeakingSecondary = Color(0xFF4DD9E0)  // teal

// Three sine layers. Base frequencies in "full-canvas-widths per cycle" units:
// wave 1 at 1.2 fits roughly one and a bit crests across the component, wave 3
// at 3.4 fits three-ish. Tuned by eye in the previews — they drift against
// each other because the phase accumulators each run on independent clocks.
private val baseFreqs = floatArrayOf(1.2f, 2.1f, 3.4f)

// Relative amplitude weight per layer. Wave 1 is the dominant shape, 2 and 3
// add detail and shimmer on top.
private val layerScales = floatArrayOf(1.0f, 0.70f, 0.40f)

// Relative saturation per layer. Back layers ride a little dimmer so the
// front layer reads as the primary contour.
private val layerAlphas = floatArrayOf(1.0f, 0.80f, 0.60f)

// Phase-cycle durations in millis at silence. Co-prime-ish so layers never
// phase-lock. The amplitude-driven phase driver multiplies velocity by up to
// PHASE_AMP_BOOST at amplitude=1, so at a full-throated speech peak wave 1
// does a cycle in ~570 ms — the characteristic Siri "surge."
private val phaseDurationsMs = intArrayOf(2000, 1400, 950)

// Multiplier applied to phase velocity at amplitude=1.0. 1 + amp*2.5 means
// silence runs at base tempo, normal speech (amp≈0.5) runs ~2.25× faster,
// loud speech (amp=1.0) runs 3.5× faster. This is what makes the wave feel
// like it's being pushed by your voice instead of drifting on its own clock.
private const val PHASE_AMP_BOOST = 2.5f

// Envelope math: each wave's max excursion is `amp * layerScale * (h * peak)`.
// `peak = 0.55` means at amplitude=1.0 the dominant wave reaches 55 % of the
// half-height — nearly the full canvas span minus stroke headroom.
private const val PEAK_FRACTION = 0.55f

// Minimum envelope at amplitude=0. Keeps the component faintly alive at
// silence so it doesn't collapse to a dead straight line between turns.
private const val MIN_ENVELOPE = 0.04f

// Sample one path vertex every N pixels. Lower = smoother but more work per
// frame. 2 px is plenty smooth at this stroke width.
private const val SAMPLE_STEP_PX = 2f

// Stroke width in dp.
private const val STROKE_WIDTH_DP = 2.5f

// Fraction of the canvas width devoted to each edge's alpha falloff. Conjure's
// pill overlay masks the outer ~15% of each side with an opaque gradient; we
// replicate it as an alpha mask here since we're sitting on a translucent
// overlay, not a solid pill. 0.12 = 12 % fade per side, 76 % solid middle.
private const val EDGE_FADE_FRACTION = 0.12f

@Composable
fun VoiceWaveform(
    amplitude: Float,
    state: VoiceState,
    modifier: Modifier = Modifier,
) {
    // No downstream smoothing. The VoiceViewModel already runs an
    // attack/release envelope follower on the recorder/player amplitude
    // flow, so we get an instantly-responsive-on-peaks / slow-fade-on-tail
    // signal here. Stacking a Compose spring on top would re-introduce the
    // very lag that made the waveform feel dead during the V2b round.
    val displayAmplitude = amplitude.coerceIn(0f, 1f)

    // State-driven color pair. animateColorAsState gives us a ~400ms crossfade
    // between palettes so transitioning from Listening → Thinking → Speaking
    // doesn't look abrupt.
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error

    val targetPrimary = when (state) {
        VoiceState.Idle -> dim.copy(alpha = 0.3f)
        VoiceState.Listening -> ListeningPrimary
        VoiceState.Transcribing -> ListeningPrimary.copy(alpha = 0.6f)
        VoiceState.Thinking -> dim.copy(alpha = 0.5f)
        VoiceState.Speaking -> SpeakingPrimary
        VoiceState.Error -> errorColor.copy(alpha = 0.8f)
    }
    val targetSecondary = when (state) {
        VoiceState.Idle -> dim.copy(alpha = 0.3f)
        VoiceState.Listening -> ListeningSecondary
        VoiceState.Transcribing -> dim.copy(alpha = 0.6f)
        VoiceState.Thinking -> dim.copy(alpha = 0.5f)
        VoiceState.Speaking -> SpeakingSecondary
        VoiceState.Error -> errorColor.copy(alpha = 0.8f)
    }

    val primaryColor by animateColorAsState(
        targetValue = targetPrimary,
        animationSpec = tween(durationMillis = 400),
        label = "wavePrimary",
    )
    val secondaryColor by animateColorAsState(
        targetValue = targetSecondary,
        animationSpec = tween(durationMillis = 400),
        label = "waveSecondary",
    )

    // Three phase accumulators driven by a single per-frame ticker. Phase
    // velocity scales with the current amplitude so the wave visibly surges
    // when the user speaks instead of running on its own fixed clock.
    val phases = rememberAmplitudeDrivenPhases(phaseDurationsMs, displayAmplitude)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val centerY = height / 2f
        val peakPixels = height * PEAK_FRACTION
        val strokePx = STROKE_WIDTH_DP.dp.toPx()

        // Sample every SAMPLE_STEP_PX pixels across the canvas. steps+1 vertices.
        val steps = max(2, (width / SAMPLE_STEP_PX).toInt())

        // Offscreen layer so we can mask the stroke alpha with DstIn and the
        // mask never bleeds onto whatever's underneath the waveform. Without
        // saveLayer the DstIn would punch a hole in the parent background.
        drawIntoCanvas { canvas ->
            canvas.saveLayer(
                bounds = androidx.compose.ui.geometry.Rect(0f, 0f, width, height),
                paint = androidx.compose.ui.graphics.Paint(),
            )

            // Draw back-to-front so wave 1 (front, full saturation) sits on top.
            for (layer in 2 downTo 0) {
                val freq = baseFreqs[layer]
                val phase = phases[layer]
                val layerScale = layerScales[layer]
                val layerAlpha = layerAlphas[layer]

                // Amplitude envelope for this layer. Uses the gain-curved
                // displayAmplitude so low speech levels still move the wave.
                // Baseline of MIN_ENVELOPE keeps the component faintly alive at
                // true silence (amplitude = 0).
                val rawEnvelope = displayAmplitude * layerScale * peakPixels
                val minEnvelope = MIN_ENVELOPE * peakPixels * layerScale
                val envelope = max(rawEnvelope, minEnvelope)

                val path = Path()
                path.moveTo(0f, centerY)
                for (i in 0..steps) {
                    val x = i * (width / steps)
                    val t = x / width
                    val angle = (t * freq * 2f * PI).toFloat() + phase
                    // Geometric tuck-in: sin(πt) goes 0 → 1 → 0 across the
                    // canvas so the sine excursion is forced to zero at both
                    // edges. This pulls both endpoints down to centerY and
                    // makes the wave form a natural pill/lens silhouette
                    // without any hard cut at the canvas boundary.
                    val taper = sin(PI.toFloat() * t)
                    val y = centerY + sin(angle) * envelope * taper
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        listOf(
                            primaryColor.copy(alpha = primaryColor.alpha * layerAlpha),
                            secondaryColor.copy(alpha = secondaryColor.alpha * layerAlpha),
                        ),
                    ),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }

            // Alpha-mask the edges. Conjure paints the pill background over
            // the outer 15 % of each side; on a translucent overlay we instead
            // modulate the stroke alpha directly with DstIn so only the wave
            // pixels get feathered — the parent background is untouched.
            // Belt-and-suspenders with the geometric tuck above: the mask
            // catches any stray stroke width near the edges that the taper
            // leaves behind.
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        EDGE_FADE_FRACTION to Color.Black,
                        1f - EDGE_FADE_FRACTION to Color.Black,
                        1f to Color.Transparent,
                    ),
                ),
                topLeft = Offset.Zero,
                size = size,
                blendMode = BlendMode.DstIn,
            )

            canvas.restore()
        }
    }
}

/**
 * Per-frame phase ticker for the three wave layers. Runs one [withFrameNanos]
 * loop and updates all three phases atomically so they can't tear against
 * each other. Phase velocity = base-omega × (1 + amplitude × [PHASE_AMP_BOOST]),
 * so the wave accelerates when the user speaks.
 */
@Composable
private fun rememberAmplitudeDrivenPhases(
    baseDurationsMs: IntArray,
    amplitude: Float,
): FloatArray {
    val ampRef = rememberUpdatedState(amplitude)
    var phases by remember { mutableStateOf(FloatArray(baseDurationsMs.size)) }
    LaunchedEffect(Unit) {
        val twoPi = (2f * PI).toFloat()
        // Precompute base angular velocities (rad/s) so we don't divide on
        // every frame. Each wave's full cycle at silence = durationMs.
        val baseOmega = FloatArray(baseDurationsMs.size) { i ->
            twoPi / (baseDurationsMs[i] / 1000f)
        }
        var prevNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (prevNanos == 0L) {
                    prevNanos = nanos
                    return@withFrameNanos
                }
                val dtSec = (nanos - prevNanos) / 1_000_000_000f
                prevNanos = nanos
                val boost = 1f + ampRef.value * PHASE_AMP_BOOST
                val current = phases
                val next = FloatArray(current.size)
                for (i in current.indices) {
                    var p = current[i] + baseOmega[i] * dtSec * boost
                    // Keep phase in [0, 2π) so sin() stays in its efficient
                    // range and the float doesn't drift to a large magnitude
                    // over long voice sessions.
                    if (p >= twoPi) p -= twoPi
                    next[i] = p
                }
                phases = next
            }
        }
    }
    return phases
}

// ── Previews ─────────────────────────────────────────────────────────

@Preview(
    name = "Idle",
    showBackground = true,
    backgroundColor = 0xFF0A0A0A,
    widthDp = 360,
    heightDp = 80,
)
@Composable
private fun VoiceWaveformIdlePreview() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        VoiceWaveform(
            amplitude = 0.0f,
            state = VoiceState.Idle,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(
    name = "Listening low",
    showBackground = true,
    backgroundColor = 0xFF0A0A0A,
    widthDp = 360,
    heightDp = 80,
)
@Composable
private fun VoiceWaveformListeningLowPreview() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        VoiceWaveform(
            amplitude = 0.25f,
            state = VoiceState.Listening,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(
    name = "Listening peak",
    showBackground = true,
    backgroundColor = 0xFF0A0A0A,
    widthDp = 360,
    heightDp = 80,
)
@Composable
private fun VoiceWaveformListeningPeakPreview() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        VoiceWaveform(
            amplitude = 0.9f,
            state = VoiceState.Listening,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(
    name = "Speaking peak",
    showBackground = true,
    backgroundColor = 0xFF0A0A0A,
    widthDp = 360,
    heightDp = 80,
)
@Composable
private fun VoiceWaveformSpeakingPeakPreview() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        VoiceWaveform(
            amplitude = 0.9f,
            state = VoiceState.Speaking,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(
    name = "Thinking",
    showBackground = true,
    backgroundColor = 0xFF0A0A0A,
    widthDp = 360,
    heightDp = 80,
)
@Composable
private fun VoiceWaveformThinkingPreview() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        VoiceWaveform(
            amplitude = 0.1f,
            state = VoiceState.Thinking,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(
    name = "Error",
    showBackground = true,
    backgroundColor = 0xFF0A0A0A,
    widthDp = 360,
    heightDp = 80,
)
@Composable
private fun VoiceWaveformErrorPreview() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        VoiceWaveform(
            amplitude = 0.3f,
            state = VoiceState.Error,
            modifier = Modifier.padding(16.dp),
        )
    }
}
