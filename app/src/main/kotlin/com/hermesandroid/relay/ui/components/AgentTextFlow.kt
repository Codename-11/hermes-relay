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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.RelayRefresh

// --- Text-flow tuning constants -------------------------------------------
//
// All time-based numbers stay inside the ranges WP-C1 prescribes so the
// "clean text flowing in and fading out" reads calm rather than frantic.

/** Soft word-wrap width for a flow line — keeps each buffer entry to ~one
 *  visual line so the bounded buffer maps cleanly to "≤6 lines". */
private const val FLOW_MAX_CHARS = 42

/** Soft-wrap target only — the visible buffer is now bounded by the
 *  scrollable viewport height + scroll, not a hard line count. */
private const val FLOW_MAX_LINES = 6

/** Memory ceiling for the persistent line buffer. Lines past this (already
 *  scrolled well above the faded top edge) are dropped silently so a very long
 *  turn can't grow the list without bound. */
private const val FLOW_BUFFER_MAX = 80

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

/**
 * Soft fade on the TOP edge so lines that scroll up dissolve cleanly into the
 * background instead of hard-clipping — the "slides up and clears" look — while
 * the avatar above stays unobstructed. Renders the content into an offscreen
 * layer and masks the top [fade] dp with a transparent->opaque gradient.
 */
private fun Modifier.topFadeEdge(fade: Dp = 28.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fadePx = fade.toPx().coerceAtMost(size.height)
        if (fadePx <= 0f) return@drawWithContent
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                (fadePx / size.height) to Color.Black,
            ),
            blendMode = BlendMode.DstIn,
        )
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
            segmentFlowLines(content, FLOW_MAX_CHARS).takeLast(FLOW_BUFFER_MAX)
        }
        val staticScroll = rememberScrollState()
        // Pin the latest line to the bottom of the bounded viewport.
        LaunchedEffect(staticLines.size) { staticScroll.scrollTo(staticScroll.maxValue) }
        // No contentDescription — the merged child Text content IS the readable
        // content; liveRegion announces it on change. Lines persist + scroll
        // (bounded + top-faded like the animated path) — they never vanish.
        Column(
            modifier = modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                // Fade the top edge ONLY when there's content scrolled above it —
                // a message that fits shows its first line crisply (no cut-off look).
                .topFadeEdge(fade = if (staticScroll.canScrollBackward) 28.dp else 0.dp)
                .verticalScroll(staticScroll),
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

    LaunchedEffect(messageId) {
        flowLines.clear()
        // Largest segment index ever materialized — guards against re-adding a
        // line that was dropped from the front by the memory cap.
        var maxKeyAdded = -1
        var lastText: String? = null
        while (true) {
            val text = currentContent
            // Re-diff only when the transcript changed, so an idle clean mode
            // (no streaming, no new turn) doesn't churn. We never permanently
            // exit: a new turn appended to the transcript must still slide in.
            if (text != lastText) {
                lastText = text
                val segs = segmentFlowLines(text, FLOW_MAX_CHARS)
                // Add new lines (they slide in); update a changed tail in place.
                // Lines PERSIST — older ones simply scroll up within the bounded,
                // scrollable viewport and dissolve at the top fade edge.
                segs.forEachIndexed { i, s ->
                    val existing = flowLines.firstOrNull { it.key == i }
                    if (existing == null) {
                        if (i > maxKeyAdded) {
                            flowLines.add(FlowLine(key = i, initialText = s))
                            maxKeyAdded = i
                        }
                    } else if (existing.text != s) {
                        existing.text = s
                    }
                }
                // Memory guard: drop the oldest lines once well past the viewport.
                while (flowLines.size > FLOW_BUFFER_MAX) flowLines.removeAt(0)
            }
            delay(FLOW_TICK_MS)
        }
    }

    val scrollState = rememberScrollState()
    // Pin the latest line to the bottom as content streams in / lines slide up.
    LaunchedEffect(flowLines.size, flowLines.lastOrNull()?.text) {
        scrollState.scrollTo(scrollState.maxValue)
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
                .fillMaxWidth()
                // Fade the top edge ONLY when content is scrolled above it, so a
                // reply that fits the viewport shows its first line crisply.
                .topFadeEdge(fade = if (scrollState.canScrollBackward) 28.dp else 0.dp)
                .verticalScroll(scrollState),
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
                                text = stringResource(R.string.agent_text_placeholder),
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
                    contentDescription = stringResource(R.string.agent_text_send_cd),
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
    // Clean mode shows the recent CONVERSATION (not just the last reply) as one
    // faded, scrollable flow, so scrolling up brings history into view. The flow
    // is append-only across turns; user turns get a subtle "›" so the
    // back-and-forth stays legible. How far back it retains is bounded by the
    // flow's line buffer (FLOW_BUFFER_MAX).
    val flowContent = remember(messages) {
        messages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .joinToString("\n\n") { msg ->
                val body = msg.content.trim()
                if (msg.role == MessageRole.USER) "› $body" else body
            }
    }
    // Stable per-conversation key so the flow buffer accumulates across turns and
    // resets only on a new conversation (the oldest message's id changes).
    val conversationKey = messages.firstOrNull()?.id
    val flowStreaming = lastAssistant?.isStreaming == true && isStreaming
    // The sphere + text are a vertically-centered group (equal spacers above and
    // below). The sphere is a fixed size so the group grows via the TEXT: a short
    // reply sits centered, and as the reply lengthens the centered group gets
    // taller — sliding the sphere up toward the top third while the text fills
    // down toward the composer.
    val sphereHeight = (LocalConfiguration.current.screenHeightDp * 0.34f).dp
    val maxFlowHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp

    BackHandler(enabled = true) { onExit() }

    val sphereDescription = remember(sphereState) {
        "${stringResource(R.string.agent_text_sphere_desc)} ${sphereState.name.lowercase()}"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Opaque so the chat underneath is fully hidden — this is a mode,
            // not a translucent overlay.
            .background(RelayRefresh.Background)
            // Consume any pointer event the children (composer, exit button, text
            // scroll) didn't handle, so stray taps/swipes in the empty areas don't
            // fall through to the chat + session drawer behind this mode. Children
            // run leaf-first on the same Main pass, so this only catches the gaps
            // (mirrors the voice overlay's focus-mode scrim).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
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
                        contentDescription = stringResource(R.string.agent_text_exit_clean),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Flexible top spacer — with the bottom one it vertically centers the
            // sphere + text group; as the text grows the spacers yield and the
            // sphere rises toward the top third.
            Spacer(modifier = Modifier.weight(1f))

            // Bounded, centered sphere — a fixed size so the group grows via the
            // text, sliding the sphere upward as the conversation lengthens.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sphereHeight),
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
                messageId = conversationKey,
                motionEnabled = textMotionEnabled,
                // Content-sized reading area (capped ~half the screen) directly
                // below the sphere — no gap between them. Grows + scrolls with the
                // reply, which is what lifts the centered group (and the sphere).
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .heightIn(min = 96.dp, max = maxFlowHeight)
                    .padding(bottom = 12.dp),
            )

            // Flexible bottom spacer — balances the top one to keep the
            // sphere + text group vertically centered.
            Spacer(modifier = Modifier.weight(1f))

            CleanModeComposer(
                enabled = enabled,
                onSend = onSend,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}
