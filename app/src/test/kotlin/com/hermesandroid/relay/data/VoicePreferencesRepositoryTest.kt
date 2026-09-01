package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoicePreferencesRepositoryTest {
    private lateinit var repository: VoicePreferencesRepository

    @Before
    fun setUp() {
        repository = VoicePreferencesRepository(InMemoryVoicePreferencesDataStore())
    }

    @Test
    fun realtimeSelectionPersistsPerConnectionAndProfile() = runTest {
        repository.setActiveScope("connection-a", "coder")
        repository.setRealtimeSelection(
            model = " grok-voice-think-fast-1.0 ",
            voice = " leo ",
        )

        var settings = repository.settings.first()
        assertEquals("grok-voice-think-fast-1.0", settings.realtimeModel)
        assertEquals("leo", settings.realtimeVoice)

        repository.setActiveScope("connection-b", "coder")
        settings = repository.settings.first()
        assertEquals("", settings.realtimeModel)
        assertEquals("", settings.realtimeVoice)

        repository.setActiveScope("connection-a", "coder")
        settings = repository.settings.first()
        assertEquals("grok-voice-think-fast-1.0", settings.realtimeModel)
        assertEquals("leo", settings.realtimeVoice)
    }

    @Test
    fun finalAnswerOnlyPersistsGloballyAcrossProfileScopes() = runTest {
        assertFalse(repository.settings.first().finalAnswerOnly)

        repository.setFinalAnswerOnly(true)
        assertTrue(repository.settings.first().finalAnswerOnly)

        repository.setActiveScope("connection-a", "coder")
        assertTrue(repository.settings.first().finalAnswerOnly)

        repository.setActiveScope("connection-b", "writer")
        assertTrue(repository.settings.first().finalAnswerOnly)
    }

    @Test
    fun presentationModePersistsGloballyAcrossProfileScopes() = runTest {
        assertEquals(
            VoicePresentationMode.Focus.storageValue,
            repository.settings.first().presentationMode,
        )

        repository.setPresentationMode(VoicePresentationMode.Conversation)
        assertEquals(
            VoicePresentationMode.Conversation.storageValue,
            repository.settings.first().presentationMode,
        )

        repository.setActiveScope("connection-a", "coder")
        assertEquals(
            VoicePresentationMode.Conversation.storageValue,
            repository.settings.first().presentationMode,
        )
    }

    @Test
    fun unknownPresentationModeFallsBackToFocus() {
        assertEquals(VoicePresentationMode.Focus, VoicePresentationMode.fromStorage("unknown"))
        assertEquals(VoicePresentationMode.Focus, VoicePresentationMode.fromStorage(null))
    }

    @Test
    fun stopPhrasesDefaultNormalizeRoundTripAndCanBeDisabled() = runTest {
        assertEquals(listOf("stop"), repository.settings.first().stopPhrases)

        repository.setStopPhrases(listOf(" Goodbye Hermes ", "stop", "", "STOP"))
        assertEquals(
            listOf("Goodbye Hermes", "stop", "STOP"),
            repository.settings.first().stopPhrases,
        )

        repository.setStopPhrases(emptyList())
        assertTrue(repository.settings.first().stopPhrases.isEmpty())
    }

    @Test
    fun relayRemoval_normalizesOnlyRelayOwnedSelectionsInExpectedScope() = runTest {
        repository.setActiveScope("connection-a", "coder")
        repository.setEngineMode(VoiceEngineMode.RealtimeAgent)
        repository.setAudioRoute(VoiceAudioRoute.Relay)
        val scope = repository.activeScope.value

        assertTrue(repository.reconcileRelayRemoval(scope))

        val settings = repository.settings.first()
        assertEquals(VoiceEngineMode.HermesVoiceOutput.storageValue, settings.engineMode)
        assertEquals(VoiceAudioRoute.Auto.storageValue, settings.audioRoute)
        assertFalse(repository.reconcileRelayRemoval(scope))
    }

    @Test
    fun relayRemoval_doesNotMutateAProfileThatNoLongerOwnsTheScope() = runTest {
        repository.setActiveScope("connection-a", "coder")
        repository.setEngineMode(VoiceEngineMode.RealtimeAgent)
        repository.setAudioRoute(VoiceAudioRoute.Relay)
        val staleScope = repository.activeScope.value

        repository.setActiveScope("connection-a", "writer")
        assertFalse(repository.reconcileRelayRemoval(staleScope))

        repository.setActiveScope("connection-a", "coder")
        val settings = repository.settings.first()
        assertEquals(VoiceEngineMode.RealtimeAgent.storageValue, settings.engineMode)
        assertEquals(VoiceAudioRoute.Relay.storageValue, settings.audioRoute)
    }

    @Test
    fun relayRemoval_doesNotRewriteGlobalDefaultSelectionSharedByAnotherConnection() = runTest {
        repository.setActiveScope("connection-a", null)
        repository.setEngineMode(VoiceEngineMode.RealtimeAgent)
        repository.setAudioRoute(VoiceAudioRoute.Relay)

        assertFalse(repository.reconcileRelayRemoval(repository.activeScope.value))

        repository.setActiveScope("connection-b", null)
        val settings = repository.settings.first()
        assertEquals(VoiceEngineMode.RealtimeAgent.storageValue, settings.engineMode)
        assertEquals(VoiceAudioRoute.Relay.storageValue, settings.audioRoute)
    }

    @Test
    fun relayRemoval_normalizesNamedProfileWithoutChangingSameProfileOnAnotherConnection() = runTest {
        repository.setActiveScope("connection-a", "coder")
        repository.setEngineMode(VoiceEngineMode.RealtimeAgent)
        repository.setAudioRoute(VoiceAudioRoute.Relay)

        repository.setActiveScope("connection-b", "coder")
        repository.setEngineMode(VoiceEngineMode.RealtimeAgent)
        repository.setAudioRoute(VoiceAudioRoute.Relay)

        repository.setActiveScope("connection-a", "coder")
        assertTrue(repository.reconcileRelayRemoval(repository.activeScope.value))

        repository.setActiveScope("connection-b", "coder")
        val settings = repository.settings.first()
        assertEquals(VoiceEngineMode.RealtimeAgent.storageValue, settings.engineMode)
        assertEquals(VoiceAudioRoute.Relay.storageValue, settings.audioRoute)
    }
}

private class InMemoryVoicePreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
