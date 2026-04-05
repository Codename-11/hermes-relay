package com.hermesandroid.companion.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Single DataStore instance for the companion app settings.
 *
 * All code that needs DataStore access should use [Context.companionDataStore]
 * rather than creating its own [preferencesDataStore] delegate (multiple
 * delegates targeting the same file cause a runtime crash).
 */
internal val Context.companionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "companion_settings"
)
