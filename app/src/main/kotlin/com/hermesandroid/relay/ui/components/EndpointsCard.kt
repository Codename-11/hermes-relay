package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.data.isKnownRole
import com.hermesandroid.relay.network.RouteProbeOutcome
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

/**
 * ADR 24 — per-endpoint visibility + override card for the Connection
 * settings screen. Renders one row per [EndpointCandidate] stored for the
 * active device, with a health chip, a 3-dot menu (Prefer / Probe now /
 * View pin), and a bottom "Clear manual override" action when a preferred
 * role is set.
 *
 * Verbose by design — Bailey explicitly asked for per-row visibility, so we
 * do NOT collapse into a master row. The card itself is wrapped in a
 * [SettingsExpandableCard] by the caller for page layout hygiene.
 *
 * Not visible on legacy installs: when [endpoints] is empty we render a
 * helpful one-liner instead of an empty card, so freshly-upgraded users
 * who haven't re-paired yet understand why they can't see anything.
 */
@Composable
fun EndpointsCard(
    endpoints: List<EndpointCandidate>,
    activeEndpoint: EndpointCandidate?,
    preferredRole: String?,
    onPreferEndpoint: (EndpointCandidate) -> Unit,
    onClearOverride: () -> Unit,
    onProbeNow: () -> Unit,
    onViewPin: suspend (EndpointCandidate) -> String?,
    /** True while a user-triggered route probe is in flight. */
    isProbing: Boolean = false,
    /**
     * Last probe verdict for a route, or null when it has never been
     * probed. Lambda (not a map) so the card stays decoupled from the
     * resolver's cache-key scheme.
     */
    outcomeFor: (EndpointCandidate) -> RouteProbeOutcome? = { null },
    /**
     * Route management — the standard path's manual equivalent of a v3 QR's
     * `endpoints` array. Null callbacks hide the corresponding affordance.
     * Edit/Remove only appear on fallback rows (priority > 0); the primary
     * row mirrors the connection's API URL and is edited there.
     */
    onAddRoute: (() -> Unit)? = null,
    onEditRoute: ((EndpointCandidate) -> Unit)? = null,
    onRemoveRoute: ((EndpointCandidate) -> Unit)? = null,
) {
    if (endpoints.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "No route candidates stored for this connection yet. " +
                    "Add a remote route (Tailscale, public URL) for automatic " +
                    "switching when the phone leaves this network — or scan a " +
                    "v3 pairing QR (Hermes 0.4.2+) if you use Relay.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onAddRoute != null) {
                TextButton(onClick = onAddRoute) {
                    Text("Add route")
                }
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Current: ${activeEndpoint?.displayLabel() ?: "Resolving"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        endpoints.forEachIndexed { index, candidate ->
            if (index > 0) HorizontalDivider()
            EndpointRow(
                candidate = candidate,
                isActive = activeEndpoint != null &&
                    activeEndpoint.role.equals(candidate.role, ignoreCase = true) &&
                    activeEndpoint.api.host.equals(candidate.api.host, ignoreCase = true) &&
                    activeEndpoint.api.port == candidate.api.port,
                isPreferred = preferredRole?.equals(candidate.role, ignoreCase = true) == true,
                isProbing = isProbing,
                outcome = outcomeFor(candidate),
                onPrefer = { onPreferEndpoint(candidate) },
                onProbeNow = onProbeNow,
                onViewPin = onViewPin,
                onEdit = onEditRoute?.takeIf { candidate.priority > 0 }
                    ?.let { edit -> { edit(candidate) } },
                onRemove = onRemoveRoute?.takeIf { candidate.priority > 0 }
                    ?.let { remove -> { remove(candidate) } },
            )
        }

        if (onAddRoute != null) {
            HorizontalDivider()
            TextButton(onClick = onAddRoute, modifier = Modifier.fillMaxWidth()) {
                Text("Add route")
            }
        }

        if (preferredRole != null) {
            HorizontalDivider()
            TextButton(onClick = onClearOverride, modifier = Modifier.fillMaxWidth()) {
                Text("Clear manual override (preferring $preferredRole)")
            }
        }
    }
}

/**
 * One row: role chip + host:port + transport hint + health chip + 3-dot menu.
 */
@Composable
private fun EndpointRow(
    candidate: EndpointCandidate,
    isActive: Boolean,
    isPreferred: Boolean,
    isProbing: Boolean = false,
    outcome: RouteProbeOutcome? = null,
    onPrefer: () -> Unit,
    onProbeNow: () -> Unit,
    onViewPin: suspend (EndpointCandidate) -> String?,
    onEdit: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pinDialogText by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = roleIcon(candidate.role),
                contentDescription = null,
                tint = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = candidate.displayLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (isActive) {
                        ActiveChip()
                    } else if (isPreferred) {
                        PreferredChip()
                    } else {
                        FallbackChip()
                    }
                    if (!candidate.isKnownRole()) {
                        // Show the raw role for custom-VPN entries so users
                        // can tell "netbird-eu" from "wireguard-home" at a
                        // glance without poking into the menu.
                        Text(
                            text = "(${candidate.role})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                // Full URL, scheme included: http vs https decides whether
                // the health probe TLS-handshakes, so two rows that both
                // read "host:8642" can behave completely differently. The
                // scheme must be visible to be debuggable.
                Text(
                    text = candidate.api.url +
                        (candidate.relay.transportHint?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                when {
                    isProbing -> Text(
                        text = "Checking…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    outcome == null -> Unit // never probed — say nothing
                    outcome.reachable -> Text(
                        text = "Reachable",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    else -> Text(
                        text = "Unreachable — ${outcome.detail ?: "no detail"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // 3-dot overflow menu — actions per-row so the card stays flat
            // without needing to expand into a detail sheet. "View pin"
            // suspends to read CertPinStore, so we resolve it into a dialog
            // when the user taps it.
            if (!isActive) {
                TextButton(onClick = onPrefer) {
                    Text("Use now")
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Endpoint actions",
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Prefer this route") },
                        onClick = {
                            menuOpen = false
                            onPrefer()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Probe now") },
                        onClick = {
                            menuOpen = false
                            onProbeNow()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("View pin") },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                pinDialogText = onViewPin(candidate)
                                    ?: "No pin recorded yet — the phone will " +
                                        "record one on first TOFU connect."
                            }
                        },
                    )
                    if (onEdit != null) {
                        DropdownMenuItem(
                            text = { Text("Edit route") },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                    }
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text("Remove route") },
                            onClick = {
                                menuOpen = false
                                confirmRemove = true
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmRemove && onRemove != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${candidate.displayLabel()} route?") },
            text = {
                Text(
                    text = "${candidate.api.host}:${candidate.api.port} will no longer be " +
                        "probed as a fallback. You can add it back any time.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        onRemove()
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            },
        )
    }

    pinDialogText?.let { body ->
        AlertDialog(
            onDismissRequest = { pinDialogText = null },
            title = { Text("TOFU pin · ${candidate.api.host}") },
            text = {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            },
            confirmButton = {
                TextButton(onClick = { pinDialogText = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun ActiveChip() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(10.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Active",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PreferredChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFFA726).copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "Preferred",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB26A00),
        )
    }
}

/**
 * Outlined neutral chip rendered on non-active, non-preferred routes so
 * every row states its standing explicitly (mirror of [ActiveChip] /
 * [PreferredChip]). No background fill — just a 1dp border so it reads
 * as "available fallback" not "something is happening here".
 */
@Composable
private fun FallbackChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "Fallback",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Role → Material icon. Known roles get their canonical glyph; anything
 * else falls through to [Icons.Filled.Shield] (generic "Custom VPN").
 */
private fun roleIcon(role: String): ImageVector = when (role.lowercase()) {
    "lan" -> Icons.Filled.Lan
    "tailscale" -> Icons.Filled.VpnKey
    "public" -> Icons.Filled.Public
    else -> Icons.Filled.Shield
}

/**
 * Add/edit dialog for an extra fallback route — the manual counterpart of a
 * v3 pairing QR's `endpoints` array, so standard (no-Relay) connections can
 * set up LAN ↔ Tailscale roaming without the plugin.
 *
 * The relay URL is derived from the API URL (same `:8767` convention the
 * wizard uses); routes that need a custom relay URL still come from a QR.
 *
 * @param original null = add a new route; non-null = edit (pre-fills role +
 *   URL, keeps the stored priority).
 * @param onSave invoked with (role, apiUrl, resultCallback); the callback
 *   receives a user-facing error string to render inline, or null on
 *   success (the dialog then closes itself).
 */
@Composable
fun RouteEditorDialog(
    original: EndpointCandidate?,
    onSave: (role: String, apiUrl: String, onResult: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val knownRoles = listOf("tailscale", "public")
    var selectedRole by remember {
        mutableStateOf(
            when (original?.role?.lowercase()) {
                null -> "tailscale"
                in knownRoles -> original.role.lowercase()
                else -> CUSTOM_ROLE
            },
        )
    }
    var customRole by remember {
        mutableStateOf(
            original?.role?.takeIf { it.lowercase() !in knownRoles }.orEmpty(),
        )
    }
    var url by remember { mutableStateOf(original?.api?.url.orEmpty()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val effectiveRole = if (selectedRole == CUSTOM_ROLE) customRole else selectedRole
    val saveEnabled = !saving &&
        url.isNotBlank() &&
        (selectedRole != CUSTOM_ROLE || customRole.isNotBlank())

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (original == null) "Add route" else "Edit route") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "A fallback route the phone switches to when the " +
                        "primary stops answering — e.g. your server's " +
                        "Tailscale or public URL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedRole == "tailscale",
                        onClick = { selectedRole = "tailscale" },
                        label = { Text("Tailscale") },
                    )
                    FilterChip(
                        selected = selectedRole == "public",
                        onClick = { selectedRole = "public" },
                        label = { Text("Public") },
                    )
                    FilterChip(
                        selected = selectedRole == CUSTOM_ROLE,
                        onClick = { selectedRole = CUSTOM_ROLE },
                        label = { Text("Custom") },
                    )
                }
                if (selectedRole == CUSTOM_ROLE) {
                    OutlinedTextField(
                        value = customRole,
                        onValueChange = { customRole = it },
                        label = { Text("Route name") },
                        placeholder = { Text("wireguard-home") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Live preview of what will actually be saved — scheme and
                // port defaults applied — so "what, which port, http or
                // https?" is answered before Save, not after a failed probe.
                val previewCandidate = remember(url, effectiveRole) {
                    url.takeIf { it.isNotBlank() }?.let {
                        Connection.endpointCandidateFromApiUrl(
                            role = effectiveRole.ifBlank { "custom" },
                            priority = original?.priority ?: 1,
                            apiServerUrl = Connection.normalizeApiUrlInput(it),
                            relayUrl = "",
                        )
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        errorText = null
                    },
                    label = { Text("API server URL or host") },
                    placeholder = { Text("100.71.8.56 or http://host:8642") },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = {
                        Text(
                            text = errorText ?: when {
                                url.isBlank() ->
                                    "Use the API server port (8642 by default) — " +
                                        "not the dashboard's 9119. http:// is " +
                                        "assumed unless you type https://."
                                previewCandidate != null ->
                                    "Will save: ${previewCandidate.api.url} — relay " +
                                        "and dashboard URLs are derived from the host"
                                else ->
                                    "Enter a host/IP or an http(s):// URL " +
                                        "(API port 8642, not dashboard 9119)"
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = saveEnabled,
                onClick = {
                    saving = true
                    onSave(effectiveRole, url) { error ->
                        saving = false
                        if (error == null) {
                            onDismiss()
                        } else {
                            errorText = error
                        }
                    }
                },
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
    )
}

private const val CUSTOM_ROLE = "__custom__"
