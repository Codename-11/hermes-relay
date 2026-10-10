package com.hermesandroid.relay.push

import android.content.Context
import com.hermesandroid.relay.data.FcmClientConfig

/** googlePlay: BYO FCM is sideload-only in this theme. */
fun createFcmPushController(context: Context): FcmPushController =
    object : FcmPushController {
        override val isSupported: Boolean = false
        override suspend fun applyConfig(config: FcmClientConfig, enabled: Boolean): String =
            "FCM is available on the sideload build only"
        override suspend fun currentToken(): String = ""
        override suspend fun clear() = Unit
    }
