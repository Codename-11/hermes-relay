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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.SurfaceSecurityKind
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.data.isEncryptedOverlayRoute
import com.hermesandroid.relay.data.isKnownRole
import com.hermesandroid.relay.data.isTlsUrl
import com.hermesandroid.relay.data.primaryRouteUrl
import com.hermesandroid.relay.data.routeAuthority
import com.hermesandroid.relay.network.shared.RouteProbeOutcome
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

/**
 * ADR 24 — per-endpoint visibility + override card for the Connection
 * settings screen. Renders one row per [EndpointCandidate] stored for the
 * active device, with a health chip, a 3-dot menu (Prefer / Probe now /
 * View pin), and bottom clear actions when a switch/preference is set.
 *
 * Two distinct route actions, deliberately separated:
 * - "Use now" (row button) — one-time switch. Holds until the next
 *   disconnect, never persisted, cancelled by [onCancelUseNow].
 * - "Prefer this route" (3-dot menu) — sticky policy. Persisted, restored
 *   on app start, tried first on every resolve; cleared by
 *   [onClearPreferred].
 *
 * Verbose by design: per-route visibility is retained instead of collapsing
 * into a master row. The card itself is wrapped in a
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
    /** Persisted sticky preference ([Connection.preferredRouteRole]). */
    preferredRole: String?,
    /**
     * Live transient override installed by "Use now" (or by preference
     * restoration — equal to [preferredRole] in that case). Drives the
     * automatic / preferred / manual annotation on the Current line.
     */
    manualOverrideRole: String?,
    onUseNow: (EndpointCandidate) -> Unit,
    onCancelUseNow: () -> Unit,
    onPreferEndpoint: (EndpointCandidate) -> Unit,
    onClearPreferred: () -> Unit,
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
    // Pre-resolve strings
    val noRoutesStoredText = stringResource(R.string.endpoints_no_routes_stored)
    val addRouteText = stringResource(R.string.endpoints_add_route)
    val resolvingText = stringResource(R.string.endpoints_resolving)
    val manualUntilDisconnectText = stringResource(R.string.endpoints_manual_until_disconnect)
    val preferredText = stringResource(R.string.endpoints_preferred)
    val automaticText = stringResource(R.string.endpoints_automatic)
    val currentRouteText = stringResource(R.string.endpoints_current_route)

    if (endpoints.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = noRoutesStoredText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onAddRoute != null) {
                TextButton(onClick = onAddRoute) {
                    Text(addRouteText)
                }
            }
        }
        return
    }

    // A manual "Use now" switch is in effect when the live override differs
    // from the sticky preference (preference restoration writes the same
    // role into both, so equality means "preferred", not "manual").
    val manualSwitchActive = manualOverrideRole != null &&
        !manualOverrideRole.equals(preferredRole, ignoreCase = true)

    // Pre-resolve cancel manual switch string (needs preferredRole which may be null)
    val cancelManualSwitchText = stringResource(R.string.endpoints_cancel_manual_switch)
    val stopPreferringText = stringResource(R.string.endpoints_stop_preferring)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = currentRouteText.format(
                activeEndpoint?.displayLabel() ?: resolvingText,
                when {
                    manualSwitchActive -> manualUntilDisconnectText
                    manualOverrideRole != null -> preferredText
                    else -> automaticText
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        endpoints.forEachIndexed { index, candidate ->
            if (index > 0) HorizontalDivider()
            EndpointRow(
                candidate = candidate,
                isActive = activeEndpoint != null &&
                    activeEndpoint.role.equals(candidate.role, ignoreCase = true) &&
                    activeEndpoint.routeAuthority() == candidate.routeAuthority(),
                isPreferred = preferredRole?.equals(candidate.role, ignoreCase = true) == true,
                isProbing = isProbing,
                outcome = outcomeFor(candidate),
                onUseNow = { onUseNow(candidate) },
                onPrefer = { onPreferEndpoint(candidate) },
                onClearPrefer = onClearPreferred,
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
                Text(addRouteText)
            }
        }

        if (manualSwitchActive) {
            HorizontalDivider()
            TextButton(onClick = onCancelUseNow, modifier = Modifier.fillMaxWidth()) {
                Text(cancelManualSwitchText.format(preferredRole ?: automaticText))
            }
        }
        if (preferredRole != null) {
            HorizontalDivider()
            TextButton(onClick = onClearPreferred, modifier = Modifier.fillMaxWidth()) {
                Text(stopPreferringText.format(preferredRole))
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
    onUseNow: () -> Unit,
    onPrefer: () -> Unit,
    onClearPrefer: () -> Unit,
    onProbeNow: () -> Unit,
    onViewPin: suspend (EndpointCandidate) -> String?,
    onEdit: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pinDialogText by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val noPinRecordedText = stringResource(R.string.endpoints_no_pin_recorded)

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
                    SurfaceSecurityGlyph(kind = candidate.routeSecurityKind())
                    if (isActive) {
                        ActiveChip(stringResource(R.string.endpoints_active))
                    } else if (isPreferred) {
                        PreferredChip(stringResource(R.string.endpoints_preferred_chip))
                    } else {
                        FallbackChip(stringResource(R.string.endpoints_fallback))
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
                    text = candidate.primaryRouteUrl().orEmpty() +
                        (candidate.relay?.transportHint?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                when {
                    isProbing -> Text(
                        text = stringResource(R.string.endpoints_checking),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    outcome == null -> Unit // never probed — say nothing
                    outcome.reachable -> Text(
                        text = stringResource(R.string.endpoints_reachable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    else -> Text(
                        text = stringResource(R.string.endpoints_unreachable, outcome.detail ?: stringResource(R.string.endpoints_unreachable_no_detail)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // 3-dot overflow menu — actions per-row so the card stays flat
            // without needing to expand into a detail sheet. "View pin"
            // suspends to read CertPinStore, so we resolve it into a dialog
            // when the user taps it.
            //
            // "Use now" is the TRANSIENT switch (until disconnect); the
            // sticky "Prefer this route" lives in the menu below.
            if (!isActive) {
                TextButton(onClick = onUseNow) {
                    Text(stringResource(R.string.endpoints_use_now))
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.endpoints_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = if (isPreferred) stringResource(R.string.endpoints_stop_preferring_menu) else stringResource(R.string.endpoints_prefer_this_route),
                                )
                                Text(
                                    text = if (isPreferred) {
                                        stringResource(R.string.endpoints_back_to_automatic)
                                    } else {
                                        stringResource(R.string.endpoints_always_try_first)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            menuOpen = false
                            if (isPreferred) onClearPrefer() else onPrefer()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.endpoints_probe_now)) },
                        onClick = {
                            menuOpen = false
                            onProbeNow()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.endpoints_view_pin)) },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                pinDialogText = onViewPin(candidate)
                                    ?: noPinRecordedText
                            }
                        },
                    )
                    if (onEdit != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.endpoints_edit_route)) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                    }
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.endpoints_remove_route)) },
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
            title = { Text(stringResource(R.string.endpoints_remove_route_title, candidate.displayLabel())) },
            text = {
                Text(
                    text = stringResource(R.string.endpoints_remove_route_body, candidate.routeAuthority().orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        onRemove()
                    },
                ) { Text(stringResource(R.string.endpoints_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.endpoints_cancel)) }
            },
        )
    }

    pinDialogText?.let { body ->
        AlertDialog(
            onDismissRequest = { pinDialogText = null },
            title = { Text(stringResource(R.string.endpoints_pin_title, candidate.routeAuthority().orEmpty())) },
            text = {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            },
            confirmButton = {
                TextButton(onClick = { pinDialogText = null }) { Text(stringResource(R.string.endpoints_close)) }
            },
        )
    }
}

@Composable
private fun ActiveChip(label: String) {
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
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PreferredChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFFA726).copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
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
private fun FallbackChip(label: String) {
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
            text = label,
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
 * Per-route security classification for the picker glyph. Keyed on the
 * candidate's own scheme + role (no device-level Tailscale detection needed —
 * a `tailscale`/`plugin_proxy` role is encrypted regardless), so each row can
 * be classified independently before it's the active route.
 */
private fun EndpointCandidate.routeSecurityKind(): SurfaceSecurityKind = when {
    isTlsUrl(primaryRouteUrl().orEmpty()) -> SurfaceSecurityKind.Tls
    isEncryptedOverlayRoute(isTailscaleDetected = false) -> SurfaceSecurityKind.Overlay
    else -> SurfaceSecurityKind.Plain
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
    val uriHandler = LocalUriHandler.current
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
    var url by remember(original) { mutableStateOf(original?.primaryRouteUrl().orEmpty()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val effectiveRole = if (selectedRole == CUSTOM_ROLE) customRole else selectedRole
    val saveEnabled = !saving &&
        url.isNotBlank() &&
        (selectedRole != CUSTOM_ROLE || customRole.isNotBlank())

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(
                if (original == null) stringResource(R.string.endpoints_add_route_title)
                else stringResource(R.string.endpoints_edit_route_title)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.endpoints_route_editor_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedRole == "tailscale",
                        onClick = { selectedRole = "tailscale" },
                        label = { Text(stringResource(R.string.endpoints_tailscale)) },
                    )
                    FilterChip(
                        selected = selectedRole == "public",
                        onClick = { selectedRole = "public" },
                        label = { Text(stringResource(R.string.endpoints_public)) },
                    )
                    FilterChip(
                        selected = selectedRole == CUSTOM_ROLE,
                        onClick = { selectedRole = CUSTOM_ROLE },
                        label = { Text(stringResource(R.string.endpoints_custom)) },
                    )
                }
                if (selectedRole == CUSTOM_ROLE) {
                    OutlinedTextField(
                        value = customRole,
                        onValueChange = { customRole = it },
                        label = { Text(stringResource(R.string.endpoints_route_name)) },
                        placeholder = { Text(stringResource(R.string.endpoints_route_name_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Live preview of what will actually be saved — scheme and
                // port defaults applied — so "what, which port, http or
                // https?" is answered before Save, not after a failed probe.
                val previewCandidate = remember(url, effectiveRole) {
                    url.takeIf { it.isNotBlank() }?.let {
                        Connection.endpointCandidateFromDashboardUrl(
                            role = effectiveRole.ifBlank { "custom" },
                            priority = original?.priority ?: 1,
                            dashboardUrl = Connection.normalizeDashboardUrlInput(it),
                        )
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        errorText = null
                    },
                    label = { Text(stringResource(R.string.endpoints_api_url_host)) },
                    placeholder = { Text(stringResource(R.string.endpoints_url_host_placeholder)) },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = {
                        val supportingBlank = stringResource(R.string.endpoints_url_supporting_blank)
                        val supportingEnter = stringResource(R.string.endpoints_url_supporting_enter)
                        Text(
                            text = errorText ?: when {
                                url.isBlank() ->
                                    supportingBlank
                                previewCandidate != null ->
                                    stringResource(R.string.endpoints_url_supporting_preview, previewCandidate.primaryRouteUrl().orEmpty())
                                else ->
                                    supportingEnter
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (selectedRole == "tailscale") {
                    Text(
                        text = stringResource(R.string.endpoints_tailscale_setup_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { uriHandler.openUri(REMOTE_ACCESS_DOCS_URL) },
                    ) {
                        Text(stringResource(R.string.endpoints_setup_help))
                    }
                }
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
            ) {
                Text(
                    if (saving) stringResource(R.string.endpoints_saving)
                    else stringResource(R.string.endpoints_save)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.endpoints_cancel))
            }
        },
    )
}

private const val REMOTE_ACCESS_DOCS_URL =
    "https://hermes-relay.dev/docs/guide/remote-access"

private const val CUSTOM_ROLE = "__custom__"
