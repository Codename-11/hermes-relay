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
