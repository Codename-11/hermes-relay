package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class RenderedMessageStateTest {

    @Test
    fun publicationBoundary_keepsFirstPositionAndLatestSnapshotForEachUiIdentity() {
        val state = RenderedMessageState(emptyList())
        val first = message(id = "client-a", uiKey = "row-a", content = "partial")
        val middle = message(id = "server-b", uiKey = "row-b", content = "other")
        val replay = message(id = "server-a", uiKey = "row-a", content = "final")

        state.update { listOf(first, middle, replay) }

        assertEquals(listOf("row-a", "row-b"), state.value.map(ChatMessage::uiKey))
        assertEquals(listOf("server-a", "server-b"), state.value.map(ChatMessage::id))
        assertEquals("final", state.value.first().content)
    }

    @Test
    fun publicationBoundary_normalizesEveryMutationApi() {
        val first = message(id = "client", uiKey = "stable", content = "partial")
        val latest = message(id = "server", uiKey = "stable", content = "final")
        val state = RenderedMessageState(listOf(first, latest))
        assertEquals(1, state.value.size)

        state.value = listOf(first, latest)
        assertEquals(1, state.value.size)

        state.update { listOf(first, latest) }
        assertEquals(1, state.value.size)
    }

    private fun message(id: String, uiKey: String, content: String) = ChatMessage(
        id = id,
        uiKey = uiKey,
        role = MessageRole.ASSISTANT,
        content = content,
        timestamp = 1L,
    )
}
