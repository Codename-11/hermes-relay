package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.dashboardHttpConsentRequired
import com.hermesandroid.relay.data.dashboardHttpOrigin

@Composable
fun DashboardHttpConsentControl(
    address: String,
    confirmedOrigin: String?,
    onConfirmationChange: (String?) -> Unit,
    enabled: Boolean = true,
    required: Boolean = dashboardHttpConsentRequired(address),
) {
    if (!required) return
    val origin = dashboardHttpOrigin(address) ?: return
    val checked = confirmedOrigin == origin
    Column {
        Text(
            stringResource(R.string.dashboard_http_risk, origin),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onConfirmationChange(if (it) origin else null) },
            ).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            Text(stringResource(R.string.dashboard_http_allow), modifier = Modifier.weight(1f))
        }
    }
}
