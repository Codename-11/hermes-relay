package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the offline Demo-mode transcript. No Android / network:
 * [DemoContent] is plain data classes, so these run without Robolectric.
 *
 * The transcript is the user-facing artifact of Demo mode (see
 * `docs/play-store-listing.md` App access). These tests pin the showcase
 * contract — Markdown, a tool-progress card, and a rich [HermesCard] — and the
 * "renders with zero network" guarantee that lets the demo run in airplane mode.
 */
class DemoContentTest {

    @Test
    fun transcriptHasBothRolesAndIsNonEmpty() {
        val transcript = DemoContent.transcript()
        assertTrue("transcript should not be empty", transcript.isNotEmpty())
        assertTrue(
            "transcript should contain at least one user message",
            transcript.any { it.role == MessageRole.USER && it.content.isNotBlank() },
        )
        assertTrue(
            "transcript should contain at least one assistant message",
            transcript.any { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() },
        )
    }

    @Test
    fun assistantReplyShowsMarkdownIncludingACodeBlock() {
        val assistant = DemoContent.transcript().filter { it.role == MessageRole.ASSISTANT }
        // Bold markdown somewhere in the tour.
        assertTrue(
            "assistant reply should contain Markdown emphasis",
            assistant.any { it.content.contains("**") },
        )
        // A fenced code block to exercise code rendering.
        assertTrue(
            "assistant reply should contain a fenced code block",
            assistant.any { it.content.contains("```") },
        )
    }

    @Test
    fun transcriptIncludesACompletedToolProgressCard() {
        val toolCalls = DemoContent.transcript().flatMap { it.toolCalls }
        assertTrue("transcript should include at least one tool call", toolCalls.isNotEmpty())
        val tool = toolCalls.first()
        assertTrue("tool call should have a name", tool.name.isNotBlank())
        assertTrue("demo tool call should be complete", tool.isComplete)
        assertEquals("demo tool call should be successful", true, tool.success)
        // A finished tool renders a duration — completedAt must be after startedAt.
        assertNotNull("completed tool should have a completedAt", tool.completedAt)
        assertTrue(tool.completedAt!! > tool.startedAt)
    }

    @Test
    fun transcriptIncludesARichCard() {
        val cards = DemoContent.transcript().flatMap { it.cards }
        assertTrue("transcript should include at least one HermesCard", cards.isNotEmpty())
        val card = cards.first()
        assertTrue("card should have a type", card.type.isNotBlank())
        assertTrue(
            "card should have a title or fields to render",
            !card.title.isNullOrBlank() || card.fields.isNotEmpty(),
        )
    }

    @Test
    fun transcriptRendersWithZeroNetwork() {
        // The whole point of demo mode: it must render in airplane mode. Every
        // message is terminal (not mid-stream), and no attachment carries a
        // relay token or LOADING state that would trigger a fetch.
        val transcript = DemoContent.transcript()
        transcript.forEach { msg ->
            assertFalse("demo message must not be mid-stream: ${msg.id}", msg.isStreaming)
            msg.attachments.forEach { att ->
                assertEquals(
                    "demo attachment must be pre-loaded (no fetch): ${msg.id}",
                    AttachmentState.LOADED,
                    att.state,
                )
                assertTrue(
                    "demo attachment must not carry a relay token (would fetch): ${msg.id}",
                    att.relayToken.isNullOrBlank(),
                )
            }
        }
    }

    @Test
    fun assistantMessagesAreClientOnlySoNoServerReconcileWipesThem() {
        // Demo bubbles have no server-side row; marking them clientOnly keeps the
        // history-reconcile from ever deleting them (matches the real app's
        // contract for locally-authored messages).
        DemoContent.transcript()
            .filter { it.role == MessageRole.ASSISTANT }
            .forEach { assertTrue("assistant demo bubble should be clientOnly", it.clientOnly) }
    }

    @Test
    fun transcriptIsDeterministic() {
        // Fixed timestamps (DEMO_BASE_TIME + offsets) mean two builds are equal —
        // the demo looks the same every launch and the content is testable.
        assertEquals(DemoContent.transcript(), DemoContent.transcript())
    }
}
