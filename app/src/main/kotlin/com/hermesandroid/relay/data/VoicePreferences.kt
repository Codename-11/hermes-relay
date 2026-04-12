package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
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
    val interactionMode: String = "tap",
    val silenceThresholdMs: Long = 3000L,
    val autoTts: Boolean = false,
    val language: String = "",
)

class VoicePreferencesRepository(private val context: Context) {

    companion object {
        private val KEY_INTERACTION_MODE = stringPreferencesKey("voice_interaction_mode")
        private val KEY_SILENCE_THRESHOLD_MS = longPreferencesKey("voice_silence_threshold_ms")
        private val KEY_AUTO_TTS = booleanPreferencesKey("voice_auto_tts")
        private val KEY_LANGUAGE = stringPreferencesKey("voice_language")

        const val DEFAULT_INTERACTION_MODE = "tap"
        const val DEFAULT_SILENCE_THRESHOLD_MS = 3000L
        const val DEFAULT_AUTO_TTS = false
        const val DEFAULT_LANGUAGE = ""
    }

    val settings: Flow<VoiceSettings> = context.relayDataStore.data.map { prefs ->
        VoiceSettings(
            interactionMode = prefs[KEY_INTERACTION_MODE] ?: DEFAULT_INTERACTION_MODE,
            silenceThresholdMs = prefs[KEY_SILENCE_THRESHOLD_MS] ?: DEFAULT_SILENCE_THRESHOLD_MS,
            autoTts = prefs[KEY_AUTO_TTS] ?: DEFAULT_AUTO_TTS,
            language = prefs[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE,
        )
    }

    suspend fun setInteractionMode(mode: String) {
        context.relayDataStore.edit { it[KEY_INTERACTION_MODE] = mode }
    }

    suspend fun setSilenceThresholdMs(ms: Long) {
        context.relayDataStore.edit { it[KEY_SILENCE_THRESHOLD_MS] = ms.coerceAtLeast(500L) }
    }

    suspend fun setAutoTts(enabled: Boolean) {
        context.relayDataStore.edit { it[KEY_AUTO_TTS] = enabled }
    }

    suspend fun setLanguage(language: String) {
        context.relayDataStore.edit { it[KEY_LANGUAGE] = language }
    }
}
