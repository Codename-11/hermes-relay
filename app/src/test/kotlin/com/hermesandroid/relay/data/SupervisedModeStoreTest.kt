package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisedModeStoreTest {

    @Test
    fun freshConnectionUsesRestrictiveDefaults() = runTest {
        val store = SupervisedModeStore.forTesting(InMemorySupervisedPreferencesDataStore())

        val policy = store.policyFlow("connection-a").first()

        assertFalse(policy.enabled)
        assertFalse(policy.isConfigured)
        assertFalse(policy.isActive)
        assertFalse(policy.capabilities.attachments)
        assertFalse(policy.capabilities.voice)
        assertFalse(policy.visibility.resolved().showModelName)
        assertFalse(policy.visibility.resolved().showTechnicalRoute)
        assertTrue(policy.parentAccess.requireDeviceAuthentication)
        assertEquals(5, policy.parentAccess.timeoutMinutes)
    }

    @Test
    fun policyRoundTripsWithCapabilitiesLimitsAndVisibility() = runTest {
        val dataStore = InMemorySupervisedPreferencesDataStore()
        val store = SupervisedModeStore.forTesting(dataStore)
        val saved = SupervisedModePolicy(
            enabled = true,
            pinnedProfileName = " willow ",
            capabilities = SupervisedCapabilities(
                attachments = true,
                voice = true,
                attachmentMaxCount = 6,
                attachmentMaxFileMb = 20,
                attachmentCategories = setOf(
                    SupervisedAttachmentCategory.Images,
                    SupervisedAttachmentCategory.Documents,
                ),
            ),
            visibility = SupervisedVisibility(
                preset = SupervisedVisibilityPreset.Custom,
                showAgentIdentity = true,
                showModelName = true,
                showToolNames = true,
            ),
        )

        store.setPolicy("connection-a", saved)
        val restored = SupervisedModeStore.forTesting(dataStore).policyFlow("connection-a").first()

        assertTrue(restored.isActive)
        assertEquals("willow", restored.pinnedProfileName)
        assertEquals(6, restored.capabilities.attachmentMaxCount)
        assertEquals(20, restored.capabilities.attachmentMaxFileMb)
        assertEquals(saved.capabilities.attachmentCategories, restored.capabilities.attachmentCategories)
        assertEquals(SupervisedVisibilityPreset.Custom, restored.visibility.preset)
        assertTrue(restored.visibility.showModelName)
        assertTrue(restored.visibility.showToolNames)
    }

    @Test
    fun connectionsAreIsolatedAndClearRemovesOnlyTarget() = runTest {
        val store = SupervisedModeStore.forTesting(InMemorySupervisedPreferencesDataStore())
        store.setPolicy("connection-a", SupervisedModePolicy(true, "willow"))
        store.setPolicy("connection-b", SupervisedModePolicy(true, "juniper"))

        store.clear("connection-a")

        assertFalse(store.policyFlow("connection-a").first().enabled)
        assertEquals("juniper", store.policyFlow("connection-b").first().pinnedProfileName)
    }

    @Test
    fun updateAndSetEnabledPreserveOtherPolicyFields() = runTest {
        val store = SupervisedModeStore.forTesting(InMemorySupervisedPreferencesDataStore())
        store.setPolicy(
            "connection-a",
            SupervisedModePolicy(
                pinnedProfileName = "willow",
                capabilities = SupervisedCapabilities(voice = true),
            ),
        )

        store.setEnabled("connection-a", true)
        store.updatePolicy("connection-a") {
            it.copy(visibility = it.visibility.copy(preset = SupervisedVisibilityPreset.Transparent))
        }

        val policy = store.policyFlow("connection-a").first()
        assertTrue(policy.isActive)
        assertTrue(policy.capabilities.voice)
        assertEquals(SupervisedVisibilityPreset.Transparent, policy.visibility.preset)
    }

    @Test
    fun invalidLimitsAreNormalizedAndEmptyCategoriesFallBackToImages() = runTest {
        val store = SupervisedModeStore.forTesting(InMemorySupervisedPreferencesDataStore())
        store.setPolicy(
            "connection-a",
            SupervisedModePolicy(
                pinnedProfileName = "willow",
                capabilities = SupervisedCapabilities(
                    attachmentMaxCount = Int.MAX_VALUE,
                    attachmentMaxFileMb = -1,
                    attachmentCategories = emptySet(),
                ),
                parentAccess = SupervisedParentAccess(
                    requireDeviceAuthentication = false,
                    timeoutMinutes = 0,
                ),
            ),
        )

        val policy = store.policyFlow("connection-a").first()
        assertEquals(SupervisedCapabilities.MAX_ATTACHMENT_COUNT, policy.capabilities.attachmentMaxCount)
        assertEquals(1, policy.capabilities.attachmentMaxFileMb)
        assertEquals(setOf(SupervisedAttachmentCategory.Images), policy.capabilities.attachmentCategories)
        assertTrue(policy.parentAccess.requireDeviceAuthentication)
        assertEquals(SupervisedParentAccess.MIN_TIMEOUT_MINUTES, policy.parentAccess.timeoutMinutes)
    }

    @Test
    fun simplePresetResolvesToSafeValuesEvenIfStoredFlagsDiffer() {
        val visibility = SupervisedVisibility(
            preset = SupervisedVisibilityPreset.Simple,
            showModelName = true,
            showTechnicalRoute = true,
            showReasoning = true,
        ).resolved()

        assertFalse(visibility.showModelName)
        assertFalse(visibility.showTechnicalRoute)
        assertFalse(visibility.showReasoning)
        assertTrue(visibility.showAgentIdentity)
        assertTrue(visibility.showConnectionStatus)
    }

    @Test
    fun malformedPersistedPolicyFailsClosed() = runTest {
        val policyKey = androidx.datastore.preferences.core.stringPreferencesKey(
            "supervised_mode_policies_v1",
        )
        val dataStore = InMemorySupervisedPreferencesDataStore(
            mutablePreferencesOf(policyKey to "{not-valid-json"),
        )

        val policy = SupervisedModeStore.forTesting(dataStore)
            .policyFlow("connection-a")
            .first()

        assertTrue(policy.enabled)
        assertFalse(policy.isConfigured)
        assertFalse(policy.isActive)
    }

    @Test
    fun clearAllDoesNotClearUnrelatedPreferences() = runTest {
        val unrelatedKey = androidx.datastore.preferences.core.stringPreferencesKey("unrelated")
        val dataStore = InMemorySupervisedPreferencesDataStore(
            mutablePreferencesOf(unrelatedKey to "kept"),
        )
        val store = SupervisedModeStore.forTesting(dataStore)
        store.setPolicy("connection-a", SupervisedModePolicy(true, "willow"))

        store.clearAll()

        assertFalse(store.policyFlow("connection-a").first().enabled)
        assertEquals("kept", dataStore.data.first()[unrelatedKey])
    }
}

private class InMemorySupervisedPreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
