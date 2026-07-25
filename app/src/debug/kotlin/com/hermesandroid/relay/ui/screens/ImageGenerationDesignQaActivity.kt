package com.hermesandroid.relay.ui.screens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.components.ImageGenerationPlaceholder
import com.hermesandroid.relay.ui.components.ImageGenerationResultTransition
import com.hermesandroid.relay.ui.components.ImageGenerationVisualStyle
import com.hermesandroid.relay.ui.theme.HermesRelayTheme

/**
 * Debug-build-only live host for fast image-generation motion tuning.
 *
 * Launch directly:
 * adb shell am start -n <applicationId>/
 *   com.hermesandroid.relay.ui.screens.ImageGenerationDesignQaActivity
 */
class ImageGenerationDesignQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themePreference = intent.getStringExtra("theme") ?: "auto"
        setContent {
            HermesRelayTheme(themePreference = themePreference) {
                ImageGenerationDesignQaScene(onBack = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageGenerationDesignQaScene(onBack: () -> Unit) {
    var restartKey by remember { mutableIntStateOf(0) }
    var durationMillis by remember { mutableIntStateOf(4_800) }
    var visualStyle by remember { androidx.compose.runtime.mutableStateOf(ImageGenerationVisualStyle.LatentGrid) }
    var showResult by remember { androidx.compose.runtime.mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image generation lab") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Live debug preview · no generation request",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    ImageGenerationVisualStyle.LatentGrid to "Grid",
                    ImageGenerationVisualStyle.ParticleOrb to "Orb",
                    ImageGenerationVisualStyle.Constellation to "Nodes",
                ).forEach { (style, label) ->
                    FilterChip(
                        selected = visualStyle == style,
                        onClick = { visualStyle = style },
                        label = { Text(label) },
                    )
                }
            }
            key(restartKey, durationMillis, visualStyle) {
                val startedAtMillis = remember { System.currentTimeMillis() }
                ImageGenerationResultTransition(
                    generating = !showResult,
                    startedAtMillis = startedAtMillis,
                    animationDurationMillis = durationMillis,
                    visualStyle = visualStyle,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.image_generation_transition_preview),
                            contentDescription = "Generated landscape preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Generated image",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "12.4s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(
                text = "Cycle speed",
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    7_200 to "Slow",
                    4_800 to "Normal",
                    3_200 to "Fast",
                ).forEach { (duration, label) ->
                    FilterChip(
                        selected = durationMillis == duration,
                        onClick = { durationMillis = duration },
                        label = { Text(label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        showResult = true
                    },
                    enabled = !showResult,
                ) {
                    Text("Reveal result")
                }
                Button(
                    onClick = {
                        showResult = false
                        restartKey++
                    },
                ) {
                    Text("Restart")
                }
            }
        }
    }
}
