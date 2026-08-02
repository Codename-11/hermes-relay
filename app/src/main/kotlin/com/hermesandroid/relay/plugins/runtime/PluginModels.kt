package com.hermesandroid.relay.plugins.runtime

import kotlinx.serialization.json.JsonObject

/** Upstream dashboard metadata for a plugin whose backend can expose a mobile surface. */
data class AndroidPluginCatalogEntry(
    val id: String,
    val label: String,
    val description: String,
    val version: String,
    val icon: String,
    val source: String,
)

/**
 * A mobile manifest with identity anchored to the authenticated dashboard catalog.
 *
 * [manifest] stays as JSON at this transport boundary. The renderer owns the versioned
 * declarative document contract; the discovery layer must not let a manifest assert a
 * different plugin identity or widen its API namespace.
 */
data class DiscoveredAndroidPlugin(
    val catalog: AndroidPluginCatalogEntry,
    val manifest: JsonObject,
)

/** Local preference scope. A null profile is the server-default profile context. */
data class PluginScope(
    val connectionId: String,
    val profileName: String?,
    val pluginId: String,
) {
    init {
        require(connectionId.isNotBlank()) { "connectionId must not be blank" }
        require(PluginIdentifiers.isValid(pluginId)) { "Invalid plugin id: $pluginId" }
    }
}

data class PluginPreferenceState(
    val enabled: Boolean = false,
    val grants: Set<String> = emptySet(),
    /** Distinguishes an explicit opt-out from a manifest's default enablement. */
    val configured: Boolean = false,
)

/** Conservative identifier grammar shared by discovery, API routing, and persistence. */
object PluginIdentifiers {
    private val pattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$")

    fun isValid(value: String): Boolean = pattern.matches(value)
}
