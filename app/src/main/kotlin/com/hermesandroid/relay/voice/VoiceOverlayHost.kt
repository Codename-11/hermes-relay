package com.hermesandroid.relay.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.hermesandroid.relay.data.BargeInPreferences
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.util.ComposeArrWorkaround
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * WindowManager-backed host for the experimental voice overlay.
 *
 * This deliberately mirrors BridgeStatusOverlay's permission and lifecycle
 * pattern, but remains voice-owned so the Bridge safety chip and confirmation
 * modal are not coupled to realtime voice mode.
 */
class VoiceOverlayHost(context: Context) {
    companion object {
        private const val TAG = "VoiceOverlayHost"

        @Volatile
        private var INSTANCE: VoiceOverlayHost? = null

        fun install(context: Context): VoiceOverlayHost {
            val existing = INSTANCE
            if (existing != null) return existing
            val created = VoiceOverlayHost(context.applicationContext)
            INSTANCE = created
            return created
        }

        fun peek(): VoiceOverlayHost? = INSTANCE
    }

    private val appContext: Context = context.applicationContext
    private val wm: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val sessionState = MutableStateFlow<VoiceOverlaySession?>(null)
    private var overlayView: View? = null
    private var overlayOwner: VoiceOverlayLifecycleOwner? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(appContext)

    @SuppressLint("InflateParams")
    fun show(session: VoiceOverlaySession): Boolean {
        sessionState.value = session
        if (!hasOverlayPermission()) {
            Log.w(TAG, "show: SYSTEM_ALERT_WINDOW not granted")
            return false
        }
        if (overlayView != null) return true

        val compose = ComposeView(appContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                HermesRelayTheme {
                    val activeSession by sessionState.collectAsState()
                    activeSession?.let {
                        VoiceFloatingOverlayPill(
                            session = it,
                            onDragBy = { dx, dy -> moveBy(dx, dy) },
                        )
                    }
                }
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
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 96
        }

        val added = runCatching { wm.addView(compose, params) }
            .onFailure { Log.w(TAG, "addView(voice overlay) failed", it) }
            .isSuccess
        if (!added) {
            sessionState.value = null
            overlayOwner?.stop()
            overlayOwner = null
            return false
        }

        compose.post { ComposeArrWorkaround.disableForViewTree(compose) }
        overlayView = compose
        overlayParams = params
        return true
    }

    fun hide() {
        val view = overlayView
        overlayView = null
        overlayParams = null
        sessionState.value = null
        if (view != null) {
            runCatching { wm.removeView(view) }
                .onFailure { Log.w(TAG, "removeView(voice overlay) failed", it) }
        }
        overlayOwner?.stop()
        overlayOwner = null
    }

    private fun moveBy(dx: Float, dy: Float) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        params.x = (params.x + dx.roundToInt()).coerceAtLeast(0)
        params.y = (params.y + dy.roundToInt()).coerceAtLeast(0)
        runCatching { wm.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "updateViewLayout(voice overlay) failed", it) }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun attachLifecycle(view: View) {
        val owner = VoiceOverlayLifecycleOwner().also { it.start() }
        overlayOwner = owner
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
    }
}

data class VoiceOverlaySession(
    val uiState: StateFlow<VoiceUiState>,
    val bargeInPreferences: Flow<BargeInPreferences>,
    val provider: String?,
    val voice: String?,
    val outputEnabled: Boolean?,
    val fallbackEnabled: Boolean?,
    val onStartListening: () -> Unit,
    val onStopListening: () -> Unit,
    val onInterrupt: () -> Unit,
    val onReturnToHermes: () -> Unit,
    val onExit: () -> Unit,
)

@Composable
private fun VoiceFloatingOverlayPill(
    session: VoiceOverlaySession,
    onDragBy: (Float, Float) -> Unit,
) {
    val uiState by session.uiState.collectAsState()
    val bargeIn by session.bargeInPreferences.collectAsState(initial = BargeInPreferences())
    val providerText = voiceProviderLabel(session.provider, session.voice, session.outputEnabled)
    val stateText = when (uiState.state) {
        VoiceState.Idle -> "Ready"
        VoiceState.Listening -> "Listening"
        VoiceState.Transcribing -> "Transcribing"
        VoiceState.Thinking -> "Thinking"
        VoiceState.Speaking -> "Speaking"
        VoiceState.Error -> "Error"
    }

    Surface(
        modifier = Modifier
            .width(332.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragBy(dragAmount.x, dragAmount.y)
                }
            },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Voice Overlay",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        StatusChip("Experimental")
                    }
                    Text(
                        text = "$stateText · $providerText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MicControlButton(
                    uiState = uiState,
                    onStartListening = session.onStartListening,
                    onStopListening = session.onStopListening,
                    onInterrupt = session.onInterrupt,
                )
            }

            if (uiState.state == VoiceState.Transcribing || uiState.state == VoiceState.Thinking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusChip(
                    text = if (bargeIn.enabled) {
                        "barge-in ${bargeIn.sensitivity.name.lowercase()}"
                    } else {
                        "barge-in off"
                    },
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    text = when (session.fallbackEnabled) {
                        true -> "fallback on"
                        false -> "fallback off"
                        null -> "fallback ..."
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = session.onInterrupt,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Interrupt")
                }
                TextButton(
                    onClick = session.onReturnToHermes,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Hermes")
                }
                TextButton(
                    onClick = session.onExit,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Exit")
                }
            }
        }
    }
}

@Composable
private fun MicControlButton(
    uiState: VoiceUiState,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onInterrupt: () -> Unit,
) {
    val isHot = uiState.state == VoiceState.Listening || uiState.state == VoiceState.Speaking
    Surface(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable {
                when (uiState.state) {
                    VoiceState.Listening -> onStopListening()
                    VoiceState.Speaking -> onInterrupt()
                    VoiceState.Transcribing, VoiceState.Thinking -> Unit
                    VoiceState.Idle, VoiceState.Error -> onStartListening()
                }
            },
        shape = CircleShape,
        color = if (isHot) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isHot) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = "Voice overlay mic",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(24.dp),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun voiceProviderLabel(provider: String?, voice: String?, outputEnabled: Boolean?): String {
    if (outputEnabled == false) return "output off"
    val providerPart = provider?.takeIf { it.isNotBlank() } ?: "provider ..."
    val voicePart = voice?.takeIf { it.isNotBlank() }
    return if (voicePart == null) providerPart else "$providerPart / $voicePart"
}

fun openHermesFromOverlay(context: Context) {
    val appContext = context.applicationContext
    val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        ?: Intent().setPackage(appContext.packageName)
    launchIntent
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    runCatching { appContext.startActivity(launchIntent) }
        .onFailure { Log.w("VoiceOverlayHost", "return to Hermes failed", it) }
}

private class VoiceOverlayLifecycleOwner :
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
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
