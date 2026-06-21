package com.hermesandroid.relay.ui.components.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hermesandroid.relay.ui.components.SphereReactivity
import com.hermesandroid.relay.ui.components.SphereState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Never animate a pet faster than this regardless of the spec's `fps`. */
private const val PET_MAX_FPS = 60f

/** voiceAmplitude (0..1) → up to this extra scale, for a subtle "talking" bounce. */
private const val PET_BOUNCE = 0.12f

/**
 * intensity (0..1) → up to this fraction *faster* clip playback, so a base/working
 * loop visibly "works harder" as the agent ramps. At a typical streaming intensity
 * (~0.7) that is ~1.4×; at full intensity 1.6×, capped at [PET_MAX_FPS].
 */
private const val PET_INTENSITY_RATE = 0.6f

/**
 * toolCallBurst (0..1; ramps to ~1 within 200ms of a tool call, decays over
 * 1200ms) above this switches the pet to its `working` clip. 0.5 activates fast
 * and lingers ~600ms after the last tool, smoothing back-to-back tool calls
 * without flicker.
 */
private const val WORKING_BURST_THRESHOLD = 0.5f

/**
 * Safety ceiling for a one-shot reaction overlay: even if its clip fails to
 * decode or can't animate, the pet returns to its base loop after this. Real
 * reactions finish far sooner and clear themselves on their final frame.
 */
private const val ONE_SHOT_MAX_MS = 4000L

/**
 * The live reactive signals the pet renderer actually consumes **today**. A pet's
 * effective [AgentAvatar.reactivity] is clamped to this in [PetSpec.toAvatar]
 * (declared-AND-supported), so a `pet.json` can never advertise reactivity on the
 * picker badge that [Render] doesn't deliver.
 *
 * This is the single forward-compat switch: flip a flag to `true` here the moment
 * [Render] learns to consume that signal, and every manifest that already declared
 * it lights up with no other change.
 *
 * Honored today: [SphereReactivity.voice] (`voiceAmplitude` → bounce),
 * [SphereReactivity.tools] (`toolCallBurst` → swap to the `working` clip, gated
 * per-pet on it shipping one — see [PetSpec.toAvatar]), and
 * [SphereReactivity.intensity] (`intensity` → faster clip playback, opt-in via the
 * declared flag). `gaze` is never fed.
 */
internal val PET_RENDERER_CAPABILITIES: SphereReactivity = SphereReactivity(
    voice = true,
    tools = true,
    intensity = true,
    gaze = false,
)

/**
 * Transient "reaction" overlays a pet can play **once** over its base loop, then
 * return — the event tier of avatar behavior (vs. the sustained per-state clips).
 * Each is **opt-in**: the pet plays it only if it ships the matching clip.
 * Resolved by [PetSpec.toAvatar]; fired by [PetAvatar.Render] off the
 * activity-state transitions it already observes, so no host plumbing is needed.
 *
 * - [Greet] — when the pet first appears (clip key `greet`/`wake`).
 * - [Done] — when a productive turn finishes, i.e. streaming/speaking → idle
 *   (clip key `done`/`celebrate`).
 *
 * `attention` (on a notification) is reserved — it needs a host event the avatar
 * doesn't yet receive.
 */
enum class PetOneShot { Greet, Done }

/**
 * A resolved, ready-to-render animation clip for one agent state. Holds file
 * references + metadata only — pixels are decoded lazily by [PetAvatar.Render]
 * so an unselected pet costs no bitmap memory.
 */
sealed interface PetClip {
    val fps: Float
    val frameCount: Int
}

/** One image file per frame. */
data class FrameSequenceClip(
    val files: List<File>,
    override val fps: Float,
) : PetClip {
    override val frameCount: Int get() = files.size
}

/** A single sprite sheet sliced into [frameCount] cells of [frameWidth]×[frameHeight]. */
data class SpriteSheetClip(
    val sheet: File,
    val frameWidth: Int,
    val frameHeight: Int,
    override val frameCount: Int,
    override val fps: Float,
) : PetClip

/**
 * User-loaded "pet" avatar — a bitmap frame-sequence / sprite-atlas companion
 * that replaces the sphere. Implements the C2 [AgentAvatar] seam, so once
 * selected it renders across chat, clean mode, the voice overlay, onboarding,
 * and the splash with no per-surface code.
 *
 * Rendering is deliberately dependency-free: frames are decoded with
 * [BitmapFactory] and drawn through a Compose [Canvas]. The frame loop is
 * **rate-capped** (a manual `withFrameNanos` + `delay` loop, mirroring
 * `MorphingSphere`) — never an unbounded `rememberInfiniteTransition` — and it
 * stops the moment the avatar leaves composition.
 *
 * Built by [PetSpec.toAvatar]; discovered by [PetLoader].
 *
 * @property clips a fully-resolved clip for every [SphereState] (the loader
 *   pre-applies the idle/thinking/speaking fallback chain), so [Render] is a
 *   simple lookup.
 * @property workingClip optional distinct "agent is running a tool" loop. When
 *   present (the author shipped a `working` clip), it overrides the base-state
 *   clip while [AvatarRenderState.toolCallBurst] is high during a thinking/
 *   writing turn — so tool-use reads differently from contemplation. Null →
 *   tool-use looks like the base state (the pre-working behavior).
 * @property oneShots optional [PetOneShot] → clip map. Each plays **once** over
 *   the base loop on its trigger, then returns. Empty → no reactions (today's
 *   behavior). Triggers are derived from activity-state transitions in [Render].
 */
class PetAvatar(
    override val id: String,
    override val label: String,
    override val description: String,
    override val reactivity: SphereReactivity,
    private val clips: Map<SphereState, PetClip>,
    private val workingClip: PetClip? = null,
    private val oneShots: Map<PetOneShot, PetClip> = emptyMap(),
) : AgentAvatar {
    override val source: AvatarSource = AvatarSource.USER

    /** A one-shot is fireable only if it ships a clip that can actually play. */
    private fun fireable(kind: PetOneShot): Boolean = (oneShots[kind]?.frameCount ?: 0) > 1

    @Composable
    override fun Render(state: AvatarRenderState, modifier: Modifier) {
        // ── One-shot reactions ──────────────────────────────────────────────
        // A reaction clip plays ONCE over the base loop, then releases. Triggers
        // are derived from the activity-state transitions this avatar already
        // sees — no host plumbing. Suppressed under reduced motion (`paused`).
        var activeOneShot by remember { mutableStateOf<PetOneShot?>(null) }

        // Greet on first appearance.
        LaunchedEffect(Unit) {
            if (!state.paused && fireable(PetOneShot.Greet)) activeOneShot = PetOneShot.Greet
        }
        // Celebrate when a productive turn ends: streaming/speaking → idle.
        var prevState by remember { mutableStateOf(state.state) }
        LaunchedEffect(state.state) {
            val ended = state.state == SphereState.Idle &&
                (prevState == SphereState.Streaming || prevState == SphereState.Speaking)
            prevState = state.state
            if (ended && !state.paused && fireable(PetOneShot.Done)) activeOneShot = PetOneShot.Done
        }
        // Backstop: never let a reaction linger (decode failure / paused / single
        // frame). The play-once loop clears it far sooner on success.
        LaunchedEffect(activeOneShot) {
            if (activeOneShot != null) {
                delay(ONE_SHOT_MAX_MS)
                activeOneShot = null
            }
        }

        // While a tool runs mid-turn (thinking/writing), show the distinct
        // `working` clip if the pet ships one; otherwise the base-state clip.
        // toolCallBurst is ~0 outside tool activity, and Error keeps its own clip,
        // so this only fires while the agent actually operates a tool.
        val toolActive = workingClip != null &&
            state.toolCallBurst >= WORKING_BURST_THRESHOLD &&
            (state.state == SphereState.Thinking || state.state == SphereState.Streaming)
        // A live reaction overlays everything; otherwise working overlays the base.
        val oneShotClip = activeOneShot?.let { oneShots[it] }
        val baseClip = if (toolActive) workingClip else (clips[state.state] ?: clips[SphereState.Idle])
        val clip = oneShotClip ?: baseClip
        val playOnce = oneShotClip != null

        // Re-center each frame on its own opaque content at decode time —
        // neutralizes the positional drift common in AI-generated sheets (a
        // character that floats/jumps cell-to-cell). Global Appearance toggle;
        // flipping it re-decodes via the produceState key.
        val stabilize = LocalPetStabilize.current

        // Decode the active clip off the main thread; null until ready / on
        // decode failure (graceful — the avatar just renders nothing).
        val frames by produceState<PetFrames?>(initialValue = null, clip, stabilize) {
            value = if (clip == null) {
                null
            } else {
                withContext(Dispatchers.IO) { runCatching { decodeClip(clip, stabilize) }.getOrNull() }
            }
        }

        var frameIndex by remember(clip) { mutableIntStateOf(0) }
        val current = frames
        val animate = current != null && current.frameCount > 1 && !state.paused

        // Live activity level + opt-in speedup, read inside the loop so playback
        // tracks the agent without restarting it. One-shots play at authored rate.
        val intensityState = rememberUpdatedState(state.intensity)
        val modulateIntensity = reactivity.intensity && !playOnce
        // Global user speed tune (Appearance), read live like intensity. Applies
        // to every clip including one-shots, so the whole pet slows/speeds together.
        val speedState = rememberUpdatedState(LocalPetPlaybackSpeed.current)

        if (animate) {
            // Vsync-paced frame advance (no fixed delay). Cancels when this leaves
            // composition. A one-shot (playOnce) runs 0→end then releases to the base loop;
            // the base loop wraps with modulo. With intensity modulation on, fps
            // is recomputed each tick from the live activity level.
            LaunchedEffect(current, playOnce) {
                val f = current ?: return@LaunchedEffect
                val baseFps = f.fps.coerceIn(1f, PET_MAX_FPS)
                var acc = 0f
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    acc += (now - last).coerceAtLeast(0L) / 1_000_000_000f
                    last = now
                    val speed = speedState.value
                    val fps = if (modulateIntensity) {
                        (baseFps * speed * (1f + intensityState.value.coerceIn(0f, 1f) * PET_INTENSITY_RATE))
                            .coerceIn(1f, PET_MAX_FPS)
                    } else {
                        (baseFps * speed).coerceIn(1f, PET_MAX_FPS)
                    }
                    val frameDurSec = 1f / fps
                    if (acc >= frameDurSec) {
                        val steps = (acc / frameDurSec).toInt()
                        if (playOnce && frameIndex + steps >= f.frameCount) {
                            // Reaction finished: park on the last frame and hand
                            // back to the base loop (clearing recomposes).
                            frameIndex = f.frameCount - 1
                            activeOneShot = null
                            return@LaunchedEffect
                        }
                        frameIndex = (frameIndex + steps).let { if (playOnce) it else it % f.frameCount }
                        acc -= steps * frameDurSec
                    }
                    // No extra delay: withFrameNanos already suspends until the
                    // next frame, so the loop is vsync-paced and advances the
                    // sprite only when frameDurSec of real time has accumulated.
                    // A fixed per-frame delay here double-counted the vsync wait,
                    // drifted the accumulator, and forced periodic 2-frame skips
                    // (visible stutter, worst at low fps).
                }
            }
        }

        // Subtle voice bounce; suppressed when paused (reduced motion) or when
        // the pet opts out of voice reactivity.
        val bounce = if (state.paused || !reactivity.voice) {
            1f
        } else {
            1f + state.voiceAmplitude.coerceIn(0f, 1f) * PET_BOUNCE
        }

        Canvas(modifier = modifier) {
            val f = current ?: return@Canvas
            drawPetFrame(f, frameIndex.coerceIn(0, (f.frameCount - 1).coerceAtLeast(0)), bounce)
        }
    }
}

/** Decoded, drawable form of a [PetClip]. Exactly one of [frames]/[sheet] is set. */
private class PetFrames(
    val frames: List<ImageBitmap>,
    val sheet: ImageBitmap?,
    val frameWidth: Int,
    val frameHeight: Int,
    val frameCount: Int,
    val fps: Float,
    /** Per-frame recenter offset (source px) when stabilization is on; null = off. */
    val centerOffsets: List<IntOffset>? = null,
)

private fun decodeClip(clip: PetClip, stabilize: Boolean): PetFrames? = when (clip) {
    is FrameSequenceClip -> {
        val bitmaps = clip.files.mapNotNull { file -> BitmapFactory.decodeFile(file.absolutePath) }
        if (bitmaps.isEmpty()) {
            null
        } else {
            PetFrames(
                frames = bitmaps.map { it.asImageBitmap() },
                sheet = null,
                frameWidth = bitmaps.first().width,
                frameHeight = bitmaps.first().height,
                frameCount = bitmaps.size,
                fps = clip.fps,
                centerOffsets = if (stabilize) bitmaps.map { bitmapRecenter(it) } else null,
            )
        }
    }

    is SpriteSheetClip -> {
        val bmp = BitmapFactory.decodeFile(clip.sheet.absolutePath)
        if (bmp == null) {
            null
        } else {
            // Clamp the declared frame count to what the sheet can actually hold.
            val cols = (bmp.width / clip.frameWidth).coerceAtLeast(1)
            val rows = (bmp.height / clip.frameHeight).coerceAtLeast(1)
            val count = clip.frameCount.coerceIn(1, cols * rows)
            PetFrames(
                frames = emptyList(),
                sheet = bmp.asImageBitmap(),
                frameWidth = clip.frameWidth,
                frameHeight = clip.frameHeight,
                frameCount = count,
                fps = clip.fps,
                centerOffsets = if (stabilize) {
                    sheetRecenter(bmp, cols, clip.frameWidth, clip.frameHeight, count)
                } else {
                    null
                },
            )
        }
    }
}

/** Per-cell recenter offsets for a sprite sheet (see [contentRecenter]). */
private fun sheetRecenter(bmp: Bitmap, cols: Int, fw: Int, fh: Int, count: Int): List<IntOffset> {
    val buf = IntArray(fw * fh)
    return (0 until count).map { i ->
        contentRecenter(bmp, (i % cols) * fw, (i / cols) * fh, fw, fh, buf)
    }
}

/** Recenter offset for one standalone frame bitmap. */
private fun bitmapRecenter(bmp: Bitmap): IntOffset =
    contentRecenter(bmp, 0, 0, bmp.width, bmp.height, IntArray(bmp.width * bmp.height))

/**
 * Scan the [w]×[h] region at ([x0],[y0]) of [bmp] for opaque pixels and return the
 * offset (source px) that moves their bounding-box center to the region center —
 * cancelling per-frame positional drift. [buf] (size ≥ [w]×[h]) is reused scratch.
 * Returns [IntOffset.Zero] for a fully-transparent region.
 */
private fun contentRecenter(bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int, buf: IntArray): IntOffset {
    bmp.getPixels(buf, 0, w, x0, y0, w, h)
    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1
    var idx = 0
    for (yy in 0 until h) {
        for (xx in 0 until w) {
            if ((buf[idx] ushr 24) and 0xFF > 16) {
                if (xx < minX) minX = xx
                if (xx > maxX) maxX = xx
                if (yy < minY) minY = yy
                if (yy > maxY) maxY = yy
            }
            idx++
        }
    }
    if (maxX < 0) return IntOffset.Zero
    return IntOffset(w / 2 - (minX + maxX) / 2, h / 2 - (minY + maxY) / 2)
}

/** Draw frame [index] of [f], contain-fit + centered, scaled by [bounce]. */
private fun DrawScope.drawPetFrame(f: PetFrames, index: Int, bounce: Float) {
    if (f.sheet != null) {
        val fw = f.frameWidth
        val fh = f.frameHeight
        if (fw <= 0 || fh <= 0) return
        val cols = (f.sheet.width / fw).coerceAtLeast(1)
        val col = index % cols
        val row = index / cols
        val (dstOffset, dstSize) = containFit(fw.toFloat(), fh.toFloat(), bounce)
        drawImage(
            image = f.sheet,
            srcOffset = IntOffset(col * fw, row * fh),
            srcSize = IntSize(fw, fh),
            dstOffset = recenter(dstOffset, dstSize.width, fw, f.centerOffsets, index),
            dstSize = dstSize,
        )
    } else {
        val bmp = f.frames.getOrNull(index) ?: f.frames.firstOrNull() ?: return
        val (dstOffset, dstSize) = containFit(bmp.width.toFloat(), bmp.height.toFloat(), bounce)
        drawImage(
            image = bmp,
            dstOffset = recenter(dstOffset, dstSize.width, bmp.width, f.centerOffsets, index),
            dstSize = dstSize,
        )
    }
}

/** Shift [dstOffset] by frame [index]'s stabilization offset (source px → dest px). */
private fun recenter(dstOffset: IntOffset, dstW: Int, srcW: Int, offsets: List<IntOffset>?, index: Int): IntOffset {
    val o = offsets?.getOrNull(index) ?: return dstOffset
    if (o.x == 0 && o.y == 0) return dstOffset
    val scale = if (srcW > 0) dstW.toFloat() / srcW else 1f
    return IntOffset(
        dstOffset.x + (o.x * scale).roundToInt(),
        dstOffset.y + (o.y * scale).roundToInt(),
    )
}

/** Compute a centered, aspect-preserving destination rect for a [w]×[h] frame. */
private fun DrawScope.containFit(w: Float, h: Float, bounce: Float): Pair<IntOffset, IntSize> {
    if (w <= 0f || h <= 0f) return IntOffset.Zero to IntSize(size.width.roundToInt(), size.height.roundToInt())
    val scale = minOf(size.width / w, size.height / h) * bounce
    val dstW = w * scale
    val dstH = h * scale
    val left = (size.width - dstW) / 2f
    val top = (size.height - dstH) / 2f
    return IntOffset(left.roundToInt(), top.roundToInt()) to IntSize(dstW.roundToInt(), dstH.roundToInt())
}
