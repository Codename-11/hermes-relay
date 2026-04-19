package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

    val tabs = remember {
        listOf(
            InspectorTab("Config", InspectorSection.Config),
            InspectorTab("SOUL", InspectorSection.Soul),
            InspectorTab("Memory", InspectorSection.Memory),
            InspectorTab("Skills", InspectorSection.Skills),
        )
    }
    var selectedTab by remember { mutableStateOf(0) }

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
                    )
                    InspectorSection.Memory -> MemoryPane(
                        state = memoryState,
                        onRetry = { viewModel.refreshSection(InspectorSection.Memory) },
                    )
                    InspectorSection.Skills -> SkillsPane(
                        state = skillsState,
                        onRetry = { viewModel.refreshSection(InspectorSection.Skills) },
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
 */
@Composable
private fun JsonObjectTree(obj: JsonObject, depth: Int) {
    // Per-key expanded state — collapsed by default so big configs don't
    // blow up the initial screen. Users tap to drill in.
    val expanded = remember(obj) { mutableStateMapOf<String, Boolean>() }
    val indent = (depth * 12).dp

    Column(modifier = Modifier.fillMaxWidth()) {
        for ((key, value) in obj.entries) {
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
                        JsonObjectTree(obj = value, depth = depth + 1)
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
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
) {
    PaneShell(state = state, onRetry = onRetry) { response ->
        if (!response.exists) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "No SOUL.md for this profile",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Expected at:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = response.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (response.truncated) {
                    TruncatedBanner()
                }
                // Pane header row — path caption on the left, render-mode
                // toggle on the right. The toggle icon flips between the
                // code (raw) glyph and the text-fields (rendered) glyph,
                // so the icon in the button represents the *target* mode
                // the user would switch to, matching iconography the chat
                // bubbles use when showing a raw-source view.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        PathCaption(label = "SOUL file", path = response.path)
                        Text(
                            text = "${response.sizeBytes} bytes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                }
                Spacer(modifier = Modifier.height(4.dp))
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

// ---------------------------------------------------------------
// Memory pane — expandable cards per entry.
// ---------------------------------------------------------------

@Composable
private fun MemoryPane(
    state: LoadState<com.hermesandroid.relay.data.ProfileMemoryResponse>,
    onRetry: () -> Unit,
) {
    PaneShell(state = state, onRetry = onRetry) { response ->
        if (response.entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
            // Expansion state by filename — kept in a mutableStateMap so
            // the list recomposition doesn't drop open-cards when other
            // state updates.
            val expanded = remember { mutableStateMapOf<String, Boolean>() }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = response.entries,
                    key = { it.filename },
                ) { entry ->
                    val isOpen = expanded[entry.filename] == true
                    MemoryEntryCard(
                        entry = entry,
                        expanded = isOpen,
                        onToggle = { expanded[entry.filename] = !isOpen },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryEntryCard(
    entry: ProfileMemoryEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
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
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.ExpandLess
                    } else {
                        Icons.Filled.ExpandMore
                    },
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(8.dp))
                if (entry.truncated) {
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

// ---------------------------------------------------------------
// Skills pane — grouped by category.
// ---------------------------------------------------------------

@Composable
private fun SkillsPane(
    state: LoadState<com.hermesandroid.relay.data.ProfileSkillsResponse>,
    onRetry: () -> Unit,
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
                    SkillCategorySection(category = pair.first, skills = pair.second)
                }
            }
        }
    }
}

@Composable
private fun SkillCategorySection(category: String, skills: List<ProfileSkillEntry>) {
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
                    SkillRow(skill = skill)
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
private fun SkillRow(skill: ProfileSkillEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!skill.enabled) {
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
