package com.hermesandroid.relay.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

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
 * A buffered [Channel] is intentional here: notification taps are consumed in
 * `MainActivity.onCreate` before Compose installs RelayApp's collector. A
 * replay-0 SharedFlow drops those cold-start requests when no subscriber exists.
 * The channel retains up to four one-shot routes and hands each to the single
 * app-root collector exactly once.
 */
object NavRouteRequest {
    private val channel = Channel<String>(capacity = 4)

    val requests: Flow<String> = channel.receiveAsFlow()

    /** Fire-and-forget emit. Safe to call from any thread, including the main thread. */
    fun tryRequest(route: String): Boolean = channel.trySend(route).isSuccess

    suspend fun request(route: String) {
        channel.send(route)
    }
}
