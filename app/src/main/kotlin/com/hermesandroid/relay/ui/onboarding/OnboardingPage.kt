package com.hermesandroid.relay.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.ui.theme.gradientBorder

@Composable
fun OnboardingPage(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    heroContent: @Composable BoxScope.() -> Unit = {
        FeatureHero(
            icon = icon,
            title = title,
        )
    },
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val isDarkTheme = isSystemInDarkTheme()
    val heroShape = RoundedCornerShape(30.dp)
    val bodyShape = RoundedCornerShape(26.dp)
    val heroBrush = Brush.radialGradient(
        colors = if (isDarkTheme) {
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
                MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.98f),
            )
        } else {
            listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.70f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.60f),
            )
        }
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(232.dp)
                .gradientBorder(shape = heroShape, isDarkTheme = isDarkTheme),
            shape = heroShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(232.dp)
                    .background(heroBrush),
                contentAlignment = Alignment.Center
            ) {
                heroContent()
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .gradientBorder(shape = bodyShape, isDarkTheme = isDarkTheme),
            shape = bodyShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                content()
            }
        }
    }
}

@Composable
private fun FeatureHero(
    icon: ImageVector,
    title: String,
) {
    val isDarkTheme = isSystemInDarkTheme()
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(136.dp)
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkTheme) 0.14f else 0.10f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(74.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingPagePreview() {
    HermesRelayTheme {
        OnboardingPage(
            icon = Icons.AutoMirrored.Filled.Chat,
            title = "Talk to Your Agent",
            description = "Stream conversations with any Hermes profile. Ask questions, run tasks, and collaborate in real time."
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingPageWithContentPreview() {
    HermesRelayTheme {
        OnboardingPage(
            icon = Icons.AutoMirrored.Filled.Chat,
            title = "Let's Connect",
            description = "Enter your relay server URL to get started."
        ) {
            Text(
                text = "Custom content slot",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
