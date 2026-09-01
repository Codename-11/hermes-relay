package com.hermesandroid.relay.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatResponsiveLayoutTest {
    @Test
    fun compactPhonesKeepExistingUnboundedLayout() {
        val layout = chatResponsiveLayout(screenWidthDp = 599)

        assertNull(layout.introMaxWidth)
        assertNull(layout.avatarSize)
        assertNull(layout.chromeMaxWidth)
    }

    @Test
    fun mediumWindowsUseBoundedChatSurfaces() {
        val layout = chatResponsiveLayout(screenWidthDp = 600)

        assertEquals(600.dp, layout.introMaxWidth)
        assertEquals(300.dp, layout.avatarSize)
        assertEquals(760.dp, layout.chromeMaxWidth)
    }

    @Test
    fun expandedTabletsUseReadableCenteredRails() {
        val layout = chatResponsiveLayout(screenWidthDp = 1280)

        assertEquals(720.dp, layout.introMaxWidth)
        assertEquals(360.dp, layout.avatarSize)
        assertEquals(960.dp, layout.chromeMaxWidth)
    }
}
