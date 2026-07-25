package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode

@Composable
internal fun SafeMarkdownHighlightedCodeFence(
    content: String,
    node: ASTNode,
    style: TextStyle = LocalMarkdownTypography.current.code,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean = false,
) {
    MarkdownCodeFence(content, node, style) { code, language, codeStyle ->
        SafeMarkdownHighlightedCode(
            code = code,
            language = language,
            style = codeStyle,
            highlightsBuilder = highlightsBuilder,
            showHeader = showHeader,
        )
    }
}

@Composable
internal fun SafeMarkdownHighlightedCodeBlock(
    content: String,
    node: ASTNode,
    style: TextStyle = LocalMarkdownTypography.current.code,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean = false,
) {
    MarkdownCodeBlock(content, node, style) { code, language, codeStyle ->
        SafeMarkdownHighlightedCode(
            code = code,
            language = language,
            style = codeStyle,
            highlightsBuilder = highlightsBuilder,
            showHeader = showHeader,
        )
    }
}

@Composable
private fun SafeMarkdownHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
) {
    val codeHighlights by produceState(
        initialValue = AnnotatedString(code),
        key1 = code,
        key2 = language,
        key3 = highlightsBuilder,
    ) {
        value = withContext(Dispatchers.Default) {
            buildSafeHighlightedAnnotatedString(code, language, highlightsBuilder)
        }
    }

    val codeBackgroundCornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(codeBackgroundCornerSize),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        showHeader = showHeader,
        language = language,
        code = code,
    ) {
        MarkdownBasicText(
            text = codeHighlights,
            style = style,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(LocalMarkdownPadding.current.codeBlock),
        )
    }
}

/**
 * Converts Highlights ranges into Compose spans without trusting dependency
 * offsets. Highlights 1.1.0 can return a reversed multiline-comment range
 * when a multiline-comment closing delimiter precedes its opening delimiter.
 * Streaming can expose that shape before a code block is complete.
 */
internal fun buildSafeHighlightedAnnotatedString(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder,
): AnnotatedString {
    val syntaxLanguage = language?.let(SyntaxLanguage::getByName)
    val highlights = highlightsBuilder
        .code(code)
        .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
        .build()
        .getHighlights()

    return buildAnnotatedString {
        append(code)
        highlights.forEach { highlight ->
            val start = highlight.location.start.coerceIn(0, code.length)
            val end = highlight.location.end.coerceIn(0, code.length)
            if (start >= end) return@forEach

            val style = when (highlight) {
                is ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(style = style, start = start, end = end)
        }
    }
}
