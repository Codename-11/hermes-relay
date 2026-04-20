package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [ProfileSelectionStore].
 *
 * Follows the pattern in `BargeInPreferencesTest` — bypasses
 * [android.content.Context] by feeding the store a filesystem-backed
 * [DataStore] via [PreferenceDataStoreFactory.create]. That only works
 * because [ProfileSelectionStore]'s primary constructor takes a raw
 * DataStore (the Context secondary resolves to the same shape in prod).
 */
class ProfileSelectionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: ProfileSelectionStore

    @Before
    fun setUp() {
        scope = TestScope(StandardTestDispatcher() + Job())
        val file: File = tempFolder.newFile("profile_selections_test.preferences_pb")
        // PreferenceDataStoreFactory requires the backing file NOT exist yet;
        // TemporaryFolder.newFile() creates it, so we delete first.
        if (file.exists()) file.delete()
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        store = ProfileSelectionStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun unset_connection_emitsNull() = runTest {
        // Fresh store — every connection id reads as null until set.
        assertNull(store.selectedProfileFlow("conn-1").first())
        assertNull(store.selectedProfileFlow("conn-unknown").first())
    }

    @Test
    fun set_then_get_roundTrips() = runTest {
        store.setSelectedProfile("conn-1", "mizu")
        assertEquals("mizu", store.selectedProfileFlow("conn-1").first())
    }

    @Test
    fun set_null_clearsTheKey() = runTest {
        store.setSelectedProfile("conn-1", "mizu")
        assertEquals("mizu", store.selectedProfileFlow("conn-1").first())

        // Writing null removes the key — read path should emit null rather
        // than an empty string. Distinguishable states: fresh install vs.
        // explicit clear converge here, but both produce null downstream.
        store.setSelectedProfile("conn-1", null)
        assertNull(store.selectedProfileFlow("conn-1").first())
    }

    @Test
    fun clear_removesOnlyTheGivenConnection() = runTest {
        store.setSelectedProfile("conn-1", "mizu")
        store.setSelectedProfile("conn-2", "coder")

        store.clear("conn-1")

        assertNull(store.selectedProfileFlow("conn-1").first())
        assertEquals("coder", store.selectedProfileFlow("conn-2").first())
    }

    @Test
    fun perConnectionKeys_areIndependent() = runTest {
        // Writing to one connection must not touch another — the key
        // factory is the contract and regressions here would cascade.
        store.setSelectedProfile("conn-A", "alpha")
        store.setSelectedProfile("conn-B", "beta")
        store.setSelectedProfile("conn-C", "gamma")

        assertEquals("alpha", store.selectedProfileFlow("conn-A").first())
        assertEquals("beta", store.selectedProfileFlow("conn-B").first())
        assertEquals("gamma", store.selectedProfileFlow("conn-C").first())
    }

    @Test
    fun overwrite_replacesPriorValue() = runTest {
        store.setSelectedProfile("conn-1", "mizu")
        store.setSelectedProfile("conn-1", "coder")
        assertEquals("coder", store.selectedProfileFlow("conn-1").first())
    }
}
