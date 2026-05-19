package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * User-tunable voice mode preferences.
 *
 * - [interactionMode] how the mic button behaves: "tap" | "hold" | "continuous".
 *   Drives the VoiceViewModel's InteractionMode enum at startup.
 * - [silenceThresholdMs] auto-stop threshold for listening: after this many
 *   ms of amplitude below the silence floor, stopListening() is called.
 * - [autoTts] future — read TTS on every non-voice assistant message.
 * - [language] STT language hint. Stored; not yet wired to /voice/transcribe
 *   (V1 doesn't accept a language param).
 */
data class VoiceSettings(
    val engineMode: String = VoiceEngineMode.HermesVoiceOutput.storageValue,
    val interactionMode: String = "tap",
    val silenceThresholdMs: Long = 3000L,
    val autoTts: Boolean = false,
    val language: String = "",
)

enum class VoiceEngineMode(val storageValue: String) {
    HermesVoiceOutput("hermes_voice_output"),
    RealtimeAgent("realtime_agent");

    companion object {
        fun fromStorage(value: String?): VoiceEngineMode =
            values().firstOrNull { it.storageValue == value } ?: HermesVoiceOutput
    }
}

class VoicePreferencesRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.relayDataStore)

    companion object {
        private val KEY_ENGINE_MODE = stringPreferencesKey("voice_engine_mode")
        private val KEY_INTERACTION_MODE = stringPreferencesKey("voice_interaction_mode")
        private val KEY_SILENCE_THRESHOLD_MS = longPreferencesKey("voice_silence_threshold_ms")
        private val KEY_AUTO_TTS = booleanPreferencesKey("voice_auto_tts")
        private val KEY_LANGUAGE = stringPreferencesKey("voice_language")

        const val DEFAULT_ENGINE_MODE = "hermes_voice_output"
        const val DEFAULT_INTERACTION_MODE = "tap"
        const val DEFAULT_SILENCE_THRESHOLD_MS = 3000L
        const val DEFAULT_AUTO_TTS = false
        const val DEFAULT_LANGUAGE = ""
    }

    val settings: Flow<VoiceSettings> = dataStore.data
        .map { prefs ->
            VoiceSettings(
                engineMode = VoiceEngineMode.fromStorage(
                    prefs[KEY_ENGINE_MODE] ?: DEFAULT_ENGINE_MODE,
                ).storageValue,
                interactionMode = prefs[KEY_INTERACTION_MODE] ?: DEFAULT_INTERACTION_MODE,
                silenceThresholdMs = prefs[KEY_SILENCE_THRESHOLD_MS] ?: DEFAULT_SILENCE_THRESHOLD_MS,
                autoTts = prefs[KEY_AUTO_TTS] ?: DEFAULT_AUTO_TTS,
                language = prefs[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE,
            )
        }
        .distinctUntilChanged()

    suspend fun setEngineMode(mode: VoiceEngineMode) {
        dataStore.edit { it[KEY_ENGINE_MODE] = mode.storageValue }
    }

    suspend fun setInteractionMode(mode: String) {
        dataStore.edit { it[KEY_INTERACTION_MODE] = mode }
    }

    suspend fun setSilenceThresholdMs(ms: Long) {
        dataStore.edit { it[KEY_SILENCE_THRESHOLD_MS] = ms.coerceAtLeast(500L) }
    }

    suspend fun setAutoTts(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_TTS] = enabled }
    }

    suspend fun setLanguage(language: String) {
        dataStore.edit { it[KEY_LANGUAGE] = language }
    }
}
