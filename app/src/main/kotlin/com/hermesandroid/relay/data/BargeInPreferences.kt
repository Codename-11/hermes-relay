package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User-tunable voice barge-in preferences.
 *
 * Phase V follow-on — owned by the voice-barge-in plan (Wave 1 / unit B1).
 *
 * Barge-in lets the user interrupt TTS playback by speaking. The three knobs
 * here back the Voice Settings "Interruption" section added by B5 and are
 * consumed by [com.hermesandroid.relay.viewmodel.VoiceViewModel] (wired in
 * B4):
 *
 *  - [enabled] — master toggle for the whole barge-in path. When false, the
 *    listener never starts and TTS plays uninterrupted. Default off at launch
 *    on both flavors so existing users aren't surprised by mic activation
 *    during a speaking turn.
 *
 *  - [sensitivity] — maps to Silero VAD threshold + hysteresis tuning inside
 *    [com.hermesandroid.relay.audio.VadEngine]. [BargeInSensitivity.Off] is
 *    a UI convenience for "disable without flipping the top-level toggle";
 *    it short-circuits the VAD to `isSpeech=false` regardless of input.
 *
 *  - [resumeAfterInterruption] — if the user interrupts, then falls silent
 *    within the 600 ms watchdog, should we re-enqueue the un-played sentence
 *    chunks so the agent finishes speaking? On by default — without it,
 *    barge-in behaves like a hard cancel, which is more abrupt than most
 *    conversational UX expects.
 *
 * Matches the [BridgePreferences] / [VoicePreferences] / [MediaSettings] style:
 * single shared DataStore (`relayDataStore`), one key per scalar field, enum
 * stored as its `name` (cheap + schema-evolvable via fall-back to default on
 * unknown values). No migration needed — preferences are additive.
 */
data class BargeInPreferences(
    val enabled: Boolean = DEFAULT_ENABLED,
    val sensitivity: BargeInSensitivity = DEFAULT_SENSITIVITY,
    val resumeAfterInterruption: Boolean = DEFAULT_RESUME_AFTER_INTERRUPTION,
)

/**
 * VAD sensitivity preset for barge-in detection.
 *
 * Tuning lives in [com.hermesandroid.relay.audio.VadEngine.setSensitivity] —
 * see the B2 unit spec for the exact `(threshold, attack, release,
 * consecutive)` tuple each value maps to. [Off] is UI-only shorthand for
 * "disable detection without clearing the toggle".
 */
enum class BargeInSensitivity {
    Off,
    Low,
    Default,
    High,
}

const val DEFAULT_ENABLED: Boolean = false
val DEFAULT_SENSITIVITY: BargeInSensitivity = BargeInSensitivity.Default
const val DEFAULT_RESUME_AFTER_INTERRUPTION: Boolean = true

/**
 * DataStore-backed repository for [BargeInPreferences].
 *
 * Primary constructor takes an [android.content.Context] and resolves the
 * shared [Context.relayDataStore]; the secondary constructor takes a raw
 * [DataStore] so unit tests can inject a filesystem-backed instance without
 * needing an Android [android.content.Context]. Matches the shape of
 * [BridgeSafetyPreferencesRepository] (field `flow: Flow<...>`, per-field
 * suspend setters).
 */
class BargeInPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.relayDataStore)

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("barge_in_enabled")
        private val KEY_SENSITIVITY = stringPreferencesKey("barge_in_sensitivity")
        private val KEY_RESUME_AFTER_INTERRUPTION =
            booleanPreferencesKey("barge_in_resume_after_interruption")
    }

    val flow: Flow<BargeInPreferences> = dataStore.data.map { prefs ->
        BargeInPreferences(
            enabled = prefs[KEY_ENABLED] ?: DEFAULT_ENABLED,
            sensitivity = prefs[KEY_SENSITIVITY]?.let { decodeSensitivity(it) }
                ?: DEFAULT_SENSITIVITY,
            resumeAfterInterruption = prefs[KEY_RESUME_AFTER_INTERRUPTION]
                ?: DEFAULT_RESUME_AFTER_INTERRUPTION,
        )
    }

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun setSensitivity(value: BargeInSensitivity) {
        dataStore.edit { it[KEY_SENSITIVITY] = value.name }
    }

    suspend fun setResumeAfterInterruption(value: Boolean) {
        dataStore.edit { it[KEY_RESUME_AFTER_INTERRUPTION] = value }
    }

    private fun decodeSensitivity(raw: String): BargeInSensitivity =
        runCatching { BargeInSensitivity.valueOf(raw) }.getOrDefault(DEFAULT_SENSITIVITY)
}
