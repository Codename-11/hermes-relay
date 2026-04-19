package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.ui.theme.gradientBorder

/**
 * Settings card that opens the Profile Inspector full-screen viewer for
 * the currently-active agent profile.
 *
 * Placement-wise this lives immediately under `ActiveAgentCard` in
 * [com.hermesandroid.relay.ui.screens.SettingsScreen] so the card lines
 * up visually with the active-agent pick the user just saw above.
 *
 * When no profile is selected, the card renders at 50% alpha with a
 * "No active agent" subtitle and its onClick is a no-op — matching the
 * existing disabled-row convention used elsewhere in Settings. This
 * keeps the card visible (discoverable) rather than hidden, so users
 * understand the feature exists even before they've picked a profile.
 */
@Composable
fun ProfileInspectorCard(
    activeProfile: Profile?,
    onClick: (profileName: String) -> Unit,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val enabled = activeProfile != null
    val profileName = activeProfile?.name

    Card(
        modifier = modifier
            .fillMaxWidth()
            .gradientBorder(
                shape = RoundedCornerShape(12.dp),
                isDarkTheme = isDarkTheme,
            )
            .alpha(if (enabled) 1f else 0.5f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (enabled && profileName != null) {
                        Modifier.clickable { onClick(profileName) }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ManageSearch,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Inspect Agent",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (enabled) {
                        "View config, SOUL, memory, skills"
                    } else {
                        "No active agent"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
