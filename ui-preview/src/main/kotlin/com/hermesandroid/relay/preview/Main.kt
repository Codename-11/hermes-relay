package com.hermesandroid.relay.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.axiomlabs.hermesrelay.ui.sphere.SphereState

/**
 * Desktop preview harness for fast, device-free UI iteration with
 * Compose Hot Reload. NOT shipped — see ui-preview/README.md.
 *
 * Run it from the IDE "Run with Compose Hot Reload" gutter (recommended — edits
 * to this module OR to the shared `MorphingSphereCore.kt` reload live), or cold
 * via `./gradlew :ui-preview:run`.
 *
 * To add a new component to the gallery: hoist it into a `@Composable` that takes
 * only plain values (no ViewModel / no Android Context), drop it into the preview
 * area below, and feed it fake state from the controls. Anything that touches a
 * ViewModel or the network must be faked — desktop has no Android runtime.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Hermes-Relay — UI Preview",
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            PreviewGallery()
        }
    }
}

@Composable
private fun PreviewGallery() {
    var state by remember { mutableStateOf(SphereState.Idle) }
    var intensity by remember { mutableFloatStateOf(0f) }
    var voiceAmplitude by remember { mutableFloatStateOf(0f) }
    var voiceMode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)).padding(16.dp)) {
        // ── State selector ───────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SphereState.entries.forEach { s ->
                Button(
                    onClick = { state = s },
                    colors = if (s == state) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) { Text(s.name) }
            }
        }

        // ── Live controls ────────────────────────────────────────────────
        LabeledSlider("intensity", intensity) { intensity = it }
        LabeledSlider("voiceAmplitude", voiceAmplitude) {
            voiceAmplitude = it
            voiceMode = it > 0f
        }

        // ── Preview surface ──────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            DesktopSphere(
                modifier = Modifier.fillMaxSize(),
                state = state,
                intensity = intensity,
                voiceAmplitude = voiceAmplitude,
                voiceMode = voiceMode,
            )
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(140.dp), color = Color.White)
        Slider(value = value, onValueChange = onChange, modifier = Modifier.weight(1f))
        Text("%.2f".format(value), Modifier.width(48.dp), color = Color.White)
    }
}
