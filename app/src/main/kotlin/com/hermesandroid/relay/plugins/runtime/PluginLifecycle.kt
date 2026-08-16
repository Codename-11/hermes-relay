package com.hermesandroid.relay.plugins.runtime

/** Host-stamped context for plugin UI. It is never accepted from plugin JSON. */
data class PluginHostContext(
    val connectionId: String,
    val profileName: String?,
    val sessionId: String?,
) {
    init {
        require(connectionId.isNotBlank()) { "connectionId must not be blank" }
        require(sessionId == null || sessionId.isNotBlank()) { "sessionId must be null or non-blank" }
    }
}

enum class PluginLifecycleChange {
    CONNECTED,
    DISCONNECTED,
    CONNECTION_CHANGED,
    PROFILE_CHANGED,
    SESSION_CHANGED,
    UNCHANGED,
}

data class PluginLifecycleSnapshot(
    val context: PluginHostContext?,
    val generation: Long,
    val changedAtEpochMillis: Long,
    val lastChange: PluginLifecycleChange,
)

/**
 * Tracks provenance changes independently from UI navigation.
 *
 * A connection or profile change invalidates catalog/page ownership. A session change is
 * metadata-only: it updates the context available to the active surface without replacing the
 * authenticated Dashboard client or discarding the catalog.
 */
class PluginLifecycleTracker(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var snapshot: PluginLifecycleSnapshot = PluginLifecycleSnapshot(
        context = null,
        generation = 0,
        changedAtEpochMillis = clock(),
        lastChange = PluginLifecycleChange.UNCHANGED,
    )
        private set

    fun update(next: PluginHostContext?): PluginLifecycleSnapshot {
        val previous = snapshot.context
        val change = when {
            previous == next -> PluginLifecycleChange.UNCHANGED
            previous == null && next != null -> PluginLifecycleChange.CONNECTED
            previous != null && next == null -> PluginLifecycleChange.DISCONNECTED
            previous?.connectionId != next?.connectionId -> PluginLifecycleChange.CONNECTION_CHANGED
            previous?.profileName != next?.profileName -> PluginLifecycleChange.PROFILE_CHANGED
            else -> PluginLifecycleChange.SESSION_CHANGED
        }
        if (change != PluginLifecycleChange.UNCHANGED) {
            snapshot = PluginLifecycleSnapshot(
                context = next,
                generation = snapshot.generation + 1,
                changedAtEpochMillis = clock(),
                lastChange = change,
            )
        }
        return snapshot
    }
}

data class PluginCatalogPreview(
    val context: PluginHostContext,
    val pluginCount: Int,
    val enabledPluginCount: Int,
    val pageCount: Int,
    val refreshedAtEpochMillis: Long,
    val liveRefreshEnabled: Boolean,
) {
    init {
        require(pluginCount >= 0 && enabledPluginCount in 0..pluginCount)
        require(pageCount >= 0)
    }
}

object PluginCatalogRefreshPolicy {
    const val VISIBLE_REFRESH_INTERVAL_MILLIS: Long = 5_000L
    const val MIN_PAGE_REFRESH_SECONDS: Int = 5
    const val MAX_PAGE_REFRESH_SECONDS: Int = 300

    fun pageRefreshIntervalMillis(requestedSeconds: Int?): Long? = requestedSeconds
        ?.coerceIn(MIN_PAGE_REFRESH_SECONDS, MAX_PAGE_REFRESH_SECONDS)
        ?.times(1_000L)
}
