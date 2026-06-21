package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.hermesandroid.relay.ui.theme.LocalBrand
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.components.LocalAvailableSphereSkins
import com.hermesandroid.relay.ui.components.SphereRegistry
import com.hermesandroid.relay.ui.components.SphereSkin
import com.hermesandroid.relay.ui.components.SphereSkinSource
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.AvatarSource
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import com.hermesandroid.relay.ui.components.avatar.LocalAvailableAvatars
import com.hermesandroid.relay.ui.components.avatar.SphereAvatar
import com.hermesandroid.relay.ui.theme.AppTheme
import com.hermesandroid.relay.ui.theme.AppThemes
import com.hermesandroid.relay.ui.theme.BrandPalette
import com.hermesandroid.relay.ui.theme.ThemeMode
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dedicated Appearance settings screen. Hosts theme picker (auto/light/dark),
 * font-scale preference, and animation toggles (sphere background, idle
 * animation, etc.).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val theme by connectionViewModel.theme.collectAsState()
    val appThemeId by connectionViewModel.appTheme.collectAsState()
    val selectedTheme = AppThemes.byId(appThemeId)
    val isDarkTheme = LocalBrand.current.isDark

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<AgentAvatar?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { connectionViewModel.importPetFromZip(it) } }

    // Re-scan pets/ when this screen opens (surfaces a pack added out-of-band,
    // e.g. via adb), and relay add/remove results as snackbars.
    LaunchedEffect(Unit) { connectionViewModel.refreshAgentAvatars() }
    LaunchedEffect(Unit) {
        connectionViewModel.avatarEvents.collect { snackbarHostState.showSnackbar(it) }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove pet?") },
            text = { Text("Remove “${target.label}”? You can re-import it later.") },
            confirmButton = {
                TextButton(onClick = {
                    connectionViewModel.deleteUserAvatar(target.id, target.label)
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Theme gallery section
            Text(
                text = "Theme",
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
                        text = "Pick a look. The brand chrome, accents, and chat " +
                            "background all follow your choice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppThemes.ALL.forEach { appTheme ->
                            ThemeSwatchChip(
                                appTheme = appTheme,
                                selected = appTheme.id == selectedTheme.id,
                                onClick = { connectionViewModel.setAppTheme(appTheme.id) },
                            )
                        }
                    }
                }
            }

            // Appearance (mode + font) section
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
                        text = "Light / Dark",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val themeOptions = listOf("auto", "light", "dark")
                    val themeLabels = listOf("Auto", "Light", "Dark")
                    val selectedIndex = themeOptions.indexOf(theme).coerceAtLeast(0)
                    // The mode toggle only applies to themes that ship both a
                    // light and dark palette (Hermes Relay). Fixed-mode themes
                    // are their own complete look, so we disable + explain.
                    val modeApplies = selectedTheme.mode == ThemeMode.BOTH

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        themeOptions.forEachIndexed { index, option ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = themeOptions.size
                                ),
                                onClick = { connectionViewModel.setTheme(option) },
                                selected = index == selectedIndex,
                                enabled = modeApplies
                            ) {
                                Text(themeLabels[index])
                            }
                        }
                    }

                    if (!modeApplies) {
                        Text(
                            text = when (selectedTheme.mode) {
                                ThemeMode.LIGHT_ONLY ->
                                    "${selectedTheme.label} is a fixed light theme."
                                else ->
                                    "${selectedTheme.label} is a fixed dark theme. " +
                                        "Switch to Hermes Relay for light/dark control."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

                    // The ambient-mode entry is a gesture with no visible
                    // control — this line is its discoverable documentation
                    // (including for screen-reader users browsing settings).
                    if (animEnabled) {
                        Text(
                            text = "Tip: long-press the chat background for a fullscreen " +
                                "ambient sphere; tap anywhere to return.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Agent avatar section — choose the avatar first (the built-in sphere
            // plus any imported "pets"; add/remove pets in-app below). When the
            // sphere avatar is selected its skin chips nest below: avatar → skin.
            Text(
                text = "Agent avatar",
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
                val brand = LocalBrand.current
                val availableAvatars = LocalAvailableAvatars.current
                val activeAvatar = LocalAgentAvatar.current
                val availableSkins = LocalAvailableSphereSkins.current
                val sphereSkinId by connectionViewModel.sphereSkin.collectAsState()
                val effectiveSkinId = SphereRegistry.resolve(
                    selectedId = sphereSkinId,
                    themeDefaultSkinId = selectedTheme.defaultSphereSkinId,
                    available = availableSkins,
                ).id

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Pick the agent's avatar. Each shows which live " +
                            "signals it reacts to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        availableAvatars.forEach { avatar ->
                            AgentAvatarChip(
                                avatar = avatar,
                                brand = brand,
                                selected = avatar.id == activeAvatar.id,
                                // Persist + switch. RelayApp re-resolves
                                // LocalAgentAvatar from this pref, so every
                                // surface (and these chips) updates.
                                onClick = { connectionViewModel.setAgentAvatar(avatar.id) },
                            )
                        }
                    }

                    // Add / manage user pets in-app — the reliable alternative to
                    // adb push (scoped storage blocks or stalls it on many devices).
                    val userAvatars = availableAvatars.filter { it.source == AvatarSource.USER }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream", "*/*")
                                )
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Add a pet",
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        TextButton(onClick = { connectionViewModel.refreshAgentAvatars() }) {
                            Text("Rescan")
                        }
                    }

                    Text(
                        text = "Import an animated \"pet\" pack (.zip). Generate one " +
                            "with AI or hand-author it — see docs/pet-spec.md.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Installed-pet management: a labeled list with per-pet remove.
                    if (userAvatars.isNotEmpty()) {
                        Text(
                            text = "Installed pets",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        userAvatars.forEach { pet ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = pet.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { pendingDelete = pet }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Remove ${pet.label}",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }

                    // Pet playback-speed tuning (selected pet only) — scales the
                    // authored fps live, no re-authoring or re-importing needed.
                    if (activeAvatar.source == AvatarSource.USER) {
                        HorizontalDivider()

                        val petSpeed by connectionViewModel.petSpeed.collectAsState()
                        Text(
                            text = "Playback speed — ${"%.1f".format(java.util.Locale.US, petSpeed)}×",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Slider(
                            value = petSpeed,
                            onValueChange = { connectionViewModel.setPetSpeed(it) },
                            valueRange = 0.5f..1.5f,
                            steps = 9,
                        )
                        Text(
                            text = "Scales the selected pet's animation speed. " +
                                "1.0× plays each clip at its authored rate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Stabilize frames",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "Auto-centers each frame so the pet doesn't drift or jump " +
                                        "between frames (fixes wobbly AI sheets).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val petStabilize by connectionViewModel.petStabilize.collectAsState()
                            Switch(
                                checked = petStabilize,
                                onCheckedChange = { connectionViewModel.setPetStabilize(it) },
                            )
                        }

                        // Live state preview — drive the pet through each state to
                        // verify look/speed/stabilization without running the agent.
                        HorizontalDivider()
                        Text(
                            text = "Preview",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        val previewStates = remember {
                            listOf(
                                Triple("Idle", SphereState.Idle, 0f),
                                Triple("Thinking", SphereState.Thinking, 0f),
                                Triple("Working", SphereState.Thinking, 1f),
                                Triple("Writing", SphereState.Streaming, 0f),
                                Triple("Speaking", SphereState.Speaking, 0f),
                                Triple("Listening", SphereState.Listening, 0f),
                                Triple("Error", SphereState.Error, 0f),
                            )
                        }
                        var previewIdx by remember { mutableIntStateOf(0) }
                        var greetKey by remember { mutableIntStateOf(0) }
                        var overrideState by remember { mutableStateOf<SphereState?>(null) }
                        val previewScope = rememberCoroutineScope()
                        val sel = previewStates[previewIdx.coerceIn(0, previewStates.lastIndex)]
                        val previewSphereState = overrideState ?: sel.second
                        val previewBurst = if (overrideState != null) 0f else sel.third

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(modifier = Modifier.size(140.dp)) {
                                key(greetKey) {
                                    activeAvatar.Render(
                                        state = AvatarRenderState(
                                            state = previewSphereState,
                                            toolCallBurst = previewBurst,
                                        ),
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            previewStates.forEachIndexed { i, s ->
                                FilterChip(
                                    selected = overrideState == null && previewIdx == i,
                                    onClick = {
                                        overrideState = null
                                        previewIdx = i
                                    },
                                    label = { Text(s.first) },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = { greetKey++ }) { Text("Greet") }
                            OutlinedButton(onClick = {
                                // Replay celebrate by driving Speaking → Idle on the
                                // live instance (the transition fires the one-shot).
                                previewScope.launch {
                                    overrideState = SphereState.Speaking
                                    delay(150)
                                    overrideState = SphereState.Idle
                                    delay(2500)
                                    overrideState = null
                                }
                            }) { Text("Done") }
                        }

                        Text(
                            text = "Tap a state to preview it; Greet/Done replay the one-shot " +
                                "reactions. Reflects the speed and stabilize settings above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Second level of the model: skin chips, shown only when the
                    // sphere avatar is active (a pet carries no skins).
                    if (activeAvatar.id == SphereAvatar.id) {
                        HorizontalDivider()

                        Text(
                            text = "Sphere skin — choose the orb's look.",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            availableSkins.forEach { skin ->
                                SphereSkinChip(
                                    skin = skin,
                                    brand = brand,
                                    selected = skin.id == effectiveSkinId,
                                    onClick = { connectionViewModel.setSphereSkin(skin.id) },
                                )
                            }
                        }

                        HorizontalDivider()

                        Text(
                            text = "Add your own: drop a sphere JSON spec into the app's " +
                                "private spheres/ folder, then reopen this screen. See " +
                                "docs/sphere-spec.md for the format.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact theme picker chip — a swatch preview of the theme's three signature
 * colors (background fill + two accent dots) with its label, a selected border,
 * and a check badge. Reads from [AppTheme.swatch] so it stays correct as themes
 * are added.
 */
@Composable
private fun ThemeSwatchChip(
    appTheme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = appTheme.swatch.getOrElse(0) { MaterialTheme.colorScheme.surface }
    val accents = appTheme.swatch.drop(1)
    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(12.dp),
            )
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                accents.forEach { accent ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        Text(
            text = appTheme.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * Sphere skin chip — a two-pole gradient preview of the skin's idle colors, its
 * label, and a one-line capability summary (which live signals it reacts to,
 * plus a "Custom" tag for user-loaded skins). Adaptive skins preview against the
 * active [BrandPalette].
 */
@Composable
private fun SphereSkinChip(
    skin: SphereSkin,
    brand: BrandPalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val swatch = skin.swatch(brand)
    val poleA = swatch.getOrElse(0) { MaterialTheme.colorScheme.primary }
    val poleB = swatch.getOrElse(1) { poleA }
    val capability = buildString {
        append(skin.reactivity.summary())
        if (skin.source == SphereSkinSource.USER) append(" · Custom")
    }
    Column(
        modifier = Modifier
            .width(110.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(12.dp),
            )
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.horizontalGradient(listOf(poleA, poleB))),
            contentAlignment = Alignment.TopEnd,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .padding(3.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        Text(
            text = skin.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = capability,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Agent avatar chip — a representative preview, the avatar's label, and its
 * one-line capability summary (which live signals it reacts to). Mirrors
 * [SphereSkinChip]'s layout so the avatar row and the skin row read as one
 * family. The built-in sphere previews as a brand-gradient orb; a user "pet"
 * previews as its own static first frame (rendered paused via the avatar seam).
 */
@Composable
private fun AgentAvatarChip(
    avatar: AgentAvatar,
    brand: BrandPalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(110.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(12.dp),
            )
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar.source == AvatarSource.USER) {
                // Honest preview: the pet's own first frame, frozen (paused) so
                // the picker doesn't run N animation loops at once.
                avatar.Render(
                    state = AvatarRenderState(state = SphereState.Idle, paused = true),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                )
            } else {
                // Orb glyph — a small radial-gradient circle for the sphere.
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(brand.relay, brand.purple))),
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        Text(
            text = avatar.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = avatar.reactivity.summary(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
