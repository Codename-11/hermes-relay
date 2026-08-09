package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.ui.theme.LocalBrand
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How the in-bubble "working" indicator is drawn while a reply streams.
 * [Dots] is the classic three fading bullets ([StreamingDots]); [Matrix] is the
 * dot-anime-style [DotMatrixIndicator] grid. Persisted as the lowercase name
 * ("dots"/"matrix") by `ConnectionViewModel.thinkingIndicatorStyle`.
 */
enum class ThinkingIndicatorStyle { Dots, Matrix }

/**
 * The motion the [DotMatrixIndicator] grid plays. [Wave] is procedural (a sine
 * sweep); the rest are authored frame sequences (the dot-anime-react concept) —
 * a looping list of "lit" dot index sets, crossfaded between frames.
 *
 * [key] is the lowercase value persisted by `ConnectionViewModel`; [label] is
 * the picker chip text; [periodMillis] is one full loop of the motion.
 */
enum class ThinkingMatrixPattern(val key: String, val label: String, val periodMillis: Int) {
    Wave("wave", "Wave", 1100),
    Pulse("pulse", "Pulse", 1300),
    Bounce("bounce", "Bounce", 1100),
    Sparkle("sparkle", "Sparkle", 850),
    ;

    companion object {
        /** Map a persisted key back to a pattern, falling back to [Wave]. */
        fun fromKey(key: String?): ThinkingMatrixPattern =
            entries.firstOrNull { it.key == key } ?: Wave
    }
}

/**
 * The color the [DotMatrixIndicator] grid paints with. [Auto] follows the
 * bubble's text color; the rest pull a named accent from the active
 * [com.hermesandroid.relay.ui.theme.BrandPalette], so the same choice re-themes
 * across app themes (e.g. "Amber" is bronze in Ember, gold in Cyberpunk).
 * Resolve to a concrete [Color] with [toColor].
 */
enum class ThinkingMatrixColor(val key: String, val label: String) {
    Auto("auto", "Auto"),
    Relay("relay", "Relay"),
    Cyan("cyan", "Cyan"),
    Green("green", "Green"),
    Amber("amber", "Amber"),
    Purple("purple", "Purple"),
    Pink("pink", "Pink"),
    ;

    companion object {
        /** Map a persisted key back to a color choice, falling back to [Auto]. */
        fun fromKey(key: String?): ThinkingMatrixColor =
            entries.firstOrNull { it.key == key } ?: Auto
    }
}

/**
 * Resolve a [ThinkingMatrixColor] against the active brand palette. [autoColor]
 * is used for [ThinkingMatrixColor.Auto] (typically the bubble's text color).
 */
@Composable
fun ThinkingMatrixColor.toColor(autoColor: Color): Color {
    val brand = LocalBrand.current
    return when (this) {
        ThinkingMatrixColor.Auto -> autoColor
        ThinkingMatrixColor.Relay -> brand.relay
        ThinkingMatrixColor.Cyan -> brand.cyan
        ThinkingMatrixColor.Green -> brand.green
        ThinkingMatrixColor.Amber -> brand.amber
        ThinkingMatrixColor.Purple -> brand.purple
        ThinkingMatrixColor.Pink -> brand.danger
    }
}

/**
 * Resolved streaming-indicator config, provided once at the chat root so
 * [MessageBubble] can pick the style/pattern and honor the motion pref without
 * threading more params through its (already long) signature.
 *
 * Defaults to the legacy [ThinkingIndicatorStyle.Dots] + animated, so previews,
 * tests, and any call site that doesn't provide the local stay unchanged.
 */
data class ThinkingIndicatorConfig(
    val style: ThinkingIndicatorStyle = ThinkingIndicatorStyle.Dots,
    val pattern: ThinkingMatrixPattern = ThinkingMatrixPattern.Wave,
    val color: ThinkingMatrixColor = ThinkingMatrixColor.Auto,
    val animated: Boolean = true,
)

/** Chat-root provided streaming-indicator config; see [ThinkingIndicatorConfig]. */
val LocalThinkingIndicator = compositionLocalOf { ThinkingIndicatorConfig() }

internal fun shouldAnimateDotMatrix(
    appAnimationsEnabled: Boolean,
    osAnimationsEnabled: Boolean,
    touchExplorationEnabled: Boolean,
): Boolean = appAnimationsEnabled && osAnimationsEnabled && !touchExplorationEnabled

/**
 * A compact dot-matrix "thinking" animation — a small grid of dots evoking a
 * dot-matrix / LED display (the dot-anime-react concept reimplemented natively
 * on a Compose [Canvas] rather than ported from React DOM). The motion is set
 * by [pattern]: [ThinkingMatrixPattern.Wave] is a procedural sine sweep; the
 * others are authored frame sequences (see [buildMatrixFrames]).
 *
 * Themed: every dot is [color] modulated only in alpha (≈0.18 idle → 1.0 lit),
 * so it inherits the bubble's text color in light and dark.
 *
 * Motion: driven by [rememberAmbientPhase] (frame-throttled to ~[fps], and it
 * parks to zero cost when [animated] is false) instead of an always-on
 * `rememberInfiniteTransition` — the indicator can be on screen for the whole
 * reply, so it must not pin the panel at the display refresh rate (see
 * `AmbientAnimation.kt`). When [animated] is false it paints a single still
 * frame — the avatar-agnostic reduced-motion / animations-off behavior.
 *
 * The horizontal pitch ([columnSpacing]) is a touch wider than the vertical
 * pitch ([rowSpacing]) so the grid reads wider than tall without growing taller.
 */
@Composable
fun DotMatrixIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    pattern: ThinkingMatrixPattern = ThinkingMatrixPattern.Wave,
    columns: Int = 5,
    rows: Int = 3,
    dotRadius: Dp = 1.6.dp,
    columnSpacing: Dp = 11.dp,
    rowSpacing: Dp = 5.dp,
    fps: Int = 30,
    animated: Boolean = true,
) {
    val motion = rememberAccessibleMotionState()
    val phase = rememberAmbientPhase(
        periodMillis = pattern.periodMillis,
        fps = fps,
        running = shouldAnimateDotMatrix(
            appAnimationsEnabled = animated,
            osAnimationsEnabled = motion.osAnimations,
            touchExplorationEnabled = motion.touchExploration,
        ),
    )
    val gridWidth = columnSpacing * (columns - 1)
    val gridHeight = rowSpacing * (rows - 1)

    // Authored patterns precompute their frames (lit indices per frame) for the
    // grid size; Wave is procedural and needs none.
    val frames = remember(pattern, columns, rows) {
        if (pattern == ThinkingMatrixPattern.Wave) emptyList()
        else buildMatrixFrames(pattern, columns, rows)
    }

    Canvas(
        modifier = modifier.size(
            width = gridWidth + dotRadius * 2,
            height = gridHeight + dotRadius * 2,
        )
    ) {
        val r = dotRadius.toPx()
        val gapX = columnSpacing.toPx()
        val gapY = rowSpacing.toPx()

        // Per-cell brightness in 0..1; the chosen motion supplies the function.
        val brightnessAt: (Int, Int) -> Float = if (frames.isEmpty()) {
            // Procedural horizontal wave: each column samples the sine a little
            // later than the one to its left, so a bright band travels L→R.
            val midRow = (rows - 1) / 2f
            val amplitude = (rows - 1) / 2f
            ({ c, rr ->
                val columnPhase = phase + c.toFloat() / columns
                val crestRow = midRow + amplitude * sin(2f * PI.toFloat() * columnPhase)
                1f - abs(rr - crestRow) / 1.2f
            })
        } else {
            // Authored frames, crossfaded between the current and next frame by
            // the fractional phase so dots fade rather than hard-blink.
            val n = frames.size
            val pos = phase * n
            val cur = pos.toInt() % n
            val nxt = (cur + 1) % n
            val t = pos - floor(pos)
            val curSet = frames[cur]
            val nxtSet = frames[nxt]
            ({ c, rr ->
                val i = rr * columns + c
                val a = if (i in curSet) 1f else 0f
                val b = if (i in nxtSet) 1f else 0f
                a + (b - a) * t
            })
        }

        for (c in 0 until columns) {
            for (rr in 0 until rows) {
                val brightness = brightnessAt(c, rr).coerceIn(0f, 1f)
                val alpha = 0.18f + 0.82f * brightness
                drawCircle(
                    color = color.copy(alpha = color.alpha * alpha),
                    radius = r,
                    center = Offset(x = r + c * gapX, y = r + rr * gapY),
                )
            }
        }
    }
}

/**
 * Build the looping frame sequence for an authored [pattern] on a
 * [columns]×[rows] grid. Each frame is the set of lit dot indices, addressed as
 * `row * columns + col` (the dot-anime-react convention). [ThinkingMatrixPattern.Wave]
 * is procedural and returns an empty list.
 */
private fun buildMatrixFrames(
    pattern: ThinkingMatrixPattern,
    columns: Int,
    rows: Int,
): List<Set<Int>> {
    fun idx(c: Int, r: Int) = r * columns + c
    return when (pattern) {
        ThinkingMatrixPattern.Wave -> emptyList()

        // Concentric Manhattan-distance rings from the center, growing out then
        // contracting back — a heartbeat that radiates and returns.
        ThinkingMatrixPattern.Pulse -> {
            val cx = (columns - 1) / 2f
            val cy = (rows - 1) / 2f
            val rings = (0..(columns + rows)).map { d ->
                buildSet {
                    for (c in 0 until columns) for (r in 0 until rows) {
                        if ((abs(c - cx) + abs(r - cy)).roundToInt() == d) add(idx(c, r))
                    }
                }
            }.filter { it.isNotEmpty() }
            if (rings.size <= 1) rings
            else rings + rings.subList(1, rings.size - 1).asReversed()
        }

        // A single dot arcing left→right and back, hopping to the top row at the
        // midpoint — a ball bouncing across the grid.
        ThinkingMatrixPattern.Bounce -> {
            val lastCol = (columns - 1).coerceAtLeast(1)
            fun arcRow(c: Int): Int {
                val s = sin(PI * c / lastCol) // 0 at the ends, 1 at the middle
                return ((rows - 1) * (1.0 - s)).roundToInt().coerceIn(0, rows - 1)
            }
            val forward = (0 until columns).map { c -> setOf(idx(c, arcRow(c))) }
            val back = (columns - 2 downTo 1).map { c -> setOf(idx(c, arcRow(c))) }
            forward + back
        }

        // Deterministic scatter that shifts every frame — a "thinking" shimmer
        // (no RNG, so it's stable across recompositions and process restarts).
        ThinkingMatrixPattern.Sparkle -> {
            val frameCount = 8
            (0 until frameCount).map { f ->
                buildSet {
                    for (c in 0 until columns) for (r in 0 until rows) {
                        val i = idx(c, r)
                        if ((i * 3 + f * 7) % 8 < 3) add(i)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DotMatrixIndicatorPreview() {
    HermesRelayTheme {
        DotMatrixIndicator(color = Color(0xFF7C4DFF), pattern = ThinkingMatrixPattern.Pulse)
    }
}
