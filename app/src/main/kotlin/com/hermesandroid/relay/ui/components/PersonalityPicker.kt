package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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

/**
 * Personality picker — shows server-configured personalities from GET /api/config.
 * "Default" uses the server's active personality (config.display.personality).
 * Other entries are from config.agent.personalities.
 */
@Composable
fun PersonalityPicker(
    selected: String,
    personalities: List<String>,
    defaultName: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = if (selected == "default") {
        if (defaultName.isNotBlank()) {
            defaultName.replaceFirstChar { it.uppercase() }
        } else "Default"
    } else {
        selected.replaceFirstChar { it.uppercase() }
    }

    Box(modifier = modifier) {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Select personality"
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // Default (server's active personality)
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (defaultName.isNotBlank()) {
                            "${defaultName.replaceFirstChar { it.uppercase() }} (default)"
                        } else "Default",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    onSelect("default")
                    expanded = false
                }
            )

            if (personalities.isNotEmpty()) {
                HorizontalDivider()

                personalities.filter { it != defaultName }.forEach { personality ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = personality.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = {
                            onSelect(personality)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
