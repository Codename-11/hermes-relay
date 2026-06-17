package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
 * Searchable model picker as a bottom sheet — a cleaner surface than the inline
 * dropdown for the 100+ models the gateway exposes across providers. Mirrors
 * [CommandPalette]'s pattern (header + search + grouped LazyColumn).
 *
 * This is a DETAIL view, so it shows the PROVIDER (group headers) and per-model
 * availability; the compact chat composer pill stays model-only by design.
 *
 * Consumes the same [ChatInputPickerOption] list the inline chip used, so the
 * "Server default" entry (value == null), current-provider-first ordering,
 * "Not on your plan" / "Needs setup" secondaries, and disabled (unavailable)
 * state all carry over unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    options: List<ChatInputPickerOption>,
    onSelect: (ChatInputPickerOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    // "Server default" is the pinned, ungrouped option (value == null).
    val defaultOption = remember(options) { options.firstOrNull { it.value == null } }
    val modelOptions = remember(options) { options.filter { it.value != null } }

    val filtered = remember(modelOptions, query) {
        if (query.isBlank()) {
            modelOptions
        } else {
            modelOptions.filter { opt ->
                opt.label.contains(query, ignoreCase = true) ||
                    (opt.group?.contains(query, ignoreCase = true) == true)
            }
        }
    }
    // groupBy preserves key insertion order, so providers stay current-first
    // (the caller sorts is_current to the front).
    val grouped = remember(filtered) {
        filtered.groupBy { it.group?.takeIf { g -> g.isNotBlank() } ?: "Other" }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 400.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Model", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${modelOptions.size} models",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("Search models or providers…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear search",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                // Server default pinned at the top (only when not searching).
                if (defaultOption != null && query.isBlank()) {
                    item(key = "server_default") {
                        ModelPickerRow(option = defaultOption, onClick = { onSelect(defaultOption) })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }

                grouped.forEach { (provider, opts) ->
                    item(key = "header_$provider") {
                        Text(
                            text = provider,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp,
                            ),
                        )
                    }
                    items(items = opts, key = { "$provider:${it.value}" }) { opt ->
                        ModelPickerRow(option = opt, onClick = { onSelect(opt) })
                    }
                }

                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No models match your search",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    option: ChatInputPickerOption,
    onClick: () -> Unit,
) {
    val enabled = option.enabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!option.secondary.isNullOrBlank()) {
                Text(
                    text = option.secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (option.selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
