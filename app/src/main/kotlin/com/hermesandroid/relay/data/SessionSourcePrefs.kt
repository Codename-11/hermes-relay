package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionSourceDataStore by preferencesDataStore(name = "session_sources")
private val KEY_HIDDEN = stringSetPreferencesKey("hidden_sources")

/**
 * Session `source`s hidden from the drawer by default — the agent's noisiest
 * automation lanes. Everything else (your chats, Threads, discord, telegram, …)
 * shows. The user can hide/reveal more from the drawer source filter or Chat
 * settings; both edit the same persisted set.
 */
val DEFAULT_HIDDEN_SOURCES = setOf("cron", "webhook")

/** DataStore for which gateway sources the drawer hides. */
class SessionSourcePrefs(private val context: Context) {

    val hiddenSources: Flow<Set<String>> = context.sessionSourceDataStore.data.map { prefs ->
        prefs[KEY_HIDDEN] ?: DEFAULT_HIDDEN_SOURCES
    }

    suspend fun setHidden(source: String, hidden: Boolean) {
        val key = source.trim().lowercase()
        if (key.isBlank()) return
        context.sessionSourceDataStore.edit { prefs ->
            val cur = prefs[KEY_HIDDEN] ?: DEFAULT_HIDDEN_SOURCES
            prefs[KEY_HIDDEN] = if (hidden) cur + key else cur - key
        }
    }
}
