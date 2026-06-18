package com.hermesandroid.relay.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.LocalBrand

/**
 * ASCII morphing sphere — the visual embodiment of the AI agent.
 *
 * Inspired by Amp Code's Supernova orb. Renders a sphere from monospace
 * characters with layered procedural effects. Algorithm lives in
 * [forEachSphereCell] (see `MorphingSphereCore.kt`) so the same math powers
 * Android here, the JS browser preview in `preview/web/`, and any future
 * renderer (Compose Desktop, terminal TUI).
 *
 * This file is the Android/Compose renderer only — it owns animation state
 * (`animateFloatAsState` for state transitions, a throttled per-frame loop for
 * the continuous drift) and text drawing.
 */

// Continuous-drift speeds, matched to the legacy infinite-transition tweens so
// the orb moves at exactly the same pace as before:
//   time:  0..1000 over the old 1_000_000ms loop == 1.0 unit/sec
//   color: 0..2π   over the old 8_000ms loop      == 0.7854 rad/sec
private const val SPHERE_TIME_UNITS_PER_SEC = 1f
private const val SPHERE_TWO_PI = 6.2832f
private const val SPHERE_COLOR_RADIANS_PER_SEC = 0.7854f

// Idle cadence: this delay plus the next frame wait nets a ~33ms period (~30fps).
private const val SPHERE_IDLE_FRAME_INTERVAL_MS = 25L

@Composable
fun MorphingSphere(
    modifier: Modifier = Modifier,
    state: SphereState = SphereState.Idle,
    intensity: Float = 0f,
    toolCallBurst: Float = 0f,
    voiceAmplitude: Float = 0f,
    voiceMode: Boolean = false,
    skin: SphereSkin = LocalSphereSkin.current,
    fixedTime: Float? = null,
    fixedColorPhase: Float? = null
) {
    val brand = LocalBrand.current
    // Gate reactive inputs on what the skin declares it honors — this is the
    // "optional + detectable" reactivity contract. A skin that opts out of an
    // input renders as if that input were neutral, and the same flags drive the
    // capability badges in the picker.
    val effIntensity = if (skin.reactivity.intensity) intensity else 0f
    val effToolBurst = if (skin.reactivity.tools) toolCallBurst else 0f
    val effVoiceMode = voiceMode && skin.reactivity.voice
    val amp = (if (skin.reactivity.voice) voiceAmplitude else 0f).coerceIn(0f, 1f)

    val targetP = remember(state, skin) { skin.paramsForState(state) }
    val targetC = remember(state, skin, brand) { skin.colorsFor(state, brand) }
    val spec = tween<Float>(800, easing = FastOutSlowInEasing)

    val voiceRadiusScale by animateFloatAsState(
        targetValue = if (effVoiceMode) 1.08f else 1.0f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "voiceExpand"
    )

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

    // Continuous motion is driven by a manual frame loop rather than
    // rememberInfiniteTransition so the redraw rate can follow the orb's
    // activity. An infinite transition pins the Canvas at the display refresh
    // (120Hz) forever — even when Idle — which needlessly drains battery and,
    // on Android 15, makes the platform log `setRequestedFrameRate` on every
    // frame. Here we advance every frame while ACTIVE (full-smoothness
    // thinking/streaming/voice pulse) and throttle to ~30fps while Idle, where
    // the slower cadence is imperceptible for the chunky ASCII glyphs.
    // dt-based accumulation keeps the animation speed identical at either rate.
    val animatedTime = remember { mutableFloatStateOf(0f) }
    val animatedColorPhase = remember { mutableFloatStateOf(0f) }
    val driveAnimation = fixedTime == null || fixedColorPhase == null
    val fullFrameRate = state != SphereState.Idle || effVoiceMode
    if (driveAnimation) {
        LaunchedEffect(fullFrameRate) {
            var lastNanos = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dtSec = (now - lastNanos).coerceAtLeast(0L) / 1_000_000_000f
                lastNanos = now
                animatedTime.floatValue =
                    (animatedTime.floatValue + dtSec * SPHERE_TIME_UNITS_PER_SEC) % 1000f
                animatedColorPhase.floatValue =
                    (animatedColorPhase.floatValue + dtSec * SPHERE_COLOR_RADIANS_PER_SEC) %
                    SPHERE_TWO_PI
                if (!fullFrameRate) delay(SPHERE_IDLE_FRAME_INTERVAL_MS)
            }
        }
    }

    val time = fixedTime ?: animatedTime.floatValue
    val colorPhase = fixedColorPhase ?: animatedColorPhase.floatValue

    val cols = 58
    val rows = 34

    // Cache covers the ~25 distinct glyphs across charSets/dataChars/debrisChars.
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)

    Canvas(modifier = modifier.fillMaxSize().clipToBounds()) {
        val canvasW = size.width
        val canvasH = size.height
        val cellW = canvasW / cols
        val cellH = canvasH / rows
        val charSize = (cellW * 1.3f).coerceAtMost(cellH * 1.1f)
        val charAspect = cellW / cellH

        val style = TextStyle(
            fontSize = charSize.toSp(),
            fontFamily = FontFamily.Monospace
        )

        val frame = SphereFrame(
            cols = cols, rows = rows, charAspect = charAspect,
            state = state, time = time, colorPhase = colorPhase,
            breatheSpeed = breatheSpeed, breatheAmp = breatheAmp,
            lightSpeedX = lightSpeedX, lightSpeedY = lightSpeedY,
            lightInfluence = lightInfluence, coreTightness = coreTightness,
            turbulenceAmp = turbulenceAmp, rippleScale = rippleScale,
            heartbeatSpeed = heartbeatSpeed, radialFlowSpeed = radialFlowSpeed,
            cr1 = cr1, cg1 = cg1, cb1 = cb1,
            cr2 = cr2, cg2 = cg2, cb2 = cb2,
            intensity = effIntensity, toolCallBurst = effToolBurst,
            voiceAmplitude = amp, voiceMode = effVoiceMode,
            voiceRadiusScale = voiceRadiusScale
        )

        forEachSphereCell(frame) { cell ->
            val layout = textMeasurer.measure(cell.char.toString(), style)
            // Legacy Paint used y as baseline (`row*cellH + cellH*0.8f`).
            // Compose `drawText` uses top-left — offset by firstBaseline to match.
            val px = cell.col * cellW
            val py = cell.row * cellH + cellH * 0.8f - layout.firstBaseline
            drawText(
                textLayoutResult = layout,
                color = Color(cell.r, cell.g, cell.b, cell.alpha),
                topLeft = Offset(px, py)
            )
        }
    }
}

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

@Preview(name = "Listening", showBackground = true, backgroundColor = 0xFF0A0A0A, widthDp = 360, heightDp = 640)
@Composable
fun MorphingSphereListeningPreview() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        MorphingSphere(
            modifier = Modifier.fillMaxSize(),
            state = SphereState.Listening,
            voiceAmplitude = 0.4f,
            voiceMode = true,
            fixedTime = 5f,
            fixedColorPhase = 1.0f
        )
    }
}

@Preview(name = "Speaking (low)", showBackground = true, backgroundColor = 0xFF0A0A0A, widthDp = 360, heightDp = 640)
@Composable
fun MorphingSphereSpeakingLowPreview() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        MorphingSphere(
            modifier = Modifier.fillMaxSize(),
            state = SphereState.Speaking,
            voiceAmplitude = 0.2f,
            voiceMode = true,
            fixedTime = 5f,
            fixedColorPhase = 2.0f
        )
    }
}

@Preview(name = "Speaking (peak)", showBackground = true, backgroundColor = 0xFF0A0A0A, widthDp = 360, heightDp = 640)
@Composable
fun MorphingSphereSpeakingPeakPreview() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        MorphingSphere(
            modifier = Modifier.fillMaxSize(),
            state = SphereState.Speaking,
            voiceAmplitude = 0.95f,
            voiceMode = true,
            fixedTime = 5f,
            fixedColorPhase = 2.5f
        )
    }
}
