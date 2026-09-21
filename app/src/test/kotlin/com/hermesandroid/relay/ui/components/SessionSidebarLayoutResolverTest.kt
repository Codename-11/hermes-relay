package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSidebarLayoutResolverTest {

    @Test
    fun pinnedIntent_rendersSidebarAtThreshold() {
        assertEquals(
            SessionSidebarLayout.Sidebar,
            resolveSessionSidebarLayout(840, pinned = true),
        )
    }

    @Test
    fun pinnedIntent_rendersSidebarAboveThreshold() {
        assertEquals(
            SessionSidebarLayout.Sidebar,
            resolveSessionSidebarLayout(1280, pinned = true),
        )
    }

    @Test
    fun pinnedIntent_fallsBackToModalBelowThreshold() {
        assertEquals(
            SessionSidebarLayout.Modal,
            resolveSessionSidebarLayout(839, pinned = true),
        )
    }

    @Test
    fun unpinnedIntent_rendersModalEvenWhenWide() {
        assertEquals(
            SessionSidebarLayout.Modal,
            resolveSessionSidebarLayout(1024, pinned = false),
        )
    }

    @Test
    fun pinnedIntent_survivesNarrowWidthForNextWideLayout() {
        // Fold: modal at narrow widths. The intent itself is persisted at the
        // DataStore layer; the resolver keeps returning Modal until wide again.
        assertEquals(
            SessionSidebarLayout.Modal,
            resolveSessionSidebarLayout(360, pinned = true),
        )
        assertEquals(
            SessionSidebarLayout.Sidebar,
            resolveSessionSidebarLayout(900, pinned = true),
        )
    }

    // resolveDrawerGesturesEnabled — edge-swipe must not open the modal
    // drawer when supervised mode gates conversation history or when the
    // pinned sidebar already renders as the persistent sessions surface.

    @Test
    fun gestures_enabledInDefaultState() {
        assertTrue(
            resolveDrawerGesturesEnabled(supervisedHistoryAllowed = true, pinnedSidebar = false),
        )
    }

    @Test
    fun gestures_disabledWhenSupervisedHistoryBlocked() {
        assertFalse(
            resolveDrawerGesturesEnabled(supervisedHistoryAllowed = false, pinnedSidebar = false),
        )
    }

    @Test
    fun gestures_disabledWhilePinnedSidebarActive() {
        assertFalse(
            resolveDrawerGesturesEnabled(supervisedHistoryAllowed = true, pinnedSidebar = true),
        )
    }

    @Test
    fun gestures_disabledWhenSupervisedAndPinnedBothActive() {
        assertFalse(
            resolveDrawerGesturesEnabled(supervisedHistoryAllowed = false, pinnedSidebar = true),
        )
    }
}
