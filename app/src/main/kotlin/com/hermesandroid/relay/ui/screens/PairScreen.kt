@file:Suppress("LocalContextGetResourceValueCall")

package com.hermesandroid.relay.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.components.ConnectionWizard
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.delay

private const val PAIR_SETUP_TIMEOUT_MS = 15_000L

/**
 * Full-screen connection route. Wraps [ConnectionWizard] in a real Scaffold so
 * the chooser tiles, manual-entry forms, and camera viewport all get the
 * actual window — not a Compose Dialog that leaked the Settings cards
 * underneath. Reached via Settings → Connections → Add/Pair Relay (or any "Re-pair"
 * button), and pops back to wherever it came from on complete or cancel.
 *
 * [autoStart] lets the caller deep-link into a specific pair method. When
 * set to `"scan"`, the wizard jumps straight to the scanner. `"relay"`
 * opens the connection-scoped Relay method chooser without exposing the
 * new-server flow. Null shows the full connection chooser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(
    connectionViewModel: ConnectionViewModel,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onManageSignIn: (() -> Unit)? = null,
    autoStart: String? = null,
    setupReady: Boolean = true,
    onSetupTimeout: (() -> Unit)? = null,
    onSetupRetry: (() -> Unit)? = null,
    onConnectionTargetChanged: (String) -> Unit = {},
    /**
     * Optional offline "Try the demo" entry, forwarded to [ConnectionWizard].
     * Wired by [RelayApp] only for the bare Connect entry (no connection draft
     * in flight); null on add-connection / re-pair flows.
     */
    onTryDemo: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var setupTimedOut by remember { mutableStateOf(false) }
    var setupAttempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(setupReady, setupAttempt) {
        setupTimedOut = false
        if (!setupReady) {
            delay(PAIR_SETUP_TIMEOUT_MS)
            setupTimedOut = true
            onSetupTimeout?.invoke()
        }
    }

    // Route system back / predictive back through the same draft-discard path
    // the TopAppBar arrow uses. Without this, the NavController just pops
    // the backstack and [RelayApp]'s wired `discardPlaceholderConnection`
    // in the Pair route's `onCancel` never fires.
    BackHandler(enabled = true) { onCancel() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (autoStart == "relay") R.string.detail_pair_relay
                            else R.string.pair_connect_to_hermes,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.pair_close),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (!setupReady) {
                // Add connection navigates before its connection-scoped auth
                // store is ready. Keep one stable surface on screen instead of
                // composing a disabled wizard that is immediately invalidated
                // by the placeholder switch's URL/auth/connection emissions.
                Column(
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!setupTimedOut) CircularProgressIndicator()
                    Text(
                        text = stringResource(
                            if (setupTimedOut) R.string.cw_pairing_did_not_complete
                            else R.string.cw_preparing_connection,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!setupTimedOut) {
                        Text(
                            text = stringResource(R.string.cw_preparing_connection_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onCancel) {
                                Text(stringResource(R.string.cw_cancel_button))
                            }
                            Button(
                                onClick = {
                                    setupAttempt += 1
                                    onSetupRetry?.invoke()
                                },
                                enabled = onSetupRetry != null,
                            ) {
                                Text(stringResource(R.string.cw_retry))
                            }
                        }
                    }
                }
            } else {
                ConnectionWizard(
                    connectionViewModel = connectionViewModel,
                    onComplete = {
                        Toast.makeText(context, context.getString(R.string.pair_connection_updated), Toast.LENGTH_SHORT).show()
                        onComplete()
                    },
                    onCancel = onCancel,
                    onManageSignIn = onManageSignIn,
                    showSkip = false,
                    autoStart = autoStart,
                    setupReady = true,
                    onConnectionTargetChanged = onConnectionTargetChanged,
                    onTryDemo = onTryDemo,
                )
            }
        }
    }
}
