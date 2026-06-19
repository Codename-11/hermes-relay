package com.hermesandroid.relay.ui.components

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import com.hermesandroid.relay.ui.theme.RelayRefresh
import kotlinx.coroutines.delay

// --- Text-flow tuning constants -------------------------------------------
//
// All time-based numbers stay inside the ranges WP-C1 prescribes so the
// "clean text flowing in and fading out" reads calm rather than frantic.

/** Soft word-wrap width for a flow line — keeps each buffer entry to ~one
 *  visual line so the bounded buffer maps cleanly to "≤6 lines". */
private const val FLOW_MAX_CHARS = 42

/** Bounded buffer ceiling. Older lines fade out so the buffer can never
 *  exceed this even when a turn streams faster than the dwell window. */
private const val FLOW_MAX_LINES = 6

/** How long a settled line lingers after it stops growing, before it begins
 *  fading. Inside the 2.5–4s band from the spec. */
private const val FLOW_DWELL_MS = 3_000L

private const val FLOW_FADE_IN_MS = 180
private const val FLOW_FADE_OUT_MS = 600

/** Buffer maintenance cadence. Cheap list bookkeeping only — it mutates
 *  observed state (and so triggers recomposition) only when something
 *  actually changes, so an idle clean mode does not churn the UI. */
private const val FLOW_TICK_MS = 80L

/**
 * One ephemeral line in the text flow.
 *
 * [text] and [visibility] are snapshot-observed so a growing tail or a
 * fade-out re-renders just that line. [settledAt]/[hiddenAt] are plain
 * bookkeeping read only by the maintenance loop, so they intentionally do
 * NOT trigger recomposition.
 *
 * [visibility] starts `currentState = false, targetState = true`; handing
 * that to `AnimatedVisibility(visibleState = …)` plays the enter transition
 * the first time the line is composed — the idiomatic "animate on appear".
 */
private class FlowLine(val key: Int, initialText: String) {
    var text by mutableStateOf(initialText)
    val visibility = MutableTransitionState(false).apply { targetState = true }

    /** Wall-clock millis at which the line stopped growing (null while it is
     *  still the active streaming tail). Starts the dwell countdown. */
    var settledAt: Long? = null

    /** Wall-clock millis at which the fade-out was requested. */
    var hiddenAt: Long? = null
}

/**
 * Split [text] into short, append-only flow segments.
 *
 * Explicit newlines hard-break; long paragraphs greedily soft-wrap at word
 * boundaries to [maxChars]. Because the source content only ever grows
 * (streaming appends), every segment except the last is final the moment the
 * next word/line exists — which is exactly what lets the caller treat the
 * last segment as the "growing tail" and everything before it as settled,
 * and key each line by its stable index.
 */
private fun segmentFlowLines(text: String, maxChars: Int): List<String> {
    if (text.isBlank()) return emptyList()
    val out = ArrayList<String>()
    for (rawLine in text.split('\n')) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val current = StringBuilder()
        for (word in line.split(' ')) {
            if (word.isEmpty()) continue
            val candidate = if (current.isEmpty()) word.length else current.length + 1 + word.length
            if (candidate > maxChars && current.isNotEmpty()) {
                out.add(current.toString())
                current.setLength(0)
                current.append(word)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
    }
    return out
}

/** Resolved motion/accessibility posture for clean mode. */
private data class CleanMotionState(
    /** OS animator scale is non-zero (i.e. system animations are ON). */
    val osAnimations: Boolean,
    /** TalkBack-style touch exploration is active — faded text is unreadable
     *  to it, so the text path must fall back to a static, announced mirror. */
    val touchExploration: Boolean,
)

@Composable
private fun rememberCleanMotionState(): CleanMotionState {
    val context = LocalContext.current
    // ANIMATOR_DURATION_SCALE == 0 is the platform "remove animations" / many
    // OEM "reduce motion" toggles. Read once on entry; a mid-mode toggle is
    // rare and recovered by leaving + re-entering the mode.
    val osAnimations = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }.getOrDefault(true)
    }
    val a11y = remember {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var touchExploration by remember {
        mutableStateOf(a11y?.isTouchExplorationEnabled == true)
    }
    DisposableEffect(a11y) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            touchExploration = enabled
        }
        a11y?.addTouchExplorationStateChangeListener(listener)
        onDispose { a11y?.removeTouchExplorationStateChangeListener(listener) }
    }
    return CleanMotionState(osAnimations = osAnimations, touchExploration = touchExploration)
}

/**
 * Ephemeral, themed text flow bound to the agent's streaming reply.
 *
 * New segments materialize with `fadeIn + slideInVertically`; a settled line
 * dwells ~[FLOW_DWELL_MS], then `fadeOut`s and is **removed from the buffer**
 * (it leaves the composition tree, so it stops composing — not merely
 * alpha-0). The still-growing tail never fades; its dwell starts only once
 * [streaming] flips false. The buffer is hard-capped at [FLOW_MAX_LINES].
 *
 * Accessibility: when [motionEnabled] is false (animations disabled, OS
 * reduce-motion, or TalkBack touch exploration) the flow renders the recent
 * lines **statically** inside a polite live region — never gating the
 * conversation on animation. Even on the animated path a visually-hidden
 * polite mirror carries the readable words, since faded glyphs are
 * unreadable to assistive tech.
 *
 * @param content the last assistant message's (streaming) content.
 * @param streaming whether that message is still growing this turn.
 * @param messageId stable id of the bound message; a new id resets the buffer.
 */
@Composable
fun AgentTextFlow(
    content: String,
    streaming: Boolean,
    messageId: String?,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val flowStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val flowColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Readable, non-faded mirror of the visible tail — used as the live-region
    // text on both paths so assistive tech hears the words.
    val mirrorText = remember(content) {
        segmentFlowLines(content, FLOW_MAX_CHARS).takeLast(FLOW_MAX_LINES).joinToString(" ")
    }

    // --- Static / reduced-motion path -------------------------------------
    if (!motionEnabled) {
        val staticLines = remember(content) {
            segmentFlowLines(content, FLOW_MAX_CHARS).takeLast(FLOW_MAX_LINES)
        }
        // No contentDescription — the merged child Text content IS the
        // readable content; liveRegion announces it on change.
        Column(
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.Bottom,
        ) {
            staticLines.forEach { line ->
                Text(
                    text = line,
                    style = flowStyle,
                    color = flowColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        return
    }

    // --- Animated path ----------------------------------------------------
    val flowLines = remember(messageId) { mutableStateListOf<FlowLine>() }
    val currentContent by rememberUpdatedState(content)
    val currentStreaming by rememberUpdatedState(streaming)

    LaunchedEffect(messageId) {
        flowLines.clear()
        // High-water mark: the largest segment index ever materialized. Guards
        // against re-adding a line that already faded out and was removed —
        // otherwise a completed message would replay its fade forever.
        var maxKeyAdded = -1
        while (true) {
            val text = currentContent
            val isStreamingNow = currentStreaming
            val now = System.currentTimeMillis()
            val segs = segmentFlowLines(text, FLOW_MAX_CHARS)
            // While streaming the last segment is the growing tail; once the
            // turn ends nothing is "growing", so even the last line settles.
            val tailIndex = if (isStreamingNow) segs.lastIndex else -1
            // Never materialize lines that have already scrolled out of the
            // window (matters when re-entering a long, already-complete turn).
            val creationFloor = (segs.size - FLOW_MAX_LINES).coerceAtLeast(0)

            // Stop once the turn is over and everything has flowed out — the
            // message is immutable from here, so the loop has nothing left to do.
            if (!isStreamingNow && flowLines.isEmpty() && maxKeyAdded >= segs.lastIndex) {
                return@LaunchedEffect
            }

            // 1. Add new lines (only indices never seen before) / update the
            //    still-growing tail.
            segs.forEachIndexed { i, s ->
                val existing = flowLines.firstOrNull { it.key == i }
                if (existing == null) {
                    if (i > maxKeyAdded && i >= creationFloor) {
                        flowLines.add(FlowLine(key = i, initialText = s))
                        maxKeyAdded = i
                    }
                } else if (existing.text != s) {
                    existing.text = s
                }
            }

            // 2. Settle every line that is no longer the active tail.
            flowLines.forEach { line ->
                if (line.key != tailIndex && line.settledAt == null) {
                    line.settledAt = now
                }
            }

            // 3. Enforce the hard cap: expire the oldest intended-visible lines
            //    immediately (lowest key first — never the tail).
            val intendedVisible = flowLines.filter { it.visibility.targetState }
            if (intendedVisible.size > FLOW_MAX_LINES) {
                intendedVisible
                    .sortedBy { it.key }
                    .take(intendedVisible.size - FLOW_MAX_LINES)
                    .forEach { line -> line.settledAt = now - FLOW_DWELL_MS - 1 }
            }

            // 4. Dwell expiry → request fade-out.
            flowLines.forEach { line ->
                val settledAt = line.settledAt
                if (line.visibility.targetState && settledAt != null &&
                    now - settledAt >= FLOW_DWELL_MS
                ) {
                    line.visibility.targetState = false
                    line.hiddenAt = now
                }
            }

            // 5. Remove once the fade-out has fully played — the node then
            //    leaves composition entirely.
            flowLines.removeAll { line ->
                val v = line.visibility
                !v.targetState && v.isIdle && !v.currentState
            }

            delay(FLOW_TICK_MS)
        }
    }

    Box(modifier = modifier) {
        // Visually-hidden, readable, politely-announced mirror. Present even
        // with motion on, so non-touch assistive tech still receives the words
        // the faded glyphs can't convey. The Text's own content is its
        // semantics text, so liveRegion alone announces it on change.
        Text(
            text = mirrorText,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 1.dp)
                .alpha(0f)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = flowStyle,
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            flowLines.forEach { line ->
                androidx.compose.runtime.key(line.key) {
                    AnimatedVisibility(
                        visibleState = line.visibility,
                        enter = fadeIn(tween(FLOW_FADE_IN_MS)) +
                            slideInVertically(tween(FLOW_FADE_IN_MS)) { it / 6 },
                        exit = fadeOut(tween(FLOW_FADE_OUT_MS)),
                    ) {
                        Text(
                            text = line.text,
                            style = flowStyle,
                            color = flowColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            // The visible glyphs fade; the mirror above owns
                            // accessibility, so keep AT off these duplicates.
                            modifier = Modifier
                                .fillMaxWidth()
                                .clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
    }
}

/**
 * Thin single-line composer for clean mode.
 *
 * Deliberately stripped: no model/effort pills, no attachments, no slash
 * palette — just a pill field plus a send affordance, calling [onSend] with
 * the same [com.hermesandroid.relay.viewmodel.ChatViewModel.sendMessage]
 * contract the full composer uses. Internal text state is UI-local.
 */
@Composable
private fun CleanModeComposer(
    enabled: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    val canSend = enabled && text.isNotBlank()
    val submit = {
        val trimmed = text.trim()
        if (enabled && trimmed.isNotEmpty()) {
            onSend(trimmed)
            text = ""
        }
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .padding(vertical = 8.dp),
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text(
                                text = "Message",
                                style = MaterialTheme.typography.bodyLarge,
                                color = RelayRefresh.Dim,
                            )
                        }
                        inner()
                    }
                },
            )
            IconButton(
                onClick = submit,
                enabled = canSend,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                )
            }
        }
    }
}

/**
 * Clean text-flow chat mode — a full-screen, minimalist third presentation of
 * the agent surface (alongside normal chat and the voice overlay).
 *
 * Centered morphing sphere, a calm themed text flow ([AgentTextFlow]) instead
 * of a persistent transcript, and a thin composer. Mirrors the voice overlay's
 * centered-sphere + bottom-content skeleton (`VoiceModeOverlay.kt:262-280`).
 *
 * Exit is an **explicit control** (top-corner dismiss + system back) — never
 * any-tap, because the in-mode composer needs taps. All mode state lives in
 * the caller as plain UI-local state; this is a presentation over the same
 * conversation, not new ViewModel state.
 *
 * Honors [animationEnabled], OS reduce-motion, and TalkBack: the sphere
 * renders a static frame and the text stays readable + announced when motion
 * is suppressed.
 *
 * The avatar is rendered through the [LocalAgentAvatar] seam (WP-C2), so clean
 * mode gets future "pets" for free alongside chat and the voice overlay.
 */
@Composable
fun CleanChatMode(
    messages: List<ChatMessage>,
    isStreaming: Boolean,
    sphereState: SphereState,
    streamingIntensity: Float,
    toolCallBurst: Float,
    animationEnabled: Boolean,
    enabled: Boolean,
    onSend: (String) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = rememberCleanMotionState()
    val sphereAnimated = animationEnabled && motion.osAnimations
    // Faded text is unreadable to touch exploration, so the text path goes
    // static (readable + announced) whenever TalkBack is exploring.
    val textMotionEnabled = sphereAnimated && !motion.touchExploration

    val lastAssistant = remember(messages) {
        messages.lastOrNull { it.role == MessageRole.ASSISTANT }
    }
    val flowContent = lastAssistant?.content.orEmpty()
    val flowStreaming = lastAssistant?.isStreaming == true && isStreaming

    BackHandler(enabled = true) { onExit() }

    val sphereDescription = remember(sphereState) {
        "Agent ${sphereState.name.lowercase()}"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Opaque so the chat underneath is fully hidden — this is a mode,
            // not a translucent overlay.
            .background(RelayRefresh.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp),
        ) {
            // Explicit dismiss — the only way out besides system back.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onExit) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Exit clean mode",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Centered sphere — takes the slack so the flow + composer keep a
            // stable bottom anchor as lines come and go.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = sphereDescription },
                ) {
                    LocalAgentAvatar.current.Render(
                        state = AvatarRenderState(
                            state = sphereState,
                            intensity = streamingIntensity,
                            toolCallBurst = toolCallBurst,
                            // Pin to a still frame when motion is suppressed.
                            paused = !sphereAnimated,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            AgentTextFlow(
                content = flowContent,
                streaming = flowStreaming,
                messageId = lastAssistant?.id,
                motionEnabled = textMotionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .heightIn(min = 96.dp)
                    .padding(bottom = 12.dp),
            )

            CleanModeComposer(
                enabled = enabled,
                onSend = onSend,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}
