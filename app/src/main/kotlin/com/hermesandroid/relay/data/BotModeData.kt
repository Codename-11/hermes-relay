package com.hermesandroid.relay.data

data class BotGatewayRouteKey(
    val connectionId: String,
    val profileName: String,
) {
    init {
        require(connectionId.isNotBlank()) { "connectionId must not be blank" }
        require(profileName.isNotBlank()) { "profileName must not be blank" }
    }
}

class BotGatewayRoute(
    val key: BotGatewayRouteKey,
    val connectionLabel: String,
    val installId: String? = null,
) {
    val connectionId: String get() = key.connectionId
    val profileName: String get() = key.profileName

    override fun equals(other: Any?): Boolean = other is BotGatewayRoute && key == other.key
    override fun hashCode(): Int = key.hashCode()
    override fun toString(): String = "BotGatewayRoute(key=$key, label=$connectionLabel)"
}

/** Bounded session summary published by upstream `profiles.list`. */
data class BotSessionSummary(
    val id: String,
    val resolvedId: String = id,
    val title: String = "",
    val rootTitle: String = "",
    val preview: String = "",
    val startedAtMs: Long = 0L,
    val lastActiveAtMs: Long = 0L,
    val messageCount: Int = 0,
)

data class BotRosterEntry(
    val profile: Profile,
    val displayName: String,
    val route: BotGatewayRoute? = null,
    val handle: String = profile.name,
    val stale: Boolean = false,
    val botTitle: String = "",
    val hidden: Boolean = false,
    val lastSession: BotSessionSummary? = null,
    val workerSession: BotSessionSummary? = null,
    val canonicalSession: BotSessionSummary? = null,
) {
    val latestActivityAtMs: Long
        get() = maxOf(
            canonicalSession?.lastActiveAtMs ?: 0L,
            lastSession?.lastActiveAtMs ?: 0L,
        )

    val presenceActivityAtMs: Long
        get() = maxOf(latestActivityAtMs, workerSession?.lastActiveAtMs ?: 0L)

    val latestPreview: String
        get() = canonicalSession?.preview?.takeIf(String::isNotBlank)
            ?: lastSession?.preview.orEmpty()
}

data class BotGroupMember(
    val name: String,
    val handle: String? = null,
    val connectionId: String? = null,
    val connectionLabel: String? = null,
)

data class BotGroupMessage(
    val id: String? = null,
    val senderName: String,
    val senderKind: String,
    val senderSource: String? = null,
    val text: String,
    val atMs: Long,
)

data class BotGroupRoom(
    val key: String,
    val roomId: String? = null,
    val name: String,
    val revision: Long = 0L,
    val members: List<BotGroupMember> = emptyList(),
    val messages: List<BotGroupMessage> = emptyList(),
    val sourceConnectionIds: Set<String> = emptySet(),
    val stale: Boolean = false,
) {
    val latestMessage: BotGroupMessage? get() = messages.maxByOrNull(BotGroupMessage::atMs)
    val latestActivityAtMs: Long get() = latestMessage?.atMs ?: 0L
}

data class BotModeRoster(
    val bots: List<BotRosterEntry> = emptyList(),
    val groups: List<BotGroupRoom> = emptyList(),
    val botModeProtocolSupported: Boolean = false,
)

data class BotGatewayRosterStatus(
    val connectionId: String,
    val label: String,
    val installId: String? = null,
    val loading: Boolean = false,
    val stale: Boolean = false,
    val error: String? = null,
    val botCount: Int = 0,
)

data class BotChatTarget(
    /** Durable registry-row identity. */
    val storedSessionId: String,
    /** Compression-lineage tip that should be resumed. */
    val resolvedSessionId: String = storedSessionId,
)

data class BotModeState(
    val loading: Boolean = false,
    val roster: BotModeRoster = BotModeRoster(),
    val gateways: List<BotGatewayRosterStatus> = emptyList(),
    val error: String? = null,
)
