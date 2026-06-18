package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's canonical "loading → confirmed" transition, lifted verbatim from
 * the chat header's skeleton→identity cross-fade (see `ChatScreen`'s
 * `chatHeaderIdentityTransition`) so every server-value reveal reads the same:
 * incoming content fades up from slightly below, outgoing fades up and out.
 *
 * Lives as a shared function so the header, the agent sheet, the context meter,
 * the session drawer, and Manage all animate identically.
 */
fun loadedContentTransform(): ContentTransform =
    (fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 6 }) togetherWith
        (fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it / 8 })

/**
 * Pulsing placeholder bar — the honest "still loading / not yet confirmed"
 * pose. Mirrors the chat header's original `ChatSkeletonLine` byte-for-byte
 * (alpha 0.18↔0.42, 980 ms linear, reverse) so skeletons match everywhere.
 */
@Composable
fun RelaySkeletonLine(
    width: Dp,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
) {
    val transition = rememberInfiniteTransition(label = "relay-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 980, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "relay-skeleton-alpha",
    )
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
    )
}

/**
 * Honest loading wrapper. While [value] is `null` (UNCONFIRMED) it shows
 * [placeholder] — a skeleton / "checking…" pose; when a confirmed value
 * arrives it fades + slides in via [loadedContentTransform]. Value→value
 * changes animate too (e.g. a profile switch swapping the model).
 *
 * **Honesty contract:** callers MUST pass `null` until the value is actually
 * known — never a guessed, cached, or stale value — so the UI never presents
 * unconfirmed server state (model, provider, approvals…) as if it were true.
 * The placeholder is the truthful "we don't know yet" state.
 */
@Composable
fun <T : Any> LoadedFadeIn(
    value: T?,
    modifier: Modifier = Modifier,
    label: String = "loadedFadeIn",
    placeholder: @Composable () -> Unit,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = { loadedContentTransform() },
        label = label,
    ) { current ->
        if (current != null) content(current) else placeholder()
    }
}
