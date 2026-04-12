package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

/**
 * Dedicated Appearance settings screen. Hosts theme picker (auto/light/dark),
 * font-scale preference, and animation toggles (sphere background, idle
 * animation, etc.).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val theme by connectionViewModel.theme.collectAsState()
    val isDarkTheme = isSystemInDarkTheme()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Theme section
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val themeOptions = listOf("auto", "light", "dark")
                    val themeLabels = listOf("Auto", "Light", "Dark")
                    val selectedIndex = themeOptions.indexOf(theme).coerceAtLeast(0)

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        themeOptions.forEachIndexed { index, option ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = themeOptions.size
                                ),
                                onClick = { connectionViewModel.setTheme(option) },
                                selected = index == selectedIndex
                            ) {
                                Text(themeLabels[index])
                            }
                        }
                    }

                    // ── Font size ──────────────────────────────────────────
                    //
                    // Discrete stops applied globally via LocalDensity.fontScale
                    // at the Compose theme root, plus pushed to xterm via
                    // window.setFontSize through TerminalWebView.
                    val fontScale by connectionViewModel.fontScale.collectAsState()
                    val fontScaleOptions = listOf(0.85f, 1.0f, 1.15f, 1.3f)
                    val fontScaleLabels = listOf("Small", "Normal", "Large", "Larger")
                    // Match the closest stop — float equality is fragile.
                    val selectedFontScaleIndex = fontScaleOptions
                        .withIndex()
                        .minByOrNull { kotlin.math.abs(it.value - fontScale) }
                        ?.index
                        ?: 1

                    Text(
                        text = "Font size",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        fontScaleOptions.forEachIndexed { index, option ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = fontScaleOptions.size
                                ),
                                onClick = { connectionViewModel.setFontScale(option) },
                                selected = index == selectedFontScaleIndex
                            ) {
                                Text(fontScaleLabels[index])
                            }
                        }
                    }

                    // Subtle preview at the chosen scale. We multiply the
                    // current bodyMedium fontSize by the selected stop so the
                    // preview reflects the user's choice immediately, even
                    // though everything else in the app already scales via
                    // LocalDensity once they tap a stop.
                    val previewBase = MaterialTheme.typography.bodyMedium
                    Text(
                        text = "Aa  —  sample text",
                        style = previewBase.copy(
                            fontSize = previewBase.fontSize * fontScaleOptions[selectedFontScaleIndex]
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Animation section
            Text(
                text = "Animation",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                val animEnabled by connectionViewModel.animationEnabled.collectAsState()
                val animBehindChat by connectionViewModel.animationBehindChat.collectAsState()

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Animation enabled toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ASCII sphere",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Show animated sphere on empty chat screen and ambient mode",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = animEnabled,
                            onCheckedChange = { connectionViewModel.setAnimationEnabled(it) }
                        )
                    }

                    HorizontalDivider()

                    // Behind chat toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (!animEnabled) Modifier.alpha(0.5f) else Modifier),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Behind messages",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Show subtle sphere animation behind chat messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = animBehindChat && animEnabled,
                            onCheckedChange = { connectionViewModel.setAnimationBehindChat(it) },
                            enabled = animEnabled
                        )
                    }
                }
            }
        }
    }
}
