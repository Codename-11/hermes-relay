package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.hermesandroid.relay.data.Profile

/**
 * Agent-profile picker — shows server-advertised profiles from the `auth.ok`
 * payload's `profiles` field. Each entry in [profiles] is an agent config
 * (`{name, model, description}`) from the server's `~/.hermes/config.yaml`.
 *
 * Selection semantics:
 *  - `null` [selected] → "Default"; the chat request omits `model` so the
 *    server uses its configured default.
 *  - non-null [selected] → the request body carries `"model": selected.model`
 *    as an override for that turn only.
 *
 * Visual structure mirrors [PersonalityPicker] — the two chips sit side-by-
 * side in the chat top bar and share the same interaction vocabulary
 * (TextButton + drop-caret, DropdownMenu, selected-row check icon).
 *
 * The chip is hidden entirely when [profiles] is empty — the server has
 * nothing to offer, so there is no dead UI to render. When [enabled] is
 * false (e.g. mid-stream) the chip greys out and taps are no-ops so a
 * profile swap can't race an in-flight chat request.
 */
@Composable
fun ProfilePicker(
    profiles: List<Profile>,
    selected: Profile?,
    onSelect: (Profile?) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // No profiles advertised → no chip. Keeps the top bar clean on servers
    // that don't define `profiles:` in config.yaml (common case on a stock
    // install).
    if (profiles.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    val displayName: String = selected?.name?.replaceFirstChar { it.uppercase() }
        ?: "Default"

    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
        ) {
            Text(
                text = displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Select agent profile",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            // "Default" row — clears the override so the server falls back
            // to whatever is configured in `agent.model` on the gateway.
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = "Default",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Server-configured model",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingIcon = {
                    if (selected == null) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )

            HorizontalDivider()

            profiles.forEach { profile ->
                val isActive = selected?.name == profile.name
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = profile.name.replaceFirstChar {
                                    it.uppercase()
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = profile.model,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (profile.description.isNotBlank()) {
                                Text(
                                    text = profile.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        if (isActive) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        onSelect(profile)
                        expanded = false
                    },
                )
            }
        }
    }
}
