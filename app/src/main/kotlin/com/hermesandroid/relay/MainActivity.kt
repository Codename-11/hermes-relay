package com.hermesandroid.relay

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hermesandroid.relay.ui.RelayApp
import com.hermesandroid.relay.util.NavRouteRequest
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

class MainActivity : ComponentActivity() {

    private val connectionViewModel: ConnectionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        // Hold splash until DataStore is loaded and onboarding status is known
        splashScreen.setKeepOnScreenCondition {
            !connectionViewModel.isReady.value
        }

        // Smooth exit: fade out the splash screen
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            val fadeOut = ObjectAnimator.ofFloat(
                splashScreenView.view,
                View.ALPHA,
                1f, 0f
            ).apply {
                duration = 400
                interpolator = DecelerateInterpolator()
                doOnEnd { splashScreenView.remove() }
            }
            fadeOut.start()
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // === PHASE3-safety-rails-followup: deep-link nav route from external intents ===
        // Foreground services, broadcast receivers, and shortcut intents can
        // attach EXTRA_NAV_ROUTE to request that RelayApp navigate to a
        // specific Compose route on launch. The actual navigation happens
        // in RelayApp's NavRouteRequest collector — we just pump the request
        // into the SharedFlow here.
        consumeNavRouteIntent(intent)
        // === END PHASE3-safety-rails-followup ===
        setContent {
            RelayApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // === PHASE3-safety-rails-followup: deep-link nav route on re-launch ===
        // Same as onCreate but for the singleTask / FLAG_ACTIVITY_CLEAR_TOP
        // path: when the app is already running and the foreground service's
        // PendingIntent re-launches us, the new intent comes through here
        // instead of onCreate. RelayApp's collector handles both cases.
        setIntent(intent)
        consumeNavRouteIntent(intent)
        // === END PHASE3-safety-rails-followup ===
    }

    private fun consumeNavRouteIntent(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_NAV_ROUTE) ?: return
        if (route.isBlank()) return
        NavRouteRequest.tryRequest(route)
    }

    companion object {
        /**
         * Intent extra carrying a Compose nav route. Set by foreground services
         * (and any other external launcher) on the `Intent(this, MainActivity::class.java)`
         * they fire to request RelayApp navigate to a specific destination on
         * launch / re-launch.
         */
        const val EXTRA_NAV_ROUTE = "com.hermesandroid.relay.EXTRA_NAV_ROUTE"
    }
}
