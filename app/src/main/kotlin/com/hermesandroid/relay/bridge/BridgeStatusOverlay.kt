package com.hermesandroid.relay.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hermesandroid.relay.ui.components.BridgeStatusOverlayChip
import com.hermesandroid.relay.ui.components.DestructiveVerbConfirmDialog
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 3 — safety-rails `bridge-safety-rails`
 *
 * WindowManager-backed overlay host. Serves two jobs in one place so we
 * only ever attach a single `SYSTEM_ALERT_WINDOW` View per process:
 *
 *  1. A small floating status chip ("Hermes is active") shown when the
 *     user has opted in via [BridgeSafetySettings.statusOverlayEnabled].
 *  2. A full-width centered destructive-verb confirmation modal that
 *     [BridgeSafetyManager] fires from [BridgeSafetyManager.awaitConfirmation].
 *
 * The two overlays are independent [ComposeView] attachments — they
 * don't share layout params. That keeps the chip a tiny permanent hole
 * in the gesture layer while the modal is a modal rectangle that
 * intercepts touches only when present.
 *
 * # Lifecycle plumbing for ComposeView
 *
 * `ComposeView` attached via `WindowManager` does not automatically get
 * a `ViewTreeLifecycleOwner` (normally the containing Activity provides
 * one). Compose requires one to run its recomposer, so we attach a
 * minimal [OverlayLifecycleOwner] that's always in the RESUMED state
 * while the view is attached. Same deal for SavedStateRegistryOwner
 * (required by SavedStateHandle inside composables) and ViewModelStoreOwner.
 */
class BridgeStatusOverlay(context: Context) : ConfirmationOverlayHost {

    companion object {
        private const val TAG = "BridgeStatusOverlay"

        @Volatile
        private var INSTANCE: BridgeStatusOverlay? = null

        fun install(context: Context): BridgeStatusOverlay {
            val existing = INSTANCE
            if (existing != null) return existing
            val created = BridgeStatusOverlay(context.applicationContext)
            INSTANCE = created
            ConfirmationOverlayHost.instance = created
            return created
        }

        fun peek(): BridgeStatusOverlay? = INSTANCE
    }

    private val appContext: Context = context.applicationContext
    private val wm: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var chipView: View? = null
    private val activeConfirmations = ConcurrentHashMap<Long, View>()

    // ── Status chip ──────────────────────────────────────────────────────

    /**
     * Show or hide the floating status chip. No-op if the overlay
     * permission hasn't been granted — [BridgeSafetySettingsScreen] is
     * responsible for walking the user through the grant flow.
     */
    @SuppressLint("InflateParams")
    fun setChipVisible(visible: Boolean) {
        if (!visible) {
            chipView?.let {
                runCatching { wm.removeView(it) }
                    .onFailure { Log.w(TAG, "removeView(chip) failed", it) }
            }
            chipView = null
            return
        }
        if (chipView != null) return // already showing
        if (!Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "setChipVisible: SYSTEM_ALERT_WINDOW not granted — skipping chip")
            return
        }

        val compose = ComposeView(appContext).apply {
            setContent {
                MaterialTheme { BridgeStatusOverlayChip() }
            }
        }
        attachLifecycle(compose)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 96
        }

        runCatching { wm.addView(compose, params) }
            .onFailure {
                Log.w(TAG, "addView(chip) failed", it)
                return
            }
        chipView = compose
    }

    // ── Confirmation modal ───────────────────────────────────────────────

    override fun showConfirmation(
        request: PendingConfirmation,
        onResult: (allowed: Boolean) -> Unit,
    ) {
        if (activeConfirmations.containsKey(request.id)) return
        if (!Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "showConfirmation: SYSTEM_ALERT_WINDOW not granted — denying")
            onResult(false)
            return
        }

        val compose = ComposeView(appContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme {
                    DestructiveVerbConfirmDialog(
                        method = request.method,
                        verb = request.verb,
                        fullText = request.text,
                        onAllow = {
                            onResult(true)
                            dismissConfirmation(request.id)
                        },
                        onDeny = {
                            onResult(false)
                            dismissConfirmation(request.id)
                        },
                    )
                }
            }
        }
        attachLifecycle(compose)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            dimAmount = 0.6f
            gravity = Gravity.CENTER
        }

        val added = runCatching { wm.addView(compose, params) }.isSuccess
        if (!added) {
            Log.w(TAG, "addView(confirm) failed — denying")
            onResult(false)
            return
        }
        activeConfirmations[request.id] = compose
    }

    override fun dismissConfirmation(requestId: Long) {
        val view = activeConfirmations.remove(requestId) ?: return
        runCatching { wm.removeView(view) }
            .onFailure { Log.w(TAG, "removeView(confirm $requestId) failed", it) }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun attachLifecycle(view: View) {
        val owner = OverlayLifecycleOwner().also { it.start() }
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        // REQUIRED even when the overlay content uses only plain `remember`:
        // `AndroidComposeView.onAttachedToWindow` hard-fails with
        // `IllegalStateException: Composed into the View which doesn't
        // propagateViewTreeSavedStateRegistryOwner` if this tree owner is
        // missing, regardless of whether the composable actually reads
        // saved state. Confirmed empirically on Samsung S24 / Android 14
        // / Compose BOM 2024.12 when enabling the persistent status chip
        // from the Bridge Safety screen (Phase 3 safety-rails).
        view.setViewTreeSavedStateRegistryOwner(owner)
    }
}

/**
 * Minimal always-RESUMED lifecycle owner for ComposeViews we attach to
 * a `WindowManager`. Compose's `Recomposer` refuses to run inside a view
 * that has no `ViewTreeLifecycleOwner`; `viewModel()` calls inside the
 * overlay need a `ViewModelStore`; and — as of recent Compose versions —
 * `AndroidComposeView.onAttachedToWindow` hard-requires a
 * `ViewTreeSavedStateRegistryOwner` even for composables that never read
 * saved state. So this class implements all three.
 *
 * Earlier versions of this file deliberately skipped
 * `SavedStateRegistryOwner` on the assumption that "no `rememberSaveable`
 * → no saved state needed". That assumption was wrong: the onAttach gate
 * in `AndroidComposeView` doesn't inspect the composable body, it just
 * checks for the tree owner and throws. The crash was
 * [IllegalStateException] at `AndroidComposeView.onAttachedToWindow:2234`
 * on every overlay attach.
 *
 * The required init sequence is: move the lifecycle to CREATED first,
 * call `SavedStateRegistryController.performRestore(null)` (empty
 * bundle = fresh state), THEN advance to RESUMED. Doing it in any other
 * order trips a second assertion in `SavedStateRegistryController`.
 */
private class OverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun start() {
        // Order matters — see KDoc above.
        registry.currentState = Lifecycle.State.CREATED
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
