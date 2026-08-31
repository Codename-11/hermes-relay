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
 * Resolve chat health for the active conversation owner only. A reachable
 * sibling endpoint cannot make a signed-out or unreachable conversation look
 * connected.
 */
fun resolveChatRuntimeStatus(
    gateway: ChatTransportReadiness,
    apiSse: ChatTransportReadiness,
    owner: ChatTransportPath = ChatTransportPath.Gateway,
): ChatRuntimeStatus {
    val readiness = when (owner) {
        ChatTransportPath.Gateway -> gateway
        ChatTransportPath.ApiSse -> apiSse
    }
    return when (readiness) {
        ChatTransportReadiness.Ready -> ChatRuntimeStatus.Connected(
            transport = owner,
            fallback = false,
        )
        ChatTransportReadiness.Connecting -> ChatRuntimeStatus.Connecting
        ChatTransportReadiness.NotConfigured,
        ChatTransportReadiness.Unavailable -> ChatRuntimeStatus.Unavailable
    }
}
