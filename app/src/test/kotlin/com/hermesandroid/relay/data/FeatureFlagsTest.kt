package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeatureFlagsTest {
    private lateinit var context: Context

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.relayDataStore.edit { it.clear() }
    }

    @After
    fun tearDown() = runTest {
        context.relayDataStore.edit { it.clear() }
    }

    @Test
    fun missingPreferenceUsesBuildDefault() = runTest {
        assertEquals(FeatureFlags.isDevBuild, FeatureFlags.devOptionsUnlocked(context).first())
    }

    @Test
    fun explicitLockOverridesDebugBuildDefault() = runTest {
        FeatureFlags.lockDevOptions(context)

        assertFalse(FeatureFlags.devOptionsUnlocked(context).first())
    }

    @Test
    fun unlockPersistsAfterExplicitLock() = runTest {
        FeatureFlags.lockDevOptions(context)
        FeatureFlags.unlockDevOptions(context)

        assertTrue(FeatureFlags.devOptionsUnlocked(context).first())
    }
}
