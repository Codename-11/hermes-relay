package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ProfileLockStore].
 *
 * These exercise the store's **logic** (per-connection key naming, the
 * null→remove unlock contract, the Server-default sentinel passthrough, and
 * per-connection isolation) against an in-memory [DataStore] rather than a
 * filesystem-backed [androidx.datastore.preferences.core.PreferenceDataStoreFactory].
 *
 * Why in-memory: a file-backed DataStore performs an atomic write-tmp-then-rename
 * on every `edit`, and on Windows that rename fails ("Unable to rename … multiple
 * instances of DataStore") when a prior test method's DataStore coroutine hasn't
 * released the file handle yet (scope cancellation is async). The in-memory
 * [DataStore] removes the OS dependency entirely — `edit { }`, `data.map { }`,
 * `remove`, and `clear` all behave identically, and persistence-to-disk is
 * DataStore's contract, not [ProfileLockStore]'s.
 */
class ProfileLockStoreTest {

    private lateinit var store: ProfileLockStore

    @Before
    fun setUp() {
        store = ProfileLockStore(InMemoryPreferencesDataStore())
    }

    @Test
    fun unset_connection_emitsNull() = runBlocking {
        // Fresh store — every connection id reads as null (unlocked) until set.
        assertNull(store.lockedProfileFlow("conn-1").first())
        assertNull(store.lockedProfileFlow("conn-unknown").first())
    }

    @Test
    fun setName_then_get_roundTrips() = runBlocking {
        store.setLockedProfile("conn-1", "mizu")
        assertEquals("mizu", store.lockedProfileFlow("conn-1").first())
    }

    @Test
    fun setServerDefaultSentinel_roundTrips() = runBlocking {
        // Locked-to-Server-default is stored as the sentinel and must read back
        // verbatim — it is NOT null (that would mean "unlocked").
        store.setLockedProfile("conn-1", AgentDisplay.SERVER_DEFAULT_PROFILE_KEY)

        val value = store.lockedProfileFlow("conn-1").first()
        assertEquals(AgentDisplay.SERVER_DEFAULT_PROFILE_KEY, value)
        // Belt-and-suspenders: the sentinel must be distinguishable from null.
        assertEquals("__server_default__", value)
    }

    @Test
    fun setNull_unlocks_removesTheKey() = runBlocking {
        store.setLockedProfile("conn-1", "mizu")
        assertEquals("mizu", store.lockedProfileFlow("conn-1").first())

        // Writing null removes the key — read path emits null (unlocked).
        store.setLockedProfile("conn-1", null)
        assertNull(store.lockedProfileFlow("conn-1").first())
    }

    @Test
    fun setNull_afterSentinel_unlocks() = runBlocking {
        // Going from "locked to Server default" back to "unlocked" must clear the
        // sentinel, not leave it stuck.
        store.setLockedProfile("conn-1", AgentDisplay.SERVER_DEFAULT_PROFILE_KEY)
        assertEquals(
            AgentDisplay.SERVER_DEFAULT_PROFILE_KEY,
            store.lockedProfileFlow("conn-1").first(),
        )

        store.setLockedProfile("conn-1", null)
        assertNull(store.lockedProfileFlow("conn-1").first())
    }

    @Test
    fun overwrite_replacesPriorValue() = runBlocking {
        store.setLockedProfile("conn-1", "mizu")
        store.setLockedProfile("conn-1", "coder")
        assertEquals("coder", store.lockedProfileFlow("conn-1").first())

        // Name → sentinel and back, to prove neither sticks.
        store.setLockedProfile("conn-1", AgentDisplay.SERVER_DEFAULT_PROFILE_KEY)
        assertEquals(
            AgentDisplay.SERVER_DEFAULT_PROFILE_KEY,
            store.lockedProfileFlow("conn-1").first(),
        )
        store.setLockedProfile("conn-1", "mizu")
        assertEquals("mizu", store.lockedProfileFlow("conn-1").first())
    }

    @Test
    fun perConnectionKeys_areIndependent() = runBlocking {
        // A lock pinned on one server must not leak onto another. Mix names and
        // the sentinel across connections.
        store.setLockedProfile("conn-A", "alpha")
        store.setLockedProfile("conn-B", AgentDisplay.SERVER_DEFAULT_PROFILE_KEY)
        store.setLockedProfile("conn-C", "gamma")

        assertEquals("alpha", store.lockedProfileFlow("conn-A").first())
        assertEquals(
            AgentDisplay.SERVER_DEFAULT_PROFILE_KEY,
            store.lockedProfileFlow("conn-B").first(),
        )
        assertEquals("gamma", store.lockedProfileFlow("conn-C").first())
    }

    @Test
    fun clear_removesOnlyTheGivenConnection() = runBlocking {
        store.setLockedProfile("conn-1", "mizu")
        store.setLockedProfile("conn-2", "coder")

        store.clear("conn-1")

        assertNull(store.lockedProfileFlow("conn-1").first())
        assertEquals("coder", store.lockedProfileFlow("conn-2").first())
    }

    @Test
    fun clearAll_wipesEveryConnection() = runBlocking {
        store.setLockedProfile("conn-A", "alpha")
        store.setLockedProfile("conn-B", AgentDisplay.SERVER_DEFAULT_PROFILE_KEY)
        store.setLockedProfile("conn-C", "gamma")

        store.clearAll()

        assertNull(store.lockedProfileFlow("conn-A").first())
        assertNull(store.lockedProfileFlow("conn-B").first())
        assertNull(store.lockedProfileFlow("conn-C").first())
    }
}

/**
 * Minimal in-memory [DataStore] of [Preferences] for unit tests — no filesystem,
 * so no atomic-rename / single-instance contention. [updateData] applies the
 * transform to the current snapshot and publishes it; [data] replays the latest
 * value to every collector (so `.first()` after a write sees the update).
 */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
