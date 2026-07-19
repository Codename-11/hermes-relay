package com.hermesandroid.relay.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.relay.ui.theme.HermesRelayTheme

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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 42.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp),
            contentAlignment = Alignment.Center,
        ) {
            heroContent()
        }

        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 38.sp,
                lineHeight = 40.sp,
            ),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun FeatureHero(
    icon: ImageVector,
    title: String,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(176.dp)
                .clip(CircleShape)
                .background(
                    Color(0xFF7B55F6).copy(alpha = 0.07f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF7B55F6).copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.size(86.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                    ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            modifier = Modifier.size(46.dp),
                            tint = Color(0xFF7B55F6),
                        )
                    }
                }
            }
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
