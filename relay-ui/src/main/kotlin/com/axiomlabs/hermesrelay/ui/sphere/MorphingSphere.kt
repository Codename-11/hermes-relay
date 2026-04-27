package com.axiomlabs.hermesrelay.ui.sphere

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * (`animateFloatAsState`, `rememberInfiniteTransition`) and text drawing.
 */

@Composable
fun MorphingSphere(
    modifier: Modifier = Modifier,
    state: SphereState = SphereState.Idle,
    intensity: Float = 0f,
    toolCallBurst: Float = 0f,
    voiceAmplitude: Float = 0f,
    voiceMode: Boolean = false,
    fixedTime: Float? = null,
    fixedColorPhase: Float? = null
) {
    val amp = voiceAmplitude.coerceIn(0f, 1f)
    val targetP = remember(state) { paramsFor(state) }
    val targetC = remember(state) { colorsFor(state) }
    val spec = tween<Float>(800, easing = FastOutSlowInEasing)

    val voiceRadiusScale by animateFloatAsState(
        targetValue = if (voiceMode) 1.08f else 1.0f,
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
            intensity = intensity, toolCallBurst = toolCallBurst,
            voiceAmplitude = amp, voiceMode = voiceMode,
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
