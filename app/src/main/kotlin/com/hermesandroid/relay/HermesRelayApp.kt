package com.hermesandroid.relay

import android.app.Application
import com.hermesandroid.relay.data.AppAnalytics

class HermesRelayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppAnalytics.initialize(this)
    }

    companion object {
        lateinit var instance: HermesRelayApp
            private set
    }
}
