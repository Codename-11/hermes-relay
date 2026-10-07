package com.hermesandroid.relay.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlin.math.sqrt

/**
 * Assistant chat text scaling: a reusable pinch-to-scale state/gesture primitive shared by the
 * chat surfaces (ChatScreen, BotChatScreen).
 *
 * Typical wiring on a chat screen:
 *
 * ```
 * val textScale = rememberChatTextScale()
 * Box(modifier = Modifier.chatTextScaleGesture(textScale)) {
 *     Column(modifier = Modifier.verticalScroll(...)) {
 *         // bubbles read LocalChatTextScale and apply it to their text size
 *     }
 * }
 * ```
 */

/** Smallest allowed assistant text scale (0.85x). */
const val CHAT_TEXT_SCALE_MIN: Float = 0.85f

/** Largest allowed assistant text scale (2.0x). */
const val CHAT_TEXT_SCALE_MAX: Float = 2.0f

/** Default assistant text scale before any pinch. */
const val CHAT_TEXT_SCALE_DEFAULT: Float = 1.0f

/**
 * Current assistant text scale for the enclosing chat surface.
 *
 * Descendant composables (message bubbles, markdown, code blocks) read this and apply it to their
 * text size. Defaults to [CHAT_TEXT_SCALE_DEFAULT] so screens that do not wire a gesture keep
 * working unchanged.
 */
val LocalChatTextScale =
    compositionLocalOf { CHAT_TEXT_SCALE_DEFAULT }

/** Clamps [scale] into the allowed chat text scale range [CHAT_TEXT_SCALE_MIN, CHAT_TEXT_SCALE_MAX]. */
fun clampChatTextScale(scale: Float): Float {
    if (scale.isNaN()) return CHAT_TEXT_SCALE_DEFAULT
    return scale.coerceIn(CHAT_TEXT_SCALE_MIN, CHAT_TEXT_SCALE_MAX)
}

/**
 * Remembers the current chat text scale. Starts at [CHAT_TEXT_SCALE_DEFAULT] and always stays
 * within [CHAT_TEXT_SCALE_MIN]..[CHAT_TEXT_SCALE_MAX].
 */
@Composable
fun rememberChatTextScale(
    initialScale: Float = CHAT_TEXT_SCALE_DEFAULT,
): ChatTextScaleState = remember(initialScale) {
    ChatTextScaleState(initialScale)
}

/**
 * Holds the current assistant text scale and applies pinch deltas to it.
 *
 * [scale] is backed by a snapshot float, so recomposition happens only when the value changes.
 */
@Stable
class ChatTextScaleState(
    initialScale: Float = CHAT_TEXT_SCALE_DEFAULT,
) {
    var scale: Float by mutableFloatStateOf(clampChatTextScale(initialScale))
        private set

    /** Multiplies the current scale by [factor] and clamps the result into the allowed range. */
    fun applyFactor(factor: Float) {
        if (factor.isFinite() && factor > 0f) {
            scale = clampChatTextScale(scale * factor)
        }
    }

    /** Sets the scale directly, clamped into the allowed range. */
    fun setScale(target: Float) {
        scale = clampChatTextScale(target)
    }

    /** Restores [CHAT_TEXT_SCALE_DEFAULT]. */
    fun reset() {
        scale = CHAT_TEXT_SCALE_DEFAULT
    }
}

/**
 * Detects two-finger pinch gestures and feeds the resulting scale factors into [state].
 *
 * The gesture only takes effect while exactly two touch pointers are down; a single finger never
 * satisfies the condition, so ordinary one-finger scrolling and taps keep flowing to whichever
 * modifier in the chain handles them (for example `verticalScroll` or `clickable`).
 */
fun Modifier.chatTextScaleGesture(
    state: ChatTextScaleState,
): Modifier = pointerInput(state) {
    var startDistance: Float? = null
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val touches: List<PointerInputChange> = event.changes.filter {
                it.type == PointerType.Touch && it.pressed
            }
            if (touches.size < 2) {
                // One or zero fingers: not a pinch. Never consume, so scrolling and taps work.
                startDistance = null
                continue
            }
            val distance = touches[0].position.getDistanceTo(touches[1].position)
            val previous = startDistance
            if (previous != null && previous > 0f) {
                state.applyFactor(distance / previous)
            }
            startDistance = distance
        }
    }
}

/** Adds a TalkBack-visible description and equivalent non-gesture actions. */
fun Modifier.chatTextScaleSemantics(
    state: ChatTextScaleState,
    description: String,
    increaseLabel: String,
    decreaseLabel: String,
    resetLabel: String,
): Modifier = semantics {
    contentDescription = description
    customActions = listOf(
        CustomAccessibilityAction(increaseLabel) {
            state.applyFactor(1.1f)
            true
        },
        CustomAccessibilityAction(decreaseLabel) {
            state.applyFactor(1f / 1.1f)
            true
        },
        CustomAccessibilityAction(resetLabel) {
            state.reset()
            true
        },
    )
}

/**
 * Provides [state]'s current scale to all descendant composables through [LocalChatTextScale].
 */
@Composable
fun ProvideChatTextScale(
    state: ChatTextScaleState,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalChatTextScale provides state.scale, content = content)
}

private fun Offset.getDistanceTo(other: Offset): Float {
    val dx = other.x - x
    val dy = other.y - y
    return sqrt(dx * dx + dy * dy)
}
