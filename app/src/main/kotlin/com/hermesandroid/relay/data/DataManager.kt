package com.hermesandroid.relay.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * Backup format is a JSON file containing settings and connection info.
 * Tokens are NOT included in backups for security.
 */
class DataManager(
    private val context: Context,
    /**
     * Multi-profile: the [ProfileStore] singleton whose snapshot gets
     * written into [AppBackup.profiles] on export. Nullable for
     * legacy/compat call sites that construct a [DataManager] without
     * profile support; a null store just means "export an empty profiles
     * list" (equivalent to v2 behavior).
     */
    private val profileStore: ProfileStore? = null,
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
     * Backup data model -- only non-sensitive settings.
     * Tokens and device IDs are never included.
     *
     * **Schema history:**
     *  - v1: `serverUrl` only (single endpoint, pre-API-split).
     *  - v2: adds `apiServerUrl` + `relayUrl`; `profiles: List<String>` held
     *    server-issued session labels from `auth.ok` (never actually populated
     *    on export — the field was vestigial).
     *  - v3 (2026-04-18): `profiles: List<Profile>` — carries the new
     *    multi-profile connection definitions. v1/v2 imports get
     *    `profiles = emptyList()` since the old string list was not
     *    structurally compatible.
     */
    @Serializable
    data class AppBackup(
        val version: Int = 3,
        val serverUrl: String? = null, // legacy (v1 compat)
        val apiServerUrl: String? = null,
        val relayUrl: String? = null,
        val theme: String = "auto",
        val onboardingCompleted: Boolean = false,
        val profiles: List<Profile> = emptyList(),
        val exportedAt: Long = System.currentTimeMillis()
    )

    /**
     * Export app settings to a JSON string.
     * Does NOT include session tokens or device IDs (security).
     *
     * The `profiles` parameter is the legacy session-labels list sourced from
     * [com.hermesandroid.relay.auth.AuthManager.sessionLabels] and is kept
     * only for call-site signature stability — it is not written to the
     * backup. The backup's [AppBackup.profiles] comes from the injected
     * [profileStore] snapshot (the new multi-profile connection definitions).
     */
    suspend fun exportSettings(
        serverUrl: String?,
        theme: String,
        onboardingCompleted: Boolean,
        @Suppress("UNUSED_PARAMETER") profiles: List<String>,
        apiServerUrl: String? = null,
        relayUrl: String? = null
    ): String {
        val profileSnapshot = profileStore?.profiles?.value
        if (profileStore == null) {
            Log.w(
                TAG,
                "exportSettings: no ProfileStore wired — writing empty profiles list " +
                    "(caller constructed DataManager without the multi-profile ctor arg)",
            )
        }
        val backup = AppBackup(
            version = 3,
            serverUrl = serverUrl, // legacy compat
            apiServerUrl = apiServerUrl,
            relayUrl = relayUrl,
            theme = theme,
            onboardingCompleted = onboardingCompleted,
            profiles = profileSnapshot ?: emptyList(),
            exportedAt = System.currentTimeMillis()
        )
        return json.encodeToString(backup)
    }

    /**
     * Import settings from a JSON string.
     * Returns the parsed backup, or null if invalid.
     *
     * For v1/v2 backups we deliberately drop the old `profiles: List<String>`
     * field on its own, since kotlinx.serialization's `ignoreUnknownKeys`
     * would throw if it found the old scalar-string entries where it now
     * expects [Profile] objects. We pre-parse as a [JsonElement] and rebuild
     * the object with `profiles = []` on older schema versions.
     */
    fun importSettings(jsonString: String): AppBackup? {
        return try {
            val element = json.parseToJsonElement(jsonString)
            val obj = element as? JsonObject ?: return null
            val version = obj["version"]?.let {
                (it as? JsonPrimitive)?.content?.toIntOrNull()
            } ?: 3
            val normalized = if (version < 3) {
                // Strip the incompatible v1/v2 `profiles` field so the
                // serializer doesn't try to decode List<String> into
                // List<Profile>. The feature never populated the list in
                // export anyway, so no user data is lost.
                Log.d(
                    TAG,
                    "importSettings: dropping legacy v$version profiles field " +
                        "(schema was vestigial)",
                )
                JsonObject(obj - "profiles")
            } else {
                obj
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

            // Clear all DataStore preferences
            context.relayDataStore.edit { it.clear() }

            // Restore onboarding flag so it isn't wiped
            if (onboarding) {
                context.relayDataStore.edit { preferences ->
                    preferences[KEY_ONBOARDING_COMPLETED] = true
                }
            }

            // Delete the EncryptedSharedPreferences file for auth tokens
            withContext(Dispatchers.IO) {
                val prefsDir = File(context.filesDir.parent, "shared_prefs")
                val authFile = File(prefsDir, "$AUTH_PREFS_NAME.xml")
                if (authFile.exists()) {
                    authFile.delete()
                    Log.d(TAG, "Deleted auth preferences file")
                }
            }

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
