package com.hermesandroid.relay.data

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Structured identity and preview for a quoted chat message. */
data class ChatQuoteReference(
    val messageId: String,
    val authorLabel: String,
    val excerpt: String,
)

/** Parsed transport envelope: Android renders [reference] separately from [body]. */
data class ChatQuoteEnvelope(
    val reference: ChatQuoteReference,
    val body: String,
)

/**
 * Serialize a structured quote as ordinary Markdown for unmodified Hermes clients.
 * Android parses the same envelope back into a quote chip, while Desktop/TUI see
 * a readable linked attribution instead of an Android-only marker.
 */
fun buildChatQuotedPrompt(body: String, reference: ChatQuoteReference?): String {
    if (reference == null) return body
    val encodedId = Base64.getUrlEncoder().withoutPadding().encodeToString(
        reference.messageId.toByteArray(StandardCharsets.UTF_8),
    )
    val author = reference.authorLabel.normalizedQuoteText(MAX_AUTHOR_CHARS)
    val excerpt = reference.excerpt.normalizedQuoteText(MAX_EXCERPT_CHARS)
    if (encodedId.isBlank() || author.isBlank() || excerpt.isBlank()) return body
    return "> **Replying to [@$author](hermes-message://$encodedId):** $excerpt\n\n$body"
}

/** Parse only the exact bounded envelope emitted by [buildChatQuotedPrompt]. */
fun parseChatQuotedPrompt(content: String): ChatQuoteEnvelope? {
    val match = QUOTE_ENVELOPE.matchEntire(content) ?: return null
    val author = match.groupValues[1]
    val encodedId = match.groupValues[2]
    val excerpt = match.groupValues[3]
    val body = match.groupValues[4]
    val messageId = runCatching {
        String(Base64.getUrlDecoder().decode(encodedId), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= MAX_MESSAGE_ID_CHARS } ?: return null
    return ChatQuoteEnvelope(
        reference = ChatQuoteReference(messageId, author, excerpt),
        body = body,
    )
}

private fun String.normalizedQuoteText(maxChars: Int): String =
    replace(Regex("[\\p{Cc}\\s]+"), " ")
        .replace("\\", "")
        .replace("]", "")
        .trim()
        .take(maxChars)

private val QUOTE_ENVELOPE = Regex(
    pattern = "^> \\*\\*Replying to \\[@([^]\\r\\n]{1,$MAX_AUTHOR_CHARS})]" +
        "\\(hermes-message://([A-Za-z0-9_-]{1,512})\\):\\*\\* " +
        "([^\\r\\n]{1,$MAX_EXCERPT_CHARS})\\n\\n([\\s\\S]*)$",
)

private const val MAX_AUTHOR_CHARS = 40
private const val MAX_EXCERPT_CHARS = 240
private const val MAX_MESSAGE_ID_CHARS = 512
