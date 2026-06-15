package com.hermesandroid.relay.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "What's New" sheet, shown automatically on a version bump (RelayApp) and from
 * the About screen. Parses [whats_new.txt]'s tiny markup — a version line,
 * blank-separated sections with a plain-text header, `*` bullets with indented
 * continuation lines — into styled Compose instead of pasting the raw text
 * (which showed literal `*` and gave headers no emphasis).
 */
@Composable
fun WhatsNewDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val notes = remember { parseWhatsNew(loadWhatsNew(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("What's New")
                notes.version?.let { version ->
                    Text(
                        text = version,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (notes.groups.isEmpty()) {
                    Text(
                        text = notes.fallback ?: "No release notes available.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                notes.groups.forEachIndexed { index, group ->
                    group.header?.let { header ->
                        Text(
                            text = header,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = if (index == 0) 0.dp else 6.dp),
                        )
                    }
                    group.bullets.forEach { bullet ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = bullet,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

/** One section: an optional header plus its bullets. */
private data class WhatsNewGroup(val header: String?, val bullets: List<String>)

/** Parsed release notes: the leading version line plus styled sections. */
private data class WhatsNewNotes(
    val version: String?,
    val groups: List<WhatsNewGroup>,
    val fallback: String?,
)

/**
 * Classify each line of the [whats_new.txt] format:
 *  - line 0 (non-bullet) → version subtitle (`-` upgraded to an em dash),
 *  - blank line → ends the current bullet,
 *  - `* ` prefix → a new bullet,
 *  - leading whitespace → continuation appended to the current bullet,
 *  - anything else → a section header.
 */
private fun parseWhatsNew(raw: String): WhatsNewNotes {
    if (raw.isBlank()) return WhatsNewNotes(null, emptyList(), raw.ifBlank { null })

    val lines = raw.lines()
    var version: String? = null
    val groups = mutableListOf<WhatsNewGroup>()
    var currentHeader: String? = null
    val currentBullets = mutableListOf<String>()
    val pending = StringBuilder()

    fun flushBullet() {
        if (pending.isNotEmpty()) {
            currentBullets += pending.toString().trim()
            pending.clear()
        }
    }

    fun flushGroup() {
        flushBullet()
        if (currentHeader != null || currentBullets.isNotEmpty()) {
            groups += WhatsNewGroup(currentHeader, currentBullets.toList())
        }
        currentHeader = null
        currentBullets.clear()
    }

    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        when {
            index == 0 && trimmed.isNotEmpty() && !trimmed.startsWith("*") ->
                version = trimmed.replace(" - ", " — ")
            trimmed.isEmpty() -> flushBullet()
            trimmed.startsWith("* ") -> {
                flushBullet()
                pending.append(trimmed.removePrefix("* "))
            }
            line.startsWith(" ") || line.startsWith("\t") -> {
                if (pending.isNotEmpty()) pending.append(' ')
                pending.append(trimmed)
            }
            else -> {
                flushGroup()
                currentHeader = trimmed
            }
        }
    }
    flushGroup()
    return WhatsNewNotes(version, groups, fallback = null)
}

private fun loadWhatsNew(context: Context): String {
    return try {
        context.assets.open("whats_new.txt").bufferedReader().readText()
    } catch (_: Exception) {
        "No release notes available."
    }
}
