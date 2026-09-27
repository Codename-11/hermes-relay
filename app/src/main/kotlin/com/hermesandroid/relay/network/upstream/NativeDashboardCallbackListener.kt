package com.hermesandroid.relay.network.upstream

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.yield

private const val MAX_CALLBACK_CONNECTIONS = 4
private const val REQUEST_DEADLINE_MILLIS = 5_000L

private class PendingNativeCallback(val socket: Socket, val target: String) {
    val consumed = CompletableDeferred<Unit>()
}

/** Bounded concurrent HTTP readers feed exactly one authorization consumer. */
internal suspend fun <T : Any> awaitNativeDashboardCallback(
    server: ServerSocket,
    diagnostic: (String) -> Unit,
    reject: suspend (Socket) -> Unit,
    consume: suspend (Socket, String) -> T?,
): T = coroutineScope {
    val pending = Channel<PendingNativeCallback>(MAX_CALLBACK_CONNECTIONS)
    val slots = Semaphore(MAX_CALLBACK_CONNECTIONS)
    val acceptor = launch(Dispatchers.IO) {
        closingOnCancellation(server) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val socket = try {
                    server.accept()
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw error
                }
                diagnostic("socket_accepted")
                if (!slots.tryAcquire()) {
                    socket.close()
                    diagnostic("request_rejected_capacity")
                    continue
                }
                // Own the descriptor even if cancellation wins before the child is dispatched.
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    try {
                        yield()
                        closingOnCancellation(socket) {
                            currentCoroutineContext().ensureActive()
                            if (socket.inetAddress.hostAddress != "127.0.0.1") {
                                diagnostic("request_rejected_peer")
                                reject(socket)
                                return@closingOnCancellation
                            }
                            val expired = AtomicBoolean(false)
                            val deadline = launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                delay(REQUEST_DEADLINE_MILLIS)
                                expired.set(true)
                                runCatching { socket.close() }
                            }
                            val target = try {
                                readNativeCallbackTarget(socket.getInputStream().buffered(), server.localPort)
                            } catch (error: IOException) {
                                currentCoroutineContext().ensureActive()
                                diagnostic(
                                    if (expired.get()) "request_read_timeout"
                                    else (error as? NativeCallbackRequestException)?.stage ?: "request_read_failed",
                                )
                                if (!expired.get()) reject(socket)
                                return@closingOnCancellation
                            } finally {
                                deadline.cancel()
                            }
                            diagnostic("request_read_completed")
                            val request = PendingNativeCallback(socket, target)
                            pending.send(request)
                            request.consumed.await()
                        }
                    } finally {
                        runCatching { socket.close() }
                        slots.release()
                    }
                }
            }
        }
    }
    try {
        while (true) {
            val request = pending.receive()
            val result = try {
                consume(request.socket, request.target)
            } finally {
                request.consumed.complete(Unit)
            }
            if (result != null) return@coroutineScope result
        }
        @Suppress("UNREACHABLE_CODE")
        error("Callback loop ended")
    } finally {
        acceptor.cancel()
        pending.cancel()
    }
}
