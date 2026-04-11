package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.relay.viewmodel.TerminalViewModel.SpecialKey

/**
 * Horizontal toolbar of keys that the Android soft keyboard doesn't offer:
 * ESC, TAB, CTRL (sticky), ALT (sticky), and arrow keys.
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
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarKey(
            label = "ESC",
            active = false,
            weight = 1.2f,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onEsc()
            }
        )
        ToolbarKey(
            label = "TAB",
            active = false,
            weight = 1.2f,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onTab()
            }
        )
        ToolbarKey(
            label = "CTRL",
            active = ctrlActive,
            weight = 1.3f,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCtrlToggle()
            }
        )
        ToolbarKey(
            label = "ALT",
            active = altActive,
            weight = 1.2f,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onAltToggle()
            }
        )

        Spacer(modifier = Modifier.width(4.dp))

        ToolbarKey(
            label = "\u2190",
            active = false,
            weight = 1f,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onArrow(SpecialKey.ARROW_LEFT)
            }
        )
        ToolbarKey(
            label = "\u2193",
            active = false,
            weight = 1f,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onArrow(SpecialKey.ARROW_DOWN)
            }
        )
        ToolbarKey(
            label = "\u2191",
            active = false,
            weight = 1f,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onArrow(SpecialKey.ARROW_UP)
            }
        )
        ToolbarKey(
            label = "\u2192",
            active = false,
            weight = 1f,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onArrow(SpecialKey.ARROW_RIGHT)
            }
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolbarKey(
    label: String,
    active: Boolean,
    weight: Float,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    val scheme = MaterialTheme.colorScheme

    val bg = if (active) scheme.primary.copy(alpha = 0.22f) else scheme.surface
    val fg = if (active) scheme.primary else scheme.onSurface
    val borderColor = if (active) scheme.primary.copy(alpha = 0.6f) else scheme.outlineVariant.copy(alpha = 0.4f)

    Box(
        modifier = Modifier
            .weight(weight)
            .heightIn(min = 36.dp)
            .height(36.dp)
            .clip(shape)
            .background(bg, shape)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}
