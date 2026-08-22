package com.hermesandroid.relay.util

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedContentRequestTest {
    @Test
    fun textSendIsAcceptedWithoutChangingItsContent() {
        assertEquals(
            SharedContentPayload(text = "  https://example.test/page  "),
            extractSharedContent(
                action = Intent.ACTION_SEND,
                texts = listOf("  https://example.test/page  "),
                subject = "Page title",
                streamUriStrings = emptyList(),
                clipTexts = emptyList(),
                clipUriStrings = emptyList(),
            ),
        )
    }

    @Test
    fun mixedAndMultipleSharesPreserveTextAndDeduplicateUris() {
        assertEquals(
            SharedContentPayload(
                text = "Review these",
                uriStrings = listOf("content://one", "content://two"),
            ),
            extractSharedContent(
                action = Intent.ACTION_SEND_MULTIPLE,
                texts = listOf("Review these"),
                subject = null,
                streamUriStrings = listOf("content://one", "content://two"),
                clipTexts = emptyList(),
                clipUriStrings = listOf("content://one"),
            ),
        )
    }

    @Test
    fun externalFileAndCustomSchemesAreRejected() {
        assertEquals(
            SharedContentPayload(uriStrings = listOf("content://provider/shared/image.png")),
            extractSharedContent(
                action = Intent.ACTION_SEND_MULTIPLE,
                texts = emptyList(),
                subject = null,
                streamUriStrings = listOf(
                    "content://provider/shared/image.png",
                    "file:///data/user/0/com.axiomlabs.hermesrelay/files/private.txt",
                    "https://example.test/image.png",
                    "relay-private://secret",
                    "content:opaque",
                    "CONTENT://provider/not-canonical",
                ),
                clipTexts = emptyList(),
                clipUriStrings = emptyList(),
            ),
        )
        assertNull(
            extractSharedContent(
                Intent.ACTION_SEND,
                emptyList(),
                null,
                listOf("file:///data/local/tmp/not-shareable"),
                emptyList(),
                emptyList(),
            )
        )
    }

    @Test
    fun multipleShareIsBoundedAndReportsOmittedFiles() {
        val payload = requireNotNull(
            extractSharedContent(
                Intent.ACTION_SEND_MULTIPLE,
                emptyList(),
                null,
                (1..15).map { "content://provider/shared/$it" },
                emptyList(),
                emptyList(),
            )
        )

        assertEquals(MAX_SHARED_CONTENT_ATTACHMENTS, payload.uriStrings.size)
        assertEquals(5, payload.omittedUriCount)
    }

    @Test
    fun clipTextAndSubjectAreFallbacksButEmptySharesAreRejected() {
        assertEquals(
            SharedContentPayload(text = "first\nsecond\nclip text"),
            extractSharedContent(
                Intent.ACTION_SEND_MULTIPLE,
                listOf("first", "second"),
                "subject",
                emptyList(),
                listOf("clip text", "first"),
                emptyList(),
            ),
        )
        assertNull(
            extractSharedContent(
                Intent.ACTION_VIEW,
                listOf("hello"),
                null,
                emptyList(),
                emptyList(),
                emptyList(),
            )
        )
        assertNull(
            extractSharedContent(
                Intent.ACTION_SEND,
                listOf("  "),
                null,
                emptyList(),
                emptyList(),
                emptyList(),
            )
        )
    }

    @Test
    fun readinessAndConsumptionOnlyAffectTheMatchingRequest() {
        SharedContentRequest.pending.value?.let { SharedContentRequest.consume(it.id) }
        assertFalse(SharedContentRequest.tryRequest(null))
        assertTrue(SharedContentRequest.tryRequest(SharedContentPayload(text = "first")))
        val first = requireNotNull(SharedContentRequest.pending.value)

        assertTrue(
            SharedContentRequest.tryRequest(
                SharedContentPayload(uriStrings = listOf("content://second"))
            )
        )
        val second = requireNotNull(SharedContentRequest.pending.value)
        SharedContentRequest.markReady(first.id, "connection", "profile", "old-session")
        assertEquals(second, SharedContentRequest.pending.value)

        SharedContentRequest.markReady(second.id, "connection", "profile", "new-session")
        assertEquals(
            second.copy(
                ready = true,
                targetConnectionId = "connection",
                targetProfileId = "profile",
                targetSessionId = "new-session",
            ),
            SharedContentRequest.pending.value,
        )
        SharedContentRequest.consume(first.id)
        assertTrue(SharedContentRequest.pending.value != null)
        SharedContentRequest.consume(second.id)
        assertNull(SharedContentRequest.pending.value)
    }

    @Test
    fun failedPreparationStaysPendingUntilForegroundRetry() {
        SharedContentRequest.pending.value?.let { SharedContentRequest.consume(it.id) }
        assertTrue(SharedContentRequest.tryRequest(SharedContentPayload(text = "keep me")))
        val request = requireNotNull(SharedContentRequest.pending.value)

        SharedContentRequest.markPreparing(request.id)
        assertTrue(requireNotNull(SharedContentRequest.pending.value).preparing)
        SharedContentRequest.markFailed(request.id)
        val failed = requireNotNull(SharedContentRequest.pending.value)
        assertTrue(failed.failed)
        assertFalse(failed.preparing)

        SharedContentRequest.retryFailed()
        val retriable = requireNotNull(SharedContentRequest.pending.value)
        assertFalse(retriable.failed)
        assertFalse(retriable.preparing)
        assertEquals(request.payload, retriable.payload)
        SharedContentRequest.consume(request.id)
    }

    @Test
    fun shareWaitsForTheExactRestoredDestinationComposer() {
        val request = SharedContentDraftRequest(
            id = 1L,
            payload = SharedContentPayload(text = "https://example.test"),
            ready = true,
            targetConnectionId = "connection-a",
            targetProfileId = "profile-a",
            targetSessionId = "destination-session",
        )

        assertFalse(
            canApplySharedContent(
                request, "connection-b", "profile-a", "destination-session", draftRestored = true
            )
        )
        assertFalse(
            canApplySharedContent(
                request, "connection-a", "profile-b", "destination-session", draftRestored = true
            )
        )
        assertFalse(
            canApplySharedContent(
                request, "connection-a", "profile-a", "old-session", draftRestored = true
            )
        )
        assertFalse(
            canApplySharedContent(
                request, "connection-a", "profile-a", "destination-session", draftRestored = false
            )
        )
        assertTrue(
            canApplySharedContent(
                request, "connection-a", "profile-a", "destination-session", draftRestored = true
            )
        )
        assertTrue(
            canApplySharedContent(
                request.copy(targetSessionId = null),
                "connection-a",
                "profile-a",
                "new-session",
                draftRestored = true,
            )
        )
    }
}
