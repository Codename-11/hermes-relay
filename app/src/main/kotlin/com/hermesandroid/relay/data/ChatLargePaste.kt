package com.hermesandroid.relay.data

import java.util.Base64

const val LARGE_PASTE_THRESHOLD_CHARS = 5_000

data class TextTransportAttachments(
    val message: String,
    val attachments: List<Attachment>,
)

fun largePasteAttachment(text: String, composerId: String? = null): Attachment {
    val bytes = text.toByteArray(Charsets.UTF_8)
    return Attachment(
        contentType = "text/plain; charset=utf-8",
        content = Base64.getEncoder().encodeToString(bytes),
        fileName = "pasted-text.txt",
        fileSize = bytes.size.toLong(),
        isLargePaste = true,
        composerId = composerId,
    )
}

fun prepareTextTransportAttachments(
    message: String,
    attachments: List<Attachment>,
): TextTransportAttachments {
    val largePastes = attachments.filter(Attachment::isLargePaste)
    if (largePastes.isEmpty()) return TextTransportAttachments(message, attachments)

    val materialized = largePastes.mapNotNull { attachment ->
        runCatching {
            val text = String(Base64.getDecoder().decode(attachment.content), Charsets.UTF_8)
            val name = attachment.fileName?.takeIf(String::isNotBlank) ?: "pasted text"
            "--- $name ---\n$text"
        }.getOrNull()
    }
    return TextTransportAttachments(
        message = (listOf(message) + materialized)
            .filter(String::isNotBlank)
            .joinToString("\n\n"),
        attachments = attachments.filterNot(Attachment::isLargePaste),
    )
}
