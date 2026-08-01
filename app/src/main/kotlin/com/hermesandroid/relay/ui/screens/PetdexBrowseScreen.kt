package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.petdex.PetdexCatalogClient
import com.hermesandroid.relay.petdex.PetdexInstallResult
import com.hermesandroid.relay.petdex.PetdexInstaller
import com.hermesandroid.relay.petdex.PetdexPet
import com.hermesandroid.relay.ui.components.avatar.LocalAvailablePets
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val PETDEX_URL = "https://petdex.dev"

/** Lightweight Petdex catalog browser. Atlas files are downloaded only after Install. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetdexBrowseScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val catalogClient = remember { PetdexCatalogClient() }
    val installer = remember { PetdexInstaller() }
    val availablePets = LocalAvailablePets.current
    val selectedPetId by connectionViewModel.floatingPet.collectAsState()

    var catalog by remember { mutableStateOf<List<PetdexPet>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var installingSlug by remember { mutableStateOf<String?>(null) }
    val installErrors = remember { mutableStateMapOf<String, Boolean>() }
    val installedThisSession = remember { mutableStateListOf<String>() }

    fun loadCatalog(forceRefresh: Boolean) {
        scope.launch {
            loading = true
            loadFailed = false
            try {
                catalog = catalogClient.fetchCatalog(forceRefresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                loadFailed = true
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            catalog = catalogClient.fetchCatalog()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        } finally {
            loading = false
        }
    }

    val visiblePets = remember(catalog, query) {
        filterPetdexPets(catalog, query)
    }
    val installedIds = remember(availablePets, installedThisSession.toList()) {
        availablePets.mapTo(mutableSetOf()) { it.id }.apply { addAll(installedThisSession) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.petdex_title)) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.petdex_description),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { uriHandler.openUri(PETDEX_URL) }) {
                        Text(stringResource(R.string.petdex_source_link))
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.petdex_search)) },
                        singleLine = true,
                    )
                }
            }

            when {
                loading -> item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                loadFailed -> item {
                    PetdexErrorCard(
                        onRetry = { loadCatalog(forceRefresh = true) },
                    )
                }

                visiblePets.isEmpty() -> item {
                    Text(
                        text = stringResource(R.string.petdex_no_results),
                        modifier = Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> items(visiblePets, key = { it.slug }) { pet ->
                    val avatarId = pet.installedAvatarId
                    val installed = avatarId in installedIds
                    val selected = selectedPetId == avatarId
                    val installing = installingSlug == pet.slug

                    PetdexPetCard(
                        pet = pet,
                        installed = installed,
                        selected = selected,
                        installing = installing,
                        installEnabled = installingSlug == null,
                        installFailed = installErrors[pet.slug] == true,
                        onViewSource = { uriHandler.openUri(pet.sourceUrl) },
                        onUse = { connectionViewModel.setFloatingPet(avatarId) },
                        onInstall = {
                            scope.launch {
                                installingSlug = pet.slug
                                installErrors.remove(pet.slug)
                                try {
                                    when (val result = installer.install(context, pet)) {
                                        is PetdexInstallResult.Success -> {
                                            if (result.avatarId !in installedThisSession) {
                                                installedThisSession += result.avatarId
                                            }
                                            connectionViewModel.refreshAgentAvatars()
                                            connectionViewModel.setFloatingPet(result.avatarId)
                                            snackbarHostState.showSnackbar(
                                                resources.getString(
                                                    R.string.petdex_installed_selected,
                                                    result.label,
                                                )
                                            )
                                        }
                                        is PetdexInstallResult.Failure -> {
                                            installErrors[pet.slug] = true
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    installErrors[pet.slug] = true
                                } finally {
                                    installingSlug = null
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

internal fun filterPetdexPets(catalog: List<PetdexPet>, query: String): List<PetdexPet> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return catalog
    return catalog.filter { pet ->
        pet.displayName.contains(normalizedQuery, ignoreCase = true) ||
            pet.slug.contains(normalizedQuery, ignoreCase = true) ||
            pet.submittedBy.contains(normalizedQuery, ignoreCase = true)
    }
}

@Composable
private fun PetdexPetCard(
    pet: PetdexPet,
    installed: Boolean,
    selected: Boolean,
    installing: Boolean,
    installEnabled: Boolean,
    installFailed: Boolean,
    onViewSource: () -> Unit,
    onUse: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = pet.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (pet.submittedBy.isBlank()) {
                    stringResource(R.string.petdex_unknown_creator)
                } else {
                    stringResource(R.string.petdex_by_creator, pet.submittedBy)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onViewSource) {
                Text(stringResource(R.string.petdex_view_source))
            }
            if (installFailed) {
                Text(
                    text = stringResource(R.string.petdex_install_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    selected -> Text(
                        text = stringResource(R.string.petdex_selected),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    installed -> Button(onClick = onUse) {
                        Text(stringResource(R.string.petdex_use))
                    }
                    installing -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.petdex_installing))
                    }
                    else -> Button(onClick = onInstall, enabled = installEnabled) {
                        Text(stringResource(R.string.petdex_install))
                    }
                }
            }
        }
    }
}

@Composable
private fun PetdexErrorCard(onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.petdex_load_error),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.petdex_retry))
            }
        }
    }
}
