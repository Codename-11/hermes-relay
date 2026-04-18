package com.hermesandroid.relay.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide foreground/background signal.
 *
 * v0.4.1 polish pass: the cross-app WindowManager chip rendered by
 * [com.hermesandroid.relay.bridge.BridgeStatusOverlay] is specifically a
 * *system-wide* indicator — "you're not in the Hermes-Relay app, but the
 * agent can still drive this device". When the user IS in the Hermes-Relay
 * app, the in-app
 * [com.hermesandroid.relay.ui.components.UnattendedGlobalBanner] already
 * occupies that slot on every tab. Rendering both at once is redundant and
 * visually noisy — the user explicitly called it out.
 *
 * This object uses [ProcessLifecycleOwner] (from
 * `androidx.lifecycle:lifecycle-process`) to observe whether any Activity
 * in the process is at least Started. The resulting [isForeground]
 * [StateFlow] drives the chip gating in [com.hermesandroid.relay.viewmodel.BridgeViewModel]:
 * chip shows only when the app is backgrounded, banner shows only when the
 * app is foregrounded.
 *
 * ## Usage
 *
 * Call [initialize] exactly once, from `Application.onCreate()`. Calls
 * after the first are a no-op (guarded by [initialized]). The observer
 * runs on the main thread — [ProcessLifecycleOwner]'s contract — so
 * [MutableStateFlow.value] writes don't need explicit synchronization.
 *
 * ## Edge cases worth knowing
 *
 * - **Lock screen / screen off:** `onStop` fires, so [isForeground] flips
 *   to false. That's what we want: if the phone is locked, the chip
 *   should surface the unattended state for anyone who wakes it.
 * - **Split-screen / freeform:** when our Activity is visible but not the
 *   top resumed one, it stays Started → [isForeground] stays true. The
 *   banner will show in our window; the chip is suppressed. This is the
 *   correct behaviour — the user is still looking at Hermes-Relay.
 * - **Brief transitions (e.g. opening a permission-settings intent):**
 *   [ProcessLifecycleOwner] debounces with a ~700ms delay before firing
 *   `onStop`, so quick round-trips don't cause chip flicker.
 */
object AppForegroundTracker {

    private val _isForeground = MutableStateFlow(false)

    /**
     * True while any Activity in this process is at least Started
     * (visible to the user). False when every Activity has been Stopped
     * (app backgrounded, screen locked, or user navigated away).
     */
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    @Volatile
    private var initialized = false

    /**
     * Register a [ProcessLifecycleOwner] observer. Idempotent — safe to
     * call multiple times; calls after the first are no-ops. Must be
     * called from `Application.onCreate()` so the observer is installed
     * before any Activity runs.
     */
    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    _isForeground.value = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    _isForeground.value = false
                }
            })
        }
    }
}
