package com.hermesandroid.relay.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.screens.AppearanceSettingsScreen
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.components.ChatInputBar
import com.hermesandroid.relay.ui.components.ChatInputPickerControl
import com.hermesandroid.relay.ui.components.ChatInputTrailing
import com.hermesandroid.relay.ui.components.ContextMeterBar
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.components.MorphingSphere
import com.hermesandroid.relay.ui.components.RelayChromeIconButton
import com.hermesandroid.relay.ui.components.RelayModeStrip
import com.hermesandroid.relay.ui.components.RelayPrimaryMode
import com.hermesandroid.relay.ui.components.RelayStatusStrip
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.components.LocalSphereSkin
import com.hermesandroid.relay.ui.components.SphereRegistry
import androidx.compose.foundation.Image
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Deterministic, host-side marketing frames with Roborazzi.
 *
 * No device, no emulator, no live server, no status bar, no clipping. Frames
 * are assembled from the REAL app chrome (TopAppBar + RelayChromeIconButton,
 * ContextMeterBar, RelayModeStrip, ChatInputBar, RelayStatusStrip) and REAL
 * leaf components (MorphingSphere, MessageBubble) fed mock, public-safe data —
 * so what renders is the app's own UI, not a mock-up. The sphere is pinned with
 * fixedTime/fixedColorPhase for byte-identical renders; the same scene
 * re-renders in any theme by swapping appThemeId.
 *
 * Run: ./gradlew :app:testGooglePlayDebugUnitTest --tests "*StoreScreenshotTest*"
 * Out: app/build/store-shots/ — each scene a 1080x2160 PNG (exactly 2:1)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi") // 1080x2160 px @ density 3.0
class StoreScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, themeId: String = "hermes-relay", body: @Composable () -> Unit) {
        compose.setContent {
            HermesRelayTheme(appThemeId = themeId, themePreference = "dark") {
                // Adaptive is the app's real default skin (resolve("auto") -> Adaptive);
                // it recolors to the active theme. The preview/test fallback is Classic,
                // which mismatches the app and reads poorly on light themes.
                CompositionLocalProvider(LocalSphereSkin provides SphereRegistry.Adaptive) {
                    body()
                }
            }
        }
        compose.onRoot().captureRoboImage("build/store-shots/$name.png")
    }

    @Test fun s01_landing_brand() = capture("01_landing", "hermes-relay") { LandingScene() }
    @Test fun s01_landing_nousBlue() = capture("01_landing_nous-blue", "nous-blue") { LandingScene() }
    @Test fun s01_landing_cyberpunk() = capture("01_landing_cyberpunk", "cyberpunk") { LandingScene() }

    @Test fun s02_chat_brand() = capture("02_chat", "hermes-relay") { ChatScene() }
    @Test fun s03_voice() = capture("03_voice", "hermes-relay") { VoiceScene() }
    // Real screen (1:1, auto-updates on layout changes). ConnectionViewModel
    // takes only an Application; Robolectric provides it. Renders every section
    // the production Appearance screen has — theme grid, mode, font, animation,
    // avatar, and skins — not a hand-rebuilt slice.
    @Test fun s05_themes() {
        val vm = ConnectionViewModel(ApplicationProvider.getApplicationContext<Application>())
        capture("05_themes", "hermes-relay") {
            AppearanceSettingsScreen(connectionViewModel = vm, onBack = {})
        }
    }
    @Test fun s06_manage() = capture("06_manage", "hermes-relay") { ManageScene() }
    @Test fun s04_sessions() = capture("04_sessions", "hermes-relay") { SessionsScene() }
    @Test fun s07_connections() = capture("07_connections", "hermes-relay") { ConnectionsScene() }
    // Same real Appearance screen, scrolled to the avatar + sphere-skin sections.
    @Test fun s08_appearance() {
        val vm = ConnectionViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                CompositionLocalProvider(LocalSphereSkin provides SphereRegistry.Adaptive) {
                    AppearanceSettingsScreen(connectionViewModel = vm, onBack = {})
                }
            }
        }
        // Scroll to a skin card (unique to the skin grid, near the bottom) so the
        // viewport frames the Agent avatar + Sphere skin sections together.
        compose.onNodeWithText("Solar").performScrollTo()
        compose.onRoot().captureRoboImage("build/store-shots/08_appearance.png")
    }

    // Theme gallery — the same content-rich chat scene reskinned by every theme
    // (content scenes carry light themes better than the sphere hero). One test
    // per theme: createComposeRule allows a single setContent per test method.
    @Test fun g_hermesRelay() = capture("gallery_hermes-relay", "hermes-relay") { ChatScene() }
    @Test fun g_hermesTeal() = capture("gallery_hermes-teal", "hermes-teal") { ChatScene() }
    @Test fun g_nousBlue() = capture("gallery_nous-blue", "nous-blue") { ChatScene() }
    @Test fun g_midnight() = capture("gallery_midnight", "midnight") { ChatScene() }
    @Test fun g_ember() = capture("gallery_ember", "ember") { ChatScene() }
    @Test fun g_mono() = capture("gallery_mono", "mono") { ChatScene() }
    @Test fun g_cyberpunk() = capture("gallery_cyberpunk", "cyberpunk") { ChatScene() }
    @Test fun g_rose() = capture("gallery_rose", "rose") { ChatScene() }
}

// ════════════════════════════════════════════════════════════════════════════
//  Scenes
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun LandingScene() = StoreCockpit {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(240.dp)) {
            MorphingSphere(Modifier.fillMaxSize(), state = SphereState.Idle, intensity = 0.4f, fixedTime = 5f, fixedColorPhase = 0.5f)
        }
        Text(
            "Start a conversation",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 24.dp)
        )
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SuggestionChip("What can you do?")
            SuggestionChip("Help me code")
        }
    }
}

@Composable
private fun ChatScene() = StoreCockpit(contextUsage = 0.04f) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MessageBubble(message = MockChat.userMsg)
        MessageBubble(message = MockChat.assistantMsg)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Real-chrome cockpit — TopAppBar + ContextMeterBar + RelayModeStrip wrap the
//  content, with ChatInputBar + RelayStatusStrip at the foot. Shared by scenes.
// ════════════════════════════════════════════════════════════════════════════

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StoreCockpit(
    contextUsage: Float? = null,
    showInput: Boolean = true,
    selectedMode: RelayPrimaryMode = RelayPrimaryMode.Chat,
    content: @Composable () -> Unit
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.Menu, "Sessions", tint = RelayRefresh.Paper)
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(34.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                        Image(painterResource(R.drawable.splash_icon), contentDescription = null, modifier = Modifier.padding(3.dp))
                    }
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("Hermes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("gpt-5.5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            },
            actions = {
                RelayChromeIconButton(Icons.Filled.Bolt, "Approvals off", onClick = {}, tint = RelayRefresh.Amber, borderColor = RelayRefresh.Amber.copy(alpha = 0.5f), modifier = Modifier.padding(end = 4.dp))
                RelayChromeIconButton(Icons.Filled.Code, "Terminal", onClick = {}, modifier = Modifier.padding(end = 4.dp))
                RelayChromeIconButton(Icons.Filled.Tune, "Settings", onClick = {}, modifier = Modifier.padding(end = 4.dp))
                RelayChromeIconButton(Icons.Filled.MoreVert, "More", onClick = {}, modifier = Modifier.padding(end = 4.dp))
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = RelayRefresh.Background.copy(alpha = 0.96f))
        )
        ContextMeterBar(usedFraction = contextUsage, usedTokens = 37_000, maxTokens = 1_050_000)
        RelayModeStrip(selected = selectedMode, onModeSelected = {})
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        if (showInput) {
            ChatInputBar(
                value = "",
                onValueChange = {},
                placeholder = "Message…",
                trailing = ChatInputTrailing.VOICE,
                onSend = {}, onVoice = {}, onStop = {},
                onAttachPhotos = {}, onAttachFiles = {}, onAttachCamera = {}, onPasteImage = {}, onLongPressAttach = {},
                charLimit = 4000,
                caption = null,
                voiceReady = true,
                showVoiceHint = false,
                onVoiceHintShown = {},
                isDarkTheme = dark,
                modelControl = ChatInputPickerControl(value = "gpt-5.5", contentDescription = "Select model", options = emptyList()),
                effortControl = ChatInputPickerControl(value = "High", contentDescription = "Select reasoning effort", options = emptyList()),
            )
        }
        RelayStatusStrip(leading = "⚡ Gateway  ·  LAN", trailing = "gpt-5.5 / profile: default")
    }
}

// ── Settings/sub-screen scaffold: ← Title bar + scrollable content + strip ───
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StoreSettings(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = RelayRefresh.Paper)
                }
            },
            title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = RelayRefresh.Background.copy(alpha = 0.96f)),
        )
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        RelayStatusStrip(leading = "⚡ Gateway  ·  LAN", trailing = "gpt-5.5 / profile: default")
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Scenes 03 / 05 / 06
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun VoiceScene() {
    // Full-screen voice modal — sphere hero + transcript prompt + mic FAB.
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(androidx.compose.material.icons.Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text("  Voice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("   Realtime Agent / default profile", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(300.dp).padding(top = 24.dp)) {
            MorphingSphere(Modifier.fillMaxSize(), state = SphereState.Listening, intensity = 0.5f, voiceAmplitude = 0.4f, voiceMode = true, fixedTime = 5f, fixedColorPhase = 1.0f)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Text("Tap the mic to speak", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.padding(vertical = 40.dp).size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(androidx.compose.material.icons.Icons.Filled.Mic, "Mic", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun ManageScene() = StoreCockpit(showInput = false, selectedMode = RelayPrimaryMode.Manage) {
    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        com.hermesandroid.relay.ui.components.RelayHeroPanel(title = "Relay Hub", subtitle = "Dashboard · skills · profiles · models")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.hermesandroid.relay.ui.components.RelayMetricCard("123", "skills", Modifier.weight(1f), valueColor = MaterialTheme.colorScheme.onSurface)
            com.hermesandroid.relay.ui.components.RelayMetricCard("ready", "dashboard", Modifier.weight(1f), valueColor = RelayRefresh.Green)
            com.hermesandroid.relay.ui.components.RelayMetricCard("0.17.0", "server", Modifier.weight(1f), valueColor = MaterialTheme.colorScheme.onSurface)
        }
        val ic = androidx.compose.material.icons.Icons.Filled
        com.hermesandroid.relay.ui.components.RelayNavTile(ic.Link, "Connections", "Pair, switch, verify routes", {})
        com.hermesandroid.relay.ui.components.RelayNavTile(ic.Person, "Profiles", "SOUL, memory, skills, sessions", {})
        com.hermesandroid.relay.ui.components.RelayNavTile(ic.AutoAwesome, "Skills + Tools", "Browse, enable, configure", {})
        com.hermesandroid.relay.ui.components.RelayNavTile(ic.Schedule, "Automations", "Cron, background runs, delivery", {})
        com.hermesandroid.relay.ui.components.RelayNavTile(ic.Code, "MCP Servers", "Servers, status, tools", {})
        com.hermesandroid.relay.ui.components.RelayNavTile(ic.Tune, "Models", "Pick provider + default model", {})
    }
}

@Composable
private fun SuggestionChip(label: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(18.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

// ── Mock, public-safe chat content ──────────────────────────────────────────
private object MockChat {
    val userMsg = ChatMessage(
        id = "u1",
        role = MessageRole.USER,
        content = "Give me three quick tips for a focused morning",
        timestamp = 0L
    )
    val assistantMsg = ChatMessage(
        id = "a1",
        role = MessageRole.ASSISTANT,
        content = """
            1. **Pick one real win before checking feeds.** One task that makes the day easier if it's done by 10.
            2. **Block the first 45–90 minutes.** Phone away, tabs closed, notifications off. Protect the clean brain window.
            3. **Start with a setup ritual, not "motivation."** Coffee, water, desk clear, timer on, first file open.
        """.trimIndent(),
        timestamp = 0L,
        agentName = "Hermes",
        badges = listOf("Voice"),
        inputTokens = 137_200,
        outputTokens = 206
    )
}

// ════════════════════════════════════════════════════════════════════════════
//  Scenes 04 / 07 / 08
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun SessionsScene() {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Server default sessions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text("Connection: Hermes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimary)
                Text("  New Chat", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text("  Search sessions or id…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("All", true); FilterChip("Pinned", false); FilterChip("Archive", false)
        }
        SessionRow("Morning focus tips", "Active 4m ago", "2 msgs")
        SessionRow("Weekly report draft", "Active 18m ago", "11 msgs")
        SessionRow("Trip planning", "Active 1h ago", "23 msgs")
        SessionRow("Code review notes", "Active 3h ago", "6 msgs")
        SessionRow("Recipe ideas", "Active 5h ago", "9 msgs")
        SessionRow("Untitled", "Active yesterday", "1 msg")
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

@Composable
private fun SessionRow(title: String, active: String, msgs: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$active  ·  $msgs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
        Icon(Icons.Filled.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ConnectionsScene() = StoreSettings("Connections") {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hermes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                com.hermesandroid.relay.ui.components.RelayStatusPill("Active", active = true)
            }
            Text("Connected  ·  Active: LAN  ·  LAN + Tailscale", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Rename", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("Re-pair", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("Revoke", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("Remove", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    Text("Features", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    Text("What this connection can do. Routes only choose how the phone reaches Hermes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            FeatureRow("Vanilla Hermes API", "Ready", true)
            FeatureRow("Dashboard", "Signed in", true)
            FeatureRow("Vanilla Hermes voice", "Ready", true)
            FeatureRow("Relay tools", "Ready", true)
            FeatureRow("Terminal", "Ready", true)
            FeatureRow("Secure proxy", "Not advertised", false)
        }
    }
    Text("Route", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Current: LAN", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("192.168.1.50:8642", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Re-check", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun FeatureRow(name: String, status: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) RelayRefresh.Green else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
        Text("  $name", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(status, style = MaterialTheme.typography.labelMedium, color = if (ok) RelayRefresh.Green else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// NOTE: scenes 05 + 08 render the REAL AppearanceSettingsScreen (1:1, see the
// s05_themes / s08_appearance test methods). The earlier hand-built theme/skin
// frames were removed to avoid a confusing duplicate of the production screen.
