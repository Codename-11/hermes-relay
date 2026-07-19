package com.hermesandroid.relay.viewmodel

/** Runtime readiness for one independently optional chat transport. */
enum class ChatTransportReadiness {
    NotConfigured,
    Connecting,
    Ready,
    Unavailable,
}
enum class ChatTransportPath {
    Gateway,
    ApiSse,
}

/**
 * Transport-neutral UI state for chat connectivity. Relay is intentionally
 * absent: it is an optional bridge surface and cannot make chat unhealthy.
 */
sealed interface ChatRuntimeStatus {
    data class Connected(
        val transport: ChatTransportPath,
        val fallback: Boolean,
    ) : ChatRuntimeStatus

    data object Connecting : ChatRuntimeStatus

    data object Unavailable : ChatRuntimeStatus
}

/**
 * Resolve chat health in product priority order:
 * Gateway primary, API/SSE fallback, pending connection, then unavailable.
 */
fun resolveChatRuntimeStatus(
    gateway: ChatTransportReadiness,
    apiSse: ChatTransportReadiness,
): ChatRuntimeStatus = when {
    gateway == ChatTransportReadiness.Ready -> ChatRuntimeStatus.Connected(
        transport = ChatTransportPath.Gateway,
        fallback = false,
    )

    apiSse == ChatTransportReadiness.Ready -> ChatRuntimeStatus.Connected(
        transport = ChatTransportPath.ApiSse,
        fallback = true,
    )

    gateway == ChatTransportReadiness.Connecting ||
        apiSse == ChatTransportReadiness.Connecting -> ChatRuntimeStatus.Connecting

    else -> ChatRuntimeStatus.Unavailable
}
