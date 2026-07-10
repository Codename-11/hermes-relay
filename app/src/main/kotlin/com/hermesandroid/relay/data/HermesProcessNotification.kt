package com.hermesandroid.relay.data

/**
 * A process event that upstream Hermes injected into transcript history as a
 * synthetic user message.
 *
 * Hermes intentionally persists these events with role=user so the agent can
 * react to them without breaking message-role alternation. UI code should use
 * [ChatMessage.hermesProcessNotificationOrNull] to present them as process
 * notices without changing their canonical role or content.
 */
data class HermesProcessNotification(
    val processId: String,
    val headline: String,
    val detail: String?,
)

/**
 * Recognizes the exact envelope emitted by upstream
 * `tools.process_registry.format_process_notification` for background-process
 * completion and watch events.
 *
 * The parser deliberately excludes other `[IMPORTANT: ...]` messages. Those
 * can carry unrelated agent instructions and must continue through the normal
 * transcript renderer.
 */
object HermesProcessNotificationParser {
    private const val ENVELOPE_PREFIX = "[IMPORTANT: Background process "
    private const val HEADLINE_PREFIX = "Background process "

    fun parse(content: String): HermesProcessNotification? {
        val normalized = content.trim()
        if (!normalized.startsWith(ENVELOPE_PREFIX) || !normalized.endsWith(']')) {
            return null
        }

        val body = normalized
            .removePrefix("[IMPORTANT: ")
            .dropLast(1)
        val headline = body.substringBefore('\n').trim()
        if (!headline.startsWith(HEADLINE_PREFIX)) return null

        val identityAndStatus = headline.removePrefix(HEADLINE_PREFIX)
        val processId = identityAndStatus.substringBefore(' ')
        val status = identityAndStatus.substringAfter(' ', missingDelimiterValue = "")
        if (processId.isBlank() || status.isBlank()) return null

        val detail = body
            .substringAfter('\n', missingDelimiterValue = "")
            .trim()
            .ifBlank { null }

        return HermesProcessNotification(
            processId = processId,
            headline = headline,
            detail = detail,
        )
    }
}

/**
 * Returns the upstream process-notification presentation model only for the
 * canonical synthetic user-row shape. The original [ChatMessage.role] remains
 * [MessageRole.USER].
 */
fun ChatMessage.hermesProcessNotificationOrNull(): HermesProcessNotification? =
    takeIf { it.role == MessageRole.USER }
        ?.content
        ?.let(HermesProcessNotificationParser::parse)
