package com.hermesandroid.relay.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.data.ChatSession
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionDrawerTimestampTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `distinct activity is labeled updated`() {
        val session = session(startedAt = 1_000L, lastActivityAt = 120_000L)

        assertEquals(
            "Updated Just now",
            sessionTimestampText(session, Locale.US, context, nowMillis = 150_000L),
        )
    }

    @Test
    fun `relative timestamp changes when the drawer clock advances`() {
        val session = session(startedAt = 1_000L, lastActivityAt = 120_000L)

        assertEquals(
            "Updated Just now",
            sessionTimestampText(session, Locale.US, context, nowMillis = 150_000L),
        )
        assertEquals(
            "Updated 2m ago",
            sessionTimestampText(session, Locale.US, context, nowMillis = 240_000L),
        )
    }

    @Test
    fun `session without later activity keeps started label`() {
        val session = session(startedAt = 120_000L, lastActivityAt = 120_000L)

        assertEquals(
            "Started Just now",
            sessionTimestampText(session, Locale.US, context, nowMillis = 150_000L),
        )
    }

    private fun session(startedAt: Long, lastActivityAt: Long) = ChatSession(
        sessionId = "session",
        title = "Session",
        model = null,
        startedAt = startedAt,
        lastActivityAt = lastActivityAt,
    )
}
