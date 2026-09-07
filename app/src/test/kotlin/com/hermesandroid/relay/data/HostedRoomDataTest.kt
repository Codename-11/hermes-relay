package com.hermesandroid.relay.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HostedRoomDataTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun projectionPreservesIdentityRevisionParentAndAttributedReactions() {
        val room = BotGroupRoom(key = "room", name = "Room")
        val row = obj("""{"event_id":"child","seq":2,"revision":8,"parent_event_id":"root","thread_id":"thread","actor":{"kind":"member","id":"retired-author"},"original_text":"old","text":"new","deleted":false,"reactions":[{"reaction":"yes","actors":[{"kind":"user","id":"desktop"}]}]}""")
        val message = hostedProjectedMessage(row, room)!!
        assertEquals("child", message.id); assertEquals("retired-author", message.senderId)
        assertEquals("member", message.senderKind); assertEquals(2L, message.seq)
        assertEquals(8L, message.revision); assertEquals("root", message.parentEventId)
        assertEquals("new", message.text); assertEquals(row.roomObjects("reactions"), message.reactions)
    }

    @Test fun featureNamesAndMethodsMustBothNegotiate() {
        val methods = setOf("groups.state", "groups.history", "groups.history.search", "groups.read.get", "groups.read.mark", "groups.log")
        val absent = HostedRoomCapabilities(methods = methods, driver = true)
        assertFalse(absent.projection); assertFalse(absent.sharedRead); assertFalse(absent.searchable); assertFalse(absent.mutations)
        assertTrue(absent.rawExport); assertTrue(absent.readable)
        val negotiated = absent.copy(features = setOf("message_history_projection_v1", "room_read_cursors_v1", "message_history_search_v1", "message_mutations_v1"))
        assertTrue(negotiated.projection); assertTrue(negotiated.sharedRead); assertTrue(negotiated.searchable); assertTrue(negotiated.mutations)
        assertFalse(negotiated.copy(methods = emptySet()).readable)
        assertFalse(negotiated.copy(methods = methods - "groups.read.mark").sharedRead)
        assertFalse(negotiated.copy(driver = false).mutations)
    }
}
