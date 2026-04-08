package com.hermesandroid.relay.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A slash command entry — built-in, personality, or server skill.
 */
data class SlashCommand(
    val command: String,
    val description: String,
    val category: String = "built-in"
)

/**
 * Full-screen command palette as a bottom sheet.
 * Shows all available commands grouped by category with search.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CommandPalette(
    commands: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var categoriesExpanded by remember { mutableStateOf(false) }

    // Get unique categories in a logical order
    val categories = remember(commands) {
        val priorityOrder = listOf("session", "configuration", "info", "personality")
        commands.map { it.category }.distinct().sortedWith(
            compareBy<String> {
                val idx = priorityOrder.indexOf(it)
                if (idx >= 0) idx else priorityOrder.size
            }.thenBy { it }
        )
    }

    // Filter commands by search + category
    val filtered = remember(commands, searchQuery, selectedCategory) {
        commands.filter { cmd ->
            val matchesSearch = searchQuery.isBlank() ||
                cmd.command.contains(searchQuery, ignoreCase = true) ||
                cmd.description.contains(searchQuery, ignoreCase = true)
            val matchesCategory = selectedCategory == null || cmd.category == selectedCategory
            matchesSearch && matchesCategory
        }
    }

    // Group filtered commands by category with priority ordering
    val grouped = remember(filtered, categories) {
        val order = categories.withIndex().associate { (i, v) -> v to i }
        filtered.groupBy { it.category }
            .toSortedMap(compareBy { order[it] ?: Int.MAX_VALUE })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 400.dp)
                .padding(bottom = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Commands",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${filtered.size} available",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("Search commands...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear search",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Category filter chips with expand/collapse
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    maxLines = if (categoriesExpanded) Int.MAX_VALUE else 2,
                    overflow = FlowRowOverflow.Clip
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    categories.forEach { category ->
                        val count = commands.count { it.category == category }
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = {
                                selectedCategory = if (selectedCategory == category) null else category
                            },
                            label = {
                                Text(
                                    text = "${formatCategoryName(category)} ($count)",
                                    maxLines = 1
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }

                if (categories.size > 6) {
                    TextButton(
                        onClick = { categoriesExpanded = !categoriesExpanded },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = if (categoriesExpanded) "Show less"
                                   else "Show all (${categories.size})",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()

            // Command list grouped by category
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                grouped.forEach { (category, cmds) ->
                    // Category header
                    item(key = "header_$category") {
                        Text(
                            text = formatCategoryName(category),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp,
                                top = 12.dp, bottom = 4.dp
                            )
                        )
                    }

                    items(
                        items = cmds,
                        key = { "${it.category}:${it.command}" }
                    ) { cmd ->
                        CommandRow(
                            command = cmd,
                            onClick = { onSelect(cmd) }
                        )
                    }
                }

                // Empty state
                if (filtered.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No commands match your search",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single command row used in both the palette and the inline autocomplete.
 */
@Composable
fun CommandRow(
    command: SlashCommand,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = command.command,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (!compact && command.category != "built-in" && command.category != "personality") {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = command.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Text(
            text = command.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Inline autocomplete popup for quick command entry.
 * Shows filtered results as user types "/" in chat input.
 */
@Composable
fun InlineAutocomplete(
    commands: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        tonalElevation = 2.dp
    ) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 280.dp)
        ) {
            items(
                items = commands,
                key = { "${it.category}:${it.command}" }
            ) { cmd ->
                CommandRow(
                    command = cmd,
                    onClick = { onSelect(cmd) },
                    compact = true
                )
                if (cmd != commands.last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

/** Format category name: "software-development" → "Software Development" */
private fun formatCategoryName(category: String): String {
    return category.split("-").joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
    }
}
