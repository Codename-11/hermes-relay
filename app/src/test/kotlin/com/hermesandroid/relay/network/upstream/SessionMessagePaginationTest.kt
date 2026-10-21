package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.network.upstream.models.MessagePagination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionMessagePaginationTest {

    @Test
    fun `oversized latest page retries at same offset and keeps the full chronological window`() = runTest {
        val requests = mutableListOf<SessionMessagePageRequest>()
        val result = loadSessionMessages(SessionMessageLoadMode.LATEST) { request ->
            requests += request
            if (request.limit > 125) {
                Result.failure(JsonResponseTooLargeException())
            } else {
                Result.success(page(1_000 - request.offset - request.limit, request.limit))
            }
        }.getOrThrow()

        assertEquals((500 until 1_000).map(Int::toString), result.map { it.id })
        assertEquals(listOf(500, 250, 125, 125, 125, 125), requests.map { it.limit })
        assertEquals(listOf(0, 0, 0, 125, 250, 375), requests.map { it.offset })
    }

    @Test
    fun `complete recovery retains offset after a later oversized page`() = runTest {
        val result = loadSessionMessages(SessionMessageLoadMode.COMPLETE) { request ->
            if (request.offset >= 500 && request.limit > 125) {
                Result.failure(JsonResponseTooLargeException())
            } else {
                Result.success(page(request.offset, minOf(request.limit, 751 - request.offset)))
            }
        }.getOrThrow()
        assertEquals((0..750).map(Int::toString), result.map { it.id })
    }

    @Test
    fun `oversized single row stops bounded retries without returning partial history`() = runTest {
        val requests = mutableListOf<SessionMessagePageRequest>()
        val result = loadSessionMessages(SessionMessageLoadMode.COMPLETE) { request ->
            requests += request
            if (request.offset == 0) Result.success(page(0, 500))
            else Result.failure(JsonResponseTooLargeException())
        }
        assertEquals(listOf(500, 500, 250, 125, 62, 31, 15, 7, 3, 1), requests.map { it.limit })
        assertEquals(SessionTranscriptTooLargeException::class, result.exceptionOrNull()?.let { it::class })
        assertEquals(null, result.getOrNull())
    }

    @Test
    fun `unrelated failures are not retried`() = runTest {
        var calls = 0
        val error = java.io.IOException("connection reset")
        val result = loadSessionMessages(SessionMessageLoadMode.LATEST) {
            calls++
            Result.failure(error)
        }
        assertEquals(1, calls)
        assertEquals(error, result.exceptionOrNull())
    }

    @Test
    fun `complete history pages oldest first without gaps`() = runTest {
        val requests = mutableListOf<SessionMessagePageRequest>()
        val result = loadSessionMessages(SessionMessageLoadMode.COMPLETE) { request ->
            requests += request
            val count = if (request.offset == 0) 500 else 1
            Result.success(page(request.offset, count, returned = count))
        }.getOrThrow()

        assertEquals((0..500).map(Int::toString), result.map { it.id })
        assertEquals(listOf(0, 500), requests.map { it.offset })
        assertEquals(listOf("oldest", "oldest"), requests.map { it.order })
    }

    @Test
    fun `exact 500 requests one terminal page and returns no duplicates`() = runTest {
        val requests = mutableListOf<SessionMessagePageRequest>()
        val result = loadSessionMessages(SessionMessageLoadMode.COMPLETE) { request ->
            requests += request
            val count = if (request.offset == 0) 500 else 0
            Result.success(page(request.offset, count, returned = count))
        }.getOrThrow()

        assertEquals(500, result.size)
        assertEquals(listOf(0, 500), requests.map { it.offset })
        assertEquals(500, result.mapNotNull { it.id }.distinct().size)
    }

    @Test
    fun `legacy envelope is accepted as one complete response`() = runTest {
        var calls = 0
        val result = loadSessionMessages(SessionMessageLoadMode.COMPLETE) {
            calls++
            Result.success(page(0, 499, pagination = null))
        }.getOrThrow()

        assertEquals(499, result.size)
        assertEquals(1, calls)
    }

    @Test
    fun `latest mode makes exactly one bounded latest request`() = runTest {
        var captured: SessionMessagePageRequest? = null
        val result = loadSessionMessages(SessionMessageLoadMode.LATEST) { request ->
            captured = request
            Result.success(page(500, 500, returned = 500))
        }.getOrThrow()

        assertEquals(500, result.size)
        assertEquals("latest", captured?.order)
        assertEquals(0, captured?.offset)
        assertEquals(500, captured?.limit)
    }

    @Test
    fun `cancellation is never converted to an empty transcript`() = runTest {
        try {
            loadSessionMessages(SessionMessageLoadMode.COMPLETE) {
                throw CancellationException("session switched")
            }
            throw AssertionError("expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("session switched", error.message)
        }
    }

    @Test
    fun `message safety bound fails without returning a partial transcript`() = runTest {
        var calls = 0
        val result = loadSessionMessages(SessionMessageLoadMode.COMPLETE) { request ->
            calls++
            Result.success(page(request.offset, 500, returned = 500))
        }

        assertEquals(101, calls)
        assertEquals(SessionTranscriptTooLargeException::class, result.exceptionOrNull()?.let { it::class })
        assertEquals(null, result.getOrNull())
    }

    @Test
    fun `payload safety bound fails clearly before another page`() = runTest {
        val result = loadSessionMessages(SessionMessageLoadMode.COMPLETE) {
            Result.success(
                SessionMessagePage(
                    messages = emptyList(),
                    pagination = MessagePagination(returned = 0),
                    payloadChars = 32_000_001,
                ),
            )
        }

        assertEquals(SessionTranscriptTooLargeException::class, result.exceptionOrNull()?.let { it::class })
    }

    private fun page(
        start: Int,
        count: Int,
        returned: Int = count,
        pagination: MessagePagination? = MessagePagination(
            limit = SESSION_MESSAGE_PAGE_SIZE,
            offset = start,
            order = "oldest",
            returned = returned,
        ),
    ): SessionMessagePage = SessionMessagePage(
        messages = (start until start + count).map { index ->
            MessageItem(id = index.toString(), role = "user", content = JsonPrimitive("m$index"))
        },
        pagination = pagination,
        payloadChars = count * 20,
    )
}
