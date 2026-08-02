package com.hermesandroid.relay.plugins.runtime

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermesandroid.relay.data.AgentDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

/** Durable, local-only plugin enablement and permission grants. */
class PluginPreferenceStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.pluginPreferencesDataStore)

    fun state(scope: PluginScope): Flow<PluginPreferenceState> {
        val suffix = scope.keySuffix()
        val enabledKey = booleanPreferencesKey("enabled_$suffix")
        val grantsKey = stringSetPreferencesKey("grants_$suffix")
        return dataStore.data.map { preferences ->
            PluginPreferenceState(
                enabled = preferences[enabledKey] ?: false,
                grants = preferences[grantsKey].orEmpty(),
                configured = enabledKey in preferences,
            )
        }
    }

    suspend fun setEnabled(scope: PluginScope, enabled: Boolean) {
        val key = booleanPreferencesKey("enabled_${scope.keySuffix()}")
        dataStore.edit { preferences -> preferences[key] = enabled }
    }

    suspend fun setGrants(scope: PluginScope, grants: Set<String>) {
        require(grants.none(String::isBlank)) { "Plugin grants must not be blank" }
        val key = stringSetPreferencesKey("grants_${scope.keySuffix()}")
        dataStore.edit { preferences -> preferences[key] = grants.toSortedSet() }
    }

    suspend fun clear(scope: PluginScope) {
        val suffix = scope.keySuffix()
        dataStore.edit { preferences ->
            preferences.remove(booleanPreferencesKey("enabled_$suffix"))
            preferences.remove(stringSetPreferencesKey("grants_$suffix"))
        }
    }

    private fun PluginScope.keySuffix(): String {
        val profileKey = AgentDisplay.profileSessionKey(profileName)
        val material = "$connectionId\u0000$profileKey\u0000$pluginId"
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal val Context.pluginPreferencesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "plugin_preferences")
