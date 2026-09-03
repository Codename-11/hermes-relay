package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.BusyMessageAction
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.appearanceComposerShape
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
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
 * canCorrect (gateway transport)  -> STEER   // Send glyph, tertiary (Cyan)
 * else                            -> QUEUE   // Send glyph + clock badge, tertiary
 * ```
 */
enum class ChatInputTrailing { SEND, VOICE, STOP, STEER, QUEUE }

internal const val CHAT_INPUT_FIELD_TEST_TAG = "chat-input-field"

data class ChatInputPickerOption(
    val label: String,
    val value: String?,
    val provider: String? = null,
    val secondary: String? = null,
    val group: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

data class ChatInputPickerControl(
    val value: String,
    val contentDescription: String,
    val options: List<ChatInputPickerOption>,
    val enabled: Boolean = true,
)

/**
 * The minimal Telegram-style chat input bar — 3 elements, one trailing
 * button. Replaces ChatScreen's Row of attach / slash / OutlinedTextField /
 * Stop / smart-swap.
 *
 *  - "+" tap opens the attach menu — Photos ([onAttachPhotos], the modern
 *    permissionless Photo Picker), Files ([onAttachFiles], arbitrary types),
 *    Camera ([onAttachCamera], capture), and Paste image ([onPasteImage],
 *    clipboard). Long-press = CommandPalette ([onLongPressAttach]) — the app's
 *    quiet-gesture idiom. The dedicated slash button is gone; typing "/" still
 *    surfaces InlineAutocomplete.
 *  - Pill [BasicTextField] (surfaceContainerHigh, hairline border, grows
 *    to 5 lines) instead of OutlinedTextField chrome.
 *  - ONE trailing slot morphing through [ChatInputTrailing] with
 *    [AnimatedContent] — Stop stops being a separate slot so the bar never
 *    widens during streaming.
 *  - Char counter as a tiny mono overline above the bar (Amber, Danger at
 *    the limit) only when length > [charLimit] - 200 — supportingText
 *    reflows the bar, the overline doesn't.
 *  - [caption] renders a single relayMetadataStyle line above the bar
 *    (correct/queue hinting during streaming-with-text); Cyan when the slot
 *    is STEER, muted otherwise. Null collapses the row.
 *  - Voice: GraphicEq glyph ("voice session", not "record"); when
 *    ![voiceReady] the button stays FULL alpha with a 6dp Amber dot badge
 *    ("needs setup" reads intentional, not broken) and the tap still goes
 *    to [onVoice] for the route-specific toast. [showVoiceHint] one-shot
 *    floats the "Live voice conversation" pill above the button for ~3s
 *    (DataStore flag owned by the caller, consumed via [onVoiceHintShown]).
 *  - [purpleGlow] on the trailing button (dark theme only) when it is an
 *    enabled SEND — the bar's one flourish, exactly as before.
 *  - [topContent] lets an active mode share the composer's outer surface.
 *    Conversation voice uses it for the dock and sets [suppressVoiceTrailing]
 *    so the dock remains the only idle/stop voice action; typed send/steer/
 *    queue actions still render normally.
 *  - [topContentVisible] expands or collapses that shared content from the
 *    composer edge instead of abruptly replacing the input layout.
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
    onAttachPhotos: () -> Unit,
    onAttachFiles: () -> Unit,
    onAttachCamera: () -> Unit,
    onPasteImage: () -> Unit,
    onLongPressAttach: () -> Unit,
    charLimit: Int,
    caption: String?,
    voiceReady: Boolean,
    showVoiceHint: Boolean,
    onVoiceHintShown: () -> Unit,
    isDarkTheme: Boolean,
    modelControl: ChatInputPickerControl? = null,
    onModelOptionSelected: (ChatInputPickerOption) -> Unit = {},
    onModelPickerClick: (() -> Unit)? = null,
    effortControl: ChatInputPickerControl? = null,
    onEffortPickerClick: (() -> Unit)? = null,
    topContent: (@Composable () -> Unit)? = null,
    topContentVisible: Boolean = topContent != null,
    suppressVoiceTrailing: Boolean = false,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
    enabled: Boolean = true,
    submitEnabled: Boolean = true,
    physicalEnterSends: Boolean = true,
    largePasteThreshold: Int? = null,
    onLargePaste: (String) -> Unit = {},
    busyAction: BusyMessageAction? = null,
    correctionAvailable: Boolean = true,
    onBusyActionChange: (BusyMessageAction) -> Unit = {},
) {
    val canSubmit = enabled && submitEnabled && trailing in setOf(
        ChatInputTrailing.SEND,
        ChatInputTrailing.STEER,
        ChatInputTrailing.QUEUE,
    )

    // Enter only means "send" when a physical keyboard is attached (see
    // the key handler below). Read the configuration here, in the composable
    // scope, and capture it for the non-composable onPreviewKeyEvent lambda.
    val keyboardAttached =
        LocalConfiguration.current.keyboard !=
            android.content.res.Configuration.KEYBOARD_NOKEYS

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
        // Correction and queueing are materially different actions. Keep the
        // explanation, but lead with a visible state pill so the distinction
        // does not depend on the trailing icon or accent color alone.
        AnimatedVisibility(visible = caption != null && busyAction == null) {
            val detail = caption ?: lastCaption.orEmpty()
            val actionLabel = when (trailing) {
                ChatInputTrailing.STEER -> stringResource(R.string.chat_input_steer_response)
                ChatInputTrailing.QUEUE -> stringResource(R.string.chat_input_queue_message)
                else -> null
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = listOfNotNull(actionLabel, detail)
                            .joinToString(separator = ". ")
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (actionLabel != null) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (trailing == ChatInputTrailing.STEER) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (trailing == ChatInputTrailing.STEER) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text(
                    text = detail,
                    style = relayMetadataStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                text = stringResource(R.string.chat_input_live_voice_hint),
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

        ChatComposerLayers(
            action = busyAction,
            onActionChange = onBusyActionChange,
            correctionAvailable = correctionAvailable,
            onStop = onStop.takeUnless { trailing == ChatInputTrailing.STOP },
        ) {
        Surface(
            shape = appearanceComposerShape(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .then(surfaceModifier),
        ) {
            Column {
                if (topContent != null) {
                    AnimatedVisibility(
                        visible = topContentVisible,
                        enter = expandVertically(
                            animationSpec = tween(240),
                            expandFrom = Alignment.Bottom,
                        ) + fadeIn(tween(180)),
                        exit = shrinkVertically(
                            animationSpec = tween(180),
                            shrinkTowards = Alignment.Bottom,
                        ) + fadeOut(tween(120)),
                    ) {
                        Column {
                            topContent()
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { updated ->
                            val converted = largePasteThreshold
                                ?.let { threshold -> detectLargeTextInsertion(value, updated, threshold) }
                            if (converted != null) {
                                onValueChange(converted.remainingText)
                                onLargePaste(converted.insertedText)
                            } else if (updated.length <= charLimit) {
                                onValueChange(updated)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 30.dp)
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                            // Keep directional keys inside the editor. Compose's
                            // BasicTextField owns normal caret/selection movement;
                            // cancelling focus traversal prevents a boundary arrow
                            // from jumping to a neighboring composer control.
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            }
                            .onPreviewKeyEvent { event ->
                                val native = event.nativeKeyEvent
                                val isEnter = native.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                                    native.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                                // Enter only means "send" when a physical keyboard is
                                // attached. IME-dispatched Enter (commitText or a
                                // synthesized KEYCODE_ENTER) must always fall through
                                // so the soft keyboard's return key inserts a newline
                                // instead of sending (issue #367). Key events alone
                                // cannot distinguish physical vs IME origin — deviceId
                                // is 0 or -1 depending on the IME — so gate on the
                                // hardware keyboard configuration (read above).
                                val isSubmitShortcut = native.isCtrlPressed || native.isMetaPressed
                                if (native.action != android.view.KeyEvent.ACTION_DOWN || !isEnter) {
                                    false
                                } else if (isSubmitShortcut || (keyboardAttached && physicalEnterSends && !native.isShiftPressed)) {
                                    if (canSubmit) onSend()
                                    true
                                } else {
                                    // Shift+Enter always inserts a newline. When
                                    // Enter is configured for newlines, the plain
                                    // key also stays owned by BasicTextField.
                                    false
                                }
                            }
                            .testTag(CHAT_INPUT_FIELD_TEST_TAG),
                        maxLines = 5,
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            // The field is multiline and already has a dedicated
                            // send button. Leave the software keyboard action as
                            // Return; hardware Enter remains handled above.
                            imeAction = ImeAction.Default,
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth()) {
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // "+" tap opens the attach menu (Photos / Files / Camera /
                    // Paste image); long-press opens the command palette.
                    var attachMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { attachMenuExpanded = true },
                                    onClickLabel = stringResource(R.string.chat_input_add_attachment),
                                    onLongClick = onLongPressAttach,
                                    onLongClickLabel = stringResource(R.string.chat_input_browse_commands),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.chat_input_add_attachment_hold),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = attachMenuExpanded,
                            onDismissRequest = { attachMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_input_photos)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                                },
                                onClick = {
                                    attachMenuExpanded = false
                                    onAttachPhotos()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_input_files)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.InsertDriveFile, contentDescription = null)
                                },
                                onClick = {
                                    attachMenuExpanded = false
                                    onAttachFiles()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_input_camera)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                                },
                                onClick = {
                                    attachMenuExpanded = false
                                    onAttachCamera()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_input_paste_image)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                                },
                                onClick = {
                                    attachMenuExpanded = false
                                    onPasteImage()
                                },
                            )
                        }
                    }

                    if (modelControl != null) {
                        ChatInputPickerChip(
                            control = modelControl,
                            onSelect = onModelOptionSelected,
                            modifier = Modifier.widthIn(max = 126.dp),
                            onClickOverride = onModelPickerClick,
                        )
                    }

                    if (effortControl != null) {
                        ChatInputPickerChip(
                            control = effortControl,
                            onSelect = {},
                            modifier = Modifier.widthIn(max = 104.dp),
                            onClickOverride = onEffortPickerClick,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

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
                                    enabled = canSubmit,
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = stringResource(R.string.chat_input_send_message),
                                        tint = if (canSubmit) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                ChatInputTrailing.VOICE -> {
                                    if (!suppressVoiceTrailing) {
                                        Box {
                                            IconButton(onClick = onVoice) {
                                                Icon(
                                                    imageVector = Icons.Filled.GraphicEq,
                                                    contentDescription = if (voiceReady) stringResource(R.string.chat_input_start_voice)
                                                        else stringResource(R.string.chat_input_voice_setup_needed),
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
                                    }
                                }

                                ChatInputTrailing.STOP -> {
                                    if (!suppressVoiceTrailing) {
                                        IconButton(onClick = onStop) {
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .border(1.dp, MaterialTheme.colorScheme.error, CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Stop,
                                                    contentDescription = stringResource(R.string.chat_input_stop_streaming),
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                ChatInputTrailing.STEER -> IconButton(
                                    onClick = onSend,
                                    enabled = canSubmit,
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = stringResource(R.string.chat_input_steer_response),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                }

                                ChatInputTrailing.QUEUE -> IconButton(
                                    onClick = onSend,
                                    enabled = canSubmit,
                                ) {
                                    Box {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = stringResource(R.string.chat_input_queue_message),
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
    }
}
}
}

internal data class LargeTextInsertion(
    val insertedText: String,
    val remainingText: String,
)

/** Finds one large contiguous edit without requiring clipboard access or retaining clipboard data. */
internal fun detectLargeTextInsertion(
    previous: String,
    updated: String,
    threshold: Int,
): LargeTextInsertion? {
    if (threshold <= 0 || updated == previous) return null
    val prefixLength = previous.commonPrefixWith(updated).length
    val maxSuffixLength = minOf(
        previous.length - prefixLength,
        updated.length - prefixLength,
    )
    var suffixLength = 0
    while (
        suffixLength < maxSuffixLength &&
        previous[previous.lastIndex - suffixLength] == updated[updated.lastIndex - suffixLength]
    ) {
        suffixLength++
    }
    val insertedEnd = updated.length - suffixLength
    val inserted = updated.substring(prefixLength, insertedEnd)
    if (inserted.length < threshold) return null
    return LargeTextInsertion(
        insertedText = inserted,
        remainingText = updated.removeRange(prefixLength, insertedEnd),
    )
}

@Composable
private fun ChatInputPickerChip(
    control: ChatInputPickerControl,
    onSelect: (ChatInputPickerOption) -> Unit,
    modifier: Modifier = Modifier,
    // When set, tapping the chip opens this instead of the inline dropdown —
    // used by the model chip to open the full searchable ModelPickerSheet.
    onClickOverride: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = control.enabled && control.options.isNotEmpty()
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }

    Box(modifier = modifier) {
        Surface(
            shape = appearanceRoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.32f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clip(appearanceRoundedCornerShape(12.dp))
                .clickable(enabled = enabled) {
                    if (onClickOverride != null) onClickOverride() else expanded = true
                },
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = control.value,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = control.contentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            var lastGroup: String? = null
            control.options.forEachIndexed { index, option ->
                val group = option.group?.takeIf { it.isNotBlank() }
                if (group != null && group != lastGroup) {
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    Text(
                        text = group,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    lastGroup = group
                }
                DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.widthIn(max = 280.dp)) {
                            Text(
                                text = option.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!option.secondary.isNullOrBlank()) {
                                Text(
                                    text = option.secondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    enabled = option.enabled,
                    leadingIcon = if (option.selected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
