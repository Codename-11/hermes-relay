package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.hermesandroid.relay.R
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.BridgeSafetySettings
import com.hermesandroid.relay.data.DEFAULT_BLOCKLIST
import com.hermesandroid.relay.data.DEFAULT_DESTRUCTIVE_VERBS

/**
 * Phase 3 — safety-rails `bridge-safety-rails`
 *
 * Replaces the inert `SafetyPlaceholderCard` in [BridgeScreen]. Shows a
 * live-updating summary of Tier 5 safety state:
 *
 *  - Blocklist count ("12 apps blocked")
 *  - Destructive-verb count ("12 verbs need confirmation")
 *  - Auto-disable window ("Auto-off after 30 min idle")
 *  - Auto-disable countdown when a timer is active
 *
 * Tap → navigate to [BridgeSafetySettingsScreen].
 *
 * The card is self-sufficient: caller passes the settings snapshot +
 * optional countdown millis (from `BridgeSafetyManager.autoDisableAtMs`)
 * and wires the onClick to the nav controller.
 */
@Composable
fun BridgeSafetySummaryCard(
    settings: BridgeSafetySettings,
    autoDisableAtMs: Long? = null,
    onManage: () -> Unit,
) {
    // Tick a local clock every second when a countdown is active so the
    // "in 12:34" label actually counts down. Recomposition is scoped to
    // this card so the rest of the Bridge screen stays still.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (autoDisableAtMs != null) {
        LaunchedEffect(autoDisableAtMs) {
            while (true) {
                nowMs = System.currentTimeMillis()
                kotlinx.coroutines.delay(1000L)
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onManage),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.bssc_safety),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.bssc_manage),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SafetySummaryRow(
                label = stringResource(R.string.bssc_blocked_apps),
                value = "${settings.blocklist.size}",
            )
            SafetySummaryRow(
                label = stringResource(R.string.bssc_destructive_verbs),
                value = "${settings.destructiveVerbs.size}",
            )
            SafetySummaryRow(
                label = stringResource(R.string.bssc_auto_disable),
                value = if (autoDisableAtMs != null) {
                    val remainMs = (autoDisableAtMs - nowMs).coerceAtLeast(0L)
                    val remainMin = (remainMs / 60_000L).toInt()
                    val remainSec = ((remainMs % 60_000L) / 1000L).toInt()
                    "in ${remainMin}:${remainSec.toString().padStart(2, '0')}"
                } else {
                    "${settings.autoDisableMinutes} min"
                },
            )

            Text(
                text = stringResource(R.string.bssc_guardrails_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SafetySummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgeSafetySummaryCardPreview() {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            BridgeSafetySummaryCard(
                settings = BridgeSafetySettings(
                    blocklist = DEFAULT_BLOCKLIST,
                    destructiveVerbs = DEFAULT_DESTRUCTIVE_VERBS,
                ),
                autoDisableAtMs = null,
                onManage = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgeSafetySummaryCardPreview_Countdown() {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            BridgeSafetySummaryCard(
                settings = BridgeSafetySettings(
                    blocklist = DEFAULT_BLOCKLIST,
                    destructiveVerbs = DEFAULT_DESTRUCTIVE_VERBS,
                ),
                autoDisableAtMs = System.currentTimeMillis() + 12 * 60_000L + 34_000L,
                onManage = {},
            )
        }
    }
}
