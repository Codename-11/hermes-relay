package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * User-tunable voice mode preferences.
 *
 * - [interactionMode] how the mic button behaves: "tap" | "hold" | "continuous".
 *   Drives the VoiceViewModel's InteractionMode enum at startup.
 * - [silenceThresholdMs] end-of-speech threshold for listening: after this many
 *   ms of amplitude below the silence floor (once speech has been heard),
 *   stopListening() is called. Default 1250 ms matches hermes-desktop
 *   voice_mode `silenceMs`. (Idle/no-speech 12 s and a 60 s hard turn cap are
 *   fixed in VoiceViewModel, not user-tunable — see startSilenceWatchdog.)
 *
 * Note: the standard path has no client-side auto-TTS or STT-language pref.
 * hermes-desktop only speaks responses during an active voice conversation
 * (no "read every typed message"), and STT language is a server-side
 * `stt.*.language` config edited via the Server voice config card, not a
 * client param — so neither is faked here.
 */
data class VoiceSettings(
    val engineMode: String = VoiceEngineMode.HermesVoiceOutput.storageValue,
    val audioRoute: String = VoiceAudioRoute.Auto.storageValue,
    val interactionMode: String = "tap",
    val silenceThresholdMs: Long = 1250L,
    val realtimeTraceDetails: Boolean = false,
    /**
     * When true (default), Realtime Agent keeps one provider session/socket open
     * across turns (persistent conversation). When false, falls back to the
     * legacy one-session-per-utterance path. See
     * docs/plans/2026-05-24-realtime-persistent-session.md.
     */
    val realtimePersistentSession: Boolean = true,
    /** Per-profile Realtime Agent session overrides; blank uses relay config. */
    val realtimeModel: String = "",
    val realtimeVoice: String = "",
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

/**
 * Active scope for per-profile voice prefs.
 *
 * Mirrors [ProfileSelectionStore]'s `_<connectionId>` keying and extends it to
 * `_<connectionId>_<profile>` so per-profile voice picks don't leak across
 * profiles (or across connections that expose a same-named profile).
 *
 * A null/blank [profileName] is the "default / launch profile" and resolves to
 * the un-namespaced global keys — i.e. the default profile *is* the base layer
 * that named profiles override. A null/blank [connectionId] degrades to
 * profile-only namespacing, which still isolates profiles within one
 * connection; it just can't disambiguate two connections with a same-named
 * profile. See [VoicePreferencesRepository.setActiveScope].
 */
data class VoiceProfileScope(
    val connectionId: String? = null,
    val profileName: String? = null,
) {
    companion object {
        val Global = VoiceProfileScope()
    }
}

class VoicePreferencesRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.relayDataStore)

    companion object {
        // --- Per-profile keys (override map; namespaced by active scope) -----
        // These are stored as base NAME strings (not typed Key<>s) so the
        // scoped key can be built per (connectionId, profile) at read/write
        // time. Resolution layers a per-profile value over the global value
        // over the hard default — see [scopedName] / [resolveString].
        //
        // Why these are per-profile: engine mode, audio route, and the
        // enhanced-voice and realtime-session overrides describe *which voice
        // the agent speaks with*, which is a property of the profile (the relay
        // already persists `voice_output:`/`realtime_voice:` per profile and
        // `RelayVoiceClient` already sends `?profile=`). Keeping them global
        // leaked one profile's voice onto every other profile.
        private const val KEY_ENGINE_MODE = "voice_engine_mode"
        private const val KEY_AUDIO_ROUTE = "voice_audio_route"
        private const val KEY_ENH_VOICE = "voice_enh_voice"
        private const val KEY_ENH_MODEL = "voice_enh_model"
        private const val KEY_ENH_AUDIO_TAGS = "voice_enh_audio_tags"
        private const val KEY_ENH_PERSONA = "voice_enh_persona"
        private const val KEY_ENH_LANGUAGE = "voice_enh_language"
        private const val KEY_REALTIME_MODEL = "voice_realtime_model"
        private const val KEY_REALTIME_VOICE = "voice_realtime_voice"

        // --- Global keys (shared across profiles; never namespaced) ----------
        // Why these stay global: interaction-mode and silence-threshold are
        // ergonomic input preferences about *how the user drives the mic*, not
        // about the agent's voice — a user wants the same tap/hold/continuous
        // habit regardless of which profile is active. The two realtime
        // diagnostic toggles (trace details, persistent session) are
        // engine-behaviour switches that aren't profile-specific. Keeping them
        // un-namespaced means switching profiles never churns these.
        private val KEY_INTERACTION_MODE = stringPreferencesKey("voice_interaction_mode")
        private val KEY_SILENCE_THRESHOLD_MS = longPreferencesKey("voice_silence_threshold_ms")
        private val KEY_REALTIME_TRACE_DETAILS = booleanPreferencesKey("voice_realtime_trace_details")
        private val KEY_REALTIME_PERSISTENT_SESSION =
            booleanPreferencesKey("voice_realtime_persistent_session")

        const val DEFAULT_ENGINE_MODE = "hermes_voice_output"
        const val DEFAULT_AUDIO_ROUTE = "auto"
        const val DEFAULT_INTERACTION_MODE = "tap"
        // 1250 ms matches hermes-desktop voice_mode `silenceMs` end-of-speech.
        const val DEFAULT_SILENCE_THRESHOLD_MS = 1250L
        const val DEFAULT_REALTIME_TRACE_DETAILS = false
        const val DEFAULT_REALTIME_PERSISTENT_SESSION = true

        /**
         * Build the storage name for a per-profile [base] key under [scope].
         *
         * - null/blank profile → returns [base] verbatim (the global base
         *   layer; the default profile reads/writes the un-namespaced key).
         * - profile set, no connection → `<base>_<profile>`.
         * - profile + connection set → `<base>_<connectionId>_<profile>`,
         *   matching [ProfileSelectionStore]'s connection-first ordering.
         */
        internal fun scopedName(base: String, scope: VoiceProfileScope): String {
            val profile = scope.profileName?.trim()?.takeIf { it.isNotEmpty() } ?: return base
            val conn = scope.connectionId?.trim()?.takeIf { it.isNotEmpty() }
            return if (conn != null) "${base}_${conn}_$profile" else "${base}_$profile"
        }
    }

    // In-memory active scope. Defaults to global so un-scoped consumers (and
    // every existing call site) behave exactly as before until a scope is set.
    private val _scope = MutableStateFlow(VoiceProfileScope.Global)

    /** The active per-profile scope. Set via [setActiveScope]. */
    val activeScope: StateFlow<VoiceProfileScope> = _scope.asStateFlow()

    /**
     * Point the repository at a (connection, profile) scope. Per-profile reads
     * and writes (engine/route/enhanced/realtime) re-target the namespaced keys
     * for that profile; global prefs are unaffected. Passing a null/blank profile name
     * reverts per-profile reads/writes to the global base layer (the default
     * profile). Idempotent — a no-op when the normalized scope is unchanged.
     */
    fun setActiveScope(connectionId: String?, profileName: String?) {
        val next = VoiceProfileScope(
            connectionId = connectionId?.trim()?.takeIf { it.isNotEmpty() },
            profileName = profileName?.trim()?.takeIf { it.isNotEmpty() },
        )
        if (_scope.value != next) {
            _scope.value = next
        }
    }

    /**
     * Emits the resolved [VoiceSettings] for the [activeScope]. Re-emits when
     * either the underlying DataStore or the active scope changes. Per-profile
     * fields are resolved as: per-profile key → global key → hard default.
     */
    val settings: Flow<VoiceSettings> = combine(_scope, dataStore.data) { scope, prefs ->
        VoiceSettings(
            // --- per-profile (override map) ---
            engineMode = VoiceEngineMode.fromStorage(
                resolveString(prefs, KEY_ENGINE_MODE, scope, DEFAULT_ENGINE_MODE),
            ).storageValue,
            audioRoute = VoiceAudioRoute.fromStorage(
                resolveString(prefs, KEY_AUDIO_ROUTE, scope, DEFAULT_AUDIO_ROUTE),
            ).storageValue,
            enhancedVoice = resolveString(prefs, KEY_ENH_VOICE, scope, ""),
            enhancedModel = resolveString(prefs, KEY_ENH_MODEL, scope, ""),
            enhancedAudioTags = resolveBoolean(prefs, KEY_ENH_AUDIO_TAGS, scope, false),
            enhancedPersona = resolveString(prefs, KEY_ENH_PERSONA, scope, ""),
            enhancedLanguage = resolveString(prefs, KEY_ENH_LANGUAGE, scope, ""),
            realtimeModel = resolveString(prefs, KEY_REALTIME_MODEL, scope, ""),
            realtimeVoice = resolveString(prefs, KEY_REALTIME_VOICE, scope, ""),
            // --- global (shared across profiles) ---
            interactionMode = prefs[KEY_INTERACTION_MODE] ?: DEFAULT_INTERACTION_MODE,
            silenceThresholdMs = prefs[KEY_SILENCE_THRESHOLD_MS] ?: DEFAULT_SILENCE_THRESHOLD_MS,
            realtimeTraceDetails = prefs[KEY_REALTIME_TRACE_DETAILS]
                ?: DEFAULT_REALTIME_TRACE_DETAILS,
            realtimePersistentSession = prefs[KEY_REALTIME_PERSISTENT_SESSION]
                ?: DEFAULT_REALTIME_PERSISTENT_SESSION,
        )
    }.distinctUntilChanged()

    // --- per-profile resolution (per-profile key → global key → default) -----

    private fun resolveString(
        prefs: Preferences,
        base: String,
        scope: VoiceProfileScope,
        default: String,
    ): String {
        val scopedName = scopedName(base, scope)
        if (scopedName != base) {
            prefs[stringPreferencesKey(scopedName)]?.let { return it }
        }
        return prefs[stringPreferencesKey(base)] ?: default
    }

    private fun resolveBoolean(
        prefs: Preferences,
        base: String,
        scope: VoiceProfileScope,
        default: Boolean,
    ): Boolean {
        val scopedName = scopedName(base, scope)
        if (scopedName != base) {
            prefs[booleanPreferencesKey(scopedName)]?.let { return it }
        }
        return prefs[booleanPreferencesKey(base)] ?: default
    }

    // --- per-profile setters (write the namespaced key for the active scope) -

    suspend fun setEngineMode(mode: VoiceEngineMode) {
        val key = stringPreferencesKey(scopedName(KEY_ENGINE_MODE, _scope.value))
        dataStore.edit { it[key] = mode.storageValue }
    }

    suspend fun setAudioRoute(route: VoiceAudioRoute) {
        val key = stringPreferencesKey(scopedName(KEY_AUDIO_ROUTE, _scope.value))
        dataStore.edit { it[key] = route.storageValue }
    }

    /** "" clears the override (relay falls back to the server's saved voice). */
    suspend fun setEnhancedVoice(voice: String) {
        val key = stringPreferencesKey(scopedName(KEY_ENH_VOICE, _scope.value))
        dataStore.edit { it[key] = voice.trim() }
    }

    /** "" clears the override (relay falls back to the server's saved model). */
    suspend fun setEnhancedModel(model: String) {
        val key = stringPreferencesKey(scopedName(KEY_ENH_MODEL, _scope.value))
        dataStore.edit { it[key] = model.trim() }
    }

    suspend fun setEnhancedAudioTags(enabled: Boolean) {
        val key = booleanPreferencesKey(scopedName(KEY_ENH_AUDIO_TAGS, _scope.value))
        dataStore.edit { it[key] = enabled }
    }

    /** "" clears the inline persona/style direction (Gemini). */
    suspend fun setEnhancedPersona(persona: String) {
        val key = stringPreferencesKey(scopedName(KEY_ENH_PERSONA, _scope.value))
        dataStore.edit { it[key] = persona }
    }

    /** "" clears the language override (xAI). */
    suspend fun setEnhancedLanguage(language: String) {
        val key = stringPreferencesKey(scopedName(KEY_ENH_LANGUAGE, _scope.value))
        dataStore.edit { it[key] = language.trim() }
    }

    /** "" clears the override so new sessions use the relay's saved model. */
    suspend fun setRealtimeModel(model: String) {
        val key = stringPreferencesKey(scopedName(KEY_REALTIME_MODEL, _scope.value))
        dataStore.edit { it[key] = model.trim() }
    }

    /** "" clears the override so new sessions use the relay's saved voice. */
    suspend fun setRealtimeVoice(voice: String) {
        val key = stringPreferencesKey(scopedName(KEY_REALTIME_VOICE, _scope.value))
        dataStore.edit { it[key] = voice.trim() }
    }

    /** Persist a compatible model/voice pair without exposing a half-updated snapshot. */
    suspend fun setRealtimeSelection(model: String, voice: String) {
        val scope = _scope.value
        val modelKey = stringPreferencesKey(scopedName(KEY_REALTIME_MODEL, scope))
        val voiceKey = stringPreferencesKey(scopedName(KEY_REALTIME_VOICE, scope))
        dataStore.edit {
            it[modelKey] = model.trim()
            it[voiceKey] = voice.trim()
        }
    }

    // --- global setters (always the un-namespaced key) -----------------------

    suspend fun setInteractionMode(mode: String) {
        dataStore.edit { it[KEY_INTERACTION_MODE] = mode }
    }

    suspend fun setSilenceThresholdMs(ms: Long) {
        dataStore.edit { it[KEY_SILENCE_THRESHOLD_MS] = ms.coerceAtLeast(500L) }
    }

    suspend fun setRealtimeTraceDetails(enabled: Boolean) {
        dataStore.edit { it[KEY_REALTIME_TRACE_DETAILS] = enabled }
    }

    suspend fun setRealtimePersistentSession(enabled: Boolean) {
        dataStore.edit { it[KEY_REALTIME_PERSISTENT_SESSION] = enabled }
    }
}
