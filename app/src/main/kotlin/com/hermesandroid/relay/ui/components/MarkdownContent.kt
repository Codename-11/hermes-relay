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
    if (isStreaming) {
        // Keep one stable layout node for the entire live turn. Promoting each
        // blank-terminated paragraph into Markdown replaced the Text subtree
        // repeatedly; LazyColumn then exposed its fallback anchor for a frame,
        // which looked like the whole chat reloaded. Updating this Text value
        // only remeasures the growing bubble. Full Markdown is parsed once the
        // owning row releases its stable live-tail layout.
        Text(
            // CommonMark ignores blank lines before the first block. The live
            // Text renderer must do the same or a response whose transport
            // prefix contains newlines appears to start several lines down.
            // Preserve indentation on the first non-blank line so indented
            // code and deliberately spaced prose are not altered.
            text = content.withoutLeadingBlankLines(),
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
    } else {
        MarkdownContent(
            content = content,
            textColor = textColor,
            modifier = modifier,
        )
    }
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
