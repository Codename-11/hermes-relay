package com.hermesandroid.relay.update

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistent state for the update-check flow.
 *
 * - [lastCheckAtMs] — timestamp of the last successful (not errored) check.
 *   Drives the auto-check interval so we don't re-hit GitHub on every
 *   cold start. Errors don't update this, so transient failures fall
 *   back to the default refresh cadence.
 * - [dismissedVersion] — the version the user has chosen to skip. The
 *   banner stays hidden for this version. A *newer* version re-shows the
 *   banner automatically (by design: dismiss is per-version, not a
 *   forever mute).
 */
private val Context.updatePrefsStore by preferencesDataStore(name = "hermes_relay_update")

object UpdatePreferences {
    private val KEY_LAST_CHECK = longPreferencesKey("last_check_at_ms")
    private val KEY_DISMISSED_VERSION = stringPreferencesKey("dismissed_version")

    fun lastCheckAtMs(context: Context): Flow<Long> =
        context.updatePrefsStore.data.map { prefs: Preferences ->
            prefs[KEY_LAST_CHECK] ?: 0L
        }

    fun dismissedVersion(context: Context): Flow<String?> =
        context.updatePrefsStore.data.map { prefs: Preferences ->
            prefs[KEY_DISMISSED_VERSION]
        }

    suspend fun markChecked(context: Context, atMs: Long = System.currentTimeMillis()) {
        context.updatePrefsStore.edit { it[KEY_LAST_CHECK] = atMs }
    }

    suspend fun dismissVersion(context: Context, version: String) {
        context.updatePrefsStore.edit { it[KEY_DISMISSED_VERSION] = version }
    }
}
