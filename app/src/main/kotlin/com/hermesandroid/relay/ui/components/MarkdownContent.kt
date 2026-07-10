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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
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
import kotlinx.coroutines.delay
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
        // ~1.4x the 14sp body, matching Discord / GitHub-mobile in-message headings.
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
            // Prose, list items, and quotes all sit at the 14sp body size so a
            // paragraph and the bullet list under it share one rhythm — the library
            // default 'text'/list role is bodyLarge (16sp), 2sp larger than paragraph.
            paragraph = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            text = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            bullet = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            ordered = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            list = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            quote = MaterialTheme.typography.bodyMedium.copy(
                fontStyle = FontStyle.Italic, color = textColor.copy(alpha = 0.78f),
            ),
            // Inline + fenced code at 13sp (one step under body, not two): monospace
            // + the tinted chip already signal "code" without also shrinking it, and
            // the loose 0.4sp default tracking is reset to 0 for tighter token runs.
            code = MaterialTheme.typography.bodySmall.copy(
                fontSize = 13.sp, letterSpacing = 0.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                MarkdownHighlightedCodeBlock(
                    content = it.content,
                    node = it.node,
                    highlightsBuilder = highlightsBuilder,
                    showHeader = true
                )
            },
            codeFence = {
                MarkdownHighlightedCodeFence(
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
    val blocks = remember(content, isStreaming) {
        if (isStreaming) {
            parseStreamingMarkdownBlocks(content)
        } else {
            listOf(StreamingMarkdownBlock.Markdown(content))
        }
    }

    Column(
        modifier = modifier,
        // Match the final renderer's block spacer so moving the active tail
        // into the settled Markdown prefix does not add a second layout jump.
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is StreamingMarkdownBlock.Markdown -> MarkdownContent(
                    content = block.content,
                    textColor = textColor,
                )

                is StreamingMarkdownBlock.Text -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )

                is StreamingMarkdownBlock.Code -> StreamingCodeBlock(block)
            }
        }
    }
}

@Composable
private fun StreamingCodeBlock(block: StreamingMarkdownBlock.Code) {
    // Discord-like fenced block: a contrasting inset surface with a thin header
    // (language label + copy), and a horizontally-scrollable monospace body.
    // Header is shown whenever there's a language to label or code to copy, so
    // even a bare ``` fence gets the copy affordance once it has content.
    val hasHeader = block.language.isNotBlank() || block.code.isNotBlank()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column {
            if (hasHeader) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = block.language.ifBlank { "code" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CodeCopyButton(code = block.code)
                }
            }

            Text(
                text = block.code.ifEmpty { " " },
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                softWrap = false,
            )
        }
    }
}

/**
 * Small copy affordance for a code block — copies [code] to the clipboard and
 * briefly flips to a check for feedback. No-op while [code] is blank.
 */
@Composable
private fun CodeCopyButton(code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    IconButton(
        onClick = {
            if (code.isNotBlank()) {
                clipboard.setText(AnnotatedString(code))
                copied = true
            }
        },
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            contentDescription = if (copied) "Copied" else "Copy code",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

internal sealed interface StreamingMarkdownBlock {
    data class Markdown(val content: String) : StreamingMarkdownBlock
    data class Text(val text: String) : StreamingMarkdownBlock
    data class Code(val language: String, val code: String) : StreamingMarkdownBlock
}

/**
 * Splits an in-flight response into stable Markdown and one structurally
 * incomplete tail.
 *
 * Only conservative, blank-terminated top-level prose/heading blocks promote
 * to the real renderer. Lists, quotes, tables, indented blocks, HTML, and code
 * remain on the lightweight streaming surface until the message settles; those
 * containers can legally absorb later lines, so promoting them early causes a
 * visible re-parenting jump when the final CommonMark tree is parsed.
 */
internal fun parseStreamingMarkdownBlocks(content: String): List<StreamingMarkdownBlock> {
    if (content.isBlank()) return emptyList()

    val normalized = content
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    val blocks = mutableListOf<StreamingMarkdownBlock>()
    val settledEnd = findStableMarkdownBoundary(normalized).coerceAtLeast(0)

    if (settledEnd > 0) {
        normalized.substring(0, settledEnd).trimEnd().let { stable ->
            if (stable.isNotBlank()) blocks += StreamingMarkdownBlock.Markdown(stable)
        }
    }

    val activeTail = normalized.substring(settledEnd)
    if (activeTail.isNotBlank()) {
        blocks += parseActiveStreamingTail(activeTail)
    }

    return blocks
}

/** Last unambiguous blank-line boundary in a contiguous simple-markdown prefix. */
private fun findStableMarkdownBoundary(content: String): Int {
    var offset = 0
    var blockStart = 0
    var activeFence: StreamingFence? = null
    var lastStableBoundary = 0

    while (offset < content.length) {
        val newline = content.indexOf('\n', offset)
        val lineEnd = if (newline >= 0) newline else content.length
        val line = content.substring(offset, lineEnd)

        activeFence = when (val current = activeFence) {
            null -> streamingFence(line)
            else -> if (isClosingFence(line, current)) null else current
        }

        // Whitespace-only lines can be meaningful indentation inside a list.
        // Require a truly empty delimiter and stop at the first ambiguous
        // container so every promoted prefix remains structurally final.
        if (activeFence == null && newline >= 0 && line.isEmpty()) {
            val candidate = content.substring(blockStart, offset).trimEnd()
            if (candidate.isNotBlank() && !isConservativeStableBlock(candidate)) break
            lastStableBoundary = newline + 1
            blockStart = lastStableBoundary
        }

        if (newline < 0) break
        offset = newline + 1
    }

    return lastStableBoundary
}

private fun isConservativeStableBlock(block: String): Boolean = block
    .lineSequence()
    .filter { it.isNotEmpty() }
    .none { line ->
        line.firstOrNull()?.isWhitespace() == true ||
            AMBIGUOUS_STREAMING_BLOCK.matches(line)
    }

private fun parseActiveStreamingTail(content: String): List<StreamingMarkdownBlock> {
    val blocks = mutableListOf<StreamingMarkdownBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var activeFence: StreamingFence? = null
    var language = ""

    fun flushParagraph() {
        val text = paragraph.toString().trim('\n').trimEnd()
        if (text.isNotBlank()) {
            blocks += StreamingMarkdownBlock.Text(text)
        }
        paragraph.clear()
    }

    fun flushCode() {
        blocks += StreamingMarkdownBlock.Code(
            language = language,
            code = code.toString().trimEnd('\n'),
        )
        code.clear()
    }

    val lines = content.split('\n')

    lines.forEachIndexed { index, line ->
        val lineWithBreak = if (index == lines.lastIndex) line else "$line\n"

        if (activeFence == null) {
            val fence = streamingFence(line)
            if (fence != null) {
                flushParagraph()
                activeFence = fence
                language = streamingFenceLanguage(line, fence)
            } else {
                paragraph.append(lineWithBreak)
            }
        } else if (isClosingFence(line, activeFence)) {
            flushCode()
            activeFence = null
            language = ""
        } else {
            code.append(lineWithBreak)
        }
    }

    if (activeFence != null) {
        flushCode()
    } else {
        flushParagraph()
    }

    return blocks
}

private data class StreamingFence(
    val marker: Char,
    val length: Int,
)

private fun streamingFence(line: String): StreamingFence? {
    val trimmed = line.trimStart()
    val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = trimmed.takeWhile { it == marker }.length
    return length.takeIf { it >= 3 }?.let { StreamingFence(marker, it) }
}

private fun isClosingFence(line: String, activeFence: StreamingFence): Boolean {
    val trimmed = line.trimStart()
    if (trimmed.firstOrNull() != activeFence.marker) return false
    val markerLength = trimmed.takeWhile { it == activeFence.marker }.length
    return markerLength >= activeFence.length && trimmed.drop(markerLength).isBlank()
}

private fun streamingFenceLanguage(line: String, fence: StreamingFence): String {
    val tail = line.trimStart().drop(fence.length).trim()
    return tail
        .takeWhile { !it.isWhitespace() && it != fence.marker }
        .take(32)
}

private val AMBIGUOUS_STREAMING_BLOCK = Regex(
    """^(?:[-+*]\s|\d{1,9}[.)]\s|>|```|~~~|<|\||(?:-{3,}|={3,})\s*$).*""",
)
