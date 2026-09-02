package com.hermesandroid.relay.preview

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
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
import com.axiomlabs.hermesrelay.ui.sphere.SphereFrame
import com.axiomlabs.hermesrelay.ui.sphere.SphereState
import com.axiomlabs.hermesrelay.ui.sphere.colorsFor
import com.axiomlabs.hermesrelay.ui.sphere.forEachSphereCell
import com.axiomlabs.hermesrelay.ui.sphere.paramsFor

/**
 * Compose **Desktop** renderer for the morphing sphere — the "future renderer
 * (Compose Desktop)" the [forEachSphereCell] docstring anticipates.
 *
 * This is intentionally a thin twin of the Android renderer in
 * `:relay-ui` → `MorphingSphere.kt`: the animation-state wiring and the
 * Canvas/`drawText` draw loop are duplicated here, but the ALGORITHM lives only
 * in the shared, source-included `MorphingSphereCore.kt`. Keep visual/structural
 * changes in the Core so Android, this desktop preview, and the JS mirror in
 * `preview/web/` stay in lockstep (guarded by `MorphingSphereCoreParityTest`).
 *
 * Uses only `androidx.compose.*` APIs that exist in Compose Multiplatform — no
 * Android framework, no `@Preview`/`androidx.*.tooling` (those are Android-only).
 */
@Composable
fun DesktopSphere(
    modifier: Modifier = Modifier,
    state: SphereState = SphereState.Idle,
    intensity: Float = 0f,
    toolCallBurst: Float = 0f,
    voiceAmplitude: Float = 0f,
    voiceMode: Boolean = false,
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
    val colorPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2832f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "colorPulse"
    )

    val cols = 58
    val rows = 34
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)

    Canvas(modifier = modifier.fillMaxSize().clipToBounds()) {
        val cellW = size.width / cols
        val cellH = size.height / rows
        val charSize = (cellW * 1.3f).coerceAtMost(cellH * 1.1f)
        val charAspect = cellW / cellH

        val style = TextStyle(
            fontSize = charSize.toSp(),
            fontFamily = FontFamily.Monospace
        )

        val frame = SphereFrame(
            cols = cols, rows = rows, charAspect = charAspect,
            state = state, time = animatedTime, colorPhase = colorPhase,
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
