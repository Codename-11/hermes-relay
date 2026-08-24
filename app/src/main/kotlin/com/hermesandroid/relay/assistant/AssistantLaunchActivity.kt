package com.hermesandroid.relay.assistant

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.WindowManager
import java.lang.ref.WeakReference

/** Strict trampoline for firmware assistant buttons that emit ACTION_WEB_SEARCH. */
class AssistantLaunchActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val launchTimeout = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(launchIntent: Intent?) {
        if (isAssistantWebSearchAction(launchIntent?.action) &&
            AssistantRole.status(this) == AssistantRoleStatus.Selected
        ) {
            activeActivity = WeakReference(this)
            handler.removeCallbacks(launchTimeout)
            handler.postDelayed(launchTimeout, LAUNCH_TIMEOUT_MS)
            HermesVoiceInteractionService.requestAssistantSession(
                manualMic = false,
                captureScreenContext = true,
            )
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(launchTimeout)
        if (activeActivity?.get() === this) activeActivity = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var activeActivity: WeakReference<AssistantLaunchActivity>? = null

        private const val LAUNCH_TIMEOUT_MS = 10_000L

        fun markSessionAccepted() {
            val activity = activeActivity?.get() ?: return
            activity.runOnUiThread { activity.handler.removeCallbacks(activity.launchTimeout) }
        }

        fun finishActive() {
            val activity = activeActivity?.get() ?: return
            activity.runOnUiThread {
                activity.handler.removeCallbacks(activity.launchTimeout)
                activity.finish()
            }
        }
    }
}

internal fun isAssistantWebSearchAction(action: String?): Boolean =
    action == RecognizerIntent.ACTION_WEB_SEARCH
