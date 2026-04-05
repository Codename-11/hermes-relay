package com.hermesandroid.companion.data

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
import java.io.File

/**
 * Manages app data: backup, restore, and reset.
 *
 * Backup format is a JSON file containing settings and connection info.
 * Tokens are NOT included in backups for security.
 */
class DataManager(private val context: Context) {

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
     */
    @Serializable
    data class AppBackup(
        val version: Int = 1,
        val serverUrl: String? = null,
        val theme: String = "auto",
        val onboardingCompleted: Boolean = false,
        val profiles: List<String> = emptyList(),
        val exportedAt: Long = System.currentTimeMillis()
    )

    /**
     * Export app settings to a JSON string.
     * Does NOT include session tokens or device IDs (security).
     */
    suspend fun exportSettings(
        serverUrl: String?,
        theme: String,
        onboardingCompleted: Boolean,
        profiles: List<String>
    ): String {
        val backup = AppBackup(
            version = 1,
            serverUrl = serverUrl,
            theme = theme,
            onboardingCompleted = onboardingCompleted,
            profiles = profiles,
            exportedAt = System.currentTimeMillis()
        )
        return json.encodeToString(backup)
    }

    /**
     * Import settings from a JSON string.
     * Returns the parsed backup, or null if invalid.
     */
    fun importSettings(jsonString: String): AppBackup? {
        return try {
            json.decodeFromString<AppBackup>(jsonString)
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
            context.companionDataStore.edit { it.clear() }

            // Restore onboarding flag so it isn't wiped
            if (onboarding) {
                context.companionDataStore.edit { preferences ->
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
            context.companionDataStore.edit { preferences ->
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
            context.companionDataStore.data
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
            context.companionDataStore.edit { preferences ->
                preferences[KEY_ONBOARDING_COMPLETED] = completed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set onboarding completed", e)
        }
    }
}
