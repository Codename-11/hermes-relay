package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

const val CHAT_TEXT_SCALE_DEFAULT = 1.0f
const val CHAT_TEXT_SCALE_MIN = 0.85f
const val CHAT_TEXT_SCALE_MAX = 2.0f

fun normalizeChatTextScale(scale: Float): Float =
    if (scale.isNaN()) CHAT_TEXT_SCALE_DEFAULT else scale.coerceIn(
        CHAT_TEXT_SCALE_MIN,
        CHAT_TEXT_SCALE_MAX,
    )

class ChatTextScalePreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        internal val KEY_SCALE = floatPreferencesKey("chat_text_scale")
    }

    val scale: Flow<Float> = dataStore.data
        .map { normalizeChatTextScale(it[KEY_SCALE] ?: CHAT_TEXT_SCALE_DEFAULT) }
        .distinctUntilChanged()

    suspend fun setScale(scale: Float) {
        dataStore.edit { it[KEY_SCALE] = normalizeChatTextScale(scale) }
    }
}