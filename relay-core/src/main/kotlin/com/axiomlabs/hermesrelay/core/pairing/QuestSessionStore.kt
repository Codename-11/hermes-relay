package com.axiomlabs.hermesrelay.core.pairing

import android.content.Context
import java.util.UUID

class QuestSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("hermes_quest_session", Context.MODE_PRIVATE)

    var relayUrl: String?
        get() = prefs.getString(KEY_RELAY_URL, null)
        set(value) = prefs.edit().putString(KEY_RELAY_URL, value).apply()

    var sessionToken: String?
        get() = prefs.getString(KEY_SESSION_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_SESSION_TOKEN, value).apply()

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (existing != null) return existing
            val next = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, next).apply()
            return next
        }

    fun clearSessionToken() {
        prefs.edit().remove(KEY_SESSION_TOKEN).apply()
    }

    private companion object {
        const val KEY_RELAY_URL = "relay_url"
        const val KEY_SESSION_TOKEN = "session_token"
        const val KEY_DEVICE_ID = "device_id"
    }
}
