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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

        // Center column: sphere + transcript + response
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 64.dp, bottom = 160.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f),
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

            // weight(fill=false) lets this block take only what's left between
            // the waveform and the bottom mic padding; verticalScroll keeps long
            // agent responses from clipping off-screen. Transcript scrolls with
            // the response so layout stays simple — one scroll state, one column.
            val textScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(textScrollState)
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedContent(
                    targetState = uiState.transcribedText ?: "",
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                    label = "transcribed",
                ) { text ->
                    if (text.isNotBlank()) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                AnimatedContent(
                    targetState = uiState.responseText,
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                    label = "response",
                ) { text ->
                    if (text.isNotBlank()) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = stateHint(uiState.state),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
