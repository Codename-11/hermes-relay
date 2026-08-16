package com.hermesandroid.relay.plugins.runtime

import com.hermesandroid.relay.network.upstream.DashboardApiClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * Namespace-confined access to one authenticated plugin backend.
 *
 * Callers provide path segments, never a URL. This rejects traversal and encoded path
 * tricks before composing with [DashboardApiClient], while query values are encoded.
 */
class ScopedPluginApiClient(
    private val pluginId: String,
    private val dashboard: DashboardApiClient,
) {
    init {
        require(PluginIdentifiers.isValid(pluginId)) { "Invalid plugin id: $pluginId" }
    }

    suspend fun get(
        relativePath: String,
        query: Map<String, String> = emptyMap(),
    ): Result<JsonElement> = validateAndBuild(relativePath, query).fold(
        onSuccess = { dashboard.getJsonElement(it) },
        onFailure = { Result.failure(it) },
    )

    suspend fun post(relativePath: String, payload: JsonObject): Result<JsonObject> =
        objectPath(relativePath).fold(
            onSuccess = { dashboard.postJsonObject(it, payload) },
            onFailure = { Result.failure(it) },
        )

    suspend fun put(relativePath: String, payload: JsonObject): Result<JsonObject> =
        objectPath(relativePath).fold(
            onSuccess = { dashboard.putJsonObject(it, payload) },
            onFailure = { Result.failure(it) },
        )

    suspend fun patch(relativePath: String, payload: JsonObject): Result<JsonObject> =
        objectPath(relativePath).fold(
            onSuccess = { dashboard.patchJsonObject(it, payload) },
            onFailure = { Result.failure(it) },
        )

    suspend fun delete(relativePath: String): Result<JsonObject> = objectPath(relativePath).fold(
        onSuccess = { dashboard.deleteJsonObject(it) },
        onFailure = { Result.failure(it) },
    )

    internal fun objectPath(relativePath: String): Result<String> =
        validateAndBuild(relativePath, emptyMap())

    private fun validateAndBuild(
        relativePath: String,
        query: Map<String, String>,
    ): Result<String> = runCatching {
        val trimmed = relativePath.trim()
        require(trimmed.isNotEmpty()) { "Plugin API path must not be empty" }
        require(!trimmed.startsWith('/') && !trimmed.endsWith('/')) {
            "Plugin API path must be relative and have no empty segments"
        }
        require('?' !in trimmed && '#' !in trimmed && '\\' !in trimmed && '%' !in trimmed) {
            "Plugin API path must contain only plain path segments"
        }
        val segments = trimmed.split('/')
        require(segments.all(::isSafeSegment)) { "Invalid plugin API path: $relativePath" }

        buildString {
            append("/api/plugins/")
            append(pluginId)
            append('/')
            append(segments.joinToString("/"))
            if (query.isNotEmpty()) {
                append('?')
                append(
                    query.entries.sortedBy { it.key }.joinToString("&") { (key, value) ->
                        "${encode(key)}=${encode(value)}"
                    },
                )
            }
        }
    }

    private fun isSafeSegment(segment: String): Boolean =
        segment.isNotEmpty() && segment != "." && segment != ".." && SEGMENT.matches(segment)

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        val SEGMENT = Regex("^[a-zA-Z0-9._~-]+$")
    }
}
