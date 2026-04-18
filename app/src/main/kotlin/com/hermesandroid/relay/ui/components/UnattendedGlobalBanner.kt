package com.hermesandroid.relay.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Global single-line banner rendered at the top of [RelayApp]'s scaffold
 * whenever the bridge master toggle AND unattended-access opt-in are
 * both active. Visible on every tab (Chat, Terminal, Bridge, Settings)
 * so the user can't forget the agent is permitted to wake the screen
 * and drive the device just because they navigated away from Bridge.
 *
 * Rendered in addition to the system-wide [BridgeStatusOverlayChip]
 * (the floating amber pill). The overlay is visible across all apps
 * (gmail, chrome, etc) — this banner is visible inside Hermes-Relay.
 * Both surfaces exist because they serve different visibility windows:
 *  - Overlay = "someone glancing at the phone can see unattended is on"
 *  - Banner  = "you inside the app can't miss that unattended is on
 *    even if you're looking at Chat history"
 *
 * Design choices:
 *  - Full edge-to-edge width, flat sharp corners, amber tint. Reads as
 *    a system-level status strip rather than a Material card.
 *  - Single-line label — 28dp total height. Tight vertical footprint
 *    so it doesn't eat scroll space on every screen.
 *  - Tappable — jumps to the Bridge tab so the user has one motion to
 *    reach the toggle they might want to turn off.
 *  - Pulsing dot uses an infinite animation instead of a static colour
 *    so it reads as "active right now" rather than "configuration flag".
 */
@Composable
fun UnattendedGlobalBanner(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Theme-aware amber palette. The "safety signal" semantic is always
    // amber regardless of theme — we're telling the user the agent is in
    // an unusual "can wake the screen while you're away" state. What
    // changes per theme is the *background lightness*: a dark stripe on
    // a white app body reads as a system banner, and a pale-amber stripe
    // on a dark app body does too — but swapping them would produce a
    // pale-amber stripe on a white app body that looks anemic, and a
    // dark stripe on a dark app body that disappears.
    //
    // Contrast picked to exceed WCAG AA (4.5:1) for body text on the
    // background in both themes:
    //  - Dark: #FFD180 on #2A1F0A ≈ 10.3:1
    //  - Light: #7A3E00 on #FFF3E0 ≈ 9.2:1
    val isDark = isSystemInDarkTheme()
    val amberBg = if (isDark) Color(0xFF2A1F0A) else Color(0xFFFFF3E0)
    val amberOn = if (isDark) Color(0xFFFFD180) else Color(0xFF7A3E00)
    val amberFill = if (isDark) Color(0xFFFFA000) else Color(0xFFE65100)

    // Column lets the banner itself pad for the status bar. The banner
    // runs at the very top of the screen above the Scaffold, and the
    // app uses edge-to-edge mode (contentWindowInsets = WindowInsets(0)
    // on Scaffold) — without this inset padding, Android's status-bar
    // icons (clock, wifi, battery) would render on top of our text.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(amberBg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .clickable(onClick = onTap)
            // Tell TalkBack this is a button — Row.clickable alone adds
            // onClick but not a role, so assistive tech may read it as
            // plain text with no cue that a tap will do anything.
            .semantics { role = Role.Button },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        PulsingStatusDot(color = amberFill)
        Text(
            text = "Unattended access ON — agent can wake and drive this device",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = amberOn,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = amberOn,
            modifier = Modifier.size(16.dp),
        )
        }
    }
}

/**
 * Slow breathing-style pulse — 1.2s cycle, 0.4–1.0 alpha. Subtle enough
 * not to be distracting on a screen the user is reading, obvious enough
 * to read as "live" rather than "static marker."
 */
@Composable
private fun PulsingStatusDot(color: Color) {
    val infinite = rememberInfiniteTransition(label = "unattended-banner-pulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "unattended-banner-pulse-alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}

@Preview(widthDp = 360, heightDp = 40, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun UnattendedGlobalBannerPreview() {
    MaterialTheme {
        UnattendedGlobalBanner(onTap = {})
    }
}
