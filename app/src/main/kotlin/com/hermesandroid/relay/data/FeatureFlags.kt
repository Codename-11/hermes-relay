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
}
