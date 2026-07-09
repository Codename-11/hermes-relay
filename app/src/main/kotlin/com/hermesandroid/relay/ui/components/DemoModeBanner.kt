package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.HermesRelayTheme

/**
 * Persistent single-line strip rendered at the top of [RelayApp]'s scaffold
 * while offline **Demo / Explore mode** is active. Tells the user the chat is
 * sample data with no live server, and offers a one-tap exit into the real
 * Connect flow.
 *
 * Sibling of [UnattendedGlobalBanner] (same edge-to-edge, status-bar-padded,
 * fully-tappable strip pattern) but tinted with the theme's primary container
 * — informational, not a warning. Tapping anywhere runs [onConnect], which
 * exits demo and routes to the Connection wizard.
 */
@Composable
fun DemoModeBanner(
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = MaterialTheme.colorScheme.primaryContainer
    val on = MaterialTheme.colorScheme.onPrimaryContainer

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .clickable(onClick = onConnect)
            .semantics { role = Role.Button },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Explore,
                contentDescription = null,
                tint = on,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.dmb_demo_mode),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = on,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = on,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Friendly full-screen empty state shown on the non-Chat surfaces (Manage,
 * Bridge, …) while Demo mode is active, instead of attempting a network call
 * or rendering a blank/error screen. Chat is the demo showcase; everything
 * else points the user at connecting their own Hermes server.
 *
 * @param feature human name of the surface, e.g. "Manage" or "Bridge".
 * @param onConnect exits demo and opens the real Connection wizard.
 */
@Composable
fun DemoUnavailableContent(
    feature: String,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Explore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = stringResource(R.string.dmb_this_is_demo),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.dmb_connect_feature, feature),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onConnect) {
                Text(stringResource(R.string.dmb_connect))
            }
        }
    }
}

@Preview(widthDp = 360, heightDp = 44, showBackground = true)
@Composable
private fun DemoModeBannerPreview() {
    HermesRelayTheme {
        DemoModeBanner(onConnect = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun DemoUnavailableContentPreview() {
    HermesRelayTheme {
        DemoUnavailableContent(feature = "Manage", onConnect = {})
    }
}
