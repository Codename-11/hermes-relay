package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Result state for a single step's test action (API reachability,
 * relay health probe) and for the final pairing attempt.
 */
sealed class StepResult {
    data class Success(val message: String) : StepResult()
    data class Error(val message: String) : StepResult()
}

/**
 * Full-screen 4-step walkthrough that guides the user through configuring
 * the API server, the relay server, entering a pairing code, and finally
 * performing the pair + verify handshake. Each intermediate step has its
 * own test-in-place action so the user can catch mistakes early before
 * the final pairing attempt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingWalkthroughDialog(
    connectionViewModel: ConnectionViewModel,
    onDismiss: () -> Unit,
) {
    // --- Seed state from the current ConnectionViewModel snapshot --------
    val initialApiUrl by connectionViewModel.apiServerUrl.collectAsState()
    val initialRelayUrl by connectionViewModel.relayUrl.collectAsState()
    val initialInsecure by connectionViewModel.insecureMode.collectAsState()

    var currentStep by remember { mutableStateOf(0) }

    var apiUrl by remember { mutableStateOf(initialApiUrl) }
    var apiKey by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var apiTesting by remember { mutableStateOf(false) }
    var apiTestResult by remember { mutableStateOf<StepResult?>(null) }

    var relayUrl by remember { mutableStateOf(initialRelayUrl) }
    var insecureAllowed by remember { mutableStateOf(initialInsecure) }
    var relayTesting by remember { mutableStateOf(false) }
    var relayTestResult by remember { mutableStateOf<StepResult?>(null) }

    var pairingCode by remember { mutableStateOf("") }
    val trimmedCode = pairingCode.trim().uppercase()

    var pairingInProgress by remember { mutableStateOf(false) }
    var pairingAttempt by remember { mutableStateOf(0) }
    var pairingResult by remember { mutableStateOf<StepResult?>(null) }

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // --- Per-step validation gates for enabling the Next button ----------
    val step0Valid = apiUrl.isNotBlank()
    val step1Valid = relayUrl.isNotBlank() &&
        (relayUrl.startsWith("ws://") || relayUrl.startsWith("wss://"))
    val step2Valid = trimmedCode.length in 4..12 &&
        trimmedCode.all { it.isLetterOrDigit() }

    // --- Observe auth state after triggering a pair attempt --------------
    LaunchedEffect(pairingAttempt) {
        if (pairingAttempt == 0) return@LaunchedEffect
        try {
            val terminal = withTimeout(15_000) {
                connectionViewModel.authState.first {
                    it is AuthState.Paired || it is AuthState.Failed
                }
            }
            when (terminal) {
                is AuthState.Paired -> {
                    pairingInProgress = false
                    pairingResult = StepResult.Success("Paired successfully!")
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                is AuthState.Failed -> {
                    pairingInProgress = false
                    pairingResult = StepResult.Error(
                        "Server rejected code: ${terminal.reason}"
                    )
                }
                else -> {
                    pairingInProgress = false
                }
            }
        } catch (_: TimeoutCancellationException) {
            pairingInProgress = false
            pairingResult = StepResult.Error(
                "Timed out waiting for relay. Check that the relay server is running and the URL + code are correct."
            )
        }
    }

    Dialog(
        onDismissRequest = { if (!pairingInProgress) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = !pairingInProgress,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Set up pairing") },
                        navigationIcon = {
                            // Left-side back button doubles as a visual
                            // anchor. Uses the same "close" semantics as
                            // the right-side X — both exit the walkthrough.
                            IconButton(
                                onClick = onDismiss,
                                enabled = !pairingInProgress,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Close",
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = onDismiss,
                                enabled = !pairingInProgress,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Close",
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                },
                bottomBar = {
                    WalkthroughBottomBar(
                        currentStep = currentStep,
                        canGoBack = currentStep > 0 && !pairingInProgress,
                        canGoNext = when (currentStep) {
                            0 -> step0Valid
                            1 -> step1Valid
                            2 -> step2Valid
                            else -> false
                        },
                        onBack = { currentStep = (currentStep - 1).coerceAtLeast(0) },
                        onNext = { currentStep = (currentStep + 1).coerceAtMost(3) },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    StepIndicator(
                        currentStep = currentStep,
                        stepsCompleted = currentStep, // steps 0..currentStep-1 treated as complete
                    )

                    // --- Body ------------------------------------------------
                    when (currentStep) {
                        0 -> ApiServerStep(
                            apiUrl = apiUrl,
                            onApiUrlChange = { apiUrl = it; apiTestResult = null },
                            apiKey = apiKey,
                            onApiKeyChange = { apiKey = it },
                            apiKeyVisible = apiKeyVisible,
                            onToggleApiKeyVisibility = { apiKeyVisible = !apiKeyVisible },
                            testing = apiTesting,
                            result = apiTestResult,
                            onTest = {
                                apiTesting = true
                                apiTestResult = null
                                connectionViewModel.updateApiServerUrl(apiUrl)
                                if (apiKey.isNotBlank()) {
                                    connectionViewModel.updateApiKey(apiKey)
                                }
                                connectionViewModel.testApiConnection { success ->
                                    apiTesting = false
                                    apiTestResult = if (success) {
                                        StepResult.Success("Reachable")
                                    } else {
                                        StepResult.Error("Unreachable — check URL and port")
                                    }
                                }
                            },
                        )

                        1 -> RelayServerStep(
                            relayUrl = relayUrl,
                            onRelayUrlChange = {
                                relayUrl = it
                                relayTestResult = null
                                // Auto-enable insecure if user types ws://
                                if (it.startsWith("ws://")) insecureAllowed = true
                            },
                            insecureAllowed = insecureAllowed,
                            onInsecureChange = { insecureAllowed = it },
                            testing = relayTesting,
                            result = relayTestResult,
                            onTest = {
                                relayTesting = true
                                relayTestResult = null
                                scope.launch {
                                    val ok = probeRelayHealth(relayUrl)
                                    relayTesting = false
                                    relayTestResult = if (ok) {
                                        StepResult.Success("Relay reachable")
                                    } else {
                                        StepResult.Error(
                                            "Unreachable — check URL, port, and that the relay server is running"
                                        )
                                    }
                                }
                            },
                        )

                        2 -> PairingCodeStep(
                            code = pairingCode,
                            onCodeChange = { new ->
                                // Auto-uppercase so the monospace preview
                                // always looks consistent, and so validation
                                // doesn't drift based on what the IME sent.
                                pairingCode = new.uppercase()
                            },
                            isActive = true,
                            onImeNext = {
                                if (step2Valid) currentStep = 3
                            },
                        )

                        3 -> PairAndVerifyStep(
                            apiUrl = apiUrl,
                            apiKeySet = apiKey.isNotBlank(),
                            relayUrl = relayUrl,
                            pairingCode = trimmedCode,
                            inProgress = pairingInProgress,
                            result = pairingResult,
                            onPairClick = {
                                pairingInProgress = true
                                pairingResult = null
                                connectionViewModel.updateApiServerUrl(apiUrl)
                                if (apiKey.isNotBlank()) {
                                    connectionViewModel.updateApiKey(apiKey)
                                }
                                connectionViewModel.updateRelayUrl(relayUrl)
                                connectionViewModel.setInsecureMode(
                                    insecureAllowed || relayUrl.startsWith("ws://")
                                )
                                connectionViewModel.authManager
                                    .applyServerIssuedCodeAndReset(trimmedCode)
                                connectionViewModel.disconnectRelay()
                                connectionViewModel.connectRelay(relayUrl)
                                pairingAttempt += 1
                            },
                            onDone = onDismiss,
                            onRetryCode = {
                                pairingResult = null
                                currentStep = 2
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step indicator
// ---------------------------------------------------------------------------

@Composable
private fun StepIndicator(
    currentStep: Int,
    stepsCompleted: Int,
) {
    val labels = listOf("API", "Relay", "Code", "Pair")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { index, _ ->
                val state = when {
                    index < stepsCompleted -> StepDotState.Completed
                    index == currentStep -> StepDotState.Current
                    else -> StepDotState.Future
                }
                StepDot(state = state, number = index + 1)
                if (index != labels.lastIndex) {
                    val connectorActive = index < stepsCompleted
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(
                                if (connectorActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            labels.forEachIndexed { index, label ->
                val color = when {
                    index == currentStep -> MaterialTheme.colorScheme.primary
                    index < stepsCompleted -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = if (index == currentStep) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.width(56.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

private enum class StepDotState { Completed, Current, Future }

@Composable
private fun StepDot(state: StepDotState, number: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val outline = MaterialTheme.colorScheme.outline
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val bg = when (state) {
        StepDotState.Completed, StepDotState.Current -> primary
        StepDotState.Future -> surfaceVariant
    }
    val border = when (state) {
        StepDotState.Future -> outline
        else -> primary
    }
    val contentColor = when (state) {
        StepDotState.Completed, StepDotState.Current -> onPrimary
        StepDotState.Future -> onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (state == StepDotState.Completed) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // Outline ring for visual separation against light surfaces
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Transparent),
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, border),
                modifier = Modifier.size(28.dp),
            ) {}
        }
    }
}

// ---------------------------------------------------------------------------
// Step 1 — API server
// ---------------------------------------------------------------------------

@Composable
private fun ApiServerStep(
    apiUrl: String,
    onApiUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onToggleApiKeyVisibility: () -> Unit,
    testing: Boolean,
    result: StepResult?,
    onTest: () -> Unit,
) {
    StepBody {
        Text(
            text = "API Server",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Chat connects directly to your Hermes API server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = apiUrl,
            onValueChange = onApiUrlChange,
            label = { Text("API Server URL") },
            placeholder = { Text("http://your-server:8642") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("API Key (optional)") },
            singleLine = true,
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onToggleApiKeyVisibility) {
                    Icon(
                        imageVector = if (apiKeyVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (apiKeyVisible) "Hide key" else "Show key",
                    )
                }
            },
            supportingText = {
                Text("Only needed if Hermes is configured with API_SERVER_KEY")
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onTest,
            enabled = apiUrl.isNotBlank() && !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(12.dp))
                Text("Testing…")
            } else {
                Text("Test connection")
            }
        }

        ResultRow(result)
    }
}

// ---------------------------------------------------------------------------
// Step 2 — Relay server
// ---------------------------------------------------------------------------

@Composable
private fun RelayServerStep(
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    insecureAllowed: Boolean,
    onInsecureChange: (Boolean) -> Unit,
    testing: Boolean,
    result: StepResult?,
    onTest: () -> Unit,
) {
    StepBody {
        Text(
            text = "Relay Server",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "The relay server brokers terminal and bridge channels. Usually same host as the API, different port (8767).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = relayUrl,
            onValueChange = onRelayUrlChange,
            label = { Text("Relay URL") },
            placeholder = { Text("wss://your-server:8767") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Allow insecure (ws://)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Only for local dev/testing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = insecureAllowed,
                onCheckedChange = onInsecureChange,
            )
        }

        Button(
            onClick = onTest,
            enabled = relayUrl.isNotBlank() && !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(12.dp))
                Text("Probing…")
            } else {
                Text("Test reachability")
            }
        }

        ResultRow(result)
    }
}

// ---------------------------------------------------------------------------
// Step 3 — Pairing code
// ---------------------------------------------------------------------------

@Composable
private fun PairingCodeStep(
    code: String,
    onCodeChange: (String) -> Unit,
    isActive: Boolean,
    onImeNext: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isActive) {
        if (isActive) {
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus requester not yet attached — the user can tap the
                // field manually. Happens when this step is composed off
                // screen during transitions.
            }
        }
    }

    StepBody {
        Text(
            text = "Pairing Code",
            style = MaterialTheme.typography.headlineSmall,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Run `hermes-pair` in a shell OR `/hermes-relay-pair` in any Hermes chat session on your server. Enter the 6-character code it prints here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("Pairing code") },
            placeholder = { Text("ABC123") },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(
                onNext = { onImeNext() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
}

// ---------------------------------------------------------------------------
// Step 4 — Pair & verify
// ---------------------------------------------------------------------------

@Composable
private fun PairAndVerifyStep(
    apiUrl: String,
    apiKeySet: Boolean,
    relayUrl: String,
    pairingCode: String,
    inProgress: Boolean,
    result: StepResult?,
    onPairClick: () -> Unit,
    onDone: () -> Unit,
    onRetryCode: () -> Unit,
) {
    StepBody {
        Text(
            text = "Pair & Verify",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Review your settings and connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SummaryRow("API Server", apiUrl.ifBlank { "—" })
                SummaryRow("API Key", if (apiKeySet) "Set" else "Not set")
                SummaryRow("Relay Server", relayUrl.ifBlank { "—" })
                SummaryRow("Pairing Code", pairingCode.ifBlank { "—" })
            }
        }

        when (val r = result) {
            is StepResult.Success -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = r.message,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF4CAF50),
                        )
                    }
                    Button(
                        onClick = onDone,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    ) {
                        Text("Done")
                    }
                }
            }

            is StepResult.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = r.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onRetryCode,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Back to code step")
                    }
                    Button(
                        onClick = onPairClick,
                        enabled = !inProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    ) {
                        Text("Try again")
                    }
                }
            }

            null -> {
                Button(
                    onClick = onPairClick,
                    enabled = !inProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    if (inProgress) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Pairing…")
                    } else {
                        Text("Pair Now")
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Shared step body + result row
// ---------------------------------------------------------------------------

@Composable
private fun ColumnScopedStepBody(content: @Composable () -> Unit) {
    // Private indirection kept so StepBody stays terse at call sites.
    content()
}

@Composable
private fun StepBody(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
    }
}

@Composable
private fun ResultRow(result: StepResult?) {
    when (result) {
        is StepResult.Success -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4CAF50),
                )
            }
        }
        is StepResult.Error -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        null -> { /* nothing to render */ }
    }
}

// ---------------------------------------------------------------------------
// Bottom navigation bar
// ---------------------------------------------------------------------------

@Composable
private fun WalkthroughBottomBar(
    currentStep: Int,
    canGoBack: Boolean,
    canGoNext: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onBack,
                enabled = canGoBack,
                modifier = Modifier.weight(1f),
            ) {
                Text("Back")
            }
            // Step 3 hides the Next button — it owns its own primary action
            // ("Pair Now" / "Done") so the bottom bar doesn't compete with it.
            if (currentStep < 3) {
                Button(
                    onClick = onNext,
                    enabled = canGoNext,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text("Next")
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Relay health probe
// ---------------------------------------------------------------------------

/**
 * Probes the relay's HTTP /health endpoint. The relay exposes WSS at
 * `/ws` but also serves plain HTTP routes (health, pairing) on the same
 * port, so we can verify the server is up without going through the full
 * auth handshake — which would need a pairing code this step doesn't
 * have yet.
 *
 * Returns true on 2xx, false on any error (timeout, DNS, refused,
 * non-2xx, malformed URL).
 */
private suspend fun probeRelayHealth(wsUrl: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val trimmed = wsUrl.trim()
        if (trimmed.isBlank()) return@withContext false
        val uri = java.net.URI(trimmed)
        val scheme = when (uri.scheme) {
            "ws" -> "http"
            "wss" -> "https"
            else -> return@withContext false
        }
        val port = if (uri.port > 0) uri.port else (if (scheme == "https") 443 else 80)
        val healthUrl = "$scheme://${uri.host}:$port/health"
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder().url(healthUrl).get().build()
        client.newCall(request).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }
}
