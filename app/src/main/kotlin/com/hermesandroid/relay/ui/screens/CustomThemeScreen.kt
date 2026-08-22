package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.CustomThemePreset
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.theme.AppearanceShape
import com.hermesandroid.relay.ui.theme.LocalAppearanceShapeScale
import com.hermesandroid.relay.ui.theme.LocalBrand
import com.hermesandroid.relay.ui.theme.accentColor
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import com.hermesandroid.relay.ui.theme.appearanceShapeScale
import com.hermesandroid.relay.ui.theme.contrastRatio
import com.hermesandroid.relay.ui.theme.normalizeAccentHex
import com.hermesandroid.relay.ui.theme.toBrandPalette
import com.hermesandroid.relay.ui.theme.toColorScheme
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import java.util.UUID

private enum class CustomThemeTab { COLORS, STYLE }
private enum class CustomColorRole { BACKGROUND, SURFACE, ACCENT, TEXT }

@Composable
fun CustomThemeScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val presets by connectionViewModel.customThemes.collectAsState()
    val activeThemeId by connectionViewModel.appTheme.collectAsState()
    val currentShape by connectionViewModel.appearanceShape.collectAsState()
    val brand = LocalBrand.current
    val activeId = CustomThemePreset.idFromAppTheme(activeThemeId)
    var draft by remember { mutableStateOf<CustomThemePreset?>(null) }
    var saved by remember { mutableStateOf<CustomThemePreset?>(null) }
    var revertTarget by remember { mutableStateOf<CustomThemePreset?>(null) }
    var selectedTab by remember { mutableStateOf(CustomThemeTab.COLORS) }
    var menuPresetId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<CustomThemePreset?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<CustomThemePreset?>(null) }
    var colorRole by remember { mutableStateOf<CustomColorRole?>(null) }
    var colorValue by remember { mutableStateOf("") }
    val nextDefaultName = stringResource(R.string.custom_theme_default_name, presets.size + 1)

    LaunchedEffect(presets, activeId) {
        if (draft == null) {
            val initial = activeId?.let { id -> presets.firstOrNull { it.id == id } }
                ?: presets.firstOrNull()
                ?: customThemeFromBrand(
                    name = "Custom 1",
                    brand = brand,
                    shapeId = currentShape,
                )
            draft = initial
            saved = initial.takeIf { preset -> presets.any { it.id == preset.id } }
            revertTarget = initial
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.custom_theme_rename)) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(CustomThemePreset.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.custom_theme_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    target.copy(name = renameValue).normalized()?.let { renamed ->
                        connectionViewModel.saveCustomTheme(renamed, select = activeId == target.id)
                        if (draft?.id == target.id) {
                            draft = renamed
                            saved = renamed
                            revertTarget = renamed
                        }
                    }
                    renameTarget = null
                }) { Text(stringResource(R.string.custom_theme_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.appearance_cancel))
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.custom_theme_delete_title, target.name)) },
            text = { Text(stringResource(R.string.custom_theme_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    connectionViewModel.deleteCustomTheme(target.id)
                    val replacement = presets.firstOrNull { it.id != target.id }
                        ?: customThemeFromBrand("Custom 1", brand, currentShape)
                    draft = replacement
                    saved = replacement.takeIf {
                        it.id != target.id && presets.any { item -> item.id == replacement.id }
                    }
                    revertTarget = replacement
                    deleteTarget = null
                }) { Text(stringResource(R.string.custom_theme_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.appearance_cancel))
                }
            },
        )
    }

    colorRole?.let { role ->
        AlertDialog(
            onDismissRequest = { colorRole = null },
            title = { Text(stringResource(role.labelRes())) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(appearanceRoundedCornerShape(12.dp))
                            .background(accentColor(colorValue) ?: Color.Transparent)
                            .border(1.dp, MaterialTheme.colorScheme.outline, appearanceRoundedCornerShape(12.dp)),
                    )
                    OutlinedTextField(
                        value = colorValue,
                        onValueChange = { colorValue = it.take(7) },
                        label = { Text(stringResource(R.string.custom_theme_hex)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = normalizeAccentHex(colorValue) != null,
                    onClick = {
                        val normalized = normalizeAccentHex(colorValue) ?: return@TextButton
                        draft = draft?.withColor(role, normalized)
                        colorRole = null
                    },
                ) { Text(stringResource(R.string.custom_theme_apply_color)) }
            },
            dismissButton = {
                TextButton(onClick = { colorRole = null }) {
                    Text(stringResource(R.string.appearance_cancel))
                }
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
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.appearance_back))
                    }
                    Text(
                        stringResource(R.string.custom_theme_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = presets.size < CustomThemePreset.MAX_PRESETS,
                        onClick = {
                            val fresh = customThemeFromBrand(
                                name = nextDefaultName,
                                brand = brand,
                                shapeId = currentShape,
                            )
                            draft = fresh
                            saved = null
                            revertTarget = fresh
                            menuPresetId = null
                        },
                    ) {
                        Icon(Icons.Filled.Add, null)
                        Text(stringResource(R.string.custom_theme_new), Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { draft = revertTarget },
                        enabled = draft != revertTarget,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.custom_theme_revert)) }
                    Button(
                        onClick = {
                            draft?.normalized()?.let {
                                connectionViewModel.saveCustomTheme(it)
                                draft = it
                                saved = it
                                revertTarget = it
                            }
                        },
                        enabled = draft?.normalized()?.let(::hasReadableCustomThemeContrast) == true && draft != saved,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.custom_theme_save_changes)) }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.custom_theme_your_presets),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                presets.forEach { preset ->
                    CustomPresetCard(
                        preset = preset,
                        selected = draft?.id == preset.id,
                        active = activeId == preset.id,
                        menuExpanded = menuPresetId == preset.id,
                        onSelect = {
                            draft = preset
                            saved = preset
                            revertTarget = preset
                            connectionViewModel.selectCustomTheme(preset.id)
                        },
                        onMenu = { menuPresetId = preset.id },
                        onDismissMenu = { menuPresetId = null },
                        onRename = {
                            renameTarget = preset
                            renameValue = preset.name
                            menuPresetId = null
                        },
                        onDuplicate = {
                            val copy = preset.copy(
                                id = UUID.randomUUID().toString(),
                                name = "${preset.name} copy".take(CustomThemePreset.MAX_NAME_LENGTH),
                            )
                            connectionViewModel.saveCustomTheme(copy)
                            draft = copy
                            saved = copy
                            revertTarget = copy
                            menuPresetId = null
                        },
                        onDelete = {
                            deleteTarget = preset
                            menuPresetId = null
                        },
                    )
                }
                AddPresetCard(
                    enabled = presets.size < CustomThemePreset.MAX_PRESETS,
                    onClick = {
                        draft = customThemeFromBrand(
                            name = nextDefaultName,
                            brand = brand,
                            shapeId = currentShape,
                        )
                        saved = null
                        revertTarget = draft
                    },
                )
            }
            draft?.let { current ->
                OutlinedTextField(
                    value = current.name,
                    onValueChange = {
                        draft = current.copy(name = it.take(CustomThemePreset.MAX_NAME_LENGTH))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.custom_theme_name)) },
                    singleLine = true,
                )
                CustomThemePreview(current)
                CustomThemeTabs(selectedTab = selectedTab, onSelect = { selectedTab = it })
                when (selectedTab) {
                    CustomThemeTab.COLORS -> {
                        Surface(
                            shape = appearanceRoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column {
                                CustomColorRole.entries.forEachIndexed { index, role ->
                                    if (index > 0) HorizontalDivider()
                                    ColorRoleRow(
                                        role = role,
                                        value = current.color(role),
                                        onClick = {
                                            colorRole = role
                                            colorValue = current.color(role)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    CustomThemeTab.STYLE -> StyleEditor()
                }

                val contrastPasses = hasReadableCustomThemeContrast(current)
                Surface(
                    shape = appearanceRoundedCornerShape(12.dp),
                    color = if (contrastPasses) {
                        LocalBrand.current.green.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (contrastPasses) Icons.Filled.Check else Icons.Filled.Warning,
                            null,
                            tint = if (contrastPasses) LocalBrand.current.green else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            stringResource(
                                if (contrastPasses) R.string.appearance_contrast_passes
                                else R.string.appearance_contrast_fails,
                            ),
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        Text(
                            stringResource(R.string.appearance_contrast_aa),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                CustomModeControl(
                    preset = current,
                    onModeSelected = { draft = current.copy(mode = it) },
                )
                ShapeControl(
                    selected = AppearanceShape.fromId(current.shapeId),
                    onSelected = { draft = current.copy(shapeId = it.id) },
                )
                Text(
                    text = if (saved == null) {
                        stringResource(R.string.custom_theme_unsaved)
                    } else if (draft == saved) {
                        stringResource(R.string.custom_theme_saved_to, saved?.name.orEmpty())
                    } else {
                        stringResource(R.string.custom_theme_modified)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun CustomPresetCard(
    preset: CustomThemePreset,
    selected: Boolean,
    active: Boolean,
    menuExpanded: Boolean,
    onSelect: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = preset.toBrandPalette()
    Card(
        modifier = Modifier.width(104.dp).clickable(onClick = onSelect),
        shape = appearanceRoundedCornerShape(16.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(appearanceRoundedCornerShape(12.dp))
                    .background(palette.background),
            ) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .width(54.dp)
                        .height(12.dp)
                        .clip(appearanceRoundedCornerShape(6.dp))
                        .background(palette.electric),
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp, top = 13.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(palette.relay),
                )
                if (selected) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                    ) { Icon(Icons.Filled.Check, null, Modifier.padding(4.dp)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    preset.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.custom_theme_more_actions))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.custom_theme_rename)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = onRename,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.custom_theme_duplicate)) },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = onDuplicate,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.custom_theme_delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = onDelete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPresetCard(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(92.dp).height(130.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = appearanceRoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Add, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.custom_theme_add_new), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CustomThemePreview(preset: CustomThemePreset) {
    val palette = preset.toBrandPalette()
    val shapeScale = appearanceShapeScale(preset.shapeId)
    CompositionLocalProvider(
        LocalBrand provides palette,
        LocalAppearanceShapeScale provides shapeScale,
    ) {
        MaterialTheme(colorScheme = palette.toColorScheme(), shapes = shapeScale.asMaterialShapes()) {
            Card(
                shape = appearanceRoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.custom_theme_preview_name, preset.name),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    MessageBubble(
                        message = ChatMessage("custom-user", MessageRole.USER, stringResource(R.string.custom_theme_preview_user), 0L),
                        maxBubbleWidth = 300.dp,
                    )
                    MessageBubble(
                        message = ChatMessage("custom-agent", MessageRole.ASSISTANT, stringResource(R.string.custom_theme_preview_agent), 0L),
                        maxBubbleWidth = 320.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomThemeTabs(selectedTab: CustomThemeTab, onSelect: (CustomThemeTab) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        CustomThemeTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            Column(
                modifier = Modifier.weight(1f).clickable { onSelect(tab) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (tab == CustomThemeTab.COLORS) Icons.Filled.Palette else Icons.Filled.Tune,
                        null,
                        Modifier.size(18.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(if (tab == CustomThemeTab.COLORS) R.string.custom_theme_colors else R.string.custom_theme_style),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(2.dp).background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ColorRoleRow(role: CustomColorRole, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(role.labelRes()), modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(32.dp)
                .clip(appearanceRoundedCornerShape(8.dp))
                .background(accentColor(value) ?: Color.Transparent)
                .border(1.dp, MaterialTheme.colorScheme.outline, appearanceRoundedCornerShape(8.dp)),
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

@Composable
private fun StyleEditor() {
    Surface(
        shape = appearanceRoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.custom_theme_style_summary), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CustomModeControl(
    preset: CustomThemePreset,
    onModeSelected: (String) -> Unit,
) {
    val selectedMode = if (preset.isDark) CustomThemePreset.MODE_DARK else CustomThemePreset.MODE_LIGHT
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.custom_theme_mode), style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth().clip(appearanceRoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, appearanceRoundedCornerShape(12.dp)),
        ) {
            listOf("auto", CustomThemePreset.MODE_LIGHT, CustomThemePreset.MODE_DARK).forEach { mode ->
                val selected = mode == selectedMode
                val enabled = mode != "auto"
                Row(
                    modifier = Modifier.weight(1f).height(44.dp)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .alpha(if (enabled) 1f else 0.38f)
                        .clickable(enabled = enabled) { onModeSelected(mode) }
                        .then(
                            Modifier.semantics(mergeDescendants = true) {
                                if (!enabled) disabled()
                            },
                        ),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                    if (selected) Spacer(Modifier.width(5.dp))
                    if (!enabled) Icon(Icons.Filled.Lock, null, Modifier.size(14.dp))
                    if (!enabled) Spacer(Modifier.width(5.dp))
                    Text(
                        stringResource(
                            when (mode) {
                                "auto" -> R.string.appearance_theme_auto
                                CustomThemePreset.MODE_LIGHT -> R.string.appearance_theme_light
                                else -> R.string.appearance_theme_dark
                            },
                        ),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.custom_theme_mode_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShapeControl(selected: AppearanceShape, onSelected: (AppearanceShape) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.appearance_shape), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.weight(1f).clip(appearanceRoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, appearanceRoundedCornerShape(12.dp)),
        ) {
            AppearanceShape.entries.forEach { shape ->
                val isSelected = shape == selected
                Text(
                    text = stringResource(
                        when (shape) {
                            AppearanceShape.SOFT -> R.string.appearance_shape_soft
                            AppearanceShape.BALANCED -> R.string.appearance_shape_balanced
                            AppearanceShape.SHARP -> R.string.appearance_shape_sharp
                        },
                    ),
                    modifier = Modifier.weight(1f).clickable { onSelected(shape) }
                        .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .padding(vertical = 11.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun CustomColorRole.labelRes(): Int = when (this) {
    CustomColorRole.BACKGROUND -> R.string.custom_theme_background
    CustomColorRole.SURFACE -> R.string.custom_theme_surface
    CustomColorRole.ACCENT -> R.string.custom_theme_accent
    CustomColorRole.TEXT -> R.string.custom_theme_text
}

private fun CustomThemePreset.color(role: CustomColorRole): String = when (role) {
    CustomColorRole.BACKGROUND -> backgroundHex
    CustomColorRole.SURFACE -> surfaceHex
    CustomColorRole.ACCENT -> accentHex
    CustomColorRole.TEXT -> textHex
}

private fun CustomThemePreset.withColor(role: CustomColorRole, value: String): CustomThemePreset = when (role) {
    CustomColorRole.BACKGROUND -> copy(backgroundHex = value)
    CustomColorRole.SURFACE -> copy(surfaceHex = value)
    CustomColorRole.ACCENT -> copy(accentHex = value)
    CustomColorRole.TEXT -> copy(textHex = value)
}

private fun customThemeFromBrand(
    name: String,
    brand: com.hermesandroid.relay.ui.theme.BrandPalette,
    shapeId: String,
): CustomThemePreset = CustomThemePreset(
    id = UUID.randomUUID().toString(),
    name = name,
    mode = if (brand.isDark) CustomThemePreset.MODE_DARK else CustomThemePreset.MODE_LIGHT,
    backgroundHex = brand.background.toHex(),
    surfaceHex = brand.navy2.toHex(),
    accentHex = brand.electric.toHex(),
    textHex = brand.ink.toHex(),
    shapeId = AppearanceShape.fromId(shapeId).id,
)

private fun Color.toHex(): String = "#%02X%02X%02X".format(
    java.util.Locale.US,
    (red * 255).toInt().coerceIn(0, 255),
    (green * 255).toInt().coerceIn(0, 255),
    (blue * 255).toInt().coerceIn(0, 255),
)

private fun hasReadableCustomThemeContrast(preset: CustomThemePreset): Boolean {
    val palette = preset.normalized()?.toBrandPalette() ?: return false
    return contrastRatio(palette.ink, palette.background) >= 4.5f &&
        contrastRatio(palette.ink, palette.navy2) >= 4.5f
}
