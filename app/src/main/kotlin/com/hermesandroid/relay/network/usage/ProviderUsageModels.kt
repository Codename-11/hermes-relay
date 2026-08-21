package com.hermesandroid.relay.network.usage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProviderUsageResponse(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("fetched_at") val fetchedAt: String? = null,
    val providers: List<ProviderUsageProvider> = emptyList(),
)

@Serializable
data class ProviderUsageProvider(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val status: String,
    val source: String? = null,
    @SerialName("fetched_at") val fetchedAt: String? = null,
    val plan: String? = null,
    val windows: List<ProviderUsageWindow> = emptyList(),
    val details: List<String> = emptyList(),
    val message: String? = null,
) {
    val available: Boolean get() = status == STATUS_AVAILABLE

    companion object {
        const val STATUS_AVAILABLE = "available"
        const val STATUS_NOT_CONFIGURED = "not_configured"
        const val STATUS_UNAVAILABLE = "unavailable"
    }
}

@Serializable
data class ProviderUsageWindow(
    val id: String,
    val label: String,
    @SerialName("used_percent") val usedPercent: Double? = null,
    @SerialName("reset_at") val resetAt: String? = null,
    val detail: String? = null,
)
