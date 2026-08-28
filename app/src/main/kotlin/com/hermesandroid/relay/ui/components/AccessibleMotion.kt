package com.hermesandroid.relay.ui.components

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Shared OS motion/accessibility posture for animated UI affordances. */
internal data class AccessibleMotionState(
    /** OS animator scale is non-zero (i.e. system animations are on). */
    val osAnimations: Boolean,
    /** TalkBack-style touch exploration is active. */
    val touchExploration: Boolean,
)

@Composable
internal fun rememberAccessibleMotionState(): AccessibleMotionState {
    val context = LocalContext.current
    val osAnimations = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }.getOrDefault(true)
    }
    val accessibilityManager = remember {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var touchExploration by remember {
        mutableStateOf(accessibilityManager?.isTouchExplorationEnabled == true)
    }
    DisposableEffect(accessibilityManager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            touchExploration = enabled
        }
        accessibilityManager?.addTouchExplorationStateChangeListener(listener)
        onDispose {
            accessibilityManager?.removeTouchExplorationStateChangeListener(listener)
        }
    }
    return AccessibleMotionState(
        osAnimations = osAnimations,
        touchExploration = touchExploration,
    )
}
