package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import com.hermesandroid.relay.BuildConfig
import com.hermesandroid.relay.R
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.components.ChangelogStore
import com.hermesandroid.relay.ui.components.ChangelogVersion
import com.hermesandroid.relay.ui.components.VersionNotesBlock
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Full release history, sourced from the bundled `changelog.json` (the same
 * single source the auto post-update [com.hermesandroid.relay.ui.components.WhatsNewDialog]
 * renders the latest entry from).
 *
 * The newest version is expanded by default; every older version is a
 * collapsible card. Per-version rendering reuses [VersionNotesBody] so the
 * header/bullet styling matches the auto dialog exactly.
 *
 * This is a self-contained screen meant to be hosted inside a full-screen
 * `Dialog` from Settings — it owns its own [Scaffold] + close affordance and
 * has no nav dependency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val versions = remember { ChangelogStore.load(context).versions }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.changelog_title)) },
                actions = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.changelog_close),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        if (versions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.changelog_no_notes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                items = versions,
                key = { _, entry -> entry.version },
            ) { index, entry ->
                // Latest version is expanded; older ones start collapsed.
                ChangelogVersionCard(
                    entry = entry,
                    initiallyExpanded = index == 0,
                    installed = isInstalledRelease(
                        releaseVersion = entry.version,
                        buildVersion = BuildConfig.VERSION_NAME,
                        flavor = BuildConfig.FLAVOR,
                    ),
                )
            }
        }
    }
}

/**
 * One version's collapsible card. The header row (version · title · date) is
 * always visible and toggles the body; the body reuses [VersionNotesBody].
 */
@Composable
private fun ChangelogVersionCard(
    entry: ChangelogVersion,
    initiallyExpanded: Boolean,
    installed: Boolean,
) {
    var expanded by remember(entry.version) { mutableStateOf(initiallyExpanded) }
    val expansionState = stringResource(
        if (expanded) R.string.changelog_state_expanded else R.string.changelog_state_collapsed,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = appearanceRoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = expansionState }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "v${entry.version}" +
                            (entry.title?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        entry.date?.takeIf { it.isNotBlank() }?.let { date ->
                            Text(
                                text = formatReleaseDate(date),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (installed) {
                            InstalledVersionBadge()
                        }
                    }
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.ExpandLess
                    } else {
                        Icons.Filled.ExpandMore
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    VersionNotesBlock(entry, showVersionLine = false)
                }
            }
        }
    }
}

@Composable
private fun InstalledVersionBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        shape = appearanceRoundedCornerShape(10.dp),
    ) {
        Text(
            text = stringResource(R.string.changelog_installed),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

internal fun formatReleaseDate(raw: String, locale: Locale = Locale.getDefault()): String =
    runCatching {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(LocalDate.parse(raw))
    }.getOrDefault(raw)

internal fun isInstalledRelease(
    releaseVersion: String,
    buildVersion: String,
    flavor: String,
): Boolean {
    val canonicalBuildVersion = flavor
        .takeIf { it.isNotBlank() }
        ?.let { buildVersion.removeSuffix("-$it") }
        ?: buildVersion
    return releaseVersion == canonicalBuildVersion
}
