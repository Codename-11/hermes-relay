package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.ToolCall
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.showHumanError
import com.hermesandroid.relay.util.HumanError
import kotlinx.coroutines.delay
import com.hermesandroid.relay.viewmodel.BackgroundRunPhase
import com.hermesandroid.relay.viewmodel.BackgroundRunState
import com.hermesandroid.relay.viewmodel.DestructiveCountdownState
import com.hermesandroid.relay.viewmodel.HermesConfirmationState
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.PermissionDeniedCallout
import com.hermesandroid.relay.viewmodel.VoiceHandoffStatus
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * Full-screen voice-mode overlay. Renders the MorphingSphere in its voiceMode
 * expanded state plus a mic button that drives the [VoiceViewModel] turn
 * cycle. Dispatch rules per interaction mode live on the mic button — the
 * overlay itself is a thin Compose shell over the locked VoiceUiState.
 */
@Composable
fun VoiceModeOverlay(
    uiState: VoiceUiState,
    onMicTap: () -> Unit,
    onMicRelease: () -> Unit,
    onInterrupt: () -> Unit,
    onPauseAutoMode: () -> Unit = {},
    onDismiss: () -> Unit,
    // Navigates to the full Voice Settings screen from the overlay's gear
    // button. Default no-op so existing call sites/previews still compile;
    // the gear button exits voice mode before invoking this so the overlay
    // isn't left floating over the settings screen.
    onOpenSettings: () -> Unit = {},
    onModeChange: (InteractionMode) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
    // Nullable so existing call sites compile; when wired, voice errors
    // surface as global snackbars in addition to the inline banner below.
    errorEvents: SharedFlow<HumanError>? = null,
    // === PHASE3-voice-mode-transcript ===
    // Compact rolling transcript of the last N chat messages — the
    // caller (ChatScreen) passes `chatViewModel.messages.takeLast(6)`.
    // Voice mode is voice-first but wasn't giving the user any visibility
    // into conversation history during a session, so this adds a compact
    // strip below the sphere that observes the same ChatHandler message
    // flow the chat tab uses. Includes local-only voice-intent traces
    // (agentName = "Voice action", id prefix "voice-intent-") rendered
    // via MarkdownContent so the bold + inline code in traces like
    // "**Opened Chrome** `com.android.chrome`" shows correctly.
    //
    // Default empty-list so existing call sites compile; the empty state
    // hint ("Tap the mic to speak") still renders when the transcript
    // is empty.
    transcriptMessages: List<ChatMessage> = emptyList(),
    showThinking: Boolean = true,
    voiceEngineMode: String? = null,
    voiceOutputProvider: String? = null,
    voiceOutputModel: String? = null,
    voiceOutputVoice: String? = null,
    voiceProfileName: String? = null,
    voiceConfigScope: String? = null,
    voiceOutputEnabled: Boolean? = null,
    voiceOutputFallbackEnabled: Boolean? = null,
    onOverlayRequest: () -> Unit = {},
    onCompactModeChange: (Boolean) -> Unit = {},
    // === END PHASE3-voice-mode-transcript ===
    // === v0.4.1 JIT permission-denied chip ===
    // Tapped when the user clicks the permission-denied chip. Default no-op
    // so existing call sites keep compiling; the chip simply does nothing
    // visible if not wired (the chip itself is also gated on
    // `uiState.permissionDeniedCallout` being non-null).
    onPermissionDeniedChipTap: (PermissionDeniedCallout) -> Unit = {},
    // Cancels the promoted/durable background Hermes run from the chip's ✕.
    // Default no-op so existing call sites/previews keep compiling.
    onBackgroundRunCancel: () -> Unit = {},
    onHermesConfirmationAnswer: (String) -> Unit = {},
    // === END v0.4.1 ===
) {
    val surface = MaterialTheme.colorScheme.surface
    val haptic = LocalHapticFeedback.current

    var controlsExpanded by remember { mutableStateOf(false) }
    var focusMode by remember { mutableStateOf(true) }
    val setFocusMode: (Boolean) -> Unit = { focused ->
        focusMode = focused
        onCompactModeChange(!focused)
    }

    LaunchedEffect(uiState.voiceMode) {
        if (!uiState.voiceMode) {
            setFocusMode(true)
        }
    }

    // Voice errors surface ONLY on the overlay's own inline top banner
    // (uiState.error) while the overlay is up — we deliberately do NOT also pipe
    // them to the app-wide bottom snackbar. Doing both duplicated the message
    // and left a retry-only, un-dismissable toast at the bottom during long /
    // timed-out background runs. (errorEvents is still used by the chat + voice
    // settings surfaces when the overlay isn't the active surface.)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (focusMode) surface.copy(alpha = 0.96f) else Color.Transparent)
            // Click-through fix: in focus mode the overlay is a true modal.
            // Consume any pointer event a child (mic button, transcript,
            // chips, pill) didn't handle so stray taps/swipes don't fall
            // through to the chat + session drawer behind it. Children run on
            // the same Main pass leaf-first, so this only catches the gaps.
            // In compact mode the overlay is intentionally transparent and the
            // chat stays interactive, so no scrim is installed.
            .then(
                if (focusMode) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        VoiceSessionPill(
            uiState = uiState,
            expanded = controlsExpanded,
            onExpandedChange = { controlsExpanded = it },
            focusMode = focusMode,
            onFocusModeChange = setFocusMode,
            engineMode = voiceEngineMode,
            provider = voiceOutputProvider,
            model = voiceOutputModel,
            voice = voiceOutputVoice,
            profileName = voiceProfileName,
            configScope = voiceConfigScope,
            outputEnabled = voiceOutputEnabled,
            fallbackEnabled = voiceOutputFallbackEnabled,
            onModeChange = onModeChange,
            onMicTap = onMicTap,
            onMicRelease = onMicRelease,
            onInterrupt = onInterrupt,
            onPauseAutoMode = onPauseAutoMode,
            onOverlayRequest = onOverlayRequest,
            onOpenSettings = onOpenSettings,
            onExit = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
        )

        // Center column: sphere -> waveform -> scrolling transcript.
        //
        // Layout uses fixed-weight slots instead of SpaceBetween so the
        // sphere/waveform don't drift as the transcript grows. The transcript
        // area owns its own LazyListState and auto-scrolls to the tail item so
        // long active responses and tool rows stay visible as they update.
        //
        // Transcript content source: chat history is the durable source of
        // truth, with one short-lived exception: after STT succeeds but
        // before ChatViewModel has committed the user message, render a
        // deduped pending "YOU" row from uiState.transcribedText. That keeps
        // the voice overlay honest during the Thinking gap without creating
        // duplicate rows once chat history catches up.
        val transcriptListState = rememberLazyListState()
        val visibleTranscriptMessages = remember(transcriptMessages) {
            transcriptMessages.filter { it.role != MessageRole.SYSTEM }
        }
        val pendingTranscriptText = remember(
            uiState.state,
            uiState.transcribedText,
            visibleTranscriptMessages,
        ) {
            pendingVoiceTranscriptText(
                uiState = uiState,
                visibleTranscriptMessages = visibleTranscriptMessages,
            )
        }

        // Derive the streaming "token length" from the last assistant message
        // in the transcript so auto-scroll follows mid-stream growth without
        // needing a separate `responseText` flow.
        val lastStreamingContentLength = visibleTranscriptMessages.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.isStreaming
        }?.content?.length ?: 0
        val lastThinkingContentLength = visibleTranscriptMessages.lastOrNull {
            it.role == MessageRole.ASSISTANT &&
                (it.isThinkingStreaming || it.thinkingContent.isNotBlank())
        }?.thinkingContent?.length ?: 0
        val toolSnapshot = visibleTranscriptMessages
            .flatMap { it.toolCalls }
            .joinToString("|") { tool ->
                "${tool.id}:${tool.name}:${tool.isComplete}:${tool.result?.length}:${tool.error?.length}"
            }

        // Auto-scroll to the tail whenever a new message arrives in the
        // transcript or the last streaming assistant bubble grows. Smooth
        // animateScrollTo so the user's eye never has to chase token churn.
        LaunchedEffect(
            visibleTranscriptMessages.size,
            pendingTranscriptText,
            lastStreamingContentLength,
            lastThinkingContentLength,
            toolSnapshot,
        ) {
            val tailIndex = visibleTranscriptMessages.size +
                if (pendingTranscriptText != null) 1 else 0
            if (tailIndex > 0) {
                transcriptListState.animateScrollToItem(tailIndex)
            }
        }

        AnimatedVisibility(
            visible = focusMode,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 92.dp, bottom = 160.dp, start = 20.dp, end = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.0f),
                    contentAlignment = Alignment.Center,
                ) {
                    LocalAgentAvatar.current.Render(
                        state = AvatarRenderState(
                            state = voiceStateToSphereState(uiState.state),
                            voiceAmplitude = uiState.amplitude,
                            voiceMode = true,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                VoiceWaveform(
                    amplitude = uiState.amplitude,
                    state = uiState.state,
                    outputAudioActive = uiState.outputAudioActive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 8.dp),
                )

                AnimatedVisibility(
                    visible = uiState.handoffStatus != null,
                    enter = fadeIn(tween(140)),
                    exit = fadeOut(tween(180)),
                ) {
                    VoiceHandoffStrip(
                        status = uiState.handoffStatus,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }

                BackgroundRunChip(
                    run = uiState.backgroundRun,
                    onCancel = onBackgroundRunCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                )

                Spacer(Modifier.height(8.dp))

                DestructiveCountdownRow(
                    countdown = uiState.destructiveCountdown,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )

                PermissionDeniedChip(
                    callout = uiState.permissionDeniedCallout,
                    onTap = onPermissionDeniedChipTap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                )

                HermesConfirmationCard(
                    confirmation = uiState.hermesConfirmation,
                    onAnswer = onHermesConfirmationAnswer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                )

                Spacer(Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.25f, fill = true),
                ) {
                    val hasTranscript = visibleTranscriptMessages.isNotEmpty() ||
                        pendingTranscriptText != null
                    if (hasTranscript) {
                        val latestId = visibleTranscriptMessages.lastOrNull()?.id
                        LazyColumn(
                            state = transcriptListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(visibleTranscriptMessages, key = { it.id }) { msg ->
                                CompactTranscriptRow(
                                    message = msg,
                                    showThinking = showThinking,
                                    expanded = msg.id == latestId || msg.isStreaming,
                                )
                            }
                            if (pendingTranscriptText != null) {
                                item(key = "pending-voice-transcript") {
                                    CompactTranscriptRow(
                                        message = ChatMessage(
                                            id = "pending-voice-transcript",
                                            role = MessageRole.USER,
                                            content = pendingTranscriptText,
                                            timestamp = System.currentTimeMillis(),
                                            isStreaming = true,
                                        ),
                                        showThinking = showThinking,
                                        expanded = true,
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(12.dp)) }
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            AnimatedContent(
                                targetState = stateHint(uiState.state),
                                transitionSpec = {
                                    fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                                },
                                label = "stateHint",
                            ) { hint ->
                                Text(
                                    text = hint,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Error banner
        AnimatedVisibility(
            visible = uiState.error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp, start = 16.dp, end = 16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = uiState.error.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    // Dismiss clears the error and returns to Idle without
                    // retrying, so a failed/timed-out turn never traps the user
                    // on a retry-only banner.
                    TextButton(onClick = { onClearError() }) {
                        Text("Dismiss")
                    }
                    TextButton(
                        onClick = {
                            onClearError()
                            onMicTap()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text("Retry")
                    }
                }
            }
        }

        // Mic button — dispatch by current voice state.
        //
        // Listening → tap stops recording (feeds the audio to STT)
        // Speaking  → tap interrupts TTS and starts fresh recording
        // Idle/Error → tap starts recording
        // Transcribing/Thinking → tap cancels the in-flight realtime turn
        //
        // The bug before was that Listening fell through to the else branch
        // which called onMicTap (= startListening) a second time instead of
        // stopping the in-progress recording. User ended up with a button
        // that visually said "Stop" but was actually wired to "Start."
        VoiceMicButton(
            uiState = uiState,
            onTap = {
                dispatchVoiceMicTap(
                    uiState = uiState,
                    onStartListening = {
                        try {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } catch (_: Exception) { /* ignore */ }
                        onMicTap()
                    },
                    onStopListening = {
                        try {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } catch (_: Exception) { /* ignore */ }
                        onMicRelease()
                    },
                    onInterrupt = onInterrupt,
                    onPauseAutoMode = onPauseAutoMode,
                )
            },
            onHoldPress = {
                dispatchVoiceMicHoldPress(
                    uiState = uiState,
                    onStartListening = {
                        try {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } catch (_: Exception) { /* ignore */ }
                        onMicTap()
                    },
                    onInterruptAndStart = {
                        try {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } catch (_: Exception) { /* ignore */ }
                        onInterrupt()
                        onMicTap()
                    },
                )
            },
            onHoldRelease = {
                try {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                } catch (_: Exception) { /* ignore */ }
                onMicRelease()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            visible = focusMode,
        )
    }
}

@Composable
private fun VoiceMicButton(
    uiState: VoiceUiState,
    onTap: () -> Unit,
    onHoldPress: () -> Unit,
    onHoldRelease: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    baseSize: Int = 72,
    iconSize: Int = 32,
) {
    if (!visible) return

    val pulseScale by animateFloatAsState(
        targetValue = when (uiState.state) {
            VoiceState.Listening -> 1f + uiState.amplitude * 0.15f
            VoiceState.Speaking -> 1f + uiState.amplitude * 0.08f
            else -> 1f
        },
        animationSpec = tween(120),
        label = "micPulse",
    )

    // Hardcoded vivid red for Listening AND Speaking. Material 3's dark-theme
    // `colorScheme.error` role resolves to a soft pink (#F2B8B5) which reads
    // as "pale" rather than "STOP" on a circular mic button — the universal
    // "record/stop" language is a saturated red, so bypass the role and use
    // Material Red 600. Speaking needs the same red because tapping it
    // interrupts TTS; the old tertiary (green-ish) read as "playing" and
    // users didn't realize they could stop the agent.
    val containerColor = when (uiState.state) {
        VoiceState.Listening -> Color(0xFFE53935)
        VoiceState.Speaking -> Color(0xFFE53935)
        VoiceState.Transcribing, VoiceState.Thinking -> Color(0xFFE53935)
        VoiceState.Error -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primary
    }

    val icon = when (uiState.state) {
        VoiceState.Listening -> Icons.Filled.Stop
        VoiceState.Transcribing, VoiceState.Thinking -> Icons.Filled.Stop
        // Stop icon makes the "tap to interrupt TTS" affordance obvious;
        // VolumeUp looked decorative and users didn't try tapping it.
        VoiceState.Speaking -> Icons.Filled.Stop
        else -> Icons.Filled.Mic
    }

    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnHoldPress by rememberUpdatedState(onHoldPress)
    val currentOnHoldRelease by rememberUpdatedState(onHoldRelease)

    val gestureModifier = when (uiState.interactionMode) {
        InteractionMode.HoldToTalk -> Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                currentOnHoldPress()
                // Hold until the finger genuinely lifts. Don't use
                // waitForUpOrCancellation(): it ends the hold on ANY cancel — a
                // consumed move event or the finger drifting just off the small
                // circle — which made the button feel like it released by
                // accident. Loop until no pointer is still pressed so drift and
                // minor consumption don't cut the recording short.
                do {
                    val event = awaitPointerEvent()
                } while (event.changes.any { it.pressed })
                currentOnHoldRelease()
            }
        }
        else -> Modifier.clickable { currentOnTap() }
    }

    Surface(
        modifier = modifier
            .size((baseSize * pulseScale).dp)
            .clip(CircleShape)
            .then(gestureModifier),
        shape = CircleShape,
        color = containerColor,
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = "Voice mic",
                tint = Color.White,
                modifier = Modifier.size(iconSize.dp),
            )
        }
    }
}

private fun voiceStateToSphereState(state: VoiceState): SphereState = when (state) {
    VoiceState.Listening -> SphereState.Listening
    VoiceState.Speaking -> SphereState.Speaking
    VoiceState.Thinking, VoiceState.Transcribing -> SphereState.Thinking
    VoiceState.Error -> SphereState.Error
    VoiceState.Idle -> SphereState.Idle
}

private fun stateHint(state: VoiceState): String = when (state) {
    VoiceState.Idle -> "Tap the mic to speak"
    VoiceState.Listening -> "Listening..."
    VoiceState.Transcribing -> "Transcribing..."
    VoiceState.Thinking -> "Thinking..."
    VoiceState.Speaking -> "Speaking..."
    VoiceState.Error -> ""
}

internal fun pendingVoiceTranscriptText(
    uiState: VoiceUiState,
    visibleTranscriptMessages: List<ChatMessage>,
): String? {
    if (uiState.state != VoiceState.Thinking) return null

    val transcribed = uiState.transcribedText
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null

    fun contentMatches(message: ChatMessage?): Boolean {
        return message?.role == MessageRole.USER &&
            message.content.trim() == transcribed
    }

    val lastMessage = visibleTranscriptMessages.lastOrNull()
    val previousMessage = visibleTranscriptMessages.dropLast(1).lastOrNull()
    val alreadyRendered = contentMatches(lastMessage) ||
        (lastMessage?.role == MessageRole.ASSISTANT &&
            lastMessage.isStreaming &&
            contentMatches(previousMessage))

    return if (alreadyRendered) null else transcribed
}

private fun InteractionMode.label(): String = when (this) {
    InteractionMode.TapToTalk -> "Tap to talk"
    InteractionMode.HoldToTalk -> "Hold to talk"
    InteractionMode.Continuous -> "Continuous"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceSessionPill(
    uiState: VoiceUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    focusMode: Boolean,
    onFocusModeChange: (Boolean) -> Unit,
    engineMode: String?,
    provider: String?,
    model: String?,
    voice: String?,
    profileName: String?,
    configScope: String?,
    outputEnabled: Boolean?,
    fallbackEnabled: Boolean?,
    onModeChange: (InteractionMode) -> Unit,
    onMicTap: () -> Unit,
    onMicRelease: () -> Unit,
    onInterrupt: () -> Unit,
    onPauseAutoMode: () -> Unit,
    onOverlayRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engineText = voiceEngineLabel(engineMode)
    val providerText = voiceProviderLabel(provider, model, voice, outputEnabled)
    val profileText = profileName?.takeIf { it.isNotBlank() } ?: "default profile"
    val scopeText = when (configScope) {
        "profile" -> "profile voice"
        "relay" -> "relay voice"
        "global" -> "global voice"
        else -> null
    }
    val headlineText = if (focusMode) {
        "$engineText / $profileText / $providerText"
    } else {
        "${stateHint(uiState.state).ifBlank { "Voice ready" }} / $engineText / $providerText"
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        // Fully opaque panel — the overlay floats over live chat/sphere, so a
        // translucent surface let the background bleed through and made the
        // dropdown text hard to read.
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 5.dp,
        shadowElevation = 7.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Show the active profile's local icon (if set) as the leading
                // glyph — same circular avatar treatment chat uses in
                // MessageBubble. Falls back to the equalizer icon when there's
                // no profile icon; the sphere/pet remains the no-icon fallback.
                val agentIconPath = LocalAgentIconPath.current
                if (!agentIconPath.isNullOrBlank()) {
                    AsyncImage(
                        model = File(agentIconPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .testTag("voiceOverlayProfileIcon")
                            .size(18.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = "Voice",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Collapsed header trimmed to icon + "Voice" + a single
                // weighted title + chevron + close so the top bar can never
                // wrap on narrow screens. The status pill and inline mic
                // control moved into the expanded body below.
                Text(
                    text = headlineText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse voice controls" else "Expand voice controls",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                IconButton(
                    onClick = onExit,
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

            VoiceHandoffStrip(
                status = uiState.handoffStatus,
                compact = !expanded,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Moved out of the collapsed header (4a): the current
                    // interaction-mode pill plus the inline mic control. The
                    // compact mic only appears in compact mode, where the
                    // full-size bottom mic button is hidden.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusPill(uiState.interactionMode.label())
                        Spacer(Modifier.weight(1f))
                        if (!focusMode) {
                            CompactVoiceMicButton(
                                uiState = uiState,
                                onMicTap = onMicTap,
                                onMicRelease = onMicRelease,
                                onInterrupt = onInterrupt,
                                onPauseAutoMode = onPauseAutoMode,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        InteractionMode.values().forEach { mode ->
                            VoiceControlChip(
                                text = mode.shortLabel(),
                                selected = uiState.interactionMode == mode,
                                onClick = { onModeChange(mode) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Status pills use a FlowRow so they wrap to a second line
                    // on narrow screens instead of being squeezed into equal
                    // weighted columns that truncate every label (4a).
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusPill(engineText, emphasized = true)
                        StatusPill(profileText)
                        scopeText?.let { StatusPill(it) }
                        StatusPill(providerText)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = { onFocusModeChange(!focusMode) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (focusMode) "Compact" else "Focus",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            onClick = {
                                onFocusModeChange(false)
                                onOverlayRequest()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Overlay", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(
                            onClick = onExit,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Exit", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // Settings link (4c): exit voice mode before navigating
                        // so the overlay isn't left floating over the Voice
                        // Settings screen.
                        IconButton(
                            onClick = {
                                onExit()
                                onOpenSettings()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Voice settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactVoiceMicButton(
    uiState: VoiceUiState,
    onMicTap: () -> Unit,
    onMicRelease: () -> Unit,
    onInterrupt: () -> Unit,
    onPauseAutoMode: () -> Unit,
) {
    VoiceMicButton(
        uiState = uiState,
        onTap = {
            dispatchVoiceMicTap(
                uiState = uiState,
                onStartListening = onMicTap,
                onStopListening = onMicRelease,
                onInterrupt = onInterrupt,
                onPauseAutoMode = onPauseAutoMode,
            )
        },
        onHoldPress = {
            dispatchVoiceMicHoldPress(
                uiState = uiState,
                onStartListening = onMicTap,
                onInterruptAndStart = {
                    onInterrupt()
                    onMicTap()
                },
            )
        },
        onHoldRelease = onMicRelease,
        baseSize = 40,
        iconSize = 20,
    )
}

internal fun dispatchVoiceMicTap(
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

internal fun dispatchVoiceMicHoldPress(
    uiState: VoiceUiState,
    onStartListening: () -> Unit,
    onInterruptAndStart: () -> Unit,
) {
    when (uiState.state) {
        VoiceState.Idle, VoiceState.Error -> onStartListening()
        VoiceState.Speaking -> onInterruptAndStart()
        VoiceState.Listening, VoiceState.Transcribing, VoiceState.Thinking -> Unit
    }
}

@Composable
private fun VoiceControlChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .height(34.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            // Opaque — translucent chips over the floating overlay were hard to read.
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Surface(
        modifier = modifier.height(24.dp),
        shape = RoundedCornerShape(999.dp),
        color = if (emphasized) {
            // Opaque — translucent status bubbles over the floating overlay were
            // hard to read against the sphere/chat behind them.
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (emphasized) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
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

@Composable
private fun VoiceHandoffStrip(
    status: VoiceHandoffStatus?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val current = status ?: return
    val containerColor = when {
        current.success -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f)
        current.active -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    }
    val contentColor = when {
        current.success -> MaterialTheme.colorScheme.onTertiaryContainer
        current.active -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current.title,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            current.route?.takeIf { it.isNotBlank() }?.let { route ->
                Text(
                    text = route,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.76f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!compact) {
            current.entries.takeLast(3).forEach { entry ->
                val detail = entry.detail?.takeIf { it.isNotBlank() }
                Text(
                    text = if (detail == null) entry.label else "${entry.label} / $detail",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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

private fun InteractionMode.shortLabel(): String = when (this) {
    InteractionMode.TapToTalk -> "Tap"
    InteractionMode.HoldToTalk -> "Hold"
    InteractionMode.Continuous -> "Auto"
}

/**
 * Compact transcript row for voice mode. Rendering rules:
 *
 *   - `id.startsWith("voice-intent-")` → voice-action trace, rendered via
 *     [MarkdownContent] unbounded (these are the rich bridge-intent bubbles
 *     like "**Opened Chrome** `com.android.chrome` — exact match", which
 *     need the bold + inline code styling to read well).
 *   - `role == USER` -> caption "YOU" + body in bodySmall.
 *   - `role == ASSISTANT` -> caption "AGENT" + body in bodyMedium.
 *   - `role == SYSTEM` -> skipped entirely (voice mode is a conversation
 *     surface, not a system-message debug view).
 *
 * The latest or streaming row is unbounded so the current response is not cut
 * off. Older rows are capped to keep the overlay scan-friendly. The caller is
 * responsible for bounding the list length.
 */
@Composable
private fun CompactTranscriptRow(
    message: ChatMessage,
    showThinking: Boolean,
    expanded: Boolean,
) {
    if (message.role == MessageRole.SYSTEM) return

    // A voice-intent trace is a PAIR of messages added by
    // ChatHandler.appendLocalVoiceIntentTrace — one USER with the raw
    // utterance ("voice-intent-user-$ts") and one ASSISTANT with the
    // action description ("voice-intent-action-$ts"). Only the assistant
    // half should carry the "ACTION" caption + MarkdownContent rendering;
    // the user half should stay labeled "YOU" so the transcript reads as
    // a normal user→assistant exchange. Pre-fix we keyed off the id
    // prefix alone and mislabeled the user half as ACTION (Bailey
    // 2026-04-15 screenshot: duplicate ACTION row with the raw utterance).
    val isVoiceActionBubble = message.role == MessageRole.ASSISTANT &&
        message.id.startsWith("voice-intent-")
    val caption = when {
        isVoiceActionBubble -> "ACTION"
        message.role == MessageRole.USER -> "YOU"
        else -> "AGENT"
    }
    val captionColor = when {
        isVoiceActionBubble -> MaterialTheme.colorScheme.tertiary
        message.role == MessageRole.USER -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = captionColor,
        )
        Spacer(Modifier.height(2.dp))
        if (message.role == MessageRole.ASSISTANT &&
            showThinking &&
            message.thinkingContent.isNotBlank()
        ) {
            ThinkingBlock(
                thinkingContent = message.thinkingContent,
                isStreaming = message.isThinkingStreaming,
                timestamp = message.timestamp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        val hasText = message.content.isNotBlank()
        // Tools run before the agent's reply, so render the tool rows above the
        // reply text — the bubble then reads in chronological order (tool calls
        // ran → answer) instead of showing the answer above the tools that
        // preceded it.
        if (message.toolCalls.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.toolCalls.forEach { toolCall ->
                    VoiceToolStatusRow(toolCall)
                }
            }
            if (hasText) Spacer(Modifier.height(6.dp))
        }
        when {
            isVoiceActionBubble && hasText -> MarkdownContent(
                content = message.content,
                textColor = MaterialTheme.colorScheme.onSurface,
            )
            message.role == MessageRole.USER && hasText -> Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            hasText -> Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VoiceToolStatusRow(toolCall: ToolCall) {
    val status = when {
        toolCall.isComplete && toolCall.success == true -> "done"
        toolCall.isComplete && toolCall.success == false -> "failed"
        else -> "running"
    }
    val statusColor = when (status) {
        "done" -> MaterialTheme.colorScheme.primary
        "failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    val duration = if (toolCall.completedAt != null && toolCall.completedAt >= toolCall.startedAt) {
        val seconds = (toolCall.completedAt - toolCall.startedAt) / 1000.0
        String.format("%.1fs", seconds)
    } else {
        null
    }
    val rawPreview = toolCall.error?.takeIf { it.isNotBlank() }
        ?: toolCall.result?.takeIf { it.isNotBlank() }
    val preview = remember(rawPreview) {
        rawPreview?.let { compactToolDetail(it, maxChars = 280) }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = toolIcon(toolCall.name),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = toolCall.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = duration?.let { "$status $it" } ?: status,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
            }
            if (!toolCall.isComplete) {
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            if (preview != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HermesConfirmationCard(
    confirmation: HermesConfirmationState?,
    onAnswer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = confirmation != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        confirmation?.let { state ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onAnswer("allow") }) { Text("Allow") }
                        TextButton(onClick = { onAnswer("deny") }) { Text("Deny") }
                        TextButton(onClick = { onAnswer("cancel") }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

/**
 * Renders the 5-second destructive-intent confirmation countdown as a
 * thin horizontal progress bar with a short label. The bar fills from
 * 0 → 1 over [DestructiveCountdownState.durationMs] using a local
 * [Animatable] keyed on the countdown's `startedAtMs` so a fresh
 * countdown always restarts cleanly. When [countdown] is null, the row
 * fades out entirely.
 *
 * Visual treatment is intentionally muted (tertiary color, caption-sized
 * label) — voice mode is voice-first and the real safety rails live in
 * the spoken preview and the cancel vocabulary. This is just "something
 * is about to happen" scaffolding for sighted users.
 */
@Composable
private fun DestructiveCountdownRow(
    countdown: DestructiveCountdownState?,
    modifier: Modifier = Modifier,
) {
    // Hold the most-recent non-null countdown so the row can keep rendering
    // its fade-out transition after the VM clears the state. Without this
    // the label would snap to "" at the same frame the fade begins.
    var lastShown by remember { mutableStateOf<DestructiveCountdownState?>(null) }
    LaunchedEffect(countdown) {
        if (countdown != null) lastShown = countdown
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(countdown?.startedAtMs) {
        if (countdown != null) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = countdown.durationMs.toInt().coerceAtLeast(100),
                    easing = LinearEasing,
                ),
            )
        } else {
            progress.snapTo(0f)
        }
    }

    AnimatedVisibility(
        visible = countdown != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        val label = (countdown ?: lastShown)?.intentLabel ?: ""
        val durationSec = ((countdown ?: lastShown)?.durationMs ?: 0L) / 1000
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = if (label.isNotBlank()) {
                    "$label in ${durationSec}s — say cancel to stop"
                } else {
                    "Confirming…"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress.value.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/**
 * ADR 33 live background-run chip.
 *
 * With timer-driven spoken progress off by default, this chip is the primary
 * in-between signal for a promoted/durable Hermes run: a pulsing dot, a
 * phase-aware title, a live secondary line (active tool · steps · elapsed),
 * and a ✕ that cancels the run. Phases: RUNNING (normal), RECONNECTING (the
 * voice socket dropped mid-run — the relay keeps the run alive while the
 * client retries), DELIVERING (run finished; summary queued behind the floor).
 *
 * Takes the nullable state and remembers the last non-null value so the exit
 * fade shows real content instead of snapping empty (same pattern as
 * [PermissionDeniedChip]).
 */
@Composable
private fun BackgroundRunChip(
    run: BackgroundRunState?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var latest by remember { mutableStateOf<BackgroundRunState?>(null) }
    run?.let { latest = it }
    val display = latest ?: return

    AnimatedVisibility(
        visible = run != null,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(180)),
        modifier = modifier,
    ) {
        // mm:ss ticker — recomposes this chip once a second while visible.
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(display.startedAtMs) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1_000L)
            }
        }
        val elapsedSeconds = ((nowMs - display.startedAtMs) / 1000L).coerceAtLeast(0L)
        val elapsedLabel = "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)

        val pulse by rememberInfiniteTransition(label = "bgRunPulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "bgRunPulseAlpha",
        )
        val dotColor = when (display.phase) {
            BackgroundRunPhase.RECONNECTING -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
        val title = when (display.phase) {
            BackgroundRunPhase.RECONNECTING -> "Reconnecting — your task is still running"
            BackgroundRunPhase.DELIVERING -> display.message
            BackgroundRunPhase.RUNNING -> display.message
        }
        val detail = buildList {
            display.statusLine
                ?.takeIf { it.isNotBlank() }
                ?.let { add(it.trimEnd('.', '…')) }
            if (display.completedToolCount > 0) {
                add(
                    "${display.completedToolCount} step" +
                        if (display.completedToolCount == 1) "" else "s"
                )
            }
            add(elapsedLabel)
        }.joinToString(" · ")

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = pulse)),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cancel background task",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * v0.4.1 JIT permission-denied chip.
 *
 * Surfaced after a voice intent dispatch fails with `error_code ==
 * "permission_denied"`. Tappable Surface that deep-links to Android's
 * per-app permission page so the user can grant the missing perm without
 * leaving voice mode by hand. Visual treatment is errorContainer-coloured
 * so it stands out from the muted DestructiveCountdownRow above it.
 *
 * Hides itself with a fade transition the moment [callout] becomes null
 * (next mic tap or after the user acts on the chip).
 */
@Composable
private fun PermissionDeniedChip(
    callout: PermissionDeniedCallout?,
    onTap: (PermissionDeniedCallout) -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastShown by remember { mutableStateOf<PermissionDeniedCallout?>(null) }
    LaunchedEffect(callout) {
        if (callout != null) lastShown = callout
    }
    AnimatedVisibility(
        visible = callout != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        // Hold a non-null reference for the fade-out frames after the VM
        // clears the state. Mirror the same trick DestructiveCountdownRow
        // uses above so the chip body doesn't snap to empty mid-fade.
        val current = callout ?: lastShown
        if (current != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTap(current) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = current.hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Text(
                        text = "OPEN",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

// StreamingResponseRow removed: the overlay now renders the in-flight
// streaming assistant response through the same `transcriptMessages` path
// as committed turns, since ChatViewModel's last streaming message already
// updates in real time. See the transcript-source comment above for
// rationale (the double-entry bug fix).
