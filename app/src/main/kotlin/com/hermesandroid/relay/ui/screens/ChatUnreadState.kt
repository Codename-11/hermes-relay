package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.ChatMessage

/**
 * Compact record of the conversation content that was visible at the bottom.
 * Only the tail can grow without increasing [messageCount], so keeping one
 * visible-content revision avoids hashing the entire transcript on each token.
 */
internal data class ChatUnreadSnapshot(
    val messageCount: Int,
    val lastMessageId: String?,
    val lastVisibleRevision: Int,
)

internal fun List<ChatMessage>.toUnreadSnapshot(): ChatUnreadSnapshot {
    val last = lastOrNull()
    return ChatUnreadSnapshot(
        messageCount = size,
        lastMessageId = last?.id,
        lastVisibleRevision = last?.visibleUnreadRevision() ?: 0,
    )
}

internal fun countUnreadMessages(
    current: ChatUnreadSnapshot,
    lastRead: ChatUnreadSnapshot,
): Int {
    if (current.messageCount < lastRead.messageCount) return 0
    val appended = (current.messageCount - lastRead.messageCount).coerceAtLeast(0)
    if (appended > 0) return appended
    if (current.messageCount == 0) return 0

    return if (
        current.lastMessageId != lastRead.lastMessageId ||
        current.lastVisibleRevision != lastRead.lastVisibleRevision
    ) {
        1
    } else {
        0
    }
}

internal enum class ChatVoiceAction {
    ShowDemoNotice,
    StartVoice,
    ShowSetupNotice,
}

/** Demo is offline, so it must win before any permission or route check. */
internal fun resolveChatVoiceAction(
    isDemoMode: Boolean,
    voiceReady: Boolean,
): ChatVoiceAction = when {
    isDemoMode -> ChatVoiceAction.ShowDemoNotice
    voiceReady -> ChatVoiceAction.StartVoice
    else -> ChatVoiceAction.ShowSetupNotice
}

internal inline fun dispatchChatVoiceAction(
    isDemoMode: Boolean,
    voiceReady: Boolean,
    onDemoNotice: () -> Unit,
    onStartVoice: () -> Unit,
    onSetupNotice: () -> Unit,
) {
    when (resolveChatVoiceAction(isDemoMode, voiceReady)) {
        ChatVoiceAction.ShowDemoNotice -> onDemoNotice()
        ChatVoiceAction.StartVoice -> onStartVoice()
        ChatVoiceAction.ShowSetupNotice -> onSetupNotice()
    }
}

private fun ChatMessage.visibleUnreadRevision(): Int {
    var revision = 17
    revision = 31 * revision + content.length
    revision = 31 * revision + thinkingContent.length
    revision = 31 * revision + toolCalls.fold(1) { acc, call ->
        var callRevision = 17
        callRevision = 31 * callRevision + (call.id?.hashCode() ?: 0)
        callRevision = 31 * callRevision + call.name.hashCode()
        callRevision = 31 * callRevision + (call.result?.hashCode() ?: 0)
        callRevision = 31 * callRevision + (call.error?.hashCode() ?: 0)
        callRevision = 31 * callRevision + (call.success?.hashCode() ?: 0)
        callRevision = 31 * callRevision + call.isComplete.hashCode()
        callRevision = 31 * callRevision + call.isGenerating.hashCode()
        31 * acc + callRevision
    }
    revision = 31 * revision + attachments.fold(1) { acc, attachment ->
        var attachmentRevision = 17
        attachmentRevision = 31 * attachmentRevision + attachment.contentType.hashCode()
        attachmentRevision = 31 * attachmentRevision + (attachment.fileName?.hashCode() ?: 0)
        attachmentRevision = 31 * attachmentRevision + attachment.state.hashCode()
        attachmentRevision = 31 * attachmentRevision + (attachment.errorMessage?.hashCode() ?: 0)
        31 * acc + attachmentRevision
    }
    revision = 31 * revision + cards.hashCode()
    revision = 31 * revision + cardDispatches.hashCode()
    revision = 31 * revision + (backgroundTask?.hashCode() ?: 0)
    return revision
}
