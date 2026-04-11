package com.hermesandroid.relay.auth

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Abstraction over the storage backend for the relay session token + API key
 * + device ID.
 *
 * Two implementations exist:
 *
 *  - [KeystoreTokenStore] — preferred. Uses [MasterKey] with
 *    `setRequestStrongBoxBacked(true)` on Android P+ devices that report a
 *    dedicated secure element (`PackageManager.FEATURE_STRONGBOX_KEYSTORE`).
 *    Falls back to AES256_GCM on TEE when StrongBox isn't available.
 *
 *  - [LegacyEncryptedPrefsTokenStore] — fallback. The plain
 *    `MasterKey.Builder().setKeyScheme(AES256_GCM)` path that the pre-
 *    overhaul [AuthManager] used. Still strong — AES256_GCM is hardware-
 *    backed on almost every shipping device — but lacks the StrongBox
 *    attestation guarantee.
 *
 * Why an interface: we want to migrate existing installs off the legacy path
 * without forcing users to re-pair. [AuthManager] picks the best available
 * store, one-shot copies the legacy prefs in, and clears them. See
 * `migrateFromLegacyIfNeeded` in [AuthManager].
 *
 * The store reports whether it's actually hardware-backed via
 * [hasHardwareBackedStorage] so the UI can show a "🛡 hardware-backed" badge
 * on the paired-device card.
 */
interface SessionTokenStore {
    /**
     * True when the underlying [MasterKey] is backed by a dedicated secure
     * element (StrongBox). A `false` here doesn't mean "insecure" — it just
     * means the key is in TEE or software-emulated, depending on the device.
     */
    val hasHardwareBackedStorage: Boolean

    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun contains(key: String): Boolean

    /** Wipe every entry — used by `resetAppData` / "factory reset" in Settings. */
    fun clearAll()
}

// ---------------------------------------------------------------------------
// Keystore-backed implementation
// ---------------------------------------------------------------------------

/**
 * [SessionTokenStore] using [EncryptedSharedPreferences] backed by a
 * [MasterKey] requesting StrongBox when available.
 *
 * On Android P+ devices with `FEATURE_STRONGBOX_KEYSTORE`, the master key
 * lives in the dedicated secure element and all crypto ops happen there.
 * On older devices or devices without StrongBox, this silently falls back
 * to TEE-backed AES256_GCM — still hardware-backed on virtually every real
 * phone.
 */
class KeystoreTokenStore private constructor(
    private val prefs: SharedPreferences,
    override val hasHardwareBackedStorage: Boolean
) : SessionTokenStore {

    companion object {
        private const val TAG = "KeystoreTokenStore"
        private const val PREFS_NAME = "hermes_companion_auth_hw"

        /**
         * Try to build a [KeystoreTokenStore] on the current device. Returns
         * null when any step throws (some older OEM ROMs have broken
         * AndroidKeystore implementations — we don't want the app to brick
         * itself trying to create a master key).
         */
        fun tryCreate(context: Context): KeystoreTokenStore? {
            return try {
                val wantsStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    context.packageManager.hasSystemFeature(
                        android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE
                    )

                val builder = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)

                if (wantsStrongBox) {
                    try {
                        builder.setRequestStrongBoxBacked(true)
                    } catch (e: Exception) {
                        Log.w(TAG, "StrongBox request failed, falling back: ${e.message}")
                    }
                }

                val masterKey = builder.build()

                val prefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                KeystoreTokenStore(prefs, wantsStrongBox)
            } catch (e: Exception) {
                // Broken Keystore, expired key, etc. — fall back to legacy.
                Log.w(TAG, "KeystoreTokenStore init failed: ${e.message}")
                null
            }
        }
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun clearAll() {
        prefs.edit().clear().apply()
    }
}

// ---------------------------------------------------------------------------
// Legacy fallback implementation
// ---------------------------------------------------------------------------

/**
 * The original [EncryptedSharedPreferences] path — uses the same file name
 * as pre-overhaul [AuthManager]. Instances of this class serve a dual role:
 * (a) fallback for devices where [KeystoreTokenStore.tryCreate] fails, and
 * (b) migration source for reading existing session tokens out of the legacy
 * prefs on first launch after the update.
 */
class LegacyEncryptedPrefsTokenStore(context: Context) : SessionTokenStore {

    companion object {
        const val LEGACY_PREFS_NAME = "hermes_companion_auth"
    }

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            LEGACY_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // AES256_GCM via MasterKey is hardware-backed (TEE) on essentially every
    // shipping Android device — but we don't have the StrongBox attestation
    // guarantee, so we report false here. The UI uses this to decide whether
    // to render the shield badge.
    override val hasHardwareBackedStorage: Boolean = false

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun clearAll() {
        prefs.edit().clear().apply()
    }
}
