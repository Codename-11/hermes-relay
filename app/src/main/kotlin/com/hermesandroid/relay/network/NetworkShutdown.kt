package com.hermesandroid.relay.network

import android.os.Looper

/**
 * Run an OkHttp teardown [block] without ever performing a network write on
 * the main thread.
 *
 * [okhttp3.ConnectionPool.evictAll] closes pooled sockets synchronously. For
 * a live `https`/`wss` keep-alive connection that close drains the SSL output
 * queue — a real network write (`SSLOutputStream.writeInternal`) — which trips
 * StrictMode's [android.os.NetworkOnMainThreadException]. Reported as a hard
 * crash on connect over TLS/Tailscale (issues #70 / #118 / #124): a
 * `viewModelScope` (i.e. `Dispatchers.Main.immediate`) coroutine resumes on the
 * main thread and shuts a dashboard/API client down in a `finally` block.
 *
 * Client shutdown is fire-and-forget cleanup, so when the caller is on the main
 * thread we hand [block] to a short-lived daemon thread. Off the main thread
 * (already on `Dispatchers.IO` or a background thread) we run it inline so
 * callers that deliberately moved off main keep their ordering and any blocking
 * `awaitTermination` waits stay where the caller put them.
 */
internal fun shutdownOffMainThread(threadName: String, block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Thread({ runCatching(block) }, threadName).apply { isDaemon = true }.start()
    } else {
        block()
    }
}
