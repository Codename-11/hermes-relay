package com.hermesandroid.relay.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.plugins.document.PluginAction
import com.hermesandroid.relay.plugins.document.PluginDocument
import com.hermesandroid.relay.plugins.document.PluginDocumentState
import com.hermesandroid.relay.plugins.document.PluginDocumentValidation
import com.hermesandroid.relay.plugins.document.PluginDocumentValidator
import com.hermesandroid.relay.plugins.document.PluginValue
import com.hermesandroid.relay.plugins.runtime.ANDROID_PLUGIN_HOST_API_VERSION
import com.hermesandroid.relay.plugins.runtime.AndroidPluginCatalogEntry
import com.hermesandroid.relay.plugins.runtime.AndroidPluginContribution
import com.hermesandroid.relay.plugins.runtime.AndroidPluginManifest
import com.hermesandroid.relay.plugins.runtime.DiscoveredAndroidPlugin
import com.hermesandroid.relay.plugins.runtime.PLUGIN_API_WRITE_CAPABILITY
import com.hermesandroid.relay.plugins.runtime.PluginDiscoveryClient
import com.hermesandroid.relay.plugins.runtime.PluginCatalogPreview
import com.hermesandroid.relay.plugins.runtime.PluginCatalogRefreshPolicy
import com.hermesandroid.relay.plugins.runtime.PluginHostContext
import com.hermesandroid.relay.plugins.runtime.PluginLifecycleSnapshot
import com.hermesandroid.relay.plugins.runtime.PluginLifecycleTracker
import com.hermesandroid.relay.plugins.runtime.PluginPreferenceState
import com.hermesandroid.relay.plugins.runtime.PluginPreferenceStore
import com.hermesandroid.relay.plugins.runtime.PluginScope
import com.hermesandroid.relay.plugins.runtime.ScopedPluginApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

data class PluginHubItem(
    val catalog: AndroidPluginCatalogEntry,
    val manifest: AndroidPluginManifest,
    val preferences: PluginPreferenceState,
)

sealed interface PluginsHubState {
    data object Disconnected : PluginsHubState
    data object Loading : PluginsHubState
    data class Ready(
        val plugins: List<PluginHubItem>,
        val preview: PluginCatalogPreview,
        val refreshing: Boolean = false,
        val refreshError: String? = null,
    ) : PluginsHubState
    data class Error(val message: String) : PluginsHubState
}

sealed interface PluginPageState {
    data object Idle : PluginPageState
    data object Loading : PluginPageState
    data class Ready(
        val plugin: PluginHubItem,
        val contribution: AndroidPluginContribution,
        val document: PluginDocument,
        val documentState: PluginDocumentState,
        val hostContext: PluginHostContext,
        val refreshing: Boolean = false,
        val refreshError: String? = null,
    ) : PluginPageState
    data class Error(val message: String) : PluginPageState
}

class PluginsViewModel(application: Application) : AndroidViewModel(application) {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
    private val preferences = PluginPreferenceStore(application)
    private val lifecycleTracker = PluginLifecycleTracker()
    private val _lifecycle = MutableStateFlow(lifecycleTracker.snapshot)
    val lifecycle: StateFlow<PluginLifecycleSnapshot> = _lifecycle.asStateFlow()
    private val _hubState = MutableStateFlow<PluginsHubState>(PluginsHubState.Disconnected)
    val hubState: StateFlow<PluginsHubState> = _hubState.asStateFlow()
    private val _pageState = MutableStateFlow<PluginPageState>(PluginPageState.Idle)
    val pageState: StateFlow<PluginPageState> = _pageState.asStateFlow()

    private var hostKey: String? = null
    private var connectionId: String? = null
    private var profileName: String? = null
    private var dashboard: DashboardApiClient? = null
    private var refreshJob: Job? = null
    private var liveRefreshJob: Job? = null
    private var pageRefreshJob: Job? = null
    private var catalogVisible: Boolean = false
    private var pageVisible: Boolean = false
    private var pageRequest: Pair<String, String>? = null

    fun configure(
        connectionId: String?,
        dashboardUrl: String?,
        profileName: String?,
        dashboardFactory: (String) -> DashboardApiClient,
        sessionId: String? = null,
    ) {
        val nextKey = connectionId?.takeIf { it.isNotBlank() }?.let { id ->
            dashboardUrl?.takeIf { it.isNotBlank() }?.let { "$id\u0000${profileName.orEmpty()}\u0000$it" }
        }
        if (nextKey == hostKey) {
            updateSessionContext(sessionId)
            return
        }
        refreshJob?.cancel()
        liveRefreshJob?.cancel()
        liveRefreshJob = null
        pageRefreshJob?.cancel()
        pageRefreshJob = null
        dashboard?.shutdown()
        hostKey = nextKey
        this.connectionId = connectionId
        this.profileName = profileName
        _lifecycle.value = lifecycleTracker.update(
            nextKey?.let { PluginHostContext(connectionId, profileName, sessionId) },
        )
        dashboard = if (nextKey == null) null else dashboardFactory(dashboardUrl!!)
        _pageState.value = PluginPageState.Idle
        pageRequest = null
        if (nextKey == null) {
            _hubState.value = PluginsHubState.Disconnected
        } else {
            refresh()
            startLiveCatalogRefreshIfNeeded()
        }
    }

    /** Updates chat/session provenance without rebuilding the connection-scoped client. */
    fun updateSessionContext(sessionId: String?) {
        val context = lifecycleTracker.snapshot.context ?: return
        _lifecycle.value = lifecycleTracker.update(context.copy(sessionId = sessionId))
        val ready = _hubState.value as? PluginsHubState.Ready ?: return
        _hubState.value = ready.copy(preview = ready.preview.copy(context = lifecycleTracker.snapshot.context!!))
        val page = _pageState.value as? PluginPageState.Ready ?: return
        _pageState.value = page.copy(hostContext = lifecycleTracker.snapshot.context!!)
    }

    /** Enables foreground-only catalog refresh while the Plugins hub owns the visible surface. */
    fun setCatalogVisible(visible: Boolean) {
        if (catalogVisible == visible) return
        catalogVisible = visible
        liveRefreshJob?.cancel()
        liveRefreshJob = null
        updateLiveRefreshPreview(visible)
        startLiveCatalogRefreshIfNeeded()
    }

    private fun startLiveCatalogRefreshIfNeeded() {
        if (!catalogVisible || dashboard == null || liveRefreshJob != null) return
        liveRefreshJob = viewModelScope.launch {
            while (true) {
                delay(PluginCatalogRefreshPolicy.VISIBLE_REFRESH_INTERVAL_MILLIS)
                refresh(showLoading = false)
            }
        }
    }

    /** Enables manifest-declared polling only while a plugin page is visible. */
    fun setPageVisible(visible: Boolean) {
        if (pageVisible == visible) return
        pageVisible = visible
        pageRefreshJob?.cancel()
        pageRefreshJob = null
        startPageRefreshIfNeeded()
    }

    private fun startPageRefreshIfNeeded() {
        if (!pageVisible || pageRefreshJob != null) return
        val ready = _pageState.value as? PluginPageState.Ready ?: return
        val interval = PluginCatalogRefreshPolicy.pageRefreshIntervalMillis(
            ready.plugin.manifest.updates?.pollSeconds,
        ) ?: return
        val pluginId = ready.plugin.catalog.id
        val pageId = ready.contribution.id
        pageRefreshJob = viewModelScope.launch {
            while (true) {
                delay(interval)
                loadPage(pluginId, pageId, showLoading = false)
            }
        }
    }

    fun refresh() = refresh(showLoading = true)

    private fun refresh(showLoading: Boolean) {
        val client = dashboard ?: run {
            _hubState.value = PluginsHubState.Disconnected
            return
        }
        val expectedKey = hostKey ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val previous = _hubState.value as? PluginsHubState.Ready
            _hubState.value = if (showLoading || previous == null) {
                PluginsHubState.Loading
            } else {
                previous.copy(refreshing = true, refreshError = null)
            }
            val result: Result<List<PluginHubItem>> = try {
                val discovered = PluginDiscoveryClient(client).discover().getOrThrow()
                val items = buildList {
                    for (candidate in discovered) {
                        toHubItem(candidate)?.let(::add)
                    }
                }
                Result.success(items)
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (hostKey != expectedKey) return@launch
            _hubState.value = result.fold(
                onSuccess = { items ->
                    PluginsHubState.Ready(
                        plugins = items,
                        preview = catalogPreview(items),
                    )
                },
                onFailure = { error ->
                    val message = error.message ?: "Plugin discovery failed"
                    previous?.copy(refreshing = false, refreshError = message)
                        ?: PluginsHubState.Error(message)
                },
            )
            if (pageVisible) {
                pageRequest?.let { (pluginId, pageId) -> loadPage(pluginId, pageId) }
            }
        }
    }

    fun setEnabled(pluginId: String, enabled: Boolean) {
        val scope = scope(pluginId) ?: return
        viewModelScope.launch {
            preferences.setEnabled(scope, enabled)
            updatePreference(pluginId, preferences.state(scope).first())
            if (!enabled && (pageState.value as? PluginPageState.Ready)?.plugin?.catalog?.id == pluginId) {
                pageRefreshJob?.cancel()
                pageRefreshJob = null
                _pageState.value = PluginPageState.Error("This plugin is disabled")
            }
        }
    }

    fun setWriteGrant(pluginId: String, granted: Boolean) {
        val scope = scope(pluginId) ?: return
        viewModelScope.launch {
            val current = preferences.state(scope).first()
            val grants = if (granted) current.grants + PLUGIN_API_WRITE_CAPABILITY
            else current.grants - PLUGIN_API_WRITE_CAPABILITY
            preferences.setGrants(scope, grants)
            updatePreference(pluginId, preferences.state(scope).first())
        }
    }

    fun loadPage(pluginId: String, pageId: String) = loadPage(pluginId, pageId, showLoading = true)

    private fun loadPage(pluginId: String, pageId: String, showLoading: Boolean) {
        if (pageRequest != pluginId to pageId) {
            pageRefreshJob?.cancel()
            pageRefreshJob = null
        }
        pageRequest = pluginId to pageId
        val plugin = (_hubState.value as? PluginsHubState.Ready)
            ?.plugins?.firstOrNull { it.catalog.id == pluginId }
            ?: run {
                _pageState.value = PluginPageState.Error("Plugin not found")
                return
            }
        if (!plugin.preferences.enabled) {
            _pageState.value = PluginPageState.Error("This plugin is disabled")
            return
        }
        val contribution = plugin.manifest.contributions.firstOrNull {
            it.id == pageId && it.surface == "page" && it.document.method.equals("GET", true)
        } ?: run {
            _pageState.value = PluginPageState.Error("Plugin page not found")
            return
        }
        val client = dashboard ?: return
        val expectedKey = hostKey
        viewModelScope.launch {
            val previous = _pageState.value as? PluginPageState.Ready
            _pageState.value = if (showLoading || previous == null) {
                PluginPageState.Loading
            } else {
                previous.copy(refreshing = true, refreshError = null)
            }
            val api = ScopedPluginApiClient(pluginId, client)
            val result = api.get(contribution.document.path.removePrefix("/"))
                .mapCatching { json.decodeFromJsonElement<PluginDocument>(it) }
                .mapCatching { document ->
                    when (val validation = PluginDocumentValidator.validate(document)) {
                        PluginDocumentValidation.Valid -> document
                        is PluginDocumentValidation.Invalid -> error(validation.errors.joinToString("; "))
                    }
                }
            if (hostKey != expectedKey || pageRequest != pluginId to pageId) return@launch
            _pageState.value = result.fold(
                onSuccess = { document ->
                    val context = lifecycleTracker.snapshot.context
                        ?: return@fold PluginPageState.Error("Plugin connection unavailable")
                    PluginPageState.Ready(
                        plugin,
                        contribution,
                        document,
                        if (
                            !showLoading &&
                            previous != null &&
                            (
                                document == previous.document ||
                                    document.hostRevision != null &&
                                    document.hostRevision == previous.document.hostRevision
                                )
                        ) {
                            previous.documentState
                        } else {
                            PluginDocumentState.from(document)
                        },
                        context,
                    )
                },
                onFailure = { error ->
                    val message = error.message ?: "Plugin page failed to load"
                    previous?.copy(refreshing = false, refreshError = message)
                        ?: PluginPageState.Error(message)
                },
            )
            startPageRefreshIfNeeded()
        }
    }

    fun updateValue(key: String, value: PluginValue) {
        val ready = _pageState.value as? PluginPageState.Ready ?: return
        _pageState.value = ready.copy(documentState = ready.documentState.updated(key, value))
    }

    fun invokeAction(action: PluginAction) {
        val ready = _pageState.value as? PluginPageState.Ready ?: return
        val request = action.request ?: return
        val client = dashboard ?: return
        val method = request.method.uppercase()
        if (method != "GET" && PLUGIN_API_WRITE_CAPABILITY !in ready.plugin.preferences.grants) {
            _pageState.value = PluginPageState.Error("Allow plugin changes before using this action")
            return
        }
        val pluginId = ready.plugin.catalog.id
        val pageId = ready.contribution.id
        val expectedKey = hostKey
        viewModelScope.launch {
            val api = ScopedPluginApiClient(pluginId, client)
            val payload = buildJsonObject {
                put("arguments", json.encodeToJsonElement(action.arguments))
                put("state", json.encodeToJsonElement(ready.documentState.values))
            }
            val result = when (method) {
                "GET" -> api.get(request.path.removePrefix("/")).map { Unit }
                "POST" -> api.post(request.path.removePrefix("/"), payload).map { Unit }
                "PUT" -> api.put(request.path.removePrefix("/"), payload).map { Unit }
                "PATCH" -> api.patch(request.path.removePrefix("/"), payload).map { Unit }
                "DELETE" -> api.delete(request.path.removePrefix("/")).map { Unit }
                else -> Result.failure(IllegalArgumentException("Unsupported plugin action method: $method"))
            }
            if (hostKey != expectedKey) return@launch
            result.fold(
                onSuccess = { loadPage(pluginId, pageId) },
                onFailure = { _pageState.value = PluginPageState.Error(it.message ?: "Plugin action failed") },
            )
        }
    }

    /** Publishes a Relay-generated draft after an explicit Android user action. */
    fun publishGeneratedPage(pluginId: String, contribution: AndroidPluginContribution) {
        mutateGeneratedPage(pluginId, contribution, remove = false)
    }

    /** Removes a Relay-generated page after an explicit Android user confirmation. */
    fun removeGeneratedPage(pluginId: String, contribution: AndroidPluginContribution) {
        mutateGeneratedPage(pluginId, contribution, remove = true)
    }

    private fun mutateGeneratedPage(
        pluginId: String,
        contribution: AndroidPluginContribution,
        remove: Boolean,
    ) {
        val ready = _hubState.value as? PluginsHubState.Ready ?: return
        val plugin = ready.plugins.firstOrNull { it.catalog.id == pluginId } ?: return
        if (pluginId != RELAY_PLUGIN_ID || contribution.status !in GENERATED_PLUGIN_STATUSES) return
        val expectedDigest = contribution.digest?.takeIf { it.startsWith("sha256:") } ?: return
        if (PLUGIN_API_WRITE_CAPABILITY !in plugin.preferences.grants) return
        val client = dashboard ?: return
        val expectedKey = hostKey
        viewModelScope.launch {
            val api = ScopedPluginApiClient(pluginId, client)
            val path = "mobile/plugins/${contribution.id}"
            val payload = buildJsonObject { put("expected_digest", expectedDigest) }
            val result = api.post(
                if (remove) "$path/remove" else "$path/promote",
                payload,
            )
            if (hostKey != expectedKey) return@launch
            result.fold(
                onSuccess = {
                    if (pageRequest?.second == contribution.id) {
                        pageRequest = null
                        pageRefreshJob?.cancel()
                        pageRefreshJob = null
                        _pageState.value = PluginPageState.Idle
                    }
                    refresh(showLoading = false)
                },
                onFailure = { error ->
                    val current = _hubState.value as? PluginsHubState.Ready ?: return@fold
                    _hubState.value = current.copy(
                        refreshing = false,
                        refreshError = error.message ?: "Plugin update failed",
                    )
                },
            )
        }
    }

    private suspend fun toHubItem(discovered: DiscoveredAndroidPlugin): PluginHubItem? {
        val manifest = runCatching {
            json.decodeFromJsonElement<AndroidPluginManifest>(discovered.manifest)
        }.getOrNull() ?: return null
        if (manifest.id != discovered.catalog.id || manifest.schemaVersion != 1 ||
            manifest.minHostApi > ANDROID_PLUGIN_HOST_API_VERSION
        ) return null
        val scope = scope(manifest.id) ?: return null
        val saved = preferences.state(scope).first()
        val effective = if (!saved.configured && manifest.defaultEnabled) {
            saved.copy(enabled = true)
        } else saved
        return PluginHubItem(discovered.catalog, manifest, effective)
    }

    private fun scope(pluginId: String): PluginScope? = connectionId?.let {
        PluginScope(it, profileName, pluginId)
    }

    private fun updatePreference(pluginId: String, state: PluginPreferenceState) {
        val ready = _hubState.value as? PluginsHubState.Ready ?: return
        val plugins = ready.plugins.map {
            if (it.catalog.id == pluginId) it.copy(preferences = state) else it
        }
        _hubState.value = ready.copy(plugins = plugins, preview = catalogPreview(plugins))
    }

    private fun catalogPreview(items: List<PluginHubItem>): PluginCatalogPreview {
        val context = checkNotNull(lifecycleTracker.snapshot.context)
        return PluginCatalogPreview(
            context = context,
            pluginCount = items.size,
            enabledPluginCount = items.count { it.preferences.enabled },
            pageCount = items.sumOf { plugin ->
                plugin.manifest.contributions.count { it.surface == "page" }
            },
            refreshedAtEpochMillis = System.currentTimeMillis(),
            liveRefreshEnabled = catalogVisible,
        )
    }

    private fun updateLiveRefreshPreview(enabled: Boolean) {
        val ready = _hubState.value as? PluginsHubState.Ready ?: return
        _hubState.value = ready.copy(preview = ready.preview.copy(liveRefreshEnabled = enabled))
    }

    override fun onCleared() {
        liveRefreshJob?.cancel()
        pageRefreshJob?.cancel()
        dashboard?.shutdown()
    }

    private companion object {
        const val RELAY_PLUGIN_ID = "hermes-relay"
        val GENERATED_PLUGIN_STATUSES = setOf("draft", "published")
    }
}
