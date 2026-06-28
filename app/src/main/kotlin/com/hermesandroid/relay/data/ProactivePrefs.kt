package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * "Let Hermes message me" — the off-by-default opt-in that lets the agent
 * proactively push messages to this phone (the `phone` Hermes platform).
 *
 * This is the app half of a two-sided gate: the server-side adapter is gated
 * on `PHONE_ENABLED`, and the relay can only push when the app has sent
 * `proactive.subscribe` — which the app only does when this flag is on. So
 * nothing is delivered unless BOTH sides opt in.
 *
 * Shared by [com.hermesandroid.relay.viewmodel.ConnectionViewModel] (the
 * StateFlow + subscribe/unsubscribe wiring) and the Settings switch that
 * flips it. Phase 3 expands this into a fuller `ProactivePreferences`
 * (quiet hours, per-profile scope, rate limiting); the enablement flag is
 * the foundational gate and lives here next to the other shared pref keys.
 */
val KEY_PROACTIVE_ENABLED = booleanPreferencesKey("proactive_messages_enabled")

/** Persist the "Let Hermes message me" preference. */
suspend fun Context.setProactiveEnabled(enabled: Boolean) {
    relayDataStore.edit { it[KEY_PROACTIVE_ENABLED] = enabled }
}

/** Reactive read of the enablement flag — defaults to false (off). */
fun Context.proactiveEnabledFlow(): Flow<Boolean> =
    relayDataStore.data.map { it[KEY_PROACTIVE_ENABLED] ?: false }
