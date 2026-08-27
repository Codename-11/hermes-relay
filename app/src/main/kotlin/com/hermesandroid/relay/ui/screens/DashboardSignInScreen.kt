package com.hermesandroid.relay.ui.screens

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hermesandroid.relay.R
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.ui.components.ConnectionSetupTimeline
import com.hermesandroid.relay.ui.components.ConnectionSetupTimelineStep
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardAuthProvider
import com.hermesandroid.relay.network.upstream.DashboardAuthSession
import com.hermesandroid.relay.network.upstream.DashboardCookieStore
import com.hermesandroid.relay.network.upstream.DashboardRedirectAuthMode
import com.hermesandroid.relay.network.upstream.EncryptedDashboardCookieStore
import com.hermesandroid.relay.network.upstream.NativeDashboardSignInCoordinator
import com.hermesandroid.relay.network.upstream.androidDashboardRedirectAuthMode
import com.hermesandroid.relay.network.upstream.importDashboardCookieHeader
import com.hermesandroid.relay.network.upstream.isNativeDashboardTransportEligible
import com.hermesandroid.relay.network.upstream.nativeDashboardSignInFailureDiagnostic
import com.hermesandroid.relay.network.upstream.nativeDashboardSignInFailureStage
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val NATIVE_DASHBOARD_AUTH_LOG_TAG = "HermesNativeAuth"

/**
 * Connection-level Dashboard authentication flow. It is deliberately outside
 * Manage so onboarding, connection setup, Voice, Chat, and Manage can all use
 * the same cookie/session flow without inheriting Manage's navigation stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardSignInScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onAuthenticated: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val dashboardUrl by connectionViewModel.effectiveDashboardUrl.collectAsState()
    val routeHint by connectionViewModel.dashboardRouteMovedHint.collectAsState()
    val connectionId = activeConnection?.id ?: "default"
    var providers by remember(dashboardUrl, connectionId) {
        mutableStateOf<List<DashboardAuthProvider>>(emptyList())
    }
    var loading by remember(dashboardUrl, connectionId) { mutableStateOf(true) }
    var actionInFlight by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var actionIsError by remember { mutableStateOf(false) }
    var oauthProvider by remember { mutableStateOf<DashboardAuthProvider?>(null) }
    var authFlows by remember(dashboardUrl, connectionId) {
        mutableStateOf<List<String>>(emptyList())
    }
    var nativeSignInJob by remember(dashboardUrl, connectionId) { mutableStateOf<Job?>(null) }
    var authenticationComplete by remember { mutableStateOf(false) }

    val cookieStoreFactory = remember(appContext, connectionId) {
        {
            connectionViewModel.activeDashboardCookieStore()
                ?: EncryptedDashboardCookieStore(appContext, connectionId)
        }
    }
    val clientFactory = remember(dashboardUrl, connectionViewModel) {
        { connectionViewModel.dashboardClientForActive(dashboardUrl) }
    }

    suspend fun verifyAndRecord(client: DashboardApiClient): DashboardAuthSession? {
        val status = client.getStatus().getOrNull()
        val session = client.currentSession().getOrNull()
        val ticketAvailable = if (session?.authenticated == true) {
            client.requestWsTicket().isSuccess
        } else {
            null
        }
        connectionViewModel.recordDashboardStatus(
            status = status,
            session = session,
            reachable = status != null,
            gatewayTicketAvailable = ticketAvailable,
        )
        return session
    }

    fun finishAuthentication() {
        scope.launch {
            invalidateDashboardManageCache(appContext.cacheDir)
            connectionViewModel.refreshStandardVoice()
            connectionViewModel.refreshDashboardProfiles()
            authenticationComplete = true
        }
    }

    LaunchedEffect(dashboardUrl, connectionId) {
        if (dashboardUrl.isBlank()) {
            loading = false
            actionMessage = resources.getString(R.string.dashboard_no_url_configured)
            return@LaunchedEffect
        }
        val client = clientFactory()
        try {
            val status = client.getStatus().getOrElse {
                actionMessage = it.message ?: resources.getString(R.string.dashboard_request_failed)
                actionIsError = true
                return@LaunchedEffect
            }
            providers = client.getAuthProviders().getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: status.authProviderDetails
            authFlows = status.authFlows
            val session = if (status.authRequired) client.currentSession().getOrNull() else null
            connectionViewModel.recordDashboardStatus(
                status = status,
                session = session,
                reachable = true,
                gatewayTicketAvailable = null,
            )
            if (!status.authRequired || session?.authenticated == true) {
                finishAuthentication()
            }
        } finally {
            loading = false
            client.shutdown()
        }
    }

    fun submitPassword(provider: String, username: String, password: String) {
        if (actionInFlight || dashboardUrl.isBlank()) return
        actionInFlight = true
        actionMessage = null
        actionIsError = false
        scope.launch {
            val client = clientFactory()
            try {
                val result = client.loginPassword(provider, username, password)
                val session = if (result.isSuccess) verifyAndRecord(client) else null
                if (result.isSuccess && session?.authenticated == true) {
                    finishAuthentication()
                } else {
                    actionMessage = result.exceptionOrNull()?.message
                        ?: resources.getString(R.string.dashboard_signin_no_session)
                    actionIsError = true
                }
            } catch (e: Exception) {
                actionMessage = e.message ?: resources.getString(R.string.dashboard_signin_failed)
                actionIsError = true
            } finally {
                actionInFlight = false
                client.shutdown()
            }
        }
    }

    fun startRedirectSignIn(provider: DashboardAuthProvider) {
        if (actionInFlight || dashboardUrl.isBlank()) return
        if (
            androidDashboardRedirectAuthMode(provider.name, authFlows) ==
            DashboardRedirectAuthMode.WebView
        ) {
            oauthProvider = provider
            return
        }
        if (!isNativeDashboardTransportEligible(dashboardUrl)) {
            actionMessage = resources.getString(R.string.dashboard_native_signin_requires_https)
            actionIsError = true
            return
        }
        val authClient = connectionViewModel.nativeDashboardAuthClientForActive(dashboardUrl)
        if (authClient == null) {
            actionMessage = resources.getString(R.string.dashboard_native_signin_unavailable)
            actionIsError = true
            return
        }

        actionInFlight = true
        actionIsError = false
        actionMessage = resources.getString(R.string.dashboard_native_signin_opening)
        nativeSignInJob = scope.launch {
            try {
                NativeDashboardSignInCoordinator(authClient).signIn(provider.name) { authorizationUrl ->
                    withContext(Dispatchers.Main.immediate) {
                        launchNativeDashboardAuthorization(context, authorizationUrl)
                    }
                }
                val client = clientFactory()
                val session = try {
                    verifyAndRecord(client)
                } finally {
                    client.shutdown()
                }
                if (session?.authenticated == true) {
                    actionMessage = session.provider?.let {
                        resources.getString(R.string.dashboard_signed_in_with, it)
                    } ?: resources.getString(R.string.dashboard_signed_in)
                    actionIsError = false
                    finishAuthentication()
                } else {
                    actionMessage = resources.getString(R.string.dashboard_signin_no_session)
                    actionIsError = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failureStage = nativeDashboardSignInFailureStage(error)
                val failureDetail = nativeDashboardSignInFailureDiagnostic(error)
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Auth,
                    severity = DiagnosticSeverity.Error,
                    title = resources.getString(R.string.dashboard_signin_failed),
                    detail = failureDetail,
                    operation = "dashboard_native_pkce",
                )
                Log.w(NATIVE_DASHBOARD_AUTH_LOG_TAG, failureDetail)
                actionMessage = when (nativeDashboardSignInMessageKind(failureStage)) {
                    NativeDashboardSignInMessageKind.CallbackRejected -> resources.getString(
                        R.string.dashboard_native_signin_callback_rejected,
                    )
                    NativeDashboardSignInMessageKind.CodeRejected -> resources.getString(
                        R.string.dashboard_native_signin_code_rejected,
                    )
                    NativeDashboardSignInMessageKind.GatewayRejected -> resources.getString(
                        R.string.dashboard_native_signin_gateway_rejected,
                    )
                    NativeDashboardSignInMessageKind.RateLimited -> resources.getString(
                        R.string.dashboard_native_signin_rate_limited,
                    )
                    NativeDashboardSignInMessageKind.GatewayUnavailable -> resources.getString(
                        R.string.dashboard_native_signin_gateway_unavailable,
                    )
                    NativeDashboardSignInMessageKind.ResponseUnsupported -> resources.getString(
                        R.string.dashboard_native_signin_response_unsupported,
                    )
                    NativeDashboardSignInMessageKind.AttemptInactive -> resources.getString(
                        R.string.dashboard_native_signin_attempt_inactive,
                    )
                    NativeDashboardSignInMessageKind.SecureStorage -> resources.getString(
                        R.string.dashboard_native_signin_storage_failed,
                    )
                    NativeDashboardSignInMessageKind.Transport -> resources.getString(
                        R.string.dashboard_native_signin_transport_retry,
                    )
                    NativeDashboardSignInMessageKind.Generic -> error.message
                        ?: resources.getString(R.string.dashboard_signin_failed)
                }
                actionIsError = true
            } finally {
                actionInFlight = false
                nativeSignInJob = null
            }
        }
    }

    DisposableEffect(dashboardUrl, connectionId) {
        onDispose { nativeSignInJob?.cancel() }
    }

    oauthProvider?.let { provider ->
        DashboardOAuthScreen(
            dashboardUrl = dashboardUrl,
            provider = provider,
            cookieStoreFactory = cookieStoreFactory,
            clientFactory = clientFactory,
            onDismiss = { oauthProvider = null },
            onAuthenticated = { session ->
                oauthProvider = null
                scope.launch {
                    val client = clientFactory()
                    try {
                        verifyAndRecord(client)
                    } finally {
                        client.shutdown()
                    }
                    actionMessage = session.provider?.let {
                        resources.getString(R.string.dashboard_signed_in_with, it)
                    } ?: resources.getString(R.string.dashboard_signed_in)
                    finishAuthentication()
                }
            },
            onError = {
                actionMessage = it
                actionIsError = true
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_sign_in)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dashboard_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (authenticationComplete) {
                DashboardAuthenticationComplete(onContinue = onAuthenticated)
            } else if (loading) {
                CircularProgressIndicator()
            } else {
                DashboardSignInForm(
                    dashboardUrl = dashboardUrl,
                    routeHint = routeHint,
                    providers = providers,
                    actionInFlight = actionInFlight,
                    actionMessage = actionMessage,
                    actionIsError = actionIsError,
                    nativeSignInInFlight = nativeSignInJob != null,
                    onSignIn = ::submitPassword,
                    onOAuthSignIn = ::startRedirectSignIn,
                    onCancelNativeSignIn = {
                        actionMessage = resources.getString(R.string.dashboard_native_signin_cancelled)
                        actionIsError = false
                        nativeSignInJob?.cancel()
                    },
                )
            }
        }
    }
}

internal enum class NativeDashboardSignInMessageKind {
    CallbackRejected,
    CodeRejected,
    GatewayRejected,
    RateLimited,
    GatewayUnavailable,
    ResponseUnsupported,
    AttemptInactive,
    SecureStorage,
    Transport,
    Generic,
}

internal fun nativeDashboardSignInMessageKind(failureStage: String): NativeDashboardSignInMessageKind =
    when {
        failureStage == "callback_error" -> NativeDashboardSignInMessageKind.CallbackRejected
        failureStage == "token_http_400" -> NativeDashboardSignInMessageKind.CodeRejected
        failureStage == "token_http_401" || failureStage == "token_http_403" ->
            NativeDashboardSignInMessageKind.GatewayRejected
        failureStage == "token_http_429" -> NativeDashboardSignInMessageKind.RateLimited
        failureStage.startsWith("token_http_5") -> NativeDashboardSignInMessageKind.GatewayUnavailable
        failureStage == "token_shape" -> NativeDashboardSignInMessageKind.ResponseUnsupported
        failureStage == "inactive_generation" -> NativeDashboardSignInMessageKind.AttemptInactive
        failureStage == "token_store" -> NativeDashboardSignInMessageKind.SecureStorage
        failureStage.startsWith("token_transport") -> NativeDashboardSignInMessageKind.Transport
        else -> NativeDashboardSignInMessageKind.Generic
    }

@Composable
private fun DashboardAuthenticationComplete(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.cw_step_3_3),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.cw_dashboard_connected_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.cw_dashboard_connected_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ConnectionSetupTimeline(
            steps = listOf(
                ConnectionSetupTimelineStep(
                    stringResource(R.string.cw_timeline_discovered),
                    stringResource(R.string.cw_timeline_discovered_detail),
                ),
                ConnectionSetupTimelineStep(
                    stringResource(R.string.cw_timeline_access),
                    stringResource(R.string.cw_timeline_authenticated),
                ),
                ConnectionSetupTimelineStep(
                    stringResource(R.string.cw_timeline_ready),
                    stringResource(R.string.cw_timeline_ready_detail),
                ),
            ),
        )
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.cw_continue))
        }
    }
}

@Composable
private fun DashboardSignInForm(
    dashboardUrl: String,
    routeHint: String?,
    providers: List<DashboardAuthProvider>,
    actionInFlight: Boolean,
    actionMessage: String?,
    actionIsError: Boolean,
    nativeSignInInFlight: Boolean,
    onSignIn: (String, String, String) -> Unit,
    onOAuthSignIn: (DashboardAuthProvider) -> Unit,
    onCancelNativeSignIn: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val passwordProvider = providers.firstOrNull { it.supportsPassword }
    val redirectProviders = providers.filter { it.isRedirectProvider }

    Text(
        text = stringResource(R.string.dashboard_signin_required_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = stringResource(R.string.dashboard_signin_required_body, dashboardUrl),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    routeHint?.let {
        Text(
            text = stringResource(R.string.dashboard_signin_route_hint, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
    redirectProviders.forEach { provider ->
        Button(
            onClick = { onOAuthSignIn(provider) },
            enabled = !actionInFlight,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.dashboard_signin_with_provider, provider.displayName ?: provider.name))
        }
    }
    if (nativeSignInInFlight) {
        Button(
            onClick = onCancelNativeSignIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.dashboard_cancel))
        }
    }
    if (passwordProvider != null || providers.isEmpty()) {
        if (redirectProviders.isNotEmpty()) HorizontalDivider()
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.dashboard_username)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.dashboard_password)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            onClick = { onSignIn(passwordProvider?.name ?: "basic", username, password) },
            enabled = !actionInFlight && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (actionInFlight) stringResource(R.string.dashboard_signing_in) else stringResource(R.string.dashboard_sign_in))
        }
    }
    actionMessage?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (actionIsError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardOAuthScreen(
    dashboardUrl: String,
    provider: DashboardAuthProvider,
    cookieStoreFactory: () -> DashboardCookieStore,
    clientFactory: () -> DashboardApiClient,
    onDismiss: () -> Unit,
    onAuthenticated: (DashboardAuthSession) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val initialStatus = stringResource(R.string.dashboard_oauth_initial_status)
    val verifyingStatus = stringResource(R.string.dashboard_oauth_verifying)
    val notAcceptedStatus = stringResource(R.string.dashboard_oauth_not_accepted)
    val verifyFailedStatus = stringResource(R.string.dashboard_oauth_verify_failed)
    var statusText by remember(initialStatus) { mutableStateOf(initialStatus) }
    var checking by remember { mutableStateOf(false) }
    var pageProgress by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val loginUrl = remember(dashboardUrl, provider.name) {
        DashboardApiClient.authLoginUrl(
            baseUrl = dashboardUrl,
            provider = provider.name,
            next = DashboardApiClient.authLandingPath(dashboardUrl),
        )
    }

    fun handleNavigation(url: String?) {
        val loadedUrl = url?.takeIf { it.isNotBlank() } ?: return
        when (dashboardWebViewAuthNavigation(dashboardUrl, loadedUrl)) {
            DashboardWebViewAuthNavigation.Continue -> return
            DashboardWebViewAuthNavigation.RejectLoopbackCallback -> {
                statusText = notAcceptedStatus
                onError(notAcceptedStatus)
                return
            }
            DashboardWebViewAuthNavigation.ImportAndVerify -> Unit
        }
        val manager = CookieManager.getInstance()
        manager.flush()
        val imported = importDashboardCookieHeader(
            cookieStoreFactory(),
            loadedUrl,
            manager.getCookie(loadedUrl),
        )
        if (checking || imported == 0) return
        checking = true
        statusText = verifyingStatus
        scope.launch {
            val client = clientFactory()
            try {
                val session = client.currentSession().getOrNull()
                if (session?.authenticated == true) onAuthenticated(session) else {
                    checking = false
                    statusText = notAcceptedStatus
                }
            } catch (e: Exception) {
                checking = false
                val message = e.message ?: verifyFailedStatus
                statusText = message
                onError(message)
            } finally {
                client.shutdown()
            }
        }
    }

    BackHandler(onBack = onDismiss)

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(
                                R.string.dashboard_signin_with_provider,
                                provider.displayName ?: provider.name,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dashboard_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (pageProgress in 0..99) {
                LinearProgressIndicator(
                    progress = { pageProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { viewContext ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(viewContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                pageProgress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val target = request.url.toString()
                                if (
                                    dashboardWebViewAuthNavigation(dashboardUrl, target) ==
                                    DashboardWebViewAuthNavigation.RejectLoopbackCallback
                                ) {
                                    handleNavigation(target)
                                    return true
                                }
                                return false
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request.isForMainFrame) {
                                    val message = error.description?.toString()
                                        ?.takeIf { it.isNotBlank() }
                                        ?: verifyFailedStatus
                                    statusText = message
                                    onError(message)
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                handleNavigation(url)
                            }
                        }
                        webView = this
                        loadUrl(loginUrl)
                    }
                },
            )
        }
    }
}

internal enum class DashboardWebViewAuthNavigation {
    Continue,
    ImportAndVerify,
    RejectLoopbackCallback,
}

/**
 * Android redirect providers use the dashboard's cookie/OIDC flow. A foreign
 * loopback callback belongs to the desktop native-PKCE contract and must never
 * be followed, imported, or treated as an authenticated Android return.
 */
internal fun dashboardWebViewAuthNavigation(
    dashboardUrl: String,
    loadedUrl: String,
): DashboardWebViewAuthNavigation {
    val dashboard = dashboardUrl.trim().trimEnd('/').toHttpUrlOrNull()
        ?: return DashboardWebViewAuthNavigation.Continue
    val loaded = loadedUrl.trim().toHttpUrlOrNull()
        ?: return DashboardWebViewAuthNavigation.Continue
    val sameOrigin = dashboard.scheme == loaded.scheme &&
        dashboard.host.equals(loaded.host, ignoreCase = true) &&
        dashboard.port == loaded.port
    if (!sameOrigin) {
        val foreignLoopback = loaded.scheme == "http" &&
            loaded.host in setOf("127.0.0.1", "localhost", "::1") &&
            loaded.encodedPath == "/callback"
        return if (foreignLoopback) {
            DashboardWebViewAuthNavigation.RejectLoopbackCallback
        } else {
            DashboardWebViewAuthNavigation.Continue
        }
    }

    val basePath = dashboard.encodedPath.trimEnd('/')
    val relativePath = loaded.encodedPath
        .removePrefix(basePath)
        .ifBlank { "/" }
    return if (
        relativePath.equals("/login", ignoreCase = true) ||
        relativePath.equals("/auth/login", ignoreCase = true)
    ) {
        DashboardWebViewAuthNavigation.Continue
    } else {
        // Includes the public /auth/callback response: import its cookies at
        // root scope, then verify the resulting session through /api/auth/me.
        DashboardWebViewAuthNavigation.ImportAndVerify
    }
}
