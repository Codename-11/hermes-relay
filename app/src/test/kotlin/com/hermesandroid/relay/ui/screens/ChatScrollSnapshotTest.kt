package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatScrollSnapshotTest {
    @Test
    fun `ordinary viewport resize follows an owned restored conversation`() {
        assertEquals(
            true,
            shouldFollowConversationViewportResize(
                userScrolledAway = false,
                userDragging = false,
                imeBottomPx = 0,
                followImeResize = false,
                voiceDockAnchorTransitionActive = false,
            ),
        )
    }

    @Test
    fun `voice dock transition preserves its leading row anchor`() {
        assertEquals(
            false,
            shouldFollowConversationViewportResize(
                userScrolledAway = false,
                userDragging = false,
                imeBottomPx = 0,
                followImeResize = false,
                voiceDockAnchorTransitionActive = true,
            ),
        )
    }

    @Test
    fun `ime resize follows only after bottom ownership was captured`() {
        assertEquals(
            false,
            shouldFollowConversationViewportResize(
                userScrolledAway = false,
                userDragging = false,
                imeBottomPx = 320,
                followImeResize = false,
                voiceDockAnchorTransitionActive = false,
            ),
        )
        assertEquals(
            true,
            shouldFollowConversationViewportResize(
                userScrolledAway = false,
                userDragging = false,
                imeBottomPx = 320,
                followImeResize = true,
                voiceDockAnchorTransitionActive = false,
            ),
        )
    }

    @Test
    fun `same transcript late viewport or tail layout is corrected`() {
        val previous = viewportSnapshot(
            tailSizePx = 400,
            viewportHeightPx = 1_000,
            visibleBottomDistancePx = 0,
        )

        assertEquals(
            true,
            shouldCorrectConversationBottomAfterLayout(
                previous = previous,
                current = previous.copy(viewportHeightPx = 960, visibleBottomDistancePx = 40),
                atExactBottom = false,
                userScrolledAway = false,
                userDragging = false,
                isStreaming = false,
                smoothAutoScroll = false,
                viewportFollowAllowed = true,
            ),
        )
        assertEquals(
            true,
            shouldCorrectConversationBottomAfterLayout(
                previous = previous,
                current = previous.copy(tailSizePx = 460, visibleBottomDistancePx = null),
                atExactBottom = false,
                userScrolledAway = false,
                userDragging = false,
                isStreaming = false,
                smoothAutoScroll = false,
                viewportFollowAllowed = true,
            ),
        )
    }

    @Test
    fun `late layout correction preserves reader and new message ownership`() {
        val previous = viewportSnapshot(visibleBottomDistancePx = 0)
        val remeasured = previous.copy(viewportHeightPx = 960, visibleBottomDistancePx = 40)
        fun correction(
            current: ChatViewportFollowSnapshot = remeasured,
            exact: Boolean = false,
            away: Boolean = false,
            dragging: Boolean = false,
        ) = shouldCorrectConversationBottomAfterLayout(
            previous = previous,
            current = current,
            atExactBottom = exact,
            userScrolledAway = away,
            userDragging = dragging,
            isStreaming = false,
            smoothAutoScroll = true,
            viewportFollowAllowed = true,
        )

        assertEquals(false, correction(exact = true))
        assertEquals(false, correction(away = true))
        assertEquals(false, correction(dragging = true))
        assertEquals(
            false,
            correction(
                current = remeasured.copy(
                    totalItemsCount = remeasured.totalItemsCount + 1,
                    tailUiKey = "new-tail",
                ),
            ),
        )
    }

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
    fun `ordinary viewport ownership does not follow a new tail`() {
        val previous = viewportSnapshot(visibleBottomDistancePx = 0)
        val newTail = previous.copy(
            totalItemsCount = previous.totalItemsCount + 1,
            tailUiKey = "assistant-new",
            visibleBottomDistancePx = 48,
            followTailGrowth = false,
            followViewportResize = true,
        )

        assertEquals(0, ownedBottomFollowScroll(previous, newTail))
        assertEquals(
            48,
            ownedBottomFollowScroll(
                previous,
                newTail.copy(followTailGrowth = true),
            ),
        )
    }

    @Test
    fun `voice anchor transition suppresses retained tail following`() {
        val previous = viewportSnapshot(visibleBottomDistancePx = 0)
        val duringTransition = previous.copy(
            visibleBottomDistancePx = 52,
            followTailGrowth = false,
            followViewportResize = false,
        )

        assertEquals(0, ownedBottomFollowScroll(previous, duringTransition))
        assertEquals(
            false,
            shouldCorrectConversationBottomAfterLayout(
                previous = previous,
                current = duringTransition,
                atExactBottom = false,
                userScrolledAway = false,
                userDragging = false,
                isStreaming = false,
                smoothAutoScroll = true,
                viewportFollowAllowed = false,
            ),
        )
    }

    @Test
    fun `voice transcript can correct late layout after anchor transition`() {
        assertEquals(
            true,
            shouldFollowConversationViewportResize(
                userScrolledAway = false,
                userDragging = false,
                imeBottomPx = 0,
                followImeResize = false,
                voiceDockAnchorTransitionActive = false,
            ),
        )
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
    fun `keyboard already open on first chat layout captures bottom ownership`() {
        assertEquals(
            true,
            shouldFollowImeAfterInsetChange(
                wasFollowing = false,
                previousImeBottomPx = 0,
                currentImeBottomPx = 480,
                wasAtBottom = true,
                userDragging = false,
            ),
        )
    }

    @Test
    fun `expanded tail remeasurement follows while keyboard ownership is retained`() {
        val previous = viewportSnapshot(
            tailSizePx = 320,
            viewportHeightPx = 680,
            visibleBottomDistancePx = 0,
            followTailGrowth = false,
            followViewportResize = true,
        )
        val expanded = previous.copy(
            tailSizePx = 620,
            visibleBottomDistancePx = 300,
        )

        assertEquals(300, ownedBottomFollowScroll(previous, expanded))
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
    fun `completion and keyboard settle exactly only while bottom follow is owned`() {
        assertEquals(
            true,
            shouldExactlySettleConversation(
                autoFollowEnabled = true,
                userScrolledAway = false,
                userDragging = false,
                hasMessages = true,
            ),
        )
        assertEquals(
            false,
            shouldExactlySettleConversation(
                autoFollowEnabled = true,
                userScrolledAway = true,
                userDragging = false,
                hasMessages = true,
            ),
        )
        assertEquals(
            false,
            shouldExactlySettleConversation(
                autoFollowEnabled = true,
                userScrolledAway = false,
                userDragging = true,
                hasMessages = true,
            ),
        )
    }

    @Test
    fun `disabled auto follow and empty conversations do not request exact settlement`() {
        assertEquals(
            false,
            shouldExactlySettleConversation(
                autoFollowEnabled = false,
                userScrolledAway = false,
                userDragging = false,
                hasMessages = true,
            ),
        )
        assertEquals(
            false,
            shouldExactlySettleConversation(
                autoFollowEnabled = true,
                userScrolledAway = false,
                userDragging = false,
                hasMessages = false,
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
                streamCompleted = false,
                lastMessageUiKey = "assistant-live",
            ),
        )
    }

    @Test
    fun `same-tail completion releases the live renderer for markdown`() {
        assertNull(
            retainedLiveTailAfterTransition(
                retainedUiKey = "assistant-live",
                streamStarted = false,
                streamCompleted = true,
                lastMessageUiKey = "assistant-live",
            ),
        )
    }

    @Test
    fun `late completion survives server id adoption on the same ui row`() {
        val live = snapshot().copy(
            lastMessageId = "assistant-client-id",
            lastContentLength = 40,
            isStreaming = true,
        )
        val completed = live.copy(
            lastMessageId = "assistant-server-id",
            lastContentLength = 120,
            isStreaming = false,
        )

        assertEquals(true, completed.isCompletionAfter(live))
        assertNull(
            retainedLiveTailAfterTransition(
                retainedUiKey = "assistant-ui-key",
                streamStarted = false,
                streamCompleted = completed.isCompletionAfter(live),
                lastMessageUiKey = completed.lastMessageUiKey,
            ),
        )
    }

    @Test
    fun `restored settled history does not invent a live completion transition`() {
        assertEquals(false, snapshot().isCompletionAfter(previous = null))
    }

    @Test
    fun `new tail releases the retained renderer`() {
        assertNull(
            retainedLiveTailAfterTransition(
                retainedUiKey = "assistant-live",
                streamStarted = false,
                streamCompleted = false,
                lastMessageUiKey = "next-row",
            ),
        )
        assertNull(
            retainedLiveTailAfterTransition(
                retainedUiKey = null,
                streamStarted = false,
                streamCompleted = false,
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
        totalItemsCount: Int = 10,
        tailSizePx: Int? = 400,
        viewportHeightPx: Int = 1_000,
        visibleBottomDistancePx: Int? = null,
        followTailGrowth: Boolean = true,
        followViewportResize: Boolean = false,
    ) = ChatViewportFollowSnapshot(
        totalItemsCount = totalItemsCount,
        tailUiKey = "assistant-live",
        tailSizePx = tailSizePx,
        viewportHeightPx = viewportHeightPx,
        visibleBottomDistancePx = visibleBottomDistancePx,
        followTailGrowth = followTailGrowth,
        followViewportResize = followViewportResize,
    )
}
