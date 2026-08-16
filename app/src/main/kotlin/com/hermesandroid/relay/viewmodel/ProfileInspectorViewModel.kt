package com.hermesandroid.relay.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.data.GatewayProfileConfigureResult
import com.hermesandroid.relay.data.GatewayProfileDescription
import com.hermesandroid.relay.data.GatewayProfileEditorClient
import com.hermesandroid.relay.data.GatewayProfileEditorUnsupportedException
import com.hermesandroid.relay.data.GatewayProfilePatch
import com.hermesandroid.relay.data.GatewayProfileSection
import com.hermesandroid.relay.data.LegacyProfileInspectorClient
import com.hermesandroid.relay.data.ProfileConfigResponse
import com.hermesandroid.relay.data.ProfileMemoryResponse
import com.hermesandroid.relay.data.ProfileSkillEntry
import com.hermesandroid.relay.data.ProfileSkillsResponse
import com.hermesandroid.relay.data.ProfileSoulResponse
import com.hermesandroid.relay.data.RelaySkillToggleResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class InspectorSection { Config, Soul, Memory, Skills }

sealed class LoadState<out T> {
    data object Idle : LoadState<Nothing>()
    data object Loading : LoadState<Nothing>()
    data class Loaded<T>(val value: T) : LoadState<T>()
    data class Error(val message: String) : LoadState<Nothing>()
}

enum class ProfileInspectorSource { Unknown, Gateway, Relay }

/**
 * Owns one immutable profile-name namespace. Gateway-native describe/configure
 * is preferred when the active connection exposes it; Relay reads remain the
 * compatibility fallback and the sole owner of memory-file editing.
 */
class ProfileInspectorViewModel(
    private val legacyClient: LegacyProfileInspectorClient,
    private val gatewayClient: GatewayProfileEditorClient?,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val profileName: String = savedStateHandle.get<String>(ARG_PROFILE_NAME).orEmpty()

    private val _source = MutableStateFlow(ProfileInspectorSource.Unknown)
    val source: StateFlow<ProfileInspectorSource> = _source.asStateFlow()

    private val _gatewayDescription = MutableStateFlow<GatewayProfileDescription?>(null)
    val gatewayDescription: StateFlow<GatewayProfileDescription?> = _gatewayDescription.asStateFlow()

    private val _configState = MutableStateFlow<LoadState<ProfileConfigResponse>>(LoadState.Idle)
    val configState: StateFlow<LoadState<ProfileConfigResponse>> = _configState.asStateFlow()
    private val _soulState = MutableStateFlow<LoadState<ProfileSoulResponse>>(LoadState.Idle)
    val soulState: StateFlow<LoadState<ProfileSoulResponse>> = _soulState.asStateFlow()
    private val _memoryState = MutableStateFlow<LoadState<ProfileMemoryResponse>>(LoadState.Idle)
    val memoryState: StateFlow<LoadState<ProfileMemoryResponse>> = _memoryState.asStateFlow()
    private val _skillsState = MutableStateFlow<LoadState<ProfileSkillsResponse>>(LoadState.Idle)
    val skillsState: StateFlow<LoadState<ProfileSkillsResponse>> = _skillsState.asStateFlow()

    sealed class EditEvent {
        data class Saved(val message: String) : EditEvent()
        data class Error(val message: String) : EditEvent()
    }

    private val _editEvents = MutableSharedFlow<EditEvent>(extraBufferCapacity = 8)
    val editEvents: SharedFlow<EditEvent> = _editEvents.asSharedFlow()

    private val _soulRawView = MutableStateFlow(false)
    val soulRawView: StateFlow<Boolean> = _soulRawView.asStateFlow()
    fun toggleSoulRawView() { _soulRawView.value = !_soulRawView.value }

    private val _configEditing = MutableStateFlow(false)
    val configEditing: StateFlow<Boolean> = _configEditing.asStateFlow()
    private val _configDescriptionDraft = MutableStateFlow("")
    val configDescriptionDraft: StateFlow<String> = _configDescriptionDraft.asStateFlow()
    private val _configProviderDraft = MutableStateFlow("")
    val configProviderDraft: StateFlow<String> = _configProviderDraft.asStateFlow()
    private val _configModelDraft = MutableStateFlow("")
    val configModelDraft: StateFlow<String> = _configModelDraft.asStateFlow()
    private val _configSaving = MutableStateFlow(false)
    val configSaving: StateFlow<Boolean> = _configSaving.asStateFlow()

    fun beginConfigEdit() {
        val description = _gatewayDescription.value ?: return
        _configDescriptionDraft.value = description.description
        _configProviderDraft.value = description.provider
        _configModelDraft.value = description.model
        _configEditing.value = true
    }

    fun updateConfigDescriptionDraft(value: String) { _configDescriptionDraft.value = value }
    fun updateConfigProviderDraft(value: String) { _configProviderDraft.value = value }
    fun updateConfigModelDraft(value: String) { _configModelDraft.value = value }
    fun cancelConfigEdit() { _configEditing.value = false }

    fun saveConfigEdit() {
        val baseline = _gatewayDescription.value ?: return
        if (_configSaving.value) return
        val descriptionChanged = _configDescriptionDraft.value != baseline.description
        val modelChanged = _configProviderDraft.value != baseline.provider ||
            _configModelDraft.value != baseline.model
        if (modelChanged && (_configProviderDraft.value.isBlank() || _configModelDraft.value.isBlank())) {
            _editEvents.tryEmit(EditEvent.Error("Provider and model are both required"))
            return
        }
        val patch = GatewayProfilePatch(
            description = _configDescriptionDraft.value.takeIf { descriptionChanged },
            provider = _configProviderDraft.value.takeIf { modelChanged },
            model = _configModelDraft.value.takeIf { modelChanged },
        )
        if (patch.requestedSections.isEmpty()) {
            _configEditing.value = false
            return
        }
        _configSaving.value = true
        saveGatewayPatch(patch) { result, refreshed ->
            if (GatewayProfileSection.Description in result.applied) {
                _configDescriptionDraft.value = refreshed.description
            }
            if (GatewayProfileSection.Model in result.applied) {
                _configProviderDraft.value = refreshed.provider
                _configModelDraft.value = refreshed.model
            }
            _configEditing.value = result.failed.isNotEmpty()
            _configSaving.value = false
        }
    }

    private val _soulEditing = MutableStateFlow(false)
    val soulEditing: StateFlow<Boolean> = _soulEditing.asStateFlow()
    private val _soulDraft = MutableStateFlow("")
    val soulDraft: StateFlow<String> = _soulDraft.asStateFlow()
    private val _soulSaving = MutableStateFlow(false)
    val soulSaving: StateFlow<Boolean> = _soulSaving.asStateFlow()

    fun beginSoulEdit() {
        _soulDraft.value = (soulState.value as? LoadState.Loaded)?.value?.content.orEmpty()
        _soulEditing.value = true
    }
    fun updateSoulDraft(content: String) { _soulDraft.value = content }
    fun cancelSoulEdit() { _soulEditing.value = false }

    fun saveSoulEdit() {
        if (profileName.isBlank() || _soulSaving.value) return
        _soulSaving.value = true
        if (_source.value == ProfileInspectorSource.Gateway) {
            saveGatewayPatch(GatewayProfilePatch(soul = _soulDraft.value)) { result, refreshed ->
                if (GatewayProfileSection.Soul in result.applied) {
                    _soulDraft.value = refreshed.soul
                    _soulEditing.value = false
                }
                _soulSaving.value = false
            }
            return
        }
        viewModelScope.launch {
            val result = legacyClient.updateSoul(profileName, _soulDraft.value)
            _soulSaving.value = false
            result.fold(
                onSuccess = {
                    _soulEditing.value = false
                    _editEvents.tryEmit(EditEvent.Saved("SOUL saved"))
                    refreshLegacySection(InspectorSection.Soul)
                },
                onFailure = { _editEvents.tryEmit(EditEvent.Error(it.message ?: "Save failed")) },
            )
        }
    }

    private val _memoryEditingFilename = MutableStateFlow<String?>(null)
    val memoryEditingFilename: StateFlow<String?> = _memoryEditingFilename.asStateFlow()
    private val _memoryDraft = MutableStateFlow("")
    val memoryDraft: StateFlow<String> = _memoryDraft.asStateFlow()
    private val _memorySaving = MutableStateFlow(false)
    val memorySaving: StateFlow<Boolean> = _memorySaving.asStateFlow()

    fun beginMemoryEdit(filename: String, initialContent: String) {
        _memoryEditingFilename.value = filename
        _memoryDraft.value = initialContent
    }
    fun updateMemoryDraft(content: String) { _memoryDraft.value = content }
    fun cancelMemoryEdit() { _memoryEditingFilename.value = null }

    fun saveMemoryEdit() {
        val filename = _memoryEditingFilename.value ?: return
        if (profileName.isBlank() || _memorySaving.value) return
        validateMemoryFilename(filename)?.let {
            _editEvents.tryEmit(EditEvent.Error(it))
            return
        }
        _memorySaving.value = true
        viewModelScope.launch {
            val result = legacyClient.updateMemoryEntry(profileName, filename, _memoryDraft.value)
            _memorySaving.value = false
            result.fold(
                onSuccess = {
                    _memoryEditingFilename.value = null
                    _editEvents.tryEmit(EditEvent.Saved("Memory entry saved"))
                    refreshLegacySection(InspectorSection.Memory)
                },
                onFailure = { _editEvents.tryEmit(EditEvent.Error(it.message ?: "Save failed")) },
            )
        }
    }

    private val _skillToggleSupported = MutableStateFlow<Boolean?>(null)
    val skillToggleSupported: StateFlow<Boolean?> = _skillToggleSupported.asStateFlow()
    private val _skillDrafts = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val skillDrafts: StateFlow<Map<String, Boolean>> = _skillDrafts.asStateFlow()
    private val _toolsetDrafts = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val toolsetDrafts: StateFlow<Map<String, Boolean>> = _toolsetDrafts.asStateFlow()
    private val _skillsSaving = MutableStateFlow(false)
    val skillsSaving: StateFlow<Boolean> = _skillsSaving.asStateFlow()

    fun probeSkillToggleSupport() {
        if (_source.value == ProfileInspectorSource.Gateway) {
            _skillToggleSupported.value = true
            return
        }
        viewModelScope.launch {
            val supported = legacyClient.probeSkillToggleSupported()
            if (_source.value != ProfileInspectorSource.Gateway) {
                _skillToggleSupported.value = supported
            }
        }
    }

    fun toggleSkill(skillName: String, enabled: Boolean) {
        if (_source.value == ProfileInspectorSource.Gateway) {
            val baseline = _gatewayDescription.value?.skills?.firstOrNull { it.name == skillName }
                ?.enabled ?: return
            _skillDrafts.value = _skillDrafts.value.toMutableMap().apply {
                if (enabled == baseline) remove(skillName) else put(skillName, enabled)
            }
            return
        }
        viewModelScope.launch {
            legacyClient.updateSkillToggle(skillName, enabled).fold(
                onSuccess = {
                    when (it) {
                        RelaySkillToggleResult.Ok -> {
                            _editEvents.tryEmit(EditEvent.Saved(if (enabled) "Enabled $skillName" else "Disabled $skillName"))
                            refreshLegacySection(InspectorSection.Skills)
                        }
                        RelaySkillToggleResult.NotImplemented -> {
                            _skillToggleSupported.value = false
                            _editEvents.tryEmit(EditEvent.Error("Skill toggle not yet supported on this server"))
                        }
                    }
                },
                onFailure = { _editEvents.tryEmit(EditEvent.Error(it.message ?: "Skill toggle failed")) },
            )
        }
    }

    fun toggleToolset(toolsetName: String, enabled: Boolean) {
        val baseline = _gatewayDescription.value?.toolsets?.firstOrNull { it.name == toolsetName }
            ?.enabled ?: return
        _toolsetDrafts.value = _toolsetDrafts.value.toMutableMap().apply {
            if (enabled == baseline) remove(toolsetName) else put(toolsetName, enabled)
        }
    }

    fun saveSkillEdits() {
        val description = _gatewayDescription.value ?: return
        if (_source.value != ProfileInspectorSource.Gateway || _skillsSaving.value) return
        val skillDrafts = _skillDrafts.value
        val toolsetDrafts = _toolsetDrafts.value
        val disabledSkills = if (skillDrafts.isNotEmpty()) {
            description.skills.filter { !(skillDrafts[it.name] ?: it.enabled) }.map { it.name }
        } else null
        val enabledToolsets = if (toolsetDrafts.isNotEmpty()) {
            description.toolsets.filter { toolsetDrafts[it.name] ?: it.enabled }.map { it.name }
                .takeUnless { it.size == description.toolsets.size } ?: emptyList()
        } else null
        val patch = GatewayProfilePatch(
            disabledSkills = disabledSkills,
            enabledToolsets = enabledToolsets,
        )
        if (patch.requestedSections.isEmpty()) return
        _skillsSaving.value = true
        saveGatewayPatch(patch) { result, _ ->
            if (GatewayProfileSection.Skills in result.applied) _skillDrafts.value = emptyMap()
            if (GatewayProfileSection.Toolsets in result.applied) _toolsetDrafts.value = emptyMap()
            _skillsSaving.value = false
        }
    }

    fun validateMemoryFilename(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Filename required"
        if (!trimmed.endsWith(".md")) return "Filename must end in .md"
        if (trimmed.startsWith(".")) return "Filename cannot start with '.'"
        if (trimmed.contains("/") || trimmed.contains("\\")) return "Filename cannot contain slashes"
        if (trimmed.contains("..")) return "Filename cannot contain '..'"
        return null
    }

    fun loadAll() {
        if (profileName.isBlank()) {
            val error = LoadState.Error("No profile name supplied")
            _configState.value = error
            _soulState.value = error
            _memoryState.value = error
            _skillsState.value = error
            return
        }
        refreshEditorSections()
        refreshLegacySection(InspectorSection.Memory)
    }

    fun refreshSection(section: InspectorSection) {
        if (profileName.isBlank()) return
        if (section == InspectorSection.Memory) refreshLegacySection(section) else refreshEditorSections()
    }

    private fun refreshEditorSections() {
        _configState.value = LoadState.Loading
        _soulState.value = LoadState.Loading
        _skillsState.value = LoadState.Loading
        viewModelScope.launch {
            val gatewayResult = gatewayClient?.describeProfile(profileName)
            val gatewayDescription = gatewayResult?.getOrNull()
            if (gatewayDescription != null) {
                _source.value = ProfileInspectorSource.Gateway
                applyGatewayDescription(gatewayDescription)
                return@launch
            }
            loadLegacyEditorSections(gatewayResult?.exceptionOrNull())
        }
    }

    private suspend fun loadLegacyEditorSections(gatewayError: Throwable?) = coroutineScope {
        val config = async { legacyClient.fetchConfig(profileName) }
        val soul = async { legacyClient.fetchSoul(profileName) }
        val skills = async { legacyClient.fetchSkills(profileName) }
        val configResult = config.await()
        val soulResult = soul.await()
        val skillsResult = skills.await()
        if (configResult.isSuccess || soulResult.isSuccess || skillsResult.isSuccess) {
            _source.value = ProfileInspectorSource.Relay
            _gatewayDescription.value = null
        }
        val fallbackMessage = gatewayError
            ?.takeUnless { it is GatewayProfileEditorUnsupportedException }
            ?.message
        _configState.value = configResult.toLoadState(fallbackMessage)
        _soulState.value = soulResult.toLoadState(fallbackMessage)
        _skillsState.value = skillsResult.toLoadState(fallbackMessage)
    }

    private fun refreshLegacySection(section: InspectorSection) {
        when (section) {
            InspectorSection.Config, InspectorSection.Soul, InspectorSection.Skills -> refreshEditorSections()
            InspectorSection.Memory -> {
                _memoryState.value = LoadState.Loading
                viewModelScope.launch {
                    _memoryState.value = legacyClient.fetchMemory(profileName).toLoadState()
                }
            }
        }
    }

    private fun applyGatewayDescription(description: GatewayProfileDescription) {
        if (description.name != profileName) return
        _gatewayDescription.value = description
        _skillToggleSupported.value = true
        _configState.value = LoadState.Loaded(description.toConfigResponse())
        _soulState.value = LoadState.Loaded(description.toSoulResponse())
        _skillsState.value = LoadState.Loaded(description.toSkillsResponse())
    }

    private fun saveGatewayPatch(
        patch: GatewayProfilePatch,
        afterAuthoritativeRefresh: (GatewayProfileConfigureResult, GatewayProfileDescription) -> Unit,
    ) {
        val client = gatewayClient
        if (client == null) {
            _editEvents.tryEmit(EditEvent.Error("Gateway profile editor unavailable"))
            return
        }
        viewModelScope.launch {
            val configured = client.configureProfile(profileName, patch)
            if (configured.isFailure) {
                clearSavingFlags()
                _editEvents.tryEmit(EditEvent.Error(configured.exceptionOrNull()?.message ?: "Save failed"))
                return@launch
            }
            val result = configured.getOrThrow()
            val refreshed = client.describeProfile(profileName)
            if (refreshed.isFailure) {
                clearSavingFlags()
                _editEvents.tryEmit(EditEvent.Error(saveSummary(result) + "; authoritative refresh failed"))
                return@launch
            }
            val description = refreshed.getOrThrow()
            applyGatewayDescription(description)
            afterAuthoritativeRefresh(result, description)
            val summary = saveSummary(result)
            if (result.applied.isEmpty()) _editEvents.tryEmit(EditEvent.Error(summary))
            else _editEvents.tryEmit(EditEvent.Saved(summary))
        }
    }

    private fun clearSavingFlags() {
        _configSaving.value = false
        _soulSaving.value = false
        _skillsSaving.value = false
    }

    private fun saveSummary(result: GatewayProfileConfigureResult): String {
        val applied = result.applied.joinToString { it.wireName }.ifBlank { "none" }
        val failed = result.failed.joinToString { it.wireName }.ifBlank { "none" }
        return "Applied: $applied; failed: $failed"
    }

    private fun GatewayProfileDescription.toConfigResponse(): ProfileConfigResponse =
        ProfileConfigResponse(
            profile = name,
            path = "profiles.describe",
            readonly = false,
            config = buildJsonObject {
                put("description", description)
                put("model", buildJsonObject {
                    put("provider", provider)
                    put("default", model)
                })
                put("tools", buildJsonObject {
                    put("toolsets_pinned", toolsetsPinned)
                    put("enabled_toolsets", JsonArray(toolsets.filter { it.enabled }.map { kotlinx.serialization.json.JsonPrimitive(it.name) }))
                })
            },
        )

    private fun GatewayProfileDescription.toSoulResponse(): ProfileSoulResponse =
        ProfileSoulResponse(
            profile = name,
            path = "profiles.describe",
            content = soul,
            exists = soul.isNotEmpty(),
            sizeBytes = soul.toByteArray(Charsets.UTF_8).size.toLong(),
        )

    private fun GatewayProfileDescription.toSkillsResponse(): ProfileSkillsResponse =
        ProfileSkillsResponse(
            profile = name,
            skills = skills.map {
                ProfileSkillEntry(
                    name = it.name,
                    category = "Gateway",
                    description = "",
                    path = "",
                    enabled = it.enabled,
                )
            },
            total = skills.size,
        )

    private fun <T> Result<T>.toLoadState(fallbackMessage: String? = null): LoadState<T> = fold(
        onSuccess = { LoadState.Loaded(it) },
        onFailure = { LoadState.Error(it.message ?: fallbackMessage ?: "Unknown error") },
    )

    companion object {
        const val ARG_PROFILE_NAME: String = "profileName"
    }
}
