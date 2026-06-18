package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.horizontalScroll
import com.hermesandroid.relay.ui.theme.LocalBrand
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownExtendedSpans
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes

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
        colors = markdownColor(
            text = textColor,
            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
            inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant
        ),
        typography = markdownTypography(
            paragraph = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            code = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            }
        ),
        extendedSpans = markdownExtendedSpans {
            remember { ExtendedSpans(RoundedCornerSpanPainter()) }
        }
    )
}

@Composable
fun StreamingMarkdownContent(
    content: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val blocks = remember(content) { parseStreamingMarkdownBlocks(content) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (block.language.isNotBlank()) {
                Text(
                    text = block.language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = block.code.ifEmpty { " " },
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                softWrap = false,
            )
        }
    }
}

private sealed interface StreamingMarkdownBlock {
    data class Text(val text: String) : StreamingMarkdownBlock
    data class Code(val language: String, val code: String) : StreamingMarkdownBlock
}

private fun parseStreamingMarkdownBlocks(content: String): List<StreamingMarkdownBlock> {
    if (content.isBlank()) return emptyList()

    val blocks = mutableListOf<StreamingMarkdownBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var inFence = false
    var activeFence = ""
    var language = ""

    fun flushParagraph() {
        val text = paragraph.toString().trimEnd()
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

    val lines = content
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')

    lines.forEachIndexed { index, line ->
        val lineWithBreak = if (index == lines.lastIndex) line else "$line\n"

        if (!inFence) {
            val fence = streamingFenceMarker(line)
            if (fence != null) {
                flushParagraph()
                inFence = true
                activeFence = fence
                language = streamingFenceLanguage(line, fence)
            } else {
                paragraph.append(lineWithBreak)
            }
        } else if (streamingFenceMarker(line) == activeFence) {
            flushCode()
            inFence = false
            activeFence = ""
            language = ""
        } else {
            code.append(lineWithBreak)
        }
    }

    if (inFence) {
        flushCode()
    } else {
        flushParagraph()
    }

    return blocks
}

private fun streamingFenceMarker(line: String): String? {
    val trimmed = line.trimStart()
    return when {
        trimmed.startsWith("```") -> "```"
        trimmed.startsWith("~~~") -> "~~~"
        else -> null
    }
}

private fun streamingFenceLanguage(line: String, marker: String): String {
    val tail = line.trimStart().removePrefix(marker).trim()
    return tail
        .takeWhile { !it.isWhitespace() && it != '`' && it != '~' }
        .take(32)
}
