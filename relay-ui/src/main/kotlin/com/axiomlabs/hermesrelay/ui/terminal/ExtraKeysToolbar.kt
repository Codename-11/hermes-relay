package com.axiomlabs.hermesrelay.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiomlabs.hermesrelay.core.terminal.SpecialKey

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
    onPageUp: (() -> Unit)? = null,
    onPageDown: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarKey("ESC", active = false, weight = 1.1f) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onEsc()
        }
        ToolbarKey("TAB", active = false, weight = 1.1f) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onTab()
        }
        ToolbarKey("CTRL", active = ctrlActive, weight = 1.2f) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onCtrlToggle()
        }
        ToolbarKey("ALT", active = altActive, weight = 1.0f) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onAltToggle()
        }

        Spacer(modifier = Modifier.width(4.dp))

        ToolbarKey("←", active = false, weight = 1.0f) { onArrow(SpecialKey.ARROW_LEFT) }
        ToolbarKey("↓", active = false, weight = 1.0f) { onArrow(SpecialKey.ARROW_DOWN) }
        ToolbarKey("↑", active = false, weight = 1.0f) { onArrow(SpecialKey.ARROW_UP) }
        ToolbarKey("→", active = false, weight = 1.0f) { onArrow(SpecialKey.ARROW_RIGHT) }

        onPageUp?.let {
            Spacer(modifier = Modifier.width(4.dp))
            ToolbarKey("PG↑", active = false, weight = 1.1f) { it() }
        }
        onPageDown?.let {
            ToolbarKey("PG↓", active = false, weight = 1.1f) { it() }
        }
    }
}

@Composable
private fun RowScope.ToolbarKey(
    label: String,
    active: Boolean,
    weight: Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val scheme = MaterialTheme.colorScheme
    val bg = if (active) scheme.primary.copy(alpha = 0.22f) else scheme.surface
    val fg = if (active) scheme.primary else scheme.onSurface
    val borderColor = if (active) scheme.primary.copy(alpha = 0.65f) else scheme.outlineVariant.copy(alpha = 0.45f)

    Box(
        modifier = Modifier
            .weight(weight)
            .heightIn(min = 40.dp)
            .height(40.dp)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
