package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.ProfileMemoryEntry
import com.hermesandroid.relay.data.ProfileSkillEntry
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.viewmodel.InspectorSection
import com.hermesandroid.relay.viewmodel.LoadState
import com.hermesandroid.relay.viewmodel.ProfileInspectorViewModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Full-screen Profile Inspector — four tabs (Config / SOUL / Memory /
 * Skills) showing the read-only per-profile views the relay exposes via
 * `GET /api/profiles/{name}/{section}`.
 *
 * Navigation entry point lives on the Settings tab (see
 * [com.hermesandroid.relay.ui.components.ProfileInspectorCard]). A
 * single top-bar refresh icon re-fetches the currently-visible tab plus
 * every other tab the user has already visited — kicked via the VM's
 * `loadAll()`.
 *
 * Each pane is lazy-loaded — the ViewModel starts in [LoadState.Idle]
 * and we call `loadAll()` in a [LaunchedEffect] tied to the profile
 * name so the fetch fires exactly once per screen entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileInspectorScreen(
    viewModel: ProfileInspectorViewModel,
    profileModel: String?,
    onBack: () -> Unit,
    /**
     * Which tab to open on entry. One of "config" | "soul" | "memory"
     * | "skills". Defaults to "config" — matches the pre-deep-link
     * behaviour for all existing callers that don't pass a section.
     * Set from the nav-graph `section` query arg (see
     * [com.hermesandroid.relay.ui.Screen.ProfileInspector.route]).
     */
    initialSection: String = "config",
) {
    val profileName = viewModel.profileName

    val configState by viewModel.configState.collectAsState()
    val soulState by viewModel.soulState.collectAsState()
    val memoryState by viewModel.memoryState.collectAsState()
    val skillsState by viewModel.skillsState.collectAsState()

    // Lazy first-load on screen entry. Keyed on the profile name so a
    // re-entry for a different profile (unlikely but possible via deep
    // link) triggers a fresh fetch.
    LaunchedEffect(profileName) {
        if (profileName.isNotBlank()) {
            viewModel.loadAll()
        }
    }

    // Surface edit-save events (success + error) through the global
    // snackbar host. Keeps the pane composables free of snackbar
    // plumbing — all save outcomes funnel through one sink.
    val snackbarHost = LocalSnackbarHost.current
    LaunchedEffect(viewModel) {
        viewModel.editEvents.collect { event ->
            when (event) {
                is ProfileInspectorViewModel.EditEvent.Saved ->
                    snackbarHost.showSnackbar(event.message)
                is ProfileInspectorViewModel.EditEvent.Error ->
                    snackbarHost.showSnackbar(event.message)
            }
        }
    }

    // Skill-toggle capability probe — fire once on screen open so the
    // Skills tab can render its Switches in the correct state before
    // the user even gets there. Keyed on profileName so a fresh
    // profile triggers a fresh probe (relay capability is server-wide
    // so the answer shouldn't change, but we're defensive).
    LaunchedEffect(profileName) {
        if (profileName.isNotBlank()) {
            viewModel.probeSkillToggleSupport()
        }
    }

    val tabs = remember {
        listOf(
            InspectorTab("Config", InspectorSection.Config),
            InspectorTab("SOUL", InspectorSection.Soul),
            InspectorTab("Memory", InspectorSection.Memory),
            InspectorTab("Skills", InspectorSection.Skills),
        )
    }
    // Resolve the incoming deep-link section arg to a tab index. An
    // unknown / unrecognized section value falls back to Config (0).
    val initialTabIndex = remember(initialSection) {
        when (initialSection.lowercase()) {
            "config" -> 0
            "soul" -> 1
            "memory" -> 2
            "skills" -> 3
            else -> 0
        }
    }
    var selectedTab by remember(initialTabIndex) { mutableStateOf(initialTabIndex) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Inspect $profileName",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (!profileModel.isNullOrBlank()) {
                            Text(
                                text = profileModel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadAll() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh all",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.label) },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (tabs[selectedTab].section) {
                    InspectorSection.Config -> ConfigPane(
                        state = configState,
                        onRetry = { viewModel.refreshSection(InspectorSection.Config) },
                    )
                    InspectorSection.Soul -> SoulPane(
                        state = soulState,
                        onRetry = { viewModel.refreshSection(InspectorSection.Soul) },
                        rawView = viewModel.soulRawView.collectAsState().value,
                        onToggleRawView = { viewModel.toggleSoulRawView() },
                        editing = viewModel.soulEditing.collectAsState().value,
                        draft = viewModel.soulDraft.collectAsState().value,
                        saving = viewModel.soulSaving.collectAsState().value,
                        onBeginEdit = { viewModel.beginSoulEdit() },
                        onDraftChange = { viewModel.updateSoulDraft(it) },
                        onSave = { viewModel.saveSoulEdit() },
                        onCancelEdit = { viewModel.cancelSoulEdit() },
                    )
                    InspectorSection.Memory -> MemoryPane(
                        state = memoryState,
                        onRetry = { viewModel.refreshSection(InspectorSection.Memory) },
                        editingFilename = viewModel.memoryEditingFilename.collectAsState().value,
                        draft = viewModel.memoryDraft.collectAsState().value,
                        saving = viewModel.memorySaving.collectAsState().value,
                        onBeginEdit = { entry ->
                            viewModel.beginMemoryEdit(entry.filename, entry.content)
                        },
                        onDraftChange = { viewModel.updateMemoryDraft(it) },
                        onSave = { viewModel.saveMemoryEdit() },
                        onCancelEdit = { viewModel.cancelMemoryEdit() },
                        onNewEntry = { filename ->
                            viewModel.beginMemoryEdit(filename, "")
                        },
                        validateFilename = { viewModel.validateMemoryFilename(it) },
                    )
                    InspectorSection.Skills -> SkillsPane(
                        state = skillsState,
                        onRetry = { viewModel.refreshSection(InspectorSection.Skills) },
                        toggleSupported = viewModel.skillToggleSupported.collectAsState().value,
                        onToggleSkill = { name, enabled ->
                            viewModel.toggleSkill(name, enabled)
                        },
                    )
                }
            }
        }
    }
}

private data class InspectorTab(val label: String, val section: InspectorSection)

// ---------------------------------------------------------------
// Config pane — JSON tree with collapsible nested objects.
// ---------------------------------------------------------------

@Composable
private fun ConfigPane(
    state: LoadState<com.hermesandroid.relay.data.ProfileConfigResponse>,
    onRetry: () -> Unit,
) {
    PaneShell(state = state, onRetry = onRetry) { response ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PathCaption(label = "Config file", path = response.path)
            if (response.readonly) {
                Text(
                    text = "Read-only view",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (response.config.isEmpty()) {
                EmptyStateRow("(empty config)")
            } else {
                JsonObjectTree(obj = response.config, depth = 0)
            }
        }
    }
}

/**
 * Recursive JSON tree renderer. Nested [JsonObject] entries render a
 * click-to-collapse header; primitives and arrays render as a single
 * key-value row with monospace values. Depth adds left indentation so
 * the hierarchy is visually obvious without drawing explicit connector
 * lines.
 *
 * [revealed] is a shared map of fully-qualified paths (e.g.
 * `providers.openai.api_key`) to their current reveal state. Tree is a
 * recursive composable, so a single map threaded from [ConfigPane]
 * keeps the session-scoped reveal state out of the VM (it's strictly
 * transient — wipes on leaving the screen) and outside any nested
 * JsonObject's remember { } scope.
 */
@Composable
private fun JsonObjectTree(
    obj: JsonObject,
    depth: Int,
    pathPrefix: String = "",
    revealed: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean> =
        remember { mutableStateMapOf() },
) {
    // Per-key expanded state — collapsed by default so big configs don't
    // blow up the initial screen. Users tap to drill in.
    val expanded = remember(obj) { mutableStateMapOf<String, Boolean>() }
    val indent = (depth * 12).dp

    Column(modifier = Modifier.fillMaxWidth()) {
        for ((key, value) in obj.entries) {
            val childPath = if (pathPrefix.isEmpty()) key else "$pathPrefix.$key"
            when (value) {
                is JsonObject -> {
                    val isOpen = expanded[key] == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded[key] = !isOpen }
                            .padding(start = indent, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isOpen) {
                                Icons.Filled.ExpandLess
                            } else {
                                Icons.Filled.ExpandMore
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = key,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "{${value.size} field${if (value.size == 1) "" else "s"}}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isOpen) {
                        JsonObjectTree(
                            obj = value,
                            depth = depth + 1,
                            pathPrefix = childPath,
                            revealed = revealed,
                        )
                    }
                }

                is JsonArray -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = indent, top = 4.dp, bottom = 4.dp),
                    ) {
                        Text(
                            text = "$key:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = renderJsonValue(value),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                is JsonPrimitive, JsonNull -> {
                    // Masking applies only to string primitives whose key
                    // name matches the secret regex. Numbers, booleans, and
                    // nulls render verbatim — there's no API key pretending
                    // to be an int. The mask is applied inside renderMaskedValue
                    // so the raw string never reaches the text buffer until
                    // the user actively taps the eye icon.
                    val isSecret = value is JsonPrimitive &&
                        value.isString &&
                        isSecretKey(key)
                    val isRevealed = isSecret && revealed[childPath] == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = indent, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$key:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSecret) {
                                renderMaskedValue(
                                    value as JsonPrimitive,
                                    reveal = isRevealed,
                                )
                            } else {
                                renderJsonValue(value)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isSecret) {
                            IconButton(
                                onClick = {
                                    revealed[childPath] = !isRevealed
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = if (isRevealed) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (isRevealed) {
                                        "Hide value"
                                    } else {
                                        "Reveal value"
                                    },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Key-name heuristic for masking sensitive config values. Matches anything
 * containing (case-insensitive) "key", "token", "secret", "password", or
 * "credential" — same sentinel vocabulary the upstream Hermes config
 * loader uses for its own `_redact_secrets` sweep.
 *
 * False positives like "keymap" or "keyframes" are acceptable — a spurious
 * mask is always safer than a leak, and the user can still reveal
 * with a tap.
 */
private val SECRET_KEY_REGEX = Regex(
    "(?i).*(key|token|secret|password|credential).*"
)

private fun isSecretKey(key: String): Boolean = SECRET_KEY_REGEX.matches(key)

/**
 * Mask a string primitive while still leaving enough shape for the user
 * to confirm "what key is set". Short values (< 12 chars) render as a
 * full `"********"` mask so we don't leak prefix/suffix for tokens that
 * would be trivially guessable in-full.
 *
 * 12 chars is the threshold because most real provider keys are well
 * above that (sk-ant-* runs ~100, OpenAI ~50, etc.) — anything shorter
 * is probably a placeholder or a tiny shared secret where even 8 chars
 * of prefix/suffix reveal too much.
 */
private fun renderMaskedValue(primitive: JsonPrimitive, reveal: Boolean): String {
    val raw = primitive.content
    if (reveal) return "\"$raw\""
    val trimmed = raw
    return if (trimmed.length < 12) {
        "\"********\""
    } else {
        "\"${trimmed.take(4)}...${trimmed.takeLast(4)}\""
    }
}

/**
 * Compact one-line rendering of a JSON value for the tree-leaf rows.
 * Arrays become `[v1, v2, v3]`, strings get literal quotes, numbers
 * and booleans pass through. Truncated at 120 chars to avoid blowing
 * out the row height.
 */
private fun renderJsonValue(value: JsonElement): String {
    val raw = when (value) {
        is JsonPrimitive -> {
            if (value.isString) "\"${value.content}\"" else value.content
        }
        is JsonNull -> "null"
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { renderJsonValue(it) }
        is JsonObject -> "{…}"
    }
    return if (raw.length > 120) raw.take(117) + "…" else raw
}

// ---------------------------------------------------------------
// SOUL pane
// ---------------------------------------------------------------

@Composable
private fun SoulPane(
    state: LoadState<com.hermesandroid.relay.data.ProfileSoulResponse>,
    onRetry: () -> Unit,
    rawView: Boolean,
    onToggleRawView: () -> Unit,
    editing: Boolean,
    draft: String,
    saving: Boolean,
    onBeginEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    PaneShell(state = state, onRetry = onRetry) { response ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (response.exists && response.truncated) {
                TruncatedBanner()
            }

            // Pane header row — path + size on the left, action icons on
            // the right. In view-mode we show the render-mode toggle and
            // a pencil; in edit-mode, only the pencil is swapped for a
            // close-X so the user has a clear "abort" affordance.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    PathCaption(
                        label = if (response.exists) "SOUL file" else "SOUL file (not created)",
                        path = response.path,
                    )
                    if (response.exists) {
                        Text(
                            text = "${response.sizeBytes} bytes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!editing) {
                    // Raw-view toggle only makes sense outside edit mode
                    // — the editor is always monospace source.
                    IconButton(onClick = onToggleRawView) {
                        Icon(
                            imageVector = if (rawView) {
                                Icons.Filled.TextFields
                            } else {
                                Icons.Filled.Code
                            },
                            contentDescription = if (rawView) {
                                "Show rendered markdown"
                            } else {
                                "Show raw source"
                            },
                        )
                    }
                    IconButton(onClick = onBeginEdit) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit SOUL",
                        )
                    }
                } else {
                    IconButton(
                        onClick = onCancelEdit,
                        enabled = !saving,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel edit",
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            if (editing) {
                MonospaceEditor(
                    content = draft,
                    onContentChange = onDraftChange,
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                EditorBottomBar(
                    saving = saving,
                    canSave = true,
                    onSave = onSave,
                    onCancel = onCancelEdit,
                )
            } else if (!response.exists) {
                // Empty state — profile has no SOUL.md. Offer to create
                // one via the same edit flow.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "No SOUL.md for this profile",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Tap the pencil icon above to create one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(12.dp),
                ) {
                    if (rawView) {
                        Text(
                            text = response.content,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        )
                    } else {
                        // Rendered markdown — same library the chat bubbles
                        // use (mikepenz multiplatform-markdown-renderer m3),
                        // wrapped in our project-local [MarkdownContent]
                        // composable so code blocks get the same highlight
                        // + copy-button treatment chat does.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            com.hermesandroid.relay.ui.components.MarkdownContent(
                                content = response.content,
                                textColor = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Monospace editor with a line-numbered gutter on the left. No heavy
 * CodeMirror wrapper — just a [BasicTextField] in a Row next to a
 * computed gutter column.
 *
 * Line numbers recompute on every content change. For typical SOUL /
 * memory sizes (a few KB at most) this is trivial; if we ever host
 * truly large files we'd swap to a virtualized editor.
 */
@Composable
private fun MonospaceEditor(
    content: String,
    onContentChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // Scroll state shared by both gutter and editor so line numbers
    // stay aligned with their rows during vertical scroll.
    val scroll = rememberScrollState()
    val lines = remember(content) { content.count { it == '\n' } + 1 }
    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
    ) {
        // Line-number gutter — rendered via a single joined Text so
        // the per-line positions match the editor's natural wrapping.
        // A BasicTextField does not wrap newlines, so each \n in the
        // content starts a new visible line, keeping the 1:1 mapping.
        val gutter = remember(lines) {
            (1..lines).joinToString("\n") { it.toString() }
        }
        Text(
            text = gutter,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = 8.dp)
                .verticalScroll(scroll),
        )
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth(fraction = 0.01f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
        )
        androidx.compose.foundation.text.BasicTextField(
            value = content,
            onValueChange = { if (enabled) onContentChange(it) },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(scroll)
                .padding(start = 8.dp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun EditorBottomBar(
    saving: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onCancel,
            enabled = !saving,
            modifier = Modifier.weight(1f),
        ) {
            Text("Cancel")
        }
        FilledTonalButton(
            onClick = onSave,
            enabled = !saving && canSave,
            modifier = Modifier.weight(1f),
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Saving…")
            } else {
                Text("Save")
            }
        }
    }
}

// ---------------------------------------------------------------
// Memory pane — expandable cards per entry.
// ---------------------------------------------------------------

@Composable
private fun MemoryPane(
    state: LoadState<com.hermesandroid.relay.data.ProfileMemoryResponse>,
    onRetry: () -> Unit,
    editingFilename: String?,
    draft: String,
    saving: Boolean,
    onBeginEdit: (ProfileMemoryEntry) -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    onNewEntry: (String) -> Unit,
    validateFilename: (String) -> String?,
) {
    PaneShell(state = state, onRetry = onRetry) { response ->
        Column(modifier = Modifier.fillMaxSize()) {
            // Expansion state by filename — kept in a mutableStateMap so
            // the list recomposition doesn't drop open-cards when other
            // state updates.
            val expanded = remember { mutableStateMapOf<String, Boolean>() }

            // "New entry" dialog — shown when the user taps the FAB-like
            // button at the bottom of the pane. Local state (not hoisted
            // to the VM) because the dialog's content is ephemeral input
            // that becomes an edit session once accepted.
            var showNewDialog by remember { mutableStateOf(false) }

            // When the user created a new (not-yet-persisted) entry via
            // the "+ New entry" dialog, its filename is in
            // [editingFilename] but has no matching ProfileMemoryEntry
            // in [response.entries]. Synthesize a placeholder card at
            // the top of the list so the editor has somewhere to render.
            val newEntryFilename = editingFilename?.takeIf { name ->
                response.entries.none { it.filename == name }
            }

            if (response.entries.isEmpty() && editingFilename == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "No memory entries for this profile",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Memories directory:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = response.memoriesDir,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Synthetic "new entry" card — rendered first when
                    // the user is editing a filename not yet in the
                    // persisted list. Sizes/paths are empty until the
                    // save round-trip completes and the pane reloads.
                    if (newEntryFilename != null) {
                        item(key = "__new__:$newEntryFilename") {
                            val placeholder = ProfileMemoryEntry(
                                name = newEntryFilename.removeSuffix(".md"),
                                filename = newEntryFilename,
                                path = "(pending save)",
                                content = "",
                                sizeBytes = 0L,
                                truncated = false,
                            )
                            MemoryEntryCard(
                                entry = placeholder,
                                expanded = true,
                                onToggle = { },
                                editing = true,
                                draft = draft,
                                saving = saving,
                                onBeginEdit = { },
                                onDraftChange = onDraftChange,
                                onSave = onSave,
                                onCancelEdit = onCancelEdit,
                            )
                        }
                    }
                    items(
                        items = response.entries,
                        key = { it.filename },
                    ) { entry ->
                        val isOpen = expanded[entry.filename] == true
                        val isEditing = editingFilename == entry.filename
                        MemoryEntryCard(
                            entry = entry,
                            expanded = isOpen || isEditing,
                            onToggle = { expanded[entry.filename] = !isOpen },
                            editing = isEditing,
                            draft = draft,
                            saving = saving,
                            onBeginEdit = { onBeginEdit(entry) },
                            onDraftChange = onDraftChange,
                            onSave = onSave,
                            onCancelEdit = onCancelEdit,
                        )
                    }
                }
            }

            // Bottom "+ New entry" button — rendered whether the list is
            // empty or populated. Disabled while an edit is already in
            // flight so we don't stack drafts.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedButton(
                    onClick = { showNewDialog = true },
                    enabled = editingFilename == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New entry")
                }
            }

            if (showNewDialog) {
                NewMemoryEntryDialog(
                    existingFilenames = response.entries.map { it.filename },
                    validateFilename = validateFilename,
                    onCreate = { filename ->
                        showNewDialog = false
                        onNewEntry(filename)
                    },
                    onDismiss = { showNewDialog = false },
                )
            }
        }
    }
}

/**
 * Filename-prompt dialog for creating a new memory entry. Validates
 * against [validateFilename] (the VM-exposed rule set: must end in
 * `.md`, no slashes, no `..`, no leading `.`) plus a collision check
 * against [existingFilenames]. The current implementation rejects
 * collisions — editing an existing entry goes through the per-card
 * pencil affordance, not this dialog.
 */
@Composable
private fun NewMemoryEntryDialog(
    existingFilenames: List<String>,
    validateFilename: (String) -> String?,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filename by remember { mutableStateOf("") }
    val error = remember(filename, existingFilenames) {
        if (filename.isBlank()) null
        else validateFilename(filename)
            ?: if (filename in existingFilenames) "A file with that name already exists" else null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New memory entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Filename must end in .md",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = filename,
                    onValueChange = { filename = it },
                    placeholder = { Text("notes.md") },
                    isError = error != null && filename.isNotBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null && filename.isNotBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(filename.trim()) },
                enabled = filename.isNotBlank() && error == null,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun MemoryEntryCard(
    entry: ProfileMemoryEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    editing: Boolean,
    draft: String,
    saving: Boolean,
    onBeginEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !editing, onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.filename,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${entry.sizeBytes} bytes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!editing) {
                    IconButton(onClick = onBeginEdit) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit entry",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Filled.ExpandLess
                        } else {
                            Icons.Filled.ExpandMore
                        },
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    IconButton(
                        onClick = onCancelEdit,
                        enabled = !saving,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel edit",
                        )
                    }
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(8.dp))
                if (!editing && entry.truncated) {
                    TruncatedBanner()
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (editing) {
                    MonospaceEditor(
                        content = draft,
                        onContentChange = onDraftChange,
                        enabled = !saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 420.dp),
                    )
                    EditorBottomBar(
                        saving = saving,
                        canSave = true,
                        onSave = onSave,
                        onCancel = onCancelEdit,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(6.dp),
                            )
                            .padding(8.dp),
                    ) {
                        Text(
                            text = entry.content.ifBlank { "(empty)" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------
// Skills pane — grouped by category.
// ---------------------------------------------------------------

@Composable
private fun SkillsPane(
    state: LoadState<com.hermesandroid.relay.data.ProfileSkillsResponse>,
    onRetry: () -> Unit,
    toggleSupported: Boolean?,
    onToggleSkill: (String, Boolean) -> Unit,
) {
    PaneShell(state = state, onRetry = onRetry) { response ->
        if (response.skills.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "No skills in this profile",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Total: ${response.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Group by category, preserving insertion order (server-side
            // ordering is the source of truth). A LinkedHashMap keeps the
            // traversal order stable.
            val grouped = remember(response.skills) {
                response.skills.groupBy { it.category.ifBlank { "(uncategorized)" } }
                    .toList() // preserves group order in the JSON payload
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = grouped,
                    key = { pair -> pair.first },
                ) { pair ->
                    SkillCategorySection(
                        category = pair.first,
                        skills = pair.second,
                        toggleSupported = toggleSupported,
                        onToggleSkill = onToggleSkill,
                    )
                }
                if (toggleSupported == false) {
                    // Bottom-of-list caption when the probe confirmed
                    // the endpoint isn't available server-side. Kept
                    // out of each card so the message is shown once
                    // rather than N times.
                    item(key = "__toggle_unsupported_caption__") {
                        Text(
                            text = "Enable/disable requires a newer server.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillCategorySection(
    category: String,
    skills: List<ProfileSkillEntry>,
    toggleSupported: Boolean?,
    onToggleSkill: (String, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = category,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                skills.forEachIndexed { index, skill ->
                    SkillRow(
                        skill = skill,
                        toggleSupported = toggleSupported,
                        onToggleSkill = onToggleSkill,
                    )
                    if (index != skills.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillRow(
    skill: ProfileSkillEntry,
    toggleSupported: Boolean?,
    onToggleSkill: (String, Boolean) -> Unit,
) {
    // Optimistic local toggle state. The VM's emitted events revert us
    // on failure; on success the next `/skills` refetch will overwrite
    // this with the server's canonical value. Keyed on (skill, enabled)
    // so a fresh fetch resets the local pick.
    var localEnabled by remember(skill.name, skill.enabled) {
        mutableStateOf(skill.enabled)
    }
    // 501-known-unsupported → ghost the switch entirely.
    // null (probe hasn't completed) → leave tappable but the PUT will
    // ask authoritatively.
    val switchEnabled = toggleSupported != false

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!localEnabled) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(disabled)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.material3.Switch(
            checked = localEnabled,
            enabled = switchEnabled,
            onCheckedChange = { new ->
                // Optimistic flip; VM revert on 501 will come back via
                // the snackbar error event. We don't have a direct
                // "revert visually" hook here without VM-state feedback,
                // so we key off the revealed `toggleSupported` value
                // the next recomposition sees — when the VM updates
                // the flag to false post-call, we reset the switch to
                // the prior state on the next pass.
                localEnabled = new
                onToggleSkill(skill.name, new)
            },
        )
    }
    // Revert visual state if the probe flipped to unsupported AFTER the
    // user tapped. Compose effect keyed on toggleSupported + the skill's
    // server-reported enabled flag.
    LaunchedEffect(toggleSupported, skill.enabled) {
        if (toggleSupported == false && localEnabled != skill.enabled) {
            localEnabled = skill.enabled
        }
    }
}

// ---------------------------------------------------------------
// Shared UI bits
// ---------------------------------------------------------------

/**
 * Wrapper that renders the three shared pane states (Loading / Error /
 * Loaded) so individual panes only need to implement the Loaded body.
 * Idle renders as Loading-without-spinner so the user doesn't see
 * flashes when a section is entered before `loadAll()` completes.
 */
@Composable
private fun <T> PaneShell(
    state: LoadState<T>,
    onRetry: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is LoadState.Idle, is LoadState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (state is LoadState.Loading) {
                    CircularProgressIndicator()
                }
            }
        }
        is LoadState.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
        is LoadState.Loaded<T> -> {
            content(state.value)
        }
    }
}

@Composable
private fun PathCaption(label: String, path: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TruncatedBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Content truncated by relay — showing the first slice only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun EmptyStateRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
