package com.hermesandroid.relay.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Non-secret handle required to resume a dashboard-owned MCP OAuth flow. */
data class PendingMcpOAuth(
    val flowId: String,
    val serverName: String,
    val profile: String?,
)

/**
 * Process-recreatable Manage OAuth state. Authorization URLs, OAuth codes,
 * callback state, and tokens are deliberately never written here.
 */
class DashboardManageOAuthViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val flowId = savedStateHandle.getStateFlow<String?>(KEY_FLOW_ID, null)
    private val serverName = savedStateHandle.getStateFlow<String?>(KEY_SERVER_NAME, null)
    private val profile = savedStateHandle.getStateFlow<String?>(KEY_PROFILE, null)

    val pending: StateFlow<PendingMcpOAuth?> = combine(flowId, serverName, profile) { id, server, scope ->
        if (id.isNullOrBlank() || server.isNullOrBlank()) null
        else PendingMcpOAuth(id, server, scope)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, currentPending())

    val unsupportedRoutes: StateFlow<ArrayList<String>> =
        savedStateHandle.getStateFlow(KEY_UNSUPPORTED_ROUTES, arrayListOf())

    fun remember(flowId: String, serverName: String, profile: String?) {
        savedStateHandle[KEY_FLOW_ID] = flowId
        savedStateHandle[KEY_SERVER_NAME] = serverName
        savedStateHandle[KEY_PROFILE] = profile
    }

    fun clear() {
        savedStateHandle[KEY_FLOW_ID] = null
        savedStateHandle[KEY_SERVER_NAME] = null
        savedStateHandle[KEY_PROFILE] = null
    }

    fun markUnsupported(routeKey: String) {
        if (routeKey.isBlank()) return
        val next = ArrayList(unsupportedRoutes.value)
        if (routeKey !in next) {
            next += routeKey
            savedStateHandle[KEY_UNSUPPORTED_ROUTES] = next
        }
    }

    private fun currentPending(): PendingMcpOAuth? {
        val id = savedStateHandle.get<String>(KEY_FLOW_ID)
        val server = savedStateHandle.get<String>(KEY_SERVER_NAME)
        if (id.isNullOrBlank() || server.isNullOrBlank()) return null
        return PendingMcpOAuth(id, server, savedStateHandle.get(KEY_PROFILE))
    }

    private companion object {
        const val KEY_FLOW_ID = "manage_mcp_oauth_flow_id"
        const val KEY_SERVER_NAME = "manage_mcp_oauth_server"
        const val KEY_PROFILE = "manage_mcp_oauth_profile"
        const val KEY_UNSUPPORTED_ROUTES = "manage_mcp_oauth_unsupported_routes"
    }
}
