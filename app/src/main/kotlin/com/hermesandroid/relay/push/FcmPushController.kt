package com.hermesandroid.relay.push

import android.content.Context
import com.hermesandroid.relay.data.FcmClientConfig

/**
 * Flavor-split BYO FCM controller.
 *
 * - **sideload**: runtime [FirebaseApp] init + token fetch/upload hooks.
 * - **googlePlay**: no-op (this theme keeps messaging off the Play track).
 *
 * Factories live in each flavor as `createFcmPushController` with the same
 * package + signature (same pattern as update availability).
 */
interface FcmPushController {
    /** True when this build can actually talk to FCM (sideload + GMS). */
    val isSupported: Boolean

    /**
     * Apply client config and optionally enable. Returns a short status
     * string for the Settings UI (ok / error). Never throws.
     */
    suspend fun applyConfig(config: FcmClientConfig, enabled: Boolean): String

    /**
     * Fetch the current FCM registration token after a successful init.
     * Empty string when unavailable.
     */
    suspend fun currentToken(): String

    /** Tear down the named Firebase app instance (best-effort). */
    suspend fun clear()
}
