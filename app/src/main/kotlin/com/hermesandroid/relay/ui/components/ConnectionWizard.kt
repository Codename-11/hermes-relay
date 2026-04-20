package com.hermesandroid.relay.ui.components

import android.Manifest
import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ClipEntry
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Shared three-step pairing wizard used by both onboarding (first run) and
 * Settings → Connection (re-pair / change server). One canonical pair flow
 * exposing every supported pairing method, so first-run and re-pair stay in
 * lockstep.
 *
 * Steps:
 *
 *  1. **Method** — pick how to pair. Three tiles:
 *     - **Scan QR**: opens camera + scanner. On success → Confirm.
 *     - **Enter code**: server already minted a code via `hermes-pair
 *       --register-code` or `/hermes-relay-pair`. → ManualEntry.
 *     - **Show code** (relay-gated): phone displays a generated 6-char
 *       code + the host command to run. → ShowCode.
 *  2. **Path-specific middle step**:
 *     - QR path → **Confirm**: shows what was scanned, transport security
 *       badge, TTL picker, insecure note when the relay is plain `ws://`.
 *       Tap Pair to apply the full payload (URLs, code, grants, cert pin).
 *     - Enter code path → **ManualEntry**: API URL + Relay URL + code
 *       fields. Tap Pair to persist the URLs and connect with the typed
 *       code as the server-issued code.
 *     - Show code path → **ShowCode**: API URL + Relay URL fields, the
 *       phone-generated code (with copy + regen), the
 *       `hermes-pair --register-code <code>` command (with copy), and a
 *       Connect button to fire the pair once the operator has registered
 *       the code on the host.
 *  3. **Verify** — runs the pair, observes [AuthState], surfaces errors
 *     with a Retry affordance. On success, calls [onComplete].
 *
 * The wizard is intentionally Scaffold-less so callers can embed it inside
 * their own surface (the onboarding pager, a settings full-screen route,
 * a dialog, etc.) without fighting nested top-app-bars.
 *
 * @param connectionViewModel shared VM that owns the apply-payload helpers
 * @param onComplete called after a successful pair lands; the caller is
 *   responsible for navigating away (e.g. completeOnboarding + nav to chat)
 * @param onCancel called when the user backs out before the verify step
 *   resolves. Caller decides whether that means "stay in Settings" or
 *   "skip onboarding and go to chat anyway"
 * @param showSkip when true, surfaces a "Skip for now" affordance on the
 *   first step. Onboarding sets this to true so users can defer setup;
 *   Settings sets it to false because there's nothing to skip to.
 */
@Composable
fun ConnectionWizard(
    connectionViewModel: ConnectionViewModel,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    showSkip: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val isTailscaleDetected by connectionViewModel.isTailscaleDetected.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()
    val relayEnabled by FeatureFlags.relayEnabled(context)
        .collectAsState(initial = FeatureFlags.isDevBuild)
    val pairingCode by connectionViewModel.pairingCode.collectAsState()
    val currentApiUrl by connectionViewModel.apiServerUrl.collectAsState()
    val currentRelayUrl by connectionViewModel.relayUrl.collectAsState()

    var step by remember { mutableStateOf(WizardStep.Method) }
    var chosenMethod by remember { mutableStateOf(PairMethod.Scan) }
    var pendingPayload by remember { mutableStateOf<HermesPairingPayload?>(null) }
    var ttlSeconds by remember { mutableStateOf(PairingPreferencesDefault) }
    var showQrScanner by remember { mutableStateOf(false) }
    var verifyError by remember { mutableStateOf<String?>(null) }
    var verifyAttempt by remember { mutableStateOf(0) }

    // Manual-path field state. Pre-fill from whatever the VM already knows
    // so re-pair from Settings keeps the previously-configured URLs.
    var manualApiUrl by remember(currentApiUrl) { mutableStateOf(currentApiUrl) }
    var manualRelayUrl by remember(currentRelayUrl) { mutableStateOf(currentRelayUrl) }
    var manualCode by remember { mutableStateOf("") }

    // Camera permission gate. We don't keep the launcher result around — the
    // showQrScanner flag is the persistent state.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showQrScanner = true
        } else {
            Toast.makeText(
                context,
                "Camera permission needed to scan QR codes",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Watch the auth state once verify starts. Resolves Paired → onComplete,
    // Failed → surface error + let user retry, timeout → same. Cancelled
    // automatically when verifyAttempt changes (re-tries are a fresh attempt).
    //
    // CRITICAL: snapshot the current authState at the start of the attempt
    // and require a TRANSITION away from it before accepting Paired/Failed.
    // Without this, a stale `AuthState.Paired(token)` left in the keystore
    // from a previous install (or pair) races the new pair attempt and the
    // first {} predicate matches the stale value immediately — onComplete()
    // fires before the new WSS handshake has even started, the wizard
    // navigates to chat, and the user lands "in app" with only the API
    // configured. Snapshot+transition closes the race even when the
    // synchronous authState reset in applyPairingPayload didn't run (e.g.
    // QRs without a relay block, or relay blocks with empty code).
    LaunchedEffect(verifyAttempt) {
        if (verifyAttempt == 0) return@LaunchedEffect
        verifyError = null
        val snapshot = connectionViewModel.authState.value
        android.util.Log.i(
            "ConnectionWizard",
            "verify[$verifyAttempt] snapshot=${snapshot::class.simpleName} — waiting for transition to Paired|Failed"
        )
        try {
            val terminal = withTimeout(15_000) {
                connectionViewModel.authState.first { current ->
                    val match = current != snapshot &&
                        (current is AuthState.Paired || current is AuthState.Failed)
                    android.util.Log.d(
                        "ConnectionWizard",
                        "verify[$verifyAttempt] emission=${current::class.simpleName} " +
                            "differs=${current != snapshot} match=$match"
                    )
                    match
                }
            }
            when (terminal) {
                is AuthState.Paired -> {
                    android.util.Log.i(
                        "ConnectionWizard",
                        "verify[$verifyAttempt] terminal=Paired → onComplete()"
                    )
                    onComplete()
                }
                is AuthState.Failed -> {
                    android.util.Log.w(
                        "ConnectionWizard",
                        "verify[$verifyAttempt] terminal=Failed reason=${terminal.reason}"
                    )
                    verifyError = terminal.reason
                }
                else -> verifyError = "Pairing did not complete"
            }
        } catch (_: TimeoutCancellationException) {
            android.util.Log.w(
                "ConnectionWizard",
                "verify[$verifyAttempt] TIMEOUT after 15s (current=${connectionViewModel.authState.value::class.simpleName})"
            )
            verifyError = "Timed out waiting for the relay. " +
                "Check that the relay is running and the URL is correct."
        }
    }

    // Shared launcher for the manual paths — persists URLs, applies the
    // server-issued code, drops any stale session, and reconnects. Used by
    // both ManualEntry (typed code) and ShowCode (phone-generated code).
    val launchManualPair: (String) -> Unit = { code ->
        connectionViewModel.updateApiServerUrl(manualApiUrl.trim())
        connectionViewModel.updateRelayUrl(manualRelayUrl.trim())
        connectionViewModel.authManager
            .applyServerIssuedCodeAndReset(code.trim().uppercase())
        connectionViewModel.disconnectRelay()
        connectionViewModel.connectRelay(manualRelayUrl.trim())
        step = WizardStep.Verify
        verifyAttempt += 1
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WizardStepIndicator(currentStep = step.indicatorIndex, method = chosenMethod)

        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "wizard-step",
        ) { current ->
            when (current) {
                WizardStep.Method -> MethodStep(
                    relayEnabled = relayEnabled,
                    onPickScan = {
                        chosenMethod = PairMethod.Scan
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onPickEnterCode = {
                        chosenMethod = PairMethod.EnterCode
                        manualCode = ""
                        step = WizardStep.ManualEntry
                    },
                    onPickShowCode = {
                        chosenMethod = PairMethod.ShowCode
                        step = WizardStep.ShowCode
                    },
                    onSkip = if (showSkip) onCancel else null,
                )

                WizardStep.Confirm -> {
                    val payload = pendingPayload
                    if (payload == null) {
                        // Defensive — shouldn't happen because we only enter
                        // Confirm after a successful scan, but if it does,
                        // bounce back to the chooser instead of crashing.
                        LaunchedEffect(Unit) { step = WizardStep.Method }
                    } else {
                        ConfirmStep(
                            payload = payload,
                            ttlSeconds = ttlSeconds,
                            onTtlChange = { ttlSeconds = it },
                            isTailscaleDetected = isTailscaleDetected,
                            onBack = {
                                pendingPayload = null
                                step = WizardStep.Method
                            },
                            onConfirm = { reorderedPayload ->
                                // Persist the reordered payload so a retry
                                // from VerifyStep reuses the chosen preferred
                                // role — otherwise Retry would drop the user's
                                // "Prefer" choice on every failure.
                                pendingPayload = reorderedPayload
                                connectionViewModel.applyPairingPayload(
                                    reorderedPayload,
                                    ttlSeconds,
                                )
                                step = WizardStep.Verify
                                verifyAttempt += 1
                            },
                        )
                    }
                }

                WizardStep.ManualEntry -> ManualEntryStep(
                    apiUrl = manualApiUrl,
                    onApiUrlChange = { manualApiUrl = it },
                    relayUrl = manualRelayUrl,
                    onRelayUrlChange = { manualRelayUrl = it },
                    code = manualCode,
                    onCodeChange = { manualCode = it.uppercase() },
                    onBack = { step = WizardStep.Method },
                    onSubmit = { launchManualPair(manualCode) },
                )

                WizardStep.ShowCode -> ShowCodeStep(
                    apiUrl = manualApiUrl,
                    onApiUrlChange = { manualApiUrl = it },
                    relayUrl = manualRelayUrl,
                    onRelayUrlChange = { manualRelayUrl = it },
                    pairingCode = pairingCode,
                    onRegenerate = { connectionViewModel.regeneratePairingCode() },
                    onBack = { step = WizardStep.Method },
                    onConnect = { launchManualPair(pairingCode) },
                )

                WizardStep.Verify -> VerifyStep(
                    authState = authState,
                    error = verifyError,
                    onRetry = {
                        verifyError = null
                        when (chosenMethod) {
                            PairMethod.Scan -> pendingPayload?.let {
                                connectionViewModel.applyPairingPayload(it, ttlSeconds)
                                verifyAttempt += 1
                            }
                            PairMethod.EnterCode -> launchManualPair(manualCode)
                            PairMethod.ShowCode -> launchManualPair(pairingCode)
                        }
                    },
                    onBack = {
                        verifyError = null
                        step = when (chosenMethod) {
                            PairMethod.Scan -> WizardStep.Confirm
                            PairMethod.EnterCode -> WizardStep.ManualEntry
                            PairMethod.ShowCode -> WizardStep.ShowCode
                        }
                    },
                    onCancel = onCancel,
                )
            }
        }
    }

    if (showQrScanner) {
        QrPairingScanner(
            onPairingDetected = { payload ->
                showQrScanner = false
                pendingPayload = payload
                ttlSeconds = defaultTtlSeconds(
                    qrTtlSeconds = payload.relay?.ttlSeconds,
                    transportHint = payload.relay?.transportHint,
                    isTailscaleDetected = isTailscaleDetected,
                )
                step = WizardStep.Confirm
            },
            onDismiss = { showQrScanner = false },
        )
    }
}

private val PairingPreferencesDefault: Long =
    com.hermesandroid.relay.data.PairingPreferences.DEFAULT_TTL_SECONDS

private enum class WizardStep {
    Method,
    Confirm,
    ManualEntry,
    ShowCode,
    Verify;

    /** Slot index in the 3-dot indicator regardless of which path is active. */
    val indicatorIndex: Int
        get() = when (this) {
            Method -> 0
            Confirm, ManualEntry, ShowCode -> 1
            Verify -> 2
        }
}

private enum class PairMethod { Scan, EnterCode, ShowCode }

@Composable
private fun WizardStepIndicator(currentStep: Int, method: PairMethod) {
    val totalSteps = 3
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(totalSteps) { index ->
            val isCompleted = index < currentStep
            val isActive = index == currentStep
            val bg = when {
                isCompleted -> MaterialTheme.colorScheme.primary
                isActive -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val fg = when {
                isCompleted || isActive -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = fg,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (index < totalSteps - 1) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(
                            if (isCompleted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = when (currentStep) {
            0 -> "Step 1 of 3 — Choose how to pair"
            1 -> when (method) {
                PairMethod.Scan -> "Step 2 of 3 — Confirm pairing"
                PairMethod.EnterCode -> "Step 2 of 3 — Enter pairing code"
                PairMethod.ShowCode -> "Step 2 of 3 — Show code on host"
            }
            else -> "Step 3 of 3 — Verify"
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MethodStep(
    relayEnabled: Boolean,
    onPickScan: () -> Unit,
    onPickEnterCode: () -> Unit,
    onPickShowCode: () -> Unit,
    onSkip: (() -> Unit)?,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Pair with your server",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Run /hermes-relay-pair in any Hermes chat, or hermes-pair on your " +
                "server, to start pairing. Pick the method that fits your setup.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MethodTile(
            icon = Icons.Filled.QrCodeScanner,
            title = "Scan QR code",
            subtitle = "Recommended — one scan configures chat, the relay, and your session",
            onClick = onPickScan,
            isPrimary = true,
        )

        MethodTile(
            icon = Icons.Filled.Keyboard,
            title = "Enter a code",
            subtitle = "The host already printed a 6-character code — type it in",
            onClick = onPickEnterCode,
        )

        if (relayEnabled) {
            MethodTile(
                icon = Icons.Filled.PhonelinkLock,
                title = "Show a code on this phone",
                subtitle = "No camera or QR? Display a code here and register it on the host",
                onClick = onPickShowCode,
            )
        }

        if (onSkip != null) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Skip for now — set up later in Settings")
            }
        }
    }
}

@Composable
private fun MethodTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isPrimary: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isPrimary) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isPrimary) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isPrimary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPrimary) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = if (isPrimary) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * Returns an error message when [url] looks like a relay URL (ws/wss) in an
 * API-server field, or null when the scheme is fine or the field is empty.
 * The API server is HTTP/SSE; the relay is WSS — mixing them silently used
 * to land in the Confirm preview as a mislabeled line.
 */
private fun apiUrlSchemeError(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    return when {
        trimmed.startsWith("ws://", ignoreCase = true) ||
            trimmed.startsWith("wss://", ignoreCase = true) ->
            "Looks like a relay URL — API server expects http:// or https://"
        else -> null
    }
}

/** Mirror of [apiUrlSchemeError] for the relay field. */
private fun relayUrlSchemeError(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    return when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ->
            "Looks like an API URL — relay expects wss:// (or ws:// for local)"
        else -> null
    }
}

@Composable
private fun ManualEntryStep(
    apiUrl: String,
    onApiUrlChange: (String) -> Unit,
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    val trimmedCode = code.trim().uppercase()
    val codeValid = trimmedCode.length in 4..12 && trimmedCode.all { it.isLetterOrDigit() }
    val apiError = apiUrlSchemeError(apiUrl)
    val relayError = relayUrlSchemeError(relayUrl)
    val canSubmit = codeValid &&
        relayUrl.isNotBlank() && apiUrl.isNotBlank() &&
        apiError == null && relayError == null

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Enter pairing code",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Type the code printed by hermes-pair or /hermes-relay-pair, plus your " +
                "API server and relay URLs. We'll persist them and pair in one shot.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = apiUrl,
            onValueChange = onApiUrlChange,
            label = { Text("API server URL") },
            placeholder = { Text("http://your-server:8642") },
            singleLine = true,
            isError = apiError != null,
            supportingText = {
                Text(apiError ?: "Hermes API — chat and sessions (default port 8642)")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = relayUrl,
            onValueChange = onRelayUrlChange,
            label = { Text("Relay URL") },
            placeholder = { Text("wss://your-server:8767") },
            singleLine = true,
            isError = relayError != null,
            supportingText = {
                Text(relayError ?: "Hermes Relay — bridge, voice, terminal (default port 8767)")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("Pairing code") },
            placeholder = { Text("e.g. ABC123") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace,
            ),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(
                onGo = { if (canSubmit) onSubmit() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) {
                Text("Back")
            }
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.weight(1f),
            ) {
                Text("Pair")
            }
        }
    }
}

@Composable
private fun ShowCodeStep(
    apiUrl: String,
    onApiUrlChange: (String) -> Unit,
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    pairingCode: String,
    onRegenerate: () -> Unit,
    onBack: () -> Unit,
    onConnect: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val apiError = apiUrlSchemeError(apiUrl)
    val relayError = relayUrlSchemeError(relayUrl)
    val canConnect = pairingCode.isNotBlank() &&
        relayUrl.isNotBlank() && apiUrl.isNotBlank() &&
        apiError == null && relayError == null

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Show code on host",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Use this when you can't scan the pairing QR. Set your URLs, then " +
                "register the code below on the host running Hermes-Relay.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = apiUrl,
            onValueChange = onApiUrlChange,
            label = { Text("API server URL") },
            placeholder = { Text("http://your-server:8642") },
            singleLine = true,
            isError = apiError != null,
            supportingText = {
                Text(apiError ?: "Hermes API — chat and sessions (default port 8642)")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = relayUrl,
            onValueChange = onRelayUrlChange,
            label = { Text("Relay URL") },
            placeholder = { Text("wss://your-server:8767") },
            singleLine = true,
            isError = relayError != null,
            supportingText = {
                Text(relayError ?: "Hermes Relay — bridge, voice, terminal (default port 8767)")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        // Step 1 — show the code
        Text(
            text = "1. Copy this code",
            style = MaterialTheme.typography.titleSmall,
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            ) {
                Text(
                    text = pairingCode.ifBlank { "------" },
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = MaterialTheme.typography.headlineMedium.fontSize * 0.15,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    if (pairingCode.isNotBlank()) {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    ClipData.newPlainText("Pairing code", pairingCode)
                                )
                            )
                        }
                    }
                }) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy pairing code",
                    )
                }
                IconButton(onClick = onRegenerate) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Generate new code",
                    )
                }
            }
        }

        // Step 2 — register on host
        Text(
            text = "2. On the host running Hermes-Relay, run:",
            style = MaterialTheme.typography.titleSmall,
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "hermes-pair --register-code ${pairingCode.ifBlank { "<code>" }}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        if (pairingCode.isNotBlank()) {
                            val cmd = "hermes-pair --register-code $pairingCode"
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText("hermes-pair command", cmd)
                                    )
                                )
                            }
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy command",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Step 3 — connect
        Text(
            text = "3. Then come back and tap Connect",
            style = MaterialTheme.typography.titleSmall,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) {
                Text("Back")
            }
            Button(
                onClick = onConnect,
                enabled = canConnect,
                modifier = Modifier.weight(1f),
            ) {
                Text("Connect")
            }
        }
    }
}

@Composable
private fun ConfirmStep(
    payload: HermesPairingPayload,
    ttlSeconds: Long,
    onTtlChange: (Long) -> Unit,
    isTailscaleDetected: Boolean,
    onBack: () -> Unit,
    onConfirm: (HermesPairingPayload) -> Unit,
) {
    val transportHint = payload.relay?.transportHint
    val relayUrl = payload.relay?.url
    val isInsecureRelay = relayUrl?.startsWith("ws://") == true

    // Endpoints preview + prefer-role control (ADR 24). `endpoints` is
    // always non-null + non-empty after parseHermesPairingQr, but we still
    // null-safe on the orEmpty() path for defense in depth.
    val endpoints = payload.endpoints.orEmpty()
    val distinctRoles = endpoints.map { it.role }.distinct()
    var preferRole by remember(payload) { mutableStateOf<String?>(null) }
    var preferMenuOpen by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Confirm pairing",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Review the scanned details and choose how long this pairing should last.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // What got scanned
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabeledLine(
                    label = "API server",
                    value = payload.serverUrl,
                    hint = "chat & sessions",
                )
                if (relayUrl != null) {
                    LabeledLine(
                        label = "Relay",
                        value = relayUrl,
                        hint = "bridge, voice, terminal",
                    )
                }
                if (payload.relay?.grants?.isNotEmpty() == true) {
                    LabeledLine(
                        label = "Grants",
                        value = payload.relay.grants.keys.sorted().joinToString(", "),
                    )
                }
                Spacer(Modifier.height(2.dp))
                TransportSecurityBadge(
                    isSecure = !isInsecureRelay,
                    reason = if (isInsecureRelay) "local_dev" else null,
                    size = TransportSecuritySize.Row,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Endpoints preview (ADR 24) — always shown so v1/v2 pairings that
        // synthesize a single candidate still get a "what will connect"
        // summary row. v3 QRs with multiple candidates expose the full list
        // + a Prefer dropdown that promotes the chosen role to priority 0.
        if (endpoints.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Endpoints (${endpoints.size})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "The phone tries these in priority order and picks " +
                            "the first reachable one. Switches automatically when " +
                            "your network changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    endpoints.forEachIndexed { index, candidate ->
                        if (index > 0) HorizontalDivider()
                        EndpointPreviewRow(
                            candidate = candidate,
                            isPreferred = preferRole?.equals(
                                candidate.role,
                                ignoreCase = true,
                            ) == true,
                        )
                    }
                    // Only expose the Prefer control when the QR carried
                    // more than one distinct role — single-endpoint payloads
                    // have nothing to reorder.
                    if (distinctRoles.size > 1) {
                        HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Prefer:",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Box {
                                TextButton(onClick = { preferMenuOpen = true }) {
                                    Text(
                                        text = preferRole?.let { roleLabel(it) }
                                            ?: "Natural order",
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.ArrowDropDown,
                                        contentDescription = null,
                                    )
                                }
                                DropdownMenu(
                                    expanded = preferMenuOpen,
                                    onDismissRequest = { preferMenuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Natural order") },
                                        onClick = {
                                            preferRole = null
                                            preferMenuOpen = false
                                        },
                                    )
                                    distinctRoles.forEach { role ->
                                        DropdownMenuItem(
                                            text = { Text(roleLabel(role)) },
                                            onClick = {
                                                preferRole = role
                                                preferMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // TTL picker — flat radio list, no nested dialog
        Text(
            text = "Keep this pairing for…",
            style = MaterialTheme.typography.titleSmall,
        )
        val transportLabel = when {
            isTailscaleDetected -> "Transport: Tailscale detected"
            transportHint.equals("wss", ignoreCase = true) -> "Transport: TLS (wss://)"
            transportHint.equals("ws", ignoreCase = true) -> "Transport: plain ws://"
            else -> null
        }
        if (transportLabel != null) {
            Text(
                text = transportLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Column {
            ttlPickerOptions().forEach { option ->
                val selected = option.seconds == ttlSeconds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            onClick = { onTtlChange(option.seconds) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onTtlChange(option.seconds) },
                    )
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        if (isInsecureRelay) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "This relay uses plain ws:// — traffic is not encrypted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = "Only continue if you trust the network (LAN, Tailscale, VPN).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) {
                Text("Back")
            }
            Button(
                onClick = {
                    val effective = preferRole
                        ?.let { reorderByPreferredRole(payload, it) }
                        ?: payload
                    onConfirm(effective)
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Pair")
            }
        }
    }
}

@Composable
private fun VerifyStep(
    authState: AuthState,
    error: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (error == null) "Pairing…" else "Pairing failed",
            style = MaterialTheme.typography.headlineSmall,
        )

        if (error == null) {
            CircularProgressIndicator()
            Text(
                text = when (authState) {
                    is AuthState.Pairing -> "Negotiating with the relay…"
                    is AuthState.Paired -> "Paired — opening chat…"
                    else -> "Connecting to the relay…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Back")
                }
                Button(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Retry")
                }
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun LabeledLine(label: String, value: String, hint: String? = null) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Text(
                    text = " · $hint",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * One row of the ConfirmStep's Endpoints preview — role label, host:port,
 * priority, optional "Preferred" chip when the user promoted it.
 *
 * Intentionally NOT the shared `EndpointsCard` component: that one carries
 * probe status, 3-dot actions, and TOFU pin viewer — none of which apply
 * pre-pair. Here we only need a lightweight visual summary.
 */
@Composable
private fun EndpointPreviewRow(
    candidate: EndpointCandidate,
    isPreferred: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = candidate.displayLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isPreferred) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = "Preferred",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                text = "${candidate.api.host}:${candidate.api.port}" +
                    (candidate.relay.transportHint?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = "p${candidate.priority}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Reorder the endpoints array so the chosen role lands at priority 0.
 * Priority values are renumbered to match the new order — this matters at
 * persist time because `setDeviceEndpoints` stores the list verbatim and
 * downstream [com.hermesandroid.relay.network.EndpointResolver] trusts the
 * `priority` field (see ADR 24 "strict priority").
 *
 * No-op when the preferred role is already at index 0, or when the role
 * isn't present in the candidates list.
 */
private fun reorderByPreferredRole(
    payload: HermesPairingPayload,
    preferRole: String,
): HermesPairingPayload {
    val list = payload.endpoints.orEmpty().toMutableList()
    val idx = list.indexOfFirst { it.role.equals(preferRole, ignoreCase = true) }
    if (idx <= 0) return payload
    val promoted = list.removeAt(idx)
    list.add(0, promoted)
    val renumbered = list.mapIndexed { i, c -> c.copy(priority = i) }
    return payload.copy(endpoints = renumbered)
}

/** Role → user-facing label used inside the Prefer dropdown menu. */
private fun roleLabel(role: String): String = when (role.lowercase()) {
    "lan" -> "LAN"
    "tailscale" -> "Tailscale"
    "public" -> "Public"
    else -> role
}
