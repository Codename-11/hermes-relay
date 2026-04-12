package com.hermesandroid.relay.ui.components

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Shared three-step pairing wizard used by both onboarding (first run) and
 * Settings → Connection (re-pair / change server). Replaces the old split
 * between [com.hermesandroid.relay.ui.onboarding.OnboardingScreen]'s
 * `ConnectPage` (which discarded relay credentials) and the dialog-based
 * `PairingWalkthroughDialog` so there is one canonical pair flow that always
 * applies the full QR payload — relay code, TTL, grants, cert pin, the lot.
 *
 * Steps:
 *
 *  1. **Scan** — camera + QR scanner. Single primary action; falls through
 *     to manual entry if the user can't scan.
 *  2. **Confirm** — show what was scanned, transport security badge, TTL
 *     picker (radio list), and an inline insecure note when the relay is
 *     plain `ws://`. Tap Pair to apply.
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

    var step by remember { mutableStateOf(WizardStep.Scan) }
    var pendingPayload by remember { mutableStateOf<HermesPairingPayload?>(null) }
    var ttlSeconds by remember { mutableStateOf(PairingPreferencesDefault) }
    var showQrScanner by remember { mutableStateOf(false) }
    var verifyError by remember { mutableStateOf<String?>(null) }
    var verifyAttempt by remember { mutableStateOf(0) }

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
    LaunchedEffect(verifyAttempt) {
        if (verifyAttempt == 0) return@LaunchedEffect
        verifyError = null
        try {
            val terminal = withTimeout(15_000) {
                connectionViewModel.authState.first {
                    it is AuthState.Paired || it is AuthState.Failed
                }
            }
            when (terminal) {
                is AuthState.Paired -> onComplete()
                is AuthState.Failed -> verifyError =
                    "Server rejected the pairing: ${terminal.reason}"
                else -> verifyError = "Pairing did not complete"
            }
        } catch (_: TimeoutCancellationException) {
            verifyError = "Timed out waiting for the relay. " +
                "Check that the relay is running and the URL is correct."
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WizardStepIndicator(currentStep = step.ordinal, totalSteps = WizardStep.entries.size)

        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "wizard-step",
        ) { current ->
            when (current) {
                WizardStep.Scan -> ScanStep(
                    onLaunchScanner = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onSkip = if (showSkip) onCancel else null,
                )

                WizardStep.Confirm -> {
                    val payload = pendingPayload
                    if (payload == null) {
                        // Defensive — shouldn't happen because we only enter
                        // Confirm after a successful scan, but if it does,
                        // bounce back to Scan instead of crashing.
                        LaunchedEffect(Unit) { step = WizardStep.Scan }
                    } else {
                        ConfirmStep(
                            payload = payload,
                            ttlSeconds = ttlSeconds,
                            onTtlChange = { ttlSeconds = it },
                            isTailscaleDetected = isTailscaleDetected,
                            onBack = {
                                pendingPayload = null
                                step = WizardStep.Scan
                            },
                            onConfirm = {
                                connectionViewModel.applyPairingPayload(payload, ttlSeconds)
                                step = WizardStep.Verify
                                verifyAttempt += 1
                            },
                        )
                    }
                }

                WizardStep.Verify -> VerifyStep(
                    authState = authState,
                    error = verifyError,
                    onRetry = {
                        verifyError = null
                        pendingPayload?.let {
                            connectionViewModel.applyPairingPayload(it, ttlSeconds)
                            verifyAttempt += 1
                        }
                    },
                    onBack = {
                        // User wants to re-scan or pick a new TTL.
                        verifyError = null
                        step = WizardStep.Confirm
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

private enum class WizardStep { Scan, Confirm, Verify }

@Composable
private fun WizardStepIndicator(currentStep: Int, totalSteps: Int) {
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
            0 -> "Step 1 of 3 — Scan pairing QR"
            1 -> "Step 2 of 3 — Confirm pairing"
            else -> "Step 3 of 3 — Verify"
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ScanStep(
    onLaunchScanner: () -> Unit,
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
            text = "Run /hermes-relay-pair in any Hermes chat, or hermes-pair on your server, " +
                "to generate a pairing QR. One scan configures chat, the relay, and your session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = onLaunchScanner,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Scan QR Code")
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
private fun ConfirmStep(
    payload: HermesPairingPayload,
    ttlSeconds: Long,
    onTtlChange: (Long) -> Unit,
    isTailscaleDetected: Boolean,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val transportHint = payload.relay?.transportHint
    val relayUrl = payload.relay?.url
    val isInsecureRelay = relayUrl?.startsWith("ws://") == true

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
                LabeledLine("API server", payload.serverUrl)
                if (relayUrl != null) {
                    LabeledLine("Relay", relayUrl)
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
                onClick = onConfirm,
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
private fun LabeledLine(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
