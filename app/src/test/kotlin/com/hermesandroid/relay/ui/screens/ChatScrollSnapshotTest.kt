package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `bounded follow ramps without transcript-distance velocity`() {
        val shortSteps = followSteps(distance = 480, viewportHeight = 840)
        val longSteps = followSteps(distance = 48_000, viewportHeight = 840)

        assertTrue(shortSteps.take(3).zipWithNext().all { (a, b) -> b >= a })
        assertTrue(shortSteps.takeLast(3).zipWithNext().all { (a, b) -> b <= a })
        assertTrue(longSteps.max() <= 56)
        assertEquals(shortSteps.max(), longSteps.max())
        assertEquals(480, shortSteps.sum())
        assertEquals(48_000, longSteps.sum())
    }

    @Test
    fun `reduced motion consumes the measured delta without animation frames`() {
        assertEquals(
            720,
            boundedBottomFollowStep(
                remainingPx = 720,
                previousStepPx = 0,
                viewportHeightPx = 840,
                motionEnabled = false,
            ),
        )
    }

    @Test
    fun `history refresh and resume preserve an already positioned session`() {
        assertEquals(
            false,
            shouldInitiallyPositionConversation(
                positionedSessionId = "session-a",
                currentSessionId = "session-a",
                isLoadingHistory = false,
                hasMessages = true,
            ),
        )
        assertEquals(
            true,
            shouldInitiallyPositionConversation(
                positionedSessionId = "session-a",
                currentSessionId = "session-b",
                isLoadingHistory = false,
                hasMessages = true,
            ),
        )
    }

    @Test
    fun `only clear user actions rearm bottom follow`() {
        val passiveEvents = listOf(
            ChatFollowEvent.StreamStarted,
            ChatFollowEvent.StreamUpdated,
            ChatFollowEvent.QueuedTurnStarted,
            ChatFollowEvent.TurnCompleted,
            ChatFollowEvent.HistoryRefreshed,
            ChatFollowEvent.AppResumed,
        )

        passiveEvents.forEach { event ->
            assertEquals(true, reduceUserScrolledAway(current = true, event = event))
        }
        assertEquals(
            false,
            reduceUserScrolledAway(current = true, event = ChatFollowEvent.UserSend),
        )
        assertEquals(
            false,
            reduceUserScrolledAway(current = true, event = ChatFollowEvent.ReturnedToBottom),
        )
        assertEquals(
            false,
            reduceUserScrolledAway(current = true, event = ChatFollowEvent.JumpToLatest),
        )
    }

    @Test
    fun `user movement cancels follow until explicitly rearmed`() {
        val movedAway = reduceUserScrolledAway(
            current = false,
            event = ChatFollowEvent.UserMovedAway,
        )
        val stillAway = reduceUserScrolledAway(
            current = movedAway,
            event = ChatFollowEvent.StreamUpdated,
        )

        assertEquals(true, movedAway)
        assertEquals(true, stillAway)
    }

    @Test
    fun `settled markdown tail growth remains owned at the bottom`() {
        val previous = viewportSnapshot(
            tailSizePx = 420,
            visibleBottomDistancePx = 0,
            followTailGrowth = true,
        )
        val markdownPromotion = previous.copy(
            tailSizePx = 510,
            visibleBottomDistancePx = 90,
        )

        assertEquals(90, ownedBottomFollowScroll(previous, markdownPromotion))
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

    private fun followSteps(distance: Int, viewportHeight: Int): List<Int> {
        var remaining = distance
        var previous = 0
        return buildList {
            while (remaining > 0) {
                val step = boundedBottomFollowStep(
                    remainingPx = remaining,
                    previousStepPx = previous,
                    viewportHeightPx = viewportHeight,
                    motionEnabled = true,
                )
                add(step)
                remaining -= step
                previous = step
            }
        }
    }
}
