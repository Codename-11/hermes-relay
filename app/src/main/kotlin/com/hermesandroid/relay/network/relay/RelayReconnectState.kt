package com.hermesandroid.relay.network.relay

/**
 * Route-aware retry state for the Relay WebSocket.
 *
 * Automatic LAN/Tailscale fallback keeps the accumulated reconnect attempt so
 * swapping URLs cannot restart exponential backoff. Socket-failure streaks are
 * scoped to one URL, so failures on different roles cannot combine and poison
 * the newly selected route.
 */
internal class RelayReconnectState {
    @Volatile
    var reconnectAttempt: Int = 0
        private set

    private var socketFailureRoute: String? = null
    private var consecutiveSocketFailures: Int = 0

    @Synchronized
    fun beginExplicitConnect(route: String) {
        reconnectAttempt = 0
        resetSocketFailures(route)
    }

    @Synchronized
    fun beginAutomaticRouteSwap(route: String) {
        resetSocketFailures(route)
    }

    @Synchronized
    fun nextReconnectAttempt(): Int {
        reconnectAttempt++
        return reconnectAttempt
    }

    @Synchronized
    fun recordSocketFailure(route: String): Int {
        if (socketFailureRoute != route) {
            resetSocketFailures(route)
        }
        consecutiveSocketFailures++
        return consecutiveSocketFailures
    }

    @Synchronized
    fun connected(route: String) {
        reconnectAttempt = 0
        resetSocketFailures(route)
    }

    @Synchronized
    fun reset() {
        reconnectAttempt = 0
        resetSocketFailures(null)
    }

    private fun resetSocketFailures(route: String?) {
        socketFailureRoute = route
        consecutiveSocketFailures = 0
    }
}
