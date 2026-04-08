package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownExtendedSpans
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.delay

@Composable
fun MarkdownContent(
    content: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = isSystemInDarkTheme()
    val highlightsBuilder = remember(isDarkTheme) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDarkTheme))
    }

    Markdown(
        content = content,
        modifier = modifier,
        colors = markdownColor(
            text = textColor,
            codeText = MaterialTheme.colorScheme.onSurfaceVariant,
            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
            linkText = MaterialTheme.colorScheme.primary
        ),
        typography = markdownTypography(
            text = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            code = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ),
        components = markdownComponents(
            codeBlock = {
                MarkdownCodeBlock(it.content, it.node) { code, language ->
                    CodeBlockWithCopyButton(code, language, highlightsBuilder)
                }
            },
            codeFence = {
                MarkdownCodeFence(it.content, it.node) { code, language ->
                    CodeBlockWithCopyButton(code, language, highlightsBuilder)
                }
            }
        ),
        extendedSpans = markdownExtendedSpans {
            remember { ExtendedSpans(RoundedCornerSpanPainter()) }
        }
    )
}

@Composable
private fun CodeBlockWithCopyButton(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder
) {
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Box {
        MarkdownHighlightedCode(code, language, highlightsBuilder)

        IconButton(
            onClick = {
                clipboardManager.setText(AnnotatedString(code))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                copied = true
            },
            modifier = Modifier
                .size(28.dp)
                .align(Alignment.TopEnd)
                .padding(4.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
        ) {
            Icon(
                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy code",
                modifier = Modifier.size(14.dp),
                tint = if (copied) Color(0xFF4CAF50)
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
