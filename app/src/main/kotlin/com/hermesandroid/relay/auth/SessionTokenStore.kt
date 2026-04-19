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
    private val context: Context,
    private val wantsStrongBox: Boolean,
    override val hasHardwareBackedStorage: Boolean,
    private val prefsName: String,
) : SessionTokenStore {

    // Mutable so [resetPrefs] can swap in a fresh instance after a corrupted
    // file is deleted. Built lazily via [buildPrefs] so the constructor can't
    // throw — [tryCreate] still controls the "is this device usable at all"
    // decision via its init probe below.
    private var prefs: SharedPreferences = buildPrefs()

    private fun buildPrefs(): SharedPreferences {
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
        return EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Nuke a corrupted EncryptedSharedPreferences file and rebuild a fresh
     * one. Triggered from any read/write that throws — the typical failure
     * mode is the master key getting rotated out from under us during a
     * Studio reinstall, after which every decrypt fails with
     * `AEADBadTagException` (or sometimes a wrapped `GeneralSecurityException`)
     * forever. The cure is to delete the file so the next pair flow re-stores
     * everything against a fresh key.
     *
     * Best-effort: swallow exceptions from the `clear()` and
     * `deleteSharedPreferences` calls themselves, since they can also throw
     * when the underlying state is wedged.
     */
    private fun resetPrefs() {
        try {
            prefs.edit().clear().apply()
        } catch (_: Exception) { /* expected on a wedged file */ }
        try {
            context.deleteSharedPreferences(prefsName)
        } catch (e: Exception) {
            Log.w(TAG, "deleteSharedPreferences($prefsName) failed: ${e.message}")
        }
        prefs = buildPrefs()
    }

    companion object {
        private const val TAG = "KeystoreTokenStore"

        /**
         * Default EncryptedSharedPreferences filename. Pre-multi-connection
         * installs have all their auth state in this single file —
         * connection 0 re-uses it as-is via
         * [Connection.LEGACY_TOKEN_STORE_KEY] so no user-visible migration is
         * needed.
         */
        const val DEFAULT_PREFS_NAME = "hermes_companion_auth_hw"

        /**
         * Try to build a [KeystoreTokenStore] on the current device. Returns
         * null when any step throws (some older OEM ROMs have broken
         * AndroidKeystore implementations — we don't want the app to brick
         * itself trying to create a master key).
         *
         * [prefsName] selects the EncryptedSharedPreferences file — defaults
         * to [DEFAULT_PREFS_NAME] for backwards compat with the
         * single-connection call sites. Multi-connection callers pass a
         * per-connection filename built via
         * [com.hermesandroid.relay.data.Connection.buildTokenStoreKey].
         *
         * Also fires a one-shot read probe so a pre-corrupted file from a
         * previous install gets healed during construction rather than on the
         * first user-driven read. The probe routes through the instance's
         * own [getString], so if it throws, [resetPrefs] runs and we end up
         * with a fresh empty prefs file — not a permanently broken store.
         */
        fun tryCreate(
            context: Context,
            prefsName: String = DEFAULT_PREFS_NAME,
        ): KeystoreTokenStore? {
            return try {
                val wantsStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    context.packageManager.hasSystemFeature(
                        android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE
                    )
                val store = KeystoreTokenStore(
                    context = context.applicationContext,
                    wantsStrongBox = wantsStrongBox,
                    hasHardwareBackedStorage = wantsStrongBox,
                    prefsName = prefsName,
                )
                // Force a read so a wedged file from a prior install heals
                // here rather than at the first user-visible call.
                store.contains("__init_probe__")
                store
            } catch (e: Exception) {
                // Broken Keystore, expired key, etc. — fall back to legacy.
                Log.w(TAG, "KeystoreTokenStore init failed: ${e.message}")
                null
            }
        }
    }

    override fun getString(key: String): String? {
        return try {
            prefs.getString(key, null)
        } catch (e: Exception) {
            Log.w(TAG, "getString($key) failed — wiping corrupted prefs: ${e.message}")
            resetPrefs()
            null
        }
    }

    override fun putString(key: String, value: String) {
        try {
            prefs.edit().putString(key, value).apply()
        } catch (e: Exception) {
            Log.w(TAG, "putString($key) failed — rebuilding prefs and retrying: ${e.message}")
            resetPrefs()
            try {
                prefs.edit().putString(key, value).apply()
            } catch (e2: Exception) {
                Log.w(TAG, "putString($key) retry after reset failed: ${e2.message}")
            }
        }
    }

    override fun remove(key: String) {
        try {
            prefs.edit().remove(key).apply()
        } catch (e: Exception) {
            Log.w(TAG, "remove($key) failed: ${e.message}")
            resetPrefs()
        }
    }

    override fun contains(key: String): Boolean {
        return try {
            prefs.contains(key)
        } catch (e: Exception) {
            Log.w(TAG, "contains($key) failed — wiping corrupted prefs: ${e.message}")
            resetPrefs()
            false
        }
    }

    override fun clearAll() {
        try {
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.w(TAG, "clearAll failed — falling back to file delete: ${e.message}")
            resetPrefs()
        }
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
class LegacyEncryptedPrefsTokenStore(
    context: Context,
    private val prefsName: String = LEGACY_PREFS_NAME,
) : SessionTokenStore {

    companion object {
        const val LEGACY_PREFS_NAME = "hermes_companion_auth"
        private const val TAG = "LegacyEncryptedPrefs"
    }

    private val appContext: Context = context.applicationContext

    // Mutable so [resetPrefs] can swap in a fresh instance after a corrupted
    // file is deleted. See [KeystoreTokenStore.resetPrefs] for the rationale.
    private var prefs: SharedPreferences = buildPrefs()

    private fun buildPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun resetPrefs() {
        try {
            prefs.edit().clear().apply()
        } catch (_: Exception) { /* expected on a wedged file */ }
        try {
            appContext.deleteSharedPreferences(prefsName)
        } catch (e: Exception) {
            Log.w(TAG, "deleteSharedPreferences($prefsName) failed: ${e.message}")
        }
        prefs = buildPrefs()
    }

    // AES256_GCM via MasterKey is hardware-backed (TEE) on essentially every
    // shipping Android device — but we don't have the StrongBox attestation
    // guarantee, so we report false here. The UI uses this to decide whether
    // to render the shield badge.
    override val hasHardwareBackedStorage: Boolean = false

    override fun getString(key: String): String? {
        return try {
            prefs.getString(key, null)
        } catch (e: Exception) {
            Log.w(TAG, "getString($key) failed — wiping legacy prefs: ${e.message}")
            resetPrefs()
            null
        }
    }

    override fun putString(key: String, value: String) {
        try {
            prefs.edit().putString(key, value).apply()
        } catch (e: Exception) {
            Log.w(TAG, "putString($key) failed — rebuilding legacy prefs and retrying: ${e.message}")
            resetPrefs()
            try {
                prefs.edit().putString(key, value).apply()
            } catch (e2: Exception) {
                Log.w(TAG, "putString($key) retry after reset failed: ${e2.message}")
            }
        }
    }

    override fun remove(key: String) {
        try {
            prefs.edit().remove(key).apply()
        } catch (e: Exception) {
            Log.w(TAG, "remove($key) failed: ${e.message}")
            resetPrefs()
        }
    }

    override fun contains(key: String): Boolean {
        return try {
            prefs.contains(key)
        } catch (e: Exception) {
            Log.w(TAG, "contains($key) failed — wiping legacy prefs: ${e.message}")
            resetPrefs()
            false
        }
    }

    override fun clearAll() {
        try {
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.w(TAG, "clearAll failed — falling back to file delete: ${e.message}")
            resetPrefs()
        }
    }
}
