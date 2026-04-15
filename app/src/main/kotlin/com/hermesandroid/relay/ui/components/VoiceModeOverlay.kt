package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.showHumanError
import com.hermesandroid.relay.util.HumanError
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import kotlinx.coroutines.flow.SharedFlow

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
    onDismiss: () -> Unit,
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
    // === END PHASE3-voice-mode-transcript ===
) {
    val surface = MaterialTheme.colorScheme.surface
    val haptic = LocalHapticFeedback.current

    var modeMenuOpen by remember { mutableStateOf(false) }

    // Pipe classified voice errors to the app-wide snackbar host. The inline
    // error banner stays as a belt-and-suspenders for longer-lived messages.
    val snackbarHost = LocalSnackbarHost.current
    LaunchedEffect(errorEvents) {
        errorEvents?.collect { err ->
            snackbarHost.showHumanError(err)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Fully opaque surface — at 0.95f the chat content behind the
            // overlay was bleeding through (the "phantom pencil" effect). Voice
            // mode is a modality, not a dim-over; the transition animation
            // already sells the mode change, no translucency needed.
            .background(surface)
    ) {
        // Top bar — close + mode selector.
        // `statusBarsPadding()` pushes the row below the system status bar so
        // the close button isn't crammed against battery/signal icons and
        // Android's gesture area can't swallow taps on buttons near the edge.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { modeMenuOpen = true }) {
                    Text(uiState.interactionMode.label())
                }
                DropdownMenu(
                    expanded = modeMenuOpen,
                    onDismissRequest = { modeMenuOpen = false },
                ) {
                    InteractionMode.values().forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label()) },
                            onClick = {
                                onModeChange(mode)
                                modeMenuOpen = false
                            },
                        )
                    }
                }
            }

            // Filled tonal background gives the close button a clear "tappable"
            // affordance. Plain IconButton was visually indistinguishable from
            // the surface and users couldn't find it at a glance.
            FilledTonalIconButton(
                onClick = onDismiss,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close voice mode",
                )
            }
        }

        // Center column: pinned "you said" chip → sphere → waveform → scrolling transcript.
        //
        // Layout uses fixed-weight slots (sphere 1.5, transcript 1f) instead
        // of SpaceBetween so the sphere/waveform don't drift as the transcript
        // grows. The transcript area owns its own scroll state with auto-
        // scroll-to-tail and a top/bottom fade mask for overflow indication.
        //
        // Transcript content sources:
        //   - transcriptMessages: last N chat messages (includes voice-intent
        //     traces rendered via MarkdownContent)
        //   - uiState.responseText: the in-flight streaming response for the
        //     current turn (a bubble that hasn't yet been finalized into a
        //     ChatMessage by the time the user sees it mid-stream)
        val transcriptScrollState = rememberScrollState()

        // Auto-scroll to the tail whenever: (a) a new message arrives in the
        // transcript, (b) the streaming responseText grows. Both conditions
        // fire the same smooth animateScrollTo so the user's eye never has
        // to chase token churn.
        LaunchedEffect(transcriptMessages.size, uiState.responseText.length) {
            transcriptScrollState.animateScrollTo(transcriptScrollState.maxValue)
        }

        // Vertical fade brush for the scroll area: top + bottom edges fade
        // into the surface so overflow is visually obvious without showing a
        // scrollbar. Applied via BlendMode.DstIn inside an offscreen
        // compositing layer so the gradient multiplies the alpha of the
        // text underneath rather than tinting the surface behind.
        val fadeBrush = remember {
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.08f to Color.Black,
                0.92f to Color.Black,
                1f to Color.Transparent,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 64.dp, bottom = 160.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Sphere — dominant share of the column. Compose's `weight()`
            // splits *remaining* space (after fixed-size children) among
            // weighted children, so 1.5f vs the response's 1f gives the
            // sphere ~60% of the available area — matching the old
            // `fillMaxHeight(0.6f)` look without drifting as the response
            // grows. weight(fill=true) keeps it from collapsing.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.5f),
                contentAlignment = Alignment.Center,
            ) {
                MorphingSphere(
                    modifier = Modifier.fillMaxSize(),
                    state = voiceStateToSphereState(uiState.state),
                    voiceAmplitude = uiState.amplitude,
                    voiceMode = true,
                )
            }

            VoiceWaveform(
                amplitude = uiState.amplitude,
                state = uiState.state,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp),
            )

            Spacer(Modifier.height(12.dp))

            // "You said" block — sits directly above the agent response so
            // the eye flows from the waveform straight down through the
            // transcribed text into the answer in one motion. The old top-
            // anchored chip forced the user to look up after stopping the
            // mic, then back down to read the response — splitting attention.
            // Left-aligned + small uppercase caption so it visually pairs
            // with the response below it like a mini chat thread.
            AnimatedVisibility(
                visible = !uiState.transcribedText.isNullOrBlank(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = "YOU",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = uiState.transcribedText.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Transcript area. Shows the last N chat messages in a compact
            // rolling list, plus the in-flight streaming responseText for
            // the current turn. Empty-state hint renders when BOTH the
            // transcript AND the live response are empty (first launch,
            // fresh voice session).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
            ) {
                val hasTranscript = transcriptMessages.isNotEmpty()
                val hasLiveResponse = uiState.responseText.isNotBlank()
                if (hasTranscript || hasLiveResponse) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(brush = fadeBrush, blendMode = BlendMode.DstIn)
                            }
                            .verticalScroll(transcriptScrollState)
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        transcriptMessages.forEach { msg ->
                            CompactTranscriptRow(msg)
                        }
                        // Live streaming response for the current turn —
                        // rendered as a trailing row so it sits below the
                        // committed history. Once the turn completes and
                        // the message is appended to chat history,
                        // responseText clears and this row collapses back
                        // into the transcript list naturally.
                        if (hasLiveResponse) {
                            StreamingResponseRow(uiState.responseText)
                        }
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
                        modifier = Modifier.weight(1f, fill = false),
                    )
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
        // Transcribing/Thinking → tap is a no-op; the state machine is busy
        //
        // The bug before was that Listening fell through to the else branch
        // which called onMicTap (= startListening) a second time instead of
        // stopping the in-progress recording. User ended up with a button
        // that visually said "Stop" but was actually wired to "Start."
        VoiceMicButton(
            uiState = uiState,
            onTap = {
                when (uiState.state) {
                    VoiceState.Listening -> {
                        try {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } catch (_: Exception) { /* ignore */ }
                        onMicRelease()
                    }
                    VoiceState.Speaking -> onInterrupt()
                    VoiceState.Transcribing, VoiceState.Thinking -> {
                        // Busy — ignore tap. Avoids double-stop / double-send races.
                    }
                    VoiceState.Idle, VoiceState.Error -> {
                        try {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } catch (_: Exception) { /* ignore */ }
                        onMicTap()
                    }
                }
            },
            onPress = {
                try {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                } catch (_: Exception) { /* ignore */ }
                onMicTap()
            },
            onRelease = {
                try {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                } catch (_: Exception) { /* ignore */ }
                onMicRelease()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
        )
    }
}

@Composable
private fun VoiceMicButton(
    uiState: VoiceUiState,
    onTap: () -> Unit,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        VoiceState.Error -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primary
    }

    val icon = when (uiState.state) {
        VoiceState.Listening -> Icons.Filled.Stop
        VoiceState.Transcribing, VoiceState.Thinking -> Icons.Filled.GraphicEq
        // Stop icon makes the "tap to interrupt TTS" affordance obvious;
        // VolumeUp looked decorative and users didn't try tapping it.
        VoiceState.Speaking -> Icons.Filled.Stop
        else -> Icons.Filled.Mic
    }

    val gestureModifier = when (uiState.interactionMode) {
        InteractionMode.HoldToTalk -> Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                onPress()
                waitForUpOrCancellation()
                onRelease()
            }
        }
        else -> Modifier.clickable { onTap() }
    }

    Surface(
        modifier = modifier
            .size((72 * pulseScale).dp)
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
                modifier = Modifier.size(32.dp),
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

private fun InteractionMode.label(): String = when (this) {
    InteractionMode.TapToTalk -> "Tap to talk"
    InteractionMode.HoldToTalk -> "Hold to talk"
    InteractionMode.Continuous -> "Continuous"
}

/**
 * Compact transcript row for voice mode. Rendering rules:
 *
 *   - `id.startsWith("voice-intent-")` → voice-action trace, rendered via
 *     [MarkdownContent] unbounded (these are the rich bridge-intent bubbles
 *     like "**Opened Chrome** `com.android.chrome` — exact match", which
 *     need the bold + inline code styling to read well).
 *   - `role == USER` → small italic caption "YOU" + body in bodySmall,
 *     two-line truncation.
 *   - `role == ASSISTANT` → caption "AGENT" + body in bodyMedium,
 *     four-line truncation.
 *   - `role == SYSTEM` → skipped entirely (voice mode is a conversation
 *     surface, not a system-message debug view).
 *
 * The caller is responsible for bounding the list length (voice mode
 * uses `takeLast(6)`).
 */
@Composable
private fun CompactTranscriptRow(message: ChatMessage) {
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
        when {
            isVoiceActionBubble -> MarkdownContent(
                content = message.content,
                textColor = MaterialTheme.colorScheme.onSurface,
            )
            message.role == MessageRole.USER -> Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            else -> Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Row for the in-flight streaming response — `uiState.responseText` that
 * hasn't yet been committed to chat history. Separate composable so it
 * can render slightly differently from committed [CompactTranscriptRow]
 * (larger type, onSurface instead of onSurfaceVariant) matching the
 * pre-transcript single-line "current response" visual weight, and so
 * streaming tokens accrete in place without the AnimatedContent flicker
 * we had before transcript mode.
 */
@Composable
private fun StreamingResponseRow(responseText: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "AGENT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = responseText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
