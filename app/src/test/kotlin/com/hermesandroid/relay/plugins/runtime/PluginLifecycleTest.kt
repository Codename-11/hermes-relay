package com.hermesandroid.relay.plugins.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLifecycleTest {
    private var now = 1_000L
    private val tracker = PluginLifecycleTracker { now }

    @Test
    fun lifecycle_distinguishesConnectionProfileAndSessionChanges() {
        val first = tracker.update(PluginHostContext("connection-a", "research", "session-1"))
        assertEquals(PluginLifecycleChange.CONNECTED, first.lastChange)

        now += 1
        val session = tracker.update(PluginHostContext("connection-a", "research", "session-2"))
        assertEquals(PluginLifecycleChange.SESSION_CHANGED, session.lastChange)

        now += 1
        val profile = tracker.update(PluginHostContext("connection-a", "default", "session-2"))
        assertEquals(PluginLifecycleChange.PROFILE_CHANGED, profile.lastChange)

        now += 1
        val connection = tracker.update(PluginHostContext("connection-b", "default", null))
        assertEquals(PluginLifecycleChange.CONNECTION_CHANGED, connection.lastChange)

        now += 1
        val disconnected = tracker.update(null)
        assertEquals(PluginLifecycleChange.DISCONNECTED, disconnected.lastChange)
        assertEquals(5L, disconnected.generation)
    }

    @Test
    fun unchangedContext_preservesGenerationAndTimestamp() {
        val context = PluginHostContext("connection-a", null, null)
        val initial = tracker.update(context)
        now += 500

        val unchanged = tracker.update(context)

        assertEquals(initial.generation, unchanged.generation)
        assertEquals(initial.changedAtEpochMillis, unchanged.changedAtEpochMillis)
        assertEquals(PluginLifecycleChange.CONNECTED, unchanged.lastChange)
    }

    @Test
    fun catalogPreview_carriesHostContextAndLiveRefreshState() {
        val context = PluginHostContext("connection-a", "research", "session-1")
        val preview = PluginCatalogPreview(
            context = context,
            pluginCount = 4,
            enabledPluginCount = 2,
            pageCount = 7,
            refreshedAtEpochMillis = now,
            liveRefreshEnabled = true,
        )

        assertEquals(context, preview.context)
        assertTrue(preview.liveRefreshEnabled)
        assertFalse(preview.copy(liveRefreshEnabled = false).liveRefreshEnabled)
        assertEquals(5_000L, PluginCatalogRefreshPolicy.VISIBLE_REFRESH_INTERVAL_MILLIS)
        assertEquals(5_000L, PluginCatalogRefreshPolicy.pageRefreshIntervalMillis(1))
        assertEquals(45_000L, PluginCatalogRefreshPolicy.pageRefreshIntervalMillis(45))
        assertEquals(300_000L, PluginCatalogRefreshPolicy.pageRefreshIntervalMillis(900))
        assertEquals(null, PluginCatalogRefreshPolicy.pageRefreshIntervalMillis(null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun hostContext_rejectsBlankSessionIdentity() {
        PluginHostContext("connection-a", null, " ")
    }
}
