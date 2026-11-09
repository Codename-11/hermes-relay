package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Client-side Firebase options for BYO FCM (phone half only).
 *
 * The Mac holds the service-account JSON used to *send*. Never store a
 * service account here.
 */
data class FcmClientConfig(
    val projectId: String = "",
    val applicationId: String = "",
    val apiKey: String = "",
    val gcmSenderId: String = "",
) {
    val isComplete: Boolean
        get() = listOf(projectId, applicationId, apiKey, gcmSenderId)
            .all { it.isNotBlank() }
}

private val KEY_FCM_ENABLED = booleanPreferencesKey("fcm_push_enabled")
private val KEY_FCM_PROJECT_ID = stringPreferencesKey("fcm_project_id")
private val KEY_FCM_APPLICATION_ID = stringPreferencesKey("fcm_application_id")
private val KEY_FCM_API_KEY = stringPreferencesKey("fcm_api_key")
private val KEY_FCM_GCM_SENDER_ID = stringPreferencesKey("fcm_gcm_sender_id")
private val KEY_FCM_LAST_TOKEN = stringPreferencesKey("fcm_last_token")
private val KEY_FCM_LAST_ERROR = stringPreferencesKey("fcm_last_error")
/** When true, notifications use generic copy (no message preview). Default false = show preview. */
private val KEY_FCM_HIDE_NOTIFICATION_CONTENT =
    booleanPreferencesKey("fcm_hide_notification_content")

data class FcmPushPreferences(
    val enabled: Boolean = false,
    val config: FcmClientConfig = FcmClientConfig(),
    val lastToken: String = "",
    val lastError: String = "",
    /** Default false: show message preview on wake/reply notifications. */
    val hideNotificationContent: Boolean = false,
)

fun Context.fcmPushPreferencesFlow(): Flow<FcmPushPreferences> =
    relayDataStore.data.map { prefs ->
        FcmPushPreferences(
            enabled = prefs[KEY_FCM_ENABLED] ?: false,
            config = FcmClientConfig(
                projectId = prefs[KEY_FCM_PROJECT_ID].orEmpty(),
                applicationId = prefs[KEY_FCM_APPLICATION_ID].orEmpty(),
                apiKey = prefs[KEY_FCM_API_KEY].orEmpty(),
                gcmSenderId = prefs[KEY_FCM_GCM_SENDER_ID].orEmpty(),
            ),
            lastToken = prefs[KEY_FCM_LAST_TOKEN].orEmpty(),
            lastError = prefs[KEY_FCM_LAST_ERROR].orEmpty(),
            hideNotificationContent = prefs[KEY_FCM_HIDE_NOTIFICATION_CONTENT] ?: false,
        )
    }

suspend fun Context.setFcmPushEnabled(enabled: Boolean) {
    relayDataStore.edit { it[KEY_FCM_ENABLED] = enabled }
}

suspend fun Context.setFcmHideNotificationContent(hide: Boolean) {
    relayDataStore.edit { it[KEY_FCM_HIDE_NOTIFICATION_CONTENT] = hide }
}

suspend fun Context.setFcmClientConfig(config: FcmClientConfig) {
    relayDataStore.edit {
        it[KEY_FCM_PROJECT_ID] = config.projectId.trim()
        it[KEY_FCM_APPLICATION_ID] = config.applicationId.trim()
        it[KEY_FCM_API_KEY] = config.apiKey.trim()
        it[KEY_FCM_GCM_SENDER_ID] = config.gcmSenderId.trim()
    }
}

suspend fun Context.setFcmLastToken(token: String) {
    relayDataStore.edit {
        it[KEY_FCM_LAST_TOKEN] = token
        if (token.isNotBlank()) it[KEY_FCM_LAST_ERROR] = ""
    }
}

suspend fun Context.setFcmLastError(message: String) {
    relayDataStore.edit { it[KEY_FCM_LAST_ERROR] = message }
}

suspend fun Context.clearFcmClientConfig() {
    relayDataStore.edit {
        it.remove(KEY_FCM_PROJECT_ID)
        it.remove(KEY_FCM_APPLICATION_ID)
        it.remove(KEY_FCM_API_KEY)
        it.remove(KEY_FCM_GCM_SENDER_ID)
        it.remove(KEY_FCM_LAST_TOKEN)
        it.remove(KEY_FCM_LAST_ERROR)
        it.remove(KEY_FCM_HIDE_NOTIFICATION_CONTENT)
        it[KEY_FCM_ENABLED] = false
    }
}

/**
 * Sync read for notification paths (FCM service / turn-complete). Default false =
 * show preview. Brief [runBlocking] is acceptable on those background threads.
 */
fun Context.hideNotificationContentBlocking(): Boolean =
    runBlocking {
        runCatching { fcmPushPreferencesFlow().first().hideNotificationContent }
            .getOrDefault(false)
    }

/**
 * Parse Firebase Android client fields from pasted `google-services.json`
 * text or a compact JSON object with the same keys.
 */
object FcmClientConfigParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): Result<FcmClientConfig> {
        val text = raw.trim()
        if (text.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty Firebase config"))
        }
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            val fromClient = root["client"]
                ?.let { el ->
                    // google-services.json uses an array under "client"
                    runCatching { el.jsonObject }.getOrNull()
                        ?: el.toString().let { null }
                }
            // Prefer first client entry when present
            val clientObj: JsonObject? = when (val client = root["client"]) {
                is kotlinx.serialization.json.JsonArray ->
                    client.firstOrNull()?.jsonObject
                is JsonObject -> client
                else -> null
            }
            val projectInfo = root["project_info"]?.jsonObject
            val clientInfo = clientObj?.get("client_info")?.jsonObject
            val androidClientInfo = clientInfo?.get("android_client_info")?.jsonObject
            val apiKeyObj = clientObj
                ?.get("api_key")
                ?.let { keyEl ->
                    when (keyEl) {
                        is kotlinx.serialization.json.JsonArray ->
                            keyEl.firstOrNull()?.jsonObject
                        is JsonObject -> keyEl
                        else -> null
                    }
                }

            val projectId = listOfNotNull(
                root["project_id"]?.jsonPrimitive?.contentOrNull,
                projectInfo?.get("project_id")?.jsonPrimitive?.contentOrNull,
            ).firstOrNull().orEmpty()

            val applicationId = listOfNotNull(
                root["application_id"]?.jsonPrimitive?.contentOrNull,
                root["mobilesdk_app_id"]?.jsonPrimitive?.contentOrNull,
                clientInfo?.get("mobilesdk_app_id")?.jsonPrimitive?.contentOrNull,
            ).firstOrNull().orEmpty()

            val apiKey = listOfNotNull(
                root["api_key"]?.jsonPrimitive?.contentOrNull,
                root["apiKey"]?.jsonPrimitive?.contentOrNull,
                apiKeyObj?.get("current_key")?.jsonPrimitive?.contentOrNull,
            ).firstOrNull().orEmpty()

            val gcmSenderId = listOfNotNull(
                root["gcm_sender_id"]?.jsonPrimitive?.contentOrNull,
                root["messaging_sender_id"]?.jsonPrimitive?.contentOrNull,
                root["messagingSenderId"]?.jsonPrimitive?.contentOrNull,
                projectInfo?.get("project_number")?.jsonPrimitive?.contentOrNull,
            ).firstOrNull().orEmpty()

            // package_name is informational; Firebase still needs the app
            // registered under the install applicationId.
            val config = FcmClientConfig(
                projectId = projectId,
                applicationId = applicationId,
                apiKey = apiKey,
                gcmSenderId = gcmSenderId,
            )
            if (!config.isComplete) {
                Result.failure(
                    IllegalArgumentException(
                        "Missing fields — need project_id, mobilesdk_app_id, api_key, gcm_sender_id",
                    ),
                )
            } else {
                Result.success(config)
            }
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Invalid JSON: ${e.message}", e))
        }
    }
}
