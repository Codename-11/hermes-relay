package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.network.upstream.models.MessagePagination
import kotlinx.coroutines.CancellationException

/** Explicit transcript read intent for Hermes' bounded messages endpoint. */
enum class SessionMessageLoadMode {
    /** One bounded newest-first window, returned in chronological order. */
    LATEST,

    /** Every page, oldest first, subject to Android memory safety bounds. */
    COMPLETE,
}

internal const val SESSION_MESSAGE_PAGE_SIZE = 500
private const val MAX_COMPLETE_TRANSCRIPT_MESSAGES = 50_000
private const val MAX_COMPLETE_TRANSCRIPT_PAYLOAD_CHARS = 32_000_000

internal data class SessionMessagePageRequest(
    val limit: Int = SESSION_MESSAGE_PAGE_SIZE,
    val offset: Int = 0,
    val order: String,
)

internal data class SessionMessagePage(
    val messages: List<MessageItem>,
    val pagination: MessagePagination?,
    val payloadChars: Int,
)

internal class SessionTranscriptTooLargeException(message: String) : IllegalStateException(message)

/** Shared API-server/dashboard pagination contract. Legacy unpaginated envelopes remain valid. */
internal suspend fun loadSessionMessages(
    mode: SessionMessageLoadMode,
    fetchPage: suspend (SessionMessagePageRequest) -> Result<SessionMessagePage>,
): Result<List<MessageItem>> {
    return try {
        val order = if (mode == SessionMessageLoadMode.LATEST) "latest" else "oldest"
        val collected = ArrayList<MessageItem>()
        var offset = 0
        var payloadChars = 0L

        while (true) {
            val request = SessionMessagePageRequest(offset = offset, order = order)
            val page = fetchPage(request).getOrThrow()
            payloadChars += page.payloadChars
            if (payloadChars > MAX_COMPLETE_TRANSCRIPT_PAYLOAD_CHARS) {
                throw SessionTranscriptTooLargeException(
                    "Session transcript exceeds Android's 32 MB safe-load limit",
                )
            }
            if (collected.size + page.messages.size > MAX_COMPLETE_TRANSCRIPT_MESSAGES) {
                throw SessionTranscriptTooLargeException(
                    "Session transcript exceeds Android's 50,000-message safe-load limit",
                )
            }
            collected += page.messages

            if (mode == SessionMessageLoadMode.LATEST) break
            // Older Hermes returned one unpaginated complete envelope. Never issue
            // a speculative second request against that contract.
            val pagination = page.pagination ?: break
            val returned = pagination.returned ?: page.messages.size
            if (page.messages.isEmpty() || returned < request.limit || page.messages.size < request.limit) break

            val nextOffset = offset + page.messages.size
            if (nextOffset <= offset) break
            offset = nextOffset
        }
        Result.success(collected)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
