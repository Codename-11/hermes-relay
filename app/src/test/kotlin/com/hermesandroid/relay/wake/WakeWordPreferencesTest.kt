package com.hermesandroid.relay.wake

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WakeWordPreferencesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: WakeWordPreferencesRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        val file: File = tempFolder.newFile("wake_preferences_test.preferences_pb")
        if (file.exists()) file.delete()
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        repository = WakeWordPreferencesRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaults_areOptInAndUseOnlyValidatedPhrase() = runTest {
        val preferences = repository.flow.first()
        assertFalse(preferences.enabled)
        assertEquals(DEFAULT_WAKE_PHRASE, preferences.phrase)
        assertEquals(0.6f, preferences.sensitivity)
        assertEquals(3, preferences.confirmationFrames)
        assertTrue(preferences.startNewSession)
        assertEquals(WakeWordProfileRouteMode.Active, preferences.profileRouting.mode)
        assertNull(preferences.profileRouting.profileName)
    }

    @Test
    fun enabled_roundTrips() = runTest {
        repository.setEnabled(true)
        val preferences = repository.flow.first()
        assertTrue(preferences.enabled)
    }

    @Test
    fun sensitivity_roundTripsAndClamps() = runTest {
        repository.setSensitivity(2f)
        val preferences = repository.flow.first()
        assertEquals(0.9f, preferences.sensitivity)
    }

    @Test
    fun confirmationFrames_roundTripsAndClamps() = runTest {
        repository.setConfirmationFrames(9)
        val preferences = repository.flow.first()
        assertEquals(5, preferences.confirmationFrames)
    }

    @Test
    fun startNewSession_roundTrips() = runTest {
        repository.setStartNewSession(false)
        val preferences = repository.flow.first()
        assertFalse(preferences.startNewSession)
    }

    @Test
    fun futureProfileRoutingShape_roundTripsWithoutEnablingIt() = runTest {
        repository.setProfileRouting(
            WakeWordProfileRouting(
                mode = WakeWordProfileRouteMode.Specific,
                profileName = "research",
            )
        )

        val preferences = repository.flow.first()
        assertEquals(WakeWordProfileRouteMode.Specific, preferences.profileRouting.mode)
        assertEquals("research", preferences.profileRouting.profileName)
        assertEquals(DEFAULT_WAKE_PHRASE, preferences.phrase)
    }
}
