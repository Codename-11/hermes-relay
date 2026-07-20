package com.hermesandroid.relay.network.upstream

import android.content.Context
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.network.shutdownOffMainThread
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.network.upstream.models.MessageListResponse
import com.hermesandroid.relay.network.upstream.models.SessionItem
import com.hermesandroid.relay.network.upstream.models.SessionListResponse
import com.hermesandroid.relay.network.upstream.models.SessionPruneFilters
import com.hermesandroid.relay.network.upstream.models.SessionPrunePreview
import com.hermesandroid.relay.network.upstream.models.SessionPruneResult
import com.hermesandroid.relay.auth.SecureStoreCache
import com.hermesandroid.relay.auth.SessionTokenStore
import com.hermesandroid.relay.auth.buildRawTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// Status/session/provider snapshots are @Serializable so the Manage tab's
// disk cache (DashboardManageDiskCache) can persist Loaded entries verbatim.
@Serializable
data class DashboardStatus(
    val authRequired: Boolean,
    val authProviders: List<String> = emptyList(),
    val authProviderDetails: List<DashboardAuthProvider> = emptyList(),
    val version: String? = null,
    val message: String? = null,
    @SerialName("nous_session_valid") val nousSessionValid: String? = null,
    val profiles: List<String> = emptyList(),
    @SerialName("gateway_mode") val gatewayMode: String? = null,
    val gateways: List<DashboardGatewayTopology> = emptyList(),
)

@Serializable
data class DashboardGatewayTopology(
    val profile: String,
    val ports: Map<String, Int> = emptyMap(),
    @SerialName("served_profiles") val servedProfiles: List<String> = emptyList(),
)

@Serializable
data class DashboardAuthProvider(
    val name: String,
    val displayName: String? = null,
    val supportsPassword: Boolean = false,
) {
    val isRedirectProvider: Boolean
        get() = !supportsPassword
}

data class DashboardLoginResponse(
    val ok: Boolean,
    val next: String? = null,
    val message: String? = null,
)

@Serializable
data class DashboardAuthSession(
    val authenticated: Boolean,
    val username: String? = null,
    val provider: String? = null,
)

data class DashboardWsTicket(
    val ticket: String,
    val ttlSeconds: Int? = null,
)

/** Sticky server default and the profile that owns the running dashboard process. */
data class DashboardProfileScope(
    val active: String,
    val current: String,
)

data class DashboardChatDisplaySettings(
    val showReasoning: Boolean? = null,
    val toolDisplay: String? = null,
)

data class DashboardMcpOAuthFlow(
    val flowId: String,
    val serverName: String,
    val status: String,
    val authorizationUrl: String? = null,
    val error: String? = null,
) {
    val isTerminal: Boolean get() = status == "approved" || status == "error"
}

data class DashboardCustomEndpoint(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val models: List<String> = emptyList(),
    val contextLength: Int? = null,
    val discoverModels: Boolean = true,
    val hasApiKey: Boolean = false,
    val apiKeyPreview: String? = null,
    val isCurrent: Boolean = false,
)

data class DashboardCustomEndpoints(
    val endpoints: List<DashboardCustomEndpoint>,
    val currentProvider: String? = null,
    val currentModel: String? = null,
)

data class DashboardCustomEndpointDraft(
    val id: String? = null,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String? = null,
    val contextLength: Int? = null,
    val discoverModels: Boolean = true,
    val makeDefault: Boolean = false,
)

data class DashboardCustomEndpointValidation(
    val ok: Boolean,
    val reachable: Boolean,
    val message: String,
    val models: List<String>,
)

/** One entry from `GET /api/audio/elevenlabs/voices` — non-secret voice metadata. */
data class ElevenLabsVoice(
    val voiceId: String,
    val name: String,
    val label: String,
)

/**
 * Result of `GET /api/audio/elevenlabs/voices`. [available] is false when the
 * server has no `ELEVENLABS_API_KEY` configured (the picker degrades to a free
 * text field in that case); true with a populated [voices] list otherwise.
 */
data class ElevenLabsVoices(
    val available: Boolean,
    val voices: List<ElevenLabsVoice>,
)

/**
 * Native client for the Hermes dashboard/admin server (:9119).
 *
 * This is deliberately separate from [HermesApiClient] and all relay pairing
 * clients. Dashboard cookies authenticate standard admin surfaces such as
 * skills/cron/MCP/profile config; relay pairing remains the auth path for
 * terminal, bridge, media relay, and profile memory file editing.
 */
class DashboardApiClient(
    baseUrl: String,
    private val okHttpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    },
) {
    private val baseUrl: String = baseUrl.trim().trimEnd('/')

    /**
     * Resolve a request URL without ever throwing. okhttp's
     * [Request.Builder.url] (String overload) throws `IllegalArgumentException`
     * (`Invalid URL host: "..."`) on a malformed host — e.g. a non-URL value
     * such as a UI label / docs line reaching the dashboard-URL slot (#131). If
     * that throw escapes one of this client's `withContext(IO)` suspend lambdas
     * on a Main-dispatched caller, the app force-closes. Parsing via
     * [toHttpUrlOrNull] lets every method short-circuit to [Result.failure]
     * instead. Returns null when `baseUrl + pathAndQuery` is not a valid http(s)
     * URL.
     */
    private fun resolveUrl(pathAndQuery: String): HttpUrl? =
        "$baseUrl$pathAndQuery".toHttpUrlOrNull()

    private fun invalidUrlException(): IOException =
        IOException("Dashboard URL \"$baseUrl\" is not a valid http(s) address")

    suspend fun getStatus(): Result<DashboardStatus> = withContext(Dispatchers.IO) {
        getJson("/api/status").mapCatching { parseStatus(it) }
    }

    suspend fun getAuthProviders(): Result<List<DashboardAuthProvider>> = withContext(Dispatchers.IO) {
        getJson("/api/auth/providers").mapCatching { root ->
            parseProviders(root["providers"])
        }
    }

    suspend fun getJsonObject(path: String): Result<JsonObject> = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        getJson(normalized)
    }

    suspend fun getJsonElement(path: String): Result<JsonElement> = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val httpUrl = resolveUrl(normalized) ?: return@withContext Result.failure(invalidUrlException())
        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .build()
        executeJsonElement(request, normalized)
    }

    suspend fun postJsonObject(
        path: String,
        payload: JsonObject = JsonObject(emptyMap()),
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val httpUrl = resolveUrl(normalized) ?: return@withContext Result.failure(invalidUrlException())
        val request = Request.Builder()
            .url(httpUrl)
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()
        executeJson(request, normalized)
    }

    suspend fun putJsonObject(
        path: String,
        payload: JsonObject,
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val httpUrl = resolveUrl(normalized) ?: return@withContext Result.failure(invalidUrlException())
        val request = Request.Builder()
            .url(httpUrl)
            .put(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()
        executeJson(request, normalized)
    }

    suspend fun patchJsonObject(
        path: String,
        payload: JsonObject,
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val httpUrl = resolveUrl(normalized) ?: return@withContext Result.failure(invalidUrlException())
        val request = Request.Builder()
            .url(httpUrl)
            .patch(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()
        executeJson(request, normalized)
    }

    suspend fun deleteJsonObject(path: String): Result<JsonObject> = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val httpUrl = resolveUrl(normalized) ?: return@withContext Result.failure(invalidUrlException())
        val request = Request.Builder()
            .url(httpUrl)
            .delete()
            .build()
        executeJson(request, normalized)
    }

    /** DELETE with a JSON body — upstream's `DELETE /api/env` reads the key from the body. */
    suspend fun deleteJsonObjectWithBody(
        path: String,
        payload: JsonObject,
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val httpUrl = resolveUrl(normalized) ?: return@withContext Result.failure(invalidUrlException())
        val request = Request.Builder()
            .url(httpUrl)
            .delete(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()
        executeJson(request, normalized)
    }

    // --- Models (dashboard parity with hermes-desktop Settings → Model) ---

    suspend fun getChatDisplaySettings(): Result<DashboardChatDisplaySettings> =
        getJsonObject("/api/config").mapCatching { root -> parseChatDisplaySettings(root) }

    // --- Config tree (dashboard parity with hermes-desktop Settings → config.yaml) ---

    /**
     * The full runtime config VALUES as a nested tree (model/tts/stt/...).
     * Upstream strips internal `_`-prefixed keys server-side, so the object is
     * safe to mutate and round-trip back through [updateConfig].
     */
    suspend fun getConfig(): Result<JsonObject> = getJsonObject("/api/config")

    /**
     * The config SCHEMA: `{fields: {<dot.path>: {type, description, category,
     * options?}}, category_order: [...]}`. Describes how to render each field;
     * pair it with [getConfig] for current values. Note this is distinct from
     * the values tree — `fields` keys are flat dot-paths, the values are nested.
     */
    suspend fun getConfigSchema(): Result<JsonObject> = getJsonObject("/api/config/schema")

    /**
     * Replace the runtime config (`PUT /api/config`). Upstream `save_config`
     * writes the WHOLE document, so [config] MUST be the full values tree
     * (read [getConfig], mutate, write back) — a partial object would drop
     * every key it omits. [profile] null/blank targets the launch profile.
     */
    suspend fun updateConfig(config: JsonObject, profile: String? = null): Result<JsonObject> =
        putJsonObject(
            path = "/api/config",
            payload = buildJsonObject {
                put("config", config)
                profile?.trim()?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            },
        )

    /**
     * ElevenLabs voice catalog for the `tts.elevenlabs.voice_id` picker
     * (`GET /api/audio/elevenlabs/voices`, dashboard cookie auth). Returns
     * `available=false` with an empty list when the server has no API key
     * configured; the API key itself never leaves the server.
     */
    suspend fun getElevenLabsVoices(): Result<ElevenLabsVoices> = withContext(Dispatchers.IO) {
        getJson("/api/audio/elevenlabs/voices").mapCatching { parseElevenLabsVoices(it) }
    }

    /**
     * Full provider/model universe — REST twin of the TUI's `model.options` RPC.
     *
     * Always opts into `include_unconfigured=1`: newer upstream defaults this
     * route to configured-providers-only, which would silently drop the
     * unauthenticated skeleton rows Manage renders as its Keys-setup
     * affordance. Older upstream returned the full universe by default and
     * ignores the extra param, so both generations serve the same catalog.
     *
     * [refresh] maps to upstream's explicit `refresh=1` path, which refreshes
     * dynamic/custom-provider catalogs on demand without probing every
     * provider during normal picker opens.
     */
    suspend fun getModelOptions(refresh: Boolean = false): Result<JsonObject> =
        getJsonObject(
            if (refresh) {
                "/api/model/options?refresh=1&include_unconfigured=1"
            } else {
                "/api/model/options?include_unconfigured=1"
            },
        )

    /**
     * Assign the main model in `~/.hermes/config.yaml` (new sessions only).
     * Upstream may answer `{ok: false, confirm_required: true, warning: ...}`
     * for expensive models — re-call with [confirmExpensive] after the user
     * accepts the warning.
     */
    suspend fun setMainModel(
        provider: String,
        model: String,
        confirmExpensive: Boolean = false,
    ): Result<JsonObject> =
        postJsonObject(
            path = "/api/model/set",
            payload = buildJsonObject {
                put("scope", "main")
                put("provider", provider)
                put("model", model)
                if (confirmExpensive) put("confirm_expensive_model", true)
            },
        )

    // --- Env / keys (dashboard parity with hermes-desktop Settings → Keys) ---

    /** Curated env-var inventory: name → {is_set, redacted_value, description, category, ...}. */
    suspend fun getEnvVars(): Result<JsonObject> = getJsonObject("/api/env")

    suspend fun setEnvVar(key: String, value: String): Result<JsonObject> =
        putJsonObject(
            path = "/api/env",
            payload = buildJsonObject {
                put("key", key)
                put("value", value)
            },
        )

    suspend fun deleteEnvVar(key: String): Result<JsonObject> =
        deleteJsonObjectWithBody(
            path = "/api/env",
            payload = buildJsonObject { put("key", key) },
        )

    /** Server rate-limits reveals (5 per 30s) and audit-logs each one. */
    suspend fun revealEnvVar(key: String): Result<JsonObject> =
        postJsonObject(
            path = "/api/env/reveal",
            payload = buildJsonObject { put("key", key) },
        )

    // --- Skills hub (dashboard parity with hermes-desktop Browse-hub tab) ---

    /**
     * Parallel multi-source hub search. Response carries `results` (name /
     * description / source / identifier / trust_level / repo / tags),
     * `source_counts`, `timed_out`, and `installed` (identifier → lock entry)
     * so already-installed results can be marked. Server caps limit at 50 and
     * fans out with a 30s overall timeout — keep client read timeouts above that.
     */
    suspend fun searchSkillsHub(query: String, limit: Int = 20): Result<JsonObject> =
        getJsonObject("/api/skills/hub/search?q=${queryValue(query)}&limit=${limit.coerceIn(1, 50)}")

    /** SKILL.md + manifest for an identifier WITHOUT installing — read before you trust. */
    suspend fun previewSkillsHub(identifier: String): Result<JsonObject> =
        getJsonObject("/api/skills/hub/preview?identifier=${queryValue(identifier)}")

    /**
     * Configured hub sources + featured skills (`{sources, index_available,
     * featured, installed}`) — content for the browse dialog before the first
     * search. Featured entries share the search-result payload shape.
     */
    suspend fun getSkillsHubSources(): Result<JsonObject> =
        getJsonObject("/api/skills/hub/sources")

    /**
     * Spawns `hermes skills install <identifier>` server-side and returns
     * `{ok, pid}` immediately — the install completes in the background, so
     * callers should message "started" and refresh the skills list later.
     */
    suspend fun installSkillsHub(identifier: String): Result<JsonObject> =
        postJsonObject(
            path = "/api/skills/hub/install",
            payload = buildJsonObject { put("identifier", identifier) },
        )

    /** Async spawn like install; takes the installed skill *name*, not the hub identifier. */
    suspend fun uninstallSkillsHub(name: String): Result<JsonObject> =
        postJsonObject(
            path = "/api/skills/hub/uninstall",
            payload = buildJsonObject { put("name", name) },
        )

    /** Async spawn of `hermes skills update` for all hub-installed skills. */
    suspend fun updateSkillsHub(): Result<JsonObject> =
        postJsonObject("/api/skills/hub/update")

    // --- Profiles (write surface) ---

    /** Full SOUL.md text — upstream returns the complete file, safe for round-trip editing. */
    suspend fun putProfileSoul(name: String, content: String): Result<JsonObject> =
        putJsonObject(
            path = "/api/profiles/${pathSegment(name)}/soul",
            payload = buildJsonObject { put("content", content) },
        )

    suspend fun createProfile(
        name: String,
        cloneFromDefault: Boolean = true,
        description: String? = null,
    ): Result<JsonObject> =
        postJsonObject(
            path = "/api/profiles",
            payload = buildJsonObject {
                put("name", name)
                put("clone_from_default", cloneFromDefault)
                if (!description.isNullOrBlank()) put("description", description)
            },
        )

    suspend fun setProfileDescription(name: String, description: String): Result<JsonObject> =
        putJsonObject(
            path = "/api/profiles/${pathSegment(name)}/description",
            payload = buildJsonObject { put("description", description) },
        )

    suspend fun setProfileModel(
        name: String,
        provider: String,
        model: String,
    ): Result<JsonObject> =
        putJsonObject(
            path = "/api/profiles/${pathSegment(name)}/model",
            payload = buildJsonObject {
                put("provider", provider)
                put("model", model)
            },
        )

    suspend fun toggleSkill(name: String, enabled: Boolean): Result<JsonObject> =
        putJsonObject(
            path = "/api/skills/toggle",
            payload = buildJsonObject {
                put("name", name)
                put("enabled", enabled)
            },
        )

    suspend fun pauseCronJob(jobId: String, profile: String? = null): Result<JsonObject> =
        postJsonObject("/api/cron/jobs/${pathSegment(jobId)}/pause${profileQuery(profile)}")

    suspend fun resumeCronJob(jobId: String, profile: String? = null): Result<JsonObject> =
        postJsonObject("/api/cron/jobs/${pathSegment(jobId)}/resume${profileQuery(profile)}")

    suspend fun triggerCronJob(jobId: String, profile: String? = null): Result<JsonObject> =
        postJsonObject("/api/cron/jobs/${pathSegment(jobId)}/trigger${profileQuery(profile)}")

    suspend fun getCronJobRuns(
        jobId: String,
        profile: String? = null,
        limit: Int = 20,
    ): Result<JsonObject> =
        getJsonObject("/api/cron/jobs/${pathSegment(jobId)}/runs${profileLimitQuery(profile, limit)}")

    suspend fun deleteCronJob(jobId: String, profile: String? = null): Result<JsonObject> =
        deleteJsonObject("/api/cron/jobs/${pathSegment(jobId)}${profileQuery(profile)}")

    suspend fun setMcpServerEnabled(
        name: String,
        enabled: Boolean,
        profile: String? = null,
    ): Result<JsonObject> =
        putJsonObject(
            path = "/api/mcp/servers/${pathSegment(name)}/enabled${profileQuery(profile)}",
            payload = buildJsonObject { put("enabled", enabled) },
        )

    suspend fun testMcpServer(name: String, profile: String? = null): Result<JsonObject> =
        postJsonObject("/api/mcp/servers/${pathSegment(name)}/test${profileQuery(profile)}")

    suspend fun removeMcpServer(name: String, profile: String? = null): Result<JsonObject> =
        deleteJsonObject("/api/mcp/servers/${pathSegment(name)}${profileQuery(profile)}")

    suspend fun startMcpOAuth(
        name: String,
        profile: String? = null,
    ): Result<DashboardMcpOAuthFlow> =
        postJsonObject("/api/mcp/servers/${pathSegment(name)}/auth${profileQuery(profile)}")
            .mapCatching(::parseMcpOAuthFlow)

    suspend fun getMcpOAuthFlow(flowId: String): Result<DashboardMcpOAuthFlow> =
        getJsonObject("/api/mcp/oauth/flows/${pathSegment(flowId)}")
            .mapCatching(::parseMcpOAuthFlow)

    /**
     * Read-only hosted-OAuth capability probe. New dashboards recognize the
     * flow-status route and return its canonical expired-flow 404; older
     * FastAPI routers return the generic route-level 404. No OAuth worker is
     * started and no provider/browser interaction occurs.
     */
    suspend fun supportsHostedMcpOAuth(): Result<Boolean> {
        val result = getJsonObject("/api/mcp/oauth/flows/__relay_capability_probe_never_a_flow__")
        return result.fold(
            onSuccess = { Result.success(true) },
            onFailure = { error ->
                val message = error.message.orEmpty()
                when {
                    message.contains("OAuth flow not found or expired") -> Result.success(true)
                    message.contains("HTTP 404") -> Result.success(false)
                    else -> Result.failure(error)
                }
            },
        )
    }

    suspend fun getCustomEndpoints(): Result<DashboardCustomEndpoints> =
        getJsonObject("/api/providers/custom-endpoints")
            .mapCatching(::parseCustomEndpoints)

    suspend fun saveCustomEndpoint(
        draft: DashboardCustomEndpointDraft,
    ): Result<DashboardCustomEndpoints> =
        postJsonObject(
            "/api/providers/custom-endpoints",
            customEndpointPayload(draft),
        ).mapCatching(::parseCustomEndpoints)

    suspend fun validateCustomEndpoint(
        draft: DashboardCustomEndpointDraft,
    ): Result<DashboardCustomEndpointValidation> =
        postJsonObject(
            "/api/providers/custom-endpoints/validate",
            customEndpointPayload(draft),
        ).mapCatching { root ->
            DashboardCustomEndpointValidation(
                ok = root.booleanField("ok") == true,
                reachable = root.booleanField("reachable") == true,
                message = root.stringField("message").orEmpty(),
                models = root.stringList("models"),
            )
        }

    suspend fun activateCustomEndpoint(
        id: String,
    ): Result<JsonObject> =
        postJsonObject("/api/providers/custom-endpoints/${pathSegment(id)}/activate")

    suspend fun deleteCustomEndpoint(
        id: String,
    ): Result<DashboardCustomEndpoints> =
        deleteJsonObject("/api/providers/custom-endpoints/${pathSegment(id)}")
            .mapCatching(::parseCustomEndpoints)

    suspend fun installMcpCatalogEntry(
        name: String,
        env: Map<String, String> = emptyMap(),
        enable: Boolean = true,
        profile: String? = null,
    ): Result<JsonObject> =
        postJsonObject(
            path = "/api/mcp/catalog/install${profileQuery(profile)}",
            payload = buildJsonObject {
                put("name", name)
                put(
                    "env",
                    buildJsonObject {
                        env.forEach { (key, value) -> put(key, value) }
                    },
                )
                put("enable", enable)
            },
        )

    suspend fun setActiveProfile(name: String): Result<JsonObject> =
        postJsonObject(
            path = "/api/profiles/active",
            payload = buildJsonObject { put("name", name) },
        )

    /**
     * Read the upstream profile split used by app-global remote mode.
     * `active` is the sticky default for new Hermes invocations; `current` is
     * the already-running dashboard/gateway process scope. They can differ.
     */
    suspend fun getActiveProfileScope(): Result<DashboardProfileScope> =
        getJsonObject("/api/profiles/active").mapCatching { root ->
            DashboardProfileScope(
                active = root["active"]?.jsonPrimitive?.contentOrNull
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: "default",
                current = root["current"]?.jsonPrimitive?.contentOrNull
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: "default",
            )
        }

    suspend fun getProfileSoul(name: String): Result<JsonObject> =
        getJsonObject("/api/profiles/${pathSegment(name)}/soul")

    suspend fun deleteProfile(name: String): Result<JsonObject> =
        deleteJsonObject("/api/profiles/${pathSegment(name)}")

    /**
     * List the host's Hermes agent profiles (`GET /api/profiles`) — the same
     * profiles the Manage tab and the official desktop expose — mapped into the
     * shared [Profile] type so the chat agent sheet can offer them even on a
     * dashboard-only (non-relay) connection, where the relay's `auth.ok`
     * profile list is empty. Tolerates the array (`{profiles:[…]}` / `{items:[…]}`)
     * and the object-map (`{profiles:{name:{…}}}`) shapes; an item missing a
     * required field is skipped, not fatal.
     */
    suspend fun listProfiles(): Result<List<Profile>> =
        getJsonObject("/api/profiles").mapCatching { root -> parseProfiles(root) }

    /**
     * List a profile's chat sessions via the dashboard `GET /api/sessions?profile=`.
     *
     * This is the per-profile scoping the official desktop sidebar uses: upstream
     * (`web_server.py` `_open_session_db_for_profile`) opens THAT profile's own
     * `state.db` directly. The gateway `session.list` RPC can't do this — it reads
     * one process-global DB bound to the launch profile, so over a single socket it
     * always returns the launch profile's sessions regardless of the active profile.
     *
     * [profile] null/blank → the launch (default) profile's DB (param omitted). The
     * returned ids are the same stored-session ids the gateway `session.resume`
     * reads, so list-here / resume-on-gateway stays consistent. `min_messages=1`
     * drops empty draft rows where supported; `order=recent` requests activity
     * ordering where the host honors it. Android still sorts by decoded
     * `last_active` locally because older hosts return started-time order.
     */
    suspend fun listSessions(
        profile: String? = null,
        limit: Int = 200,
        archived: String? = null,
    ): Result<List<SessionItem>> =
        withContext(Dispatchers.IO) {
            val query = buildList {
                add("limit=${limit.coerceIn(1, 200)}")
                add("order=recent")
                add("min_messages=1")
                val name = profile?.trim().orEmpty()
                if (name.isNotBlank()) add("profile=${pathSegment(name)}")
                // Upstream `archived` filter: exclude (default) | only | include.
                // Omitted unless requested so older hosts see an unchanged request.
                val archivedMode = archived?.trim().orEmpty()
                if (archivedMode.isNotBlank()) add("archived=${pathSegment(archivedMode)}")
            }.joinToString(prefix = "?", separator = "&")
            getJson("/api/sessions$query").mapCatching { root ->
                val parsed = json.decodeFromJsonElement(SessionListResponse.serializer(), root)
                parsed.sessions ?: parsed.items ?: parsed.data ?: emptyList()
            }
        }

    /**
     * A session's message history, scoped to its owning profile via the dashboard
     * `GET /api/sessions/{id}/messages?profile=`. Required twin of [listSessions]:
     * a non-default profile's sessions live in that profile's own `state.db`, so
     * loading their transcript through the api_server (one shared DB, no profile)
     * returns nothing. [profile] null/blank → the launch profile's DB. Decodes the
     * upstream `{session_id, messages:[…]}` envelope into the shared [MessageItem].
     */
    suspend fun getSessionMessages(
        sessionId: String,
        profile: String? = null,
    ): Result<List<MessageItem>> = withContext(Dispatchers.IO) {
        val name = profile?.trim().orEmpty()
        val query = if (name.isNotBlank()) "?profile=${pathSegment(name)}" else ""
        getJson("/api/sessions/${pathSegment(sessionId)}/messages$query").mapCatching { root ->
            val parsed = json.decodeFromJsonElement(MessageListResponse.serializer(), root)
            parsed.messages ?: parsed.data ?: parsed.items ?: emptyList()
        }
    }

    /**
     * Delete a session scoped to its owning profile via the dashboard
     * `DELETE /api/sessions/{id}?profile=`. The write twin of [listSessions]:
     * a non-default profile's sessions live in that profile's own `state.db`, so
     * deleting through the api_server (one shared DB, no profile) leaves the row
     * intact and the next profile-scoped list resurrects it. [profile] null/blank
     * → the launch profile's DB (param omitted). Mirrors [deleteCronJob]'s
     * profile-scoped delete plumbing.
     */
    suspend fun deleteSession(sessionId: String, profile: String? = null): Result<JsonObject> =
        deleteJsonObject("/api/sessions/${pathSegment(sessionId)}${profileQuery(profile)}")

    /**
     * Export one session as server-owned JSON metadata + messages. This is the
     * safe "archive a copy before cleanup" primitive for clients that want to
     * offer download/share before a destructive delete or prune. Profile scoping
     * matches [deleteSession].
     */
    suspend fun exportSession(sessionId: String, profile: String? = null): Result<JsonObject> =
        getJsonObject("/api/sessions/${pathSegment(sessionId)}/export${profileQuery(profile)}")

    /**
     * Rename a session scoped to a profile via the dashboard
     * `PATCH /api/sessions/{id}` surface — the write twin of [deleteSession].
     * A non-default profile's sessions live in that profile's own `state.db`,
     * so the unscoped api_server rename would patch the wrong DB and the new
     * title would never appear in the profile-scoped list. Current upstream
     * reads `profile` from the PATCH body (`SessionRename`); the query param
     * rides along for builds that scoped by query.
     */
    suspend fun renameSession(sessionId: String, title: String, profile: String? = null): Result<JsonObject> =
        patchJsonObject(
            "/api/sessions/${pathSegment(sessionId)}${profileQuery(profile)}",
            buildJsonObject {
                put("title", title)
                profile?.trim()?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            },
        )

    /**
     * Soft-archive or restore a session via the same dashboard
     * `PATCH /api/sessions/{id}` surface (`{archived: true|false}`). Archived
     * sessions drop out of the default list and are excluded from a prune
     * unless [SessionPruneFilters.includeArchived] is set; list them back with
     * [listSessions] `archived = "only"`. Profile scoping matches
     * [renameSession]: body for current upstream, query for older builds.
     */
    suspend fun setSessionArchived(
        sessionId: String,
        archived: Boolean,
        profile: String? = null,
    ): Result<JsonObject> =
        patchJsonObject(
            "/api/sessions/${pathSegment(sessionId)}${profileQuery(profile)}",
            buildJsonObject {
                put("archived", archived)
                profile?.trim()?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            },
        )

    /**
     * Dry-run a server-backed bulk session cleanup via the dashboard
     * `POST /api/sessions/prune` (`dry_run: true`). Returns what WOULD be
     * deleted — matched count, started-at span, and the candidate rows —
     * without deleting anything. This is the required first step of the
     * prune flow: show the preview, then pass it to [pruneSessions].
     */
    suspend fun previewSessionPrune(filters: SessionPruneFilters): Result<SessionPrunePreview> =
        postJsonObject("/api/sessions/prune", filters.toPrunePayload(dryRun = true))
            .mapCatching { root ->
                json.decodeFromJsonElement(SessionPrunePreview.serializer(), root)
            }

    /**
     * Apply a server-backed bulk session cleanup (`POST /api/sessions/prune`,
     * `dry_run: false`). Destructive — [confirmedPreview] is required so no
     * caller can reach this without first running [previewSessionPrune] with
     * the same [filters] and showing the user its count/span. A preview that
     * matched nothing short-circuits without touching the server: sessions
     * that aged into the filter after the preview are not covered by what the
     * user confirmed.
     */
    suspend fun pruneSessions(
        filters: SessionPruneFilters,
        confirmedPreview: SessionPrunePreview,
    ): Result<SessionPruneResult> {
        if (confirmedPreview.matched <= 0) {
            return Result.success(SessionPruneResult(ok = true, removed = 0))
        }
        return postJsonObject("/api/sessions/prune", filters.toPrunePayload(dryRun = false))
            .mapCatching { root ->
                json.decodeFromJsonElement(SessionPruneResult.serializer(), root)
            }
    }

    private fun SessionPruneFilters.toPrunePayload(dryRun: Boolean): JsonObject =
        buildJsonObject {
            olderThanDays?.let { put("older_than_days", it) }
            source?.trim()?.takeIf { it.isNotBlank() }?.let { put("source", it) }
            profile?.trim()?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            if (includeArchived) put("include_archived", true)
            put("dry_run", dryRun)
        }

    private fun parseProfiles(root: JsonObject): List<Profile> {
        fun decode(element: JsonElement, nameOverride: String?): Profile? = runCatching {
            val obj = element as? JsonObject ?: return null
            // Profile requires name + model; inject the map key as name and an
            // empty model when the server omits them so a sparse row still maps.
            val patched = buildJsonObject {
                obj.forEach { (k, v) -> put(k, v) }
                if (obj["name"] == null && !nameOverride.isNullOrBlank()) put("name", nameOverride)
                if (obj["model"] == null) put("model", "")
            }
            json.decodeFromJsonElement(Profile.serializer(), patched)
        }.getOrNull()

        (root["profiles"] as? JsonArray)?.let { arr -> return arr.mapNotNull { decode(it, null) } }
        (root["items"] as? JsonArray)?.let { arr -> return arr.mapNotNull { decode(it, null) } }
        (root["profiles"] as? JsonObject)?.let { map ->
            return map.entries.mapNotNull { (name, value) -> decode(value, name) }
        }
        return root.entries.mapNotNull { (name, value) -> decode(value, name) }
    }

    suspend fun loginPassword(
        provider: String = "basic",
        username: String,
        password: String,
        next: String = "/",
    ): Result<DashboardLoginResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("provider", provider)
            put("username", username)
            put("password", password)
            put("next", next)
        }
        val httpUrl = resolveUrl("/auth/password-login")
            ?: return@withContext Result.failure(invalidUrlException())
        val request = Request.Builder()
            .url(httpUrl)
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()

        executeJson(request, "Dashboard sign-in").mapCatching { root ->
            DashboardLoginResponse(
                ok = root.booleanField("ok") ?: true,
                next = root.stringField("next"),
                message = root.stringField("message") ?: root.stringField("detail"),
            )
        }
    }

    suspend fun currentSession(): Result<DashboardAuthSession> = withContext(Dispatchers.IO) {
        val httpUrl = resolveUrl("/api/auth/me")
            ?: return@withContext Result.failure(invalidUrlException())
        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .build()

        // try/catch is NOT optional here: currentSession() returns a Result and
        // callers (probeStandardVoice on a viewModelScope/Main coroutine) rely
        // on it NEVER throwing. A raw execute() re-threw transient network
        // failures — e.g. a stale pooled connection over Tailscale aborting
        // ("Software caused connection abort") — straight past withContext(IO)
        // and crashed the app on the main thread. Mirror executeJson()'s
        // contract: every failure becomes Result.failure.
        try {
            okHttpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        Result.success(DashboardAuthSession(authenticated = false))
                    !response.isSuccessful ->
                        Result.failure(apiFailure(response, "Dashboard session"))
                    else ->
                        Result.success(parseAuthSession(response.readJsonObject(json)))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * True when this dashboard build exposes the hermes-desktop voice routes
     * (`/api/audio/transcribe` + `/api/audio/speak`). HEAD on a POST-only
     * FastAPI route returns 405 when the path exists and 404 when it doesn't;
     * an auth-gated 401/403 also proves the route is registered.
     */
    suspend fun audioRoutesPresent(): Boolean = withContext(Dispatchers.IO) {
        // Route exists if HEAD returns anything but a clean 404:
        //  - 405 Method Not Allowed: path registered, POST-only (FastAPI/Starlette)
        //  - 401/403: registered but auth-gated
        //  - 2xx: handled
        // A reverse proxy fronting the dashboard can rewrite a 405 into a 404,
        // which would read as absent. To cut that false-negative, probe BOTH
        // audio routes and treat the surface as present if EITHER answers
        // non-404 (they ship together upstream, so one reachable implies both).
        fun probe(path: String): Boolean {
            val httpUrl = resolveUrl(path) ?: return false
            val request = Request.Builder().url(httpUrl).head().build()
            return try {
                okHttpClient.newCall(request).execute().use { it.code != 404 }
            } catch (_: Exception) {
                false
            }
        }
        probe("/api/audio/transcribe") || probe("/api/audio/speak")
    }

    suspend fun requestWsTicket(): Result<DashboardWsTicket> = withContext(Dispatchers.IO) {
        val httpUrl = resolveUrl("/api/auth/ws-ticket")
            ?: return@withContext Result.failure(invalidUrlException())
        val request = Request.Builder()
            .url(httpUrl)
            .post(ByteArray(0).toRequestBody(null))
            .build()

        executeJson(request, "Dashboard websocket ticket").mapCatching { root ->
            val ticket = root.stringField("ticket")
                ?: root.stringField("ws_ticket")
                ?: throw IOException("Dashboard websocket ticket response missing ticket")
            DashboardWsTicket(
                ticket = ticket,
                ttlSeconds = root.intField("ttl_seconds") ?: root.intField("ttl"),
            )
        }
    }

    fun authLoginUrl(provider: String, next: String = "/"): String =
        authLoginUrl(baseUrl = baseUrl, provider = provider, next = next)

    fun gatewayWebSocketUrl(ticket: String, path: String = "/api/ws"): String? =
        gatewayWebSocketUrl(baseUrl = baseUrl, ticket = ticket, path = path)

    fun shutdown() = shutdownOffMainThread("DashboardApiClient-shutdown") {
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }

    private suspend fun getJson(path: String): Result<JsonObject> = withContext(Dispatchers.IO) {
        val httpUrl = resolveUrl(path) ?: return@withContext Result.failure(invalidUrlException())
        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .build()
        executeJson(request, path)
    }

    private fun executeJson(request: Request, operation: String): Result<JsonObject> {
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(apiFailure(response, operation))
                }
                Result.success(response.readJsonObject(json))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeJsonElement(request: Request, operation: String): Result<JsonElement> {
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(apiFailure(response, operation))
                }
                Result.success(response.readJsonElement(json))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun pathSegment(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun queryValue(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun authLoginUrl(baseUrl: String, provider: String, next: String = "/"): String {
            val root = baseUrl.trim().trimEnd('/')
            return "$root/auth/login?provider=${queryValue(provider)}&next=${queryValue(next)}"
        }

        fun authLandingPath(baseUrl: String): String {
            val httpUrl = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return "/"
            val basePath = httpUrl.encodedPath.trimEnd('/')
            return when {
                basePath.isBlank() || basePath == "/" -> "/"
                else -> "$basePath/"
            }
        }

        fun gatewayWebSocketUrl(baseUrl: String, ticket: String, path: String = "/api/ws"): String? {
            val httpUrl = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
            val websocketPrefix = when (httpUrl.scheme) {
                "https" -> "wss://"
                "http" -> "ws://"
                else -> return null
            }
            val normalizedPath = if (path.startsWith("/")) path else "/$path"
            val basePath = httpUrl.encodedPath.trimEnd('/')
            val encodedPath = when {
                basePath.isBlank() || basePath == "/" -> normalizedPath
                else -> "$basePath$normalizedPath"
            }
            val url = httpUrl.newBuilder()
                .encodedPath(encodedPath)
                .addQueryParameter("ticket", ticket)
                .build()
                .toString()
            return websocketPrefix + url.substringAfter("://")
        }

        private fun profileQuery(profile: String?): String {
            val trimmed = profile?.trim().orEmpty()
            return if (trimmed.isBlank()) "" else "?profile=${pathSegment(trimmed)}"
        }

        private fun profileLimitQuery(profile: String?, limit: Int): String {
            val params = buildList {
                val trimmed = profile?.trim().orEmpty()
                if (trimmed.isNotBlank()) add("profile=${pathSegment(trimmed)}")
                add("limit=${limit.coerceIn(1, 100)}")
            }
            return params.joinToString(prefix = "?", separator = "&")
        }

        private fun parseMcpOAuthFlow(root: JsonObject): DashboardMcpOAuthFlow {
            val flowId = root.stringField("flow_id")
                ?: throw IOException("MCP OAuth response did not include a flow id")
            val status = root.stringField("status")
                ?: throw IOException("MCP OAuth response did not include a status")
            return DashboardMcpOAuthFlow(
                flowId = flowId,
                serverName = root.stringField("server_name").orEmpty(),
                status = status,
                authorizationUrl = root.stringField("authorization_url"),
                error = root.stringField("error"),
            )
        }

        private fun parseCustomEndpoints(root: JsonObject): DashboardCustomEndpoints {
            val current = root["current"] as? JsonObject
            val endpoints = (root["endpoints"] as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.stringField("id") ?: return@mapNotNull null
                DashboardCustomEndpoint(
                    id = id,
                    name = obj.stringField("name") ?: id,
                    baseUrl = obj.stringField("base_url").orEmpty(),
                    model = obj.stringField("model").orEmpty(),
                    models = obj.stringList("models"),
                    contextLength = obj.intField("context_length"),
                    discoverModels = obj.booleanField("discover_models") != false,
                    hasApiKey = obj.booleanField("has_api_key") == true,
                    apiKeyPreview = obj.stringField("api_key_preview"),
                    isCurrent = obj.booleanField("is_current") == true,
                )
            }
            return DashboardCustomEndpoints(
                endpoints = endpoints,
                currentProvider = current?.stringField("provider"),
                currentModel = current?.stringField("model"),
            )
        }

        private fun customEndpointPayload(draft: DashboardCustomEndpointDraft): JsonObject =
            buildJsonObject {
                draft.id?.takeIf { it.isNotBlank() }?.let { put("id", it) }
                put("name", draft.name)
                put("base_url", draft.baseUrl)
                put("model", draft.model)
                draft.apiKey?.takeIf { it.isNotBlank() }?.let { put("api_key", it) }
                draft.contextLength?.takeIf { it > 0 }?.let { put("context_length", it) }
                put("discover_models", draft.discoverModels)
                put("make_default", draft.makeDefault)
            }

        fun defaultClient(
            cookieStore: DashboardCookieStore = InMemoryDashboardCookieStore(),
        ): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(DashboardCookieJar(cookieStore))
            .connectTimeout(10, TimeUnit.SECONDS)
            // Skills-hub search fans out server-side with a 30s overall
            // timeout; keep the read window above it so a slow-but-successful
            // search doesn't die client-side at the edge.
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun parseStatus(root: JsonObject): DashboardStatus {
            val authObject = root["auth"] as? JsonObject
            val providersElement = root["auth_providers"]
                ?: root["providers"]
                ?: authObject?.get("providers")
            val providers = parseProviders(providersElement)
            val profiles = (root["profiles"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
            }
            val gateways = (root["gateways"] as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val profile = obj.stringField("profile") ?: return@mapNotNull null
                val ports = (obj["ports"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
                    (value as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let { name to it }
                }.toMap()
                val served = (obj["served_profiles"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull
                }
                DashboardGatewayTopology(profile = profile, ports = ports, servedProfiles = served)
            }
            return DashboardStatus(
                authRequired = root.booleanField("auth_required")
                    ?: authObject.booleanField("required")
                    ?: false,
                authProviders = providers.map { it.name },
                authProviderDetails = providers,
                version = root.stringField("version"),
                message = root.stringField("message") ?: root.stringField("detail"),
                nousSessionValid = root.stringField("nous_session_valid"),
                profiles = profiles,
                gatewayMode = root.stringField("gateway_mode"),
                gateways = gateways,
            )
        }

        fun parseAuthSession(root: JsonObject): DashboardAuthSession {
            val user = root["user"] as? JsonObject
            val session = root["session"] as? JsonObject
            val explicitAuthenticated = root.booleanField("authenticated")
                ?: root.booleanField("ok")
            val flatIdentityPresent =
                root.stringField("user_id") != null ||
                    root.stringField("email") != null ||
                    root.stringField("display_name") != null ||
                    root.stringField("provider") != null ||
                    root["expires_at"] != null
            val authenticated = explicitAuthenticated
                ?: (user != null || session != null || flatIdentityPresent)
            return DashboardAuthSession(
                authenticated = authenticated,
                username = root.stringField("username")
                    ?: root.stringField("display_name")
                    ?: root.stringField("email")
                    ?: root.stringField("user_id")
                    ?: user.stringField("username")
                    ?: user.stringField("name")
                    ?: session.stringField("username"),
                provider = root.stringField("provider")
                    ?: session.stringField("provider")
                    ?: user.stringField("provider"),
            )
        }

        fun parseProviders(element: JsonElement?): List<DashboardAuthProvider> {
            return when (element) {
                is JsonArray -> element.mapNotNull { provider(it) }
                is JsonObject -> element.entries.mapNotNull { (key, value) ->
                    val name = key.trim().takeIf { it.isNotBlank() }
                    if (name != null && value is JsonObject) {
                        provider(name, value)
                    } else {
                        provider(value) ?: name?.let {
                            DashboardAuthProvider(name = it, supportsPassword = isPasswordProvider(it))
                        }
                    }
                }
                else -> emptyList()
            }.distinctBy { it.name }
        }

        private fun provider(element: JsonElement?): DashboardAuthProvider? {
            return when (element) {
                is JsonPrimitive -> element.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { DashboardAuthProvider(name = it, supportsPassword = isPasswordProvider(it)) }
                is JsonObject -> {
                    val name = element.stringField("id")
                        ?: element.stringField("name")
                        ?: element.stringField("provider")
                        ?: element.stringField("type")
                    name?.let { provider(it, element) }
                }
                else -> null
            }
        }

        private fun provider(name: String, element: JsonObject): DashboardAuthProvider =
            DashboardAuthProvider(
                name = name,
                displayName = element.stringField("display_name")
                    ?: element.stringField("label")
                    ?: element.stringField("title"),
                supportsPassword = element.booleanField("supports_password")
                    ?: isPasswordProvider(name),
            )

        private fun isPasswordProvider(name: String): Boolean =
            name.equals("basic", ignoreCase = true) ||
                name.equals("password", ignoreCase = true)

        fun parseElevenLabsVoices(root: JsonObject): ElevenLabsVoices {
            val available = root.booleanField("available") ?: false
            val voices = (root["voices"] as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val voiceId = obj.stringField("voice_id") ?: return@mapNotNull null
                ElevenLabsVoice(
                    voiceId = voiceId,
                    name = obj.stringField("name") ?: voiceId,
                    label = obj.stringField("label") ?: obj.stringField("name") ?: voiceId,
                )
            }
            return ElevenLabsVoices(available = available, voices = voices)
        }

        fun parseChatDisplaySettings(root: JsonObject): DashboardChatDisplaySettings {
            val config = root["config"] as? JsonObject
            val display = (config?.get("display") as? JsonObject)
                ?: (root["display"] as? JsonObject)
            return DashboardChatDisplaySettings(
                showReasoning = display.booleanField("show_reasoning"),
                toolDisplay = normalizeToolDisplay(
                    display.stringField("tool_progress")
                        ?: display.stringField("tool_display")
                        ?: display.stringField("toolProgress"),
                ),
            )
        }

        private fun normalizeToolDisplay(value: String?): String? =
            when (value?.trim()?.lowercase()) {
                "off", "none", "false", "0", "hidden", "hide" -> "off"
                "compact", "minimal", "summary", "brief" -> "compact"
                "all", "detailed", "detail", "full", "true", "1", "on", "show" -> "detailed"
                else -> null
            }
    }
}

interface DashboardCookieStore {
    fun load(): List<StoredDashboardCookie>
    fun save(cookies: List<StoredDashboardCookie>)
    fun clear()
}

class InMemoryDashboardCookieStore : DashboardCookieStore {
    private val lock = Any()
    private var cookies: List<StoredDashboardCookie> = emptyList()

    override fun load(): List<StoredDashboardCookie> = synchronized(lock) { cookies }

    override fun save(cookies: List<StoredDashboardCookie>) {
        synchronized(lock) {
            this.cookies = cookies
        }
    }

    override fun clear() {
        synchronized(lock) {
            cookies = emptyList()
        }
    }
}

class EncryptedDashboardCookieStore(
    context: Context,
    connectionId: String,
    /**
     * The connection's TOKEN-store file key. When non-null the dashboard cookies
     * ride that already-built keyset (so there is NO second keyset build on cold
     * start), and any cookies in this connection's old stand-alone
     * `hermes_dashboard_<id>` file are migrated across once. Null preserves the
     * original stand-alone-file behavior for callers that can't resolve the key.
     */
    tokenStoreKey: String? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DashboardCookieStore {
    private val serializer = ListSerializer(StoredDashboardCookie.serializer())
    private val appContext = context.applicationContext
    private val standaloneCookiePrefsName = prefsName(connectionId)
    // Unify onto the connection's token file when we know it; else stand alone.
    // (Explicit type + distinct name avoids a type-inference cycle with the
    // companion `prefsName(connectionId)` function above.)
    private val storePrefsName: String = tokenStoreKey ?: standaloneCookiePrefsName
    private val unified = tokenStoreKey != null && tokenStoreKey != standaloneCookiePrefsName

    // DEFERRED on purpose. Building the Keystore-backed prefs takes 1-4s on
    // StrongBox devices and serializes through a process-GLOBAL Tink lock
    // (AndroidKeysetManager.Builder.build) — eager construction here froze the
    // main thread for ~11s at app start (frozen-sphere incident, 2026-06-11).
    // Construction is free on any thread; the expensive build happens on the
    // first actual cookie access, always an OkHttp/IO thread. Going through
    // SecureStoreCache means that build is SHARED with the connection's token
    // store — so when unified there is NO second keyset build at all.
    private val store: SessionTokenStore by lazy {
        val s = SecureStoreCache.getOrBuild(storePrefsName) {
            buildRawTokenStore(appContext, storePrefsName)
        }
        if (unified) migrateCookiesFromStandaloneFile(s)
        s
    }

    /**
     * One-shot copy of this connection's cookies from the old stand-alone
     * `hermes_dashboard_<id>` file into the unified token file, marker-gated so
     * the old file's keyset is built at most once ever. On failure (corrupt old
     * file) the user simply re-signs-in to Manage — cookies are re-obtainable,
     * unlike the relay session token.
     */
    private fun migrateCookiesFromStandaloneFile(target: SessionTokenStore) {
        if (target.contains(KEY_COOKIES_MIGRATED)) return
        runCatching {
            val old = buildRawTokenStore(appContext, standaloneCookiePrefsName)
            old.getString(KEY_COOKIES)?.let { target.putString(KEY_COOKIES, it) }
            old.clearAll()
        }
        target.putString(KEY_COOKIES_MIGRATED, "1")
    }

    override fun load(): List<StoredDashboardCookie> {
        val raw = store.getString(KEY_COOKIES) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrElse { emptyList() }
    }

    override fun save(cookies: List<StoredDashboardCookie>) {
        store.putString(KEY_COOKIES, json.encodeToString(serializer, cookies))
    }

    override fun clear() {
        store.remove(KEY_COOKIES)
    }

    companion object {
        private const val KEY_COOKIES = "dashboard_cookies_json"
        private const val KEY_COOKIES_MIGRATED = "dashboard_cookies_migrated"

        fun prefsName(connectionId: String): String =
            "hermes_dashboard_${connectionId.take(8)}"
    }
}

class DashboardCookieJar(
    private val store: DashboardCookieStore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = clockMillis()
        val incoming = cookies.map { StoredDashboardCookie.fromCookie(it) }
            .filterNot { it.isExpired(now) }
        val retained = store.load()
            .filterNot { it.isExpired(now) }
            .filterNot { old -> incoming.any { it.key == old.key } }
        store.save(retained + incoming)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = clockMillis()
        // Load once (each load() is a decrypt + JSON decode); prune expired
        // entries back to disk only when something actually expired.
        val all = store.load()
        val stored = all.filterNot { it.isExpired(now) }
        if (stored.size != all.size) {
            store.save(stored)
        }
        return stored.mapNotNull { it.toCookie() }
            .filter { it.matches(url) }
    }
}

/**
 * Cookie jar that resolves the backing per-connection store at request time.
 *
 * Long-lived OkHttpClients (e.g. the standard voice client, remembered once
 * per process in RelayApp) can't bind a fixed [DashboardCookieStore] because
 * the active Connection — and therefore the encrypted cookie file — changes
 * when the user switches connections. A null store (no active connection)
 * degrades to an empty jar rather than failing the request.
 */
class DynamicDashboardCookieJar(
    private val storeProvider: () -> DashboardCookieStore?,
) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val store = storeProvider() ?: return
        DashboardCookieJar(store).saveFromResponse(url, cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val store = storeProvider() ?: return emptyList()
        return DashboardCookieJar(store).loadForRequest(url)
    }
}

fun importDashboardCookieHeader(
    store: DashboardCookieStore,
    url: String,
    cookieHeader: String?,
    clockMillis: () -> Long = { System.currentTimeMillis() },
): Int {
    val httpUrl = url.toHttpUrlOrNull() ?: return 0
    val raw = cookieHeader?.trim().orEmpty()
    if (raw.isBlank()) return 0

    val now = clockMillis()
    // CookieManager.getCookie(url) returns only "name=value" pairs; it does
    // not expose the original Set-Cookie Path attribute. Store imported
    // WebView auth cookies at root so a cookie observed on /auth/callback is
    // still sent to /api/auth/me during native session verification.
    val cookiePath = "/"
    val imported = raw.split(";")
        .mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val name = part.substring(0, index).trim()
            val value = part.substring(index + 1).trim()
            if (name.isBlank()) return@mapNotNull null
            StoredDashboardCookie(
                name = name,
                value = value,
                expiresAt = Long.MAX_VALUE,
                domain = httpUrl.host,
                path = cookiePath,
                secure = httpUrl.isHttps,
                httpOnly = true,
                hostOnly = true,
                persistent = false,
            )
        }
        .filterNot { it.isExpired(now) }
    if (imported.isEmpty()) return 0

    val retained = store.load()
        .filterNot { it.isExpired(now) }
        .filterNot { old -> imported.any { it.key == old.key } }
    store.save(retained + imported)
    return imported.size
}

@Serializable
data class StoredDashboardCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
    val persistent: Boolean,
) {
    val key: String
        get() = "${name.lowercase()}|${domain.lowercase()}|$path"

    fun isExpired(nowMillis: Long): Boolean =
        persistent && expiresAt <= nowMillis

    fun toCookie(): Cookie? {
        return runCatching {
            val builder = Cookie.Builder()
                .name(name)
                .value(value)
                .path(path)
            if (hostOnly) {
                builder.hostOnlyDomain(domain)
            } else {
                builder.domain(domain)
            }
            if (persistent) {
                builder.expiresAt(expiresAt)
            }
            if (secure) builder.secure()
            if (httpOnly) builder.httpOnly()
            builder.build()
        }.getOrNull()
    }

    companion object {
        fun fromCookie(cookie: Cookie): StoredDashboardCookie =
            StoredDashboardCookie(
                name = cookie.name,
                value = cookie.value,
                expiresAt = cookie.expiresAt,
                domain = cookie.domain,
                path = cookie.path,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
                hostOnly = cookie.hostOnly,
                persistent = cookie.persistent,
            )
    }
}

private fun Response.readJsonObject(json: Json): JsonObject {
    val raw = body.string()
    if (raw.isBlank()) return JsonObject(emptyMap())
    return json.parseToJsonElement(raw).jsonObject
}

private fun Response.readJsonElement(json: Json): JsonElement {
    val raw = body.string()
    if (raw.isBlank()) return JsonObject(emptyMap())
    return json.parseToJsonElement(raw)
}

private fun apiFailure(response: Response, operation: String): IOException {
    val bodyDetail = runCatching { response.body.string() }.getOrDefault("")
    val detail = bodyDetail.take(240).ifBlank { response.message }
    return IOException("$operation failed - HTTP ${response.code}: $detail")
}

private fun JsonObject?.stringField(name: String): String? =
    ((this?.get(name) as? JsonPrimitive)?.contentOrNull)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun JsonObject?.booleanField(name: String): Boolean? =
    (this?.get(name) as? JsonPrimitive)?.booleanOrNull

private fun JsonObject?.intField(name: String): Int? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun JsonObject?.stringList(name: String): List<String> =
    (this?.get(name) as? JsonArray).orEmpty().mapNotNull { element ->
        (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    }
