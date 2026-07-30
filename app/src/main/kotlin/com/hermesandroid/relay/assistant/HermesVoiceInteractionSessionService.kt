package com.hermesandroid.relay.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HermesVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        HermesVoiceInteractionSession(this)
}

private class HermesVoiceInteractionSession(
    private val service: HermesVoiceInteractionSessionService,
) : VoiceInteractionSession(service) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val viewOwner = AssistantSessionViewOwner().also { it.start() }
    private var shown = false

    init {
        scope.launch {
            AssistantSessionState.snapshot.collect { snapshot ->
                if (shown && snapshot.phase == AssistantSessionPhase.Closed) {
                    finishSession(cancelVoice = false)
                }
            }
        }
    }

    override fun onCreateContentView(): View = ComposeView(service).apply {
        setViewTreeLifecycleOwner(viewOwner)
        setViewTreeViewModelStoreOwner(viewOwner)
        setViewTreeSavedStateRegistryOwner(viewOwner)
        setContent {
            HermesRelayTheme {
                AssistantSessionSurface(
                    onCancel = { finishSession(cancelVoice = true) },
                    onOpen = {
                        runCatching {
                            startVoiceActivity(
                                AssistantSessionProtocol.activationIntent(
                                    service,
                                    UUID.randomUUID().toString(),
                                    startNewSession = true,
                                )
                            )
                        }
                    },
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        shown = true
        AssistantSessionState.reset()
        AssistantSessionProtocol.started(service)
        if (args?.getString(AssistantSessionProtocol.EXTRA_ACTIVATION_ID) != null) return
        runCatching {
            startVoiceActivity(
                AssistantSessionProtocol.activationIntent(
                    service,
                    UUID.randomUUID().toString(),
                    startNewSession = args?.getBoolean(
                        AssistantSessionProtocol.EXTRA_START_NEW_SESSION,
                        true,
                    ) ?: true,
                )
            )
        }.onFailure {
            AssistantSessionState.update(
                AssistantSessionSnapshot(
                    phase = AssistantSessionPhase.Error,
                    error = it.message ?: "Hermes could not open the voice session.",
                )
            )
        }
    }

    override fun onHide() {
        finishSession(cancelVoice = true)
        super.onHide()
    }

    override fun onDestroy() {
        if (shown) {
            shown = false
            AssistantSessionProtocol.finish(service, cancelVoice = true)
        }
        shown = false
        viewOwner.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun finishSession(cancelVoice: Boolean) {
        if (!shown) return
        shown = false
        AssistantSessionProtocol.finish(service, cancelVoice)
        finish()
    }
}

private class AssistantSessionViewOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

@Composable
private fun AssistantSessionSurface(
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) {
    val snapshot by AssistantSessionState.snapshot.collectAsState()
    val status = when (snapshot.phase) {
        AssistantSessionPhase.Launching -> stringResource(R.string.assistant_session_launching)
        AssistantSessionPhase.Listening -> stringResource(R.string.assistant_session_listening)
        AssistantSessionPhase.Transcribing ->
            stringResource(R.string.assistant_session_transcribing)
        AssistantSessionPhase.Thinking -> stringResource(R.string.assistant_session_thinking)
        AssistantSessionPhase.Speaking -> stringResource(R.string.assistant_session_speaking)
        AssistantSessionPhase.Idle -> stringResource(R.string.assistant_session_ready)
        AssistantSessionPhase.Error -> stringResource(R.string.assistant_session_error)
        AssistantSessionPhase.Closed -> stringResource(R.string.assistant_session_closing)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                status,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            snapshot.transcript?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            snapshot.response.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            snapshot.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.assistant_session_cancel))
                }
                if (snapshot.phase == AssistantSessionPhase.Error) {
                    Button(onClick = onOpen) {
                        Text(stringResource(R.string.assistant_session_retry))
                    }
                }
            }
        }
    }
}
