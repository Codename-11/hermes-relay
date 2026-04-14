package com.hermesandroid.relay

import android.app.Application
import com.hermesandroid.relay.data.AppAnalytics
import com.hermesandroid.relay.power.WakeLockManager

class HermesRelayApp : Application() {

    override fun onCreate() {
        super.onCreate()
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
