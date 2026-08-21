package com.hermesandroid.relay.network.usage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProviderUsageResponse(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("fetched_at") val fetchedAt: String? = null,
    val capabilities: Set<String> = emptySet(),
    val providers: List<ProviderUsageProvider> = emptyList(),
) {
    val relayEnhanced: Boolean
        get() = capabilities.containsAll(RELAY_ENHANCED_CAPABILITIES)

    companion object {
        val RELAY_ENHANCED_CAPABILITIES = setOf(
            "credential_pools",
            "structured_balances",
            "opencode_go",
        )
    }
}

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
    val balances: List<ProviderUsageBalance> = emptyList(),
    @SerialName("renews_at") val renewsAt: String? = null,
    @SerialName("action_url") val actionUrl: String? = null,
    val credentials: List<ProviderUsageCredential> = emptyList(),
    @SerialName("active_credential_id") val activeCredentialId: String? = null,
    @SerialName("active_credential_state") val activeCredentialState: String = "unknown",
    @SerialName("active_observed_at") val activeObservedAt: String? = null,
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
data class ProviderUsageBalance(
    val id: String,
    val label: String,
    val amount: Double,
    val currency: String = "USD",
)

@Serializable
data class ProviderUsageCredential(
    val id: String,
    val label: String,
    val active: Boolean = false,
    val status: String,
    @SerialName("pool_status") val poolStatus: String? = null,
    @SerialName("last_status_at") val lastStatusAt: String? = null,
    @SerialName("reset_at") val resetAt: String? = null,
    val plan: String? = null,
    val windows: List<ProviderUsageWindow> = emptyList(),
    val details: List<String> = emptyList(),
    val message: String? = null,
) {
    companion object {
        const val STATUS_AVAILABLE = "available"
        const val STATUS_AT_LIMIT = "at_limit"
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
