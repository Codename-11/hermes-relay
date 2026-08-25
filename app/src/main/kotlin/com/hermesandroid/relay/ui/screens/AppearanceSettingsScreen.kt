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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.AppLanguage
import com.hermesandroid.relay.data.CustomThemePreset
import com.hermesandroid.relay.data.MAX_PET_SIZE_SCALE
import com.hermesandroid.relay.data.MIN_PET_SIZE_SCALE
import com.hermesandroid.relay.ui.components.LocalAvailableSphereSkins
import com.hermesandroid.relay.ui.components.SphereRegistry
import com.hermesandroid.relay.ui.components.SphereSkin
import com.hermesandroid.relay.ui.components.SphereSkinSource
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.reactivityLabels
import com.hermesandroid.relay.ui.components.floatingPetDimensions
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.AvatarSource
import com.hermesandroid.relay.ui.components.avatar.LocalAvailablePets
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import com.hermesandroid.relay.ui.components.avatar.LocalBackgroundVisualizationEnabled
import com.hermesandroid.relay.ui.components.avatar.LocalFloatingPet
import com.hermesandroid.relay.ui.components.avatar.SphereAvatar
import com.hermesandroid.relay.ui.components.pet.LocalPetCompanionCoordinator
import com.hermesandroid.relay.ui.components.pet.petObstacleSurface
import com.hermesandroid.relay.ui.components.pet.petPerchSurface
import com.hermesandroid.relay.ui.theme.AppFont
import com.hermesandroid.relay.ui.theme.AppTheme
import com.hermesandroid.relay.ui.theme.AppThemes
import com.hermesandroid.relay.ui.theme.AccentSwatches
import com.hermesandroid.relay.ui.theme.AppearanceShape
import com.hermesandroid.relay.ui.theme.AppearanceShapeScale
import com.hermesandroid.relay.ui.theme.BrandPalette
import com.hermesandroid.relay.ui.theme.LocalAppearanceShapeScale
import com.hermesandroid.relay.ui.theme.ThemeMode
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.ui.theme.contrastRatio
import com.hermesandroid.relay.ui.theme.toColorScheme
import com.hermesandroid.relay.ui.theme.toAppTheme
import com.hermesandroid.relay.ui.theme.toBrandPalette
import com.hermesandroid.relay.ui.theme.withAccent
import com.hermesandroid.relay.ui.theme.appearanceComposerShape
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import com.hermesandroid.relay.ui.theme.appearanceShapeScale
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val APPEARANCE_PET_SURFACE_ROUTE = "settings/appearance"
private val APPEARANCE_PET_SURFACE_ROUTES = setOf(APPEARANCE_PET_SURFACE_ROUTE)

/** Each card remains a top-edge ledge while its controls stay forbidden terrain. */
private fun Modifier.appearancePetSurface(key: String): Modifier {
    val surfaceKey = "appearance-card:$key"
    return petPerchSurface(
        key = surfaceKey,
        routes = APPEARANCE_PET_SURFACE_ROUTES,
    ).petObstacleSurface(
        key = "$surfaceKey:controls",
        routes = APPEARANCE_PET_SURFACE_ROUTES,
    )
}

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
    onBrowsePetdex: () -> Unit = {},
    onCreatePet: () -> Unit = {},
    onOpenCustomTheme: () -> Unit = {},
    initialCustomizerExpanded: Boolean = false,
) {
    val theme by connectionViewModel.theme.collectAsState()
    val appThemeId by connectionViewModel.appTheme.collectAsState()
    val activeCustomTheme by connectionViewModel.activeCustomTheme.collectAsState()
    val customThemes by connectionViewModel.customThemes.collectAsState()
    val selectedTheme = activeCustomTheme?.toAppTheme() ?: AppThemes.byId(appThemeId)
    val isDarkTheme = LocalBrand.current.isDark
    val appliedAccent by connectionViewModel.appearanceAccent.collectAsState()
    val appliedShape by connectionViewModel.appearanceShape.collectAsState()
    var customizeExpanded by remember { mutableStateOf(initialCustomizerExpanded) }
    val previewPalette = activeCustomTheme?.toBrandPalette()
        ?: selectedTheme.paletteFor(isDarkTheme).withAccent(appliedAccent)

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<AgentAvatar?>(null) }
    val appearanceScrollState = rememberScrollState()
    val petCompanionCoordinator = LocalPetCompanionCoordinator.current
    LaunchedEffect(appearanceScrollState, petCompanionCoordinator) {
        snapshotFlow { appearanceScrollState.isScrollInProgress to (pendingDelete != null) }
            .distinctUntilChanged()
            .collect { (scrolling, hidden) ->
                petCompanionCoordinator.publishSurface(
                    owner = APPEARANCE_PET_SURFACE_ROUTE,
                    scrolling = scrolling,
                    hidden = hidden,
                )
            }
    }
    DisposableEffect(petCompanionCoordinator) {
        onDispose { petCompanionCoordinator.clearSurface(APPEARANCE_PET_SURFACE_ROUTE) }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { connectionViewModel.importPet(it) } }
    val sphereImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { connectionViewModel.importSphereSkin(it) } }
    val backgroundImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { connectionViewModel.importBackgroundAnimation(it) } }

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
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.appearance_back),
                        )
                    }
                    Text(
                        stringResource(R.string.appearance_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = connectionViewModel::resetAppearanceTheme) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.appearance_reset), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(appearanceScrollState)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = stringResource(R.string.appearance_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AppearanceLivePreview(previewPalette, appearanceShapeScale(appliedShape))

            // Preset-first gallery, matching the live preview above.
            Text(
                text = stringResource(R.string.appearance_preset_gallery),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val customSwatch = activeCustomTheme?.toAppTheme() ?: AppTheme(
                    id = CustomThemePreset.APP_THEME_PREFIX,
                    label = stringResource(R.string.custom_theme_title),
                    description = stringResource(R.string.custom_theme_entry_summary),
                    mode = ThemeMode.BOTH,
                    darkPalette = LocalBrand.current,
                    lightPalette = LocalBrand.current,
                    swatch = listOf(
                        LocalBrand.current.background,
                        LocalBrand.current.electric,
                        LocalBrand.current.ink,
                    ),
                )
                ThemeSwatchChip(
                    appTheme = customSwatch,
                    selected = activeCustomTheme != null,
                    onClick = onOpenCustomTheme,
                )
                AppThemes.ALL.forEach { appTheme ->
                    ThemeSwatchChip(
                        appTheme = appTheme,
                        selected = activeCustomTheme == null && appTheme.id == selectedTheme.id,
                        onClick = { connectionViewModel.setAppTheme(appTheme.id) },
                    )
                }
            }

            AppearanceModeControl(
                theme = activeCustomTheme?.mode ?: theme,
                selectedTheme = selectedTheme,
                isDarkTheme = isDarkTheme,
                enabledModes = activeCustomTheme?.let { setOf("light", "dark") },
                description = activeCustomTheme?.let {
                    stringResource(R.string.custom_theme_mode_summary)
                },
                onThemeModeSelected = { mode ->
                    val customTheme = activeCustomTheme
                    if (customTheme == null) {
                        connectionViewModel.setTheme(mode)
                    } else {
                        connectionViewModel.saveCustomTheme(customTheme.copy(mode = mode))
                    }
                },
            )

            if (activeCustomTheme == null) {
                AccentCustomizer(
                    selectedTheme = selectedTheme,
                    expanded = customizeExpanded,
                    selectedAccent = appliedAccent,
                    selectedShape = appliedShape,
                    onToggle = { customizeExpanded = !customizeExpanded },
                    onAccentSelected = connectionViewModel::setAppearanceAccent,
                    onShapeSelected = connectionViewModel::setAppearanceShape,
                    onReset = {
                        connectionViewModel.setAppearanceAccent(null)
                        connectionViewModel.setAppearanceShape(AppearanceShape.DEFAULT.id)
                    },
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenCustomTheme),
                    shape = appearanceRoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(activeCustomTheme?.name.orEmpty(), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.custom_theme_saved_count, customThemes.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
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
                    .appearancePetSurface("language")
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = appearanceRoundedCornerShape(12.dp),
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
                        AppLanguage.RUSSIAN to stringResource(R.string.appearance_language_russian),
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

            // Readability section. Theme mode and accent customization live
            // directly under the preset gallery above.
            Text(
                text = stringResource(R.string.appearance_appearance),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier
                    .appearancePetSurface("display")
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = appearanceRoundedCornerShape(12.dp),
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
                        text = stringResource(R.string.appearance_readability_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

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
                    .appearancePetSurface("font")
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = appearanceRoundedCornerShape(12.dp),
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
                    .appearancePetSurface("animation")
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = appearanceRoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                val animEnabled by connectionViewModel.animationEnabled.collectAsState()
                val animBehindChat by connectionViewModel.animationBehindChat.collectAsState()
                val imageGenerationStyle by connectionViewModel.imageGenerationStyle.collectAsState()

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

                    HorizontalDivider()

                    Text(
                        text = stringResource(R.string.appearance_image_generation_style),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.appearance_image_generation_style_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val imageStyleOptions = listOf(
                        "rotate" to stringResource(R.string.appearance_image_generation_rotate),
                        "grid" to stringResource(R.string.appearance_image_generation_grid),
                        "sphere" to stringResource(R.string.appearance_image_generation_sphere),
                        "nodes" to stringResource(R.string.appearance_image_generation_nodes),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        imageStyleOptions.forEach { (id, label) ->
                            FilterChip(
                                selected = imageGenerationStyle == id,
                                onClick = { connectionViewModel.setImageGenerationStyle(id) },
                                label = { Text(label) },
                                leadingIcon = if (imageGenerationStyle == id) {
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

            Text(
                text = stringResource(R.string.appearance_background_visualization),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Card(
                modifier = Modifier
                    .appearancePetSurface("background")
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = appearanceRoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme,
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                val backgroundVisualizationEnabled by
                    connectionViewModel.backgroundVisualizationEnabled.collectAsState()
                val backgroundAvatarId by connectionViewModel.backgroundAvatar.collectAsState()
                val availableBackgroundAnimations = LocalAvailablePets.current
                val effectiveBackgroundAvatarId = backgroundAvatarId.takeIf { selectedId ->
                    selectedId == SphereAvatar.id || availableBackgroundAnimations.any { it.id == selectedId }
                } ?: SphereAvatar.id
                val availableSkins = LocalAvailableSphereSkins.current
                val sphereSkinId by connectionViewModel.sphereSkin.collectAsState()
                val effectiveSkinId = SphereRegistry.resolve(
                    selectedId = sphereSkinId,
                    themeDefaultSkinId = selectedTheme.defaultSphereSkinId,
                    available = availableSkins,
                ).id
                val brand = LocalBrand.current

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.appearance_background_visualization_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !backgroundVisualizationEnabled,
                            onClick = { connectionViewModel.setBackgroundVisualizationEnabled(false) },
                            label = { Text(stringResource(R.string.appearance_background_off)) },
                        )
                        FilterChip(
                            selected = backgroundVisualizationEnabled && effectiveBackgroundAvatarId == SphereAvatar.id,
                            onClick = { connectionViewModel.setBackgroundAvatar(SphereAvatar.id) },
                            label = { Text(stringResource(R.string.appearance_background_sphere)) },
                        )
                    }

                    if (availableBackgroundAnimations.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.appearance_background_installed),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            availableBackgroundAnimations.forEach { avatar ->
                                AgentAvatarChip(
                                    avatar = avatar,
                                    brand = brand,
                                    selected = backgroundVisualizationEnabled && avatar.id == effectiveBackgroundAvatarId,
                                    onClick = { connectionViewModel.setBackgroundAvatar(avatar.id) },
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            backgroundImportLauncher.launch(
                                arrayOf("application/zip", "image/*", "application/octet-stream", "*/*")
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.appearance_import_background),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.appearance_import_background_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (backgroundVisualizationEnabled && effectiveBackgroundAvatarId == SphereAvatar.id) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.appearance_sphere_skin),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
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
                        Text(
                            text = stringResource(R.string.appearance_sphere_custom_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                sphereImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "*/*"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.appearance_import_sphere),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }

            // Floating pets are companions, not agent identity or background art.
            Text(
                text = stringResource(R.string.appearance_floating_pet),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier
                    .appearancePetSurface("floating-pet")
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = appearanceRoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                val brand = LocalBrand.current
                val availablePets = LocalAvailablePets.current
                val activePet = LocalFloatingPet.current

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.appearance_floating_pet_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilterChip(
                            selected = activePet == null,
                            onClick = { connectionViewModel.setFloatingPet(null) },
                            label = { Text(stringResource(R.string.appearance_floating_pet_none)) },
                        )
                        availablePets.forEach { pet ->
                            AgentAvatarChip(
                                avatar = pet,
                                brand = brand,
                                selected = pet.id == activePet?.id,
                                onClick = { connectionViewModel.setFloatingPet(pet.id) },
                            )
                        }
                    }

                    // Add / manage user pets in-app — the reliable alternative to
                    // adb push (scoped storage blocks or stalls it on many devices).
                    val userAvatars = availablePets.filter { it.source == AvatarSource.USER }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        TextButton(onClick = onBrowsePetdex) {
                            Text(stringResource(R.string.appearance_browse_petdex))
                        }
                        TextButton(onClick = onCreatePet) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.appearance_create_pet),
                                modifier = Modifier.padding(start = 6.dp),
                            )
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
                    if (activePet?.source == AvatarSource.USER) {
                        HorizontalDivider()

                        val petSpeed by connectionViewModel.petSpeed.collectAsState()
                        val petSizeScale by connectionViewModel.petSizeScale.collectAsState()
                        val petRoamingEnabled by connectionViewModel.petRoamingEnabled.collectAsState()
                        val petTemperament by connectionViewModel.petTemperament.collectAsState()
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

                        Text(
                            text = stringResource(
                                R.string.appearance_pet_size,
                                (petSizeScale * 100f).roundToInt(),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Slider(
                            value = petSizeScale,
                            onValueChange = connectionViewModel::setPetSizeScale,
                            valueRange = MIN_PET_SIZE_SCALE..MAX_PET_SIZE_SCALE,
                            steps = 5,
                        )
                        Text(
                            text = stringResource(R.string.appearance_pet_size_desc),
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.appearance_pet_roaming),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.appearance_pet_roaming_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = petRoamingEnabled,
                                onCheckedChange = { connectionViewModel.setPetRoamingEnabled(it) },
                            )
                        }

                        Text(
                            text = stringResource(R.string.appearance_pet_temperament),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.appearance_pet_temperament_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (petRoamingEnabled) 1f else 0.6f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            petTemperamentOptions.forEach { option ->
                                FilterChip(
                                    selected = option.temperament == petTemperament,
                                    onClick = {
                                        connectionViewModel.setPetTemperament(option.temperament)
                                    },
                                    enabled = petRoamingEnabled,
                                    label = { Text(stringResource(option.labelRes)) },
                                )
                            }
                        }
                        Text(
                            text = stringResource(
                                petTemperamentOption(petTemperament).descriptionRes,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alpha(if (petRoamingEnabled) 1f else 0.6f),
                        )
                        TextButton(onClick = connectionViewModel::resetPetPlacement) {
                            Text(stringResource(R.string.floating_pet_action_reset))
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
                                .clip(appearanceRoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier.size(
                                    floatingPetDimensions(
                                        compact = false,
                                        sizeScale = petSizeScale,
                                    ).visualSizeDp.dp,
                                ),
                            ) {
                                key(greetKey) {
                                    activePet.Render(
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

                }
            }
        }
    }
}

@Composable
private fun AppearanceModeControl(
    theme: String,
    selectedTheme: AppTheme,
    isDarkTheme: Boolean,
    enabledModes: Set<String>? = null,
    description: String? = null,
    onThemeModeSelected: (String) -> Unit,
) {
    val options = listOf("auto", "light", "dark")
    val labels = listOf(
        stringResource(R.string.appearance_theme_auto),
        stringResource(R.string.appearance_theme_light),
        stringResource(R.string.appearance_theme_dark),
    )
    val modeApplies = selectedTheme.mode == ThemeMode.BOTH
    val displayedMode = resolvedAppearanceModeSelection(theme, selectedTheme.mode)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            shape = appearanceRoundedCornerShape(22.dp),
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { index, option ->
                val optionEnabled = enabledModes?.contains(option) ?: modeApplies
                if (index > 0) {
                    VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (option == displayedMode) MaterialTheme.colorScheme.surfaceContainerHigh
                            else Color.Transparent,
                        )
                        .alpha(if (optionEnabled || option == displayedMode) 1f else 0.38f)
                        .clickable(enabled = optionEnabled) { onThemeModeSelected(option) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (option == displayedMode) {
                        Icon(Icons.Filled.Check, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                    } else if (!optionEnabled) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(labels[index], style = MaterialTheme.typography.labelLarge)
                }
            }
            }
        }
        if (description != null || !modeApplies) {
            Text(
                text = description ?: if (selectedTheme.mode == ThemeMode.LIGHT_ONLY) {
                    stringResource(R.string.appearance_fixed_light, selectedTheme.label)
                } else {
                    stringResource(R.string.appearance_fixed_dark, selectedTheme.label)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun resolvedAppearanceModeSelection(themePreference: String, themeMode: ThemeMode): String =
    when (themeMode) {
        ThemeMode.BOTH -> themePreference
        ThemeMode.LIGHT_ONLY -> "light"
        ThemeMode.DARK_ONLY -> "dark"
    }

@Composable
private fun AccentCustomizer(
    selectedTheme: AppTheme,
    expanded: Boolean,
    selectedAccent: String?,
    selectedShape: String,
    onToggle: () -> Unit,
    onAccentSelected: (String?) -> Unit,
    onShapeSelected: (String) -> Unit,
    onReset: () -> Unit,
) {
    val selectedPalette = selectedTheme.paletteFor(LocalBrand.current.isDark).withAccent(selectedAccent)
    val scheme = selectedPalette.toColorScheme()
    val ratio = contrastRatio(scheme.onPrimary, scheme.primary)
    Card(
        modifier = Modifier.fillMaxWidth().border(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
            appearanceRoundedCornerShape(12.dp),
        ),
        shape = appearanceRoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(
                            R.string.appearance_customize_theme,
                            selectedTheme.label.removePrefix("Hermes "),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Text(
                        stringResource(R.string.appearance_customize_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.appearance_accent), style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf<String?>(null).plus(AccentSwatches).forEach { accent ->
                            val color = if (accent == null) selectedTheme.swatch[1] else Color(
                                0xFF000000L or accent.drop(1).toLong(16)
                            )
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (selectedAccent == accent) 3.dp else 1.dp,
                                        color = if (selectedAccent == accent) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape,
                                    )
                                    .clickable { onAccentSelected(accent) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selectedAccent == accent) {
                                    Icon(Icons.Filled.Check, null, tint = com.hermesandroid.relay.ui.theme.readableContentColor(color))
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.appearance_shape), style = MaterialTheme.typography.labelLarge)
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.padding(start = 12.dp).weight(1f),
                        ) {
                            AppearanceShape.entries.forEachIndexed { index, shape ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index, AppearanceShape.entries.size),
                                    onClick = { onShapeSelected(shape.id) },
                                    selected = shape.id == selectedShape,
                                    icon = {
                                        if (shape.id == selectedShape) Icon(Icons.Filled.Check, null, Modifier.size(14.dp))
                                    },
                                ) {
                                    Text(
                                        when (shape) {
                                            AppearanceShape.SOFT -> stringResource(R.string.appearance_shape_soft)
                                            AppearanceShape.BALANCED -> stringResource(R.string.appearance_shape_balanced)
                                            AppearanceShape.SHARP -> stringResource(R.string.appearance_shape_sharp)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }

                    AppearanceSummaryRow(
                        leading = {
                            Icon(
                                Icons.Filled.Check,
                                null,
                                tint = if (ratio >= 4.5f) LocalBrand.current.green else MaterialTheme.colorScheme.error,
                            )
                        },
                        label = if (ratio >= 4.5f) stringResource(R.string.appearance_contrast_passes)
                        else stringResource(R.string.appearance_contrast_fails),
                        value = stringResource(R.string.appearance_contrast_aa),
                    )
                    AppearanceSummaryRow(
                        label = stringResource(R.string.appearance_typography_density),
                        value = stringResource(R.string.appearance_default),
                    )
                    AppearanceSummaryRow(
                        label = stringResource(R.string.appearance_readability),
                        value = stringResource(R.string.appearance_font_normal_summary),
                    )
                    TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.appearance_reset_draft))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSummaryRow(
    label: String,
    value: String,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).size(18.dp),
        )
    }
}

/** Representative, theme-live chat sample so presets are judged in context. */
@Composable
private fun AppearanceLivePreview(
    palette: BrandPalette,
    shapeScale: AppearanceShapeScale,
) {
    CompositionLocalProvider(
        LocalBrand provides palette,
        LocalAppearanceShapeScale provides shapeScale,
    ) {
        MaterialTheme(colorScheme = palette.toColorScheme(), shapes = shapeScale.asMaterialShapes()) {
            AppearanceLivePreviewContent()
        }
    }
}

@Composable
private fun AppearanceLivePreviewContent() {
    val backgroundEnabled = LocalBackgroundVisualizationEnabled.current
    val backgroundAvatar = LocalAgentAvatar.current
    Card(
        modifier = Modifier.fillMaxWidth().height(294.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Text(
                    text = stringResource(R.string.appearance_preview_relay),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.LightMode,
                            contentDescription = null,
                            modifier = Modifier.padding(5.dp).size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Icon(
                                Icons.Filled.DarkMode,
                                contentDescription = null,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp).size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.76f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    LocalBrand.current.relay,
                                ),
                            ),
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.appearance_preview_user_message),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.appearance_preview_time),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.DoneAll,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = Color.Transparent,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.splash_icon),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.padding(4.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.appearance_preview_agent),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(13.dp))
                        Text(
                            stringResource(R.string.appearance_preview_voice),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 3.dp),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.appearance_preview_agent_message),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.appearance_preview_time),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(LocalBrand.current.green))
                        Text(
                            text = stringResource(R.string.appearance_preview_tool_meta),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = LocalBrand.current.green,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
                Box(modifier = Modifier.padding(start = 6.dp).size(38.dp), contentAlignment = Alignment.Center) {
                    if (backgroundEnabled) {
                        backgroundAvatar.Render(
                            state = AvatarRenderState(state = SphereState.Idle),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Surface(
                shape = appearanceComposerShape(),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = Color.Transparent,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("gpt-5.6-sol", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                    Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(14.dp))
                    Text("High", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                    Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(14.dp))
                    Text(
                        stringResource(R.string.appearance_preview_message_placeholder),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.GraphicEq, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                shape = appearanceRoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Bolt, null, Modifier.size(14.dp), tint = LocalBrand.current.amber)
                    Text(
                        text = stringResource(R.string.appearance_preview_gateway),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = LocalBrand.current.green,
                    )
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
            .clip(appearanceRoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = appearanceRoundedCornerShape(10.dp),
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
            .width(77.dp)
            .clip(appearanceRoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = appearanceRoundedCornerShape(12.dp),
            )
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(appearanceRoundedCornerShape(8.dp))
                .background(background),
            contentAlignment = Alignment.TopStart,
        ) {
            Column(
                Modifier.fillMaxSize().padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    Modifier.align(Alignment.End).fillMaxWidth(0.68f).height(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accents.getOrElse(0) { MaterialTheme.colorScheme.primary }.copy(alpha = 0.72f)),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    accents.take(3).forEachIndexed { index, accent ->
                        Box(
                            Modifier.width(if (index == 0) 7.dp else 12.dp).height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(accent.copy(alpha = 0.9f)),
                        )
                    }
                }
                Box(
                    Modifier.fillMaxWidth(0.72f).height(15.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
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
            text = appTheme.label.removePrefix("Hermes "),
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
            .clip(appearanceRoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = appearanceRoundedCornerShape(12.dp),
            )
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(appearanceRoundedCornerShape(8.dp))
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
            .clip(appearanceRoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = appearanceRoundedCornerShape(12.dp),
            )
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(appearanceRoundedCornerShape(8.dp))
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
