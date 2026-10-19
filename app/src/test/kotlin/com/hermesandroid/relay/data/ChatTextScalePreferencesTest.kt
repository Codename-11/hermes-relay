package com.hermesandroid.relay.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatTextScalePreferencesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private lateinit var repository: ChatTextScalePreferencesRepository

    @Before
    fun setUp() {
        file = temporaryFolder.newFile("chat_scale.preferences_pb").also { it.delete() }
        scope = CoroutineScope(Dispatchers.IO + Job())
        repository = ChatTextScalePreferencesRepository(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `scale persists and reloads using the reference range`() = runTest {
        repository.setScale(1.65f)

        assertEquals(1.65f, repository.scale.first(), 0f)
    }

    @Test
    fun `persistence normalizes invalid and out of range values`() = runTest {
        assertEquals(CHAT_TEXT_SCALE_DEFAULT, normalizeChatTextScale(Float.NaN), 0f)
        assertEquals(CHAT_TEXT_SCALE_MIN, normalizeChatTextScale(0.1f), 0f)
        assertEquals(CHAT_TEXT_SCALE_MAX, normalizeChatTextScale(4f), 0f)
    }

}