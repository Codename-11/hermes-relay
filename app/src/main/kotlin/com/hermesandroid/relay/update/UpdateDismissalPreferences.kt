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
 * Per-version dismissal + check-throttle state for the unified
 * (flavor-agnostic) update banner.
 *
 * Deliberately a NEW store, separate from the legacy sideload-only
 * [UpdatePreferences] (which keys dismissal by version *string* only and is
 * still consumed by the existing `UpdateViewModel` + About screen). This one
 * keys by the abstract [UpdateStatus.dismissKey]:
 *  - googlePlay → numeric versionCode (monotonic, compared as Long)
 *  - sideload   → version string (compared with [compareVersions])
 *
 * Dismissal is **per-version, not a forever mute**: the banner reappears as
 * soon as a *strictly newer* version than the dismissed one is offered. See
 * [isDismissed].
 *
 * `lastCheckAtMs` throttles automatic checks so we don't hit Play / GitHub on
 * every cold start and resume.
 */
private val Context.updateBannerPrefsStore by
    preferencesDataStore(name = "hermes_relay_update_banner")

object UpdateDismissalPreferences {
    private val KEY_DISMISSED = stringPreferencesKey("dismissed_update_key")
    private val KEY_LAST_CHECK = longPreferencesKey("last_check_at_ms")

    fun dismissedKey(context: Context): Flow<String?> =
        context.updateBannerPrefsStore.data.map { prefs: Preferences ->
            prefs[KEY_DISMISSED]
        }

    fun lastCheckAtMs(context: Context): Flow<Long> =
        context.updateBannerPrefsStore.data.map { prefs: Preferences ->
            prefs[KEY_LAST_CHECK] ?: 0L
        }

    suspend fun dismiss(context: Context, dismissKey: String) {
        context.updateBannerPrefsStore.edit { it[KEY_DISMISSED] = dismissKey }
    }

    suspend fun markChecked(context: Context, atMs: Long = System.currentTimeMillis()) {
        context.updateBannerPrefsStore.edit { it[KEY_LAST_CHECK] = atMs }
    }

    /**
     * Whether [available]'s dismiss key has already been dismissed.
     *
     * Returns `true` only when [dismissed] is non-null AND [available] is NOT
     * strictly newer than it. A newer version always re-shows (returns
     * `false`), which is the whole point of per-version dismissal.
     *
     * Pure function (no I/O) so it's unit-testable without DataStore. Both
     * keys are compared numerically when both parse as Longs (Play
     * versionCodes), otherwise via the loose [compareVersions] semver
     * comparator (sideload version strings). A mixed/unparseable pair falls
     * back to exact-string equality — conservative: only the exact dismissed
     * key stays hidden.
     */
    fun isDismissed(available: UpdateStatus, dismissed: String?): Boolean {
        val candidate = available.dismissKey ?: return false
        if (dismissed.isNullOrBlank()) return false
        return !isStrictlyNewer(candidate, dismissed)
    }

    /** True if [candidate] represents a strictly newer version than [reference]. */
    internal fun isStrictlyNewer(candidate: String, reference: String): Boolean {
        val c = candidate.toLongOrNull()
        val r = reference.toLongOrNull()
        if (c != null && r != null) return c > r
        // Fall back to semver string compare; if neither parses cleanly that
        // comparator still yields 0 for equal strings, so exact dupes stay
        // dismissed and any lexical/semver delta re-shows.
        return compareVersions(reference, candidate) < 0
    }
}
