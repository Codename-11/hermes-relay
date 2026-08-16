package com.hermesandroid.relay.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.hermesandroid.relay.data.GatewayProfileConfigureResult
import com.hermesandroid.relay.data.GatewayProfileDescription
import com.hermesandroid.relay.data.GatewayProfileEditorClient
import com.hermesandroid.relay.data.GatewayProfileEditorUnsupportedException
import com.hermesandroid.relay.data.GatewayProfilePatch
import com.hermesandroid.relay.data.GatewayProfileSection
import com.hermesandroid.relay.data.LegacyProfileInspectorClient
import com.hermesandroid.relay.data.ProfileConfigResponse
import com.hermesandroid.relay.data.ProfileMemoryResponse
import com.hermesandroid.relay.data.ProfileMemoryUpdateResponse
import com.hermesandroid.relay.data.ProfileSkillsResponse
import com.hermesandroid.relay.data.ProfileSoulResponse
import com.hermesandroid.relay.data.ProfileSoulUpdateResponse
import com.hermesandroid.relay.data.RelaySkillToggleResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileInspectorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `partial gateway save refreshes authoritative sections and retains failed draft`() = runTest(dispatcher) {
        val initial = description(description = "Old", model = "old")
        val refreshed = description(description = "Updated", model = "old")
        val gateway = FakeGateway(
            descriptions = mutableListOf(initial, refreshed),
            configureResult = GatewayProfileConfigureResult(
                requested = setOf(GatewayProfileSection.Description, GatewayProfileSection.Model),
                applied = setOf(GatewayProfileSection.Description),
            ),
        )
        val viewModel = viewModel(gateway, FakeLegacy())

        viewModel.loadAll()
        advanceUntilIdle()
        assertTrue(viewModel.gatewayWritable.value)
        viewModel.beginConfigEdit()
        viewModel.updateConfigDescriptionDraft("Updated")
        viewModel.updateConfigModelDraft("new")
        viewModel.saveConfigEdit()
        advanceUntilIdle()

        assertEquals("operator", gateway.requestedNames.distinct().single())
        assertEquals("Updated", viewModel.configDescriptionDraft.value)
        assertEquals("new", viewModel.configModelDraft.value)
        assertTrue(viewModel.configEditing.value)
        assertEquals("old", viewModel.gatewayDescription.value?.model)
    }

    @Test
    fun `configure capability failure keeps gateway reads and disables writes`() = runTest(dispatcher) {
        val gateway = object : GatewayProfileEditorClient {
            override suspend fun describeProfile(profileName: String) =
                Result.success(description(description = "Read only", model = "stable"))

            override suspend fun configureProfile(profileName: String, patch: GatewayProfilePatch) =
                Result.failure<GatewayProfileConfigureResult>(GatewayProfileEditorUnsupportedException())
        }
        val viewModel = viewModel(gateway, FakeLegacy())

        viewModel.loadAll()
        advanceUntilIdle()
        viewModel.beginConfigEdit()
        viewModel.updateConfigDescriptionDraft("Retained draft")
        viewModel.saveConfigEdit()
        advanceUntilIdle()

        assertEquals(ProfileInspectorSource.Gateway, viewModel.source.value)
        assertEquals("Read only", viewModel.gatewayDescription.value?.description)
        assertEquals("Retained draft", viewModel.configDescriptionDraft.value)
        assertTrue(viewModel.configEditing.value)
        assertEquals(false, viewModel.gatewayWritable.value)
        assertEquals(false, viewModel.skillToggleSupported.value)
    }

    @Test
    fun `older gateway falls back to legacy inspector without changing profile namespace`() = runTest(dispatcher) {
        val legacy = FakeLegacy()
        val gateway = object : GatewayProfileEditorClient {
            override suspend fun describeProfile(profileName: String) =
                Result.failure<GatewayProfileDescription>(GatewayProfileEditorUnsupportedException())
            override suspend fun configureProfile(profileName: String, patch: GatewayProfilePatch) =
                error("configure must remain capability gated")
        }
        val viewModel = viewModel(gateway, legacy)

        viewModel.loadAll()
        advanceUntilIdle()

        assertEquals(ProfileInspectorSource.Relay, viewModel.source.value)
        assertEquals(listOf("operator"), legacy.configRequests)
        assertTrue(viewModel.configState.value is LoadState.Loaded)
    }

    private fun viewModel(
        gateway: GatewayProfileEditorClient?,
        legacy: LegacyProfileInspectorClient,
    ) = ProfileInspectorViewModel(
        legacyClient = legacy,
        gatewayClient = gateway,
        savedStateHandle = SavedStateHandle(mapOf(ProfileInspectorViewModel.ARG_PROFILE_NAME to "operator")),
    )

    private fun description(description: String, model: String) = GatewayProfileDescription(
        name = "operator",
        description = description,
        soul = "# Soul",
        provider = "openai",
        model = model,
        skills = emptyList(),
        toolsets = emptyList(),
        toolsetsPinned = false,
    )

    private class FakeGateway(
        private val descriptions: MutableList<GatewayProfileDescription>,
        private val configureResult: GatewayProfileConfigureResult,
    ) : GatewayProfileEditorClient {
        val requestedNames = mutableListOf<String>()
        override suspend fun describeProfile(profileName: String): Result<GatewayProfileDescription> {
            requestedNames += profileName
            return Result.success(descriptions.removeAt(0))
        }
        override suspend fun configureProfile(
            profileName: String,
            patch: GatewayProfilePatch,
        ): Result<GatewayProfileConfigureResult> {
            requestedNames += profileName
            return Result.success(configureResult)
        }
    }

    private class FakeLegacy : LegacyProfileInspectorClient {
        val configRequests = mutableListOf<String>()
        override suspend fun fetchConfig(profileName: String): Result<ProfileConfigResponse> {
            configRequests += profileName
            return Result.success(ProfileConfigResponse(profileName, "config.yaml", buildJsonObject {}))
        }
        override suspend fun fetchSkills(profileName: String) =
            Result.success(ProfileSkillsResponse(profileName, emptyList(), 0))
        override suspend fun fetchSoul(profileName: String) =
            Result.success(ProfileSoulResponse(profileName, "SOUL.md", "", false, 0))
        override suspend fun fetchMemory(profileName: String) =
            Result.success(ProfileMemoryResponse(profileName, "memories", emptyList(), 0))
        override suspend fun updateSoul(profileName: String, content: String) =
            Result.success(ProfileSoulUpdateResponse(true, profileName, "SOUL.md", content.length.toLong()))
        override suspend fun updateMemoryEntry(profileName: String, filename: String, content: String) =
            Result.success(ProfileMemoryUpdateResponse(true, profileName, filename, filename, content.length.toLong()))
        override suspend fun updateSkillToggle(skillName: String, enabled: Boolean) =
            Result.success<RelaySkillToggleResult>(RelaySkillToggleResult.Ok)
        override suspend fun probeSkillToggleSupported() = false
    }
}
