package com.hermesandroid.relay.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Expanded "What's New" sheet, opened from the non-blocking post-update toast
 * or directly from the About screen.
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
    onDismiss: () -> Unit,
    onViewHistory: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    // Prefer the structured changelog's latest entry; fall back to the legacy
    // text asset so a missing/garbled JSON never leaves the dialog empty.
    val latest = remember { ChangelogStore.load(context).versions.firstOrNull() }
    val notes = remember(latest) {
        latest?.toNotes() ?: parseWhatsNew(loadWhatsNew(context))
    }

    fun leave(afterExit: () -> Unit) {
        if (exiting) return
        exiting = true
        scope.launch {
            visible = false
            delay(180)
            afterExit()
        }
    }

    LaunchedEffect(Unit) {
        if (!exiting) visible = true
    }

    Dialog(
        onDismissRequest = { leave(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.92f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.96f),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 32.dp)
                    .widthIn(max = 560.dp)
                    .heightIn(max = 680.dp),
                shape = appearanceRoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.whats_new_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.whats_new_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { leave(onDismiss) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.changelog_close),
                        )
                    }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = appearanceRoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (latest != null) {
                                VersionNotesBlock(latest)
                            } else {
                                notes.version?.let { version ->
                                    Text(
                                        text = version,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (notes.groups.isEmpty()) {
                                    Text(
                                        text = notes.fallback ?: stringResource(R.string.whats_new_no_notes),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                VersionNotesBody(notes.groups)
                            }
                        }
                    }
                }
                    WhatsNewActions(
                        onDismiss = { leave(onDismiss) },
                        onViewHistory = onViewHistory?.let { action -> { leave(action) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun WhatsNewActions(
    onDismiss: () -> Unit,
    onViewHistory: (() -> Unit)?,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        val stackActions = LocalDensity.current.fontScale >= 1.3f || maxWidth < 320.dp
        if (stackActions) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                onViewHistory?.let { action ->
                    OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.whats_new_full_history))
                    }
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.whats_new_got_it))
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onViewHistory?.let { action ->
                    OutlinedButton(onClick = action, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.whats_new_full_history))
                    }
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.whats_new_got_it))
                }
            }
        }
    }
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
fun VersionNotesBlock(
    entry: ChangelogVersion,
    showVersionLine: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val highlight = entry.highlight
        if (highlight == null) {
            if (showVersionLine) {
                Text(
                    text = entry.subtitle(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            VersionNotesBody(entry.toGroups())
        } else {
            if (showVersionLine) {
                Text(
                    text = entry.versionLine(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = highlight.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            highlight.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            VersionNotesBody(listOf(WhatsNewGroup(header = null, bullets = highlight.bullets)))
            if (entry.improvements.isNotEmpty()) {
                VersionNotesBody(
                    listOf(
                        WhatsNewGroup(
                            header = stringResource(R.string.changelog_also_improved),
                            bullets = entry.improvements,
                        ),
                    ),
                )
            }
        }
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

/** Editorially selected reason to care about a release. */
@Serializable
data class ChangelogHighlight(
    val title: String,
    val summary: String? = null,
    val bullets: List<String> = emptyList(),
)

/** Compact preview of noteworthy items beyond the primary highlight. */
@Serializable
data class ChangelogToastDigest(
    val additionalFeatureCount: Int = 0,
    val fixCount: Int = 0,
    val preview: List<String> = emptyList(),
)

/** A single released version's user-facing notes. */
@Serializable
data class ChangelogVersion(
    val version: String,
    val title: String? = null,
    val date: String? = null,
    val highlight: ChangelogHighlight? = null,
    val improvements: List<String> = emptyList(),
    val toastDigest: ChangelogToastDigest? = null,
    val playNotes: String? = null,
    val sections: List<ChangelogSection> = emptyList(),
) {
    /** "v1.2.0 — Make it yours · 2026-06-20" (each token optional but version). */
    fun subtitle(): String {
        val head = "v$version"
        val titlePart = title?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
        val datePart = date?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        return head + titlePart + datePart
    }

    /** Compact metadata line used when a curated highlight owns the headline. */
    fun versionLine(): String {
        val datePart = date?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        return "v$version$datePart"
    }

    fun toGroups(): List<WhatsNewGroup> =
        highlight?.let { curated ->
            buildList {
                add(WhatsNewGroup(curated.title.takeIf { it.isNotBlank() }, curated.bullets))
                if (improvements.isNotEmpty()) {
                    add(WhatsNewGroup("Also improved", improvements))
                }
            }
        } ?: sections.map { WhatsNewGroup(it.header?.takeIf { h -> h.isNotBlank() }, it.bullets) }

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
        context.getString(R.string.whats_new_no_notes)
    }
}
