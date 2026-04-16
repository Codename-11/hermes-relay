package com.hermesandroid.relay

import android.app.Application
import android.os.Build
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import com.hermesandroid.relay.data.AppAnalytics
import com.hermesandroid.relay.power.WakeLockManager

class HermesRelayApp : Application() {

    @OptIn(ExperimentalComposeUiApi::class)
    override fun attachBaseContext(base: android.content.Context?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ComposeUiFlags.isAdaptiveRefreshRateEnabled = false
        }
        super.attachBaseContext(base)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Compose's adaptive refresh-rate hint path on API 35 can emit
            // `setRequestedFrameRate frameRate=NaN` from inside AndroidComposeView
            // on every draw pass. Disable ARR globally until the upstream fix lands.
            ComposeUiFlags.isAdaptiveRefreshRateEnabled = false
        }
        instance = this
        AppAnalytics.initialize(this)
        // A8 — wire the bridge-gesture wake-lock wrapper so
        // ActionExecutor.tap/tapText/typeText/swipe/scroll can hold
        // a partial wake lock while dispatching.
        WakeLockManager.initialize(this)
    }

    companion object {
        lateinit var instance: HermesRelayApp
            private set
    }
}
