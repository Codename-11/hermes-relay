package com.hermesandroid.relay

import android.animation.ObjectAnimator
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
        setContent {
            RelayApp()
        }
    }
}
