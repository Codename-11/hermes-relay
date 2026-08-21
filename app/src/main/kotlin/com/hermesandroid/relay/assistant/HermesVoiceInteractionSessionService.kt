package com.hermesandroid.relay.assistant

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
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
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.PersistedHermesRelayTheme
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HermesVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        HermesVoiceInteractionSession(this)
}

internal enum class AssistantSessionPresentation {
    Inactive,
    Overlay,
    FullVoice,
}

internal fun shouldCancelVoiceWhenSessionUiEnds(
    presentation: AssistantSessionPresentation,
): Boolean = presentation == AssistantSessionPresentation.Overlay

private class HermesVoiceInteractionSession(
    private val service: HermesVoiceInteractionSessionService,
) : VoiceInteractionSession(service) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val viewOwner = AssistantSessionViewOwner().also { it.start() }
    private var presentation = AssistantSessionPresentation.Inactive
    private val assistantSurfaceBounds = android.graphics.Rect()
    private var surfaceExpanded by mutableStateOf(false)

    init {
        scope.launch {
            AssistantSessionState.snapshot.collect { snapshot ->
                if (presentation != AssistantSessionPresentation.Inactive &&
                    snapshot.phase == AssistantSessionPhase.Closed
                ) {
                    finishSession(cancelVoice = false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        window.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
    }

    override fun onCreateContentView(): View = ComposeView(service).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setViewTreeLifecycleOwner(viewOwner)
        setViewTreeViewModelStoreOwner(viewOwner)
        setViewTreeSavedStateRegistryOwner(viewOwner)
        setContent {
            PersistedHermesRelayTheme {
                AssistantSessionSurface(
                    expanded = surfaceExpanded,
                    onExpandedChange = { surfaceExpanded = it },
                    onCancel = { finishSession(cancelVoice = true) },
                    onRetry = { launchVoice(startNewSession = true) },
                    onOpenFullVoice = {
                        if (presentation == AssistantSessionPresentation.Overlay) {
                            openFullVoice()
                        }
                    },
                    onSurfaceBoundsChanged = { bounds ->
                        if (assistantSurfaceBounds != bounds) {
                            assistantSurfaceBounds.set(bounds)
                            window.window?.decorView?.requestLayout()
                        }
                    },
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (args?.getBoolean(HermesVoiceInteractionService.EXTRA_FROM_KEYGUARD, false) == true) {
            window.window?.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        setUiEnabled(true)
        val startsNewLifecycle = presentation == AssistantSessionPresentation.Inactive
        presentation = AssistantSessionPresentation.Overlay
        if (!startsNewLifecycle) return

        surfaceExpanded = false
        AssistantSessionState.reset()
        launchVoice(
            activationId = args?.getString(AssistantSessionProtocol.EXTRA_ACTIVATION_ID)
                ?: UUID.randomUUID().toString(),
            startNewSession = args?.getBoolean(
                AssistantSessionProtocol.EXTRA_START_NEW_SESSION,
                true,
            ) ?: true,
        )
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(assistantSurfaceBounds)
    }

    override fun onBackPressed() {
        if (presentation == AssistantSessionPresentation.Overlay && surfaceExpanded) {
            surfaceExpanded = false
            return
        }
        super.onBackPressed()
    }

    override fun onHide() {
        if (shouldCancelVoiceWhenSessionUiEnds(presentation)) {
            finishSession(cancelVoice = true)
        }
        super.onHide()
    }

    override fun onDestroy() {
        if (shouldCancelVoiceWhenSessionUiEnds(presentation)) {
            AssistantSessionProtocol.finish(service, cancelVoice = true)
        }
        presentation = AssistantSessionPresentation.Inactive
        viewOwner.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun launchVoice(
        activationId: String = UUID.randomUUID().toString(),
        startNewSession: Boolean,
    ) {
        runCatching {
            AssistantSessionProtocol.activate(
                service,
                activationId = activationId,
                startNewSession = startNewSession,
            )
        }.onFailure {
            AssistantSessionState.update(
                AssistantSessionSnapshot(
                    phase = AssistantSessionPhase.Error,
                    error = it.message ?: "Hermes could not open the voice session.",
                )
            )
        }
    }

    private fun openFullVoice() {
        runCatching {
            startVoiceActivity(AssistantSessionProtocol.fullVoiceIntent(service))
            presentation = AssistantSessionPresentation.FullVoice
            setUiEnabled(false)
        }.onFailure {
            AssistantSessionState.update(
                AssistantSessionSnapshot(
                    phase = AssistantSessionPhase.Error,
                    error = it.message ?: "Hermes could not open full voice.",
                )
            )
        }
    }

    private fun finishSession(cancelVoice: Boolean) {
        if (presentation == AssistantSessionPresentation.Inactive) return
        presentation = AssistantSessionPresentation.Inactive
        AssistantSessionProtocol.finish(service, cancelVoice)
        finish()
    }
}

private class AssistantSessionViewOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

@Composable
private fun AssistantSessionSurface(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenFullVoice: () -> Unit,
    onSurfaceBoundsChanged: (android.graphics.Rect) -> Unit,
) {
    val snapshot by AssistantSessionState.snapshot.collectAsState()
    val status = assistantStatus(snapshot.phase)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    onSurfaceBoundsChanged(
                        android.graphics.Rect(
                            bounds.left.roundToInt(),
                            bounds.top.roundToInt(),
                            bounds.right.roundToInt(),
                            bounds.bottom.roundToInt(),
                        )
                    )
                },
            shape = RoundedCornerShape(if (expanded) 30.dp else 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 10.dp,
            shadowElevation = 12.dp,
        ) {
            if (expanded) {
                ExpandedAssistantSurface(
                    snapshot = snapshot,
                    status = status,
                    onCollapse = { onExpandedChange(false) },
                    onCancel = onCancel,
                    onRetry = onRetry,
                    onOpenFullVoice = onOpenFullVoice,
                )
            } else {
                CompactAssistantSurface(
                    snapshot = snapshot,
                    status = status,
                    onExpand = { onExpandedChange(true) },
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun CompactAssistantSurface(
    snapshot: AssistantSessionSnapshot,
    status: String,
    onExpand: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AssistantOrb(snapshot.phase)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = compactAssistantText(snapshot),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onExpand,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ExpandLess,
                contentDescription = stringResource(R.string.assistant_session_expand),
            )
        }
        AssistantStopButton(onClick = onCancel, compact = true)
    }
}

@Composable
private fun ExpandedAssistantSurface(
    snapshot: AssistantSessionSnapshot,
    status: String,
    onCollapse: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenFullVoice: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                .align(Alignment.CenterHorizontally),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AssistantOrb(snapshot.phase, size = 38)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onCollapse) {
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = stringResource(R.string.assistant_session_collapse),
                )
            }
        }

        AssistantWaveform(snapshot.phase)

        snapshot.transcript?.takeIf { it.isNotBlank() }?.let { transcript ->
            AssistantTextRow(
                icon = Icons.Filled.Person,
                text = transcript,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        snapshot.response.takeIf { it.isNotBlank() }?.let { response ->
            AssistantTextRow(
                icon = Icons.Filled.AutoAwesome,
                text = response,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        snapshot.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (snapshot.phase == AssistantSessionPhase.Transcribing ||
            snapshot.phase == AssistantSessionPhase.Thinking
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantStopButton(onClick = onCancel, compact = false)
            Spacer(Modifier.weight(1f))
            if (snapshot.phase == AssistantSessionPhase.Error) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.assistant_session_retry))
                }
            }
            OutlinedButton(onClick = onOpenFullVoice) {
                Text(stringResource(R.string.assistant_session_open_full_voice))
            }
        }
    }
}

@Composable
private fun AssistantOrb(
    phase: AssistantSessionPhase,
    size: Int = 52,
) {
    val active = phase == AssistantSessionPhase.Listening ||
        phase == AssistantSessionPhase.Transcribing ||
        phase == AssistantSessionPhase.Thinking ||
        phase == AssistantSessionPhase.Speaking
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size((size * 0.5f).dp),
        )
    }
}

@Composable
private fun AssistantWaveform(phase: AssistantSessionPhase) {
    val active = phase == AssistantSessionPhase.Listening ||
        phase == AssistantSessionPhase.Speaking
    val primary = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
    ) {
        val centerY = size.height / 2f
        val bars = 33
        val spacing = size.width / bars
        repeat(bars) { index ->
            val distance = kotlin.math.abs(index - bars / 2f) / (bars / 2f)
            val envelope = max(0.18f, 1f - distance)
            val pattern = 0.45f + ((index * 17) % 11) / 20f
            val halfHeight = size.height * 0.46f * envelope * pattern
            val x = spacing * (index + 0.5f)
            drawLine(
                color = primary,
                start = Offset(x, centerY - halfHeight),
                end = Offset(x, centerY + halfHeight),
                strokeWidth = max(2f, spacing * 0.28f),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun AssistantTextRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AssistantStopButton(
    onClick: () -> Unit,
    compact: Boolean,
) {
    if (compact) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = stringResource(R.string.assistant_session_cancel),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    } else {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.assistant_session_stop))
        }
    }
}

@Composable
private fun assistantStatus(phase: AssistantSessionPhase): String = when (phase) {
    AssistantSessionPhase.Launching -> stringResource(R.string.assistant_session_launching)
    AssistantSessionPhase.Listening -> stringResource(R.string.assistant_session_listening)
    AssistantSessionPhase.Transcribing -> stringResource(R.string.assistant_session_transcribing)
    AssistantSessionPhase.Thinking -> stringResource(R.string.assistant_session_thinking)
    AssistantSessionPhase.Speaking -> stringResource(R.string.assistant_session_speaking)
    AssistantSessionPhase.Idle -> stringResource(R.string.assistant_session_ready)
    AssistantSessionPhase.Error -> stringResource(R.string.assistant_session_error)
    AssistantSessionPhase.Closed -> stringResource(R.string.assistant_session_closing)
}

@Composable
private fun compactAssistantText(snapshot: AssistantSessionSnapshot): String =
    snapshot.transcript?.takeIf { it.isNotBlank() }
        ?: snapshot.response.takeIf { it.isNotBlank() }
        ?: snapshot.error?.takeIf { it.isNotBlank() }
        ?: assistantStatus(snapshot.phase)
