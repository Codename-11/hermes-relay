package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.BuildConfig
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString

private const val CALLBACK_PATH = "/callback"
private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
private const val MAX_HEADER_BYTES = 16 * 1024
private const val ACCEPT_POLL_MILLIS = 500
internal const val DEFAULT_NATIVE_SIGN_IN_TIMEOUT_MILLIS = 5 * 60 * 1000L
internal val NATIVE_SIGN_IN_RETURN_URI = "${BuildConfig.APPLICATION_ID}://return"

internal class NativeDashboardSignInTimeoutException :
    InterruptedIOException("Dashboard sign-in timed out")

private enum class CallbackPage(
    val modifier: String,
    val eyebrow: String,
    val title: String,
    val message: String,
    val guidance: String,
) {
    Success(
        modifier = "success",
        eyebrow = "Secure sign-in",
        title = "Sign-in complete",
        message = "Your secure session is ready in Hermes Relay.",
        guidance = "Return to Hermes Relay to continue.",
    ),
    Failure(
        modifier = "failure",
        eyebrow = "Secure sign-in",
        title = "Sign-in needs another try",
        message = "No session details were saved from this attempt.",
        guidance = "Return to Hermes Relay and start sign-in again.",
    ),
    AuthorizationRejected(
        modifier = "failure",
        eyebrow = "Provider sign-in",
        title = "Sign-in was not completed",
        message = "The provider returned without an approved authorization for Hermes.",
        guidance = "Return to Hermes Relay and start again if you still want to sign in.",
    ),
    CodeRejected(
        modifier = "failure",
        eyebrow = "Hosted Hermes callback",
        title = "Hermes rejected the sign-in code",
        message = "Google sign-in finished, but hosted Hermes could not exchange its one-time callback code for a session.",
        guidance = "Return to Hermes Relay and start a fresh sign-in attempt.",
    ),
    GatewayUnavailable(
        modifier = "failure",
        eyebrow = "Hosted Hermes callback",
        title = "Hosted Hermes could not finish sign-in",
        message = "The callback reached Hermes Relay, but the hosted Hermes sign-in service was unavailable.",
        guidance = "Return to Hermes Relay, wait a moment, and try again.",
    ),
    TransportFailure(
        modifier = "failure",
        eyebrow = "Secure sign-in connection",
        title = "Could not reach hosted Hermes",
        message = "Google sign-in finished, but the secure connection back to hosted Hermes was interrupted.",
        guidance = "Return to Hermes Relay and retry on a stable connection.",
    ),
    ResponseUnsupported(
        modifier = "failure",
        eyebrow = "Hosted Hermes callback",
        title = "Hermes returned an unsupported session",
        message = "The hosted gateway answered, but its sign-in response was not compatible with this app.",
        guidance = "Return to Hermes Relay and check for app and hosted Hermes updates.",
    ),
    SessionStorageFailure(
        modifier = "failure",
        eyebrow = "Secure session storage",
        title = "The session could not be saved",
        message = "Google sign-in finished, but Android could not securely save the Hermes session on this device.",
        guidance = "Return to Hermes Relay and try again. If it repeats, check the app's diagnostics.",
    ),
    Rejected(
        modifier = "rejected",
        eyebrow = "Protected callback",
        title = "Callback not accepted",
        message = "Hermes Relay ignored this request to protect your sign-in.",
        guidance = "Return to the app and continue the sign-in already in progress.",
    ),
}

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

/** Match upstream Desktop's capability-driven redirect policy. */
internal fun androidDashboardRedirectAuthMode(
    @Suppress("UNUSED_PARAMETER") providerName: String,
    authFlows: List<String>,
    @Suppress("UNUSED_PARAMETER") competingRedirectProviders: Int = 1,
): DashboardRedirectAuthMode = dashboardRedirectAuthMode(authFlows)

/**
 * Hosted gateways commonly expose Nous as their single native provider and
 * require the selector to be omitted. Multi-provider self-hosted gateways need
 * the explicit selector so upstream can disambiguate the requested provider.
 */
internal fun nativeDashboardAuthorizationProvider(
    providerName: String,
    competingRedirectProviders: Int,
): String? = providerName.takeUnless {
    it.equals("nous", ignoreCase = true) && competingRedirectProviders <= 1
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
        onAuthorizationPrepared: (usesAlternateOrigin: Boolean) -> Unit = {},
        onCallbackValidated: () -> Unit = {},
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
                            runCatching {
                                onAuthorizationPrepared(authorization.usesAlternateOrigin)
                            }
                            launchAuthorization(authorization.authorizationUrl)
                            awaitValidCallback(
                                server = server,
                                authorization = authorization,
                                commitAllowed = { attemptContext.isActive },
                                onCallbackValidated = onCallbackValidated,
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
            throw NativeDashboardSignInTimeoutException()
        }

    private suspend fun awaitValidCallback(
        server: ServerSocket,
        authorization: NativeDashboardAuthorization,
        commitAllowed: () -> Boolean,
        onCallbackValidated: () -> Unit,
    ): NativeDashboardTokens {
        while (true) {
            val callback = acceptCallback(server)
            val tokens = callback.use { socket ->
                if (socket.inetAddress.hostAddress != "127.0.0.1") {
                    writeResponse(
                        socket,
                        status = "403 Forbidden",
                        page = CallbackPage.Rejected,
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
                        page = CallbackPage.Rejected,
                    )
                    return@use null
                }
                try {
                    authClient.exchangeCallback(
                        authorization,
                        target,
                        commitAllowed = commitAllowed,
                        onValidated = onCallbackValidated,
                    ).also {
                        writeResponse(
                            socket,
                            status = "200 OK",
                            page = CallbackPage.Success,
                        )
                    }
                } catch (error: NativeDashboardCallbackException) {
                    if (error.retryable) {
                        writeResponse(
                            socket,
                            status = "400 Bad Request",
                            page = CallbackPage.Rejected,
                        )
                        return@use null
                    }
                    writeResponse(
                        socket,
                        status = "400 Bad Request",
                        page = CallbackPage.AuthorizationRejected,
                    )
                    throw error
                } catch (error: Exception) {
                    writeResponse(
                        socket,
                        status = "400 Bad Request",
                        page = callbackFailurePage(error),
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

    private fun writeResponse(socket: Socket, status: String, page: CallbackPage) {
        val html = callbackPageHtml(page).toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(html.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Pragma: no-cache\r\n")
            append("Expires: 0\r\n")
            append("Content-Security-Policy: ").append(CALLBACK_CSP).append("\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Permissions-Policy: camera=(), geolocation=(), microphone=(), payment=(), usb=()\r\n")
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

    private fun callbackFailurePage(error: Throwable): CallbackPage {
        val stage = nativeDashboardSignInFailureStage(error)
        return when {
            stage == "token_http_400" -> CallbackPage.CodeRejected
            stage == "callback_error" -> CallbackPage.AuthorizationRejected
            stage == "token_http_429" || stage.startsWith("token_http_5") ->
                CallbackPage.GatewayUnavailable
            stage == "token_shape" -> CallbackPage.ResponseUnsupported
            stage == "token_store" -> CallbackPage.SessionStorageFailure
            stage.startsWith("token_transport") -> CallbackPage.TransportFailure
            else -> CallbackPage.Failure
        }
    }

    private fun callbackPageHtml(page: CallbackPage): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
          <meta name="color-scheme" content="dark">
          <title>${page.title} · Hermes Relay</title>
          <style>$CALLBACK_STYLE</style>
        </head>
        <body>
          <main class="shell">
            <section class="card ${page.modifier}" aria-labelledby="page-title" aria-describedby="page-message page-guidance">
              <div class="status-rail" aria-hidden="true"></div>
              <p class="brand">Hermes Relay</p>
              <p class="eyebrow">${page.eyebrow}</p>
              <h1 id="page-title" tabindex="-1">${page.title}</h1>
              <p id="page-message" class="message">${page.message}</p>
              <p id="page-guidance" class="guidance">${page.guidance}</p>
              <a class="return-link" href="$NATIVE_SIGN_IN_RETURN_URI">Return to Hermes Relay</a>
            </section>
          </main>
        </body>
        </html>
    """.trimIndent()

    private companion object {
        private val CALLBACK_STYLE = """
            :root{color-scheme:dark;font-family:ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#08090d;color:#f7f4ff}
            *{box-sizing:border-box}
            body{margin:0;min-height:100vh;min-height:100svh;background:#08090d}
            .shell{min-height:100vh;min-height:100svh;display:grid;place-items:center;padding:max(24px,env(safe-area-inset-top)) max(20px,env(safe-area-inset-right)) max(24px,env(safe-area-inset-bottom)) max(20px,env(safe-area-inset-left))}
            .card{position:relative;width:min(100%,460px);overflow:hidden;border:1px solid #2d2938;border-radius:18px;background:#111219;padding:32px 28px 28px;box-shadow:0 18px 48px rgba(0,0,0,.34);animation:card-in 220ms ease-out both}
            .status-rail{position:absolute;inset:0 auto 0 0;width:4px;background:#9b6bf0}
            .success .status-rail{background:#55d98b}.failure .status-rail{background:#ff7188}
            .brand{margin:0 0 28px;color:#bba4f5;font-size:.78rem;font-weight:750;letter-spacing:.12em;text-transform:uppercase}
            .eyebrow{margin:0 0 8px;color:#cac4d8;font-size:.9rem;font-weight:650}
            h1{margin:0;color:#fff;font-size:clamp(1.8rem,8vw,2.45rem);font-weight:720;letter-spacing:-.035em;line-height:1.08;outline:none}
            h1:focus-visible{outline:2px solid #9b6bf0;outline-offset:6px;border-radius:3px}
            .message{margin:18px 0 0;color:#eeeaf7;font-size:1.04rem;line-height:1.6}
            .guidance{margin:12px 0 0;color:#bdb7c9;font-size:.95rem;line-height:1.55}
            .return-link{display:block;width:100%;margin-top:26px;border:1px solid #8c5ef0;border-radius:12px;background:#6b35e8;color:#fff;padding:13px 18px;font:inherit;font-weight:700;text-align:center;text-decoration:none;cursor:pointer}
            .return-link:hover{border-color:#c4a8ff}.return-link:focus-visible{outline:3px solid #c4a8ff;outline-offset:3px}.return-link:active{background:#5d28d3}
            @keyframes card-in{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
            @media (prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;scroll-behavior:auto!important;transition:none!important}}
            @media (max-width:380px){.card{padding:28px 22px 24px;border-radius:16px}}
        """.trimIndent()

        private fun cspHash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toByteString()
            .base64()

        private val CALLBACK_CSP = buildString {
            append("default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; ")
            append("img-src 'none'; connect-src 'none'; object-src 'none'; script-src 'none'; ")
            append("style-src 'sha256-").append(cspHash(CALLBACK_STYLE)).append("'")
        }
    }
}
