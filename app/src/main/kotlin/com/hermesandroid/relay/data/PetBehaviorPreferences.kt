package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Exact cadence values consumed by the floating-pet behavior director. */
data class PetBehaviorPacing(
    val responseVisitDelayMs: Long,
    val roamIntervalMs: Long,
    val idleReactionCadenceMs: Long,
) {
    init {
        require(responseVisitDelayMs > 0L)
        require(roamIntervalMs > responseVisitDelayMs)
        require(idleReactionCadenceMs > roamIntervalMs)
    }
}

/**
 * User-facing pet activity presets.
 *
 * These values control how often an otherwise-idle pet may act. Existing
 * animation, reduced-motion, touch-exploration, scrolling, and agent-activity
 * gates remain authoritative and may always defer an action.
 */
enum class PetTemperament(val pacing: PetBehaviorPacing) {
    Calm(
        PetBehaviorPacing(
            responseVisitDelayMs = 2_500L,
            roamIntervalMs = 12_000L,
            idleReactionCadenceMs = 28_000L,
        ),
    ),
    Balanced(
        PetBehaviorPacing(
            responseVisitDelayMs = 1_500L,
            roamIntervalMs = 8_000L,
            idleReactionCadenceMs = 18_000L,
        ),
    ),
    Playful(
        PetBehaviorPacing(
            responseVisitDelayMs = 750L,
            roamIntervalMs = 5_000L,
            idleReactionCadenceMs = 10_000L,
        ),
    ),
}

val DEFAULT_PET_TEMPERAMENT: PetTemperament = PetTemperament.Balanced

data class PetBehaviorPreferences(
    val temperament: PetTemperament = DEFAULT_PET_TEMPERAMENT,
) {
    /**
     * Runtime seam for the behavior director. A disabled motion gate returns
     * no pacing rather than weakening the app's accessibility policy.
     */
    fun pacingWhenMotionAllowed(motionAllowed: Boolean): PetBehaviorPacing? =
        temperament.pacing.takeIf { motionAllowed }
}

/** Additive, phone-local DataStore persistence for pet behavior preferences. */
class PetBehaviorPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.relayDataStore)

    companion object {
        internal val KEY_TEMPERAMENT = stringPreferencesKey("pet_temperament")
    }

    val flow: Flow<PetBehaviorPreferences> = dataStore.data
        .map { preferences ->
            PetBehaviorPreferences(
                temperament = decodeTemperament(preferences[KEY_TEMPERAMENT]),
            )
        }
        .distinctUntilChanged()

    val temperament: Flow<PetTemperament> = flow
        .map { preferences -> preferences.temperament }
        .distinctUntilChanged()

    suspend fun setTemperament(temperament: PetTemperament) {
        dataStore.edit { preferences ->
            preferences[KEY_TEMPERAMENT] = temperament.name
        }
    }

    private fun decodeTemperament(raw: String?): PetTemperament =
        raw?.let { stored -> PetTemperament.entries.firstOrNull { it.name == stored } }
            ?: DEFAULT_PET_TEMPERAMENT
}
