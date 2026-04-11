package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User-tunable limits for inbound media attachments fetched from the relay.
 *
 * - [maxInboundSizeMb] hard cap — anything larger is rejected after download
 *   and the attachment flips to FAILED with a "too large" message.
 * - [autoFetchThresholdMb] soft threshold — files up to this size auto-fetch
 *   on any network. Above it, still auto-fetch on Wi-Fi but show a manual
 *   "tap to download" CTA on cellular (unless [autoFetchOnCellular] is set).
 *   (Current implementation auto-fetches everything up to the max cap when
 *   the connection isn't cellular; the threshold gates cellular behavior.)
 * - [autoFetchOnCellular] master switch: when false, the cellular-network
 *   case always inserts a manual-download placeholder.
 * - [cachedMediaCapMb] LRU cap on the `hermes-media/` cache directory.
 */
data class MediaSettings(
    val maxInboundSizeMb: Int = 25,
    val autoFetchThresholdMb: Int = 2,
    val autoFetchOnCellular: Boolean = false,
    val cachedMediaCapMb: Int = 200
)

/**
 * DataStore-backed settings repository for inbound media behavior.
 * Shares the app-wide [relayDataStore] so all preferences live in one file.
 */
class MediaSettingsRepository(private val context: Context) {

    companion object {
        private val KEY_MAX_INBOUND_MB = intPreferencesKey("media_max_inbound_mb")
        private val KEY_AUTO_FETCH_THRESHOLD_MB = intPreferencesKey("media_auto_fetch_threshold_mb")
        private val KEY_AUTO_FETCH_ON_CELLULAR = booleanPreferencesKey("media_auto_fetch_on_cellular")
        private val KEY_CACHED_MEDIA_CAP_MB = intPreferencesKey("media_cached_cap_mb")

        const val DEFAULT_MAX_INBOUND_MB = 25
        const val DEFAULT_AUTO_FETCH_THRESHOLD_MB = 2
        const val DEFAULT_AUTO_FETCH_ON_CELLULAR = false
        const val DEFAULT_CACHED_MEDIA_CAP_MB = 200
    }

    val settings: Flow<MediaSettings> = context.relayDataStore.data.map { prefs ->
        MediaSettings(
            maxInboundSizeMb = prefs[KEY_MAX_INBOUND_MB] ?: DEFAULT_MAX_INBOUND_MB,
            autoFetchThresholdMb = prefs[KEY_AUTO_FETCH_THRESHOLD_MB] ?: DEFAULT_AUTO_FETCH_THRESHOLD_MB,
            autoFetchOnCellular = prefs[KEY_AUTO_FETCH_ON_CELLULAR] ?: DEFAULT_AUTO_FETCH_ON_CELLULAR,
            cachedMediaCapMb = prefs[KEY_CACHED_MEDIA_CAP_MB] ?: DEFAULT_CACHED_MEDIA_CAP_MB
        )
    }

    suspend fun setMaxInboundSize(mb: Int) {
        context.relayDataStore.edit { it[KEY_MAX_INBOUND_MB] = mb.coerceAtLeast(1) }
    }

    suspend fun setAutoFetchThreshold(mb: Int) {
        context.relayDataStore.edit { it[KEY_AUTO_FETCH_THRESHOLD_MB] = mb.coerceAtLeast(0) }
    }

    suspend fun setAutoFetchOnCellular(enabled: Boolean) {
        context.relayDataStore.edit { it[KEY_AUTO_FETCH_ON_CELLULAR] = enabled }
    }

    suspend fun setCachedMediaCap(mb: Int) {
        context.relayDataStore.edit { it[KEY_CACHED_MEDIA_CAP_MB] = mb.coerceAtLeast(10) }
    }
}
