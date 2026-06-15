package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.purpleGlow
import com.hermesandroid.relay.ui.theme.relayMetadataStyle
import kotlinx.coroutines.delay

/**
 * What the single trailing slot of the input bar renders. The caller
 * derives the state — the bar never widens, it morphs:
 *
 * ```
 * !isStreaming && hasContent      -> SEND    // Send arrow, primary (Relay)
 * !isStreaming                    -> VOICE   // GraphicEq, primary
 * isStreaming && !hasContent      -> STOP    // Stop in a Danger-outlined circle
 * canSteer (gateway transport)    -> STEER   // Send glyph, tertiary (Cyan)
 * else                            -> QUEUE   // Send glyph + clock badge, tertiary
 * ```
 */
enum class ChatInputTrailing { SEND, VOICE, STOP, STEER, QUEUE }

/**
 * The minimal Telegram-style chat input bar — 3 elements, one trailing
 * button. Replaces ChatScreen's Row of attach / slash / OutlinedTextField /
 * Stop / smart-swap.
 *
 *  - "+" tap = file picker ([onAttach]); long-press = CommandPalette
 *    ([onLongPressAttach]) — the app's quiet-gesture idiom. The dedicated
 *    slash button is gone; typing "/" still surfaces InlineAutocomplete.
 *  - Pill [BasicTextField] (surfaceContainerHigh, hairline border, grows
 *    to 5 lines) instead of OutlinedTextField chrome.
 *  - ONE trailing slot morphing through [ChatInputTrailing] with
 *    [AnimatedContent] — Stop stops being a separate slot so the bar never
 *    widens during streaming.
 *  - Char counter as a tiny mono overline above the bar (Amber, Danger at
 *    the limit) only when length > [charLimit] - 200 — supportingText
 *    reflows the bar, the overline doesn't.
 *  - [caption] renders a single relayMetadataStyle line above the bar
 *    (steer/queue hinting during streaming-with-text); Cyan when the slot
 *    is STEER, muted otherwise. Null collapses the row.
 *  - Voice: GraphicEq glyph ("voice session", not "record"); when
 *    ![voiceReady] the button stays FULL alpha with a 6dp Amber dot badge
 *    ("needs setup" reads intentional, not broken) and the tap still goes
 *    to [onVoice] for the route-specific toast. [showVoiceHint] one-shot
 *    floats the "Live voice conversation" pill above the button for ~3s
 *    (DataStore flag owned by the caller, consumed via [onVoiceHintShown]).
 *  - [purpleGlow] on the trailing button (dark theme only) when it is an
 *    enabled SEND — the bar's one flourish, exactly as before.
 *
 * [onSend] fires for SEND, STEER, and QUEUE — the caller already encoded
 * the meaning in the state it passed; [onVoice]/[onStop] for theirs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    trailing: ChatInputTrailing,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onLongPressAttach: () -> Unit,
    charLimit: Int,
    caption: String?,
    voiceReady: Boolean,
    showVoiceHint: Boolean,
    onVoiceHintShown: () -> Unit,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Keep the last caption around so the AnimatedVisibility exit doesn't
    // flash an empty line while collapsing.
    var lastCaption by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(caption) {
        if (caption != null) lastCaption = caption
    }

    // One-shot voice hint. Consumed-flag locally so flipping the DataStore
    // flag (via onVoiceHintShown) can't restart-cancel the visible window;
    // the hide timer is keyed on visibility alone so trailing-state morphs
    // mid-delay don't strand the pill.
    var hintVisible by remember { mutableStateOf(false) }
    var hintConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(showVoiceHint, trailing) {
        if (showVoiceHint && !hintConsumed && trailing == ChatInputTrailing.VOICE) {
            hintConsumed = true
            hintVisible = true
            onVoiceHintShown()
        }
    }
    LaunchedEffect(hintVisible) {
        if (hintVisible) {
            delay(3_000)
            hintVisible = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Caption row — steer/queue hinting, single line, no buttons.
        AnimatedVisibility(visible = caption != null) {
            Text(
                text = caption ?: lastCaption.orEmpty(),
                style = relayMetadataStyle(),
                color = if (trailing == ChatInputTrailing.STEER) {
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        // Voice hint pill — floats above the trailing button.
        AnimatedVisibility(
            visible = hintVisible,
            modifier = Modifier
                .align(Alignment.End)
                .padding(end = 12.dp, bottom = 2.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                text = "Live voice conversation",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }

        // Char counter overline — same near-limit threshold as before, but
        // it no longer reflows the bar.
        if (value.length > charLimit - 200) {
            Text(
                text = "${value.length}/$charLimit",
                style = relayMetadataStyle(),
                color = if (value.length >= charLimit) RelayRefresh.Danger else RelayRefresh.Amber,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = 16.dp, bottom = 2.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // "+" — tap attaches, long-press opens the command palette.
            Box(
                modifier = Modifier
                    .padding(bottom = 2.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onAttach,
                        onLongClick = onLongPressAttach,
                        onLongClickLabel = "Browse commands",
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Attach file — hold for commands",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Pill field
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { if (it.length <= charLimit) onValueChange(it) },
                    maxLines = 5,
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                            if (value.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = RelayRefresh.Dim,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            // Trailing slot
            val glow = trailing == ChatInputTrailing.SEND && enabled && isDarkTheme
            Box(
                modifier = if (glow) {
                    Modifier.purpleGlow(radius = 24.dp, alpha = 0.35f, isDarkTheme = true)
                } else {
                    Modifier
                },
            ) {
                AnimatedContent(
                    targetState = trailing,
                    transitionSpec = {
                        (fadeIn(tween(150)) + scaleIn(initialScale = 0.8f))
                            .togetherWith(fadeOut(tween(100)))
                    },
                    label = "chatInputTrailing",
                ) { state ->
                    when (state) {
                        ChatInputTrailing.SEND -> IconButton(
                            onClick = onSend,
                            enabled = enabled,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send message",
                                tint = if (enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        ChatInputTrailing.VOICE -> Box {
                            IconButton(onClick = onVoice) {
                                Icon(
                                    imageVector = Icons.Filled.GraphicEq,
                                    contentDescription = if (voiceReady) "Start voice conversation"
                                        else "Voice conversation — setup needed",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            // "Needs setup" badge — full-alpha button + Amber
                            // dot instead of a half-dimmed broken-looking mic.
                            if (!voiceReady) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 8.dp, end = 8.dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(RelayRefresh.Amber),
                                )
                            }
                        }

                        ChatInputTrailing.STOP -> IconButton(onClick = onStop) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.error, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = "Stop streaming",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        ChatInputTrailing.STEER -> IconButton(
                            onClick = onSend,
                            enabled = enabled,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Steer the response",
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }

                        ChatInputTrailing.QUEUE -> IconButton(
                            onClick = onSend,
                            enabled = enabled,
                        ) {
                            Box {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Queue message",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                                Icon(
                                    imageVector = Icons.Filled.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 5.dp, y = (-3).dp)
                                        .size(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
