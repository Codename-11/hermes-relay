package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.UiMessageBus
import com.hermesandroid.relay.ui.UiMessageSeverity
import com.hermesandroid.relay.util.HumanError

val LocalMessageActionHost = staticCompositionLocalOf<SnackbarHostState?> { null }

data class HumanErrorVisuals(val error: HumanError) : SnackbarVisuals {
    override val message get() = error.body
    override val actionLabel get() = error.actionLabel
    override val withDismissAction = true
    override val duration get() = if (error.retryable) SnackbarDuration.Long else SnackbarDuration.Short
}

/** Uses the app banner's appearance while preserving existing suspend/action contracts. */
@Composable
fun ThemedMessageHost(hostState: SnackbarHostState, modifier: Modifier = Modifier, scoped: Boolean = false) {
    val modalActive by UiMessageBus.modalHostActive.collectAsState()
    if (!scoped && modalActive) return
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val error = (data.visuals as? HumanErrorVisuals)?.error
        val severity = if (error != null) UiMessageSeverity.Error else UiMessageSeverity.Info
        Surface(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = severityContainer(severity), contentColor = severityOnContainer(severity),
        ) {
            Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(severityIcon(severity), null, Modifier.size(18.dp))
                    Column(Modifier.weight(1f).padding(10.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        error?.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                        Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = data::dismiss) {
                        Icon(Icons.Filled.Close, stringResource(R.string.common_dismiss))
                    }
                }
                data.visuals.actionLabel?.let { label ->
                    TextButton(onClick = data::performAction, modifier = Modifier.align(Alignment.End)) { Text(label) }
                }
            }
        }
    }
}

/** Modal-window host; the root retains the same bus events until their normal expiry. */
@Composable
fun MessageOverlayScope(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val actions = LocalMessageActionHost.current
    Box(modifier) {
        content()
        MessageBannerHost(Modifier.align(Alignment.TopCenter), includeStatusBarPadding = false, primary = false)
        if (actions != null) ThemedMessageHost(actions, Modifier.align(Alignment.BottomCenter), scoped = true)
    }
}
