package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import coil3.compose.AsyncImage
import java.io.File

/**
 * The agent's "face" for a circular avatar badge: the active profile's local
 * icon ([LocalAgentIconPath]) if one is set, otherwise the first letter of [name]
 * on the badge's primary background. Fills its container — wrap it in the
 * circular `Surface`/`Box` that owns the shape and color.
 */
@Composable
fun AgentAvatarFace(name: String, letterStyle: TextStyle, modifier: Modifier = Modifier) {
    val iconPath = LocalAgentIconPath.current
    if (!iconPath.isNullOrBlank()) {
        AsyncImage(
            model = File(iconPath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "H",
                style = letterStyle,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
