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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.LocalBulletListHandler
import com.mikepenz.markdown.compose.LocalOrderedListHandler
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import com.mikepenz.markdown.model.StreamingMarkdownState
import com.hermesandroid.relay.ui.theme.LocalBrand
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CHECK_BOX
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR

// Resolve direction before inline code is isolated, so its first strong character
// participates in the same decision used for list markers.
private fun TextStyle.withContentDirection(model: MarkdownComponentModel): TextStyle = copy(
    textDirection = if (isRtlText(model.node.getUnescapedTextInNode(model.content))) {
        TextDirection.Rtl
    } else {
        TextDirection.Ltr
    },
)

@Composable
private fun DirectionalMarkdownHeading(
    model: MarkdownComponentModel,
    style: TextStyle,
    hasRtl: Boolean,
    setext: Boolean = false,
) {
    MarkdownText(
        content = model.content,
        node = model.node,
        style = style.withContentDirection(model),
        modifier = (if (hasRtl) Modifier.fillMaxWidth() else Modifier).semantics { heading() },
        contentChildType = if (setext) MarkdownTokenTypes.SETEXT_CONTENT else MarkdownTokenTypes.ATX_CONTENT,
    )
}

@Composable
fun MarkdownContent(
    content: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    ConfiguredMarkdownContent(
        content = markdownRenderWindow(content),
        textColor = textColor,
        modifier = modifier,
    )
}

@Composable
private fun ConfiguredMarkdownContent(
    textColor: Color,
    modifier: Modifier = Modifier,
    content: String? = null,
    streamingState: StreamingMarkdownState? = null,
    directionContent: String? = null,
) {
    require((content == null) != (streamingState == null)) {
        "Exactly one Markdown source must be provided"
    }
    val isDarkTheme = LocalBrand.current.isDark
    val chatBodyStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = textColor,
        textDirection = TextDirection.ContentOrLtr,
        textAlign = TextAlign.Start,
    )
    val highlightsBuilder = remember(isDarkTheme) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDarkTheme))
    }
    // Code surfaces must contrast against the assistant bubble. Keeping these
    // exact values shared between static and streaming renderers prevents a
    // typography/color change when a live turn completes.
    val colors = markdownColor(
        text = textColor,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerLowest,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    val typography = markdownTypography(
        h1 = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = textColor,
            textDirection = TextDirection.ContentOrLtr, textAlign = TextAlign.Start,
        ),
        h2 = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = textColor,
            textDirection = TextDirection.ContentOrLtr, textAlign = TextAlign.Start,
        ),
        h3 = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = textColor,
            textDirection = TextDirection.ContentOrLtr, textAlign = TextAlign.Start,
        ),
        h4 = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = textColor,
            textDirection = TextDirection.ContentOrLtr, textAlign = TextAlign.Start,
        ),
        h5 = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Bold, color = textColor,
            textDirection = TextDirection.ContentOrLtr, textAlign = TextAlign.Start,
        ),
        h6 = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp,
            color = textColor.copy(alpha = 0.85f),
            textDirection = TextDirection.ContentOrLtr, textAlign = TextAlign.Start,
        ),
        paragraph = chatBodyStyle,
        text = chatBodyStyle,
        bullet = chatBodyStyle,
        ordered = chatBodyStyle,
        list = chatBodyStyle,
        quote = chatBodyStyle.copy(
            fontStyle = FontStyle.Italic,
            color = textColor.copy(alpha = 0.9f),
        ),
        code = MaterialTheme.typography.bodySmall.copy(
            fontSize = 13.sp,
            letterSpacing = 0.sp,
            fontFamily = FontFamily.Monospace,
            textDirection = TextDirection.Ltr,
            textAlign = TextAlign.Left,
            color = textColor,
        ),
        inlineCode = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 13.sp,
            letterSpacing = 0.sp,
            fontFamily = FontFamily.Monospace,
            textDirection = TextDirection.Ltr,
            textAlign = TextAlign.Left,
            color = textColor,
        ),
        textLink = TextLinkStyles(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
            ),
        ),
    )
    val codeSpanStyle = typography.inlineCode.copy(background = colors.inlineCodeBackground).toSpanStyle()
    val codeSettings = DefaultAnnotatorSettings(
        linkTextSpanStyle = typography.textLink,
        codeSpanStyle = codeSpanStyle,
        annotator = markdownAnnotator(),
        highlightSpanStyle = SpanStyle(color = colors.highlightText, background = colors.highlightBackground),
    )
    val annotator = remember(codeSpanStyle, codeSettings) {
        markdownAnnotator { source, node ->
            if (node.type == MarkdownElementTypes.CODE_SPAN && node.children.size >= 2) {
                pushStyle(codeSpanStyle)
                append(' ')
                append('\u2066') // Isolate inline code as left-to-right within surrounding text.
                buildMarkdownAnnotatedString(source, node.children.drop(1).dropLast(1), codeSettings)
                append('\u2069')
                append(' ')
                pop()
                true
            } else {
                false
            }
        }
    }
    val dimens = markdownDimens(tableCellWidth = 110.dp, tableCellPadding = 12.dp)
    // Keep English-only bubbles at their existing intrinsic width. A message
    // containing RTL text needs a shared width so shorter blocks align to its edge.
    val sourceText = directionContent ?: content.orEmpty()
    val hasRtl = sourceText.any {
        Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }
    val components = markdownComponents(
        heading1 = { DirectionalMarkdownHeading(it, it.typography.h1, hasRtl) },
        heading2 = { DirectionalMarkdownHeading(it, it.typography.h2, hasRtl) },
        heading3 = { DirectionalMarkdownHeading(it, it.typography.h3, hasRtl) },
        heading4 = { DirectionalMarkdownHeading(it, it.typography.h4, hasRtl) },
        heading5 = { DirectionalMarkdownHeading(it, it.typography.h5, hasRtl) },
        heading6 = { DirectionalMarkdownHeading(it, it.typography.h6, hasRtl) },
        setextHeading1 = { DirectionalMarkdownHeading(it, it.typography.h1, hasRtl, true) },
        setextHeading2 = { DirectionalMarkdownHeading(it, it.typography.h2, hasRtl, true) },
        paragraph = {
            MarkdownParagraph(
                content = it.content,
                node = it.node,
                modifier = if (hasRtl) Modifier.fillMaxWidth() else Modifier,
                style = it.typography.paragraph.withContentDirection(it),
            )
        },
        codeBlock = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SafeMarkdownHighlightedCodeBlock(
                    content = it.content,
                    node = it.node,
                    highlightsBuilder = highlightsBuilder,
                    showHeader = true,
                )
            }
        },
        codeFence = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SafeMarkdownHighlightedCodeFence(
                    content = it.content,
                    node = it.node,
                    highlightsBuilder = highlightsBuilder,
                    showHeader = true,
                )
            }
        },
        orderedList = { DirectionalMarkdownList(it) },
        unorderedList = { DirectionalMarkdownList(it) },
        table = { WideMarkdownTable(it) },
    )
    val extendedSpans = markdownExtendedSpans {
        remember { ExtendedSpans(RoundedCornerSpanPainter()) }
    }

    if (streamingState != null) {
        Markdown(
            streamingMarkdownState = streamingState,
            modifier = modifier,
            colors = colors,
            typography = typography,
            annotator = annotator,
            dimens = dimens,
            components = components,
            extendedSpans = extendedSpans,
        )
    } else {
        Markdown(
            content = checkNotNull(content),
            modifier = modifier,
            colors = colors,
            typography = typography,
            annotator = annotator,
            dimens = dimens,
            components = components,
            extendedSpans = extendedSpans,
        )
    }
}

/** Render each list row in its own direction, including its marker. */
@Composable
private fun DirectionalMarkdownList(model: MarkdownComponentModel, depth: Int = model.listDepth) {
    val padding = LocalMarkdownPadding.current
    val components = LocalMarkdownComponents.current
    val ordered = model.node.type == MarkdownElementTypes.ORDERED_LIST
    val bulletHandler = LocalBulletListHandler.current
    val orderedHandler = LocalOrderedListHandler.current
    val items = model.node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    val initialNumber = items.firstOrNull()?.getUnescapedTextInNode(model.content)
        ?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 1
    Column(
        Modifier.padding(start = padding.listIndent * depth, top = padding.list, bottom = padding.list),
    ) {
        items.forEachIndexed { index, item ->
            val direction = if (isRtlText(item.getUnescapedTextInNode(model.content))) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(top = padding.listItemTop, bottom = padding.listItemBottom)
                        .semantics { isTraversalGroup = true },
                ) {
                    val checkbox = item.children.getOrNull(1)?.takeIf { it.type == CHECK_BOX }
                    if (checkbox != null) {
                        components.checkbox(model.copy(node = checkbox))
                    } else {
                        val markerType = if (ordered) {
                            MarkdownTokenTypes.LIST_NUMBER
                        } else {
                            MarkdownTokenTypes.LIST_BULLET
                        }
                        val marker = item.findChildOfType(markerType)?.getUnescapedTextInNode(model.content)
                        val handler = if (ordered) orderedHandler else bulletHandler
                        MarkdownBasicText(
                            text = AnnotatedString(handler.transform(markerType, marker, index, initialNumber, depth)),
                            style = (if (ordered) model.typography.ordered else model.typography.bullet)
                                .copy(textDirection = TextDirection.Content),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        item.children.forEach { child ->
                            if (child.type == MarkdownElementTypes.ORDERED_LIST ||
                                child.type == MarkdownElementTypes.UNORDERED_LIST
                            ) {
                                DirectionalMarkdownList(model.copy(node = child), depth + 1)
                            } else {
                                MarkdownElement(child, components, model.content, includeSpacer = false)
                            }
                        }
                    }
                }
            }
        }
    }
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
    modifier: Modifier = Modifier,
) {
    var generation by remember { mutableIntStateOf(0) }
    key(generation) {
        NativeStreamingMarkdownGeneration(
            content = markdownRenderWindow(content.withoutLeadingBlankLines()),
            textColor = textColor,
            modifier = modifier,
            onResetRequired = { generation += 1 },
        )
    }
}

@Composable
private fun NativeStreamingMarkdownGeneration(
    content: String,
    textColor: Color,
    modifier: Modifier,
    onResetRequired: () -> Unit,
) {
    val streamingState = rememberStreamingMarkdownState()
    LaunchedEffect(content, streamingState) {
        val plan = planStreamingMarkdownAppend(
            renderedContent = streamingState.content.toString(),
            nextContent = content,
        )
        if (plan.resetRequired) {
            onResetRequired()
        } else if (plan.delta.isNotEmpty()) {
            streamingState.append(plan.delta)
        }
    }

    ConfiguredMarkdownContent(
        streamingState = streamingState,
        directionContent = content,
        textColor = textColor,
        modifier = modifier,
    )
}

internal const val MAX_RENDERED_MARKDOWN_CHARS = 256_000
private const val MARKDOWN_TRUNCATION_NOTICE =
    "\n\n---\n_Response display was shortened for Android memory safety._"

internal fun markdownRenderWindow(content: String): String =
    if (content.length <= MAX_RENDERED_MARKDOWN_CHARS) {
        content
    } else {
        content.take(MAX_RENDERED_MARKDOWN_CHARS) + MARKDOWN_TRUNCATION_NOTICE
    }

internal data class StreamingMarkdownAppendPlan(
    val resetRequired: Boolean,
    val delta: String,
)

internal fun planStreamingMarkdownAppend(
    renderedContent: String,
    nextContent: String,
): StreamingMarkdownAppendPlan = if (nextContent.startsWith(renderedContent)) {
    StreamingMarkdownAppendPlan(
        resetRequired = false,
        delta = nextContent.substring(renderedContent.length),
    )
} else {
    StreamingMarkdownAppendPlan(resetRequired = true, delta = "")
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
