package com.hermesandroid.relay.plugins.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AndroidPluginManifest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    val version: String? = null,
    @SerialName("min_host_api") val minHostApi: Int = 1,
    @SerialName("default_enabled") val defaultEnabled: Boolean = false,
    val contributions: List<AndroidPluginContribution> = emptyList(),
    @SerialName("requested_capabilities")
    val requestedCapabilities: List<AndroidPluginCapabilityRequest> = emptyList(),
    val updates: AndroidPluginUpdateSource? = null,
)

@Serializable
data class AndroidPluginContribution(
    val id: String,
    val surface: String = "page",
    val title: String,
    val document: AndroidPluginDocumentEndpoint,
    /** Optional host-rendered metadata used by Relay-generated declarative previews. */
    val status: String? = null,
    val lifecycle: String? = null,
    val description: String? = null,
    val revision: Int? = null,
    val digest: String? = null,
)

@Serializable
data class AndroidPluginDocumentEndpoint(
    val method: String = "GET",
    val path: String,
)

@Serializable
data class AndroidPluginCapabilityRequest(
    val id: String,
    val reason: String,
    val required: Boolean = false,
)

@Serializable
data class AndroidPluginUpdateSource(
    @SerialName("poll_seconds") val pollSeconds: Int? = null,
)

const val ANDROID_PLUGIN_HOST_API_VERSION: Int = 1
const val PLUGIN_API_WRITE_CAPABILITY: String = "plugin.api.write"
