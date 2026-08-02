package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBehaviorPreferencesTest {
    @Test
    fun `new and existing installs default to balanced`() = runTest {
        val repository = PetBehaviorPreferencesRepository(InMemoryDataStore())

        assertEquals(PetTemperament.Balanced, repository.flow.first().temperament)
        assertEquals(1f, repository.flow.first().sizeScale)
    }

    @Test
    fun `pet size round trips and clamps unsafe stored values`() = runTest {
        val repository = PetBehaviorPreferencesRepository(InMemoryDataStore())

        repository.setSizeScale(1.125f)
        assertEquals(1.125f, repository.sizeScale.first())
        repository.setSizeScale(9f)
        assertEquals(MAX_PET_SIZE_SCALE, repository.sizeScale.first())

        val nanStore = InMemoryDataStore(
            mutablePreferencesOf(PetBehaviorPreferencesRepository.KEY_SIZE_SCALE to Float.NaN),
        )
        assertEquals(DEFAULT_PET_SIZE_SCALE, PetBehaviorPreferencesRepository(nanStore).sizeScale.first())
        val lowStore = InMemoryDataStore(
            mutablePreferencesOf(PetBehaviorPreferencesRepository.KEY_SIZE_SCALE to -3f),
        )
        assertEquals(MIN_PET_SIZE_SCALE, PetBehaviorPreferencesRepository(lowStore).sizeScale.first())
    }

    @Test
    fun `all temperament values round trip`() = runTest {
        val repository = PetBehaviorPreferencesRepository(InMemoryDataStore())

        PetTemperament.entries.forEach { temperament ->
            repository.setTemperament(temperament)
            assertEquals(temperament, repository.temperament.first())
        }
    }

    @Test
    fun `unknown future stored value falls back safely`() = runTest {
        val store = InMemoryDataStore(
            mutablePreferencesOf(
                PetBehaviorPreferencesRepository.KEY_TEMPERAMENT to "ExtraBouncy",
            ),
        )

        assertEquals(
            PetTemperament.Balanced,
            PetBehaviorPreferencesRepository(store).flow.first().temperament,
        )
    }

    @Test
    fun `setting temperament preserves unrelated preferences`() = runTest {
        val unrelatedKey = stringPreferencesKey("unrelated_test_key")
        val store = InMemoryDataStore(mutablePreferencesOf(unrelatedKey to "keep-me"))
        val repository = PetBehaviorPreferencesRepository(store)

        repository.setTemperament(PetTemperament.Playful)

        assertEquals("keep-me", store.data.first()[unrelatedKey])
    }

    @Test
    fun `pacing presets are exact and increasingly playful`() {
        assertEquals(
            PetBehaviorPacing(2_500L, 12_000L, 28_000L),
            PetTemperament.Calm.pacing,
        )
        assertEquals(
            PetBehaviorPacing(1_500L, 8_000L, 18_000L),
            PetTemperament.Balanced.pacing,
        )
        assertEquals(
            PetBehaviorPacing(750L, 5_000L, 10_000L),
            PetTemperament.Playful.pacing,
        )
        assertTrue(PetTemperament.Playful.pacing.roamIntervalMs < PetTemperament.Calm.pacing.roamIntervalMs)
    }

    @Test
    fun `reduced motion gate suppresses pacing`() {
        val preferences = PetBehaviorPreferences(PetTemperament.Playful)

        assertNull(preferences.pacingWhenMotionAllowed(motionAllowed = false))
        assertEquals(
            PetTemperament.Playful.pacing,
            preferences.pacingWhenMotionAllowed(motionAllowed = true),
        )
    }
}

private class InMemoryDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = transform(state.value).also { state.value = it }
}
