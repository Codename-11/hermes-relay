package com.hermesandroid.relay.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

/**
 * Frame-throttled stand-in for `rememberInfiniteTransition` for slow, ambient
 * effects — heartbeat dots, banner glows, drifting orbs. Returns a phase that
 * loops `0f → 1f` every [periodMillis], advanced at roughly [fps] instead of
 * the display refresh rate.
 *
 * Why this exists: on Android 15 the platform logs `setRequestedFrameRate` on
 * every Compose draw pass (and on Samsung builds at INFO level). An
 * always-visible `rememberInfiniteTransition` pins the entire window at the
 * panel's refresh (e.g. 120Hz) for as long as it's composed — flooding logcat
 * and burning battery to animate motion the eye can't resolve at full rate
 * anyway. ~30fps is imperceptible for a multi-second pulse.
 *
 * When [running] is false the phase holds at `0f` and the loop parks (no frames
 * requested), so a hidden/idle effect costs nothing.
 *
 * Linear by design (matches the `LinearEasing` + `RepeatMode.Restart` the old
 * infinite transitions used). Map the phase to your value range at the call
 * site, e.g. `1f + 0.8f * phase` for a 1f→1.8f scale.
 */
@Composable
fun rememberAmbientPhase(
    periodMillis: Int,
    fps: Int = 30,
    running: Boolean = true,
): Float {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMillis, fps, running) {
        if (!running || periodMillis <= 0) {
            phase.floatValue = 0f
            return@LaunchedEffect
        }
        val frameIntervalMs = (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)
        var lastNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dtMs = (now - lastNanos).coerceAtLeast(0L) / 1_000_000f
            lastNanos = now
            phase.floatValue = (phase.floatValue + dtMs / periodMillis) % 1f
            delay(frameIntervalMs)
        }
    }
    return phase.floatValue
}
