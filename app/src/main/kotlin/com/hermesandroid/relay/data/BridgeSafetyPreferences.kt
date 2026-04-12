package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * User-tunable Tier 5 bridge-safety preferences.
 *
 * Phase 3 Wave 2 — owned by Agent ζ (`bridge-safety-rails`).
 *
 * The five knobs this file backs match the Tier 5 spec:
 *
 *  - [blocklist] — per-app package-name allowlist-inverse. If the currently
 *    foregrounded package is in this set, `BridgeCommandHandler` refuses
 *    every action with HTTP 403. Defaults ship with a conservative list of
 *    common banking apps + password managers so first-run users are safe
 *    from "agent taps Transfer in my banking app" accidents before they
 *    ever touch the settings screen. Users can edit freely.
 *
 *  - [destructiveVerbs] — words that trigger a confirmation modal when they
 *    appear in `/tap_text` or `/type` payloads. Seeded with a set of verbs
 *    that carry irreversible or high-stakes consequences. Editable.
 *
 *  - [autoDisableMinutes] — idle timeout after which the master toggle
 *    auto-flips to false. Rescheduled on every command so an active agent
 *    never triggers it; a runaway agent that stops sending commands for
 *    this long loses bridge access automatically.
 *
 *  - [statusOverlayEnabled] — opt-in floating-dot indicator (like the
 *    screen-recording red dot) that's visible while bridge is active.
 *    Off by default — some users hate persistent overlays, foreground-
 *    service notification already signals liveness.
 *
 *  - [confirmationTimeoutSeconds] — how long the destructive-verb modal
 *    waits before treating silence as DENY. Default 30s.
 *
 * Matches the `BridgePreferences.kt` / `VoicePreferences.kt` / `MediaSettings.kt`
 * style: a single DataStore (`relayDataStore`), lists serialized as JSON
 * strings under one key each, lenient deserialization so schema evolution
 * doesn't break installed users.
 */
data class BridgeSafetySettings(
    val blocklist: Set<String> = DEFAULT_BLOCKLIST,
    val destructiveVerbs: Set<String> = DEFAULT_DESTRUCTIVE_VERBS,
    val autoDisableMinutes: Int = DEFAULT_AUTO_DISABLE_MINUTES,
    val statusOverlayEnabled: Boolean = DEFAULT_STATUS_OVERLAY_ENABLED,
    val confirmationTimeoutSeconds: Int = DEFAULT_CONFIRMATION_TIMEOUT_SECONDS,
)

/** Seeded blocklist — conservative high-stakes packages shipped out of the box. */
val DEFAULT_BLOCKLIST: Set<String> = setOf(
    // Banking — US / UK / generic
    "com.chase.sig.android",
    "com.wf.wellsfargomobile",
    "com.bankofamerica.digitalwallet",
    "com.usaa.mobile.android.usaa",
    "com.konylabs.capitalone",
    "com.americanexpress.android.acctsvcs.us",
    "com.discoverfinancial.mobile",
    "com.infonow.bofa",
    "uk.co.hsbc.hsbcukmobilebanking",
    "com.barclays.android.barclaysmobilebanking",
    "com.monzo.android",
    "co.uk.getmondo",
    "co.revolut.app",
    "com.starlingbank.android",
    // Payments / crypto
    "com.venmo",
    "com.squareup.cash",
    "com.paypal.android.p2pmobile",
    "com.coinbase.android",
    "co.mona.android",
    // Password managers
    "com.lastpass.lpandroid",
    "com.agilebits.onepassword",
    "com.x8bit.bitwarden",
    "com.dashlane.frozenaccount",
    "com.keepersecurity.passwordmanager",
    "com.bitwarden.authenticator",
    // 2FA apps
    "com.google.android.apps.authenticator2",
    "com.authy.authy",
    "com.duosecurity.duomobile",
)

/**
 * Seeded destructive-verb list. Matched case-insensitively as whole words
 * (via regex `\b<verb>\b`) inside `tap_text` / `type` payloads. Users can
 * add/remove via [BridgeSafetySettingsScreen].
 */
val DEFAULT_DESTRUCTIVE_VERBS: Set<String> = setOf(
    "send",
    "pay",
    "delete",
    "transfer",
    "confirm",
    "submit",
    "post",
    "publish",
    "buy",
    "purchase",
    "charge",
    "withdraw",
)

const val DEFAULT_AUTO_DISABLE_MINUTES: Int = 30
const val MIN_AUTO_DISABLE_MINUTES: Int = 5
const val MAX_AUTO_DISABLE_MINUTES: Int = 120

const val DEFAULT_STATUS_OVERLAY_ENABLED: Boolean = false

const val DEFAULT_CONFIRMATION_TIMEOUT_SECONDS: Int = 30
const val MIN_CONFIRMATION_TIMEOUT_SECONDS: Int = 10
const val MAX_CONFIRMATION_TIMEOUT_SECONDS: Int = 60

class BridgeSafetyPreferencesRepository(private val context: Context) {

    companion object {
        private val KEY_BLOCKLIST = stringPreferencesKey("bridge_blocklist")
        private val KEY_DESTRUCTIVE_VERBS = stringPreferencesKey("bridge_destructive_verbs")
        private val KEY_AUTO_DISABLE_MINUTES = intPreferencesKey("bridge_auto_disable_minutes")
        private val KEY_STATUS_OVERLAY = booleanPreferencesKey("bridge_status_overlay_enabled")
        private val KEY_CONFIRMATION_TIMEOUT =
            intPreferencesKey("bridge_confirmation_timeout_seconds")

        /** Sentinel key we set the first time settings get written. Used to
         *  tell "user cleared the blocklist" from "user has never touched it". */
        private val KEY_SAFETY_INITIALIZED = booleanPreferencesKey("bridge_safety_initialized")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val settings: Flow<BridgeSafetySettings> = context.relayDataStore.data.map { prefs ->
        val initialized = prefs[KEY_SAFETY_INITIALIZED] ?: false
        val blocklist = prefs[KEY_BLOCKLIST]?.let { decodeSet(it) }
            ?: if (initialized) emptySet() else DEFAULT_BLOCKLIST
        val verbs = prefs[KEY_DESTRUCTIVE_VERBS]?.let { decodeSet(it) }
            ?: if (initialized) emptySet() else DEFAULT_DESTRUCTIVE_VERBS
        BridgeSafetySettings(
            blocklist = blocklist,
            destructiveVerbs = verbs,
            autoDisableMinutes = (prefs[KEY_AUTO_DISABLE_MINUTES] ?: DEFAULT_AUTO_DISABLE_MINUTES)
                .coerceIn(MIN_AUTO_DISABLE_MINUTES, MAX_AUTO_DISABLE_MINUTES),
            statusOverlayEnabled = prefs[KEY_STATUS_OVERLAY] ?: DEFAULT_STATUS_OVERLAY_ENABLED,
            confirmationTimeoutSeconds = (prefs[KEY_CONFIRMATION_TIMEOUT]
                ?: DEFAULT_CONFIRMATION_TIMEOUT_SECONDS)
                .coerceIn(MIN_CONFIRMATION_TIMEOUT_SECONDS, MAX_CONFIRMATION_TIMEOUT_SECONDS),
        )
    }

    suspend fun setBlocklist(packages: Set<String>) {
        context.relayDataStore.edit { prefs ->
            prefs[KEY_BLOCKLIST] = json.encodeToString(packages.toList().sorted())
            prefs[KEY_SAFETY_INITIALIZED] = true
        }
    }

    suspend fun addToBlocklist(packageName: String) {
        context.relayDataStore.edit { prefs ->
            val current = prefs[KEY_BLOCKLIST]?.let { decodeSet(it) } ?: DEFAULT_BLOCKLIST
            val next = (current + packageName).toList().sorted()
            prefs[KEY_BLOCKLIST] = json.encodeToString(next)
            prefs[KEY_SAFETY_INITIALIZED] = true
        }
    }

    suspend fun removeFromBlocklist(packageName: String) {
        context.relayDataStore.edit { prefs ->
            val current = prefs[KEY_BLOCKLIST]?.let { decodeSet(it) } ?: DEFAULT_BLOCKLIST
            val next = (current - packageName).toList().sorted()
            prefs[KEY_BLOCKLIST] = json.encodeToString(next)
            prefs[KEY_SAFETY_INITIALIZED] = true
        }
    }

    suspend fun setDestructiveVerbs(verbs: Set<String>) {
        context.relayDataStore.edit { prefs ->
            prefs[KEY_DESTRUCTIVE_VERBS] = json.encodeToString(
                verbs.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet().toList().sorted()
            )
            prefs[KEY_SAFETY_INITIALIZED] = true
        }
    }

    suspend fun addDestructiveVerb(verb: String) {
        val normalized = verb.trim().lowercase()
        if (normalized.isEmpty()) return
        context.relayDataStore.edit { prefs ->
            val current = prefs[KEY_DESTRUCTIVE_VERBS]?.let { decodeSet(it) } ?: DEFAULT_DESTRUCTIVE_VERBS
            val next = (current + normalized).toList().sorted()
            prefs[KEY_DESTRUCTIVE_VERBS] = json.encodeToString(next)
            prefs[KEY_SAFETY_INITIALIZED] = true
        }
    }

    suspend fun removeDestructiveVerb(verb: String) {
        val normalized = verb.trim().lowercase()
        context.relayDataStore.edit { prefs ->
            val current = prefs[KEY_DESTRUCTIVE_VERBS]?.let { decodeSet(it) } ?: DEFAULT_DESTRUCTIVE_VERBS
            val next = (current - normalized).toList().sorted()
            prefs[KEY_DESTRUCTIVE_VERBS] = json.encodeToString(next)
            prefs[KEY_SAFETY_INITIALIZED] = true
        }
    }

    suspend fun setAutoDisableMinutes(minutes: Int) {
        context.relayDataStore.edit { prefs ->
            prefs[KEY_AUTO_DISABLE_MINUTES] =
                minutes.coerceIn(MIN_AUTO_DISABLE_MINUTES, MAX_AUTO_DISABLE_MINUTES)
            prefs[KEY_SAFETY_INITIALIZED] = true
        }
    }

    suspend fun setStatusOverlayEnabled(enabled: Boolean) {
        context.relayDataStore.edit { prefs ->
            prefs[KEY_STATUS_OVERLAY] = enabled
            prefs[KEY_SAFETY_INITIALIZED] = true
        }
    }

    suspend fun setConfirmationTimeoutSeconds(seconds: Int) {
        context.relayDataStore.edit { prefs ->
            prefs[KEY_CONFIRMATION_TIMEOUT] =
                seconds.coerceIn(MIN_CONFIRMATION_TIMEOUT_SECONDS, MAX_CONFIRMATION_TIMEOUT_SECONDS)
            prefs[KEY_SAFETY_INITIALIZED] = true
        }
    }

    private fun decodeSet(raw: String): Set<String> =
        runCatching { json.decodeFromString<List<String>>(raw).toSet() }
            .getOrDefault(emptySet())
}
