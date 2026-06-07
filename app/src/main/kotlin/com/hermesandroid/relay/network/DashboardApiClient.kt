package com.hermesandroid.relay.network

import android.content.Context
import com.hermesandroid.relay.auth.KeystoreTokenStore
import com.hermesandroid.relay.auth.LegacyEncryptedPrefsTokenStore
import com.hermesandroid.relay.auth.SessionTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class DashboardStatus(
    val authRequired: Boolean,
    val authProviders: List<String> = emptyList(),
    val version: String? = null,
    val message: String? = null,
)

data class DashboardLoginResponse(
    val ok: Boolean,
    val next: String? = null,
    val message: String? = null,
)

data class DashboardAuthSession(
    val authenticated: Boolean,
    val username: String? = null,
    val provider: String? = null,
)

data class DashboardWsTicket(
    val ticket: String,
    val ttlSeconds: Int? = null,
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

    suspend fun getStatus(): Result<DashboardStatus> = withContext(Dispatchers.IO) {
        getJson("/api/status").mapCatching { parseStatus(it) }
    }

    suspend fun getJsonObject(path: String): Result<JsonObject> = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        getJson(normalized)
    }

    suspend fun postJsonObject(
        path: String,
        payload: JsonObject = JsonObject(emptyMap()),
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val request = Request.Builder()
            .url("$baseUrl$normalized")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()
        executeJson(request, normalized)
    }

    suspend fun putJsonObject(
        path: String,
        payload: JsonObject,
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val request = Request.Builder()
            .url("$baseUrl$normalized")
            .put(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()
        executeJson(request, normalized)
    }

    suspend fun deleteJsonObject(path: String): Result<JsonObject> = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val request = Request.Builder()
            .url("$baseUrl$normalized")
            .delete()
            .build()
        executeJson(request, normalized)
    }

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

    suspend fun setMcpServerEnabled(name: String, enabled: Boolean): Result<JsonObject> =
        putJsonObject(
            path = "/api/mcp/servers/${pathSegment(name)}/enabled",
            payload = buildJsonObject { put("enabled", enabled) },
        )

    suspend fun testMcpServer(name: String): Result<JsonObject> =
        postJsonObject("/api/mcp/servers/${pathSegment(name)}/test")

    suspend fun removeMcpServer(name: String): Result<JsonObject> =
        deleteJsonObject("/api/mcp/servers/${pathSegment(name)}")

    suspend fun installMcpCatalogEntry(
        name: String,
        env: Map<String, String> = emptyMap(),
        enable: Boolean = true,
    ): Result<JsonObject> =
        postJsonObject(
            path = "/api/mcp/catalog/install",
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

    suspend fun getProfileSoul(name: String): Result<JsonObject> =
        getJsonObject("/api/profiles/${pathSegment(name)}/soul")

    suspend fun deleteProfile(name: String): Result<JsonObject> =
        deleteJsonObject("/api/profiles/${pathSegment(name)}")

    suspend fun loginPassword(
        provider: String = "password",
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
        val request = Request.Builder()
            .url("$baseUrl/auth/password-login")
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
        val request = Request.Builder()
            .url("$baseUrl/api/auth/me")
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                return@withContext Result.success(DashboardAuthSession(authenticated = false))
            }
            if (!response.isSuccessful) {
                return@withContext Result.failure(apiFailure(response, "Dashboard session"))
            }
            val root = response.readJsonObject(json)
            Result.success(parseAuthSession(root))
        }
    }

    suspend fun requestWsTicket(): Result<DashboardWsTicket> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/auth/ws-ticket")
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

    fun shutdown() {
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }

    private suspend fun getJson(path: String): Result<JsonObject> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
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

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun pathSegment(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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

        fun defaultClient(
            cookieStore: DashboardCookieStore = InMemoryDashboardCookieStore(),
        ): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(DashboardCookieJar(cookieStore))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun parseStatus(root: JsonObject): DashboardStatus {
            val authObject = root["auth"] as? JsonObject
            val providersElement = root["auth_providers"]
                ?: root["providers"]
                ?: authObject?.get("providers")
            return DashboardStatus(
                authRequired = root.booleanField("auth_required")
                    ?: authObject.booleanField("required")
                    ?: false,
                authProviders = parseProviderIds(providersElement),
                version = root.stringField("version"),
                message = root.stringField("message") ?: root.stringField("detail"),
            )
        }

        fun parseAuthSession(root: JsonObject): DashboardAuthSession {
            val user = root["user"] as? JsonObject
            val session = root["session"] as? JsonObject
            val authenticated = root.booleanField("authenticated")
                ?: root.booleanField("ok")
                ?: (user != null || session != null)
            return DashboardAuthSession(
                authenticated = authenticated,
                username = root.stringField("username")
                    ?: user.stringField("username")
                    ?: user.stringField("name")
                    ?: session.stringField("username"),
                provider = root.stringField("provider")
                    ?: session.stringField("provider")
                    ?: user.stringField("provider"),
            )
        }

        private fun parseProviderIds(element: JsonElement?): List<String> {
            return when (element) {
                is JsonArray -> element.mapNotNull { providerId(it) }
                is JsonObject -> element.entries.mapNotNull { (key, value) ->
                    providerId(value) ?: key.takeIf { it.isNotBlank() }
                }
                else -> emptyList()
            }.distinct()
        }

        private fun providerId(element: JsonElement?): String? {
            return when (element) {
                is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                is JsonObject -> element.stringField("id")
                    ?: element.stringField("name")
                    ?: element.stringField("type")
                    ?: element.stringField("provider")
                else -> null
            }
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
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DashboardCookieStore {
    private val serializer = ListSerializer(StoredDashboardCookie.serializer())
    private val store: SessionTokenStore =
        KeystoreTokenStore.tryCreate(context.applicationContext, prefsName(connectionId))
            ?: LegacyEncryptedPrefsTokenStore(context.applicationContext, prefsName(connectionId))

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
        val stored = store.load().filterNot { it.isExpired(now) }
        if (stored.size != store.load().size) {
            store.save(stored)
        }
        return stored.mapNotNull { it.toCookie() }
            .filter { it.matches(url) }
    }
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
