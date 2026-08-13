package com.hermesandroid.relay.network.shared

import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.isValidHermesReach
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

private const val REACH_PROTOCOL_VERSION = 1
private const val REACH_MAX_FRAME_BYTES = 1024 * 1024
internal const val REACH_MAX_QUEUED_FRAMES = 32
internal const val REACH_MAX_QUEUED_BYTES = 8 * 1024 * 1024
private const val REACH_MATCH_TIMEOUT_MS = 10_000L

/**
 * Connection metadata for Hermes Reach's outer WSS rendezvous.
 *
 * This is deliberately transport-only. The inner HTTPS/WSS origin and its
 * pairing-authenticated SPKI pin continue to be owned by [PluginProxyRoutes],
 * so broker reachability can never weaken Secure Link trust.
 */
data class HermesReachRoute(
    val brokerUrl: String,
    val hostId: String,
    val credentialKind: String,
    val token: String,
) {
    fun tunnelUrlOrNull(): String? {
        if (hostId.isBlank() || token.isBlank()) return null
        if (credentialKind !in setOf("bootstrap", "route")) return null
        val uri = runCatching { URI(brokerUrl.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("wss", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        if (!uri.rawUserInfo.isNullOrBlank() || uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.rawPath.orEmpty().let { it.isNotEmpty() && it != "/" && it != "/v1/connect" }) return null
        val authority = buildString {
            append(if (':' in uri.host) "[${uri.host}]" else uri.host)
            if (uri.port > 0 && uri.port != 443) append(":${uri.port}")
        }
        return "wss://$authority/v1/connect"
    }
}

fun EndpointCandidate.hermesReachRouteOrNull(): HermesReachRoute? {
    val metadata = broker?.takeIf { it.isValidHermesReach() } ?: return null
    if (pluginProxyRoutesOrNull() == null) return null
    return HermesReachRoute(
        brokerUrl = metadata.url,
        hostId = metadata.hostId,
        credentialKind = metadata.credentialKind,
        token = metadata.token,
    )
}

/** Build the pinned inner Secure Link client over an outer Hermes Reach WSS. */
fun buildHermesReachClient(
    baseBuilder: OkHttpClient.Builder,
    outerClient: OkHttpClient,
    candidate: EndpointCandidate,
    sessionTokenProvider: () -> String?,
    includeRelaySessionHeader: Boolean = true,
): OkHttpClient? {
    val secureLink = candidate.pluginProxyRoutesOrNull() ?: return null
    val reach = candidate.hermesReachRouteOrNull() ?: return null
    return buildPluginProxyClient(
        baseBuilder = baseBuilder,
        routes = secureLink,
        sessionTokenProvider = sessionTokenProvider,
        includeRelaySessionHeader = includeRelaySessionHeader,
        rawSocketFactory = HermesReachSocketFactory(outerClient, reach),
    )
}

@Serializable
private data class ReachRegistration(
    val type: String = "register",
    @SerialName("protocol_version") val protocolVersion: Int = REACH_PROTOCOL_VERSION,
    val role: String = "client",
    @SerialName("host_id") val hostId: String,
    @SerialName("connection_id") val connectionId: String,
    @SerialName("credential_kind") val credentialKind: String,
    val token: String,
)

@Serializable
private data class ReachControl(
    val type: String? = null,
    @SerialName("protocol_version") val protocolVersion: Int? = null,
    @SerialName("stream_id") val streamId: String? = null,
    val code: String? = null,
)

internal object HermesReachHandshake {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    fun registration(route: HermesReachRoute, connectionId: String): String = json.encodeToString(
        ReachRegistration(
            hostId = route.hostId,
            connectionId = connectionId,
            credentialKind = route.credentialKind,
            token = route.token,
        ),
    )

    fun validateMatched(payload: String): String? {
        val control = runCatching { json.decodeFromString<ReachControl>(payload) }
            .getOrElse { return "Hermes Reach returned an invalid match response" }
        if (control.type == "error") {
            return "Hermes Reach rejected the route (${control.code ?: "unknown"})"
        }
        val streamIdValid = control.streamId?.let(::isCanonicalId) == true
        if (control.type != "matched" ||
            control.protocolVersion != REACH_PROTOCOL_VERSION ||
            !streamIdValid
        ) {
            return "Hermes Reach returned a mismatched route response"
        }
        return null
    }

    private fun isCanonicalId(value: String): Boolean {
        if (value.isBlank() || '=' in value) return false
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() ?: return false
        return decoded.size == 16 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value
    }
}

/**
 * Raw socket factory that carries bytes through Hermes Reach. OkHttp layers
 * the normal Secure Link TLS socket factory over the returned socket, so SNI,
 * hostname verification, and the QR SPKI pin all apply to the inner endpoint.
 */
class HermesReachSocketFactory(
    private val outerClient: OkHttpClient,
    private val route: HermesReachRoute,
) : SocketFactory() {
    init {
        require(route.tunnelUrlOrNull() != null) { "Invalid Hermes Reach route" }
    }

    override fun createSocket(): Socket = HermesReachSocket(outerClient, route)

    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            if (localHost != null) bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = createSocket().apply {
        if (localAddress != null) bind(InetSocketAddress(localAddress, localPort))
        connect(InetSocketAddress(address, port))
    }
}

private class HermesReachSocket(
    private val outerClient: OkHttpClient,
    private val route: HermesReachRoute,
) : Socket() {
    private val inbound = ReachInputStream()
    private val matchLatch = CountDownLatch(1)
    private val connectionId = randomConnectionId()
    @Volatile private var matchError: IOException? = null
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var closed = false
    @Volatile private var matched = false
    @Volatile private var remote: InetSocketAddress? = null
    private var readTimeoutMs: Int = 0

    private val outbound = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            if (!matched || closed) throw SocketException("Hermes Reach tunnel is not open")
            var cursor = offset
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, REACH_MAX_FRAME_BYTES)
                val accepted = webSocket?.send(ByteString.of(*bytes.copyOfRange(cursor, cursor + count))) == true
                if (!accepted) throw SocketException("Hermes Reach could not queue tunnel bytes")
                cursor += count
                remaining -= count
            }
        }
    }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, REACH_MATCH_TIMEOUT_MS.toInt())

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (connected) throw SocketException("Socket is already connected")
        if (closed) throw SocketException("Socket is closed")
        remote = endpoint as? InetSocketAddress
            ?: throw SocketException("Hermes Reach requires an internet socket target")
        val request = Request.Builder().url(requireNotNull(route.tunnelUrlOrNull())).build()
        webSocket = outerClient.newWebSocket(request, listener)
        val waitMs = minOf(
            timeout.takeIf { it > 0 }?.toLong() ?: REACH_MATCH_TIMEOUT_MS,
            REACH_MATCH_TIMEOUT_MS,
        )
        if (!matchLatch.await(waitMs, TimeUnit.MILLISECONDS)) {
            closeWithError(IOException("Hermes Reach host match timed out"))
        }
        matchError?.let { throw it }
        if (!matched) throw IOException("Hermes Reach closed before matching the host")
        connected = true
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val registration = HermesReachHandshake.registration(route, connectionId)
            if (!webSocket.send(registration)) {
                closeWithError(IOException("Hermes Reach registration could not be sent"))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (matched) {
                closeWithError(IOException("Hermes Reach sent text after matching"))
                return
            }
            HermesReachHandshake.validateMatched(text)?.let { message ->
                closeWithError(IOException(message))
                return
            }
            matched = true
            matchLatch.countDown()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!matched) {
                closeWithError(IOException("Hermes Reach sent bytes before matching"))
                return
            }
            if (bytes.size > REACH_MAX_FRAME_BYTES) {
                closeWithError(IOException("Hermes Reach frame exceeds 1 MiB"))
                return
            }
            if (!inbound.offer(bytes.toByteArray())) {
                closeWithError(IOException("Hermes Reach receive queue exceeded its safe limit"))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!matched) matchError = IOException("Hermes Reach closed before matching the host")
            closed = true
            inbound.close(matchError)
            matchLatch.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closeWithError(IOException("Hermes Reach connection failed", t))
        }
    }

    private fun closeWithError(error: IOException) {
        matchError = error
        closed = true
        webSocket?.cancel()
        inbound.close(error)
        matchLatch.countDown()
    }

    override fun getInputStream(): InputStream {
        if (!connected || closed) throw SocketException("Hermes Reach tunnel is not open")
        inbound.readTimeoutMs = readTimeoutMs
        return inbound
    }

    override fun getOutputStream(): OutputStream {
        if (!connected || closed) throw SocketException("Hermes Reach tunnel is not open")
        return outbound
    }

    override fun close() {
        if (closed) return
        closed = true
        webSocket?.close(1000, null)
        inbound.close(null)
        matchLatch.countDown()
    }

    override fun isConnected(): Boolean = connected
    override fun isClosed(): Boolean = closed
    override fun getRemoteSocketAddress(): SocketAddress? = remote
    override fun getInetAddress(): InetAddress? = remote?.address
    override fun getPort(): Int = remote?.port ?: 0
    override fun setSoTimeout(timeout: Int) { readTimeoutMs = timeout }
    override fun getSoTimeout(): Int = readTimeoutMs
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun getTcpNoDelay(): Boolean = true
    override fun setKeepAlive(on: Boolean) = Unit
    override fun getKeepAlive(): Boolean = true
    override fun setReuseAddress(on: Boolean) = Unit
    override fun getReuseAddress(): Boolean = false
}

internal class ReachInputStream : InputStream() {
    private val chunks = ArrayDeque<ByteArray>()
    private var offset = 0
    private var queuedBytes = 0
    private var terminalError: IOException? = null
    private var closed = false
    @Volatile var readTimeoutMs: Int = 0

    @Synchronized
    fun offer(bytes: ByteArray): Boolean {
        if (closed) return false
        if (chunks.size >= REACH_MAX_QUEUED_FRAMES || queuedBytes + bytes.size > REACH_MAX_QUEUED_BYTES) {
            return false
        }
        chunks.addLast(bytes)
        queuedBytes += bytes.size
        (this as java.lang.Object).notifyAll()
        return true
    }

    @Synchronized
    fun close(error: IOException?) {
        if (closed) return
        closed = true
        terminalError = error
        (this as java.lang.Object).notifyAll()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    @Synchronized
    override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
        if (length == 0) return 0
        val started = System.nanoTime()
        while (chunks.isEmpty() && !closed) {
            val waitMs = if (readTimeoutMs > 0) {
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                (readTimeoutMs - elapsed).coerceAtLeast(0)
            } else 0L
            if (readTimeoutMs > 0 && waitMs == 0L) throw java.net.SocketTimeoutException("Hermes Reach read timed out")
            (this as java.lang.Object).wait(if (readTimeoutMs > 0) waitMs else 0L)
        }
        if (chunks.isEmpty()) {
            terminalError?.let { throw it }
            return -1
        }
        val chunk = chunks.first()
        val count = minOf(length, chunk.size - offset)
        chunk.copyInto(target, targetOffset, offset, offset + count)
        offset += count
        queuedBytes -= count
        if (offset == chunk.size) {
            chunks.removeFirst()
            offset = 0
        }
        return count
    }
}

private fun randomConnectionId(): String {
    val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
