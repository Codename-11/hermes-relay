package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class ProviderUsageLandingMode(val storedValue: String) {
    Summary("summary"),
    Expanded("expanded"),
    Hidden("hidden"),
    ;

    companion object {
        fun fromStoredValue(value: String?): ProviderUsageLandingMode =
            entries.firstOrNull { it.storedValue == value } ?: Summary
    }
}

data class ProviderUsagePreferences(
    val landingMode: ProviderUsageLandingMode = ProviderUsageLandingMode.Summary,
    val visibleProviders: Set<String> = DEFAULT_VISIBLE_PROVIDERS,
) {
    companion object {
        val DEFAULT_VISIBLE_PROVIDERS = setOf("openai-codex", "nous", "opencode-go")
    }
}

class ProviderUsagePreferencesRepository(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.relayDataStore)

    companion object {
        internal val KEY_LANDING_MODE = stringPreferencesKey("provider_usage_landing_mode")
        internal val KEY_VISIBLE_PROVIDERS = stringSetPreferencesKey("provider_usage_visible_providers")
    }

    val preferences: Flow<ProviderUsagePreferences> = dataStore.data
        .map { prefs ->
            ProviderUsagePreferences(
                landingMode = ProviderUsageLandingMode.fromStoredValue(prefs[KEY_LANDING_MODE]),
                visibleProviders = prefs[KEY_VISIBLE_PROVIDERS]
                    ?: ProviderUsagePreferences.DEFAULT_VISIBLE_PROVIDERS,
            )
        }
        .distinctUntilChanged()

    suspend fun setLandingMode(mode: ProviderUsageLandingMode) {
        dataStore.edit { it[KEY_LANDING_MODE] = mode.storedValue }
    }

    suspend fun setProviderVisible(providerId: String, visible: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_VISIBLE_PROVIDERS]
                ?: ProviderUsagePreferences.DEFAULT_VISIBLE_PROVIDERS
            prefs[KEY_VISIBLE_PROVIDERS] = if (visible) current + providerId else current - providerId
        }
    }
}
