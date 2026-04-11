package com.hermesandroid.relay.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.ui.components.ConnectionStatusBadge
import com.hermesandroid.relay.ui.components.QrPairingScanner
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit

private sealed interface TestResult {
    data object Success : TestResult
    data class Failure(val message: String) : TestResult
}

/** Page identifiers for dynamic onboarding flow. */
private enum class OnboardingPage { Welcome, Chat, Terminal, Bridge, Connect, Relay }

@Composable
fun OnboardingScreen(
    onComplete: (
        apiServerUrl: String,
        apiKey: String,
        relayUrl: String,
        relayPairingCode: String?
    ) -> Unit
) {
    val context = LocalContext.current
    val relayEnabled by FeatureFlags.relayEnabled(context).collectAsState(initial = FeatureFlags.isDevBuild)

    // Build page list dynamically based on feature flags
    val pages = remember(relayEnabled) {
        buildList {
            add(OnboardingPage.Welcome)
            add(OnboardingPage.Chat)
            if (relayEnabled) {
                add(OnboardingPage.Terminal)
                add(OnboardingPage.Bridge)
            }
            add(OnboardingPage.Connect)
            if (relayEnabled) {
                add(OnboardingPage.Relay)
            }
        }
    }
    val pageCount = pages.size
    val lastPage = pageCount - 1

    val pagerState = rememberPagerState(pageCount = { pageCount })
    val coroutineScope = rememberCoroutineScope()
    var apiServerUrl by rememberSaveable { mutableStateOf("http://localhost:8642") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var relayUrl by rememberSaveable { mutableStateOf("wss://localhost:8767") }
    // When a scanned QR carries a relay block, we capture the one-shot
    // pairing code here and hand it to AuthManager during onComplete. It's
    // consumed on first successful auth, not persisted.
    var serverIssuedRelayCode by rememberSaveable { mutableStateOf<String?>(null) }
    var showSkipConfirm by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (showSkipConfirm) {
            AlertDialog(
                onDismissRequest = { showSkipConfirm = false },
                title = { Text("Skip setup?") },
                text = {
                    Text("You can configure your server connection later in Settings. Without an API key, your connection will not be authenticated.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showSkipConfirm = false
                        onComplete(apiServerUrl, apiKey, relayUrl, serverIssuedRelayCode)
                    }) {
                        Text("Skip anyway")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSkipConfirm = false }) {
                        Text("Go back")
                    }
                }
            )
        }

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar with Skip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Skip button - always visible
                TextButton(onClick = { showSkipConfirm = true }) {
                    Text(
                        text = "Skip",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { pageIndex ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when (pages[pageIndex]) {
                        OnboardingPage.Welcome -> WelcomePage()
                        OnboardingPage.Chat -> ChatPage()
                        OnboardingPage.Terminal -> TerminalPage()
                        OnboardingPage.Bridge -> BridgePage()
                        OnboardingPage.Connect -> ConnectPage(
                            apiServerUrl = apiServerUrl,
                            onApiServerUrlChange = { apiServerUrl = it },
                            apiKey = apiKey,
                            onApiKeyChange = { apiKey = it },
                            onRelayPairingDetected = { scannedUrl, scannedCode ->
                                // QR carried a relay block — configure the
                                // relay side too so the user doesn't have
                                // to type a second URL. The pairing code
                                // rides along to AuthManager via onComplete.
                                relayUrl = scannedUrl
                                serverIssuedRelayCode = scannedCode
                            }
                        )
                        OnboardingPage.Relay -> RelayPage(
                            relayUrl = relayUrl,
                            onRelayUrlChange = { relayUrl = it }
                        )
                    }
                }
            }

            // Bottom section: page indicator + navigation buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Page indicator
                PageIndicator(
                    pageCount = pageCount,
                    currentPage = pagerState.currentPage
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Navigation buttons row: Back (left) + Next/Get Started (right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button — only visible when not on first page
                    AnimatedVisibility(
                        visible = pagerState.currentPage > 0,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        ) {
                            Text(text = "Back")
                        }
                    }

                    // Invisible spacer when Back is hidden so Next/Get Started stays right-aligned
                    if (pagerState.currentPage == 0) {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    // Next / Get Started button
                    if (pagerState.currentPage < lastPage) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        ) {
                            Text(text = "Next")
                        }
                    } else {
                        Button(
                            onClick = { onComplete(apiServerUrl, apiKey, relayUrl, serverIssuedRelayCode) },
                            enabled = apiServerUrl.isNotBlank()
                        ) {
                            Text(text = "Get Started")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    val context = LocalContext.current
    OnboardingPage(
        icon = Icons.Outlined.RocketLaunch,
        title = "Hermes-Relay",
        description = "Your Hermes agent, in your pocket."
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "hermes-agent.nousresearch.com",
            style = MaterialTheme.typography.bodySmall.copy(
                textDecoration = TextDecoration.Underline
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://hermes-agent.nousresearch.com")))
            }
        )
    }
}

@Composable
private fun ChatPage() {
    OnboardingPage(
        icon = Icons.Outlined.Forum,
        title = "Chat",
        description = "Talk to any Hermes agent profile with real-time streaming responses, tool progress, and full markdown."
    )
}

@Composable
private fun TerminalPage() {
    OnboardingPage(
        icon = Icons.Outlined.Terminal,
        title = "Terminal",
        description = "Secure remote shell access to your server via tmux. Coming soon."
    )
}

@Composable
private fun BridgePage() {
    OnboardingPage(
        icon = Icons.Outlined.PhonelinkSetup,
        title = "Bridge",
        description = "Let your agent control your device — taps, typing, screenshots, and automation. Coming soon."
    )
}

@Composable
private fun ConnectPage(
    apiServerUrl: String,
    onApiServerUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onRelayPairingDetected: (relayUrl: String, relayCode: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var isTesting by rememberSaveable { mutableStateOf(false) }
    var showApiKeyHelp by rememberSaveable { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showQrScanner = true
        } else {
            Toast.makeText(context, "Camera permission needed to scan QR codes", Toast.LENGTH_SHORT).show()
        }
    }

    // API Key help dialog
    if (showApiKeyHelp) {
        AlertDialog(
            onDismissRequest = { showApiKeyHelp = false },
            title = { Text("Where to find your API key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Your API key is the API_SERVER_KEY value from your Hermes server configuration.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Location: ~/.hermes/.env",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "If you haven't set a key yet, add one to your server config — it secures all API communication.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Default port: 8642",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val helpContext = LocalContext.current
                    Text(
                        text = "View full setup guide",
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            helpContext.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://hermes-agent.nousresearch.com"))
                            )
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showApiKeyHelp = false }) {
                    Text("Got it")
                }
            }
        )
    }

    OnboardingPage(
        icon = Icons.Outlined.Dns,
        title = "Connect",
        description = "Enter your Hermes server address and API key to connect securely."
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // API Server URL
        OutlinedTextField(
            value = apiServerUrl,
            onValueChange = {
                onApiServerUrlChange(it)
                testResult = null
            },
            label = { Text("API Server URL") },
            placeholder = { Text("http://your-server:8642") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // API Key with help icon
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                onApiKeyChange(it)
                testResult = null
            },
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("API Key")
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = "API key help",
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { showApiKeyHelp = true },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            placeholder = { Text("Your API_SERVER_KEY value") },
            singleLine = true,
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (apiKeyVisible) "Hide" else "Show"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        // "How do I set this up?" clickable text
        Text(
            text = "How do I set this up?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.Start)
                .clickable { showApiKeyHelp = true }
                .padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Test Connection button + result
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (apiServerUrl.isNotBlank()) {
                        isTesting = true
                        testResult = null
                        coroutineScope.launch {
                            testResult = performHealthCheck(apiServerUrl, apiKey)
                            isTesting = false
                        }
                    }
                },
                enabled = apiServerUrl.isNotBlank() && !isTesting
            ) {
                Text("Test Connection")
            }

            if (isTesting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            testResult?.let { result ->
                when (result) {
                    is TestResult.Success -> {
                        ConnectionStatusBadge(
                            isConnected = true,
                            modifier = Modifier.size(14.dp),
                            size = 14.dp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is TestResult.Failure -> {
                        ConnectionStatusBadge(
                            isConnected = false,
                            modifier = Modifier.size(14.dp),
                            size = 14.dp
                        )
                    }
                }
            }
        }

        // Error message text below the row
        testResult?.let { result ->
            if (result is TestResult.Failure) {
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(top = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // QR code scanning option
        OutlinedButton(
            onClick = {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Filled.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Scan QR Code")
        }

        Text(
            text = "Run hermes-pair on your server to generate a QR code",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Start)
        )
    }

    // QR Scanner overlay
    if (showQrScanner) {
        QrPairingScanner(
            onPairingDetected = { payload ->
                onApiServerUrlChange(payload.serverUrl)
                onApiKeyChange(payload.key)
                // If the QR also carries relay credentials, propagate them
                // up so the relay URL + pairing code get applied before
                // onComplete runs.
                payload.relay?.let { r ->
                    onRelayPairingDetected(r.url, r.code)
                }
                showQrScanner = false
                // Auto-trigger test
                isTesting = true
                testResult = null
                coroutineScope.launch {
                    testResult = performHealthCheck(payload.serverUrl, payload.key)
                    isTesting = false
                }
            },
            onDismiss = { showQrScanner = false }
        )
    }
}

@Composable
private fun RelayPage(
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit
) {
    OnboardingPage(
        icon = Icons.Outlined.Hub,
        title = "Relay Server",
        description = "Optional — for Bridge and Terminal features. You can set this up later in Settings."
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = relayUrl,
            onValueChange = onRelayUrlChange,
            label = { Text("Relay URL (optional)") },
            placeholder = { Text("wss://your-server:8767") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Needed for Bridge and Terminal features",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Performs a direct OkHttp health check against the Hermes API server.
 * Returns a [TestResult] with either success or a descriptive error message.
 */
private suspend fun performHealthCheck(apiServerUrl: String, apiKey: String): TestResult =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        try {
            val builder = Request.Builder()
                .url("$apiServerUrl/health")
                .get()
            if (apiKey.isNotBlank()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            val request = builder.build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> TestResult.Success
                    response.code == 401 -> TestResult.Failure("Unauthorized \u2014 check your API key")
                    else -> TestResult.Failure("Server returned HTTP ${response.code}")
                }
            }
        } catch (e: SSLException) {
            // User entered https:// but server runs plain HTTP, or vice versa
            if (apiServerUrl.startsWith("https://", ignoreCase = true)) {
                TestResult.Failure("TLS handshake failed \u2014 try http:// if your server doesn't use HTTPS")
            } else {
                TestResult.Failure("SSL error: ${e.message}")
            }
        } catch (e: ConnectException) {
            TestResult.Failure("Connection refused \u2014 check the URL and port")
        } catch (e: UnknownHostException) {
            TestResult.Failure("Server not found \u2014 check the hostname")
        } catch (e: SocketTimeoutException) {
            TestResult.Failure("Connection timed out \u2014 is the server running?")
        } catch (e: IOException) {
            val msg = e.message ?: ""
            when {
                msg.contains("401") -> TestResult.Failure("Unauthorized \u2014 check your API key")
                msg.contains("tls", ignoreCase = true) || msg.contains("ssl", ignoreCase = true) ->
                    TestResult.Failure("TLS error \u2014 try http:// if your server doesn't use HTTPS")
                else -> TestResult.Failure("Connection failed: $msg")
            }
        } catch (e: Exception) {
            TestResult.Failure("Connection failed: ${e.message}")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

@Preview(showSystemUi = true)
@Composable
private fun OnboardingScreenPreview() {
    HermesRelayTheme {
        OnboardingScreen(
            onComplete = { _, _, _, _ -> }
        )
    }
}

@Preview(showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingScreenDarkPreview() {
    HermesRelayTheme(themePreference = "dark") {
        OnboardingScreen(
            onComplete = { _, _, _, _ -> }
        )
    }
}
