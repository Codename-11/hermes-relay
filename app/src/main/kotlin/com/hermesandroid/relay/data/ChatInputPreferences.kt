package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

/** Default intent for a message submitted while an agent is responding. */
enum class BusyMessageAction(val storedValue: String) {
    CorrectNow("correct_now"),
    QueueNext("queue_next");

    companion object {
        fun fromStoredValue(value: String?): BusyMessageAction =
            entries.firstOrNull { it.storedValue == value } ?: CorrectNow
    }
}

fun canCorrectBusyMessage(
    steerable: Boolean,
    hasAttachments: Boolean,
    hasPendingInput: Boolean,
    status: String?,
    text: String,
): Boolean = steerable && !hasAttachments && !hasPendingInput &&
    status?.contains("compact", ignoreCase = true) != true && !text.trimStart().startsWith("/")

/** Device-level chat input preferences shared by every Hermes profile. */
class ChatInputPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.relayDataStore)

    companion object {
        internal val KEY_BUSY_MESSAGE_ACTION = stringPreferencesKey("busy_message_action")
        internal val KEY_PHYSICAL_KEYBOARD_ENTER =
            stringPreferencesKey("physical_keyboard_enter_behavior")
        internal val KEY_CONVERT_LARGE_PASTES =
            booleanPreferencesKey("convert_large_pastes_to_attachments")
        internal val KEY_SHOW_GIT_WORKSPACE_IN_CHAT =
            booleanPreferencesKey("show_git_workspace_in_chat")
    }

    val physicalKeyboardEnterBehavior: Flow<PhysicalKeyboardEnterBehavior> = dataStore.data
        .map { preferences ->
            PhysicalKeyboardEnterBehavior.fromStoredValue(
                preferences[KEY_PHYSICAL_KEYBOARD_ENTER],
            )
        }
        .distinctUntilChanged()

    val busyMessageAction: Flow<BusyMessageAction> = dataStore.data
        .map { BusyMessageAction.fromStoredValue(it[KEY_BUSY_MESSAGE_ACTION]) }
        .distinctUntilChanged()

    suspend fun setBusyMessageAction(action: BusyMessageAction) {
        dataStore.edit { it[KEY_BUSY_MESSAGE_ACTION] = action.storedValue }
    }

    val convertLargePastesToAttachments: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[KEY_CONVERT_LARGE_PASTES] ?: true }
        .distinctUntilChanged()

    val showGitWorkspaceInChat: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[KEY_SHOW_GIT_WORKSPACE_IN_CHAT] ?: true }
        .distinctUntilChanged()

    suspend fun setPhysicalKeyboardEnterBehavior(behavior: PhysicalKeyboardEnterBehavior) {
        dataStore.edit { preferences ->
            preferences[KEY_PHYSICAL_KEYBOARD_ENTER] = behavior.storedValue
        }
    }

    suspend fun setConvertLargePastesToAttachments(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_CONVERT_LARGE_PASTES] = enabled
        }
    }

    suspend fun setShowGitWorkspaceInChat(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_SHOW_GIT_WORKSPACE_IN_CHAT] = enabled
        }
    }
}
