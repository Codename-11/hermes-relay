package com.hermesandroid.relay.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/** Canonical Relay routes derived from one operator- or pairing-supplied URL. */
data class RelayEndpoints(
    val httpBaseUrl: String,
    val webSocketBaseUrl: String,
    val webSocketUrl: String,
    val healthUrl: String,
)

/**
 * Parses the accepted Relay URL forms and derives every route from one base.
 *
 * The input may identify the route base, its terminal `/ws` endpoint, or its
 * terminal `/health` endpoint, using either HTTP(S) or WS(S). The final
 * `ws`/`health` segment is removed before both canonical routes are rebuilt,
 * which makes the operation idempotent and preserves reverse-proxy prefixes.
 */
object RelayEndpointContract {
    private val encodedAmbiguousPathByte = Regex("%(?:2e|2f|5c)", RegexOption.IGNORE_CASE)

    fun parseOrNull(raw: String?): RelayEndpoints? = runCatching { parse(raw) }.getOrNull()

    fun parse(raw: String?): RelayEndpoints {
        val input = raw?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Relay URL is empty")
        val uri = runCatching { URI(input) }.getOrElse {
            throw IllegalArgumentException("Relay URL is malformed")
        }
        val sourceScheme = uri.scheme?.lowercase()
        val secure = when (sourceScheme) {
            "https", "wss" -> true
            "http", "ws" -> false
            else -> throw IllegalArgumentException("Relay URL must use HTTP(S) or WS(S)")
        }
        if (uri.host.isNullOrBlank() || uri.rawAuthority.isNullOrBlank() || uri.isOpaque) {
            throw IllegalArgumentException("Relay URL has no valid host")
        }
        if (uri.rawUserInfo != null) {
            throw IllegalArgumentException("Relay URL must not contain user info")
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            throw IllegalArgumentException("Relay URL must not contain a query or fragment")
        }
        if (uri.port == 0 || uri.port > 65_535) {
            throw IllegalArgumentException("Relay URL has an invalid port")
        }

        val rawPath = uri.rawPath.orEmpty()
        if ('\\' in rawPath || "//" in rawPath || encodedAmbiguousPathByte.containsMatchIn(rawPath)) {
            throw IllegalArgumentException("Relay URL contains an ambiguous path")
        }
        val trimmedPath = rawPath.trimEnd('/')
        val pathSegments = trimmedPath.split('/').filter { it.isNotEmpty() }
        if (pathSegments.any { it == "." || it == ".." }) {
            throw IllegalArgumentException("Relay URL contains a relative path segment")
        }
        val baseSegments = if (pathSegments.lastOrNull() in setOf("ws", "health")) {
            pathSegments.dropLast(1)
        } else {
            pathSegments
        }
        val basePath = baseSegments.joinToString(separator = "/", prefix = "/")
            .takeUnless { it == "/" }
            .orEmpty()
        val httpScheme = if (secure) "https" else "http"
        val webSocketScheme = if (secure) "wss" else "ws"
        val httpBase = "$httpScheme://${uri.rawAuthority}$basePath"
        val webSocketBase = "$webSocketScheme://${uri.rawAuthority}$basePath"
        val webSocket = "$webSocketBase/ws"
        val health = "$httpBase/health"

        if (httpBase.toHttpUrlOrNull() == null || health.toHttpUrlOrNull() == null) {
            throw IllegalArgumentException("Relay URL is malformed")
        }
        return RelayEndpoints(
            httpBaseUrl = httpBase,
            webSocketBaseUrl = webSocketBase,
            webSocketUrl = webSocket,
            healthUrl = health,
        )
    }
}
