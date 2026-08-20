package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BridgePreferencesMigrationTest {
    private lateinit var context: Context
    private val legacyMaster = booleanPreferencesKey("bridge_master_enabled")

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.relayDataStore.edit { it.clear() }
    }

    @After
    fun tearDown() = runTest { context.relayDataStore.edit { it.clear() } }

    @Test
    fun legacyEnabledMasterMigratesFailClosed() = runTest {
        context.relayDataStore.edit { it[legacyMaster] = true }

        assertFalse(BridgePreferencesRepository(context).settings.first().masterEnabled)
    }

    @Test
    fun newMasterWritesKeepLegacyDowngradeGateOff() = runTest {
        val repo = BridgePreferencesRepository(context)
        repo.setMasterEnabled(true)

        assertTrue(repo.settings.first().masterEnabled)
        assertFalse(context.relayDataStore.data.first()[legacyMaster] ?: true)
    }
}
