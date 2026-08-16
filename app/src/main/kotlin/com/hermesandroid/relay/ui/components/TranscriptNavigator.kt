package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.theme.LocalBrand

@Immutable
data class TranscriptMatch(
    val messageUiKey: String,
    val messageIndex: Int,
    val start: Int,
    val endExclusive: Int,
)

@Immutable
data class TranscriptTurnMarker(
    val messageUiKey: String,
    val messageIndex: Int,
    val turnNumber: Int,
    val label: String,
    val matchCount: Int,
    val containsActiveMatch: Boolean,
)

@Immutable
data class TranscriptNavigationModel(
    val matches: List<TranscriptMatch>,
    val turns: List<TranscriptTurnMarker>,
)

/**
 * User-facing copy is injectable so the component remains reusable and its
 * eventual ChatScreen integration can supply localized resources without
 * coupling the pure navigation model to Android resources.
 */
@Immutable
data class TranscriptNavigatorStrings(
    val searchPlaceholder: String = "Search conversation",
    val previousMatch: String = "Previous match",
    val nextMatch: String = "Next match",
    val closeSearch: String = "Close search",
    val noResults: String = "No results",
    val resultCount: (current: Int, total: Int) -> String = { current, total -> "$current of $total" },
    val resultCountDescription: (current: Int, total: Int) -> String = { current, total ->
        if (total == 0) "No search results" else "Search result $current of $total"
    },
    val turnDescription: (turn: Int, label: String, matches: Int) -> String = { turn, label, matches ->
        val suffix = if (matches == 1) ", 1 match" else if (matches > 1) ", $matches matches" else ""
        "Jump to turn $turn: $label$suffix"
    },
)

object TranscriptNavigatorTestTags {
    const val Query = "transcript-search-query"
    const val ResultCount = "transcript-search-result-count"
    const val Previous = "transcript-search-previous"
    const val Next = "transcript-search-next"
    const val Close = "transcript-search-close"
    const val TurnPrefix = "transcript-turn-"
}

/**
 * Compact, self-contained find bar plus prompt rail for an active transcript.
 * All jump callbacks use [ChatMessage.uiKey], the same stable identity used by
 * Chat's LazyColumn, so a history reconciliation cannot invalidate a target.
 */
@Composable
fun TranscriptSearchNavigator(
    messages: List<ChatMessage>,
    onJumpToMessage: (messageUiKey: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
    strings: TranscriptNavigatorStrings = TranscriptNavigatorStrings(),
) {
    val brand = LocalBrand.current
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var activeMatchIndex by rememberSaveable { mutableIntStateOf(0) }
    val model = remember(messages, query, activeMatchIndex) {
        buildTranscriptNavigationModel(messages, query, activeMatchIndex)
    }
    val normalizedActiveIndex = activeMatchIndex.coerceIn(0, (model.matches.size - 1).coerceAtLeast(0))
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    fun selectMatch(index: Int) {
        if (model.matches.isEmpty()) return
        val normalized = Math.floorMod(index, model.matches.size)
        activeMatchIndex = normalized
        onJumpToMessage(model.matches[normalized].messageUiKey)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(brand.navy)
            .border(1.dp, brand.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(brand.surfaceLow)
                    .border(1.dp, brand.line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = brand.muted,
                    modifier = Modifier.size(18.dp),
                )
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        activeMatchIndex = 0
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .testTag(TranscriptNavigatorTestTags.Query),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = brand.ink),
                    cursorBrush = SolidColor(brand.relay),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { selectMatch(normalizedActiveIndex) }),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    text = strings.searchPlaceholder,
                                    color = brand.dim,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            val current = if (model.matches.isEmpty()) 0 else normalizedActiveIndex + 1
            Text(
                text = if (model.matches.isEmpty() && query.isNotBlank()) {
                    strings.noResults
                } else {
                    strings.resultCount(current, model.matches.size)
                },
                color = brand.muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier
                    .testTag(TranscriptNavigatorTestTags.ResultCount)
                    .semantics {
                        contentDescription = strings.resultCountDescription(current, model.matches.size)
                    },
            )
            IconButton(
                onClick = { selectMatch(normalizedActiveIndex - 1) },
                enabled = model.matches.isNotEmpty(),
                modifier = Modifier.testTag(TranscriptNavigatorTestTags.Previous),
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, strings.previousMatch, tint = brand.ink)
            }
            IconButton(
                onClick = { selectMatch(normalizedActiveIndex + 1) },
                enabled = model.matches.isNotEmpty(),
                modifier = Modifier.testTag(TranscriptNavigatorTestTags.Next),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, strings.nextMatch, tint = brand.ink)
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag(TranscriptNavigatorTestTags.Close),
            ) {
                Icon(Icons.Filled.Close, strings.closeSearch, tint = brand.ink)
            }
        }

        TranscriptTurnRail(
            turns = model.turns,
            onJumpToMessage = onJumpToMessage,
            strings = strings,
        )
    }
}

@Composable
fun TranscriptTurnRail(
    turns: List<TranscriptTurnMarker>,
    onJumpToMessage: (messageUiKey: String) -> Unit,
    modifier: Modifier = Modifier,
    strings: TranscriptNavigatorStrings = TranscriptNavigatorStrings(),
) {
    if (turns.isEmpty()) return
    val brand = LocalBrand.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        turns.forEach { turn ->
            IconButton(
                onClick = { onJumpToMessage(turn.messageUiKey) },
                modifier = Modifier
                    .testTag("${TranscriptNavigatorTestTags.TurnPrefix}${turn.messageUiKey}")
                    .semantics {
                        contentDescription = strings.turnDescription(
                            turn.turnNumber,
                            turn.label,
                            turn.matchCount,
                        )
                    },
            ) {
                Box(
                    modifier = Modifier
                        .size(if (turn.containsActiveMatch) 12.dp else 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            when {
                                turn.containsActiveMatch -> brand.relay
                                turn.matchCount > 0 -> brand.purple
                                else -> brand.dim
                            },
                        ),
                )
            }
        }
    }
}

internal fun buildTranscriptNavigationModel(
    messages: List<ChatMessage>,
    query: String,
    activeMatchIndex: Int = 0,
): TranscriptNavigationModel {
    val needle = query.trim()
    val matches = if (needle.isEmpty()) {
        emptyList()
    } else {
        buildList {
            messages.forEachIndexed { messageIndex, message ->
                val text = message.searchableRenderedText()
                var start = 0
                while (start <= text.length - needle.length) {
                    val found = text.indexOf(needle, startIndex = start, ignoreCase = true)
                    if (found < 0) break
                    add(TranscriptMatch(message.uiKey, messageIndex, found, found + needle.length))
                    start = found + needle.length.coerceAtLeast(1)
                }
            }
        }
    }

    val activeMatch = matches.getOrNull(activeMatchIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0)))
    val userIndexes = messages.indices.filter { messages[it].role == MessageRole.USER }
    val turns = userIndexes.mapIndexed { ordinal, messageIndex ->
        val endExclusive = userIndexes.getOrNull(ordinal + 1) ?: messages.size
        val turnMatches = matches.count { it.messageIndex in messageIndex until endExclusive }
        val prompt = messages[messageIndex]
        TranscriptTurnMarker(
            messageUiKey = prompt.uiKey,
            messageIndex = messageIndex,
            turnNumber = ordinal + 1,
            label = prompt.content.compactTranscriptLabel("Prompt ${ordinal + 1}"),
            matchCount = turnMatches,
            containsActiveMatch = activeMatch?.messageIndex?.let { it in messageIndex until endExclusive } == true,
        )
    }
    return TranscriptNavigationModel(matches = matches, turns = turns)
}

internal fun ChatMessage.searchableRenderedText(): String = buildList {
    add(content)
    add(thinkingContent)
    moaReferences.forEach {
        add(it.label)
        add(it.text)
    }
    toolCalls.forEach {
        add(it.name)
        add(it.taskLabel.orEmpty())
        add(it.args.orEmpty())
        add(it.result.orEmpty())
        add(it.error.orEmpty())
    }
    attachments.forEach {
        add(it.fileName.orEmpty())
        add(it.errorMessage.orEmpty())
    }
    cards.forEach { card ->
        add(card.title.orEmpty())
        add(card.subtitle.orEmpty())
        add(card.body.orEmpty())
        card.fields.forEach {
            add(it.label)
            add(it.value)
        }
        card.actions.forEach { add(it.label) }
        add(card.footer.orEmpty())
    }
}.filter(String::isNotBlank).joinToString("\n")

private fun String.compactTranscriptLabel(fallback: String): String {
    val compact = trim().replace(Regex("\\s+"), " ")
    return when {
        compact.isEmpty() -> fallback
        compact.length <= 64 -> compact
        else -> compact.take(61).trimEnd() + "…"
    }
}
