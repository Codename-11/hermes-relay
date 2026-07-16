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
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.AppLanguage
import com.hermesandroid.relay.ui.components.LocalAvailableSphereSkins
import com.hermesandroid.relay.ui.components.SphereRegistry
import com.hermesandroid.relay.ui.components.SphereSkin
import com.hermesandroid.relay.ui.components.SphereSkinSource
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.reactivityLabels
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.AvatarSource
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import com.hermesandroid.relay.ui.components.avatar.LocalAvailableAvatars
import com.hermesandroid.relay.ui.components.avatar.SphereAvatar
import com.hermesandroid.relay.ui.theme.AppFont
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
    ) { uri -> uri?.let { connectionViewModel.importPet(it) } }

    // Re-scan pets/ when this screen opens (surfaces a pack added out-of-band,
    // e.g. via adb), and relay add/remove results as snackbars.
    LaunchedEffect(Unit) { connectionViewModel.refreshAgentAvatars() }
    LaunchedEffect(Unit) {
        connectionViewModel.avatarEvents.collect { snackbarHostState.showSnackbar(it) }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.appearance_remove_pet_title)) },
            text = { Text(stringResource(R.string.appearance_remove_pet_body, target.label)) },
            confirmButton = {
                TextButton(onClick = {
                    connectionViewModel.deleteUserAvatar(target.id, target.label)
                    pendingDelete = null
                }) { Text(stringResource(R.string.appearance_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.appearance_cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appearance_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.appearance_back),
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
                text = stringResource(R.string.appearance_theme),
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
                        text = stringResource(R.string.appearance_theme_desc),
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

            // Language section — AppCompat keeps this synchronized with the
            // Android 13+ per-app language setting and persists it on older OSes.
            Text(
                text = stringResource(R.string.appearance_language),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme,
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.appearance_language_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val selectedLanguage = AppLanguage.fromLanguageTags(
                        AppCompatDelegate.getApplicationLocales().toLanguageTags(),
                    )
                    val languageLabels = mapOf(
                        AppLanguage.SYSTEM_DEFAULT to stringResource(R.string.appearance_language_system),
                        AppLanguage.ENGLISH to stringResource(R.string.appearance_language_english),
                        AppLanguage.GERMAN to stringResource(R.string.appearance_language_german),
                        AppLanguage.BRAZILIAN_PORTUGUESE to stringResource(R.string.appearance_language_brazilian_portuguese),
                        AppLanguage.JAPANESE to stringResource(R.string.appearance_language_japanese),
                        AppLanguage.SIMPLIFIED_CHINESE to stringResource(R.string.appearance_language_simplified_chinese),
                        AppLanguage.SPANISH to stringResource(R.string.appearance_language_spanish),
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppLanguage.entries.forEach { language ->
                            FilterChip(
                                selected = language == selectedLanguage,
                                onClick = {
                                    AppCompatDelegate.setApplicationLocales(language.toLocaleList())
                                },
                                label = { Text(languageLabels.getValue(language)) },
                                leadingIcon = if (language == selectedLanguage) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            // Appearance (mode + font) section
            Text(
                text = stringResource(R.string.appearance_appearance),
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
                        text = stringResource(R.string.appearance_light_dark),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val themeOptions = listOf("auto", "light", "dark")
                    val themeLabels = listOf(stringResource(R.string.appearance_theme_auto), stringResource(R.string.appearance_theme_light), stringResource(R.string.appearance_theme_dark))
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
                                    stringResource(R.string.appearance_fixed_light, selectedTheme.label)
                                else ->
                                    stringResource(R.string.appearance_fixed_dark, selectedTheme.label)
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
                    val fontScaleLabels = listOf(stringResource(R.string.appearance_font_small), stringResource(R.string.appearance_font_normal), stringResource(R.string.appearance_font_large), stringResource(R.string.appearance_font_larger))
                    // Match the closest stop — float equality is fragile.
                    val selectedFontScaleIndex = fontScaleOptions
                        .withIndex()
                        .minByOrNull { kotlin.math.abs(it.value - fontScale) }
                        ?.index
                        ?: 1

                    Text(
                        text = stringResource(R.string.appearance_font_size),
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
                        text = stringResource(R.string.appearance_font_preview),
                        style = previewBase.copy(
                            fontSize = previewBase.fontSize * fontScaleOptions[selectedFontScaleIndex]
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Font section — pick the app-wide body typeface. Each option renders
            // its own label + sample line IN that font so the choice is legible
            // before tapping; the selection re-themes every screen live.
            Text(
                text = stringResource(R.string.appearance_font),
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
                val appFontId by connectionViewModel.appFont.collectAsState()
                val selectedFont = AppFont.byId(appFontId)

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.appearance_font_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    AppFont.entries.forEach { font ->
                        FontOptionRow(
                            font = font,
                            selected = font.id == selectedFont.id,
                            onClick = { connectionViewModel.setAppFont(font.id) },
                        )
                    }
                }
            }

            // Animation section
            Text(
                text = stringResource(R.string.appearance_animation),
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
                                text = stringResource(R.string.appearance_ascii_sphere),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.appearance_ascii_sphere_desc),
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
                                text = stringResource(R.string.appearance_behind_messages),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.appearance_behind_messages_desc),
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
                            text = stringResource(R.string.appearance_ambient_tip),
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
                text = stringResource(R.string.appearance_agent_avatar),
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
                        text = stringResource(R.string.appearance_agent_avatar_desc),
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
                                    arrayOf("application/zip", "image/*", "application/octet-stream", "*/*")
                                )
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.appearance_add_pet),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        TextButton(onClick = { connectionViewModel.refreshAgentAvatars() }) {
                            Text(stringResource(R.string.appearance_rescan))
                        }
                    }

                    Text(
                        text = stringResource(R.string.appearance_add_pet_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Installed-pet management: a labeled list with per-pet remove.
                    if (userAvatars.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.appearance_installed_pets),
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
                                        contentDescription = stringResource(R.string.appearance_remove_pet_cd, pet.label),
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
                            text = stringResource(R.string.appearance_playback_speed, "%.1f".format(java.util.Locale.US, petSpeed)),
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
                            text = stringResource(R.string.appearance_playback_speed_desc),
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
                                    text = stringResource(R.string.appearance_stabilize),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.appearance_stabilize_desc),
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
                            text = stringResource(R.string.appearance_preview),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        val previewStates = listOf(
                            Triple(stringResource(R.string.appearance_state_idle), SphereState.Idle, 0f),
                            Triple(stringResource(R.string.appearance_state_thinking), SphereState.Thinking, 0f),
                            Triple(stringResource(R.string.appearance_state_working), SphereState.Thinking, 1f),
                            Triple(stringResource(R.string.appearance_state_writing), SphereState.Streaming, 0f),
                            Triple(stringResource(R.string.appearance_state_speaking), SphereState.Speaking, 0f),
                            Triple(stringResource(R.string.appearance_state_listening), SphereState.Listening, 0f),
                            Triple(stringResource(R.string.appearance_state_error), SphereState.Error, 0f),
                        )
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
                            OutlinedButton(onClick = { greetKey++ }) { Text(stringResource(R.string.appearance_greet)) }
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
                            }) { Text(stringResource(R.string.appearance_done)) }
                        }

                        Text(
                            text = stringResource(R.string.appearance_preview_tip),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Second level of the model: skin chips, shown only when the
                    // sphere avatar is active (a pet carries no skins).
                    if (activeAvatar.id == SphereAvatar.id) {
                        HorizontalDivider()

                        Text(
                            text = stringResource(R.string.appearance_sphere_skin),
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
                            text = stringResource(R.string.appearance_sphere_custom_desc),
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
 * A single font option — its name + a sample line, both rendered in the option's
 * own [AppFont.fontFamily] so the typeface is visible before selecting. Selected
 * state shows a brand border + check badge. Tapping persists immediately and the
 * app re-themes live.
 */
@Composable
private fun FontOptionRow(
    font: AppFont,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val family = font.fontFamily()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = font.label,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = family),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = font.preview,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = family),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(13.dp),
                )
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
        append(reactivityLabels(skin.reactivity.flags()))
        if (skin.source == SphereSkinSource.USER) append(stringResource(R.string.appearance_custom_suffix))
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
            text = reactivityLabels(avatar.reactivity.flags()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
