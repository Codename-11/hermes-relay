package com.hermesandroid.relay.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

internal enum class CustomPetKind { STATIC, ANIMATED }

internal fun buildCustomPetPrompt(
    name: String,
    description: String,
    kind: CustomPetKind,
): String {
    val safeName = name.trim().ifBlank { "My Hermes companion" }
    val safeDescription = description.trim().ifBlank {
        "a friendly companion that remains recognizable at small mobile sizes"
    }
    val output = if (kind == CustomPetKind.STATIC) {
        "Create one transparent PNG or WebP image. Keep the subject centered with generous edge padding."
    } else {
        "Create a validated Hermes pet pack ZIP using pet.json plus transparent sprite sheets. " +
            "Include idle, walk-left/right, thinking or working, writing, speaking, listening, greet, done, and error where practical. " +
            "Frames must show real motion while keeping the character, scale, baseline, and anchor consistent. " +
            "At minimum, pet.json must use schemaVersion 1, a stable id and label, and an idle state. " +
            "A frame-list state is shaped like {\"frames\":[\"idle-01.png\"],\"fps\":8}; a sheet state is shaped like " +
            "{\"sheet\":\"idle.png\",\"frameWidth\":256,\"frameHeight\":256,\"frameCount\":16,\"fps\":8}. " +
            "All referenced files must remain inside the pack folder."
    }
    return """
        Help me create a custom floating pet for the Hermes Relay Android app.

        Pet name: $safeName
        Character direction: $safeDescription
        Format: ${if (kind == CustomPetKind.STATIC) "single static image" else "animated pet pack"}

        $output

        Follow the current Hermes Relay pet contract above. If the repository docs/pet-spec.md or user-docs/features/custom-avatars.md are available, use them as the final authority. Use image-generation tools only if they are available in this chat. Do not upload my reference image, generated art, or pet pack anywhere except through tools I explicitly approve. Work locally where possible.

        Before returning anything, validate the manifest and every referenced file, reject path traversal, verify decoded image dimensions and safe margins, and check that animated frames differ visibly without drifting. Then show me a concise review summary and attach the final PNG/WebP or ZIP for me to inspect. Do not install, publish, or share it automatically. I will review it and import it from Appearance > Floating pet.

        If you need a visual reference, ask me to attach it in this chat before generating the pet.
    """.trimIndent()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomPetGuideScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onStartNewChat: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(CustomPetKind.ANIMATED) }
    val prompt = remember(name, description, kind) { buildCustomPetPrompt(name, description, kind) }
    val clipboard = LocalClipboardManager.current
    val copiedMessage = stringResource(R.string.pet_creator_copied)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(connectionViewModel::importPet)
    }
    LaunchedEffect(connectionViewModel) {
        connectionViewModel.avatarEvents.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pet_creator_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.appearance_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.pet_creator_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GuideCard(step = "1", title = stringResource(R.string.pet_creator_step_idea)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.pet_creator_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.pet_creator_description)) },
                    supportingText = { Text(stringResource(R.string.pet_creator_description_hint)) },
                    minLines = 3,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == CustomPetKind.ANIMATED,
                        onClick = { kind = CustomPetKind.ANIMATED },
                        label = { Text(stringResource(R.string.pet_creator_animated)) },
                    )
                    FilterChip(
                        selected = kind == CustomPetKind.STATIC,
                        onClick = { kind = CustomPetKind.STATIC },
                        label = { Text(stringResource(R.string.pet_creator_static)) },
                    )
                }
            }

            GuideCard(step = "2", title = stringResource(R.string.pet_creator_step_create)) {
                Text(
                    text = stringResource(R.string.pet_creator_review_copy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = prompt,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(prompt))
                            scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.ContentCopy, null)
                        Text(stringResource(R.string.pet_creator_copy), modifier = Modifier.padding(start = 6.dp))
                    }
                    Button(
                        onClick = { onStartNewChat(prompt) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, null)
                        Text(stringResource(R.string.pet_creator_new_chat), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            GuideCard(step = "3", title = stringResource(R.string.pet_creator_step_review)) {
                Text(
                    text = stringResource(R.string.pet_creator_review_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/zip", "image/*", "application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Text(stringResource(R.string.pet_creator_import), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun GuideCard(step: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "$step  ·  $title",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}
