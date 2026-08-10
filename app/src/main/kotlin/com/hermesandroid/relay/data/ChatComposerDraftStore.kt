package com.hermesandroid.relay.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Immutable owner of one composer draft.
 *
 * Callers must supply stable ids rather than display labels. [sessionId] may be
 * a server id or a stable client-generated id for a not-yet-created session.
 * [draftId] separates the primary composer from any future named draft slot.
 */
data class ChatComposerDraftKey(
    val connectionId: String,
    val profileId: String,
    val sessionId: String,
    val draftId: String = PRIMARY_DRAFT_ID,
) {
    init {
        require(connectionId.isNotBlank()) { "connectionId must not be blank" }
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(draftId.isNotBlank()) { "draftId must not be blank" }
    }

    companion object {
        const val PRIMARY_DRAFT_ID = "primary"
        const val DEFAULT_PROFILE_ID = "default"
    }
}

/** Message references associated with composer content. */
data class ChatComposerDraftContext(
    val quotedMessageId: String? = null,
    val editingMessageId: String? = null,
) {
    internal fun normalized(): ChatComposerDraftContext = copy(
        quotedMessageId = quotedMessageId?.takeIf(String::isNotBlank),
        editingMessageId = editingMessageId?.takeIf(String::isNotBlank),
    )
}

/**
 * Complete restorable state for one composer.
 *
 * Selection offsets use the same start-inclusive/end-exclusive convention as
 * Compose text fields. The store clamps them whenever the text changes so a
 * restored selection can never address outside the restored string.
 */
data class ChatComposerDraft(
    val text: String = "",
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
    val context: ChatComposerDraftContext = ChatComposerDraftContext(),
    val attachments: List<Attachment> = emptyList(),
) {
    val isEmpty: Boolean
        get() = text.isEmpty() &&
            context.quotedMessageId == null &&
            context.editingMessageId == null &&
            attachments.isEmpty()

    internal fun normalized(): ChatComposerDraft {
        val normalizedStart = selectionStart.coerceIn(0, text.length)
        val normalizedEnd = selectionEnd.coerceIn(0, text.length)
        return copy(
            selectionStart = minOf(normalizedStart, normalizedEnd),
            selectionEnd = maxOf(normalizedStart, normalizedEnd),
            context = context.normalized(),
            attachments = attachments.toList(),
        )
    }
}

/**
 * Session-owned composer state.
 *
 * This store is deliberately memory-only: outbound [Attachment.content] can
 * contain large Base64 payloads and must not enter Preferences DataStore. Keep
 * one instance in the chat owner (normally its ViewModel) so drafts survive
 * navigation and Activity recreation. Process death starts with empty drafts;
 * a future durable implementation should persist URI grants, not attachment
 * bytes.
 */
interface ChatComposerDraftStore {
    fun observe(key: ChatComposerDraftKey): Flow<ChatComposerDraft>
    fun snapshot(key: ChatComposerDraftKey): ChatComposerDraft
    fun save(key: ChatComposerDraftKey, draft: ChatComposerDraft)
    fun update(
        key: ChatComposerDraftKey,
        transform: (ChatComposerDraft) -> ChatComposerDraft,
    )
    fun remove(key: ChatComposerDraftKey)
    fun removeSession(connectionId: String, profileId: String, sessionId: String)
    fun clear()
}

class InMemoryChatComposerDraftStore : ChatComposerDraftStore {
    private val drafts = MutableStateFlow<Map<ChatComposerDraftKey, ChatComposerDraft>>(emptyMap())

    override fun observe(key: ChatComposerDraftKey): Flow<ChatComposerDraft> =
        drafts
            .map { it[key] ?: ChatComposerDraft() }
            .distinctUntilChanged()

    override fun snapshot(key: ChatComposerDraftKey): ChatComposerDraft =
        drafts.value[key] ?: ChatComposerDraft()

    @Synchronized
    override fun save(key: ChatComposerDraftKey, draft: ChatComposerDraft) {
        val normalized = draft.normalized()
        drafts.value = if (normalized.isEmpty) {
            drafts.value - key
        } else {
            drafts.value + (key to normalized)
        }
    }

    @Synchronized
    override fun update(
        key: ChatComposerDraftKey,
        transform: (ChatComposerDraft) -> ChatComposerDraft,
    ) {
        save(key, transform(snapshot(key)))
    }

    @Synchronized
    override fun remove(key: ChatComposerDraftKey) {
        drafts.value = drafts.value - key
    }

    @Synchronized
    override fun removeSession(connectionId: String, profileId: String, sessionId: String) {
        drafts.value = drafts.value.filterKeys { key ->
            key.connectionId != connectionId ||
                key.profileId != profileId ||
                key.sessionId != sessionId
        }
    }

    @Synchronized
    override fun clear() {
        drafts.value = emptyMap()
    }
}
