package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
const val DEFAULT_PET_SIZE_SCALE: Float = 1f
const val MIN_PET_SIZE_SCALE: Float = 0.6f
const val MAX_PET_SIZE_SCALE: Float = 1.2f
private const val LEGACY_PET_SIZE_BASE_SCALE: Float = 1.25f
private const val CURRENT_PET_SIZE_SCALE_VERSION: Int = 2

internal fun sanitizedPetSizeScale(value: Float?): Float =
    value?.takeIf(Float::isFinite)?.coerceIn(MIN_PET_SIZE_SCALE, MAX_PET_SIZE_SCALE)
        ?: DEFAULT_PET_SIZE_SCALE

internal fun decodeStoredPetSizeScale(value: Float?, version: Int?): Float {
    if (value == null) return DEFAULT_PET_SIZE_SCALE
    val rebased = if (version == null) value / LEGACY_PET_SIZE_BASE_SCALE else value
    return sanitizedPetSizeScale(rebased)
}

data class PetBehaviorPreferences(
    val temperament: PetTemperament = DEFAULT_PET_TEMPERAMENT,
    val sizeScale: Float = DEFAULT_PET_SIZE_SCALE,
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
        internal val KEY_SIZE_SCALE = floatPreferencesKey("pet_size_scale")
        internal val KEY_SIZE_SCALE_VERSION = intPreferencesKey("pet_size_scale_version")
    }

    val flow: Flow<PetBehaviorPreferences> = dataStore.data
        .map { preferences ->
            PetBehaviorPreferences(
                temperament = decodeTemperament(preferences[KEY_TEMPERAMENT]),
                sizeScale = decodeStoredPetSizeScale(
                    value = preferences[KEY_SIZE_SCALE],
                    version = preferences[KEY_SIZE_SCALE_VERSION],
                ),
            )
        }
        .distinctUntilChanged()

    val temperament: Flow<PetTemperament> = flow
        .map { preferences -> preferences.temperament }
        .distinctUntilChanged()

    val sizeScale: Flow<Float> = flow
        .map { preferences -> preferences.sizeScale }
        .distinctUntilChanged()

    suspend fun setTemperament(temperament: PetTemperament) {
        dataStore.edit { preferences ->
            preferences[KEY_TEMPERAMENT] = temperament.name
        }
    }

    suspend fun setSizeScale(sizeScale: Float) {
        dataStore.edit { preferences ->
            preferences[KEY_SIZE_SCALE] = sanitizedPetSizeScale(sizeScale)
            preferences[KEY_SIZE_SCALE_VERSION] = CURRENT_PET_SIZE_SCALE_VERSION
        }
    }

    private fun decodeTemperament(raw: String?): PetTemperament =
        raw?.let { stored -> PetTemperament.entries.firstOrNull { it.name == stored } }
            ?: DEFAULT_PET_TEMPERAMENT
}
