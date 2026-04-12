package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.viewmodel.TerminalViewModel

/**
 * Chrome-style tab strip for the terminal screen.
 *
 * One numbered button per active tab plus a `+` button to open new tabs
 * (disabled when [TerminalViewModel.MAX_TABS] is reached).
 *
 *  - Active tab: primary background, on-primary text.
 *  - Inactive tab: surface-variant background, on-surface-variant text.
 *  - The active tab also shows a small × icon to close it without needing
 *    a long-press; long-press still works on any tab so users can close
 *    background tabs without switching to them first.
 *  - Closing the last remaining tab is silently a no-op (handled in
 *    [TerminalViewModel.closeTab]) — there's always at least one terminal.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalTabBar(
    tabs: List<TerminalViewModel.TabState>,
    activeTabId: Int,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tab in tabs) {
            val isActive = tab.tabId == activeTabId
            TerminalTabChip(
                tab = tab,
                isActive = isActive,
                canClose = tabs.size > 1,
                onClick = { onSelectTab(tab.tabId) },
                onLongPress = { if (tabs.size > 1) onCloseTab(tab.tabId) },
                onCloseClick = { if (tabs.size > 1) onCloseTab(tab.tabId) },
            )
        }

        Spacer(modifier = Modifier.width(2.dp))

        // New-tab button — visually distinct from numbered tabs (rounder,
        // plain icon) so it doesn't get mistaken for a fifth tab when the
        // user has 4 already.
        val canAddMore = tabs.size < TerminalViewModel.MAX_TABS
        IconButton(
            onClick = onNewTab,
            enabled = canAddMore,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "New terminal tab",
                tint = if (canAddMore) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalTabChip(
    tab: TerminalViewModel.TabState,
    isActive: Boolean,
    canClose: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onCloseClick: () -> Unit,
) {
    val bg = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (isActive) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(bg)
            .border(
                width = 1.dp,
                color = if (isActive) Color.Transparent else fg.copy(alpha = 0.15f),
                shape = shape,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = tab.tabId.toString(),
            color = fg,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
        )
        // Show a small × on the active tab so users can close it without
        // discovering the long-press gesture. Hidden when canClose is false
        // (last remaining tab) or on background tabs to keep the strip
        // compact.
        if (isActive && canClose) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(50))
                    .combinedClickable(
                        onClick = onCloseClick,
                        onLongClick = onCloseClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close tab ${tab.tabId}",
                    tint = fg,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
