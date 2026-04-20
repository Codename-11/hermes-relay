package com.hermesandroid.relay.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.data.ProfileConfigResponse
import com.hermesandroid.relay.data.ProfileMemoryResponse
import com.hermesandroid.relay.data.ProfileSkillsResponse
import com.hermesandroid.relay.data.ProfileSoulResponse
import com.hermesandroid.relay.network.RelayProfileInspectorClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    // -----------------------------------------------------------------
    // Edit-mode state for SOUL + memory panes.
    //
    // Both panes share the same edit-mode pattern:
    //   1. User taps the pencil icon → enters edit mode.
    //   2. A monospace BasicTextField lets them type; state is kept in
    //      [soulDraft] / [memoryDraft]. The currently-saving flag is
    //      [soulSaving] / [memorySaving] so the Save button disables
    //      and a progress indicator renders.
    //   3. On Save we PUT and, on success, reload the pane (fresh
    //      content from disk) and emit an [EditEvent.Saved] so the
    //      screen shows a snackbar. On failure we stay in edit mode so
    //      the user can retry.
    //
    // Per-memory-entry edit uses the same triple of flows keyed by
    // filename. A separate key-based design (rather than "one memory
    // entry at a time") keeps the door open to tabbed edits later
    // without rewiring the state model.
    // -----------------------------------------------------------------

    /** User-facing snackbar events emitted by the edit pipeline. */
    sealed class EditEvent {
        data class Saved(val message: String) : EditEvent()
        data class Error(val message: String) : EditEvent()
    }

    private val _editEvents = MutableSharedFlow<EditEvent>(
        extraBufferCapacity = 8,
    )
    val editEvents: SharedFlow<EditEvent> = _editEvents.asSharedFlow()

    // --- SOUL edit state ---------------------------------------------

    private val _soulEditing = MutableStateFlow(false)
    val soulEditing: StateFlow<Boolean> = _soulEditing.asStateFlow()

    private val _soulDraft = MutableStateFlow("")
    val soulDraft: StateFlow<String> = _soulDraft.asStateFlow()

    private val _soulSaving = MutableStateFlow(false)
    val soulSaving: StateFlow<Boolean> = _soulSaving.asStateFlow()

    fun beginSoulEdit() {
        val current = (soulState.value as? LoadState.Loaded)?.value?.content ?: ""
        _soulDraft.value = current
        _soulEditing.value = true
    }

    fun updateSoulDraft(content: String) {
        _soulDraft.value = content
    }

    fun cancelSoulEdit() {
        _soulEditing.value = false
        _soulDraft.value = ""
    }

    fun saveSoulEdit() {
        if (profileName.isBlank() || _soulSaving.value) return
        val content = _soulDraft.value
        _soulSaving.value = true
        viewModelScope.launch {
            val result = client.updateSoul(profileName, content)
            _soulSaving.value = false
            result.fold(
                onSuccess = {
                    _soulEditing.value = false
                    _soulDraft.value = ""
                    _editEvents.tryEmit(EditEvent.Saved("SOUL saved"))
                    // Re-fetch the pane so the user sees freshly-loaded
                    // content (byte counts, truncation flags etc.).
                    refreshSection(InspectorSection.Soul)
                },
                onFailure = { err ->
                    _editEvents.tryEmit(
                        EditEvent.Error(err.message ?: "Save failed")
                    )
                },
            )
        }
    }

    // --- Memory edit state (keyed by filename) ------------------------

    /**
     * Filename of the memory entry currently being edited, or `null`
     * when no memory entry is in edit mode. A single active edit at a
     * time — matches the card-expansion model already in the pane.
     */
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

    fun updateMemoryDraft(content: String) {
        _memoryDraft.value = content
    }

    fun cancelMemoryEdit() {
        _memoryEditingFilename.value = null
        _memoryDraft.value = ""
    }

    fun saveMemoryEdit() {
        val filename = _memoryEditingFilename.value ?: return
        if (profileName.isBlank() || _memorySaving.value) return
        val content = _memoryDraft.value
        // Client-side filename sanity so we don't round-trip an obvious
        // bad name and eat a 400. Server validates authoritatively.
        val err = validateMemoryFilename(filename)
        if (err != null) {
            _editEvents.tryEmit(EditEvent.Error(err))
            return
        }
        _memorySaving.value = true
        viewModelScope.launch {
            val result = client.updateMemoryEntry(profileName, filename, content)
            _memorySaving.value = false
            result.fold(
                onSuccess = {
                    _memoryEditingFilename.value = null
                    _memoryDraft.value = ""
                    _editEvents.tryEmit(EditEvent.Saved("Memory entry saved"))
                    refreshSection(InspectorSection.Memory)
                },
                onFailure = { e ->
                    _editEvents.tryEmit(
                        EditEvent.Error(e.message ?: "Save failed")
                    )
                },
            )
        }
    }

    // -----------------------------------------------------------------
    // Skill toggle — server stubs this out as HTTP 501 today. We expose
    // the probe result so the Skills pane can disable the Switch until
    // the relay implements the endpoint, and we emit the 501 response
    // as an EditEvent.Error on optimistic tap so the UI can revert
    // the Switch visual state.
    // -----------------------------------------------------------------

    /**
     * Capability flag. `null` = probe hasn't run yet (Switch renders
     * enabled-but-pending); `true` = server claimed support on the
     * capability probe; `false` = 501 / 404 / 405 — definitively not
     * supported, Switch renders ghosted.
     */
    private val _skillToggleSupported = MutableStateFlow<Boolean?>(null)
    val skillToggleSupported: StateFlow<Boolean?> = _skillToggleSupported.asStateFlow()

    /**
     * One-shot capability probe. Fires at screen-open time from the
     * Composable; idempotent — extra calls during the screen's lifetime
     * reprobe but leave a positive result in place on failure.
     */
    fun probeSkillToggleSupport() {
        viewModelScope.launch {
            val supported = client.probeSkillToggleSupported()
            _skillToggleSupported.value = supported
        }
    }

    /**
     * Optimistic toggle — UI flips the switch immediately, then we PUT.
     * On a 501 we emit an error event so the screen can revert the
     * local visual state and cache "not supported" so subsequent taps
     * are short-circuited.
     */
    fun toggleSkill(skillName: String, enabled: Boolean) {
        viewModelScope.launch {
            val result = client.updateSkillToggle(skillName, enabled)
            result.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is RelayProfileInspectorClient.SkillToggleResult.Ok ->
                            _editEvents.tryEmit(
                                EditEvent.Saved(
                                    if (enabled) "Enabled $skillName" else "Disabled $skillName"
                                )
                            )
                        is RelayProfileInspectorClient.SkillToggleResult.NotImplemented -> {
                            _skillToggleSupported.value = false
                            _editEvents.tryEmit(
                                EditEvent.Error("Skill toggle not yet supported on this server")
                            )
                        }
                    }
                },
                onFailure = { err ->
                    _editEvents.tryEmit(
                        EditEvent.Error(err.message ?: "Skill toggle failed")
                    )
                },
            )
        }
    }

    /**
     * Local filename sanity for new/updated memory entries. Mirrors
     * the rules the server worker enforces:
     *   - Must end in `.md`.
     *   - No path-traversal components (`..`).
     *   - No slashes/backslashes.
     *   - No leading `.` (dotfiles).
     *
     * Returns the error string to show, or null when the name passes.
     * Running this client-side saves a server round-trip for the
     * common typo cases and produces a tighter error ("filename must
     * end in .md") than the server's generic 400.
     */
    fun validateMemoryFilename(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Filename required"
        if (!trimmed.endsWith(".md", ignoreCase = false)) {
            return "Filename must end in .md"
        }
        if (trimmed.startsWith(".")) return "Filename cannot start with '.'"
        if (trimmed.contains("/") || trimmed.contains("\\")) {
            return "Filename cannot contain slashes"
        }
        if (trimmed.contains("..")) return "Filename cannot contain '..'"
        return null
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
