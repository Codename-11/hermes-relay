package com.hermesandroid.relay.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.auth.ConnectionAuthSecrets
import com.hermesandroid.relay.network.upstream.EncryptedDashboardCookieStore
import com.hermesandroid.relay.network.upstream.StoredDashboardCookie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Manages app data: backup, restore, and reset.
 *
 * Backup format is a JSON file containing full connection metadata and
 * credentials. Treat exported files as sensitive secrets.
 */
class DataManager(
    private val context: Context,
    /**
     * Multi-connection: the [ConnectionStore] singleton whose snapshot gets
     * written into [AppBackup.connections] on export. Nullable for
     * legacy/compat call sites that construct a [DataManager] without
     * connection support; a null store just means "export an empty
     * connections list" (equivalent to v2 behavior).
     */
    private val connectionStore: ConnectionStore? = null,
) {

    companion object {
        private const val TAG = "DataManager"
        private const val AUTH_PREFS_NAME = "hermes_companion_auth"

        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Backup data model.
     *
     * **Schema history:**
     *  - v1: `serverUrl` only (single endpoint, pre-API-split).
     *  - v2: adds `apiServerUrl` + `relayUrl`; `profiles: List<String>` held
     *    server-issued session labels from `auth.ok` (never actually populated
     *    on export — the field was vestigial).
     *  - v3 (2026-04-18): `profiles: List<Profile>` — carried the new
     *    multi-connection definitions under the then-current "Profile" name.
     *  - v4 (2026-04-18): `connections: List<Connection>` — same shape as v3
     *    under the renamed concept. v3 imports get their `profiles` field
     *    re-mapped to `connections` (see [importSettings]). v1/v2 imports
     *    get `connections = emptyList()` since the old string list was not
     *    structurally compatible.
     *  - v5 (2026-06-08): full connection backups. Adds active connection id
     *    and `connectionSecrets`, including API keys, relay tokens, device id,
     *    paired metadata, and dashboard cookies.
     */
    @Serializable
    data class AppBackup(
        val version: Int = 5,
        val serverUrl: String? = null, // legacy (v1 compat)
        val apiServerUrl: String? = null,
        val relayUrl: String? = null,
        val theme: String = "auto",
        val onboardingCompleted: Boolean = false,
        val connections: List<Connection> = emptyList(),
        val activeConnectionId: String? = null,
        val containsSensitiveData: Boolean = true,
        val connectionSecrets: List<ConnectionSecretBackup> = emptyList(),
        val exportedAt: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class ConnectionSecretBackup(
        val connectionId: String,
        val tokenStoreKey: String,
        val auth: ConnectionAuthSecrets = ConnectionAuthSecrets(),
        val dashboardCookies: List<DashboardCookieBackup> = emptyList(),
    )

    @Serializable
    data class DashboardCookieBackup(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
        val persistent: Boolean,
    )

    /**
     * Export app settings to a JSON string.
     * Includes connection credentials. The export UI must warn the user that
     * the resulting JSON file is sensitive.
     *
     * The `sessionLabels` parameter is a legacy dead parameter — it was
     * previously sourced from `AuthManager.sessionLabels`, a field removed
     * in Pass 2 of the multi-connection rollout (2026-04-18) when it was
     * replaced by the structured `agentProfiles: StateFlow<List<Profile>>`.
     * Kept only for call-site signature stability; not written to the
     * backup. The backup's [AppBackup.connections] comes from the
     * injected [connectionStore] snapshot. Callers should pass
     * `emptyList()`. Will be removed in a later pass.
     */
    suspend fun exportSettings(
        serverUrl: String?,
        theme: String,
        onboardingCompleted: Boolean,
        @Suppress("UNUSED_PARAMETER") sessionLabels: List<String>,
        apiServerUrl: String? = null,
        relayUrl: String? = null
    ): String {
        val connectionsSnapshot = connectionStore?.connections?.value.orEmpty()
        if (connectionStore == null) {
            Log.w(
                TAG,
                "exportSettings: no ConnectionStore wired — writing empty connections list " +
                    "(caller constructed DataManager without the multi-connection ctor arg)",
            )
        }
        val connectionSecrets = connectionsSnapshot.map { connection ->
            ConnectionSecretBackup(
                connectionId = connection.id,
                tokenStoreKey = connection.tokenStoreKey,
                auth = AuthManager.exportStoredSecrets(context, connection.tokenStoreKey),
                dashboardCookies = EncryptedDashboardCookieStore(
                    context = context,
                    connectionId = connection.id,
                ).load().map { it.toBackup() },
            )
        }
        val backup = AppBackup(
            version = 5,
            serverUrl = serverUrl, // legacy compat
            apiServerUrl = apiServerUrl,
            relayUrl = relayUrl,
            theme = theme,
            onboardingCompleted = onboardingCompleted,
            connections = connectionsSnapshot,
            activeConnectionId = connectionStore?.activeConnectionId?.value,
            containsSensitiveData = true,
            connectionSecrets = connectionSecrets,
            exportedAt = System.currentTimeMillis(),
        )
        return json.encodeToString(backup)
    }

    suspend fun restoreConnectionBackup(backup: AppBackup) {
        val store = connectionStore ?: return
        deleteSensitivePreferenceFiles()
        store.replaceConnections(
            connections = backup.connections,
            activeConnectionId = backup.activeConnectionId,
        )

        val connectionsById = backup.connections.associateBy { it.id }
        backup.connectionSecrets.forEach { secret ->
            val connection = connectionsById[secret.connectionId] ?: return@forEach
            AuthManager.importStoredSecrets(
                context = context,
                tokenStoreKey = connection.tokenStoreKey,
                secrets = secret.auth,
            )
            EncryptedDashboardCookieStore(
                context = context,
                connectionId = connection.id,
            ).save(secret.dashboardCookies.map { it.toStoredCookie() })
        }
    }

    /**
     * Import settings from a JSON string.
     * Returns the parsed backup, or null if invalid.
     *
     * For v1/v2 backups we deliberately drop the old `profiles: List<String>`
     * field on its own, since kotlinx.serialization's `ignoreUnknownKeys`
     * would throw if it found the old scalar-string entries where it now
     * expects [Connection] objects. We pre-parse as a [JsonElement] and
     * rebuild the object with `connections = []` on older schema versions.
     *
     * For v3 backups, the old `profiles: List<Profile>` field is re-mapped
     * to `connections: List<Connection>` — same wire shape, just renamed.
     */
    fun importSettings(jsonString: String): AppBackup? {
        return try {
            val element = json.parseToJsonElement(jsonString)
            val obj = element as? JsonObject ?: return null
            val version = obj["version"]?.let {
                (it as? JsonPrimitive)?.content?.toIntOrNull()
            } ?: 4
            val normalized = when {
                version < 3 -> {
                    // Strip the incompatible v1/v2 `profiles` field so the
                    // serializer doesn't try to decode List<String> into
                    // List<Connection>. The feature never populated the list
                    // in export anyway, so no user data is lost.
                    Log.d(
                        TAG,
                        "importSettings: dropping legacy v$version profiles field " +
                            "(schema was vestigial)",
                    )
                    JsonObject(obj - "profiles")
                }
                version == 3 -> {
                    // v3 used `profiles: List<Profile>` with the same wire
                    // shape as v4's `connections: List<Connection>`. Swap
                    // the key name and decode as v4.
                    val profilesField = obj["profiles"]
                    val withoutProfiles = obj - "profiles"
                    if (profilesField != null) {
                        JsonObject(withoutProfiles + ("connections" to profilesField))
                    } else {
                        JsonObject(withoutProfiles)
                    }
                }
                else -> obj
            }
            json.decodeFromJsonElement(AppBackup.serializer(), normalized)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse backup JSON", e)
            null
        }
    }

    /**
     * Write backup JSON to a URI (e.g., from SAF file picker).
     */
    suspend fun writeBackupToUri(uri: Uri, backup: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(backup.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write backup to URI: $uri", e)
                false
            }
        }
    }

    /**
     * Read backup JSON from a URI (e.g., from SAF file picker).
     */
    suspend fun readBackupFromUri(uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader(Charsets.UTF_8).readText()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read backup from URI: $uri", e)
                null
            }
        }
    }

    /**
     * Reset all app data:
     * - Clear DataStore preferences
     * - Clear EncryptedSharedPreferences (auth tokens)
     * - Clear any cached data
     * Does NOT clear the onboarding flag (that's separate via [resetOnboarding]).
     */
    suspend fun resetAppData() {
        try {
            // Preserve onboarding state before clearing
            val onboarding = isOnboardingCompleted()

            // Multi-connection reset: clear the hot ConnectionStore state and
            // delete every per-connection token store before the global
            // DataStore is wiped.
            connectionStore?.clearAllConnections()

            // Clear all DataStore preferences
            context.relayDataStore.edit { it.clear() }

            // Restore onboarding flag so it isn't wiped
            if (onboarding) {
                context.relayDataStore.edit { preferences ->
                    preferences[KEY_ONBOARDING_COMPLETED] = true
                }
            }

            deleteSensitivePreferenceFiles()

            // Clear cache directory
            withContext(Dispatchers.IO) {
                context.cacheDir.listFiles()?.forEach { file ->
                    file.deleteRecursively()
                }
            }

            Log.d(TAG, "App data reset complete")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset app data", e)
        }
    }

    private suspend fun deleteSensitivePreferenceFiles() {
        withContext(Dispatchers.IO) {
            val prefsDir = File(context.filesDir.parent, "shared_prefs")
            val stores = buildSet {
                add(AUTH_PREFS_NAME)
                add(Connection.LEGACY_TOKEN_STORE_KEY)
                prefsDir.listFiles()?.forEach { file ->
                    if (file.extension == "xml") {
                        val name = file.nameWithoutExtension
                        if (
                            name.startsWith("hermes_auth_") ||
                            name.startsWith("hermes_dashboard_")
                        ) {
                            add(name)
                        }
                    }
                }
            }
            stores.forEach { storeName ->
                try {
                    context.deleteSharedPreferences(storeName)
                    Log.d(TAG, "Deleted auth preferences file: $storeName")
                } catch (e: Exception) {
                    Log.w(TAG, "deleteSharedPreferences($storeName) failed: ${e.message}")
                }
            }
        }
    }

    private fun StoredDashboardCookie.toBackup(): DashboardCookieBackup =
        DashboardCookieBackup(
            name = name,
            value = value,
            expiresAt = expiresAt,
            domain = domain,
            path = path,
            secure = secure,
            httpOnly = httpOnly,
            hostOnly = hostOnly,
            persistent = persistent,
        )

    private fun DashboardCookieBackup.toStoredCookie(): StoredDashboardCookie =
        StoredDashboardCookie(
            name = name,
            value = value,
            expiresAt = expiresAt,
            domain = domain,
            path = path,
            secure = secure,
            httpOnly = httpOnly,
            hostOnly = hostOnly,
            persistent = persistent,
        )

    /**
     * Reset only the onboarding completion flag.
     * Next app launch will show onboarding again.
     */
    suspend fun resetOnboarding() {
        try {
            context.relayDataStore.edit { preferences ->
                preferences.remove(KEY_ONBOARDING_COMPLETED)
            }
            Log.d(TAG, "Onboarding flag reset")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset onboarding flag", e)
        }
    }

    /**
     * Check if onboarding has been completed.
     */
    suspend fun isOnboardingCompleted(): Boolean {
        return try {
            context.relayDataStore.data
                .map { preferences -> preferences[KEY_ONBOARDING_COMPLETED] ?: false }
                .first()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read onboarding state", e)
            false
        }
    }

    /**
     * Mark onboarding as completed.
     */
    suspend fun setOnboardingCompleted(completed: Boolean) {
        try {
            context.relayDataStore.edit { preferences ->
                preferences[KEY_ONBOARDING_COMPLETED] = completed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set onboarding completed", e)
        }
    }
}
