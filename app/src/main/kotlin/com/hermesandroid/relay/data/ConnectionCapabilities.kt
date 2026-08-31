package com.hermesandroid.relay.data

/**
 * Pure, persisted-state-derived availability for a Hermes connection.
 *
 * This deliberately describes configured surfaces, not live reachability or
 * authentication. Runtime layers can combine it with their probe/auth state
 * without treating a missing optional API server or Relay as a broken Hermes
 * connection.
 */
data class ConnectionCapabilities(
    val dashboardGatewayConfigured: Boolean,
    val apiServerConfigured: Boolean,
    val relayConfigured: Boolean,
) {
    val gatewayChatAvailable: Boolean get() = dashboardGatewayConfigured
    val manageAvailable: Boolean get() = dashboardGatewayConfigured
    val standardVoiceAvailable: Boolean get() = dashboardGatewayConfigured
    val apiChatFallbackAvailable: Boolean get() = apiServerConfigured
    val relayFeaturesAvailable: Boolean get() = relayConfigured
    val chatConfigured: Boolean get() = gatewayChatAvailable || apiChatFallbackAvailable
    val anySurfaceConfigured: Boolean
        get() = dashboardGatewayConfigured || apiServerConfigured || relayConfigured
}

val Connection.capabilities: ConnectionCapabilities
    get() = ConnectionCapabilities(
        dashboardGatewayConfigured = resolvedDashboardUrl.isNotBlank(),
        apiServerConfigured = apiServerUrl.isNotBlank(),
        relayConfigured = relayUrl.isNotBlank(),
    )

/**
 * Stable owner for an Auto chat before a conversation is opened.
 *
 * A legacy API-only record has no persisted Dashboard route; the conventional
 * same-host `:9119` derivation remains useful for an explicit upgrade, but it
 * must not silently turn that compatibility record into a Gateway-owned chat.
 * Once a Dashboard route (or authenticated Dashboard origin) is persisted,
 * standard Chat belongs to Gateway even while that route is signed out or
 * temporarily unreachable.
 */
val Connection.automaticChatTransport: SessionTransport
    get() {
        val dashboardPersisted = !dashboardUrl.isNullOrBlank() ||
            !authenticatedDashboardOrigin.isNullOrBlank()
        return if (dashboardPersisted) SessionTransport.GATEWAY else SessionTransport.SSE
    }

fun Connection.chatTransportForPreference(preference: String): SessionTransport =
    if (preference == "auto") automaticChatTransport
    else SessionTransport.forEndpoint(preference)
