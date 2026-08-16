package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Phone-local behavior for a physical keyboard's unmodified Enter key. */
enum class PhysicalKeyboardEnterBehavior(val storedValue: String) {
    SendMessage("send_message"),
    InsertNewline("insert_newline"),
    ;

    companion object {
        fun fromStoredValue(value: String?): PhysicalKeyboardEnterBehavior =
            entries.firstOrNull { it.storedValue == value } ?: SendMessage
    }
}

/** Device-level chat input preferences shared by every Hermes profile. */
class ChatInputPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.relayDataStore)

    companion object {
        internal val KEY_PHYSICAL_KEYBOARD_ENTER =
            stringPreferencesKey("physical_keyboard_enter_behavior")
    }

    val physicalKeyboardEnterBehavior: Flow<PhysicalKeyboardEnterBehavior> = dataStore.data
        .map { preferences ->
            PhysicalKeyboardEnterBehavior.fromStoredValue(
                preferences[KEY_PHYSICAL_KEYBOARD_ENTER],
            )
        }
        .distinctUntilChanged()

    suspend fun setPhysicalKeyboardEnterBehavior(behavior: PhysicalKeyboardEnterBehavior) {
        dataStore.edit { preferences ->
            preferences[KEY_PHYSICAL_KEYBOARD_ENTER] = behavior.storedValue
        }
    }
}
