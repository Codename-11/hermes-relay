package com.hermesandroid.relay.diagnostics

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps network failure classes to narrow, truthful next steps.
 *
 * These messages are diagnostic guidance, not recovery behavior: callers still
 * own retries, routing, and authentication. Walking the cause chain preserves
 * useful classification when OkHttp or a coroutine boundary wraps the socket
 * exception in a higher-level failure.
 */
object NetworkDiagnosticGuidance {
    fun forThrowable(throwable: Throwable, target: String): String? {
        val causes = generateSequence(throwable as Throwable?) { it.cause }.take(12).toList()
        return when {
            causes.any { it is ConnectException } ->
                "Verify $target is running and listening on the configured host and port."
            causes.any { it is UnknownHostException } ->
                "Verify the configured hostname resolves from this device."
            causes.any { it is NoRouteToHostException } ->
                "Verify the device has a network path to the configured host."
            causes.any { it is SocketTimeoutException } ->
                "Check network routing or firewall rules between this device and $target."
            causes.any { it is SSLException } ->
                "Verify the TLS scheme, certificate, and trust configuration for $target."
            else -> null
        }
    }

    fun forHttpStatus(statusCode: Int, target: String): String? = when (statusCode) {
        401, 403 -> "Verify the configured $target credentials or pair the device again."
        404 -> "Verify this URL points to the expected $target route and version."
        429 -> "Wait for the server's backoff period before retrying."
        in 500..599 -> "Check the $target server logs for the failing request."
        else -> null
    }
}
