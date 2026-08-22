package com.hermesandroid.relay

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.animation.doOnEnd
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hermesandroid.relay.accessibility.ScreenCaptureRequester
import com.hermesandroid.relay.bridge.BridgeForegroundService
import com.hermesandroid.relay.bridge.UnattendedAccessManager
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.notifications.TurnCompleteNotifier
import com.hermesandroid.relay.notifications.InteractionRequestNotifier
import com.hermesandroid.relay.ui.RelayApp
import com.hermesandroid.relay.util.NavRouteRequest
import com.hermesandroid.relay.util.SharedContentRequest
import com.hermesandroid.relay.util.extractSharedContent
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class MainActivity : AppCompatActivity() {

    private val connectionViewModel: ConnectionViewModel
        get() = (applicationContext as HermesRelayApp).runtime.connectionViewModel

    // === PHASE3-bridge-ui-followup: MediaProjection consent flow ===
    // ActivityResultLauncher for the system screen-capture consent dialog.
    // Must be registered BEFORE the activity reaches STARTED state, hence
    // declared as a property (registerForActivityResult is safe to call
    // from a property initializer on ComponentActivity).
    //
    // We do NOT call MediaProjectionHolder directly from here. On Android
    // 14+, getMediaProjection() must run from inside a foreground service
    // that has already called startForeground(type=mediaProjection), and
    // that startForeground call must happen AFT consent. So we hand the
    // result off to BridgeForegroundService, which:
    //   1. Upgrades its FGS type to SPECIAL_USE | MEDIA_PROJECTION
    //   2. Calls MediaProjectionHolder.acceptGrantInsideForegroundService
    //   3. Stores the projection in the holder's StateFlow
    // BridgeViewModel observes that flow and refreshes the UI immediately.
    //
    // ScreenCaptureRequester is a process-singleton rendezvous so the
    // BridgeViewModel (which has no Activity reference) can ask us to
    // launch the dialog without leaking this Activity through the VM.
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (!BuildFlavor.isSideload) {
            Log.w(TAG, "Ignoring MediaProjection result on Google Play Bridge Core build")
            return@registerForActivityResult
        }
        if (result.resultCode == RESULT_OK && data != null) {
            Log.i(TAG, "MediaProjection consent granted — handing off to FGS")
            BridgeForegroundService.grantMediaProjection(this, result.resultCode, data)
        } else {
            Log.i(TAG, "MediaProjection consent rejected (resultCode=${result.resultCode})")
        }
    }
    // === END PHASE3-bridge-ui-followup ===

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        com.hermesandroid.relay.assistant.AssistantSessionProtocol
            .prepareAssistActivation(intent)

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
        configureAssistantWindow(intent)
        lifecycleScope.launch {
            com.hermesandroid.relay.assistant.AssistantAppSessionState.active.collect { active ->
                if (!active) clearAssistantWindow()
            }
        }
        enableEdgeToEdge()

        // === PHASE3-bridge-ui-followup: install MediaProjection requester ===
        // Hand the launcher to the process-singleton rendezvous so
        // BridgeViewModel.requestScreenCapture() can fire the consent
        // dialog without holding an Activity reference.
        if (BuildFlavor.isSideload) {
            ScreenCaptureRequester.install {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
                try {
                    mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
                } catch (t: Throwable) {
                    Log.w(TAG, "failed to launch MediaProjection consent: ${t.message}")
                }
            }
        }
        // === END PHASE3-bridge-ui-followup ===

        // === PHASE3-safety-rails-followup: deep-link nav route from external intents ===
        // Foreground services, broadcast receivers, and shortcut intents can
        // attach EXTRA_NAV_ROUTE to request that RelayApp navigate to a
        // specific Compose route on launch. The actual navigation happens
        // in RelayApp's NavRouteRequest collector — we just pump the request
        // into the SharedFlow here.
        consumeNavRouteIntent(intent)
        consumeSharedContentIntent(intent)
        val consumedAssistantActivation =
            com.hermesandroid.relay.assistant.AssistantSessionProtocol.consumeActivation(
                this,
                intent,
            )
        if (!consumedAssistantActivation) {
            com.hermesandroid.relay.assistant.AssistantSessionProtocol.restoreActivation(this)
        }
        // === END PHASE3-safety-rails-followup ===
        setContent {
            RelayApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        com.hermesandroid.relay.assistant.AssistantSessionProtocol
            .prepareAssistActivation(intent)
        configureAssistantWindow(intent)
        // === PHASE3-safety-rails-followup: deep-link nav route on re-launch ===
        // Same as onCreate but for the singleTask / FLAG_ACTIVITY_CLEAR_TOP
        // path: when the app is already running and the foreground service's
        // PendingIntent re-launches us, the new intent comes through here
        // instead of onCreate. RelayApp's collector handles both cases.
        setIntent(intent)
        consumeNavRouteIntent(intent)
        consumeSharedContentIntent(intent)
        com.hermesandroid.relay.assistant.AssistantSessionProtocol.consumeActivation(this, intent)
        // === END PHASE3-safety-rails-followup ===
    }

    private fun consumeNavRouteIntent(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_NAV_ROUTE) ?: return
        if (route.isBlank()) return
        NavRouteRequest.tryRequest(route)
    }

    private fun consumeSharedContentIntent(intent: Intent?) {
        intent ?: return
        val streamUris = buildList {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    android.net.Uri::class.java,
                )?.let(::addAll)
            } else {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    ?.let(::add)
            }
        }
        val clipUris = buildList {
            val clipData = intent.clipData ?: return@buildList
            repeat(clipData.itemCount) { index -> clipData.getItemAt(index).uri?.let(::add) }
        }
        val clipTexts = buildList {
            val clip = intent.clipData ?: return@buildList
            repeat(clip.itemCount) { index -> clip.getItemAt(index).text?.let(::add) }
        }
        val sharedTexts = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT).orEmpty()
        } else {
            listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
        }
        val payload = extractSharedContent(
            action = intent.action,
            texts = sharedTexts,
            subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT),
            streamUriStrings = streamUris.map(android.net.Uri::toString),
            clipTexts = clipTexts,
            clipUriStrings = clipUris.map(android.net.Uri::toString),
        )
        SharedContentRequest.tryRequest(payload)
    }

    private fun configureAssistantWindow(intent: Intent?) {
        if (
            intent?.getBooleanExtra(
                com.hermesandroid.relay.assistant.AssistantSessionProtocol.EXTRA_ASSISTANT_SESSION,
                false,
            ) == true ||
            com.hermesandroid.relay.assistant.AssistantSessionPersistence.isActive(this)
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        }
    }

    private fun clearAssistantWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onResume() {
        super.onResume()
        SharedContentRequest.retryFailed()
        // Returning to the app clears the one-slot "Hermes finished
        // responding" notification — the chat surface is the answer.
        TurnCompleteNotifier.cancel(this)
        // Action-required notifications are durable across process death.
        // Once the authenticated chat surface is visible it owns presentation;
        // unresolved asks are re-posted if the app returns to the background.
        InteractionRequestNotifier.cancelAll(this)
        // v0.4.1 — register this activity as the host for
        // KeyguardManager.requestDismissKeyguard. Cleared in onPause so
        // we don't leak the Activity past its lifecycle. The unattended-
        // access manager only attempts dismiss when an activity is
        // registered AND the user has opted in.
        if (BuildFlavor.isSideload) {
            UnattendedAccessManager.setHostActivity(this)
        }
        // Re-probe the credential-lock state on resume so the Bridge
        // tab badge updates immediately if the user just changed their
        // lock screen in system Settings between app sessions.
        if (BuildFlavor.isSideload) {
            UnattendedAccessManager.refreshKeyguardState()
        }
    }

    override fun onPause() {
        if (BuildFlavor.isSideload) {
            UnattendedAccessManager.setHostActivity(null)
        }
        super.onPause()
    }

    override fun onDestroy() {
        // === PHASE3-bridge-ui-followup: clear MediaProjection requester ===
        // Drop the launcher closure so we don't hold a stale Activity ref
        // after destroy. ScreenCaptureRequester.request() will return false
        // until the next MainActivity instance reinstalls itself.
        if (BuildFlavor.isSideload) {
            ScreenCaptureRequester.uninstall()
        }
        // === END PHASE3-bridge-ui-followup ===
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"

        /**
         * Intent extra carrying a Compose nav route. Set by foreground services
         * (and any other external launcher) on the `Intent(this, MainActivity::class.java)`
         * they fire to request RelayApp navigate to a specific destination on
         * launch / re-launch.
         */
        const val EXTRA_NAV_ROUTE = "com.hermesandroid.relay.EXTRA_NAV_ROUTE"
    }
}
