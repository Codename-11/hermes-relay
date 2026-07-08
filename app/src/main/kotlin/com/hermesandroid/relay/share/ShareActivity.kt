package com.hermesandroid.relay.share

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.data.ConnectionStore
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import kotlinx.coroutines.flow.first

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = ShareIntentParser.parse(intent)
        setContent {
            HermesRelayTheme {
                if (payload == null) {
                    ShareErrorScreen(
                        message = "Hermes Relay can only review ACTION_SEND text shares.",
                        onClose = { finish() },
                    )
                    return@HermesRelayTheme
                }

                var senderResult by remember { mutableStateOf<Result<ShareCaptureContract>?>(null) }
                LaunchedEffect(payload) {
                    senderResult = buildSender()
                }

                val result = senderResult
                when {
                    result == null -> ShareErrorScreen(
                        message = "Loading active Hermes connection…",
                        onClose = { finish() },
                    )
                    result.isFailure -> ShareErrorScreen(
                        message = result.exceptionOrNull()?.message ?: "No active Hermes connection is available.",
                        onClose = { finish() },
                    )
                    else -> {
                        val sender = result.getOrThrow()
                        val shareViewModel: ShareViewModel = viewModel(
                            key = "share-${payload.timestamp}",
                            factory = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                    ShareViewModel(payload, sender) as T
                            },
                        )
                        val state by shareViewModel.state.collectAsState()
                        ShareReviewScreen(
                            state = state,
                            onActionSelected = shareViewModel::selectAction,
                            onSubmit = shareViewModel::submitSelectedAction,
                            onCancel = { finish() },
                        )
                    }
                }
            }
        }
    }

    private suspend fun buildSender(): Result<ShareCaptureContract> = runCatching {
        val store = ConnectionStore(applicationContext)
        store.isHydrated.first { it }
        val connection = store.activeConnection.value
            ?: throw IllegalStateException("No active Hermes connection is configured.")
        val apiKey = AuthManager.exportStoredSecrets(
            applicationContext,
            connection.tokenStoreKey,
        ).apiKey?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("The active Hermes connection has no API key saved.")
        val baseUrl = connection.resolvedDashboardUrl.ifBlank { connection.apiServerUrl }
        CaptureShareHermesSender(baseUrl = baseUrl, apiKey = apiKey)
    }
}
