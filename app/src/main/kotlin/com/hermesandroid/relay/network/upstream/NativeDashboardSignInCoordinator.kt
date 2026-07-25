package com.hermesandroid.relay.network.upstream

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val CALLBACK_PATH = "/callback"
private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
private const val MAX_HEADER_BYTES = 16 * 1024
private const val ACCEPT_POLL_MILLIS = 500
internal const val DEFAULT_NATIVE_SIGN_IN_TIMEOUT_MILLIS = 2 * 60 * 1000L

internal enum class DashboardRedirectAuthMode {
    NativePkce,
    WebView,
}

internal fun dashboardRedirectAuthMode(authFlows: List<String>): DashboardRedirectAuthMode =
    if ("native_pkce" in authFlows) {
        DashboardRedirectAuthMode.NativePkce
    } else {
        DashboardRedirectAuthMode.WebView
    }

/**
 * Owns one native dashboard sign-in attempt.
 *
 * The listener and PKCE authorization are both local to [signIn], so leaving
 * the screen, cancellation, timeout, or callback completion closes the port
 * and discards verifier/state. Nothing secret enters Compose or saved state.
 */
class NativeDashboardSignInCoordinator(
    private val authClient: NativeDashboardAuthClient,
    private val timeoutMillis: Long = DEFAULT_NATIVE_SIGN_IN_TIMEOUT_MILLIS,
    private val serverSocketFactory: () -> ServerSocket = ::ServerSocket,
) {
    suspend fun signIn(
        provider: String?,
        launchAuthorization: suspend (String) -> Unit,
    ): NativeDashboardTokens =
        try {
            withContext(Dispatchers.IO) {
                withTimeout(timeoutMillis) {
                    serverSocketFactory().use { server ->
                        server.reuseAddress = false
                        server.bind(
                            InetSocketAddress(
                                InetAddress.getByName("127.0.0.1"),
                                0,
                            ),
                            1,
                        )
                        server.soTimeout = ACCEPT_POLL_MILLIS
                        check(server.inetAddress.hostAddress == "127.0.0.1") {
                            "Native sign-in listener did not bind to IPv4 loopback"
                        }

                        val redirectUri = "http://127.0.0.1:${server.localPort}$CALLBACK_PATH"
                        val authorization = authClient.beginAuthorization(redirectUri, provider)
                        val attemptContext = currentCoroutineContext()
                        var completed = false
                        try {
                            launchAuthorization(authorization.authorizationUrl)
                            awaitValidCallback(
                                server = server,
                                authorization = authorization,
                                commitAllowed = { attemptContext.isActive },
                            ).also { completed = true }
                        } finally {
                            if (!completed) {
                                authClient.cancelAuthorization(authorization)
                            }
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw IOException("Dashboard sign-in timed out")
        }

    private suspend fun awaitValidCallback(
        server: ServerSocket,
        authorization: NativeDashboardAuthorization,
        commitAllowed: () -> Boolean,
    ): NativeDashboardTokens {
        while (true) {
            val callback = acceptCallback(server)
            val tokens = callback.use { socket ->
                if (socket.inetAddress.hostAddress != "127.0.0.1") {
                    writeResponse(
                        socket,
                        status = "403 Forbidden",
                        body = "This sign-in callback was not accepted.",
                    )
                    return@use null
                }
                val target = try {
                    readCallbackTarget(
                        input = socket.getInputStream(),
                        expectedPort = server.localPort,
                    )
                } catch (_: IOException) {
                    writeResponse(
                        socket,
                        status = "400 Bad Request",
                        body = "This sign-in callback was not accepted.",
                    )
                    return@use null
                }
                try {
                    authClient.exchangeCallback(
                        authorization,
                        target,
                        commitAllowed = commitAllowed,
                    ).also {
                        writeResponse(
                            socket,
                            status = "200 OK",
                            body = "Sign-in complete. You can return to Hermes Relay.",
                        )
                    }
                } catch (error: NativeDashboardCallbackException) {
                    if (error.retryable) {
                        writeResponse(
                            socket,
                            status = "400 Bad Request",
                            body = "This sign-in callback was not accepted.",
                        )
                        return@use null
                    }
                    writeResponse(
                        socket,
                        status = "400 Bad Request",
                        body = "Sign-in could not be completed. Return to Hermes Relay and try again.",
                    )
                    throw error
                } catch (error: Exception) {
                    writeResponse(
                        socket,
                        status = "400 Bad Request",
                        body = "Sign-in could not be completed. Return to Hermes Relay and try again.",
                    )
                    throw error
                }
            }
            if (tokens != null) return tokens
        }
    }

    private suspend fun acceptCallback(server: ServerSocket): Socket {
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return server.accept().apply { soTimeout = 5_000 }
            } catch (_: SocketTimeoutException) {
                // Poll so coroutine cancellation closes the lifecycle-owned listener promptly.
            }
        }
    }

    private fun readCallbackTarget(input: InputStream, expectedPort: Int): String {
        val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_BYTES)
            ?: throw IOException("Native sign-in callback was empty")
        val requestParts = requestLine.split(' ')
        if (requestParts.size != 3 || requestParts[0] != "GET" ||
            !requestParts[1].startsWith("/") ||
            !requestParts[2].startsWith("HTTP/1.")
        ) {
            throw IOException("Native sign-in callback request was malformed")
        }

        var headerBytes = 0
        var host: String? = null
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_BYTES - headerBytes)
                ?: throw IOException("Native sign-in callback headers were incomplete")
            headerBytes += line.length + 2
            if (line.isEmpty()) break
            if (line.startsWith("Host:", ignoreCase = true)) {
                host = line.substringAfter(':').trim()
            }
            if (headerBytes >= MAX_HEADER_BYTES) {
                throw IOException("Native sign-in callback headers were too large")
            }
        }
        if (host != "127.0.0.1:$expectedPort") {
            throw IOException("Native sign-in callback host was not accepted")
        }
        return requestParts[1]
    }

    private fun readAsciiLine(input: InputStream, limit: Int): String? {
        if (limit <= 0) throw IOException("Native sign-in callback was too large")
        val bytes = ArrayList<Byte>(minOf(limit, 128))
        var previous = -1
        while (bytes.size < limit) {
            val current = input.read()
            if (current == -1) return if (bytes.isEmpty()) null else throw IOException(
                "Native sign-in callback ended unexpectedly",
            )
            if (previous == '\r'.code && current == '\n'.code) {
                bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            bytes += current.toByte()
            previous = current
        }
        throw IOException("Native sign-in callback line was too large")
    }

    private fun writeResponse(socket: Socket, status: String, body: String) {
        val html = """
            <!doctype html>
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body><p>${escapeHtml(body)}</p></body></html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(html.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        runCatching {
            socket.getOutputStream().apply {
                write(headers)
                write(html)
                flush()
            }
        }
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
