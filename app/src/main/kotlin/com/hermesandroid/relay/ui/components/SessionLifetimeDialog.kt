package com.hermesandroid.relay.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.hermesandroid.relay.R
import kotlinx.coroutines.launch

/** Only reductions of the current bearer are supported without operator approval. */
fun shorterSessionOptions(expiresAt: Double?, nowSeconds: Long): List<TtlOption> =
    ttlPickerOptions().filter { it.seconds > 0 && (expiresAt == null || nowSeconds + it.seconds < expiresAt) }

@Composable
fun SessionLifetimeDialog(
    expiresAt: Double?,
    onUpdate: suspend (Long) -> Result<Unit>,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    onRenew: () -> Unit,
) {
    val options = remember(expiresAt) { shorterSessionOptions(expiresAt, System.currentTimeMillis() / 1000) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val failure = stringResource(R.string.paired_devices_policy_failed)
    SessionTtlPickerDialog(
        initialTtlSeconds = options.firstOrNull()?.seconds ?: -1,
        isTailscaleDetected = false,
        transportHint = null,
        options = options,
        titleRes = R.string.paired_devices_shorten_title,
        bodyRes = R.string.paired_devices_shorten_body,
        confirmRes = R.string.paired_devices_shorten,
        busy = busy,
        error = error,
        onConfirm = { ttl ->
            busy = true
            error = null
            scope.launch {
                try {
                    val result = onUpdate(ttl)
                    if (result.isSuccess) onSuccess() else error = failure
                } finally {
                    busy = false
                }
            }
        },
        onCancel = onCancel,
        onRenew = onRenew,
    )
}
