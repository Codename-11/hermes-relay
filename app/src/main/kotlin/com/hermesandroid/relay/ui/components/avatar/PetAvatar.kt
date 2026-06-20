package com.hermesandroid.relay.ui.components.avatar

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
 * toolCallBurst (0..1; ramps to ~1 within 200ms of a tool call, decays over
 * 1200ms) above this switches the pet to its `working` clip. 0.5 activates fast
 * and lingers ~600ms after the last tool, smoothing back-to-back tool calls
 * without flicker.
 */
private const val WORKING_BURST_THRESHOLD = 0.5f

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
 * Honored today: [SphereReactivity.voice] (`voiceAmplitude` → bounce) and
 * [SphereReactivity.tools] (`toolCallBurst` → swap to the `working` clip, gated
 * per-pet on it shipping one — see [PetSpec.toAvatar]). [SphereReactivity.intensity]
 * is delivered to [Render] but not yet consumed; `gaze` is never fed.
 */
internal val PET_RENDERER_CAPABILITIES: SphereReactivity = SphereReactivity(
    voice = true,
    tools = true,
    intensity = false,
    gaze = false,
)

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
 */
class PetAvatar(
    override val id: String,
    override val label: String,
    override val description: String,
    override val reactivity: SphereReactivity,
    private val clips: Map<SphereState, PetClip>,
    private val workingClip: PetClip? = null,
) : AgentAvatar {
    override val source: AvatarSource = AvatarSource.USER

    @Composable
    override fun Render(state: AvatarRenderState, modifier: Modifier) {
        // While a tool runs mid-turn (thinking/writing), show the distinct
        // `working` clip if the pet ships one; otherwise fall through to the
        // base-state clip. toolCallBurst is ~0 outside tool activity, and Error
        // keeps its own clip, so this only fires when the agent is actually
        // operating a tool.
        val toolActive = workingClip != null &&
            state.toolCallBurst >= WORKING_BURST_THRESHOLD &&
            (state.state == SphereState.Thinking || state.state == SphereState.Streaming)
        val clip = if (toolActive) workingClip else (clips[state.state] ?: clips[SphereState.Idle])

        // Decode the active clip off the main thread; null until ready / on
        // decode failure (graceful — the avatar just renders nothing).
        val frames by produceState<PetFrames?>(initialValue = null, clip) {
            value = if (clip == null) {
                null
            } else {
                withContext(Dispatchers.IO) { runCatching { decodeClip(clip) }.getOrNull() }
            }
        }

        var frameIndex by remember(clip) { mutableIntStateOf(0) }
        val current = frames
        val animate = current != null && current.frameCount > 1 && !state.paused

        if (animate) {
            // Rate-capped frame advance. Cancels when this leaves composition.
            LaunchedEffect(current) {
                val f = current ?: return@LaunchedEffect
                val fps = f.fps.coerceIn(1f, PET_MAX_FPS)
                val frameDurSec = 1f / fps
                val capMs = (1000f / fps).toLong().coerceIn(16L, 1000L)
                var acc = 0f
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    acc += (now - last).coerceAtLeast(0L) / 1_000_000_000f
                    last = now
                    if (acc >= frameDurSec) {
                        val steps = (acc / frameDurSec).toInt()
                        frameIndex = (frameIndex + steps) % f.frameCount
                        acc -= steps * frameDurSec
                    }
                    delay(capMs)
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
)

private fun decodeClip(clip: PetClip): PetFrames? = when (clip) {
    is FrameSequenceClip -> {
        val bitmaps = clip.files.mapNotNull { file ->
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
        if (bitmaps.isEmpty()) {
            null
        } else {
            PetFrames(
                frames = bitmaps,
                sheet = null,
                frameWidth = bitmaps.first().width,
                frameHeight = bitmaps.first().height,
                frameCount = bitmaps.size,
                fps = clip.fps,
            )
        }
    }

    is SpriteSheetClip -> {
        val sheet = BitmapFactory.decodeFile(clip.sheet.absolutePath)?.asImageBitmap()
        if (sheet == null) {
            null
        } else {
            // Clamp the declared frame count to what the sheet can actually hold.
            val cols = (sheet.width / clip.frameWidth).coerceAtLeast(1)
            val rows = (sheet.height / clip.frameHeight).coerceAtLeast(1)
            val capacity = cols * rows
            PetFrames(
                frames = emptyList(),
                sheet = sheet,
                frameWidth = clip.frameWidth,
                frameHeight = clip.frameHeight,
                frameCount = clip.frameCount.coerceIn(1, capacity),
                fps = clip.fps,
            )
        }
    }
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
            dstOffset = dstOffset,
            dstSize = dstSize,
        )
    } else {
        val bmp = f.frames.getOrNull(index) ?: f.frames.firstOrNull() ?: return
        val (dstOffset, dstSize) = containFit(bmp.width.toFloat(), bmp.height.toFloat(), bounce)
        drawImage(image = bmp, dstOffset = dstOffset, dstSize = dstSize)
    }
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
