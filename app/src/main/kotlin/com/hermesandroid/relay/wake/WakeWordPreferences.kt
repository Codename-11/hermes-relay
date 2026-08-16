package com.hermesandroid.relay.wake

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hermesandroid.relay.data.relayDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

const val DEFAULT_WAKE_PHRASE = "Hey Hermes"

enum class WakeWordProfileRouteMode(val storageValue: String) {
    Active("active"),
    Specific("specific");

    companion object {
        fun fromStorage(value: String?): WakeWordProfileRouteMode =
            entries.firstOrNull { it.storageValue == value } ?: Active
    }
}

/**
 * Future-safe routing shape. The first release deliberately supports only
 * [WakeWordProfileRouteMode.Active], so wake activation preserves the profile,
 * provider, and model already selected in the app.
 */
data class WakeWordProfileRouting(
    val mode: WakeWordProfileRouteMode = WakeWordProfileRouteMode.Active,
    val profileName: String? = null,
)

data class WakeWordPreferences(
    val enabled: Boolean = false,
    val assistantEnabled: Boolean = false,
    val phrase: String = DEFAULT_WAKE_PHRASE,
    /** Higher is stricter (fewer false activations), matching upstream. */
    val sensitivity: Float = 0.3f,
    val confirmationFrames: Int = 3,
    val startNewSession: Boolean = true,
    val profileRouting: WakeWordProfileRouting = WakeWordProfileRouting(),
)

internal enum class WakeWordListenerMode {
    ForegroundService,
    SystemAssistant,
}

internal data class WakeWordListenerFlags(
    val foregroundService: Boolean,
    val systemAssistant: Boolean,
)

internal fun flagsForWakeWordMode(mode: WakeWordListenerMode): WakeWordListenerFlags =
    when (mode) {
        WakeWordListenerMode.ForegroundService -> WakeWordListenerFlags(
            foregroundService = true,
            systemAssistant = false,
        )
        WakeWordListenerMode.SystemAssistant -> WakeWordListenerFlags(
            foregroundService = false,
            systemAssistant = true,
        )
    }

class WakeWordPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.relayDataStore)

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val KEY_ASSISTANT_ENABLED = booleanPreferencesKey("assistant_wake_word_enabled")
        val KEY_PHRASE = stringPreferencesKey("wake_word_phrase")
        val KEY_SENSITIVITY = floatPreferencesKey("wake_word_sensitivity")
        val KEY_CONFIRMATION_FRAMES = intPreferencesKey("wake_word_confirmation_frames")
        val KEY_START_NEW_SESSION = booleanPreferencesKey("wake_word_start_new_session")
        val KEY_PROFILE_ROUTE_MODE = stringPreferencesKey("wake_word_profile_route_mode")
        val KEY_PROFILE_NAME = stringPreferencesKey("wake_word_profile_name")
    }

    val flow: Flow<WakeWordPreferences> = dataStore.data
        .map { prefs ->
            val routeMode = WakeWordProfileRouteMode.fromStorage(prefs[KEY_PROFILE_ROUTE_MODE])
            WakeWordPreferences(
                enabled = prefs[KEY_ENABLED] ?: false,
                assistantEnabled = prefs[KEY_ASSISTANT_ENABLED] ?: false,
                // Only one phrase has been validated. Ignore stale/future values
                // until the product actually exposes multi-phrase support.
                phrase = DEFAULT_WAKE_PHRASE,
                sensitivity = (prefs[KEY_SENSITIVITY] ?: 0.3f).coerceIn(0.2f, 0.9f),
                confirmationFrames = (prefs[KEY_CONFIRMATION_FRAMES] ?: 3).coerceIn(1, 5),
                startNewSession = prefs[KEY_START_NEW_SESSION] ?: true,
                profileRouting = WakeWordProfileRouting(
                    mode = routeMode,
                    profileName = prefs[KEY_PROFILE_NAME]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                ),
            )
        }
        .distinctUntilChanged()

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit {
            if (enabled) {
                val flags = flagsForWakeWordMode(WakeWordListenerMode.ForegroundService)
                it[KEY_ENABLED] = flags.foregroundService
                it[KEY_ASSISTANT_ENABLED] = flags.systemAssistant
                it[KEY_PHRASE] = DEFAULT_WAKE_PHRASE
            } else {
                it[KEY_ENABLED] = false
            }
        }
    }

    suspend fun setAssistantEnabled(enabled: Boolean) {
        dataStore.edit {
            if (enabled) {
                val flags = flagsForWakeWordMode(WakeWordListenerMode.SystemAssistant)
                it[KEY_ENABLED] = flags.foregroundService
                it[KEY_ASSISTANT_ENABLED] = flags.systemAssistant
                it[KEY_PHRASE] = DEFAULT_WAKE_PHRASE
            } else {
                it[KEY_ASSISTANT_ENABLED] = false
            }
        }
    }

    suspend fun setSensitivity(sensitivity: Float) {
        dataStore.edit { it[KEY_SENSITIVITY] = sensitivity.coerceIn(0.2f, 0.9f) }
    }

    suspend fun setConfirmationFrames(frames: Int) {
        dataStore.edit { it[KEY_CONFIRMATION_FRAMES] = frames.coerceIn(1, 5) }
    }

    suspend fun setStartNewSession(enabled: Boolean) {
        dataStore.edit { it[KEY_START_NEW_SESSION] = enabled }
    }

    /**
     * Stored now so a future profile-specific phrase UI can migrate without a
     * schema rewrite. Product code intentionally writes Active in this release.
     */
    suspend fun setProfileRouting(routing: WakeWordProfileRouting) {
        dataStore.edit { prefs ->
            prefs[KEY_PROFILE_ROUTE_MODE] = routing.mode.storageValue
            val profileName = routing.profileName?.trim()?.takeIf { it.isNotEmpty() }
            if (profileName == null) {
                prefs.remove(KEY_PROFILE_NAME)
            } else {
                prefs[KEY_PROFILE_NAME] = profileName
            }
            prefs[KEY_PHRASE] = DEFAULT_WAKE_PHRASE
        }
    }
}
