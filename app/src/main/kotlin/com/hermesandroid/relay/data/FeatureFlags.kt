package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.hermesandroid.relay.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Feature flags with compile-time defaults and runtime overrides.
 *
 * In debug builds, all features are unlocked by default.
 * In release builds, experimental features are hidden unless the user
 * enables Developer Options (tap version 7 times in Settings > About).
 *
 * Runtime overrides persist in DataStore so testers can toggle features
 * without needing a debug APK.
 */
object FeatureFlags {

    // DataStore keys
    private val KEY_DEV_OPTIONS_UNLOCKED = booleanPreferencesKey("dev_options_unlocked")
    private val KEY_RELAY_ENABLED = booleanPreferencesKey("feature_relay_enabled")

    /** Whether the app is running a debug build. */
    val isDevBuild: Boolean get() = BuildConfig.DEV_MODE

    /** Observe whether Developer Options have been unlocked. */
    fun devOptionsUnlocked(context: Context): Flow<Boolean> =
        context.relayDataStore.data.map { prefs ->
            if (isDevBuild) true else prefs[KEY_DEV_OPTIONS_UNLOCKED] ?: false
        }

    /** Observe whether relay features (settings, pairing, onboarding pages) are enabled. */
    fun relayEnabled(context: Context): Flow<Boolean> =
        context.relayDataStore.data.map { prefs ->
            if (isDevBuild) true else prefs[KEY_RELAY_ENABLED] ?: false
        }

    /** Unlock Developer Options. */
    suspend fun unlockDevOptions(context: Context) {
        context.relayDataStore.edit { prefs ->
            prefs[KEY_DEV_OPTIONS_UNLOCKED] = true
        }
    }

    /** Lock Developer Options and disable all experimental features. */
    suspend fun lockDevOptions(context: Context) {
        context.relayDataStore.edit { prefs ->
            prefs[KEY_DEV_OPTIONS_UNLOCKED] = false
            prefs[KEY_RELAY_ENABLED] = false
        }
    }

    /** Toggle relay features (terminal/bridge settings, pairing, onboarding relay page). */
    suspend fun setRelayEnabled(context: Context, enabled: Boolean) {
        context.relayDataStore.edit { prefs ->
            prefs[KEY_RELAY_ENABLED] = enabled
        }
    }

    /**
     * Safety hook for the V5 ExoPlayer rollout.
     *
     * Default `true` — VoicePlayer uses Media3 ExoPlayer for gapless TTS
     * queue playback. No MediaPlayer fallback is wired this session; if a
     * regression surfaces in the field we'll rewire one and honor this flag
     * at the construction site. Flipping this to `false` today has no
     * effect — it's a placeholder hook, not yet load-bearing.
     */
    const val useExoPlayerVoice: Boolean = true
}

/**
 * Compile-time gating based on the active Gradle product flavor.
 *
 * Phase 3 keeps "Hermes Bridge" as the umbrella, but only the `sideload`
 * flavor ships AccessibilityService-backed Device Control. The `googlePlay`
 * flavor is Bridge Core: relay pairing, chat, voice, terminal, notification
 * companion, media, and session-grant surfaces without screen reading, taps,
 * typing, screenshots, overlays, or unattended control.
 *
 * Device Control tier definitions (see `Phase 3 — Bridge Channel.md`):
 *   1. baseline          — sideload only (app open, tap, navigate within app)
 *   2. screen context    — sideload only (Accessibility tree / screen reads)
 *   3. voice-first       — sideload only (always-on voice capture)
 *   4. vision-first      — sideload only (always-on screen reading)
 *   5. safety rails      — sideload only (confirmation dialogs, action log)
 *   6. ambitious future  — sideload only (cross-app macros, scheduling)
 */
object BuildFlavor {
    const val GOOGLE_PLAY = "googlePlay"
    const val SIDELOAD = "sideload"
    val current: String get() = BuildConfig.FLAVOR

    /**
     * True when the current build is the sideload track. Kept as a property
     * getter (not a compile-time `val`) so the call site reads cleanly —
     * `BuildFlavor.isSideload` is easier to eyeball in `BridgeCommandHandler`
     * than `BuildFlavor.current == BuildFlavor.SIDELOAD`. R8 folds the
     * comparison away in release builds because `current` resolves to a
     * compile-time `BuildConfig.FLAVOR` string constant.
     *
     * Used by Tier C (C1-C4) tool gates — `android_location`,
     * `android_search_contacts`, `android_call`, `android_send_sms` — to
     * return `"sideload-only"` 403 responses on googlePlay builds instead
     * of crashing on a missing permission declaration.
     */
    val isSideload: Boolean get() = current == SIDELOAD

    val bridgeTier1: Boolean get() = current == SIDELOAD         // baseline device control
    val bridgeTier2: Boolean get() = current == SIDELOAD         // screen context
    val bridgeTier3: Boolean get() = current == SIDELOAD         // voice-first
    val bridgeTier4: Boolean get() = current == SIDELOAD         // vision-first
    val bridgeTier5: Boolean get() = current == SIDELOAD         // safety rails
    val bridgeTier6: Boolean get() = current == SIDELOAD         // future ambitious

    /** Human-readable badge label for the Settings → About version row. */
    val displayName: String
        get() = when (current) {
            GOOGLE_PLAY -> "Google Play"
            SIDELOAD -> "Sideload"
            else -> current.ifBlank { "Unknown" }
        }
}
