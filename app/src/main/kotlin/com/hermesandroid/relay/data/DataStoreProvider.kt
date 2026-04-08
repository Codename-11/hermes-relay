package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Single DataStore instance for the app settings.
 *
 * All code that needs DataStore access should use [Context.relayDataStore]
 * rather than creating its own [preferencesDataStore] delegate (multiple
 * delegates targeting the same file cause a runtime crash).
 */
internal val Context.relayDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "relay_settings"
)
