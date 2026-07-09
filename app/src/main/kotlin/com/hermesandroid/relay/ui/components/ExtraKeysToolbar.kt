package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.viewmodel.TerminalViewModel.SpecialKey

/**
 * Horizontal toolbar of keys that the Android soft keyboard doesn't offer:
 * ESC, TAB, CTRL (sticky), ALT (sticky), clipboard, arrows, and scrollback.
 *
 * Layout: a single [Row] wrapped in [horizontalScroll]. Keys size to their
 * label (clamped to a min width) and the row scrolls when the cluster is
 * wider than the screen — the same strategy Orca's mobile terminal uses.
 * The earlier weight-distributed layout squeezed every key into the screen
 * width, which clipped labels ("CTRL" → "CTR") on narrow phones; fixed-width
 * keys plus horizontal scroll render every label cleanly at any key count.
 *
 * Sticky modifier behavior: tapping CTRL or ALT highlights the key and
 * applies the modifier to the next character typed (via
 * [TerminalViewModel.sendInput]'s translation path). The ViewModel clears
 * the flag automatically once a character is sent, so tap → key → back to
 * idle matches Termux/JuiceSSH convention.
 */
@Composable
fun ExtraKeysToolbar(
    ctrlActive: Boolean,
    altActive: Boolean,
    onEsc: () -> Unit,
    onTab: () -> Unit,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onArrow: (SpecialKey) -> Unit,
    modifier: Modifier = Modifier,
    onScrollUp: (() -> Unit)? = null,
    onScrollDown: (() -> Unit)? = null,
    onScrollToBottom: (() -> Unit)? = null,
    onPaste: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onToggleKeyboard: (() -> Unit)? = null,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh

    // Column so the hairline top divider spans the full bar width while the
    // key row underneath scrolls independently. The caller's modifier (nav-bar
    // + IME padding) is applied to the outer container.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor),
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarKey(label = stringResource(R.string.extra_keys_esc), onClick = onEsc)
            ToolbarKey(label = stringResource(R.string.extra_keys_tab), onClick = onTab)
            ToolbarKey(label = stringResource(R.string.extra_keys_ctrl), active = ctrlActive, onClick = onCtrlToggle)
            ToolbarKey(label = stringResource(R.string.extra_keys_alt), active = altActive, onClick = onAltToggle)

            // Clipboard + keyboard cluster — selecting/copying/pasting and
            // raising the soft keyboard are all unreliable through long-press
            // inside an Android WebView, so these explicit keys are the
            // dependable path.
            onCopy?.let { copy ->
                ToolbarKey(label = stringResource(R.string.extra_keys_copy), onClick = copy)
            }
            onPaste?.let { paste ->
                ToolbarKey(label = stringResource(R.string.extra_keys_paste), onClick = paste)
            }
            onToggleKeyboard?.let { toggle ->
                ToolbarKey(label = "⌨", onClick = toggle) // show/hide soft keyboard
            }

            GroupSpacer()

            ToolbarKey(label = "←", onClick = { onArrow(SpecialKey.ARROW_LEFT) })
            ToolbarKey(label = "↓", onClick = { onArrow(SpecialKey.ARROW_DOWN) })
            ToolbarKey(label = "↑", onClick = { onArrow(SpecialKey.ARROW_UP) })
            ToolbarKey(label = "→", onClick = { onArrow(SpecialKey.ARROW_RIGHT) })

            // Scrollback controls — target xterm.js's viewport, NOT the remote
            // PTY. Unlike the arrow keys above (which send ANSI escapes into
            // the running shell), these just move the local scrollback window,
            // so the user can look at older output without disturbing whatever
            // the shell thinks the cursor position is.
            if (onScrollUp != null || onScrollDown != null || onScrollToBottom != null) {
                GroupSpacer()
            }
            onScrollUp?.let { scrollUp ->
                // upwards double arrow — distinct from ARROW_UP
                ToolbarKey(label = "⇑", onClick = scrollUp)
            }
            onScrollDown?.let { scrollDown ->
                ToolbarKey(label = "⇓", onClick = scrollDown) // downwards double arrow
            }
            // "Jump to bottom" — covers the case where the user has scrolled
            // way up and wants to snap back without swiping endlessly.
            onScrollToBottom?.let { scrollToBottom ->
                ToolbarKey(label = "⇲", onClick = scrollToBottom) // SE double arrow; "end"
            }
        }
    }
}

/** Extra gap between key clusters (modifiers · arrows · scrollback). */
@Composable
private fun GroupSpacer() {
    Spacer(modifier = Modifier.width(6.dp))
}

@Composable
private fun ToolbarKey(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    minWidth: Dp = 36.dp,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(6.dp)
    val scheme = MaterialTheme.colorScheme

    val bg = if (active) scheme.primary.copy(alpha = 0.22f) else scheme.surface
    val fg = if (active) scheme.primary else scheme.onSurface
    val borderColor = if (active) {
        scheme.primary.copy(alpha = 0.6f)
    } else {
        scheme.outlineVariant.copy(alpha = 0.4f)
    }

    Box(
        modifier = Modifier
            // Clamp to a tappable minimum but let longer labels (CTRL, PASTE)
            // grow so nothing clips. Width is content-driven inside the
            // horizontally-scrolling parent, never weight-divided.
            .widthIn(min = minWidth)
            .height(32.dp)
            .clip(shape)
            .background(bg, shape)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
