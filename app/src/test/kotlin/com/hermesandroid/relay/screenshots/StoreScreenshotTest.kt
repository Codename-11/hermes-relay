package com.hermesandroid.relay.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Link
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
import androidx.compose.material.icons.filled.Refresh
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
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.DashboardConnectionStatus
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.PetBehaviorPreferences
import com.hermesandroid.relay.ui.components.ChatInputBar
import com.hermesandroid.relay.ui.components.ChatInputPickerControl
import com.hermesandroid.relay.ui.components.ChatInputTrailing
import com.hermesandroid.relay.ui.components.ContextMeterBar
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.components.MorphingSphere
import com.hermesandroid.relay.ui.components.RelayChromeIconButton
import com.hermesandroid.relay.ui.components.RelayStatusStrip
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.SphereReactivity
import com.hermesandroid.relay.ui.components.VoiceModeOverlay
import com.hermesandroid.relay.ui.components.FloatingPetCompanion
import com.hermesandroid.relay.ui.components.SessionDrawerContent
import com.hermesandroid.relay.ui.components.pet.PetLogicalEdge
import com.hermesandroid.relay.ui.components.pet.PetPlacement
import com.hermesandroid.relay.ui.screens.ConnectionsSettingsScreen
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.VoicePresentationMode
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.AvatarSource
import com.hermesandroid.relay.ui.components.avatar.FrameSequenceClip
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import com.hermesandroid.relay.ui.components.avatar.LocalAvailablePets
import com.hermesandroid.relay.ui.components.avatar.LocalFloatingPet
import com.hermesandroid.relay.ui.components.avatar.PetAvatar
import com.hermesandroid.relay.ui.components.LocalSphereSkin
import com.hermesandroid.relay.ui.components.SphereRegistry
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import com.hermesandroid.relay.viewmodel.RelayUiState
import androidx.compose.foundation.Image
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Deterministic, host-side marketing frames with Roborazzi.
 *
 * No device, no emulator, no live server, no status bar, no clipping. Frames
 * are assembled from the REAL app chrome (TopAppBar + RelayChromeIconButton,
 * ContextMeterBar, RelayModeStrip, ChatInputBar, RelayStatusStrip) and REAL
 * leaf components (MorphingSphere, MessageBubble) fed mock, public-safe data —
 * so what renders is the app's own UI, not a mock-up. The sphere is pinned with
 * fixedTime/fixedColorPhase for pixel-identical renders; the same scene
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

    // The grouped chat scene is the canonical store frame: it exercises the
    // current wider bubbles, assistant avatar, grouped turns, code rendering,
    // model/effort controls, and the complete production chrome.
    @Test fun s02_chat_brand() {
        val pet = marketingPet()
        capture("02_chat", "hermes-relay") { ChatWithPetScene(pet) }
    }
    // Keep a second filename plus a light-theme render as visual-regression
    // diagnostics; only 02_chat is promoted to the store listing.
    @Test fun s09_blend_chat() = capture("09_blend_chat", "hermes-relay") { BlendChatScene() }
    @Test fun s09_blend_chat_light() = capture("09_blend_chat_nous-blue", "nous-blue") { BlendChatScene() }
    @Test fun s03_voice() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                CompositionLocalProvider(
                    LocalSphereSkin provides SphereRegistry.Adaptive,
                    LocalAgentAvatar provides FrozenMarketingSphere,
                ) {
                    VoiceScene()
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().captureRoboImage("build/store-shots/03_voice.png")
    }
    // Real screen (1:1, auto-updates on layout changes). ConnectionViewModel
    // takes only an Application; Robolectric provides it. Renders every section
    // the production Appearance screen has — theme grid, mode, font, animation,
    // avatar, and skins — not a hand-rebuilt slice.
    @Test fun s05_themes() {
        val vm = ConnectionViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                CompositionLocalProvider(LocalSphereSkin provides SphereRegistry.Adaptive) {
                    AppearanceSettingsScreen(
                        connectionViewModel = vm,
                        onBack = {},
                        initialCustomizerExpanded = true,
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("build/store-shots/05_themes.png")
    }

    @Test fun s05_theme_customizer() {
        val vm = ConnectionViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                CompositionLocalProvider(LocalSphereSkin provides SphereRegistry.Adaptive) {
                    AppearanceSettingsScreen(
                        connectionViewModel = vm,
                        onBack = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Customize Relay").performScrollTo()
        compose.onNodeWithText("Customize Relay").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Shape").performScrollTo()
        compose.onRoot().captureRoboImage("build/store-shots/05_theme_customizer.png")
    }
    @Test fun s06_manage() = capture("06_manage", "hermes-relay") { ManageScene() }
    @Test fun s04_sessions() = capture("04_sessions", "hermes-relay") { SessionsScene() }
    @Test fun s07_connections() = capture("07_connections", "hermes-relay") { ConnectionsScene() }
    // Real Appearance screen scrolled to the new Font picker — proves the
    // bundled Inter/Nunito faces load as visibly distinct previews (vs System).
    @Test fun s10_font_picker() {
        val vm = ConnectionViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                CompositionLocalProvider(LocalSphereSkin provides SphereRegistry.Adaptive) {
                    AppearanceSettingsScreen(connectionViewModel = vm, onBack = {})
                }
            }
        }
        compose.onNodeWithText("Nunito").performScrollTo()
        compose.onRoot().captureRoboImage("build/store-shots/10_font_picker.png")
    }

    // Same real Appearance screen, scrolled to the avatar + sphere-skin sections.
    @Test fun s08_appearance() {
        val vm = ConnectionViewModel(ApplicationProvider.getApplicationContext<Application>())
        val pet = marketingPet()
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                CompositionLocalProvider(
                    LocalSphereSkin provides SphereRegistry.Adaptive,
                    LocalAvailablePets provides listOf(pet),
                    LocalFloatingPet provides pet,
                ) {
                    AppearanceSettingsScreen(connectionViewModel = vm, onBack = {})
                }
            }
        }
        // The earlier frame scrolled past the pet controls and showed only the
        // sphere-skin grid. Frame the independently selected floating companion,
        // its real PetAvatar preview, Petdex/import actions, and tuning controls.
        compose.onNodeWithText("Browse Petdex").performScrollTo()
        compose.onRoot().captureRoboImage("build/store-shots/08_appearance.png")
    }

    /** Public-safe, deterministic one-frame pet rendered through PetAvatar. */
    private fun marketingPet(): PetAvatar {
        val output = File("build/store-fixtures/pixel-companion.png")
        output.parentFile?.mkdirs()

        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)

        paint.color = AndroidColor.rgb(126, 87, 255)
        canvas.drawRoundRect(36f, 48f, 156f, 154f, 34f, 34f, paint)
        canvas.drawCircle(58f, 48f, 27f, paint)
        canvas.drawCircle(134f, 48f, 27f, paint)
        paint.color = AndroidColor.rgb(177, 196, 255)
        canvas.drawOval(54f, 76f, 82f, 108f, paint)
        canvas.drawOval(110f, 76f, 138f, 108f, paint)
        paint.color = AndroidColor.rgb(8, 9, 14)
        canvas.drawCircle(68f, 92f, 7f, paint)
        canvas.drawCircle(124f, 92f, 7f, paint)
        paint.strokeWidth = 7f
        paint.style = AndroidPaint.Style.STROKE
        canvas.drawArc(76f, 98f, 116f, 134f, 12f, 156f, false, paint)
        paint.style = AndroidPaint.Style.FILL
        paint.color = AndroidColor.rgb(87, 218, 136)
        canvas.drawRoundRect(50f, 146f, 86f, 170f, 12f, 12f, paint)
        canvas.drawRoundRect(106f, 146f, 142f, 170f, 12f, 12f, paint)

        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val clip = FrameSequenceClip(files = listOf(output), fps = 1f)
        val stateClips = SphereState.entries.associateWith { clip }
        return PetAvatar(
            id = "pixel-companion",
            label = "Pixel",
            description = "A public-safe custom companion fixture",
            reactivity = SphereReactivity(voice = true, tools = true, intensity = true, gaze = false),
            activityClips = stateClips,
            workingClip = clip,
        )
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

// "Blend" chat — a real grouped thread so the refresh is visible: the assistant
// group shows the avatar once (first message), the second assistant message
// flat-tops under it with no avatar, a fenced code block exercises the code
// surface, and the compact 340dp cap + tightened density match production.
@Composable
private fun BlendChatScene() = StoreCockpit(contextUsage = 0.06f) {
    val thread = MockChat.blendThread
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        thread.forEachIndexed { i, m ->
            val first = i == 0 || thread[i - 1].role != m.role
            val last = i == thread.lastIndex || thread[i + 1].role != m.role
            MessageBubble(
                message = m,
                modifier = Modifier.padding(top = if (first) 6.dp else 1.dp),
                maxBubbleWidth = 340.dp,
                isFirstInGroup = first,
                isLastInGroup = last,
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Real-chrome cockpit — TopAppBar + ContextMeterBar wrap the content, with
//  ChatInputBar + RelayStatusStrip at the foot. Shared by chat-context scenes.
// ════════════════════════════════════════════════════════════════════════════

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StoreCockpit(
    contextUsage: Float? = null,
    showInput: Boolean = true,
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
private fun VoiceScene() = VoiceModeOverlay(
    uiState = VoiceUiState(
        voiceMode = true,
        state = VoiceState.Speaking,
        amplitude = 0.58f,
        outputAudioActive = true,
        interactionMode = InteractionMode.Continuous,
    ),
    onMicTap = {},
    onMicRelease = {},
    onInterrupt = {},
    onDismiss = {},
    onModeChange = {},
    onClearError = {},
    transcriptMessages = MockChat.voiceThread,
    showThinking = true,
    voiceEngineMode = "hermes_voice_output",
    voiceOutputProvider = "OpenAI",
    voiceOutputModel = "gpt-4o-mini-tts",
    voiceOutputVoice = "alloy",
    voiceProfileName = "default",
    voiceOutputEnabled = true,
    voiceOutputFallbackEnabled = true,
    presentationMode = VoicePresentationMode.Focus,
)

/** The real sphere renderer pinned to one frame for pixel-identical marketing output. */
private object FrozenMarketingSphere : AgentAvatar {
    override val id = "marketing-sphere"
    override val label = "Sphere"
    override val description = "Deterministic marketing renderer"
    override val source = AvatarSource.BUILT_IN
    override val reactivity = SphereReactivity(voice = true, tools = true, intensity = true, gaze = false)

    @Composable
    override fun Render(state: AvatarRenderState, modifier: Modifier) {
        MorphingSphere(
            modifier = modifier,
            state = state.state,
            intensity = state.intensity,
            toolCallBurst = state.toolCallBurst,
            voiceAmplitude = state.voiceAmplitude,
            voiceMode = state.voiceMode,
            fixedTime = 5f,
            fixedColorPhase = 1f,
        )
    }
}

/** Production chat chrome and production app-level companion layered exactly as RelayApp does. */
@Composable
private fun ChatWithPetScene(pet: AgentAvatar) {
    Box(Modifier.fillMaxSize()) {
        BlendChatScene()
        FloatingPetCompanion(
            pet = pet,
            state = AvatarRenderState(state = SphereState.Idle, paused = true),
            placement = PetPlacement(PetLogicalEdge.End, 0.63f),
            roamingEnabled = false,
            roamingAllowed = false,
            behaviorPreferences = PetBehaviorPreferences(),
            surfaceScrolling = false,
            compact = false,
            animationEnabled = false,
            appForeground = true,
            route = null,
            visitRequest = null,
            onVisitRequestConsumed = {},
            onPlacementChanged = {},
            onRoamingEnabledChanged = {},
            onResetPlacement = {},
            onHide = {},
            onOpenAppearance = {},
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ManageScene() {
    // Manage's own chrome (matches DashboardManagementScreen): a back arrow to
    // Chat + Terminal/Settings/Refresh actions — no hamburger, no context meter,
    // and no mode strip (removed app-wide; Manage is reached from Settings now).
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            navigationIcon = {
                RelayChromeIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat", onClick = {}, modifier = Modifier.padding(start = 4.dp))
            },
            title = { Text("Manage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
            actions = {
                RelayChromeIconButton(Icons.Filled.Code, "Terminal", onClick = {}, modifier = Modifier.padding(end = 4.dp))
                RelayChromeIconButton(Icons.Filled.Tune, "Settings", onClick = {}, modifier = Modifier.padding(end = 4.dp))
                IconButton(onClick = {}) { Icon(Icons.Filled.Refresh, "Refresh", tint = RelayRefresh.Paper) }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = RelayRefresh.Background.copy(alpha = 0.96f)),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
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
        RelayStatusStrip(leading = "⚡ Gateway  ·  LAN", trailing = "gpt-5.5 / profile: default")
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

    val voiceThread = listOf(
        ChatMessage(
            id = "voice-user",
            role = MessageRole.USER,
            content = "Give me the quick version of today's project changes.",
            timestamp = 0L,
        ),
        ChatMessage(
            id = "voice-assistant",
            role = MessageRole.ASSISTANT,
            content = "Appearance is more flexible, pets can roam safely, and voice now keeps the conversation visible while you work hands-free.",
            timestamp = 0L,
            agentName = "Hermes",
            badges = listOf("Voice"),
        ),
    )

    // A grouped thread for the "Blend" capture: user → two-message assistant
    // group (avatar once, code block in the first) → user follow-up.
    val blendThread = listOf(
        ChatMessage(
            id = "bu1",
            role = MessageRole.USER,
            content = "How should I handle a flaky network call?",
            timestamp = 0L,
        ),
        ChatMessage(
            id = "ba1",
            role = MessageRole.ASSISTANT,
            content = """
                Use bounded exponential backoff and retry only transient failures:

                ```kotlin
                val delayMs = minOf(1_000L shl attempt, 8_000L)
                delay(delayMs)
                ```

                Keep the retry budget small, add a timeout, and surface the final error clearly.
            """.trimIndent(),
            timestamp = 0L,
            agentName = "Hermes",
            badges = listOf("Gateway"),
        ),
        ChatMessage(
            id = "ba2",
            role = MessageRole.ASSISTANT,
            content = "Want me to adapt this to the networking layer in your project?",
            timestamp = 0L,
            inputTokens = 138_010,
            outputTokens = 212,
        ),
        ChatMessage(
            id = "bu2",
            role = MessageRole.USER,
            content = "Yes — keep cancellation and offline mode intact.",
            timestamp = 0L,
        ),
    )
}

// ════════════════════════════════════════════════════════════════════════════
//  Scenes 04 / 07 / 08
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun SessionsScene() {
    // The production drawer intentionally caps itself at 320dp inside a 360dp
    // phone. Keep the full app viewport as the capture root so Roborazzi does
    // not shrink-wrap the image to the drawer's intrinsic width.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
        SessionDrawerContent(
            sessions = marketingSessions,
            currentSessionId = "morning-focus",
            scopeTitle = "Hermes",
            scopeSubtitle = "Server default sessions",
            animationEnabled = false,
            threadsCapabilityActive = true,
            onNewThread = {},
            onNewChat = {},
            onSelectSession = {},
            onDeleteSession = {},
            onRenameSession = { _, _ -> },
        )
    }
}

@Composable
private fun ConnectionsScene() = ConnectionsSettingsScreen(
    connections = marketingConnections,
    activeConnectionId = "home",
    activeRelayUiState = RelayUiState.Connected,
    onOpenConnection = {},
    onAddConnection = {},
    onBack = {},
)

private val marketingSessions = listOf(
    ChatSession("morning-focus", "Morning focus tips", "gpt-5.5", 8, lastActivityAt = 1_780_000_000_000, pinned = true),
    ChatSession("android-polish", "Android appearance polish", "gpt-5.5", 23, lastActivityAt = 1_779_999_900_000, source = "phone"),
    ChatSession("weekly-report", "Weekly report draft", "gpt-5.5", 11, lastActivityAt = 1_779_999_800_000),
    ChatSession("voice-notes", "Voice workflow notes", "gpt-5.5", 17, lastActivityAt = 1_779_999_700_000),
    ChatSession("trip-planning", "Trip planning", "gpt-5.5", 31, lastActivityAt = 1_779_999_600_000),
)

private val marketingConnections = listOf(
    Connection(
        id = "home",
        label = "Hermes",
        apiServerUrl = "https://hermes.example.com:8642",
        relayUrl = "wss://hermes.example.com:8767",
        dashboardUrl = "https://hermes.example.com:9119",
        tokenStoreKey = "marketing-home",
        dashboardAuthRequired = true,
        dashboardLastStatus = DashboardConnectionStatus(
            reachable = true,
            authenticated = true,
            gatewayTicketAvailable = true,
            gatewayMode = "gateway",
            profiles = listOf("default", "work"),
        ),
        transportHint = "tailscale",
        pairedAt = 1_779_900_000_000,
    ),
    Connection(
        id = "lab",
        label = "Lab server",
        apiServerUrl = "https://lab.example.com:8642",
        relayUrl = "wss://lab.example.com:8767",
        dashboardUrl = "https://lab.example.com:9119",
        tokenStoreKey = "marketing-lab",
        pairedAt = 1_779_800_000_000,
    ),
)

// NOTE: scenes 05 + 08 render the REAL AppearanceSettingsScreen (1:1, see the
// s05_themes / s08_appearance test methods). The earlier hand-built theme/skin
// frames were removed to avoid a confusing duplicate of the production screen.
