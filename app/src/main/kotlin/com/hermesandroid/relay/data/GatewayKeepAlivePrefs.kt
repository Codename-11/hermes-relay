package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit

/**
 * Single source of truth for the opt-in "keep the gateway chat connection
 * alive in the background" preference. Off by default.
 *
 * Shared by [com.hermesandroid.relay.viewmodel.ConnectionViewModel] (the
 * StateFlow + setter that drive the foreground service and the client's
 * no-background-close flag) and
 * [com.hermesandroid.relay.network.GatewayKeepAliveService]'s Stop notification
 * action, so both read/write the same key.
 */
val KEY_GATEWAY_KEEP_ALIVE = booleanPreferencesKey("gateway_keep_alive_background")

/** Persist the keep-alive preference. Used by the FGS Stop action. */
suspend fun Context.setGatewayKeepAlive(enabled: Boolean) {
    relayDataStore.edit { it[KEY_GATEWAY_KEEP_ALIVE] = enabled }
}
