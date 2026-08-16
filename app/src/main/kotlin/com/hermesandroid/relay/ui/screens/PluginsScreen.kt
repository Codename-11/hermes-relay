package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.plugins.document.PluginAction
import com.hermesandroid.relay.plugins.runtime.PLUGIN_API_WRITE_CAPABILITY
import com.hermesandroid.relay.plugins.runtime.AndroidPluginContribution
import com.hermesandroid.relay.plugins.ui.PluginInteraction
import com.hermesandroid.relay.plugins.ui.PluginPageRenderer
import com.hermesandroid.relay.viewmodel.PluginHubItem
import com.hermesandroid.relay.viewmodel.PluginPageState
import com.hermesandroid.relay.viewmodel.PluginsHubState
import com.hermesandroid.relay.viewmodel.PluginsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    viewModel: PluginsViewModel,
    onBack: () -> Unit,
    onOpenPage: (pluginId: String, pageId: String) -> Unit,
) {
    val state by viewModel.hubState.collectAsState()
    var pendingRemoval by remember {
        mutableStateOf<Pair<String, AndroidPluginContribution>?>(null)
    }
    DisposableEffect(viewModel) {
        viewModel.setCatalogVisible(true)
        onDispose { viewModel.setCatalogVisible(false) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plugins_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.plugins_back))
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            PluginsHubState.Disconnected -> PluginCenteredMessage(
                stringResource(R.string.plugins_dashboard_required),
                Modifier.padding(padding),
            )
            PluginsHubState.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            is PluginsHubState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::refresh, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.plugins_retry))
                }
            }
            is PluginsHubState.Ready -> if (current.plugins.isEmpty()) {
                PluginCenteredMessage(stringResource(R.string.plugins_empty), Modifier.padding(padding))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.plugins_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (current.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                            current.refreshError?.let { message ->
                                Text(message, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    items(current.plugins, key = { it.catalog.id }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            viewModel = viewModel,
                            onOpenPage = onOpenPage,
                            onRequestRemove = { page ->
                                pendingRemoval = plugin.catalog.id to page
                            },
                        )
                    }
                }
            }
        }
    }
    pendingRemoval?.let { (pluginId, page) ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.plugins_remove)) },
            text = { Text(stringResource(R.string.plugins_remove_confirm, page.title)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    viewModel.removeGeneratedPage(pluginId, page)
                }) { Text(stringResource(R.string.plugins_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(R.string.plugins_cancel))
                }
            },
        )
    }
}

@Composable
private fun PluginCard(
    plugin: PluginHubItem,
    viewModel: PluginsViewModel,
    onOpenPage: (String, String) -> Unit,
    onRequestRemove: (AndroidPluginContribution) -> Unit,
) {
    val requestsWrite = plugin.manifest.requestedCapabilities.any {
        it.id == PLUGIN_API_WRITE_CAPABILITY
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Extension, null, Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        plugin.manifest.displayName ?: plugin.catalog.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val description = plugin.manifest.description ?: plugin.catalog.description
                    if (description.isNotBlank()) {
                        Text(description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Switch(
                    checked = plugin.preferences.enabled,
                    onCheckedChange = { viewModel.setEnabled(plugin.catalog.id, it) },
                )
            }
            if (plugin.preferences.enabled && requestsWrite) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.plugins_allow_changes))
                        Text(
                            stringResource(R.string.plugins_allow_changes_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = PLUGIN_API_WRITE_CAPABILITY in plugin.preferences.grants,
                        onCheckedChange = { viewModel.setWriteGrant(plugin.catalog.id, it) },
                    )
                }
            }
            if (plugin.preferences.enabled) {
                plugin.manifest.contributions.filter { it.surface == "page" }.forEach { page ->
                    val generated = plugin.catalog.id == "hermes-relay" &&
                        page.status in setOf("draft", "published")
                    val canMutate = PLUGIN_API_WRITE_CAPABILITY in plugin.preferences.grants
                    Column {
                        TextButton(onClick = { onOpenPage(plugin.catalog.id, page.id) }) {
                            Text(page.title)
                        }
                        page.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Text(
                                description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (generated) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (page.status == "draft") {
                                        stringResource(R.string.plugins_draft)
                                    } else {
                                        stringResource(R.string.plugins_published)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                                if (page.status == "draft") {
                                    TextButton(
                                        enabled = canMutate,
                                        onClick = {
                                            viewModel.publishGeneratedPage(plugin.catalog.id, page)
                                        },
                                    ) { Text(stringResource(R.string.plugins_keep)) }
                                }
                                TextButton(
                                    enabled = canMutate,
                                    onClick = { onRequestRemove(page) },
                                ) { Text(stringResource(R.string.plugins_remove)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginPageScreen(
    viewModel: PluginsViewModel,
    pluginId: String,
    pageId: String,
    onBack: () -> Unit,
) {
    val state by viewModel.pageState.collectAsState()
    var pendingAction by remember { mutableStateOf<PluginAction?>(null) }
    LaunchedEffect(pluginId, pageId) { viewModel.loadPage(pluginId, pageId) }
    DisposableEffect(viewModel, pluginId, pageId) {
        viewModel.setPageVisible(true)
        onDispose { viewModel.setPageVisible(false) }
    }
    val title = (state as? PluginPageState.Ready)?.contribution?.title
        ?: stringResource(R.string.plugins_title)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.plugins_back))
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            PluginPageState.Idle, PluginPageState.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            is PluginPageState.Error -> PluginCenteredMessage(current.message, Modifier.padding(padding))
            is PluginPageState.Ready -> PluginPageRenderer(
                document = current.document,
                pageId = pageId,
                state = current.documentState,
                onInteraction = { interaction ->
                    when (interaction) {
                        is PluginInteraction.ValueChanged ->
                            viewModel.updateValue(interaction.key, interaction.value)
                        is PluginInteraction.ActionInvoked -> {
                            if (interaction.action.confirmation == null) {
                                viewModel.invokeAction(interaction.action)
                            } else {
                                pendingAction = interaction.action
                            }
                        }
                    }
                },
                modifier = Modifier.padding(padding),
            )
        }
    }
    pendingAction?.let { action ->
        val ready = state as? PluginPageState.Ready
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(R.string.plugins_confirm_action)) },
            text = { Text(ready?.documentState?.resolve(action.confirmation!!) ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    viewModel.invokeAction(action)
                }) { Text(stringResource(R.string.plugins_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text(stringResource(R.string.plugins_cancel)) }
            },
        )
    }
}

@Composable
private fun PluginCenteredMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}
