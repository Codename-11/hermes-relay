package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInputPreferencesTest {

    @Test
    fun `large paste conversion defaults on and round trips`() = runTest {
        val store = InMemoryChatInputDataStore()
        val repository = ChatInputPreferencesRepository(store)

        assertEquals(true, repository.convertLargePastesToAttachments.first())

        repository.setConvertLargePastesToAttachments(false)
        assertEquals(false, repository.convertLargePastesToAttachments.first())

        repository.setConvertLargePastesToAttachments(true)
        assertEquals(true, repository.convertLargePastesToAttachments.first())
    }

    @Test
    fun `Git workspace in Chat defaults on and round trips without replacing other settings`() = runTest {
        val unrelatedKey = stringPreferencesKey("unrelated_git_chat_test")
        val store = InMemoryChatInputDataStore(
            mutablePreferencesOf(unrelatedKey to "keep-me"),
        )
        val repository = ChatInputPreferencesRepository(store)

        assertEquals(true, repository.showGitWorkspaceInChat.first())

        repository.setShowGitWorkspaceInChat(false)
        assertEquals(false, repository.showGitWorkspaceInChat.first())
        assertEquals("keep-me", store.data.first()[unrelatedKey])

        repository.setShowGitWorkspaceInChat(true)
        assertEquals(true, repository.showGitWorkspaceInChat.first())
        assertEquals("keep-me", store.data.first()[unrelatedKey])
    }

    @Test
    fun `enter defaults to send and unknown values fall back safely`() = runTest {
        assertEquals(
            PhysicalKeyboardEnterBehavior.SendMessage,
            ChatInputPreferencesRepository(InMemoryChatInputDataStore())
                .physicalKeyboardEnterBehavior.first(),
        )
        val futureStore = InMemoryChatInputDataStore(
            mutablePreferencesOf(
                ChatInputPreferencesRepository.KEY_PHYSICAL_KEYBOARD_ENTER to "future_mode",
            ),
        )
        assertEquals(
            PhysicalKeyboardEnterBehavior.SendMessage,
            ChatInputPreferencesRepository(futureStore).physicalKeyboardEnterBehavior.first(),
        )
    }

    @Test
    fun `all enter behaviors round trip without replacing unrelated settings`() = runTest {
        val unrelatedKey = stringPreferencesKey("unrelated_chat_input_test")
        val unrelatedBoolean = booleanPreferencesKey("unrelated_chat_input_boolean_test")
        val store = InMemoryChatInputDataStore(
            mutablePreferencesOf(
                unrelatedKey to "keep-me",
                unrelatedBoolean to true,
            ),
        )
        val repository = ChatInputPreferencesRepository(store)

        PhysicalKeyboardEnterBehavior.entries.forEach { behavior ->
            repository.setPhysicalKeyboardEnterBehavior(behavior)
            assertEquals(behavior, repository.physicalKeyboardEnterBehavior.first())
            assertEquals("keep-me", store.data.first()[unrelatedKey])
            assertEquals(true, store.data.first()[unrelatedBoolean])
        }
    }
}

private class InMemoryChatInputDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = transform(state.value).also { state.value = it }
}
