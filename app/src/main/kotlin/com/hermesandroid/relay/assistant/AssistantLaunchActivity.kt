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

/** Strict assistant trampoline. Protected WEB_SEARCH and public Bluetooth actions
 *  have separate manifest activities so the public entry cannot request screen content.
 */
open class AssistantLaunchActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val launchTimeout = Runnable { finish() }

    protected open fun acceptsLaunchAction(action: String?): Boolean =
        isAssistantWebSearchAction(action)

    protected open val captureScreenContext: Boolean = true

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
        if (acceptsLaunchAction(launchIntent?.action) &&
            AssistantRole.status(this) == AssistantRoleStatus.Selected
        ) {
            activeActivity = WeakReference(this)
            handler.removeCallbacks(launchTimeout)
            handler.postDelayed(launchTimeout, LAUNCH_TIMEOUT_MS)
            HermesVoiceInteractionService.requestAssistantSession(
                manualMic = false,
                captureScreenContext = captureScreenContext,
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

/** The public headset entry cannot accept WEB_SEARCH or request screen context. */
class BluetoothVoiceCommandActivity : AssistantLaunchActivity() {
    override fun acceptsLaunchAction(action: String?): Boolean =
        isAssistantVoiceCommandAction(action)

    override val captureScreenContext: Boolean = false
}

internal fun isAssistantVoiceCommandAction(action: String?): Boolean =
    action == Intent.ACTION_VOICE_COMMAND

internal fun isAssistantWebSearchAction(action: String?): Boolean =
    action == RecognizerIntent.ACTION_WEB_SEARCH
