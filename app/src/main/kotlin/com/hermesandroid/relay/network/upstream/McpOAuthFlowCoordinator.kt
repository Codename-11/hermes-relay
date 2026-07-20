package com.hermesandroid.relay.network.upstream

import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/**
 * Ephemeral hosted MCP OAuth driver. The opaque flow id and authorization URL
 * remain in memory only; OAuth codes, callback state, and tokens never enter
 * the Android client. The dashboard owns the complete PKCE/callback exchange.
 */
class McpOAuthFlowCoordinator(
    private val client: DashboardApiClient,
    private val pollDelayMillis: Long = 1_000,
    private val maxPolls: Int = 900,
    private val maxConsecutiveFailures: Int = 3,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun complete(
        serverName: String,
        profile: String? = null,
        openAuthorization: (String) -> Boolean,
    ): Result<DashboardMcpOAuthFlow> = runCatching {
        val started = client.startMcpOAuth(serverName, profile).getOrThrow()
        if (started.status == "error") {
            throw IOException(started.error ?: "MCP OAuth failed to start")
        }
        if (started.status == "approved") return@runCatching started
        val authorizationUrl = started.authorizationUrl
            ?: throw IOException("MCP OAuth server did not provide an authorization URL")
        val parsedUrl = authorizationUrl.toHttpUrlOrNull()
        if (parsedUrl?.scheme != "https") {
            throw IOException("MCP OAuth authorization URL must use HTTPS")
        }
        if (!openAuthorization(authorizationUrl)) {
            throw IOException("No browser is available to complete MCP OAuth")
        }

        var failures = 0
        repeat(maxPolls.coerceAtLeast(1)) {
            val current = client.getMcpOAuthFlow(started.flowId)
            if (current.isFailure) {
                failures += 1
                if (failures >= maxConsecutiveFailures.coerceAtLeast(1)) {
                    throw current.exceptionOrNull() ?: IOException("MCP OAuth status check failed")
                }
            } else {
                failures = 0
                val flow = current.getOrThrow()
                when (flow.status) {
                    "approved" -> return@runCatching flow
                    "error" -> throw IOException(flow.error ?: "MCP OAuth authorization failed")
                }
            }
            sleep(pollDelayMillis.coerceAtLeast(0))
        }
        throw IOException("MCP OAuth authorization timed out")
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }
}
