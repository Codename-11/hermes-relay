package com.hermesandroid.relay.reliability

/**
 * Content-free evidence captured immediately before Android replaces a chat
 * context. Deliberately excludes prompts, message text, IDs, profile names,
 * URLs, paths, attachments, and tool arguments/results.
 */
data class SessionResetEvidence(
    val reason: String,
    val transport: String,
    val messageCount: Int,
    val toolCount: Int,
    val queuedCount: Int,
    val pendingAttachmentCount: Int,
    val hadStoredSession: Boolean,
    val turnActive: Boolean,
    val askPending: Boolean,
) {
    fun technicalDetail(): String = buildString {
        appendLine("reason=${reason.safeToken()}")
        appendLine("transport=${transport.safeToken()}")
        appendLine("messages=${messageCount.coerceAtLeast(0)}")
        appendLine("tools=${toolCount.coerceAtLeast(0)}")
        appendLine("queued=${queuedCount.coerceAtLeast(0)}")
        appendLine("pending_attachments=${pendingAttachmentCount.coerceAtLeast(0)}")
        appendLine("had_stored_session=$hadStoredSession")
        appendLine("turn_active=$turnActive")
        append("ask_pending=$askPending")
    }

    private fun String.safeToken(): String =
        lowercase().replace(Regex("[^a-z0-9_.-]"), "_").take(40).ifBlank { "unknown" }
}
