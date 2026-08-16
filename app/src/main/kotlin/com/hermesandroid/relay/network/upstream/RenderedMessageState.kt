package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.data.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The publication boundary for Chat and Voice transcript rows.
 *
 * [ChatMessage.id] may change when the server adopts a client-created row,
 * while [ChatMessage.uiKey] is that row's stable render identity. Every value
 * emitted from this state therefore has exactly one row per render identity.
 */
internal class RenderedMessageState(initialValue: List<ChatMessage>) {
    private val mutable = MutableStateFlow(normalizeRenderedMessages(initialValue))
    val flow: StateFlow<List<ChatMessage>> = mutable.asStateFlow()

    var value: List<ChatMessage>
        get() = mutable.value
        set(value) {
            mutable.value = normalizeRenderedMessages(value)
        }

    fun update(transform: (List<ChatMessage>) -> List<ChatMessage>) {
        mutable.update { current -> normalizeRenderedMessages(transform(current)) }
    }
}

/**
 * Coalesce aliases before they can escape to keyed Compose consumers.
 *
 * The first slot owns transcript position and the latest snapshot owns row
 * state. This is the same ordering contract used for replayed server history.
 */
internal fun normalizeRenderedMessages(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.size < 2) return messages

    val firstSlotByUiKey = HashMap<String, Int>()
    val normalized = ArrayList<ChatMessage>(messages.size)
    for (message in messages) {
        val existingSlot = firstSlotByUiKey[message.uiKey]
        if (existingSlot == null) {
            firstSlotByUiKey[message.uiKey] = normalized.size
            normalized += message
        } else {
            normalized[existingSlot] = message
        }
    }
    return if (normalized.size == messages.size) messages else normalized
}
