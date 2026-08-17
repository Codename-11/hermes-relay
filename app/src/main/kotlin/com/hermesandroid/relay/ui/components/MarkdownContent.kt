package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.hermesandroid.relay.ui.theme.LocalBrand
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR

@Composable
fun MarkdownContent(
    content: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = LocalBrand.current.isDark
    val chatBodyStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = textColor,
    )
    val highlightsBuilder = remember(isDarkTheme) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDarkTheme))
    }
    Markdown(
        content = content,
        modifier = modifier,
        // Code surfaces must contrast against the bubble (which is itself
        // surfaceVariant for assistant turns) or code reads as invisible. The
        // block uses the lowest container (a darker inset in dark themes, a
        // clean white inset in light), inline code a subtle raised step.
        colors = markdownColor(
            text = textColor,
            codeBackground = MaterialTheme.colorScheme.surfaceContainerLowest,
            inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        // Chat-tuned type ramp. Left unset, the mikepenz M3 defaults map headings
        // to DISPLAY roles (in this app's scale h1=displayLarge 57sp, h2=displayMedium
        // ~45sp, h3=displaySmall 36sp) — a single `#` becomes a billboard inside the
        // ~272dp bubble. Here every level derives from bodyLarge/bodyMedium (so the
        // live font-picker still applies) and is capped so the largest heading is
        // proportionate to the 15sp body, matching Discord / GitHub-mobile
        // in-message headings.
        typography = markdownTypography(
            h1 = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = textColor,
            ),
            h2 = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = textColor,
            ),
            h3 = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = textColor,
            ),
            h4 = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = textColor,
            ),
            h5 = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold, color = textColor,
            ),
            h6 = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp,
                color = textColor.copy(alpha = 0.85f),
            ),
            // Prose, list items, and quotes share a 15sp/21sp reading rhythm.
            // The library default 'text'/list role is bodyLarge (16sp), while
            // bodyMedium was previously 14sp and unnecessarily small for long chat.
            paragraph = chatBodyStyle,
            text = chatBodyStyle,
            bullet = chatBodyStyle,
            ordered = chatBodyStyle,
            list = chatBodyStyle,
            quote = chatBodyStyle.copy(
                fontStyle = FontStyle.Italic,
                color = textColor.copy(alpha = 0.9f),
            ),
            // Inline + fenced code at 13sp (one step under body, not two): monospace
            // + the tinted chip already signal "code" without also shrinking it, and
            // the loose 0.4sp default tracking is reset to 0 for tighter token runs.
            code = MaterialTheme.typography.bodySmall.copy(
                fontSize = 13.sp, letterSpacing = 0.sp,
                fontFamily = FontFamily.Monospace,
                color = textColor,
            ),
            inlineCode = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp, letterSpacing = 0.sp,
                fontFamily = FontFamily.Monospace, color = textColor,
            ),
            // Links get an accent color + underline so they read as tappable on the
            // muted assistant bubble (the default textLink is body-colored).
            textLink = TextLinkStyles(
                style = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
        ),
        // Tables get a phone-friendly minimum measure. The stock renderer uses
        // one-line cells; our table component below keeps the same AST/inline
        // annotator path but permits wrapping and exposes horizontal overflow.
        dimens = markdownDimens(
            tableCellWidth = 110.dp,
            tableCellPadding = 12.dp,
        ),
        components = markdownComponents(
            codeBlock = {
                SafeMarkdownHighlightedCodeBlock(
                    content = it.content,
                    node = it.node,
                    highlightsBuilder = highlightsBuilder,
                    showHeader = true
                )
            },
            codeFence = {
                SafeMarkdownHighlightedCodeFence(
                    content = it.content,
                    node = it.node,
                    highlightsBuilder = highlightsBuilder,
                    showHeader = true
                )
            },
            table = { WideMarkdownTable(it) },
        ),
        extendedSpans = markdownExtendedSpans {
            remember { ExtendedSpans(RoundedCornerSpanPainter()) }
        }
    )
}

/**
 * GFM table renderer tuned for a narrow chat bubble.
 *
 * Every column keeps the configured 110dp minimum and cells wrap instead of
 * truncating to one line. Tables wider than the bubble scroll horizontally;
 * the trailing fade is deliberately subtle and disappears once the reader has
 * reached the final column.
 */
@Composable
private fun WideMarkdownTable(model: MarkdownComponentModel) {
    val columnsCount = remember(model.node) {
        model.node.findChildOfType(HEADER)?.children?.count { it.type == CELL } ?: 0
    }
    if (columnsCount == 0) {
        MarkdownTable(
            content = model.content,
            node = model.node,
            style = model.typography.table,
        )
        return
    }

    val rowsCount = remember(model.node) {
        model.node.children.count { it.type == ROW } + 1
    }
    val tableCellWidth = LocalMarkdownDimens.current.tableCellWidth
    val tableWidth = columnsCount * tableCellWidth
    val tableCornerSize = LocalMarkdownDimens.current.tableCornerSize
    val tableBackground = LocalMarkdownColors.current.tableBackground
    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tableCornerSize))
            .background(tableBackground)
            .semantics {
                collectionInfo = CollectionInfo(
                    rowCount = rowsCount,
                    columnCount = columnsCount,
                )
            },
    ) {
        val scrollable = maxWidth < tableWidth
        Box {
            Column(
                modifier = if (scrollable) {
                    Modifier
                        .horizontalScroll(scrollState)
                        .requiredWidth(tableWidth)
                } else {
                    Modifier.fillMaxWidth()
                },
            ) {
                var rowIndex = 1
                model.node.children.forEach { child ->
                    when (child.type) {
                        HEADER -> MarkdownTableHeader(
                            content = model.content,
                            header = child,
                            tableWidth = tableWidth,
                            style = model.typography.table,
                            verticalAlignment = Alignment.Top,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip,
                        )

                        ROW -> {
                            MarkdownTableRow(
                                content = model.content,
                                header = child,
                                tableWidth = tableWidth,
                                style = model.typography.table,
                                rowIndex = rowIndex,
                                verticalAlignment = Alignment.Top,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                            rowIndex++
                        }

                        TABLE_SEPARATOR -> MarkdownDivider()
                    }
                }
            }

            if (scrollable && scrollState.canScrollForward) {
                Box(
                    modifier = Modifier.matchParentSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(28.dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, tableBackground),
                                ),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
fun StreamingMarkdownContent(
    content: String,
    textColor: Color,
    isStreaming: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val partitionState = remember { StreamingMarkdownState() }
    val partition = partitionState.update(content, finalizeTail = !isStreaming)
    Column(modifier = modifier) {
        partition.blocks.forEach { block ->
            // Start offsets never move as the response grows. Already-promoted
            // Markdown therefore keeps its composition/selection identity while
            // only the live tail is remeasured or promoted.
            key(block.startOffset) {
                MarkdownContent(
                    content = block.content,
                    textColor = textColor,
                )
            }
        }
        partition.tail?.let { tail ->
            key(tail.startOffset) {
                Text(
                    text = tail.content,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    ),
                    color = textColor,
                )
            }
        }
    }
}

internal data class StreamingMarkdownBlock(
    val startOffset: Int,
    val content: String,
)

internal data class StreamingMarkdownPartition(
    val blocks: List<StreamingMarkdownBlock>,
    val tail: StreamingMarkdownBlock?,
)

/** Retains immutable promoted blocks and rescans only the unfinished suffix. */
internal class StreamingMarkdownState {
    private var previousContent: String? = null
    private var previousPartition = StreamingMarkdownPartition(emptyList(), null)
    private var previousFinalized = false

    internal var lastScanStart: Int = 0
        private set

    fun update(source: String, finalizeTail: Boolean): StreamingMarkdownPartition {
        val content = source.withoutLeadingBlankLines()
        val previous = previousContent
        if (content == previous && finalizeTail == previousFinalized) {
            return previousPartition
        }
        val canAppend = previous != null &&
            !previousFinalized &&
            content.startsWith(previous)
        val scanStart = if (canAppend) {
            previousPartition.tail?.startOffset ?: previous.length
        } else {
            0
        }
        val stableBlocks = if (canAppend) previousPartition.blocks else emptyList()

        lastScanStart = scanStart
        previousPartition = partitionStreamingMarkdownNormalized(
            content = content,
            startOffset = scanStart,
            stableBlocks = stableBlocks,
            finalizeTail = finalizeTail,
        )
        previousContent = content
        previousFinalized = finalizeTail
        return previousPartition
    }
}

/**
 * Splits an accumulating response into immutable Markdown blocks plus one live
 * plain-text tail. A block is promoted only after a blank-line boundary, or a
 * newline-terminated matching fenced-code close. That conservative boundary
 * means inline delimiters, links, tables, list markers, and malformed fences
 * can arrive across arbitrary transport chunks without reparsing an earlier
 * selectable subtree.
 */
internal fun partitionStreamingMarkdown(
    source: String,
    finalizeTail: Boolean,
): StreamingMarkdownPartition {
    val content = source.withoutLeadingBlankLines()
    return partitionStreamingMarkdownNormalized(
        content = content,
        startOffset = 0,
        stableBlocks = emptyList(),
        finalizeTail = finalizeTail,
    )
}

private fun partitionStreamingMarkdownNormalized(
    content: String,
    startOffset: Int,
    stableBlocks: List<StreamingMarkdownBlock>,
    finalizeTail: Boolean,
): StreamingMarkdownPartition {
    if (content.isEmpty()) return StreamingMarkdownPartition(emptyList(), null)

    val blocks = stableBlocks.toMutableList()
    var blockStart = startOffset
    var lineStart = startOffset
    var fence: MarkdownFence? = null

    while (lineStart < content.length) {
        val newline = content.indexOf('\n', lineStart)
        if (newline < 0) break
        val lineEnd = newline + 1
        val line = content.substring(lineStart, newline).removeSuffix("\r")

        val activeFence = fence
        if (activeFence != null) {
            if (line.closes(activeFence)) {
                blocks += StreamingMarkdownBlock(
                    startOffset = blockStart,
                    content = content.substring(blockStart, lineEnd),
                )
                blockStart = lineEnd
                fence = null
            }
        } else {
            val openingFence = line.openingFenceOrNull()
            when {
                openingFence != null -> fence = openingFence
                line.isBlank() -> {
                    val candidate = content.substring(blockStart, lineStart)
                    when {
                        candidate.isBlank() -> blockStart = lineEnd
                        !candidate.containsPotentialReferenceSyntax() -> {
                            blocks += StreamingMarkdownBlock(
                                startOffset = blockStart,
                                content = content.substring(blockStart, lineEnd),
                            )
                            blockStart = lineEnd
                        }
                    }
                }
            }
        }
        lineStart = lineEnd
    }

    val remaining = content.substring(blockStart)
    if (remaining.isNotEmpty()) {
        if (finalizeTail) {
            blocks += StreamingMarkdownBlock(blockStart, remaining)
        } else {
            return StreamingMarkdownPartition(
                blocks = blocks,
                tail = StreamingMarkdownBlock(blockStart, remaining),
            )
        }
    }
    return StreamingMarkdownPartition(blocks = blocks, tail = null)
}

private data class MarkdownFence(val marker: Char, val length: Int)

private fun String.openingFenceOrNull(): MarkdownFence? {
    val candidate = dropWhileLimitedSpaces(maxSpaces = 3)
    val marker = candidate.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = candidate.takeWhile { it == marker }.length
    if (length < 3) return null
    if (marker == '`' && candidate.drop(length).contains('`')) return null
    return MarkdownFence(marker, length)
}

private fun String.closes(fence: MarkdownFence): Boolean {
    val candidate = dropWhileLimitedSpaces(maxSpaces = 3)
    val markerLength = candidate.takeWhile { it == fence.marker }.length
    return markerLength >= fence.length && candidate.drop(markerLength).isBlank()
}

private fun String.dropWhileLimitedSpaces(maxSpaces: Int): String {
    var count = 0
    while (count < length && count < maxSpaces && this[count] == ' ') count++
    return substring(count)
}

/**
 * Reference links/definitions have document scope. Keep their segment live
 * until finalization rather than freezing an unresolved label in an earlier
 * independently parsed Markdown block. Inline links (`[text](url)`) are local
 * and can still promote at the normal blank-line boundary.
 */
private fun String.containsPotentialReferenceSyntax(): Boolean {
    var open = indexOf('[')
    while (open >= 0) {
        val escaped = open > 0 && this[open - 1] == '\\'
        val close = indexOf(']', startIndex = open + 1)
        if (close < 0) return false
        if (!escaped && getOrNull(close + 1) != '(') return true
        open = indexOf('[', startIndex = close + 1)
    }
    return false
}

internal fun String.withoutLeadingBlankLines(): String {
    var contentStart = 0
    while (contentStart < length) {
        val lineEnd = indexOf('\n', startIndex = contentStart)
        if (lineEnd < 0) break

        val line = substring(contentStart, lineEnd).removeSuffix("\r")
        if (line.isNotBlank()) break
        contentStart = lineEnd + 1
    }
    return substring(contentStart)
}
