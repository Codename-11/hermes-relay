package com.hermesandroid.relay.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.hermesandroid.relay.data.FcmClientConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "FcmPushController"

/**
 * sideload: runtime FirebaseOptions on the **default** FirebaseApp so
 * [HermesFcmMessagingService] receives tokens/messages without a named-app
 * split. No google-services.json is baked into the APK.
 */
fun createFcmPushController(context: Context): FcmPushController =
    SideloadFcmPushController(context.applicationContext)

private class SideloadFcmPushController(
    private val appContext: Context,
) : FcmPushController {
    override val isSupported: Boolean = true

    override suspend fun applyConfig(config: FcmClientConfig, enabled: Boolean): String =
        withContext(Dispatchers.IO) {
            if (!enabled) {
                clearLocked()
                return@withContext "FCM disabled"
            }
            if (!config.isComplete) {
                return@withContext "Incomplete Firebase client config"
            }
            try {
                val options = FirebaseOptions.Builder()
                    .setProjectId(config.projectId)
                    .setApplicationId(config.applicationId)
                    .setApiKey(config.apiKey)
                    .setGcmSenderId(config.gcmSenderId)
                    .build()

                val default = FirebaseApp.getApps(appContext)
                    .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                if (default != null) {
                    // Options are immutable after init — recreate when changed.
                    val same =
                        default.options.projectId == options.projectId &&
                            default.options.applicationId == options.applicationId &&
                            default.options.apiKey == options.apiKey &&
                            default.options.gcmSenderId == options.gcmSenderId
                    if (!same) {
                        default.delete()
                        FirebaseApp.initializeApp(appContext, options)
                    }
                } else {
                    FirebaseApp.initializeApp(appContext, options)
                }
                FirebaseMessaging.getInstance().token.await()
                "FCM ready"
            } catch (t: Throwable) {
                Log.w(TAG, "applyConfig failed", t)
                "FCM init failed: ${t.message ?: t.javaClass.simpleName}"
            }
        }

    override suspend fun currentToken(): String = withContext(Dispatchers.IO) {
        try {
            if (FirebaseApp.getApps(appContext).isEmpty()) return@withContext ""
            FirebaseMessaging.getInstance().token.await().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "currentToken failed", t)
            ""
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { clearLocked() }

    private fun clearLocked() {
        try {
            FirebaseApp.getApps(appContext)
                .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "clear failed", t)
        }
    }
}
