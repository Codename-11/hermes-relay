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

/**
 * Unit tests for [BargeInPreferencesRepository].
 *
 * Uses an in-memory [DataStore] so Windows file rename/antivirus timing cannot
 * make preference semantics flaky.
 */
class BargeInPreferencesTest {
    private lateinit var repo: BargeInPreferencesRepository

    @Before
    fun setUp() {
        repo = BargeInPreferencesRepository(InMemoryBargeInPreferencesDataStore())
    }

    // --- Defaults ---

    @Test
    fun defaults_enabled_isTrue() = runTest {
        val prefs = repo.flow.first()
        assertTrue("enabled should match upstream default", prefs.enabled)
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
        assertEquals(true, fromDataClass.enabled)
        assertEquals(BargeInSensitivity.Default, fromDataClass.sensitivity)
        assertEquals(true, fromDataClass.resumeAfterInterruption)
        assertEquals(3f, fromDataClass.thresholdMultiplier)
        assertEquals(500L, fromDataClass.playbackGraceMs)
        assertEquals(false, fromDataClass.debugDiagnostics)
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

    @Test
    fun upstreamRmsTuning_roundTrips() = runTest {
        repo.setThresholdMultiplier(2.5f)
        repo.setPlaybackGraceMs(750L)
        repo.setDebugDiagnostics(true)

        val prefs = repo.flow.first()
        assertEquals(2.5f, prefs.thresholdMultiplier)
        assertEquals(750L, prefs.playbackGraceMs)
        assertTrue(prefs.debugDiagnostics)
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
        repo.setThresholdMultiplier(4f)
        repo.setPlaybackGraceMs(250L)
        repo.setDebugDiagnostics(true)

        val prefs = repo.flow.first()
        assertTrue(prefs.enabled)
        assertEquals(BargeInSensitivity.Low, prefs.sensitivity)
        assertFalse(prefs.resumeAfterInterruption)
        assertEquals(4f, prefs.thresholdMultiplier)
        assertEquals(250L, prefs.playbackGraceMs)
        assertTrue(prefs.debugDiagnostics)
    }
}

private class InMemoryBargeInPreferencesDataStore : DataStore<Preferences> {
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
