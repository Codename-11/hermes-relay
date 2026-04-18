package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/**
 * Unit tests for [BargeInPreferencesRepository].
 *
 * No Robolectric available in this module, so we bypass [android.content.Context]
 * by constructing the repo against a filesystem-backed [DataStore] via
 * [PreferenceDataStoreFactory.create]. This is the canonical JVM-only DataStore
 * test pattern and is why [BargeInPreferencesRepository] exposes a
 * `DataStore<Preferences>`-accepting constructor alongside the Context one.
 */
class BargeInPreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: BargeInPreferencesRepository

    @Before
    fun setUp() {
        scope = TestScope(StandardTestDispatcher() + Job())
        val file: File = tempFolder.newFile("barge_in_test.preferences_pb")
        // We want a fresh empty store — delete the newFile() sentinel so
        // PreferenceDataStoreFactory starts from clean state each test.
        if (file.exists()) file.delete()
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        repo = BargeInPreferencesRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // --- Defaults ---

    @Test
    fun defaults_enabled_isFalse() = runTest {
        val prefs = repo.flow.first()
        assertFalse("enabled should default to false", prefs.enabled)
    }

    @Test
    fun defaults_sensitivity_isDefault() = runTest {
        val prefs = repo.flow.first()
        assertEquals(BargeInSensitivity.Default, prefs.sensitivity)
    }

    @Test
    fun defaults_resumeAfterInterruption_isTrue() = runTest {
        val prefs = repo.flow.first()
        assertTrue("resumeAfterInterruption should default to true", prefs.resumeAfterInterruption)
    }

    @Test
    fun defaults_dataClassDefaultsMatchRepoDefaults() {
        // The data class and the repo defaults must agree — belt-and-suspenders
        // because the repo re-materializes defaults on every read.
        val fromDataClass = BargeInPreferences()
        assertEquals(false, fromDataClass.enabled)
        assertEquals(BargeInSensitivity.Default, fromDataClass.sensitivity)
        assertEquals(true, fromDataClass.resumeAfterInterruption)
    }

    // --- Round-trip per field ---

    @Test
    fun setEnabled_roundTrips() = runTest {
        repo.setEnabled(true)
        assertTrue(repo.flow.first().enabled)

        repo.setEnabled(false)
        assertFalse(repo.flow.first().enabled)
    }

    @Test
    fun setResumeAfterInterruption_roundTrips() = runTest {
        repo.setResumeAfterInterruption(false)
        assertFalse(repo.flow.first().resumeAfterInterruption)

        repo.setResumeAfterInterruption(true)
        assertTrue(repo.flow.first().resumeAfterInterruption)
    }

    @Test
    fun setSensitivity_roundTrips_allValues() = runTest {
        for (value in BargeInSensitivity.values()) {
            repo.setSensitivity(value)
            assertEquals(value, repo.flow.first().sensitivity)
        }
    }

    // --- Enum serialization ---

    @Test
    fun setSensitivity_high_persistsAsHigh() = runTest {
        repo.setSensitivity(BargeInSensitivity.High)
        val prefs = repo.flow.first()
        assertEquals(BargeInSensitivity.High, prefs.sensitivity)
    }

    @Test
    fun setSensitivity_off_persistsAsOff() = runTest {
        repo.setSensitivity(BargeInSensitivity.Off)
        assertEquals(BargeInSensitivity.Off, repo.flow.first().sensitivity)
    }

    // --- Independence: setting one field doesn't reset the others ---

    @Test
    fun setters_areIndependent() = runTest {
        repo.setEnabled(true)
        repo.setSensitivity(BargeInSensitivity.Low)
        repo.setResumeAfterInterruption(false)

        val prefs = repo.flow.first()
        assertTrue(prefs.enabled)
        assertEquals(BargeInSensitivity.Low, prefs.sensitivity)
        assertFalse(prefs.resumeAfterInterruption)
    }
}
