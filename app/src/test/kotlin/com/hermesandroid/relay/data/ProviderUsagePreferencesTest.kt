package com.hermesandroid.relay.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderUsagePreferencesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private lateinit var repository: ProviderUsagePreferencesRepository

    @Before
    fun setUp() {
        file = tempFolder.newFile("provider_usage.preferences_pb").also { it.delete() }
        scope = CoroutineScope(Dispatchers.IO + Job())
        repository = ProviderUsagePreferencesRepository(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaultsToSummaryWithSupportedProvidersVisible() = runTest {
        val preferences = repository.preferences.first()
        assertEquals(ProviderUsageLandingMode.Summary, preferences.landingMode)
        assertEquals(
            setOf("openai-codex", "nous", "opencode-go"),
            preferences.visibleProviders,
        )
    }

    @Test
    fun persistsDisplayMode() = runTest {
        repository.setLandingMode(ProviderUsageLandingMode.Expanded)

        val preferences = repository.preferences.first()
        assertEquals(ProviderUsageLandingMode.Expanded, preferences.landingMode)
    }

    @Test
    fun persistsIndependentProviderVisibility() = runTest {
        repository.setProviderVisible("nous", false)

        val preferences = repository.preferences.first()
        assertFalse("nous" in preferences.visibleProviders)
        assertTrue("openai-codex" in preferences.visibleProviders)
        assertTrue("opencode-go" in preferences.visibleProviders)
    }
}
