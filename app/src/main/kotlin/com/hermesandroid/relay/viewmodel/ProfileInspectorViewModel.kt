package com.hermesandroid.relay.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.data.ProfileConfigResponse
import com.hermesandroid.relay.data.ProfileMemoryResponse
import com.hermesandroid.relay.data.ProfileSkillsResponse
import com.hermesandroid.relay.data.ProfileSoulResponse
import com.hermesandroid.relay.network.RelayProfileInspectorClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One of the four sections the inspector screen can display — used by
 * [ProfileInspectorViewModel.refreshSection] to target a single pane
 * without reloading the whole screen.
 */
enum class InspectorSection { Config, Soul, Memory, Skills }

/**
 * Generic load state for each of the four inspector sections. Kept
 * separate per section so a slow `/memory` fetch doesn't block the
 * already-arrived `/config` tab from rendering.
 *
 * `Idle` is the pre-first-fetch state (useful for "don't render anything
 * yet"); `Loading` is during an active fetch; `Loaded` carries the
 * successfully-parsed payload; `Error` carries a human-readable message
 * for inline display with a retry button.
 */
sealed class LoadState<out T> {
    data object Idle : LoadState<Nothing>()
    data object Loading : LoadState<Nothing>()
    data class Loaded<T>(val value: T) : LoadState<T>()
    data class Error(val message: String) : LoadState<Nothing>()
}

/**
 * ViewModel for the Profile Inspector screen. Owns four load states
 * (one per section) plus a one-shot `loadAll()` and per-section
 * `refreshSection()` for pull-to-refresh. Lazy: no fetch is kicked off
 * until the screen first calls [loadAll].
 *
 * The inspected profile name comes in via [SavedStateHandle] so it
 * survives process death — Android nav graph arg → SavedStateHandle is
 * the standard path. If the arg is missing (unexpected), [profileName]
 * falls back to an empty string and every fetch short-circuits to an
 * error state.
 */
class ProfileInspectorViewModel(
    private val client: RelayProfileInspectorClient,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * Key the screen pass on. Read from [SavedStateHandle] so a
     * process-death restore brings the same profile back — Android
     * nav args are automatically mirrored into savedStateHandle when
     * the screen is registered via `composable(route, arguments=...)`.
     */
    val profileName: String =
        savedStateHandle.get<String>(ARG_PROFILE_NAME).orEmpty()

    private val _configState =
        MutableStateFlow<LoadState<ProfileConfigResponse>>(LoadState.Idle)
    val configState: StateFlow<LoadState<ProfileConfigResponse>> =
        _configState.asStateFlow()

    private val _soulState =
        MutableStateFlow<LoadState<ProfileSoulResponse>>(LoadState.Idle)
    val soulState: StateFlow<LoadState<ProfileSoulResponse>> =
        _soulState.asStateFlow()

    private val _memoryState =
        MutableStateFlow<LoadState<ProfileMemoryResponse>>(LoadState.Idle)
    val memoryState: StateFlow<LoadState<ProfileMemoryResponse>> =
        _memoryState.asStateFlow()

    private val _skillsState =
        MutableStateFlow<LoadState<ProfileSkillsResponse>>(LoadState.Idle)
    val skillsState: StateFlow<LoadState<ProfileSkillsResponse>> =
        _skillsState.asStateFlow()

    // -----------------------------------------------------------------
    // UI view-state flags — session-scoped (no DataStore). All of these
    // are kept on the VM rather than inside the Composable so they
    // survive process-death restore via SavedStateHandle plumbing and
    // — more importantly — recomposition-bound state hoists cleanly
    // into a single source of truth per pane.
    // -----------------------------------------------------------------

    /**
     * SOUL pane render mode. Defaults to rendered markdown — raw source
     * is an explicit opt-in toggle in the top-right of the pane. Kept
     * session-scoped because "Bailey wants raw this time" is a transient
     * preference, not something worth persisting across app restarts.
     */
    private val _soulRawView = MutableStateFlow(false)
    val soulRawView: StateFlow<Boolean> = _soulRawView.asStateFlow()

    fun toggleSoulRawView() {
        _soulRawView.value = !_soulRawView.value
    }

    /**
     * Kick off all four fetches in parallel. Safe to call more than once
     * — re-invoking replaces the load state from scratch (reverts any
     * previous Error to Loading and re-tries).
     */
    fun loadAll() {
        if (profileName.isBlank()) {
            val msg = "No profile name supplied"
            _configState.value = LoadState.Error(msg)
            _soulState.value = LoadState.Error(msg)
            _memoryState.value = LoadState.Error(msg)
            _skillsState.value = LoadState.Error(msg)
            return
        }
        refreshSection(InspectorSection.Config)
        refreshSection(InspectorSection.Soul)
        refreshSection(InspectorSection.Memory)
        refreshSection(InspectorSection.Skills)
    }

    /**
     * Refresh a single section (pull-to-refresh on one pane). Transitions
     * state to [LoadState.Loading] immediately so the UI can show a
     * progress indicator; then fires the coroutine and updates the state
     * with either [LoadState.Loaded] or [LoadState.Error].
     */
    fun refreshSection(section: InspectorSection) {
        if (profileName.isBlank()) return
        when (section) {
            InspectorSection.Config -> {
                _configState.value = LoadState.Loading
                viewModelScope.launch {
                    val result = client.fetchConfig(profileName)
                    _configState.value = result.toLoadState()
                }
            }
            InspectorSection.Soul -> {
                _soulState.value = LoadState.Loading
                viewModelScope.launch {
                    val result = client.fetchSoul(profileName)
                    _soulState.value = result.toLoadState()
                }
            }
            InspectorSection.Memory -> {
                _memoryState.value = LoadState.Loading
                viewModelScope.launch {
                    val result = client.fetchMemory(profileName)
                    _memoryState.value = result.toLoadState()
                }
            }
            InspectorSection.Skills -> {
                _skillsState.value = LoadState.Loading
                viewModelScope.launch {
                    val result = client.fetchSkills(profileName)
                    _skillsState.value = result.toLoadState()
                }
            }
        }
    }

    private fun <T> Result<T>.toLoadState(): LoadState<T> = fold(
        onSuccess = { LoadState.Loaded(it) },
        onFailure = { LoadState.Error(it.message ?: "Unknown error") },
    )

    companion object {
        /** Nav-arg key for the profile-name path segment. Matches the
         *  declaration in `Screen.ProfileInspector`. */
        const val ARG_PROFILE_NAME: String = "profileName"
    }
}
