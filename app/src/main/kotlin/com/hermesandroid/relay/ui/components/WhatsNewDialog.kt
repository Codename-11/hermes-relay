package com.hermesandroid.relay.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * "What's New" sheet, shown automatically on a version bump (RelayApp) and from
 * the About screen.
 *
 * As of the multi-version changelog work, the single source of truth is the
 * bundled [changelog.json] asset (see [ChangelogStore]). This dialog renders the
 * *latest* entry; the full version history lives in `ChangelogScreen`, which
 * reuses [VersionNotesBlock] for per-version rendering so the styling stays in
 * lockstep.
 *
 * For resilience the dialog still falls back to the legacy [whats_new.txt]
 * tiny-markup format (a version line, blank-separated sections with a plain-text
 * header, `*` bullets) when the JSON asset is missing or unparseable — that file
 * is also what `gradle-play-publisher`-adjacent tooling expects to find, so it's
 * kept current alongside the JSON.
 */
@Composable
fun WhatsNewDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // Prefer the structured changelog's latest entry; fall back to the legacy
    // text asset so a missing/garbled JSON never leaves the dialog empty.
    val notes = remember {
        ChangelogStore.loadLatestAsNotes(context)
            ?: parseWhatsNew(loadWhatsNew(context))
    }

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
                VersionNotesBody(notes.groups)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

// ──────────────────────────────────────────────────────────────────────────
// Shared rendering — used by both this dialog and ChangelogScreen so a tweak
// to bullet/header styling lands in one place.
// ──────────────────────────────────────────────────────────────────────────

/** One section: an optional header plus its bullets. */
data class WhatsNewGroup(val header: String?, val bullets: List<String>)

/** Parsed release notes for a single version: the version subtitle + sections. */
data class WhatsNewNotes(
    val version: String?,
    val groups: List<WhatsNewGroup>,
    val fallback: String? = null,
)

/**
 * Renders the body of one version's notes — its section headers and bullet
 * lists — without any surrounding chrome (no title, no scroll container). The
 * caller owns the [Column] so this can be dropped into a dialog or a screen.
 */
@Composable
fun ColumnScope.VersionNotesBody(groups: List<WhatsNewGroup>) {
    groups.forEachIndexed { index, group ->
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

/**
 * Self-contained version block — a version subtitle (version · title · date)
 * followed by [VersionNotesBody]. Used by ChangelogScreen for each release.
 */
@Composable
fun VersionNotesBlock(entry: ChangelogVersion) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = entry.subtitle(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        VersionNotesBody(entry.toGroups())
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Structured changelog model + loader (kotlinx.serialization).
// ──────────────────────────────────────────────────────────────────────────

/** One bullet group within a version: an optional header and its bullets. */
@Serializable
data class ChangelogSection(
    val header: String? = null,
    val bullets: List<String> = emptyList(),
)

/** A single released version's user-facing notes. */
@Serializable
data class ChangelogVersion(
    val version: String,
    val title: String? = null,
    val date: String? = null,
    val sections: List<ChangelogSection> = emptyList(),
) {
    /** "v1.2.0 — Make it yours · 2026-06-20" (each token optional but version). */
    fun subtitle(): String {
        val head = "v$version"
        val titlePart = title?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
        val datePart = date?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        return head + titlePart + datePart
    }

    fun toGroups(): List<WhatsNewGroup> =
        sections.map { WhatsNewGroup(it.header?.takeIf { h -> h.isNotBlank() }, it.bullets) }

    /** Adapt this version into the dialog's [WhatsNewNotes] shape. */
    fun toNotes(): WhatsNewNotes = WhatsNewNotes(
        version = subtitle(),
        groups = toGroups(),
    )
}

/** Top-level shape of [changelog.json]: newest version first. */
@Serializable
data class Changelog(
    @SerialName("versions") val versions: List<ChangelogVersion> = emptyList(),
)

/**
 * Parses + loads the bundled [changelog.json]. Parsing is a pure function
 * ([parse]) so it can be unit-tested off-device; only [load] touches the
 * Android asset stream.
 */
object ChangelogStore {

    /** Tolerant of upstream additions — unknown keys are ignored. */
    private val json = Json { ignoreUnknownKeys = true }

    const val ASSET_NAME: String = "changelog.json"

    /**
     * Parse raw changelog JSON into the model, preserving file order
     * (authored newest-first). Returns an empty [Changelog] on blank input or
     * any deserialization error — callers fall back to the legacy text asset.
     */
    fun parse(raw: String): Changelog {
        if (raw.isBlank()) return Changelog()
        return try {
            json.decodeFromString(Changelog.serializer(), raw)
        } catch (_: Exception) {
            Changelog()
        }
    }

    /** Read + parse the bundled asset (IO is cheap — a few KB, done once). */
    fun load(context: Context): Changelog {
        val raw = try {
            context.assets.open(ASSET_NAME).bufferedReader().readText()
        } catch (_: Exception) {
            return Changelog()
        }
        return parse(raw)
    }

    /**
     * The latest (first) entry rendered into the dialog's notes shape, or null
     * when the changelog can't be loaded so the caller can fall back to
     * [whats_new.txt].
     */
    fun loadLatestAsNotes(context: Context): WhatsNewNotes? =
        load(context).versions.firstOrNull()?.toNotes()
}

// ──────────────────────────────────────────────────────────────────────────
// Legacy whats_new.txt fallback parser (kept for resilience + Play tooling).
// ──────────────────────────────────────────────────────────────────────────

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
