package com.hermesandroid.relay.ui.screens

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.components.ConnectionSetupTimeline
import com.hermesandroid.relay.ui.components.ConnectionSetupTimelineStep
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardAuthProvider
import com.hermesandroid.relay.network.upstream.DashboardAuthSession
import com.hermesandroid.relay.network.upstream.DashboardCookieStore
import com.hermesandroid.relay.network.upstream.EncryptedDashboardCookieStore
import com.hermesandroid.relay.network.upstream.importDashboardCookieHeader
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

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
    val context = LocalContext.current.applicationContext
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
    var oauthProvider by remember { mutableStateOf<DashboardAuthProvider?>(null) }
    var authenticationComplete by remember { mutableStateOf(false) }

    val cookieStoreFactory = remember(context, connectionId) {
        {
            connectionViewModel.activeDashboardCookieStore()
                ?: EncryptedDashboardCookieStore(context, connectionId)
        }
    }
    val clientFactory = remember(dashboardUrl, cookieStoreFactory) {
        {
            DashboardApiClient(
                baseUrl = dashboardUrl,
                okHttpClient = DashboardApiClient.defaultClient(cookieStoreFactory()),
            )
        }
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
            invalidateDashboardManageCache(context.cacheDir)
            connectionViewModel.refreshStandardVoice()
            connectionViewModel.refreshDashboardProfiles()
            authenticationComplete = true
        }
    }

    LaunchedEffect(dashboardUrl, connectionId) {
        if (dashboardUrl.isBlank()) {
            loading = false
            actionMessage = context.getString(R.string.dashboard_no_url_configured)
            return@LaunchedEffect
        }
        val client = clientFactory()
        try {
            val status = client.getStatus().getOrElse {
                actionMessage = it.message ?: context.getString(R.string.dashboard_request_failed)
                return@LaunchedEffect
            }
            providers = status.authProviderDetails.ifEmpty {
                client.getAuthProviders().getOrNull().orEmpty()
            }
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
        scope.launch {
            val client = clientFactory()
            try {
                val result = client.loginPassword(provider, username, password)
                val session = if (result.isSuccess) verifyAndRecord(client) else null
                if (result.isSuccess && session?.authenticated == true) {
                    finishAuthentication()
                } else {
                    actionMessage = result.exceptionOrNull()?.message
                        ?: context.getString(R.string.dashboard_signin_no_session)
                }
            } catch (e: Exception) {
                actionMessage = e.message ?: context.getString(R.string.dashboard_signin_failed)
            } finally {
                actionInFlight = false
                client.shutdown()
            }
        }
    }

    oauthProvider?.let { provider ->
        DashboardOAuthDialog(
            dashboardUrl = dashboardUrl,
            provider = provider,
            cookieStoreFactory = cookieStoreFactory,
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
                        context.getString(R.string.dashboard_signed_in_with, it)
                    } ?: context.getString(R.string.dashboard_signed_in)
                    finishAuthentication()
                }
            },
            onError = { actionMessage = it },
        )
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
                    onSignIn = ::submitPassword,
                    onOAuthSignIn = { oauthProvider = it },
                )
            }
        }
    }
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
    onSignIn: (String, String, String) -> Unit,
    onOAuthSignIn: (DashboardAuthProvider) -> Unit,
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
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DashboardOAuthDialog(
    dashboardUrl: String,
    provider: DashboardAuthProvider,
    cookieStoreFactory: () -> DashboardCookieStore,
    onDismiss: () -> Unit,
    onAuthenticated: (DashboardAuthSession) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialStatus = stringResource(R.string.dashboard_oauth_initial_status)
    val verifyingStatus = stringResource(R.string.dashboard_oauth_verifying)
    val notAcceptedStatus = stringResource(R.string.dashboard_oauth_not_accepted)
    val verifyFailedStatus = stringResource(R.string.dashboard_oauth_verify_failed)
    var statusText by remember(initialStatus) { mutableStateOf(initialStatus) }
    var checking by remember { mutableStateOf(false) }
    val loginUrl = remember(dashboardUrl, provider.name) {
        DashboardApiClient.authLoginUrl(
            baseUrl = dashboardUrl,
            provider = provider.name,
            next = DashboardApiClient.authLandingPath(dashboardUrl),
        )
    }

    fun maybeVerify(url: String?) {
        val loadedUrl = url?.takeIf { it.isNotBlank() } ?: return
        val root = dashboardUrl.trim().trimEnd('/')
        val relative = loadedUrl.trim().removePrefix(root)
        val stillAuthenticating = relative.startsWith("/login", true) ||
            relative.startsWith("/auth/login", true) ||
            relative.startsWith("/auth/callback", true)
        if (!loadedUrl.startsWith(root, true) || stillAuthenticating) return
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
            val client = DashboardApiClient(
                dashboardUrl,
                DashboardApiClient.defaultClient(cookieStoreFactory()),
            )
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

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dashboard_close_signin))
                }
                Text(statusText, style = MaterialTheme.typography.bodySmall)
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { viewContext ->
                        CookieManager.getInstance().setAcceptCookie(true)
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean = false

                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    maybeVerify(url)
                                }
                            }
                            loadUrl(loginUrl)
                        }
                    },
                )
            }
        }
    }
}
