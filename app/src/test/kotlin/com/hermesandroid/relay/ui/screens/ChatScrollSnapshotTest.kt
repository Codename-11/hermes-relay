package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatScrollSnapshotTest {
    @Test
    fun `visible footer distance is the exact follow amount`() {
        val previous = viewportSnapshot(tailSizePx = 400, visibleBottomDistancePx = 0)
        val current = viewportSnapshot(tailSizePx = 432, visibleBottomDistancePx = 7)

        assertEquals(7, requiredBottomFollowScroll(previous, current))
    }

    @Test
    fun `viewport loss follows keyboard when footer leaves the viewport`() {
        val previous = viewportSnapshot(viewportHeightPx = 1_000)
        val current = viewportSnapshot(
            viewportHeightPx = 680,
            visibleBottomDistancePx = null,
            followTailGrowth = false,
            followViewportResize = true,
        )

        assertEquals(320, requiredBottomFollowScroll(previous, current))
    }

    @Test
    fun `history reading never follows viewport or tail changes`() {
        val previous = viewportSnapshot(tailSizePx = 400, viewportHeightPx = 1_000)
        val current = viewportSnapshot(
            tailSizePx = 520,
            viewportHeightPx = 680,
            visibleBottomDistancePx = 120,
            followTailGrowth = false,
            followViewportResize = false,
        )

        assertEquals(0, requiredBottomFollowScroll(previous, current))
    }

    @Test
    fun `keyboard follow arms only from the conversation bottom`() {
        assertEquals(
            true,
            shouldFollowImeAfterInsetChange(
                wasFollowing = false,
                previousImeBottomPx = 0,
                currentImeBottomPx = 1,
                wasAtBottom = true,
                userDragging = false,
            ),
        )
        assertEquals(
            false,
            shouldFollowImeAfterInsetChange(
                wasFollowing = false,
                previousImeBottomPx = 0,
                currentImeBottomPx = 1,
                wasAtBottom = false,
                userDragging = false,
            ),
        )
    }

    @Test
    fun `keyboard follow survives animation and clears on close or drag`() {
        assertEquals(
            true,
            shouldFollowImeAfterInsetChange(
                wasFollowing = true,
                previousImeBottomPx = 120,
                currentImeBottomPx = 480,
                wasAtBottom = false,
                userDragging = false,
            ),
        )
        assertEquals(
            false,
            shouldFollowImeAfterInsetChange(
                wasFollowing = true,
                previousImeBottomPx = 480,
                currentImeBottomPx = 0,
                wasAtBottom = true,
                userDragging = false,
            ),
        )
        assertEquals(
            false,
            shouldFollowImeAfterInsetChange(
                wasFollowing = true,
                previousImeBottomPx = 120,
                currentImeBottomPx = 480,
                wasAtBottom = true,
                userDragging = true,
            ),
        )
    }

    @Test
    fun `stream start captures the live tail renderer`() {
        assertEquals(
            "assistant-live",
            retainedLiveTailAfterTransition(
                retainedUiKey = null,
                streamStarted = true,
                lastMessageUiKey = "assistant-live",
            ),
        )
    }

    @Test
    fun `same-tail completion keeps the stable live renderer`() {
        assertEquals(
            "assistant-live",
            retainedLiveTailAfterTransition(
                retainedUiKey = "assistant-live",
                streamStarted = false,
                lastMessageUiKey = "assistant-live",
            ),
        )
    }

    @Test
    fun `new tail releases the retained renderer`() {
        assertNull(
            retainedLiveTailAfterTransition(
                retainedUiKey = "assistant-live",
                streamStarted = false,
                lastMessageUiKey = "next-row",
            ),
        )
        assertNull(
            retainedLiveTailAfterTransition(
                retainedUiKey = null,
                streamStarted = false,
                lastMessageUiKey = "next-row",
            ),
        )
    }

    @Test
    fun `server id adoption remains an observable tail change`() {
        val local = snapshot()
        val reconciled = local.copy(lastMessageId = "assistant-server-id")

        assertNotEquals(local, reconciled)
    }

    private fun snapshot() = ChatScrollSnapshot(
        messageCount = 8,
        lastMessageId = "assistant-live-id",
        lastMessageUiKey = "assistant-ui-key",
        lastContentLength = 12_000,
        lastThinkingLength = 1_200,
        lastToolCallCount = 2,
        isStreaming = false,
    )

    private fun viewportSnapshot(
        tailSizePx: Int? = 400,
        viewportHeightPx: Int = 1_000,
        visibleBottomDistancePx: Int? = null,
        followTailGrowth: Boolean = true,
        followViewportResize: Boolean = false,
    ) = ChatViewportFollowSnapshot(
        tailUiKey = "assistant-live",
        tailSizePx = tailSizePx,
        viewportHeightPx = viewportHeightPx,
        visibleBottomDistancePx = visibleBottomDistancePx,
        followTailGrowth = followTailGrowth,
        followViewportResize = followViewportResize,
    )
}
