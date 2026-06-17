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
    val audioRoute: String = VoiceAudioRoute.Auto.storageValue,
    val interactionMode: String = "tap",
    val silenceThresholdMs: Long = 3000L,
    val autoTts: Boolean = false,
    val language: String = "",
    val realtimeTraceDetails: Boolean = false,
    /**
     * When true (default), Realtime Agent keeps one provider session/socket open
     * across turns (persistent conversation). When false, falls back to the
     * legacy one-session-per-utterance path. See
     * docs/plans/2026-05-24-realtime-persistent-session.md.
     */
    val realtimePersistentSession: Boolean = true,
    /**
     * Enhanced-voice overrides for the relay TTS path, mapped onto the active
     * provider (Gemini / xAI). Empty string / false means "use the server's
     * saved config" — the relay only applies a field when it is set. Surfaced
     * in Voice Settings only when the relay advertises an enhanced provider
     * (`/voice/config` `tts.enhanced.supported`). Field meaning is generic:
     * `enhancedVoice` → Gemini voice / xAI voice_id; `enhancedAudioTags` →
     * Gemini audio_tags / xAI auto_speech_tags; `enhancedPersona` is Gemini-only
     * and `enhancedLanguage` is xAI-only.
     */
    val enhancedVoice: String = "",
    val enhancedModel: String = "",
    val enhancedAudioTags: Boolean = false,
    val enhancedPersona: String = "",
    val enhancedLanguage: String = "",
)

/**
 * Per-request enhanced-voice overrides forwarded to the relay's
 * `/voice/synthesize`. Mirrors the generic fields recognized by
 * `plugin/relay/voice.py:_extract_voice_overrides`; the relay maps them onto
 * the active provider's config.
 */
data class EnhancedVoiceOverrides(
    val voice: String? = null,
    val model: String? = null,
    val audioTags: Boolean? = null,
    val personaPrompt: String? = null,
    val language: String? = null,
) {
    val isEmpty: Boolean
        get() = voice == null && model == null && audioTags == null &&
            personaPrompt == null && language == null

    companion object {
        /**
         * Build overrides from persisted settings, or null when nothing is set
         * (so the relay falls back to the server's saved config). The audio-tags
         * toggle only sends `true` — leaving it off defers to the server default
         * rather than forcing it off.
         */
        fun fromSettings(s: VoiceSettings): EnhancedVoiceOverrides? {
            val overrides = EnhancedVoiceOverrides(
                voice = s.enhancedVoice.takeIf { it.isNotBlank() },
                model = s.enhancedModel.takeIf { it.isNotBlank() },
                audioTags = true.takeIf { s.enhancedAudioTags },
                personaPrompt = s.enhancedPersona.takeIf { it.isNotBlank() },
                language = s.enhancedLanguage.takeIf { it.isNotBlank() },
            )
            return overrides.takeUnless { it.isEmpty }
        }
    }
}

enum class VoiceEngineMode(val storageValue: String) {
    HermesVoiceOutput("hermes_voice_output"),
    RealtimeAgent("realtime_agent");

    companion object {
        fun fromStorage(value: String?): VoiceEngineMode =
            values().firstOrNull { it.storageValue == value } ?: HermesVoiceOutput
    }
}

enum class VoiceAudioRoute(val storageValue: String) {
    Auto("auto"),
    Standard("standard"),
    Relay("relay");

    companion object {
        fun fromStorage(value: String?): VoiceAudioRoute =
            values().firstOrNull { it.storageValue == value } ?: Auto
    }
}

class VoicePreferencesRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.relayDataStore)

    companion object {
        private val KEY_ENGINE_MODE = stringPreferencesKey("voice_engine_mode")
        private val KEY_AUDIO_ROUTE = stringPreferencesKey("voice_audio_route")
        private val KEY_INTERACTION_MODE = stringPreferencesKey("voice_interaction_mode")
        private val KEY_SILENCE_THRESHOLD_MS = longPreferencesKey("voice_silence_threshold_ms")
        private val KEY_AUTO_TTS = booleanPreferencesKey("voice_auto_tts")
        private val KEY_LANGUAGE = stringPreferencesKey("voice_language")
        private val KEY_REALTIME_TRACE_DETAILS = booleanPreferencesKey("voice_realtime_trace_details")
        private val KEY_REALTIME_PERSISTENT_SESSION =
            booleanPreferencesKey("voice_realtime_persistent_session")
        private val KEY_ENH_VOICE = stringPreferencesKey("voice_enh_voice")
        private val KEY_ENH_MODEL = stringPreferencesKey("voice_enh_model")
        private val KEY_ENH_AUDIO_TAGS = booleanPreferencesKey("voice_enh_audio_tags")
        private val KEY_ENH_PERSONA = stringPreferencesKey("voice_enh_persona")
        private val KEY_ENH_LANGUAGE = stringPreferencesKey("voice_enh_language")

        const val DEFAULT_ENGINE_MODE = "hermes_voice_output"
        const val DEFAULT_AUDIO_ROUTE = "auto"
        const val DEFAULT_INTERACTION_MODE = "tap"
        const val DEFAULT_SILENCE_THRESHOLD_MS = 3000L
        const val DEFAULT_AUTO_TTS = false
        const val DEFAULT_LANGUAGE = ""
        const val DEFAULT_REALTIME_TRACE_DETAILS = false
        const val DEFAULT_REALTIME_PERSISTENT_SESSION = true
    }

    val settings: Flow<VoiceSettings> = dataStore.data
        .map { prefs ->
            VoiceSettings(
                engineMode = VoiceEngineMode.fromStorage(
                    prefs[KEY_ENGINE_MODE] ?: DEFAULT_ENGINE_MODE,
                ).storageValue,
                audioRoute = VoiceAudioRoute.fromStorage(
                    prefs[KEY_AUDIO_ROUTE] ?: DEFAULT_AUDIO_ROUTE,
                ).storageValue,
                interactionMode = prefs[KEY_INTERACTION_MODE] ?: DEFAULT_INTERACTION_MODE,
                silenceThresholdMs = prefs[KEY_SILENCE_THRESHOLD_MS] ?: DEFAULT_SILENCE_THRESHOLD_MS,
                autoTts = prefs[KEY_AUTO_TTS] ?: DEFAULT_AUTO_TTS,
                language = prefs[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE,
                realtimeTraceDetails = prefs[KEY_REALTIME_TRACE_DETAILS]
                    ?: DEFAULT_REALTIME_TRACE_DETAILS,
                realtimePersistentSession = prefs[KEY_REALTIME_PERSISTENT_SESSION]
                    ?: DEFAULT_REALTIME_PERSISTENT_SESSION,
                enhancedVoice = prefs[KEY_ENH_VOICE] ?: "",
                enhancedModel = prefs[KEY_ENH_MODEL] ?: "",
                enhancedAudioTags = prefs[KEY_ENH_AUDIO_TAGS] ?: false,
                enhancedPersona = prefs[KEY_ENH_PERSONA] ?: "",
                enhancedLanguage = prefs[KEY_ENH_LANGUAGE] ?: "",
            )
        }
        .distinctUntilChanged()

    suspend fun setEngineMode(mode: VoiceEngineMode) {
        dataStore.edit { it[KEY_ENGINE_MODE] = mode.storageValue }
    }

    suspend fun setAudioRoute(route: VoiceAudioRoute) {
        dataStore.edit { it[KEY_AUDIO_ROUTE] = route.storageValue }
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

    suspend fun setRealtimeTraceDetails(enabled: Boolean) {
        dataStore.edit { it[KEY_REALTIME_TRACE_DETAILS] = enabled }
    }

    suspend fun setRealtimePersistentSession(enabled: Boolean) {
        dataStore.edit { it[KEY_REALTIME_PERSISTENT_SESSION] = enabled }
    }

    /** "" clears the override (relay falls back to the server's saved voice). */
    suspend fun setEnhancedVoice(voice: String) {
        dataStore.edit { it[KEY_ENH_VOICE] = voice.trim() }
    }

    /** "" clears the override (relay falls back to the server's saved model). */
    suspend fun setEnhancedModel(model: String) {
        dataStore.edit { it[KEY_ENH_MODEL] = model.trim() }
    }

    suspend fun setEnhancedAudioTags(enabled: Boolean) {
        dataStore.edit { it[KEY_ENH_AUDIO_TAGS] = enabled }
    }

    /** "" clears the inline persona/style direction (Gemini). */
    suspend fun setEnhancedPersona(persona: String) {
        dataStore.edit { it[KEY_ENH_PERSONA] = persona }
    }

    /** "" clears the language override (xAI). */
    suspend fun setEnhancedLanguage(language: String) {
        dataStore.edit { it[KEY_ENH_LANGUAGE] = language.trim() }
    }
}
