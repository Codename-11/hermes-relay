package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoicePreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: VoicePreferencesRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        val file: File = tempFolder.newFile("voice_preferences_test.preferences_pb")
        if (file.exists()) file.delete()
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        repository = VoicePreferencesRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
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
}
