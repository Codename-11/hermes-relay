package com.hermesandroid.relay.ui.components

import org.junit.Test
import java.util.Locale

/**
 * Parity harness for MorphingSphereCore vs the JS port in `preview/web/sphere.js`.
 *
 * Runs the same 8 fixtures that the Compose `@Preview` decorators use (and that
 * `preview/web/parity-check.mjs` mirrors), then prints a deterministic
 * fingerprint per fixture. Matching `structural` checksums between this test and
 * the JS harness mean the algorithm renders the same character grid on both
 * sides — the hard bar for "single source of truth" claims.
 *
 * Run: `./gradlew :app:testDebugUnitTest --tests "*MorphingSphereCoreParityTest*" -i`
 * Compare against: `node preview/web/parity-check.mjs --checksum-only`
 */
class MorphingSphereCoreParityTest {

    private data class Fixture(
        val name: String,
        val state: SphereState,
        val widthDp: Int,
        val heightDp: Int,
        val time: Float,
        val colorPhase: Float,
        val voiceAmplitude: Float = 0f,
        val voiceMode: Boolean = false,
    )

    // Must match FIXTURES in preview/web/parity-check.mjs one-for-one.
    private val fixtures = listOf(
        Fixture("Idle",            SphereState.Idle,      360, 640, 5f, 0.5f),
        Fixture("Thinking",        SphereState.Thinking,  360, 640, 5f, 1.5f),
        Fixture("Streaming",       SphereState.Streaming, 360, 640, 5f, 2.5f),
        Fixture("Error",           SphereState.Error,     360, 640, 5f, 3.0f),
        Fixture("Compact",         SphereState.Idle,      200, 200, 8f, 2.0f),
        Fixture("Listening",       SphereState.Listening, 360, 640, 5f, 1.0f, 0.4f,  true),
        Fixture("Speaking (low)",  SphereState.Speaking,  360, 640, 5f, 2.0f, 0.2f,  true),
        Fixture("Speaking (peak)", SphereState.Speaking,  360, 640, 5f, 2.5f, 0.95f, true),
    )

    private val cols = 58
    private val rows = 34

    private fun buildFrame(fx: Fixture): SphereFrame {
        val p = paramsFor(fx.state)
        val c = colorsFor(fx.state)
        val cellW = fx.widthDp.toFloat() / cols
        val cellH = fx.heightDp.toFloat() / rows
        return SphereFrame(
            cols = cols, rows = rows, charAspect = cellW / cellH,
            state = fx.state, time = fx.time, colorPhase = fx.colorPhase,
            breatheSpeed = p.breatheSpeed, breatheAmp = p.breatheAmp,
            lightSpeedX = p.lightSpeedX, lightSpeedY = p.lightSpeedY,
            lightInfluence = p.lightInfluence, coreTightness = p.coreTightness,
            turbulenceAmp = p.turbulenceAmp, rippleScale = p.rippleScale,
            heartbeatSpeed = p.heartbeatSpeed, radialFlowSpeed = p.radialFlowSpeed,
            cr1 = c.r1, cg1 = c.g1, cb1 = c.b1,
            cr2 = c.r2, cg2 = c.g2, cb2 = c.b2,
            intensity = 0f, toolCallBurst = 0f,
            voiceAmplitude = fx.voiceAmplitude, voiceMode = fx.voiceMode,
            voiceRadiusScale = if (fx.voiceMode) 1.08f else 1.0f,
        )
    }

    // FNV-1a 32-bit, matches the JS implementation in parity-check.mjs.
    private fun fnv1a(s: String): String {
        var h: UInt = 0x811c9dc5u
        for (ch in s) {
            h = h xor ch.code.toUInt()
            h *= 0x01000193u
        }
        return "%08x".format(h.toLong())
    }

    private fun fmt3(v: Float): String = String.format(Locale.ROOT, "%.3f", v)

    private fun classifyZone(ch: Char): String = when (ch) {
        in "·:;-" -> "glow"
        in "01<>[]{}|/\\~^" -> "ring"
        else -> "inside"
    }

    private data class Rendered(
        val structural: String,
        val full: String,
        val inside: Int, val glow: Int, val ring: Int, val total: Int,
    )

    private fun render(fx: Fixture): Rendered {
        val frame = buildFrame(fx)
        val structBuf = StringBuilder()
        val fullBuf = StringBuilder()
        var inside = 0; var glow = 0; var ring = 0; var total = 0

        forEachSphereCell(frame) { cell ->
            when (classifyZone(cell.char)) {
                "inside" -> inside++
                "glow" -> glow++
                "ring" -> ring++
            }
            total++
            if (structBuf.isNotEmpty()) structBuf.append('\n')
            structBuf.append("${cell.row},${cell.col},${cell.char.code}")
            if (fullBuf.isNotEmpty()) fullBuf.append('\n')
            fullBuf.append(
                "${cell.row},${cell.col},${cell.char.code}," +
                    "${fmt3(cell.r)},${fmt3(cell.g)},${fmt3(cell.b)},${fmt3(cell.alpha)}"
            )
        }
        return Rendered(
            structural = fnv1a(structBuf.toString()),
            full = fnv1a(fullBuf.toString()),
            inside = inside, glow = glow, ring = ring, total = total,
        )
    }

    @Test fun `print parity fingerprints`() {
        println()
        println("─── MorphingSphereCore parity fingerprints (Kotlin) ───")
        println("Compare against: node preview/web/parity-check.mjs --checksum-only")
        println()
        for (fx in fixtures) {
            val r = render(fx)
            println(
                "${fx.name.padEnd(18)}  struct=${r.structural}  full=${r.full}  " +
                    "inside=${r.inside} glow=${r.glow} ring=${r.ring}"
            )
        }
        println()
    }
}
