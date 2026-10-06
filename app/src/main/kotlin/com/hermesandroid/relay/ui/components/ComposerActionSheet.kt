package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape

const val COMPOSER_ACTION_SHEET_TEST_TAG = "composer-action-sheet"

/**
 * Clean composer's "+" sheet, in the style of ChatGPT and Claude: attach
 * sources as large tiles, then the session settings (model, reasoning) and
 * commands as rows. Keeps the composer bar itself to "+", the field, and one
 * action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerActionSheet(
    onDismiss: () -> Unit,
    onAttachCamera: () -> Unit,
    onAttachPhotos: () -> Unit,
    onAttachFiles: () -> Unit,
    onPasteImage: () -> Unit,
    modelControl: ChatInputPickerControl?,
    onModelClick: (() -> Unit)?,
    effortControl: ChatInputPickerControl?,
    onEffortClick: (() -> Unit)?,
    onCommands: (() -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // Run the action after the sheet is asked to close so a follow-up
        // sheet or picker opens on top of a settled composer.
        fun pick(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
                .testTag(COMPOSER_ACTION_SHEET_TEST_TAG),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AttachTile(Icons.Filled.PhotoCamera, stringResource(R.string.chat_input_camera), pick(onAttachCamera), Modifier.weight(1f))
                AttachTile(Icons.Filled.PhotoLibrary, stringResource(R.string.chat_input_photos), pick(onAttachPhotos), Modifier.weight(1f))
                AttachTile(Icons.AutoMirrored.Filled.InsertDriveFile, stringResource(R.string.chat_input_files), pick(onAttachFiles), Modifier.weight(1f))
                AttachTile(Icons.Filled.ContentPaste, stringResource(R.string.chat_input_paste_image), pick(onPasteImage), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            if (modelControl != null && modelControl.isUsable && onModelClick != null) {
                SheetRow(
                    icon = Icons.Outlined.AutoAwesome,
                    label = stringResource(R.string.chat_input_sheet_model),
                    value = modelControl.value,
                    onClick = pick(onModelClick),
                )
            }
            if (effortControl != null && effortControl.isUsable && onEffortClick != null) {
                SheetRow(
                    icon = Icons.Outlined.Psychology,
                    label = stringResource(R.string.chat_input_sheet_reasoning),
                    value = effortControl.value,
                    onClick = pick(onEffortClick),
                )
            }
            if (onCommands != null) {
                SheetRow(
                    icon = Icons.Outlined.Terminal,
                    label = stringResource(R.string.chat_input_browse_commands),
                    value = null,
                    onClick = pick(onCommands),
                )
            }
        }
    }
}

@Composable
private fun AttachTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = appearanceRoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.heightIn(min = 84.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                // Two lines so longer labels ("Paste image", translations)
                // wrap instead of truncating.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
