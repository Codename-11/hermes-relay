package com.hermesandroid.relay.ui.components

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.res.stringResource
import com.hermesandroid.relay.R
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.network.shared.HermesLanDiscovery
import com.hermesandroid.relay.network.shared.HermesLanDiscoveryResult
import com.hermesandroid.relay.util.ServerAddress
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.StandardVoiceAvailability
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Shared connection wizard used by both onboarding (first run) and
 * Settings → Connections. Hermes setup is the default path:
 * save the API URL/key, derive the dashboard URL, and verify sessions.
 * Relay pairing remains available for power tools such as Terminal,
 * Bridge, Relay sessions, channel grants, and relay-backed media routes.
 *
 * Steps:
 *
 *  1. **Method** — pick a setup path. Four tiles:
 *     - **Hermes**: API URL + API key. → StandardEntry.
 *     - **Scan QR**: standard convenience path for API URL/key QRs; Relay
 *       plugin QRs still work and route through Confirm/Relay pair.
 *     - **Pair Relay by code**: server already minted a code via
 *       `hermes pair --register-code` or `/hermes-relay-pair`. → ManualEntry.
 *     - **Show Relay code** (relay-gated): phone displays a generated
 *       6-char code + the host command to run. → ShowCode.
 *  2. **Path-specific middle step**:
 *     - Standard path → **StandardEntry**: API URL + API key.
 *       Tap Connect to persist and verify `/health` + `/api/sessions`.
 *     - QR path → **Confirm**: shows what was scanned, transport security
 *       badge, TTL picker, insecure note when the relay is plain `ws://`.
 *       Tap Pair to apply the full payload (URLs, code, grants, cert pin),
 *       or Connect for API-only QRs.
 *     - Enter code path → **ManualEntry**: API URL + Relay URL + code
 *       fields. Tap Pair to persist the URLs and connect with the typed
 *       code as the server-issued code.
 *     - Show code path → **ShowCode**: API URL + Relay URL fields, the
 *       phone-generated code (with copy + regen), the
 *       `hermes pair --register-code <code>` command (with copy), and a
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
 * @param onComplete called after a successful standard connect or pair lands; the caller is
 *   responsible for navigating away (e.g. completeOnboarding + nav to chat)
 * @param onCancel called when the user backs out before the verify step
 *   resolves. Caller decides whether that means "stay in Settings" or
 *   "skip onboarding and go to chat anyway"
 * @param onManageSignIn optional navigation hook shown after a successful
 *   Standard connect when the dashboard reports that sign-in is required.
 * @param showSkip when true, surfaces a "Skip for now" affordance on the
 *   first step. Onboarding sets this to true so users can defer setup;
 *   Settings sets it to false because there's nothing to skip to.
 */
@Composable
fun ConnectionWizard(
    connectionViewModel: ConnectionViewModel,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onManageSignIn: (() -> Unit)? = null,
    showSkip: Boolean = false,
    modifier: Modifier = Modifier,
    /**
     * Deep-link into a specific pair method on first composition.
     * Currently only `"scan"` is honored — jumps directly into camera-
     * permission-request → scanner, skipping the Method chooser. Null
     * keeps the default Method step so users can still pick Scan / Enter
     * code / Show code manually. The "Add connection" FAB sets this to
     * `"scan"` because the single-purpose entry deserves a single-purpose
     * flow; re-pair surfaces leave it null so the chooser stays available.
     */
    autoStart: String? = null,
    /**
     * Optional "Try the demo" affordance shown atop the Method step. When
     * non-null, the wizard surfaces an offline Demo / Explore entry point so a
     * first-run user (or a Play reviewer with no server) can see the app work
     * with zero setup. Null hides it — Settings → Connections passes null
     * because there's nothing to "first-run" there; onboarding + the Connect
     * screen pass a callback that enters demo and routes to Chat.
     */
    onTryDemo: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    val isTailscaleDetected by connectionViewModel.isTailscaleDetected.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()
    val relayEnabled by FeatureFlags.relayEnabled(context)
        .collectAsState(initial = FeatureFlags.isDevBuild)
    val pairingCode by connectionViewModel.pairingCode.collectAsState()
    val currentApiUrl by connectionViewModel.apiServerUrl.collectAsState()
    val currentRelayUrl by connectionViewModel.relayUrl.collectAsState()
    val currentDashboardUrl by connectionViewModel.effectiveDashboardUrl.collectAsState()

    var step by remember { mutableStateOf(WizardStep.Method) }
    var chosenMethod by remember { mutableStateOf(PairMethod.Standard) }
    var pendingPayload by remember { mutableStateOf<HermesPairingPayload?>(null) }
    var ttlSeconds by remember { mutableStateOf(PairingPreferencesDefault) }
    var showQrScanner by remember { mutableStateOf(false) }
    var verifyError by remember { mutableStateOf<String?>(null) }
    var verifyAttempt by remember { mutableStateOf(0) }
    var standardBusy by remember { mutableStateOf(false) }
    var standardError by remember { mutableStateOf<String?>(null) }
    var standardSuccess by remember { mutableStateOf<ConnectionViewModel.StandardApiSetupResult?>(null) }

    // Pre-pair duplicate detection. When the user is about to pair to an
    // API URL that already has a connection in the store, we stop the
    // wizard at this prompt instead of silently creating a second entry
    // (which would just get merged away by the post-pair dedupe in
    // ConnectionViewModel — confusing UX, since the user's custom label
    // on the existing entry is what "wins"). The prompt lets them
    // explicitly opt into the re-pair, or cancel out.
    //
    // Held as a Connection to preserve label/id for the dialog copy;
    // null means "no prompt active, proceed normally".
    var duplicatePrompt by remember { mutableStateOf<Connection?>(null) }
    // When the manual flow triggers the duplicate prompt, remember the
    // code the user typed / was issued so the confirm branch can finish
    // the pair without the user re-entering anything. Null for scan
    // path (which uses [pendingPayload] instead).
    var pendingManualCode by remember { mutableStateOf<String?>(null) }
    var pendingStandardDraft by remember { mutableStateOf<StandardConnectionDraft?>(null) }
    val wizardScope = rememberCoroutineScope()

    // Standard API/dashboard fields. Pre-fill from the active connection.
    var standardApiUrl by remember(currentApiUrl) { mutableStateOf(currentApiUrl) }
    var standardApiKey by remember { mutableStateOf("") }
    var standardDashboardUrl by remember(currentApiUrl, currentDashboardUrl) {
        val derived = Connection.deriveDefaultDashboardUrl(currentApiUrl)
        mutableStateOf(
            currentDashboardUrl
                .takeIf { it.isNotBlank() && !it.equals(derived, ignoreCase = true) }
                .orEmpty(),
        )
    }
    var standardTailscaleApiUrl by remember { mutableStateOf("") }
    var standardApiKeyVisible by remember { mutableStateOf(false) }

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
            // Don't dead-end on denial — return to the chooser and point the
            // user at the manual pairing paths (URL entry / 6-char code)
            // instead of leaving them on a vanishing toast with no scanner.
            step = WizardStep.Method
            Toast.makeText(
                context,
                context.getString(R.string.cw_camera_denied),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Deep-link: when the caller passed autoStart="scan" (currently only the
    // "Add connection" FAB does), fire the permission launcher on first
    // composition. Equivalent to the user tapping the Scan tile in the
    // Method step — sets chosenMethod and bounces through the camera
    // permission gate into the scanner. Only runs once per wizard mount
    // (keyed on Unit); unrecognized autoStart values fall through silently
    // so a future build that adds more deep-link targets can ignore old
    // args without crashing.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        when (autoStart) {
            "scan" -> {
                chosenMethod = PairMethod.Scan
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> Unit
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
            // Method-aware timeout copy. The watchdog only sees authState, but
            // it knows which pairing method the user chose — enough to name the
            // most likely cause instead of one generic "relay timed out."
            verifyError = when (chosenMethod) {
                PairMethod.EnterCode, PairMethod.ShowCode ->
                    context.getString(R.string.cw_timeout_entercode_showcode)
                PairMethod.Scan ->
                    context.getString(R.string.cw_timeout_scan)
                else ->
                    context.getString(R.string.cw_timeout_other)
            }
        }
    }

    // Look up an existing connection pointing at [serverUrl] — excluding
    // the active one, which during the Add-connection flow is the blank
    // placeholder we pre-created in [ConnectionViewModel.beginAddConnection].
    // Re-pair flows from Settings → Connections switch to the target BEFORE
    // entering the wizard, so during those the active id IS the target
    // and the filter correctly returns null (no pointless self-prompt).
    val findDuplicateFor: (String) -> Connection? = { serverUrl ->
        val activeId = connectionViewModel.activeConnectionId.value
        connectionViewModel.connectionStore.connections.value.firstOrNull { c ->
            c.id != activeId &&
                c.apiServerUrl.isNotBlank() &&
                c.apiServerUrl == serverUrl
        }
    }

    val applyStandardConnect:
        (String, String, String, String, List<EndpointCandidate>?) -> Unit = { apiUrl, apiKey, tailscaleApiUrl, dashboardUrl, routes ->
        val trimmedApi = apiUrl.trim()
        standardBusy = true
        standardError = null
        standardSuccess = null
        connectionViewModel.saveStandardApiConnection(
            apiUrl = trimmedApi,
            apiKey = apiKey,
            tailscaleApiUrl = tailscaleApiUrl,
            dashboardUrl = dashboardUrl,
            routeCandidatesOverride = routes,
        ) { result ->
            standardBusy = false
            if (result.ok) {
                standardSuccess = result
            } else {
                standardError = result.message
                step = WizardStep.StandardEntry
            }
        }
    }

    val launchStandardConnect:
        (String, String, String, String, List<EndpointCandidate>?) -> Unit = { apiUrl, apiKey, tailscaleApiUrl, dashboardUrl, routes ->
        val trimmedApi = apiUrl.trim()
        val existing = findDuplicateFor(trimmedApi)
        if (existing != null) {
            pendingStandardDraft = StandardConnectionDraft(
                apiUrl = trimmedApi,
                apiKey = apiKey,
                tailscaleApiUrl = tailscaleApiUrl,
                dashboardUrl = dashboardUrl,
                routeCandidates = routes,
            )
            duplicatePrompt = existing
        } else {
            applyStandardConnect(trimmedApi, apiKey, tailscaleApiUrl, dashboardUrl, routes)
        }
    }

    // Shared launcher for the manual paths — persists URLs, applies the
    // server-issued code, drops any stale session, and reconnects. Used by
    // both ManualEntry (typed code) and ShowCode (phone-generated code).
    //
    // Runs the pre-pair duplicate check first: if another connection
    // already has this API URL, surface the prompt and stall the wizard
    // on Method/ManualEntry/ShowCode until the user confirms or cancels.
    // Dialog confirm re-invokes via [applyManualPair] (below) which
    // bypasses the check so we don't loop.
    val launchManualPair: (String) -> Unit = { code ->
        val trimmedApi = manualApiUrl.trim()
        val existing = findDuplicateFor(trimmedApi)
        if (existing != null) {
            // Remember what to re-run when the user confirms.
            pendingManualCode = code
            duplicatePrompt = existing
        } else {
            wizardScope.launch {
                connectionViewModel.ensureActiveConnectionForSetup(
                    apiServerUrl = trimmedApi,
                    relayUrl = manualRelayUrl.trim(),
                )
                applyManualPair(connectionViewModel, trimmedApi, manualRelayUrl.trim(), code)
                step = WizardStep.Verify
                verifyAttempt += 1
            }
        }
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
                    onPickStandard = {
                        chosenMethod = PairMethod.Standard
                        standardError = null
                        step = WizardStep.StandardEntry
                    },
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
                    onTryDemo = onTryDemo,
                )

                WizardStep.StandardEntry -> StandardEntryStep(
                    apiUrl = standardApiUrl,
                    onApiUrlChange = {
                        standardApiUrl = it
                        standardError = null
                        standardSuccess = null
                    },
                    apiKey = standardApiKey,
                    onApiKeyChange = {
                        standardApiKey = it
                        standardError = null
                        standardSuccess = null
                    },
                    tailscaleApiUrl = standardTailscaleApiUrl,
                    onTailscaleApiUrlChange = {
                        standardTailscaleApiUrl = it
                        standardError = null
                        standardSuccess = null
                    },
                    dashboardUrl = standardDashboardUrl,
                    onDashboardUrlChange = {
                        standardDashboardUrl = it
                        standardError = null
                        standardSuccess = null
                    },
                    apiKeyVisible = standardApiKeyVisible,
                    onToggleApiKeyVisible = { standardApiKeyVisible = !standardApiKeyVisible },
                    isConnecting = standardBusy,
                    error = standardError,
                    success = standardSuccess,
                    onBack = { step = WizardStep.Method },
                    onComplete = onComplete,
                    onManageSignIn = onManageSignIn,
                    isTailscaleDetected = isTailscaleDetected,
                    onSubmit = {
                        launchStandardConnect(
                            standardApiUrl,
                            standardApiKey,
                            standardTailscaleApiUrl,
                            standardDashboardUrl,
                            null,
                        )
                    },
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
                            standardBusy = standardBusy,
                            standardSuccess = standardSuccess,
                            onBack = {
                                pendingPayload = null
                                step = WizardStep.Method
                            },
                            onComplete = onComplete,
                            onManageSignIn = onManageSignIn,
                            onConfirm = { reorderedPayload ->
                                // Persist the reordered payload so a retry
                                // from VerifyStep reuses the chosen preferred
                                // role — otherwise Retry would drop the user's
                                // "Prefer" choice on every failure.
                                pendingPayload = reorderedPayload
                                // Pre-pair duplicate check — stops the flow
                                // on the Confirm screen if the scanned
                                // serverUrl already matches an existing
                                // connection. User can then re-pair that
                                // existing entry in place (see the
                                // DuplicateConnectionDialog at the bottom
                                // of this composable) or cancel out. If no
                                // duplicate, proceed to Verify as before.
                                val existing = findDuplicateFor(
                                    reorderedPayload.serverUrl,
                                )
                                if (existing != null) {
                                    duplicatePrompt = existing
                                } else if (reorderedPayload.relay == null) {
                                    launchStandardConnect(
                                        reorderedPayload.serverUrl,
                                        reorderedPayload.key,
                                        "",
                                        reorderedPayload.dashboardUrl.orEmpty(),
                                        reorderedPayload.endpoints,
                                    )
                                } else {
                                    wizardScope.launch {
                                        connectionViewModel.ensureActiveConnectionForSetup(
                                            apiServerUrl = reorderedPayload.serverUrl,
                                            relayUrl = reorderedPayload.relay.url,
                                            routeCandidates = reorderedPayload.endpoints,
                                        )
                                        connectionViewModel.applyPairingPayload(
                                            reorderedPayload,
                                            ttlSeconds,
                                        )
                                        step = WizardStep.Verify
                                        verifyAttempt += 1
                                    }
                                }
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
                            PairMethod.Standard -> applyStandardConnect(
                                standardApiUrl,
                                standardApiKey,
                                standardTailscaleApiUrl,
                                standardDashboardUrl,
                                null,
                            )
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
                            PairMethod.Standard -> WizardStep.StandardEntry
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
                if (payload.relay == null) {
                    standardApiUrl = payload.serverUrl
                    standardApiKey = payload.key
                    standardDashboardUrl = payload.dashboardUrl.orEmpty()
                    standardError = null
                    standardSuccess = null
                }
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

    // Pre-pair duplicate prompt. Renders over whichever wizard step is
    // currently visible. Confirm = switch to the existing connection,
    // discard the placeholder [ConnectionViewModel.beginAddConnection]
    // pre-created for this flow (if any), then re-run the pair against
    // the existing connection so the new session replaces the old one.
    // Dismiss = clear the prompt and return the user to the step they
    // came from (Confirm for scan, ManualEntry/ShowCode for manual), so
    // they can re-read the URL they just entered or scan a different QR.
    duplicatePrompt?.let { existing ->
        DuplicateConnectionDialog(
            existing = existing,
            onUpdate = {
                val prompt = existing
                duplicatePrompt = null
                wizardScope.launch {
                    // Snapshot the placeholder id before we switch away —
                    // after switchConnection returns, activeConnectionId
                    // points at the EXISTING connection and we'd lose the
                    // reference to the blank placeholder we need to delete.
                    val placeholderId = connectionViewModel.activeConnectionId.value
                        ?.takeIf { it != prompt.id }

                    // 1. Switch to the existing connection so all subsequent
                    //    applyPairingPayload / applyServerIssuedCodeAndReset
                    //    calls land in ITS auth store, not the placeholder's.
                    //    join() ensures the AuthManager swap has finished
                    //    before we apply the payload.
                    connectionViewModel.switchConnection(prompt.id).join()

                    // 2. Remove the placeholder we pre-created. Safe no-op
                    //    if it was never a placeholder (pairedAt != null)
                    //    thanks to discardPlaceholderConnection's own
                    //    guard. Also safe if placeholderId is null (which
                    //    would mean the user entered the wizard from a
                    //    re-pair flow on the target itself — no cleanup
                    //    needed).
                    if (placeholderId != null) {
                        connectionViewModel.discardPlaceholderConnection(placeholderId)
                    }

                    // 3. Apply the connect/pair, now targeting the existing
                    //    connection's auth store. Standard paths save API
                    //    settings only; Relay paths apply the pairing code.
                    when (chosenMethod) {
                        PairMethod.Standard -> {
                            val draft = pendingStandardDraft
                            if (draft != null) {
                                pendingStandardDraft = null
                                applyStandardConnect(
                                    draft.apiUrl,
                                    draft.apiKey,
                                    draft.tailscaleApiUrl,
                                    draft.dashboardUrl,
                                    draft.routeCandidates,
                                )
                            }
                        }
                        PairMethod.Scan -> {
                            val payload = pendingPayload
                            if (payload != null) {
                                if (payload.relay == null) {
                                    applyStandardConnect(
                                        payload.serverUrl,
                                        payload.key,
                                        "",
                                        payload.dashboardUrl.orEmpty(),
                                        payload.endpoints,
                                    )
                                } else {
                                    connectionViewModel.applyPairingPayload(
                                        payload,
                                        ttlSeconds,
                                    )
                                    step = WizardStep.Verify
                                    verifyAttempt += 1
                                }
                            }
                        }
                        PairMethod.EnterCode, PairMethod.ShowCode -> {
                            val code = pendingManualCode
                            if (code != null) {
                                applyManualPair(
                                    connectionViewModel,
                                    manualApiUrl.trim(),
                                    manualRelayUrl.trim(),
                                    code,
                                )
                                pendingManualCode = null
                                step = WizardStep.Verify
                                verifyAttempt += 1
                            }
                        }
                    }
                }
            },
            onDismiss = {
                duplicatePrompt = null
                pendingManualCode = null
                pendingStandardDraft = null
                // Scan path: kick back to the Confirm step so the user
                // can either re-confirm (which will re-trigger the prompt)
                // or hit Back to scan a different QR. Manual paths: the
                // user is still on ManualEntry/ShowCode, the step hasn't
                // advanced, so no navigation change needed.
            },
        )
    }
}

/**
 * Bypass version of the manual pair launcher — does NOT run the duplicate
 * check. Called from two sites:
 *  - [launchManualPair] inside [ConnectionWizard] when no duplicate is
 *    found (the common happy path).
 *  - The duplicate-prompt confirm handler, which has already switched to
 *    the existing connection and explicitly wants to apply the pairing
 *    there.
 */
private fun applyManualPair(
    vm: ConnectionViewModel,
    apiUrl: String,
    relayUrl: String,
    code: String,
) {
    vm.updateApiServerUrl(apiUrl)
    vm.updateRelayUrl(relayUrl)
    vm.authManager.applyServerIssuedCodeAndReset(code.trim().uppercase())
    vm.disconnectRelay()
    vm.connectRelay(relayUrl)
}

/**
 * Two-button confirmation dialog shown by [ConnectionWizard] when the user
 * is about to pair against an API URL that already matches an existing
 * [Connection] in the store. Prevents the "two cards to the same server"
 * class of bug at the wizard layer — the post-pair dedupe in
 * [ConnectionViewModel] is still there as a safety net, but this prompt
 * lets the user understand what's about to happen and carry their custom
 * label forward without a silent merge.
 */
@Composable
private fun DuplicateConnectionDialog(
    existing: Connection,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cw_update_existing_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.cw_update_existing_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = existing.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = existing.apiServerUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.cw_update_existing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onUpdate) { Text(stringResource(R.string.cw_update_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cw_cancel)) }
        },
    )
}

private val PairingPreferencesDefault: Long =
    com.hermesandroid.relay.data.PairingPreferences.DEFAULT_TTL_SECONDS

private enum class WizardStep {
    Method,
    StandardEntry,
    Confirm,
    ManualEntry,
    ShowCode,
    Verify;

    /** Slot index in the 3-dot indicator regardless of which path is active. */
    val indicatorIndex: Int
        get() = when (this) {
            Method -> 0
            StandardEntry, Confirm, ManualEntry, ShowCode -> 1
            Verify -> 2
        }
}

private enum class PairMethod { Standard, Scan, EnterCode, ShowCode }

private data class StandardConnectionDraft(
    val apiUrl: String,
    val apiKey: String,
    val tailscaleApiUrl: String = "",
    val dashboardUrl: String = "",
    val routeCandidates: List<EndpointCandidate>? = null,
)

private const val SetupGuideUrl = "https://codename-11.github.io/hermes-relay/guide/getting-started"
private const val RelaySetupDocsUrl = "https://codename-11.github.io/hermes-relay/reference/relay-server"
private const val HermesApiDocsUrl = "https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server"

private fun openExternalUrl(context: android.content.Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

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
            0 -> "Step 1 of 3 — Choose setup"
            1 -> when (method) {
                PairMethod.Standard -> "Step 2 of 3 — Connect API/dashboard"
                PairMethod.Scan -> "Step 2 of 3 — Confirm QR details"
                PairMethod.EnterCode -> "Step 2 of 3 — Enter Relay pairing code"
                PairMethod.ShowCode -> "Step 2 of 3 — Show Relay code on host"
            }
            else -> "Step 3 of 3 — Verify Relay"
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MethodStep(
    relayEnabled: Boolean,
    onPickStandard: () -> Unit,
    onPickScan: () -> Unit,
    onPickEnterCode: () -> Unit,
    onPickShowCode: () -> Unit,
    onSkip: (() -> Unit)?,
    onTryDemo: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.cw_connect_to_hermes),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.cw_connect_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Offline "Try the demo" entry point — only surfaced where a first-run
        // user benefits (onboarding + the Connect screen). Lets a reviewer or
        // curious user see the app work with zero setup and zero network
        // before committing to connecting a real server.
        if (onTryDemo != null) {
            OutlinedButton(
                onClick = onTryDemo,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.cw_try_demo),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.cw_try_demo_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { openExternalUrl(context, SetupGuideUrl) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.cw_setup_guide))
            }
            OutlinedButton(
                onClick = { openExternalUrl(context, HermesApiDocsUrl) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.cw_hermes_api))
            }
        }

        MethodTile(
            icon = Icons.Filled.Check,
            title = stringResource(R.string.cw_method_hermes_title),
            subtitle = stringResource(R.string.cw_method_hermes_subtitle),
            onClick = onPickStandard,
            isPrimary = true,
        )

        MethodTile(
            icon = Icons.Filled.QrCodeScanner,
            title = stringResource(R.string.cw_method_scan_title),
            subtitle = stringResource(R.string.cw_method_scan_subtitle),
            onClick = onPickScan,
        )

        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cw_advanced_relay_pairing),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.cw_advanced_relay_pairing_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { openExternalUrl(context, RelaySetupDocsUrl) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.cw_relay_docs))
            }
        }

        MethodTile(
            icon = Icons.Filled.Keyboard,
            title = stringResource(R.string.cw_method_pair_code_title),
            subtitle = stringResource(R.string.cw_method_pair_code_subtitle),
            onClick = onPickEnterCode,
        )

        if (relayEnabled) {
            MethodTile(
                icon = Icons.Filled.PhonelinkLock,
                title = stringResource(R.string.cw_method_show_code_title),
                subtitle = stringResource(R.string.cw_method_show_code_subtitle),
                onClick = onPickShowCode,
            )
        }

        if (onSkip != null) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cw_skip_for_now))
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
    // Wrong-scheme paste gets a precise message first…
    if (trimmed.startsWith("ws://", ignoreCase = true) ||
        trimmed.startsWith("wss://", ignoreCase = true)
    ) {
        return "Looks like a relay URL — API server expects http:// or https://"
    }
    // …then reject anything that won't actually parse as a host/URL. Without
    // this, a non-address such as "Manage sign-in and admin screens" passed
    // validation, was normalized to http://<spaces> at save, and crashed the
    // app when okhttp's url(String) threw on the malformed host (issue #131).
    return ServerAddress.fieldError(trimmed, "API server URL")
}

private fun optionalHttpUrlError(url: String, fieldLabel: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    // Bare hosts/IPs are fine — save paths run them through
    // [Connection.normalizeApiUrlInput], which assumes http://. An explicit
    // non-http scheme is an error (it would be preserved verbatim and dropped
    // at candidate-build time)…
    val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://").find(trimmed)
        ?.groupValues?.get(1)?.lowercase()
    if (scheme != null && scheme != "http" && scheme != "https") {
        return "$fieldLabel expects http:// or https:// (bare hosts get http://)"
    }
    // …and a value that won't parse as a real http(s) host (spaces, junk) is
    // rejected here rather than reaching a request builder that throws (#131).
    return ServerAddress.fieldError(trimmed, fieldLabel)
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
private fun StandardEntryStep(
    apiUrl: String,
    onApiUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    tailscaleApiUrl: String,
    onTailscaleApiUrlChange: (String) -> Unit,
    dashboardUrl: String,
    onDashboardUrlChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onToggleApiKeyVisible: () -> Unit,
    isConnecting: Boolean,
    error: String?,
    success: ConnectionViewModel.StandardApiSetupResult?,
    onBack: () -> Unit,
    onComplete: () -> Unit,
    onManageSignIn: (() -> Unit)?,
    isTailscaleDetected: Boolean = false,
    onSubmit: () -> Unit,
) {
    val context = LocalContext.current
    val scanScope = rememberCoroutineScope()
    val apiError = apiUrlSchemeError(apiUrl)
    val tailscaleError = optionalHttpUrlError(tailscaleApiUrl, "Tailscale API URL")
    val dashboardError = optionalHttpUrlError(dashboardUrl, "Dashboard URL")
    var advancedExpanded by remember { mutableStateOf(false) }
    var scanBusy by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<HermesLanDiscoveryResult>>(emptyList()) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var scanApiPort by remember { mutableStateOf("8642") }
    var scanDashboardPort by remember {
        mutableStateOf(Connection.DEFAULT_DASHBOARD_PORT.toString())
    }
    val parsedScanApiPort = scanApiPort.toIntOrNull()?.takeIf { it in 1..65_535 }
    val parsedScanDashboardPort = scanDashboardPort.toIntOrNull()?.takeIf { it in 1..65_535 }
    val canSubmit = apiUrl.isNotBlank() &&
        apiError == null &&
        tailscaleError == null &&
        dashboardError == null &&
        !isConnecting
    val defaultDashboardUrl = Connection.deriveDefaultDashboardUrl(apiUrl)
    val effectiveDashboardUrl = dashboardUrl
        .trim()
        .trimEnd('/')
        .takeIf { it.isNotBlank() }
        ?: defaultDashboardUrl

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.cw_hermes_label),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.cw_hermes_label_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = apiUrl,
            onValueChange = onApiUrlChange,
            label = { Text(stringResource(R.string.cw_api_url_label)) },
            placeholder = { Text(stringResource(R.string.cw_api_url_placeholder)) },
            singleLine = true,
            isError = apiError != null,
            supportingText = {
                Text(
                    apiError ?: stringResource(R.string.cw_api_url_supporting),
                )
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(
            onClick = {
                val apiPort = parsedScanApiPort ?: 8642
                val dashboardPort = parsedScanDashboardPort ?: Connection.DEFAULT_DASHBOARD_PORT
                scanBusy = true
                scanResults = emptyList()
                scanMessage = context.getString(R.string.cw_scan_message)
                scanScope.launch {
                    val results = runCatching {
                        HermesLanDiscovery.scan(
                            context = context,
                            apiPort = apiPort,
                            dashboardPort = dashboardPort,
                        )
                    }
                    results.onSuccess { found ->
                        scanResults = found
                        scanMessage = scanSummary(found)
                    }.onFailure { failure ->
                        scanResults = emptyList()
                        scanMessage = "LAN scan failed: ${failure.message ?: failure.javaClass.simpleName}"
                    }
                    scanBusy = false
                }
            },
            enabled = !isConnecting &&
                !scanBusy &&
                parsedScanApiPort != null &&
                parsedScanDashboardPort != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (scanBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(if (scanBusy) "Scanning LAN" else "Scan for Hermes on LAN")
        }

        scanMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        scanResults.forEach { candidate ->
            LanDiscoveryResultRow(
                candidate = candidate,
                onUse = {
                    onApiUrlChange(candidate.apiUrl)
                    val derivedDashboard = Connection.deriveDefaultDashboardUrl(candidate.apiUrl)
                    val detectedDashboard = candidate.dashboardUrl.orEmpty()
                    onDashboardUrlChange(
                        detectedDashboard
                            .takeIf {
                                it.isNotBlank() &&
                                    !it.equals(derivedDashboard, ignoreCase = true)
                            }
                            .orEmpty(),
                    )
                    scanMessage = when {
                        candidate.apiReachable && candidate.dashboardReachable ->
                            "Selected ${candidate.host}. API and dashboard were reachable on LAN."
                        candidate.apiReachable ->
                            "Selected ${candidate.host}. API was reachable; dashboard was not found on :${parsedScanDashboardPort ?: Connection.DEFAULT_DASHBOARD_PORT}."
                        candidate.dashboardReachable ->
                            "Selected ${candidate.host}. Dashboard was found, but API was not reachable on :${parsedScanApiPort ?: 8642}."
                        else -> "Selected ${candidate.host}. Enter your API key, then connect."
                    }
                },
            )
        }

        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text(stringResource(R.string.cw_api_key_label)) },
            placeholder = { Text(stringResource(R.string.cw_api_key_placeholder)) },
            singleLine = true,
            supportingText = {
                Text(stringResource(R.string.cw_api_key_hint))
            },
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onToggleApiKeyVisible) {
                    Icon(
                        imageVector = if (apiKeyVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (apiKeyVisible) "Hide API key" else "Show API key",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(
                onGo = { if (canSubmit) onSubmit() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Remote access is part of the main form, not Advanced: the one URL
        // that decides whether the app works outside the house shouldn't be
        // an easter egg. Optional — blank simply means LAN-only for now.
        OutlinedTextField(
            value = tailscaleApiUrl,
            onValueChange = onTailscaleApiUrlChange,
            label = { Text(stringResource(R.string.cw_tailscale_label)) },
            placeholder = { Text("100.x.y.z or http://your-host.ts.net:8642") },
            singleLine = true,
            isError = tailscaleError != null,
            supportingText = {
                Text(
                    tailscaleError ?: if (isTailscaleDetected) {
                        "Tailscale detected on this phone — add your server's " +
                            "Tailscale IP or hostname so Hermes keeps working " +
                            "away from home. API port 8642 and http:// are " +
                            "assumed; use https:// only if your server has TLS."
                    } else {
                        "Lets the phone switch to this URL automatically when it " +
                            "leaves your server's network (API port 8642 and " +
                            "http:// assumed). Editable later in Settings → " +
                            "Connections → Routes."
                    },
                )
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.cw_dashboard),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = effectiveDashboardUrl ?: "Derived after a valid API URL is saved",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (dashboardUrl.isBlank()) {
                        "Manage uses dashboard cookies. Blank means same host on :${Connection.DEFAULT_DASHBOARD_PORT}."
                    } else {
                        "Manage will use this custom dashboard URL for login and dashboard actions."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (tailscaleApiUrl.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.cw_dashboard_routes_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        TextButton(
            onClick = { advancedExpanded = !advancedExpanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (advancedExpanded) stringResource(R.string.cw_advanced_urls_hide) else stringResource(R.string.cw_advanced_urls_show))
            Spacer(Modifier.size(4.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }

        if (advancedExpanded) {
            OutlinedTextField(
                value = dashboardUrl,
                onValueChange = onDashboardUrlChange,
                label = { Text(stringResource(R.string.cw_dashboard_url_override)) },
                placeholder = { Text(stringResource(R.string.cw_dashboard_url_placeholder, Connection.DEFAULT_DASHBOARD_PORT)) },
                singleLine = true,
                isError = dashboardError != null,
                supportingText = {
                    Text(
                        text = dashboardError ?: stringResource(R.string.cw_dashboard_url_supporting)
                    )
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )


            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = scanApiPort,
                    onValueChange = { scanApiPort = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.cw_api_port)) },
                    singleLine = true,
                    isError = parsedScanApiPort == null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = scanDashboardPort,
                    onValueChange = { scanDashboardPort = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.cw_dashboard_port)) },
                    singleLine = true,
                    isError = parsedScanDashboardPort == null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(R.string.cw_port_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (success != null) {
            StandardSetupResultCard(
                result = success,
                onContinue = onComplete,
                onManageSignIn = onManageSignIn,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onBack,
                enabled = !isConnecting,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cw_back))
            }
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.weight(1f),
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.cw_connect_button))
                }
            }
        }
    }
}

private fun scanSummary(found: List<HermesLanDiscoveryResult>): String {
    if (found.isEmpty()) {
        return "No Hermes dashboard/API found on this LAN. Check host firewall and ports; Tailscale URLs can be added under Advanced."
    }
    val both = found.count { it.apiReachable && it.dashboardReachable }
    val apiOnly = found.count { it.apiReachable && !it.dashboardReachable }
    val dashboardOnly = found.count { !it.apiReachable && it.dashboardReachable }
    return buildList {
        add("Found ${found.size} possible Hermes host${if (found.size == 1) "" else "s"}")
        if (both > 0) add("$both with API + dashboard")
        if (apiOnly > 0) add("$apiOnly API only")
        if (dashboardOnly > 0) add("$dashboardOnly dashboard only")
    }.joinToString(". ") + "."
}

@Composable
private fun StandardSetupResultCard(
    result: ConnectionViewModel.StandardApiSetupResult,
    onContinue: () -> Unit,
    onManageSignIn: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.cw_hermes_connected),
                style = MaterialTheme.typography.titleMedium,
            )
            ReadinessLine(
                label = stringResource(R.string.cw_chat),
                detail = if (result.apiReachable) {
                    stringResource(R.string.cw_api_server_ready)
                } else {
                    stringResource(R.string.cw_api_server_not_reachable)
                },
                ok = result.apiReachable,
            )
            ReadinessLine(
                label = stringResource(R.string.cw_manage),
                detail = when {
                    result.dashboardAuthenticated == true -> stringResource(R.string.cw_dashboard_signed_in)
                    result.dashboardSignInRequired -> stringResource(R.string.cw_dashboard_sign_in_required)
                    result.dashboardReachable == true -> stringResource(R.string.cw_dashboard_available)
                    result.dashboardReachable == false -> stringResource(R.string.cw_dashboard_not_reachable)
                    else -> stringResource(R.string.cw_dashboard_will_check)
                },
                ok = result.dashboardAuthenticated == true ||
                    result.dashboardReachable == true && !result.dashboardSignInRequired,
            )
            ReadinessLine(
                label = stringResource(R.string.cw_voice),
                detail = when (result.voiceAvailability) {
                    StandardVoiceAvailability.Ready -> stringResource(R.string.cw_voice_ready)
                    StandardVoiceAvailability.SignInRequired -> stringResource(R.string.cw_voice_sign_in_to_unlock)
                    StandardVoiceAvailability.Unsupported ->
                        stringResource(R.string.cw_voice_no_routes)
                    StandardVoiceAvailability.Unreachable -> stringResource(R.string.cw_voice_after_dashboard)
                    StandardVoiceAvailability.Unknown -> stringResource(R.string.cw_voice_after_connect)
                },
                ok = result.voiceAvailability == StandardVoiceAvailability.Ready,
                neutralWhenFalse = true,
            )
            ReadinessLine(
                label = stringResource(R.string.cw_remote),
                detail = if (result.remoteRouteConfigured) {
                    stringResource(R.string.cw_remote_fallback_ready)
                } else {
                    stringResource(R.string.cw_remote_lan_only)
                },
                ok = result.remoteRouteConfigured,
                neutralWhenFalse = true,
            )
            ReadinessLine(
                label = stringResource(R.string.cw_relay),
                detail = if (result.relayPaired) {
                    stringResource(R.string.cw_relay_paired)
                } else {
                    stringResource(R.string.cw_relay_optional)
                },
                ok = result.relayPaired,
                neutralWhenFalse = true,
            )
            Text(
                text = result.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cw_start_chat))
            }
            if (result.dashboardSignInRequired && onManageSignIn != null) {
                OutlinedButton(
                    onClick = onManageSignIn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cw_sign_in_to_manage))
                }
            }
        }
    }
}

@Composable
private fun ReadinessLine(
    label: String,
    detail: String,
    ok: Boolean,
    neutralWhenFalse: Boolean = false,
) {
    val tint = when {
        ok -> Color(0xFF2E7D32)
        neutralWhenFalse -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = when {
                ok -> Icons.Filled.Check
                neutralWhenFalse -> Icons.Filled.ChevronRight
                else -> Icons.Filled.ErrorOutline
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LanDiscoveryResultRow(
    candidate: HermesLanDiscoveryResult,
    onUse: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onUse),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.host,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        candidate.apiReachable && candidate.dashboardReachable ->
                            "API + Dashboard reachable"
                        candidate.apiReachable ->
                            "API reachable · Dashboard not found"
                        candidate.dashboardReachable ->
                            "Dashboard reachable · API not found"
                        else -> "Reachability unknown"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (candidate.apiReachable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    text = candidate.dashboardUrl ?: candidate.apiUrl,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.endpoints_use_now),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            text = stringResource(R.string.cw_method_pair_code_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.cw_pair_code_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = apiUrl,
            onValueChange = onApiUrlChange,
            label = { Text(stringResource(R.string.cw_api_url_label_field)) },
            placeholder = { Text(stringResource(R.string.cw_api_url_field_placeholder)) },
            singleLine = true,
            isError = apiError != null,
            supportingText = {
                Text(apiError ?: stringResource(R.string.cw_api_url_field_supporting))
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = relayUrl,
            onValueChange = onRelayUrlChange,
            label = { Text(stringResource(R.string.cw_relay_url_label)) },
            placeholder = { Text(stringResource(R.string.cw_relay_url_placeholder)) },
            singleLine = true,
            isError = relayError != null,
            supportingText = {
                Text(relayError ?: stringResource(R.string.cw_relay_url_supporting))
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text(stringResource(R.string.cw_pairing_code_label)) },
            placeholder = { Text(stringResource(R.string.cw_pairing_code_placeholder)) },
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
                Text(stringResource(R.string.cw_back))
            }
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cw_pair_button))
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
            text = stringResource(R.string.cw_method_show_code_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.cw_show_code_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = apiUrl,
            onValueChange = onApiUrlChange,
            label = { Text(stringResource(R.string.cw_api_url_label_field)) },
            placeholder = { Text(stringResource(R.string.cw_api_url_field_placeholder)) },
            singleLine = true,
            isError = apiError != null,
            supportingText = {
                Text(apiError ?: stringResource(R.string.cw_api_url_field_supporting))
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = relayUrl,
            onValueChange = onRelayUrlChange,
            label = { Text(stringResource(R.string.cw_relay_url_label)) },
            placeholder = { Text(stringResource(R.string.cw_relay_url_placeholder)) },
            singleLine = true,
            isError = relayError != null,
            supportingText = {
                Text(relayError ?: stringResource(R.string.cw_relay_url_supporting))
            },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        // Step 1 — show the code
        Text(
            text = stringResource(R.string.cw_step_copy_code),
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
                        contentDescription = stringResource(R.string.cw_copy_pairing_code),
                    )
                }
                IconButton(onClick = onRegenerate) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.cw_generate_new_code),
                    )
                }
            }
        }

        // Step 2 — register on host
        Text(
            text = stringResource(R.string.cw_step_register_code),
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
                    text = "hermes pair --register-code ${pairingCode.ifBlank { "<code>" }}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        if (pairingCode.isNotBlank()) {
                            val cmd = "hermes pair --register-code $pairingCode"
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText("hermes pair command", cmd)
                                    )
                                )
                            }
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.cw_copy_command),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Step 3 — connect
        Text(
            text = stringResource(R.string.cw_step_connect),
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
                Text(stringResource(R.string.cw_back))
            }
            Button(
                onClick = onConnect,
                enabled = canConnect,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cw_connect_button))
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
    standardBusy: Boolean,
    standardSuccess: ConnectionViewModel.StandardApiSetupResult?,
    onBack: () -> Unit,
    onComplete: () -> Unit,
    onManageSignIn: (() -> Unit)?,
    onConfirm: (HermesPairingPayload) -> Unit,
) {
    val context = LocalContext.current
    val confirmScope = rememberCoroutineScope()
    // Per-install acknowledgment for the AllInsecure pair gate
    // ([PairingPreferences.allInsecurePairAckSeen]). Once true, the
    // checkbox below is not rendered at all on subsequent pairings —
    // the user has consented to plain-text pairs and shouldn't keep
    // seeing friction for an already-made choice.
    val allInsecureAckSeen by com.hermesandroid.relay.data.PairingPreferences
        .allInsecurePairAckSeen(context)
        .collectAsState(initial = false)
    // Transient, per-pair tick. Resets every time ConfirmStep is composed
    // for a fresh payload — we don't want a stale tick from a previous
    // scan to carry forward.
    var ackThisPair by remember(payload) { mutableStateOf(false) }

    val transportHint = payload.relay?.transportHint
    val relayUrl = payload.relay?.url
    val isInsecureRelay = relayUrl?.startsWith("ws://") == true

    // Endpoints preview + prefer-role control (ADR 24). `endpoints` is
    // always non-null + non-empty after parseHermesPairingQr, but we still
    // null-safe on the orEmpty() path for defense in depth.
    val endpoints = payload.endpoints.orEmpty()

    // Tri-state security posture across the full endpoint set. A multi-
    // endpoint QR with LAN (ws://) + Tailscale (wss://) is *Mixed* — the
    // app auto-falls back to the secure one, so a blanket "Insecure (dev)"
    // badge from endpoint[0] alone would lie to the user.
    val anySecure = endpoints.any { c ->
        c.relay.url.startsWith("wss://") || c.api.tls ||
            c.relay.transportHint.equals("wss", ignoreCase = true)
    }
    val anyInsecure = endpoints.any { c ->
        c.relay.url.startsWith("ws://") || !c.api.tls ||
            c.relay.transportHint.equals("ws", ignoreCase = true)
    }
    val securityState = when {
        endpoints.isEmpty() ->
            if (isInsecureRelay) TransportSecurityState.AllInsecure
            else TransportSecurityState.AllSecure
        anySecure && anyInsecure -> TransportSecurityState.Mixed
        anySecure -> TransportSecurityState.AllSecure
        else -> TransportSecurityState.AllInsecure
    }
    // Pick the first secure endpoint's label for user-facing copy in the
    // Mixed case ("Tailscale is encrypted..." vs "Public is encrypted...").
    val firstSecureLabel = endpoints
        .firstOrNull { c ->
            c.relay.url.startsWith("wss://") || c.api.tls ||
                c.relay.transportHint.equals("wss", ignoreCase = true)
        }?.displayLabel()
    val firstInsecureLabel = endpoints
        .firstOrNull { c ->
            c.relay.url.startsWith("ws://") ||
                c.relay.transportHint.equals("ws", ignoreCase = true)
        }?.displayLabel()
    val distinctRoles = endpoints.map { it.role }.distinct()
    var preferRole by remember(payload) { mutableStateOf<String?>(null) }
    var preferMenuOpen by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (relayUrl == null) "Confirm Hermes connection" else "Confirm Relay pairing",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = if (relayUrl == null) {
                "This QR configures the standard API/dashboard connection. Pair Relay later for Terminal and Bridge."
            } else {
                "Review the scanned details and choose how long this Relay pairing should last."
            },
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
                if (relayUrl == null) {
                    LabeledLine(
                        label = "Dashboard",
                        value = payload.dashboardUrl
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: Connection.deriveDefaultDashboardUrl(payload.serverUrl)
                            ?: "Derived from API URL",
                        hint = "Manage",
                    )
                    LabeledLine(
                        label = "API key",
                        value = if (payload.key.isBlank()) "Not included" else "Included",
                    )
                }
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
                if (relayUrl != null) {
                    TransportSecurityBadge(
                        state = securityState,
                        size = TransportSecuritySize.Row,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Endpoints preview (ADR 24) — always shown so v1/v2 pairings that
        // synthesize a single candidate still get a "what will connect"
        // summary row. v3 QRs with multiple candidates expose the full list
        // + a Prefer dropdown that promotes the chosen role to priority 0.
        if (relayUrl != null && endpoints.isNotEmpty()) {
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
                        text = stringResource(R.string.cw_routes_count, endpoints.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.cw_routes_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    endpoints.forEachIndexed { index, candidate ->
                        if (index > 0) HorizontalDivider()
                        EndpointPreviewRow(
                            candidate = candidate,
                            index = index,
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
                                text = stringResource(R.string.cw_prefer_label),
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
                                        text = { Text(stringResource(R.string.cw_natural_order)) },
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

        if (relayUrl != null) {
            // TTL picker — flat radio list, no nested dialog
            Text(
                text = stringResource(R.string.cw_keep_pairing_for),
                style = MaterialTheme.typography.titleSmall,
            )
            val transportLabelRes = when {
                isTailscaleDetected -> R.string.cw_transport_tailscale
                transportHint.equals("wss", ignoreCase = true) -> R.string.cw_transport_tls
                transportHint.equals("ws", ignoreCase = true) -> R.string.cw_transport_plain
                else -> null
            }
            if (transportLabelRes != null) {
                Text(
                    text = stringResource(transportLabelRes),
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

            when (securityState) {
                TransportSecurityState.AllInsecure -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                                .copy(alpha = 0.4f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.cw_insecure_relay_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = stringResource(R.string.cw_insecure_relay_trust),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                    // Per-install Tier-1 gate: only render the checkbox when the
                    // user has never acknowledged an AllInsecure pair on this
                    // install. Once they have, we never show it again — the
                    // warning card above stays, but the gate is lifted.
                    if (!allInsecureAckSeen) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = ackThisPair,
                                onCheckedChange = { ackThisPair = it },
                            )
                            Text(
                                text = stringResource(R.string.cw_insecure_ack),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
                TransportSecurityState.Mixed -> {
                    // Amber-tinted informational card. The secure route is the
                    // safety net — spell that out explicitly so users stop
                    // reading "some plain" as "all plain".
                    val plainLabel = firstInsecureLabel ?: "LAN"
                    val secureLabel = firstSecureLabel ?: "Tailscale"
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF9A825).copy(alpha = 0.12f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.cw_mixed_route_plain, plainLabel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.cw_mixed_route_secure, secureLabel, plainLabel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.cw_mixed_safe),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                TransportSecurityState.AllSecure -> {
                    // No warning block — every route is TLS.
                }
            }
        }

        if (relayUrl == null && standardBusy) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.cw_connecting_to_hermes),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.cw_connecting_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (relayUrl == null && standardSuccess != null) {
            StandardSetupResultCard(
                result = standardSuccess,
                onContinue = onComplete,
                onManageSignIn = onManageSignIn,
            )
        }

        // Gate for the absolute-boundary AllInsecure case only. Mixed and
        // AllSecure stay one-tap. Satisfied when either (a) the user has
        // previously ack'd an AllInsecure pair on this install (per-install,
        // never expires), or (b) they've ticked the checkbox for this pair.
        val gateIsSatisfied = relayUrl == null || when (securityState) {
            TransportSecurityState.AllInsecure -> allInsecureAckSeen || ackThisPair
            else -> true
        }

        if (standardSuccess == null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onBack,
                    enabled = !standardBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cw_back))
                }
                Button(
                    onClick = {
                        // Persist the per-install ack the first time an
                        // AllInsecure pair goes through via the checkbox path.
                        // Future AllInsecure pairs skip the checkbox entirely.
                        if (securityState == TransportSecurityState.AllInsecure &&
                            ackThisPair &&
                            !allInsecureAckSeen
                        ) {
                            confirmScope.launch {
                                com.hermesandroid.relay.data.PairingPreferences
                                    .setAllInsecurePairAckSeen(context, true)
                            }
                        }
                        val effective = preferRole
                            ?.let { reorderByPreferredRole(payload, it) }
                            ?: payload
                        onConfirm(effective)
                    },
                    enabled = gateIsSatisfied && !standardBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (relayUrl == null && standardBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(if (relayUrl == null) stringResource(R.string.cw_connect_button) else stringResource(R.string.cw_pair_button))
                    }
                }
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
            text = if (error == null) stringResource(R.string.cw_pairing_title) else stringResource(R.string.cw_pairing_failed),
            style = MaterialTheme.typography.headlineSmall,
        )

        if (error == null) {
            CircularProgressIndicator()
            Text(
                text = when (authState) {
                    is AuthState.Pairing -> stringResource(R.string.cw_negotiating_relay)
                    is AuthState.Paired -> stringResource(R.string.cw_paired_opening_chat)
                    else -> stringResource(R.string.cw_connecting_to_relay)
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
                    Text(stringResource(R.string.cw_back))
                }
                Button(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cw_retry))
                }
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cw_cancel_button))
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
    index: Int,
    isPreferred: Boolean,
) {
    // Per-row security derived from the same three signals as the overall
    // securityState computation — scheme, tls flag, transportHint.
    val isSecure = candidate.relay.url.startsWith("wss://") ||
        candidate.api.tls ||
        candidate.relay.transportHint.equals("wss", ignoreCase = true)
    val ordinalLabel = when (index) {
        0 -> "1st choice"
        1 -> "Fallback"
        else -> "Fallback $index"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                SoftPill(
                    text = if (isSecure) "Secure" else "Plain",
                    fg = if (isSecure) Color(0xFF2E7D32) else Color(0xFFF9A825),
                )
                if (isPreferred) {
                    SoftPill(
                        text = "Preferred",
                        fg = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = "${candidate.api.host}:${candidate.api.port}" +
                    (candidate.relay.transportHint?.let { " \u00b7 $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        // Ordinal chip pinned to the right — "1st choice" / "Fallback" /
        // "Fallback N" replaces the raw `p0/p1/p2` from the old UI.
        SoftPill(
            text = ordinalLabel,
            fg = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Compact pill used by [EndpointPreviewRow] — matches the "Preferred" soft
 * chip style so the row reads as a row of related chips rather than a mix
 * of primary + secondary visual weights.
 */
@Composable
private fun SoftPill(
    text: String,
    fg: Color,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = fg.copy(alpha = 0.14f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Reorder the endpoints array so the chosen role lands at priority 0.
 * Priority values are renumbered to match the new order — this matters at
 * persist time because `setDeviceEndpoints` stores the list verbatim and
 * downstream [com.hermesandroid.relay.network.shared.EndpointResolver] trusts the
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
