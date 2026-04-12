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
import com.hermesandroid.relay.ui.components.BridgeStatusOverlayChip
import com.hermesandroid.relay.ui.components.DestructiveVerbConfirmDialog
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 3 — ζ `bridge-safety-rails`
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
    }
}

/**
 * Minimal always-RESUMED lifecycle owner for ComposeViews we attach to
 * a WindowManager. Compose's `Recomposer` refuses to run inside a view
 * that has no `ViewTreeLifecycleOwner`; we also need a ViewModelStore
 * so `viewModel()` calls inside the overlay can resolve.
 *
 * We deliberately skip `SavedStateRegistryOwner` — [DestructiveVerbConfirmDialog]
 * uses plain `remember`, not `rememberSaveable`, and the chip is stateless.
 * Adding SavedState here would tie the class to the androidx.savedstate
 * artifact version which isn't currently pinned in the version catalog.
 */
private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
