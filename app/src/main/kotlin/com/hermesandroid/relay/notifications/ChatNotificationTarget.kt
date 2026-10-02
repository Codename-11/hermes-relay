package com.hermesandroid.relay.notifications

import android.net.Uri
import com.hermesandroid.relay.data.AgentDisplay

/** Durable navigation owner. Runtime session ids and the current picker are not destinations. */
data class ChatNotificationTarget(
    val connectionId: String,
    val profile: String?,
    val sessionId: String,
) {
    init { require(connectionId.isNotBlank() && sessionId.isNotBlank()) }

    val key: String get() = Uri.Builder().scheme("hermes-relay").authority("conversation")
        .appendPath(connectionId).appendPath(AgentDisplay.profileSessionKey(profile))
        .appendPath(sessionId).build().toString()

    fun route(): String = "chat?connectionId=${Uri.encode(connectionId)}" +
        "&sessionId=${Uri.encode(sessionId)}&profile=${Uri.encode(profile ?: DEFAULT_PROFILE)}"

    companion object {
        const val DEFAULT_PROFILE = "__server_default__"
        const val EXTRA_OWNER = "chat_notification_owner"

        fun from(contextKey: String?, sessionId: String?): ChatNotificationTarget? {
            val owner = AgentDisplay.parseProfileContextKey(contextKey) ?: return null
            return sessionId?.takeIf { it.isNotBlank() }?.let {
                ChatNotificationTarget(owner.connectionId, owner.requestProfileName, it)
            }
        }
    }
}
