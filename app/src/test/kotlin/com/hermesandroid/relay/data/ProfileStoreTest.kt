package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM unit tests for [ProfileStore].
 *
 * Exercises the public suspend API against a real [DataStore] backed by a
 * temp-folder preferences file — no Android Context, no instrumentation.
 * The cross-call behavior (writes visible to subsequent reads) is what we
 * actually want to verify; instantiating a fresh store per test via
 * [TemporaryFolder] keeps the suite hermetic.
 */
class ProfileStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: ProfileStore

    @Before
    fun setUp() {
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("test_profiles.preferences_pb") },
        )
        store = ProfileStore(dataStore)
    }

    @After
    fun tearDown() {
        // DataStore doesn't expose an explicit close on this API surface —
        // letting the scope cancel is sufficient for test isolation.
    }

    // --- Sample builder -----------------------------------------------------

    private fun sampleProfile(
        id: String = "11111111-2222-3333-4444-555555555555",
        label: String = "local",
        apiUrl: String = "http://localhost:8642",
        relayUrl: String = "wss://localhost:8767",
    ): Profile = Profile(
        id = id,
        label = label,
        apiServerUrl = apiUrl,
        relayUrl = relayUrl,
        tokenStoreKey = Profile.buildTokenStoreKey(id),
    )

    // Read-until-matches helper: the public StateFlows are hydrated on a
    // background coroutine in ProfileStore.init, so a naive `.value` read
    // right after a write can race. We poll with a short timeout.
    private suspend fun <T> awaitFlowValue(
        flow: kotlinx.coroutines.flow.Flow<T>,
        predicate: (T) -> Boolean,
    ): T = withTimeout(2_000) {
        flow.first { predicate(it) }
    }

    // --- Tests --------------------------------------------------------------

    @Test
    fun addProfile_persistsAndEmitsInFlow() = runTest {
        val profile = sampleProfile()
        store.addProfile(profile)

        val list = awaitFlowValue(store.profiles) { it.any { p -> p.id == profile.id } }
        assertEquals(1, list.size)
        assertEquals(profile.id, list[0].id)
        assertEquals(profile.label, list[0].label)

        // Reload via a fresh ProfileStore pointed at the same DataStore.
        // Proves the write hit the actual preferences file, not just the
        // in-memory StateFlow.
        val reloaded = ProfileStore(dataStore)
        val reloadedList = awaitFlowValue(reloaded.profiles) { it.isNotEmpty() }
        assertEquals(1, reloadedList.size)
        assertEquals(profile.id, reloadedList[0].id)
    }

    @Test
    fun removeProfile_removesFromList() = runTest {
        val a = sampleProfile(id = "aaaaaaaa-0000-0000-0000-000000000000", label = "A")
        val b = sampleProfile(id = "bbbbbbbb-0000-0000-0000-000000000000", label = "B")
        store.addProfile(a)
        store.addProfile(b)
        awaitFlowValue(store.profiles) { it.size == 2 }

        store.removeProfile(a.id)
        val after = awaitFlowValue(store.profiles) { it.size == 1 }
        assertEquals(1, after.size)
        assertEquals(b.id, after[0].id)
    }

    @Test
    fun removeProfile_clearsActivePointerWhenRemovingActive() = runTest {
        val a = sampleProfile(id = "aaaaaaaa-0000-0000-0000-000000000000", label = "A")
        store.addProfile(a)
        store.setActiveProfile(a.id)
        awaitFlowValue(store.activeProfileId) { it == a.id }

        store.removeProfile(a.id)
        val active = awaitFlowValue(store.activeProfileId) { it == null }
        assertNull(active)
    }

    @Test
    fun setActiveProfile_updatesActiveProfileId() = runTest {
        val a = sampleProfile(id = "aaaaaaaa-0000-0000-0000-000000000000", label = "A")
        val b = sampleProfile(id = "bbbbbbbb-0000-0000-0000-000000000000", label = "B")
        store.addProfile(a)
        store.addProfile(b)

        store.setActiveProfile(b.id)
        val activeId = awaitFlowValue(store.activeProfileId) { it == b.id }
        assertEquals(b.id, activeId)
    }

    @Test
    fun activeProfile_derivesCorrectly() = runTest {
        val a = sampleProfile(id = "aaaaaaaa-0000-0000-0000-000000000000", label = "A")
        store.addProfile(a)
        store.setActiveProfile(a.id)

        val active = awaitFlowValue(store.activeProfile) { it?.id == a.id }
        assertNotNull(active)
        assertEquals(a.id, active!!.id)
        assertEquals("A", active.label)

        // Active ID referring to a non-existent profile → null. Force the
        // situation by writing an active ID that no profile claims.
        store.setActiveProfile("nonexistent-id")
        val gone = awaitFlowValue(store.activeProfile) { it == null }
        assertNull(gone)
    }

    @Test
    fun updateProfile_replacesMatchingIdOnly() = runTest {
        val a = sampleProfile(id = "aaaaaaaa-0000-0000-0000-000000000000", label = "A")
        val b = sampleProfile(id = "bbbbbbbb-0000-0000-0000-000000000000", label = "B")
        store.addProfile(a)
        store.addProfile(b)

        store.updateProfile(a.copy(label = "A-renamed"))
        val list = awaitFlowValue(store.profiles) {
            it.firstOrNull { p -> p.id == a.id }?.label == "A-renamed"
        }
        assertEquals(2, list.size)
        assertEquals("A-renamed", list.first { it.id == a.id }.label)
        assertEquals("B", list.first { it.id == b.id }.label)
    }

    @Test
    fun setLastActiveSessionId_updatesProfile() = runTest {
        val a = sampleProfile()
        store.addProfile(a)

        store.setLastActiveSessionId(a.id, "sess-123")
        val list = awaitFlowValue(store.profiles) {
            it.firstOrNull()?.lastActiveSessionId == "sess-123"
        }
        assertEquals("sess-123", list[0].lastActiveSessionId)

        store.setLastActiveSessionId(a.id, null)
        val cleared = awaitFlowValue(store.profiles) {
            it.firstOrNull()?.lastActiveSessionId == null
        }
        assertNull(cleared[0].lastActiveSessionId)
    }

    @Test
    fun markPaired_stampsMetadata() = runTest {
        val a = sampleProfile()
        store.addProfile(a)

        store.markPaired(
            profileId = a.id,
            pairedAt = 1_700_000_000L,
            transportHint = "wss",
            expiresAt = 1_700_100_000L,
        )

        val list = awaitFlowValue(store.profiles) {
            it.firstOrNull()?.pairedAt == 1_700_000_000L
        }
        assertEquals(1_700_000_000L, list[0].pairedAt)
        assertEquals("wss", list[0].transportHint)
        assertEquals(1_700_100_000L, list[0].expiresAt)
    }

    @Test
    fun migrateLegacyProfileIfNeeded_seedsProfile0WhenEmpty() = runTest {
        store.migrateLegacyProfileIfNeeded(
            legacyApiServerUrl = "http://10.0.0.5:8642",
            legacyRelayUrl = "wss://10.0.0.5:8767",
            legacyLastSessionId = "sess-legacy",
        )

        val list = awaitFlowValue(store.profiles) { it.isNotEmpty() }
        assertEquals(1, list.size)
        val seeded = list[0]
        assertEquals("10.0.0.5", seeded.label)
        assertEquals("http://10.0.0.5:8642", seeded.apiServerUrl)
        assertEquals("wss://10.0.0.5:8767", seeded.relayUrl)
        assertEquals(Profile.LEGACY_TOKEN_STORE_KEY, seeded.tokenStoreKey)
        assertEquals("sess-legacy", seeded.lastActiveSessionId)

        // Active pointer should now target the seed.
        val active = awaitFlowValue(store.activeProfileId) { it != null }
        assertEquals(seeded.id, active)
    }

    @Test
    fun migrateLegacyProfileIfNeeded_isIdempotent() = runTest {
        val existing = sampleProfile(
            id = "11111111-0000-0000-0000-000000000000",
            label = "pre-existing",
        )
        store.addProfile(existing)
        awaitFlowValue(store.profiles) { it.isNotEmpty() }

        store.migrateLegacyProfileIfNeeded(
            legacyApiServerUrl = "http://should-be-ignored:8642",
            legacyRelayUrl = "wss://should-be-ignored:8767",
            legacyLastSessionId = null,
        )

        // Second migration call must not change the list at all.
        val list = store.profiles.value
        assertEquals(1, list.size)
        assertEquals(existing.id, list[0].id)
        assertEquals("pre-existing", list[0].label)
        assertFalse(
            "Legacy migration must NOT add a profile when list is non-empty",
            list.any { it.tokenStoreKey == Profile.LEGACY_TOKEN_STORE_KEY },
        )
    }

    @Test
    fun profile_buildTokenStoreKey_usesFirst8Chars() {
        val id = "abcdef01-2345-6789-abcd-ef0123456789"
        assertEquals("hermes_auth_abcdef01", Profile.buildTokenStoreKey(id))
    }

    @Test
    fun profile_extractDefaultLabel_fallsBackToRawOnInvalid() {
        assertEquals("192.168.1.10", Profile.extractDefaultLabel("http://192.168.1.10:8642"))
        // No scheme → URI#getHost returns null → fallback.
        val raw = "not a url"
        assertEquals(raw, Profile.extractDefaultLabel(raw))
    }
}
