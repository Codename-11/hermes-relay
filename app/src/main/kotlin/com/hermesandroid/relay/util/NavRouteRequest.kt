package com.hermesandroid.relay.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Cross-layer one-shot navigation requests.
 *
 * `RelayApp` collects from [requests] and forwards each emitted route to its
 * `NavController`. Anything that needs to navigate the user from outside the
 * Compose tree — a foreground service notification action, a broadcast
 * receiver, an `onNewIntent` deep-link — emits via [tryRequest] (or
 * [request] from a coroutine).
 *
 * Used by Phase 3 / Wave 2 / safety-rails to deep-link the
 * `BridgeForegroundService` notification's "Settings" action straight to
 * `BridgeSafetySettingsScreen` instead of dropping the user on `MainActivity`'s
 * home screen.
 *
 * Buffer: `extraBufferCapacity = 4` so back-to-back tryEmit calls during
 * `onCreate → setContent` don't drop on the floor before `RelayApp`'s
 * collector subscribes. Replay 0 — late subscribers shouldn't replay stale
 * navigation intents from prior process lifetimes.
 */
object NavRouteRequest {
    private val _requests = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
    )

    val requests: SharedFlow<String> = _requests.asSharedFlow()

    /** Fire-and-forget emit. Safe to call from any thread, including the main thread. */
    fun tryRequest(route: String): Boolean = _requests.tryEmit(route)

    suspend fun request(route: String) {
        _requests.emit(route)
    }
}
