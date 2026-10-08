package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatUnreadStoreTest {
    private class Memory : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private val a = AgentDisplay.profileContextKey("connection-a", "research")
    private val b = AgentDisplay.profileContextKey("connection-b", "research")

    @Test fun completionSurvivesStoreRecreationAndReadCannotBeUndoneByReplay() = runBlocking {
        val memory = Memory()
        val first = ChatUnreadStore(memory)
        first.completed(a, "same-session", "turn-1", 1, false)
        val restarted = ChatUnreadStore(memory)
        assertTrue(restarted.receipts.first().single().unread)
        restarted.markRead(a, "same-session")
        assertFalse(restarted.completed(a, "same-session", "turn-1", 2, false))
        assertFalse(restarted.receipts.first().single().unread)
        assertTrue(restarted.completed(a, "same-session", "turn-2", 3, false))
        assertTrue(restarted.receipts.first().single().unread)
        assertEquals(1, restarted.receipts.first().size)
    }

    @Test fun readingOneOwnerPreservesOtherConnectionsProfilesAndSessions() = runBlocking {
        val store = ChatUnreadStore(Memory())
        val otherProfile = AgentDisplay.profileContextKey("connection-a", "writer")
        for (owner in listOf(a, b, otherProfile)) store.completed(owner, "session", "turn", 1, false)
        store.completed(a, "sibling", "turn", 1, false)
        store.markRead(a, "session")
        assertEquals(3, store.receipts.first().count { it.unread })
        assertTrue(store.receipts.first().filter { it.contextKey != a }.all { it.unread })
    }

    @Test fun serverDefaultAndNamedDefaultNeverShareReadState() = runBlocking {
        val store = ChatUnreadStore(Memory())
        val inherited = AgentDisplay.profileContextKey("connection", null)
        val named = AgentDisplay.profileContextKey("connection", "default")
        store.completed(inherited, "same", "turn", 1, false)
        store.completed(named, "same", "turn", 1, false)
        store.markRead(named, "same")
        assertTrue(store.receipts.first().single { it.contextKey == inherited }.unread)
    }

    @Test fun visibleCompletionDoesNotAddUnreadAndLateOlderTurnCannotReplaceNewer() = runBlocking {
        val store = ChatUnreadStore(Memory())
        store.completed(a, "session", "new", 10, true)
        store.completed(a, "session", "old", 5, false)
        assertEquals("new", store.receipts.first().single().turnId)
        assertFalse(store.receipts.first().single().unread)
    }

    @Test fun retentionIsBoundedAndConnectionRemovalIsScoped() = runBlocking {
        val store = ChatUnreadStore(Memory())
        repeat(520) { store.completed(a, "session-$it", "turn", it.toLong(), false) }
        assertEquals(512, store.receipts.first().size)
        store.completed(b, "keep", "turn", 1000, false)
        store.removeConnection("connection-a")
        assertEquals(b, store.receipts.first().single().contextKey)
        store.clear()
        assertTrue(store.receipts.first().isEmpty())
    }
}
