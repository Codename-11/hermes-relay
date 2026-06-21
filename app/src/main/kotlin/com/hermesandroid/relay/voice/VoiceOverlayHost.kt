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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
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
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TWO_PI = 6.2831855f
private const val HALF_PI = 1.5707964f

// Matches the in-app VoiceWaveform palette so minimized overlay mode reads as
// the same voice surface, just wrapped around the mic control.
private val OverlayListeningPrimary = Color(0xFF597EF2)
private val OverlayListeningSecondary = Color(0xFFA573F2)
private val OverlaySpeakingPrimary = Color(0xFF40EB8C)
private val OverlaySpeakingSecondary = Color(0xFF4DD9E0)

/**
 * WindowManager-backed host for the voice overlay.
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
    val engineMode: String? = null,
    val provider: String?,
    val model: String?,
    val voice: String?,
    val profileName: String?,
    val configScope: String?,
    val outputEnabled: Boolean?,
    val fallbackEnabled: Boolean?,
    val onStartListening: () -> Unit,
    val onStopListening: () -> Unit,
    val onInterrupt: () -> Unit,
    val onPauseAutoMode: () -> Unit,
    val onReturnToHermes: () -> Unit,
    val onDismissOverlay: () -> Unit,
    val onExit: () -> Unit,
)

@Composable
private fun VoiceFloatingOverlayPill(
    session: VoiceOverlaySession,
    onDragBy: (Float, Float) -> Unit,
) {
    val uiState by session.uiState.collectAsState()
    var minimized by remember { mutableStateOf(false) }
    val engineText = voiceEngineLabel(session.engineMode)
    val providerText = voiceProviderLabel(
        session.provider,
        session.model,
        session.voice,
        session.outputEnabled,
    )
    val profileText = session.profileName?.takeIf { it.isNotBlank() } ?: "default profile"
    val stateText = when (uiState.state) {
        VoiceState.Idle -> "Ready"
        VoiceState.Listening -> "Listening"
        VoiceState.Transcribing -> "Transcribing"
        VoiceState.Thinking -> "Thinking"
        VoiceState.Speaking -> "Speaking"
        VoiceState.Error -> "Error"
    }

    if (minimized) {
        VoiceFloatingOverlayBubble(
            uiState = uiState,
            stateText = stateText,
            onExpand = { minimized = false },
            onStartListening = session.onStartListening,
            onStopListening = session.onStopListening,
            onInterrupt = session.onInterrupt,
            onPauseAutoMode = session.onPauseAutoMode,
            onDragBy = onDragBy,
        )
        return
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
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
                    }
                    Text(
                        text = "$stateText · $engineText · $profileText · $providerText",
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
                    onPauseAutoMode = session.onPauseAutoMode,
                )
                IconButton(
                    onClick = session.onExit,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Exit voice mode",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (uiState.state == VoiceState.Transcribing || uiState.state == VoiceState.Thinking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(
                    onClick = { minimized = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Minimize")
                }
                TextButton(
                    onClick = session.onReturnToHermes,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Hermes")
                }
                TextButton(
                    onClick = session.onDismissOverlay,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Hide")
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceFloatingOverlayBubble(
    uiState: VoiceUiState,
    stateText: String,
    onExpand: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onInterrupt: () -> Unit,
    onPauseAutoMode: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
) {
    val isHot = uiState.state == VoiceState.Listening || uiState.state == VoiceState.Speaking
    val stateLabel = overlayBubbleStateLabel(uiState.state)
    val tapAction = when (uiState.state) {
        VoiceState.Idle, VoiceState.Error -> "start listening"
        VoiceState.Listening -> "stop listening"
        VoiceState.Speaking ->
            if (uiState.interactionMode == InteractionMode.Continuous) "pause auto mode" else "interrupt"
        VoiceState.Transcribing, VoiceState.Thinking ->
            if (uiState.interactionMode == InteractionMode.Continuous) "pause auto mode" else "interrupt"
    }
    val containerColor = when (uiState.state) {
        VoiceState.Listening, VoiceState.Speaking -> Color(0xFFE53935)
        VoiceState.Transcribing, VoiceState.Thinking -> Color(0xFFE53935)
        VoiceState.Error -> MaterialTheme.colorScheme.error
        VoiceState.Idle -> MaterialTheme.colorScheme.primary
    }
    val icon = when (uiState.state) {
        VoiceState.Listening, VoiceState.Speaking -> Icons.Filled.Stop
        VoiceState.Transcribing, VoiceState.Thinking -> Icons.Filled.Stop
        VoiceState.Idle, VoiceState.Error -> Icons.Filled.Mic
    }

    Box(
        modifier = Modifier
            .size(94.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragBy(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        OverlayCircularWaveformRing(
            amplitude = uiState.amplitude,
            state = uiState.state,
            modifier = Modifier.fillMaxSize(),
        )

        Surface(
            modifier = Modifier
                .size(70.dp)
                .clip(CircleShape)
            .combinedClickable(
                onClick = {
                    dispatchMicAction(
                        uiState = uiState,
                        onStartListening = onStartListening,
                        onStopListening = onStopListening,
                        onInterrupt = onInterrupt,
                        onPauseAutoMode = onPauseAutoMode,
                    )
                },
                onLongClick = onExpand,
                onDoubleClick = onExpand,
            ),
            shape = CircleShape,
            color = containerColor.copy(alpha = if (isHot) 0.96f else 0.92f),
            shadowElevation = 8.dp,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = "Voice overlay $stateText. Tap to $tapAction, double tap to expand.",
                    tint = Color.White,
                    modifier = Modifier.size(23.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OverlayCircularWaveformRing(
    amplitude: Float,
    state: VoiceState,
    modifier: Modifier = Modifier,
) {
    val displayAmplitude = amplitude.coerceIn(0f, 1f)
    val phase = rememberOverlayWaveformPhase(displayAmplitude)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    val targetPrimary = when (state) {
        VoiceState.Idle -> dim.copy(alpha = 0.38f)
        VoiceState.Listening -> OverlayListeningPrimary
        VoiceState.Transcribing -> OverlayListeningPrimary.copy(alpha = 0.72f)
        VoiceState.Thinking -> dim.copy(alpha = 0.62f)
        VoiceState.Speaking -> OverlaySpeakingPrimary
        VoiceState.Error -> errorColor.copy(alpha = 0.9f)
    }
    val targetSecondary = when (state) {
        VoiceState.Idle -> dim.copy(alpha = 0.28f)
        VoiceState.Listening -> OverlayListeningSecondary
        VoiceState.Transcribing -> dim.copy(alpha = 0.58f)
        VoiceState.Thinking -> dim.copy(alpha = 0.48f)
        VoiceState.Speaking -> OverlaySpeakingSecondary
        VoiceState.Error -> errorColor.copy(alpha = 0.72f)
    }
    val primary by animateColorAsState(
        targetValue = targetPrimary,
        animationSpec = tween(durationMillis = 350),
        label = "overlayRingPrimary",
    )
    val secondary by animateColorAsState(
        targetValue = targetSecondary,
        animationSpec = tween(durationMillis = 350),
        label = "overlayRingSecondary",
    )

    Canvas(modifier = modifier) {
        val minDimension = min(size.width, size.height)
        if (minDimension <= 0f) return@Canvas

        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val innerRadius = minDimension * 0.385f
        val baseLength = minDimension * 0.035f
        val reactiveLength = minDimension * 0.105f
        val strokePx = max(2f, minDimension * 0.024f)
        val bars = 72
        val baseline = when (state) {
            VoiceState.Idle -> 0.04f
            VoiceState.Thinking, VoiceState.Transcribing -> 0.11f
            VoiceState.Error -> 0.08f
            VoiceState.Listening, VoiceState.Speaking -> 0.08f
        }
        val envelope = max(displayAmplitude, baseline)

        for (index in 0 until bars) {
            val fraction = index / bars.toFloat()
            val angle = (fraction * TWO_PI) - HALF_PI
            val wobble = (
                sin(angle * 3.0f + phase) * 0.48f +
                    sin(angle * 7.0f - phase * 0.7f) * 0.28f +
                    sin(angle * 13.0f + phase * 1.35f) * 0.16f
                ).coerceIn(-1f, 1f)
            val normalized = (wobble + 1f) * 0.5f
            val lineLength = baseLength + reactiveLength * envelope * normalized
            val startRadius = innerRadius
            val endRadius = innerRadius + lineLength
            val start = androidx.compose.ui.geometry.Offset(
                x = center.x + cos(angle) * startRadius,
                y = center.y + sin(angle) * startRadius,
            )
            val end = androidx.compose.ui.geometry.Offset(
                x = center.x + cos(angle) * endRadius,
                y = center.y + sin(angle) * endRadius,
            )
            val colorMix = (sin(angle + phase * 0.35f) + 1f) * 0.5f
            drawLine(
                color = lerp(primary, secondary, colorMix),
                start = start,
                end = end,
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
                alpha = (0.56f + normalized * 0.36f).coerceIn(0f, 1f),
            )
        }
    }
}

@Composable
private fun rememberOverlayWaveformPhase(amplitude: Float): Float {
    val ampRef = rememberUpdatedState(amplitude.coerceIn(0f, 1f))
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastNanos != 0L) {
                    val deltaSeconds = (now - lastNanos) / 1_000_000_000f
                    val cyclesPerSecond = 0.28f + ampRef.value * 1.55f
                    phase = (phase + deltaSeconds * cyclesPerSecond * TWO_PI) % TWO_PI
                }
                lastNanos = now
            }
        }
    }
    return phase
}

private fun overlayBubbleStateLabel(state: VoiceState): String = when (state) {
    VoiceState.Idle -> "Ready"
    VoiceState.Listening -> "Listen"
    VoiceState.Transcribing -> "STT"
    VoiceState.Thinking -> "Think"
    VoiceState.Speaking -> "Speak"
    VoiceState.Error -> "Error"
}

@Composable
private fun MicControlButton(
    uiState: VoiceUiState,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onInterrupt: () -> Unit,
    onPauseAutoMode: () -> Unit,
) {
    val isStopAction = uiState.state == VoiceState.Listening ||
        uiState.state == VoiceState.Speaking ||
        uiState.state == VoiceState.Transcribing ||
        uiState.state == VoiceState.Thinking
    Surface(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable {
                dispatchMicAction(
                    uiState = uiState,
                    onStartListening = onStartListening,
                    onStopListening = onStopListening,
                    onInterrupt = onInterrupt,
                    onPauseAutoMode = onPauseAutoMode,
                )
        },
        shape = CircleShape,
        color = when {
            isStopAction -> Color(0xFFE53935)
            uiState.state == VoiceState.Transcribing || uiState.state == VoiceState.Thinking ->
                Color(0xFFE53935)
            uiState.state == VoiceState.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when {
                    isStopAction -> Icons.Filled.Stop
                    uiState.state == VoiceState.Transcribing || uiState.state == VoiceState.Thinking ->
                        Icons.Filled.Stop
                    else -> Icons.Filled.Mic
                },
                contentDescription = "Voice overlay mic",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun dispatchMicAction(
    uiState: VoiceUiState,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onInterrupt: () -> Unit,
    onPauseAutoMode: () -> Unit,
) {
    when (uiState.state) {
        VoiceState.Listening -> {
            if (uiState.interactionMode == InteractionMode.Continuous) {
                onPauseAutoMode()
            } else {
                onStopListening()
            }
        }
        VoiceState.Speaking -> {
            if (uiState.interactionMode == InteractionMode.Continuous) {
                onPauseAutoMode()
            } else {
                onInterrupt()
            }
        }
        VoiceState.Transcribing, VoiceState.Thinking -> {
            if (uiState.interactionMode == InteractionMode.Continuous) {
                onPauseAutoMode()
            } else {
                onInterrupt()
            }
        }
        VoiceState.Idle, VoiceState.Error -> onStartListening()
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

private fun voiceProviderLabel(
    provider: String?,
    model: String?,
    voice: String?,
    outputEnabled: Boolean?,
): String {
    if (outputEnabled == false) return "output off"
    val providerPart = provider?.takeIf { it.isNotBlank() } ?: "provider ..."
    val modelPart = model?.takeIf { it.isNotBlank() }
    val voicePart = voice?.takeIf { it.isNotBlank() }
    return listOfNotNull(providerPart, modelPart, voicePart).joinToString(" / ")
}

private fun voiceEngineLabel(engineMode: String?): String = when (engineMode) {
    "realtime_agent" -> "Realtime Agent"
    "hermes_voice_output" -> "Hermes voice"
    null, "" -> "Voice engine ..."
    else -> engineMode
        .replace('_', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
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
