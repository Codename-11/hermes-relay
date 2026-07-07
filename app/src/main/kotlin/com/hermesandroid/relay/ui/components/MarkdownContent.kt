package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.horizontalScroll
import com.hermesandroid.relay.ui.theme.LocalBrand
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
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
import kotlinx.coroutines.delay
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
