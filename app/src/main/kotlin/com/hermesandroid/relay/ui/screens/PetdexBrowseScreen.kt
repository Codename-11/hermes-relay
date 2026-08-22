package com.hermesandroid.relay.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import com.hermesandroid.relay.network.upstream.GatewayRpcException
import com.hermesandroid.relay.petdex.PetdexCatalogClient
import com.hermesandroid.relay.petdex.PetdexInstallResult
import com.hermesandroid.relay.petdex.PetdexInstaller
import com.hermesandroid.relay.petdex.PetdexPet
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.LocalAvailablePets
import com.hermesandroid.relay.ui.components.avatar.PetAvatar
import com.hermesandroid.relay.ui.components.avatar.PetPreviewAction
import com.hermesandroid.relay.ui.components.avatar.PetPreviewMapping
import com.hermesandroid.relay.ui.components.avatar.PetPreviewSupport
import com.hermesandroid.relay.ui.components.avatar.forCapabilityPreview
import com.hermesandroid.relay.ui.components.avatar.previewMappings
import com.hermesandroid.relay.ui.components.decodeInlineImageDataUrl
import com.hermesandroid.relay.ui.components.withInlineImageDecodeLock
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private const val PETDEX_URL = "https://petdex.dev"
private const val PETDEX_THUMB_MAX_BYTES = 512 * 1024
private const val PETDEX_THUMB_MAX_WIDTH = 192
private const val PETDEX_THUMB_MAX_HEIGHT = 208
private const val PETDEX_THUMB_CACHE_SIZE = 24
private const val PETDEX_THUMB_CONCURRENCY = 4
private const val PETDEX_THUMB_RETRY_MS = 10_000L
private const val JSONRPC_METHOD_NOT_FOUND = -32601
private const val PETDEX_FRAME_ASPECT = 192f / 208f

/** Petdex gallery with upstream-compatible, lazily loaded idle-frame previews. */
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
    val thumbnailLoader = remember(connectionViewModel) {
        PetdexThumbnailLoader { slug, url ->
            connectionViewModel.activeGatewayChatClient()
                ?.petThumbnail(slug, url)
        }
    }
    val availablePets = LocalAvailablePets.current
    val selectedPetId by connectionViewModel.floatingPet.collectAsState()
    val animationEnabled by connectionViewModel.animationEnabled.collectAsState()

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

    val visiblePets = remember(catalog, query) { filterPetdexPets(catalog, query) }
    val installedIds = remember(availablePets, installedThisSession.toList()) {
        availablePets.mapTo(mutableSetOf()) { it.id }.apply { addAll(installedThisSession) }
    }
    val selectedPet = remember(availablePets, selectedPetId) {
        availablePets.firstOrNull { it.id == selectedPetId }
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(156.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
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

            if (selectedPet != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SelectedPetPreview(
                        pet = selectedPet,
                        animationEnabled = animationEnabled,
                    )
                }
            }

            when {
                loading -> items(6) {
                    PetdexLoadingCard()
                }

                loadFailed -> item(span = { GridItemSpan(maxLineSpan) }) {
                    PetdexErrorCard(
                        onRetry = { loadCatalog(forceRefresh = true) },
                    )
                }

                visiblePets.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
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
                        thumbnailLoader = thumbnailLoader,
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
    val matches = if (normalizedQuery.isEmpty()) {
        catalog
    } else {
        catalog.filter { pet ->
            pet.displayName.contains(normalizedQuery, ignoreCase = true) ||
                pet.slug.contains(normalizedQuery, ignoreCase = true) ||
                pet.submittedBy.contains(normalizedQuery, ignoreCase = true)
        }
    }
    return matches
}

@Composable
private fun SelectedPetPreview(
    pet: AgentAvatar,
    animationEnabled: Boolean,
) {
    val mappings = remember(pet) { (pet as? PetAvatar)?.previewMappings().orEmpty() }
    val previewPet = remember(pet) { (pet as? PetAvatar)?.forCapabilityPreview() ?: pet }
    var selectedAction by remember(pet.id) { mutableStateOf(PetPreviewAction.Idle) }
    val selectedMapping = mappings.firstOrNull { it.action == selectedAction }
        ?: mappings.firstOrNull()
    val renderState = selectedMapping?.renderState
        ?: AvatarRenderState(state = SphereState.Idle)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                key(pet.id) {
                    previewPet.Render(
                        state = renderState.copy(paused = !animationEnabled),
                        modifier = Modifier.size(88.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.petdex_selected),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = pet.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (selectedMapping != null) {
                        Text(
                            text = petPreviewMappingDescription(selectedMapping),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            if (mappings.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    mappings.forEach { mapping ->
                        FilterChip(
                            selected = mapping.action == selectedMapping?.action,
                            onClick = { selectedAction = mapping.action },
                            label = { Text(petPreviewActionLabel(mapping.action)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun petPreviewActionLabel(action: PetPreviewAction): String = stringResource(
    when (action) {
        PetPreviewAction.Idle -> R.string.pet_preview_idle
        PetPreviewAction.WalkLeft -> R.string.pet_preview_walk_left
        PetPreviewAction.WalkRight -> R.string.pet_preview_walk_right
        PetPreviewAction.Jump -> R.string.pet_preview_jump
        PetPreviewAction.Fall -> R.string.pet_preview_fall
        PetPreviewAction.Held -> R.string.pet_preview_held
        PetPreviewAction.Wave -> R.string.pet_preview_wave
        PetPreviewAction.Working -> R.string.pet_preview_working
        PetPreviewAction.Review -> R.string.pet_preview_review
        PetPreviewAction.Waiting -> R.string.pet_preview_waiting
        PetPreviewAction.Error -> R.string.pet_preview_error
    },
)

@Composable
private fun petPreviewMappingDescription(mapping: PetPreviewMapping): String = when (mapping.support) {
    PetPreviewSupport.Direct -> stringResource(
        R.string.pet_preview_uses_clip,
        mapping.sourceKey,
    )
    PetPreviewSupport.Mirrored -> stringResource(
        R.string.pet_preview_mirrors_clip,
        mapping.sourceKey,
    )
    PetPreviewSupport.Fallback -> stringResource(
        R.string.pet_preview_falls_back,
        mapping.sourceKey,
    )
    PetPreviewSupport.MirroredFallback -> stringResource(
        R.string.pet_preview_mirrors_fallback,
        mapping.sourceKey,
    )
}

@Composable
private fun PetdexPetCard(
    pet: PetdexPet,
    thumbnailLoader: PetdexThumbnailLoader,
    installed: Boolean,
    selected: Boolean,
    installing: Boolean,
    installEnabled: Boolean,
    installFailed: Boolean,
    onViewSource: () -> Unit,
    onUse: () -> Unit,
    onInstall: () -> Unit,
) {
    val shape = appearanceRoundedCornerShape(16.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PetdexThumbnail(
                pet = pet,
                loader = thumbnailLoader,
                installing = installing,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pet.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = onViewSource,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.petdex_view_source),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Text(
                text = if (pet.submittedBy.isBlank()) {
                    stringResource(R.string.petdex_unknown_creator)
                } else {
                    stringResource(R.string.petdex_by_creator, pet.submittedBy)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (installFailed) {
                Text(
                    text = stringResource(R.string.petdex_install_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                selected -> Text(
                    text = stringResource(R.string.petdex_selected),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                installed -> Button(
                    onClick = onUse,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.petdex_use))
                }
                else -> Button(
                    onClick = onInstall,
                    enabled = installEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (installing) stringResource(R.string.petdex_installing)
                        else stringResource(R.string.petdex_install)
                    )
                }
            }
        }
    }
}

@Composable
private fun PetdexThumbnail(
    pet: PetdexPet,
    loader: PetdexThumbnailLoader,
    installing: Boolean,
) {
    var phase by remember(pet.slug, pet.spritesheetUrl) {
        mutableStateOf<PetdexThumbnailPhase>(PetdexThumbnailPhase.Loading)
    }
    LaunchedEffect(pet.slug, pet.spritesheetUrl, loader) {
        phase = loader.load(pet)
            ?.let(PetdexThumbnailPhase::Loaded)
            ?: PetdexThumbnailPhase.Unavailable
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(PETDEX_FRAME_ASPECT)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = phase) {
            PetdexThumbnailPhase.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
            PetdexThumbnailPhase.Unavailable -> Icon(
                imageVector = Icons.Filled.Pets,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is PetdexThumbnailPhase.Loaded -> Image(
                bitmap = current.bitmap,
                contentDescription = pet.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        }
        if (installing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    strokeWidth = 2.5.dp,
                )
            }
        }
    }
}

@Composable
private fun PetdexLoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(PETDEX_FRAME_ASPECT)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .size(width = 96.dp, height = 14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surface),
            )
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

private sealed interface PetdexThumbnailPhase {
    data object Loading : PetdexThumbnailPhase
    data object Unavailable : PetdexThumbnailPhase
    data class Loaded(val bitmap: ImageBitmap) : PetdexThumbnailPhase
}

private class PetdexThumbnailLoader(
    private val request: suspend (slug: String, url: String) -> Result<String?>?,
) {
    private val bitmapCache = LinkedHashMap<String, ImageBitmap>(
        PETDEX_THUMB_CACHE_SIZE,
        0.75f,
        true,
    )
    private val missingKeys = mutableSetOf<String>()
    private val retryAfterMs = mutableMapOf<String, Long>()
    private val cacheMutex = Mutex()
    private val requestSlots = Semaphore(PETDEX_THUMB_CONCURRENCY)
    private var thumbnailsUnsupported = false

    suspend fun load(pet: PetdexPet): ImageBitmap? {
        val cacheKey = "${pet.slug}\u0000${pet.spritesheetUrl}"
        while (true) {
            when (val cached = cacheMutex.withLock { cachedOutcome(cacheKey) }) {
                is PetdexThumbnailLoadOutcome.Loaded -> return cached.bitmap
                PetdexThumbnailLoadOutcome.Unavailable -> return null
                is PetdexThumbnailLoadOutcome.Retry -> {
                    delay(cached.delayMs)
                    continue
                }
                null -> Unit
            }
            val outcome = requestSlots.withPermit {
                cacheMutex.withLock {
                    cachedOutcome(cacheKey)?.let { return@withPermit it }
                }
                val result = request(pet.slug, pet.spritesheetUrl)
                val methodUnavailable = (result?.exceptionOrNull() as? GatewayRpcException)?.code ==
                    JSONRPC_METHOD_NOT_FOUND
                val failed = result == null || result.isFailure
                val dataUri = result?.getOrNull()
                val bitmap = dataUri?.let { decodePetdexThumbnail(it) }
                cacheMutex.withLock {
                    when {
                        methodUnavailable -> {
                            thumbnailsUnsupported = true
                            PetdexThumbnailLoadOutcome.Unavailable
                        }
                        failed || (dataUri != null && bitmap == null) -> {
                            retryAfterMs[cacheKey] = System.currentTimeMillis() + PETDEX_THUMB_RETRY_MS
                            PetdexThumbnailLoadOutcome.Retry(PETDEX_THUMB_RETRY_MS)
                        }
                        dataUri == null -> {
                            missingKeys += cacheKey
                            PetdexThumbnailLoadOutcome.Unavailable
                        }
                        bitmap != null -> {
                            retryAfterMs.remove(cacheKey)
                            bitmapCache[cacheKey] = bitmap
                            while (bitmapCache.size > PETDEX_THUMB_CACHE_SIZE) {
                                bitmapCache.remove(bitmapCache.entries.first().key)
                            }
                            PetdexThumbnailLoadOutcome.Loaded(bitmap)
                        }
                        else -> PetdexThumbnailLoadOutcome.Unavailable
                    }
                }
            }
            when (outcome) {
                is PetdexThumbnailLoadOutcome.Loaded -> return outcome.bitmap
                PetdexThumbnailLoadOutcome.Unavailable -> return null
                is PetdexThumbnailLoadOutcome.Retry -> delay(outcome.delayMs)
            }
        }
    }

    /** Must be called while [cacheMutex] is held. Null means a network attempt is needed. */
    private fun cachedOutcome(cacheKey: String): PetdexThumbnailLoadOutcome? {
        bitmapCache[cacheKey]?.let { return PetdexThumbnailLoadOutcome.Loaded(it) }
        if (cacheKey in missingKeys || thumbnailsUnsupported) {
            return PetdexThumbnailLoadOutcome.Unavailable
        }
        val remainingBackoff = (retryAfterMs[cacheKey] ?: 0L) - System.currentTimeMillis()
        return if (remainingBackoff > 0L) {
            PetdexThumbnailLoadOutcome.Retry(remainingBackoff)
        } else {
            null
        }
    }
}

private sealed interface PetdexThumbnailLoadOutcome {
    data object Unavailable : PetdexThumbnailLoadOutcome
    data class Loaded(val bitmap: ImageBitmap) : PetdexThumbnailLoadOutcome
    data class Retry(val delayMs: Long) : PetdexThumbnailLoadOutcome
}

private suspend fun decodePetdexThumbnail(dataUri: String): ImageBitmap? = withInlineImageDecodeLock {
    val decoded = decodeInlineImageDataUrl(dataUri, PETDEX_THUMB_MAX_BYTES)
        ?: return@withInlineImageDecodeLock null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(decoded.bytes, 0, decoded.bytes.size, bounds)
    if (bounds.outWidth !in 1..PETDEX_THUMB_MAX_WIDTH ||
        bounds.outHeight !in 1..PETDEX_THUMB_MAX_HEIGHT
    ) {
        return@withInlineImageDecodeLock null
    }
    BitmapFactory.decodeByteArray(decoded.bytes, 0, decoded.bytes.size)
        ?.asImageBitmap()
}
