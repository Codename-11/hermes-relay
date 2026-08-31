package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProactiveInboxStoreTest {

    @Test
    fun `remove thread matches the rendered connection row and preserves other rows`() = runBlocking {
        val repository = ProactiveInboxRepository(InMemoryPreferencesDataStore())
        repository.add(entry("owned", "reminders", "connection-a", notificationId = 42))
        repository.add(entry("legacy", "reminders", null))
        repository.add(entry("other-connection", "reminders", "connection-b"))
        repository.add(entry("other-thread", "updates", "connection-a"))

        val removed = repository.removeThread("reminders", "connection-a")

        assertEquals(setOf("owned", "legacy"), removed.map { it.id }.toSet())
        assertEquals(listOf(42), removed.mapNotNull { it.notificationId })
        assertEquals(
            setOf("other-connection", "other-thread"),
            repository.entries.first().map { it.id }.toSet(),
        )
    }

    @Test
    fun `blank chat id removes only the local phone fallback row`() = runBlocking {
        val repository = ProactiveInboxRepository(InMemoryPreferencesDataStore())
        repository.add(entry("default", null, "connection-a"))
        repository.add(entry("named", "reminders", "connection-a"))

        repository.removeThread("phone", "connection-a")

        assertEquals(listOf("named"), repository.entries.first().map { it.id })
    }

    private fun entry(
        id: String,
        chatId: String?,
        connectionId: String?,
        notificationId: Int? = null,
    ) =
        ProactiveInboxEntry(
            id = id,
            title = "Hermes",
            text = id,
            receivedAt = 1L,
            chatId = chatId,
            connectionId = connectionId,
            notificationId = notificationId,
        )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }
}
