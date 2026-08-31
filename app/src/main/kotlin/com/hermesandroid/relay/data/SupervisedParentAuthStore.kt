package com.hermesandroid.relay.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Availability of the app-specific parent credential. */
enum class SupervisedParentAuthStatus {
    Missing,
    Configured,
    Corrupt,
}

/** Input method selected for the app-specific parent credential. */
@Serializable
enum class SupervisedParentCredentialType {
    Legacy,
    Pin,
    Password,
}

@Serializable
private enum class SupervisedRecoveryFormat {
    LegacyCode,
    WordPhrase,
}

/** Result of a parent-secret or recovery-phrase verification attempt. */
sealed interface SupervisedParentAuthResult {
    data object Success : SupervisedParentAuthResult
    data class Invalid(val attemptsBeforeDelay: Int) : SupervisedParentAuthResult
    data class Throttled(val retryAfterMillis: Long) : SupervisedParentAuthResult
    data object Missing : SupervisedParentAuthResult
    data object Corrupt : SupervisedParentAuthResult
}

/** Successful enrollment returns a recovery phrase which is shown once and never persisted. */
data class SupervisedParentEnrollment(val recoveryPhrase: String)

/** Validation result for a new parent PIN or password. */
data class SupervisedParentSecretValidation(
    val valid: Boolean,
    val message: String? = null,
)

/** Narrow authentication surface consumed by Compose dialogs and test fakes. */
interface SupervisedParentAuthenticator {
    val credentialTypeFlow: Flow<SupervisedParentCredentialType?>
    suspend fun enroll(
        newSecret: CharArray,
        credentialType: SupervisedParentCredentialType,
    ): Result<SupervisedParentEnrollment>
    suspend fun verify(secret: CharArray): SupervisedParentAuthResult
    suspend fun change(
        currentSecret: CharArray,
        newSecret: CharArray,
        credentialType: SupervisedParentCredentialType,
    ): Result<SupervisedParentEnrollment>
    suspend fun resetWithRecoveryPhrase(
        recoveryPhrase: CharArray,
        newSecret: CharArray,
        credentialType: SupervisedParentCredentialType,
    ): Result<SupervisedParentEnrollment>
}

/**
 * App-specific parent authentication for Supervised Mode.
 *
 * This store deliberately does not delegate to Android's device credential: a
 * child can legitimately own the PIN or biometrics on their Android profile.
 * Only salted PBKDF2 verifiers and bounded failure state are stored. The parent
 * secret and recovery phrase are never persisted.
 */
class SupervisedParentAuthStore private constructor(
    private val dataStore: DataStore<Preferences>,
    private val iterations: Int,
    private val minimumAcceptedIterations: Int,
    private val random: SecureRandom,
    private val nowMillis: () -> Long,
) : SupervisedParentAuthenticator {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.relayDataStore,
        iterations = DEFAULT_PBKDF2_ITERATIONS,
        minimumAcceptedIterations = MIN_ACCEPTED_ITERATIONS,
        random = SecureRandom(),
        nowMillis = System::currentTimeMillis,
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    val statusFlow: Flow<SupervisedParentAuthStatus> = dataStore.data.map { preferences ->
        decode(preferences[KEY_RECORD]).status
    }
    override val credentialTypeFlow: Flow<SupervisedParentCredentialType?> = dataStore.data.map { preferences ->
        decode(preferences[KEY_RECORD]).record?.credentialType
    }

    override suspend fun enroll(
        newSecret: CharArray,
        credentialType: SupervisedParentCredentialType,
    ): Result<SupervisedParentEnrollment> = processMutex.withLock {
        val validation = validateNewSecret(newSecret, credentialType)
        if (!validation.valid) {
            return Result.failure(IllegalArgumentException(validation.message))
        }
        if (decode(dataStore.data.first()[KEY_RECORD]).status != SupervisedParentAuthStatus.Missing) {
            return Result.failure(IllegalStateException("Parent access is already configured or unavailable."))
        }
        runCatching { enrollLocked(newSecret, credentialType) }
    }

    override suspend fun verify(secret: CharArray): SupervisedParentAuthResult = processMutex.withLock {
        verifyLocked(secret, AuthTarget.ParentSecret)
    }

    override suspend fun change(
        currentSecret: CharArray,
        newSecret: CharArray,
        credentialType: SupervisedParentCredentialType,
    ): Result<SupervisedParentEnrollment> = processMutex.withLock {
        val validation = validateNewSecret(newSecret, credentialType)
        if (!validation.valid) {
            return Result.failure(IllegalArgumentException(validation.message))
        }
        when (val verified = verifyLocked(currentSecret, AuthTarget.ParentSecret)) {
            SupervisedParentAuthResult.Success -> runCatching { enrollLocked(newSecret, credentialType) }
            else -> Result.failure(ParentAuthenticationException(verified))
        }
    }

    override suspend fun resetWithRecoveryPhrase(
        recoveryPhrase: CharArray,
        newSecret: CharArray,
        credentialType: SupervisedParentCredentialType,
    ): Result<SupervisedParentEnrollment> = processMutex.withLock {
        val validation = validateNewSecret(newSecret, credentialType)
        if (!validation.valid) {
            return Result.failure(IllegalArgumentException(validation.message))
        }
        val record = decode(dataStore.data.first()[KEY_RECORD]).record
            ?: return Result.failure(ParentAuthenticationException(SupervisedParentAuthResult.Missing))
        val normalizedRecovery = normalizeRecoveryPhrase(recoveryPhrase, record.recoveryFormat)
        try {
            when (val verified = verifyLocked(normalizedRecovery, AuthTarget.RecoveryCode)) {
                SupervisedParentAuthResult.Success -> runCatching { enrollLocked(newSecret, credentialType) }
                else -> Result.failure(ParentAuthenticationException(verified))
            }
        } finally {
            normalizedRecovery.fill('\u0000')
        }
    }

    /**
     * Authenticated escape hatch used by the parent controls.
     *
     * The app-global credential cannot be removed while leaving any supervised
     * policy enabled. Every policy is disabled, but its configuration is retained,
     * in the same transaction that removes the credential.
     */
    suspend fun clearCredentialAndDisablePolicies(): Result<Unit> = processMutex.withLock {
        runCatching {
            SupervisedModeStore.forTesting(dataStore).disableAllAndRemoveCredential(KEY_RECORD)
            Unit
        }
    }

    private suspend fun enrollLocked(
        secret: CharArray,
        credentialType: SupervisedParentCredentialType,
    ): SupervisedParentEnrollment {
        require(credentialType != SupervisedParentCredentialType.Legacy)
        val recoveryChars = generateRecoveryPhrase().toCharArray()
        val parentSalt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val recoverySalt = ByteArray(SALT_BYTES).also(random::nextBytes)
        var parentVerifier = ByteArray(0)
        var recoveryVerifier = ByteArray(0)
        try {
            parentVerifier = derive(secret, parentSalt, iterations)
            recoveryVerifier = derive(recoveryChars, recoverySalt, iterations)
            val record = PersistedParentAuth(
                iterations = iterations,
                parentSalt = encode(parentSalt),
                parentVerifier = encode(parentVerifier),
                recoverySalt = encode(recoverySalt),
                recoveryVerifier = encode(recoveryVerifier),
                credentialType = credentialType,
                recoveryFormat = SupervisedRecoveryFormat.WordPhrase,
            )
            dataStore.edit { it[KEY_RECORD] = json.encodeToString(record) }
            return SupervisedParentEnrollment(recoveryChars.concatToString())
        } finally {
            recoveryChars.fill('\u0000')
            parentSalt.fill(0)
            recoverySalt.fill(0)
            parentVerifier.fill(0)
            recoveryVerifier.fill(0)
        }
    }

    private suspend fun verifyLocked(
        candidate: CharArray,
        target: AuthTarget,
    ): SupervisedParentAuthResult {
        val decoded = decode(dataStore.data.first()[KEY_RECORD])
        val record = decoded.record ?: return when (decoded.status) {
            SupervisedParentAuthStatus.Missing -> SupervisedParentAuthResult.Missing
            else -> SupervisedParentAuthResult.Corrupt
        }
        val now = nowMillis()
        if (record.blockedUntilEpochMillis > now) {
            return SupervisedParentAuthResult.Throttled(record.blockedUntilEpochMillis - now)
        }

        val saltText = when (target) {
            AuthTarget.ParentSecret -> record.parentSalt
            AuthTarget.RecoveryCode -> record.recoverySalt
        }
        val verifierText = when (target) {
            AuthTarget.ParentSecret -> record.parentVerifier
            AuthTarget.RecoveryCode -> record.recoveryVerifier
        }
        val salt = decodeBytes(saltText) ?: return SupervisedParentAuthResult.Corrupt
        val expected = decodeBytes(verifierText) ?: return SupervisedParentAuthResult.Corrupt
        val actual = try {
            derive(candidate, salt, record.iterations)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to derive supervised parent verifier", error)
            return SupervisedParentAuthResult.Corrupt
        } finally {
            salt.fill(0)
        }
        val matches = try {
            MessageDigest.isEqual(expected, actual)
        } finally {
            expected.fill(0)
            actual.fill(0)
        }

        if (matches) {
            if (record.failedAttempts != 0 || record.blockedUntilEpochMillis != 0L) {
                save(record.copy(failedAttempts = 0, blockedUntilEpochMillis = 0L))
            }
            return SupervisedParentAuthResult.Success
        }

        val failures = (record.failedAttempts + 1).coerceAtMost(MAX_TRACKED_FAILURES)
        val delayMillis = backoffMillis(failures)
        save(
            record.copy(
                failedAttempts = failures,
                blockedUntilEpochMillis = if (delayMillis == 0L) 0L else now + delayMillis,
            ),
        )
        return if (delayMillis == 0L) {
            SupervisedParentAuthResult.Invalid(
                attemptsBeforeDelay = (FAILURES_BEFORE_BACKOFF - failures).coerceAtLeast(0),
            )
        } else {
            SupervisedParentAuthResult.Throttled(delayMillis)
        }
    }

    private suspend fun save(record: PersistedParentAuth) {
        dataStore.edit { it[KEY_RECORD] = json.encodeToString(record) }
    }

    private suspend fun derive(secret: CharArray, salt: ByteArray, rounds: Int): ByteArray =
        withContext(Dispatchers.Default) {
            val spec = PBEKeySpec(secret, salt, rounds, KEY_BITS)
            try {
                SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

    private fun decode(raw: String?): DecodedRecord {
        if (raw.isNullOrBlank()) {
            return DecodedRecord(SupervisedParentAuthStatus.Missing, null)
        }
        val record = runCatching { json.decodeFromString<PersistedParentAuth>(raw) }
            .getOrElse {
                Log.w(TAG, "Unable to decode supervised parent authentication; failing closed", it)
                return DecodedRecord(SupervisedParentAuthStatus.Corrupt, null)
            }
        val valid = record.version == RECORD_VERSION &&
            record.algorithm == KDF_ALGORITHM &&
            record.iterations in minimumAcceptedIterations..MAX_ACCEPTED_ITERATIONS &&
            decodeBytes(record.parentSalt)?.size == SALT_BYTES &&
            decodeBytes(record.parentVerifier)?.size == KEY_BITS / 8 &&
            decodeBytes(record.recoverySalt)?.size == SALT_BYTES &&
            decodeBytes(record.recoveryVerifier)?.size == KEY_BITS / 8 &&
            record.failedAttempts in 0..MAX_TRACKED_FAILURES &&
            record.blockedUntilEpochMillis >= 0
        return if (valid) {
            DecodedRecord(SupervisedParentAuthStatus.Configured, record)
        } else {
            DecodedRecord(SupervisedParentAuthStatus.Corrupt, null)
        }
    }

    private fun generateRecoveryPhrase(): String {
        val available = RECOVERY_WORDS.toMutableList()
        val selected = buildList(RECOVERY_WORD_COUNT) {
            repeat(RECOVERY_WORD_COUNT) {
                add(available.removeAt(random.nextInt(available.size)))
            }
        }
        return selected.joinToString("-")
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decodeBytes(value: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()

    private fun backoffMillis(failures: Int): Long = when (failures) {
        in 0 until FAILURES_BEFORE_BACKOFF -> 0L
        FAILURES_BEFORE_BACKOFF -> 30_000L
        FAILURES_BEFORE_BACKOFF + 1 -> 60_000L
        FAILURES_BEFORE_BACKOFF + 2 -> 120_000L
        FAILURES_BEFORE_BACKOFF + 3 -> 300_000L
        else -> MAX_BACKOFF_MILLIS
    }

    @Serializable
    private data class PersistedParentAuth(
        val version: Int = RECORD_VERSION,
        val algorithm: String = KDF_ALGORITHM,
        val iterations: Int,
        val parentSalt: String,
        val parentVerifier: String,
        val recoverySalt: String,
        val recoveryVerifier: String,
        val credentialType: SupervisedParentCredentialType = SupervisedParentCredentialType.Legacy,
        val recoveryFormat: SupervisedRecoveryFormat = SupervisedRecoveryFormat.LegacyCode,
        val failedAttempts: Int = 0,
        val blockedUntilEpochMillis: Long = 0L,
    )

    private data class DecodedRecord(
        val status: SupervisedParentAuthStatus,
        val record: PersistedParentAuth?,
    )

    private enum class AuthTarget { ParentSecret, RecoveryCode }

    class ParentAuthenticationException(
        val authResult: SupervisedParentAuthResult,
    ) : IllegalStateException("Parent authentication failed: $authResult")

    companion object {
        private const val TAG = "SupervisedParentAuth"
        private const val RECORD_VERSION = 1
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val DEFAULT_PBKDF2_ITERATIONS = 310_000
        private const val MIN_ACCEPTED_ITERATIONS = 100_000
        private const val MAX_ACCEPTED_ITERATIONS = 1_000_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private const val FAILURES_BEFORE_BACKOFF = 5
        private const val MAX_TRACKED_FAILURES = 9
        private const val MAX_BACKOFF_MILLIS = 15 * 60_000L
        private const val RECOVERY_WORD_COUNT = 6
        private val KEY_RECORD = stringPreferencesKey("supervised_parent_auth_v1")
        private val processMutex = Mutex()

        fun validateNewSecret(
            secret: CharArray,
            credentialType: SupervisedParentCredentialType,
        ): SupervisedParentSecretValidation {
            if (secret.size > 64) {
                return SupervisedParentSecretValidation(false, "Use at most 64 characters.")
            }
            if (credentialType == SupervisedParentCredentialType.Pin) {
                return if (secret.size == 6 && secret.all(Char::isDigit)) {
                    SupervisedParentSecretValidation(true)
                } else {
                    SupervisedParentSecretValidation(false, "Use exactly 6 digits.")
                }
            }
            return if (
                credentialType == SupervisedParentCredentialType.Password &&
                secret.size >= 8 && secret.any { !it.isWhitespace() }
            ) {
                SupervisedParentSecretValidation(true)
            } else {
                SupervisedParentSecretValidation(false, "Use a password with at least 8 characters.")
            }
        }

        private fun normalizeRecoveryPhrase(
            value: CharArray,
            format: SupervisedRecoveryFormat,
        ): CharArray = when (format) {
            SupervisedRecoveryFormat.LegacyCode -> value
                .filterNot { it == '-' || it.isWhitespace() }
                .joinToString("")
                .uppercase()
                .toCharArray()
            SupervisedRecoveryFormat.WordPhrase -> value.concatToString()
                .trim()
                .lowercase()
                .split(Regex("[-\\s]+"))
                .filter(String::isNotBlank)
                .joinToString("-")
                .toCharArray()
        }

        private val RECOVERY_WORDS = listOf(
            "acorn", "amber", "apple", "april", "arrow", "beach", "berry", "birch",
            "blue", "breeze", "brook", "button", "cabin", "cactus", "candle", "cedar",
            "cherry", "cloud", "clover", "cobalt", "comet", "coral", "cotton", "cove",
            "daisy", "dawn", "delta", "drift", "eagle", "earth", "ember", "fern",
            "field", "finch", "forest", "frost", "garden", "ginger", "glade", "gold",
            "grape", "green", "harbor", "hazel", "heron", "honey", "island", "ivory",
            "jade", "juniper", "kite", "lagoon", "lake", "lantern", "lark", "leaf",
            "lemon", "lilac", "lotus", "maple", "meadow", "mint", "moon", "morning",
            "moss", "oasis", "ocean", "olive", "orchid", "otter", "peach", "pearl",
            "pebble", "pine", "plum", "pond", "poppy", "quartz", "rain", "reed",
            "river", "robin", "rose", "saffron", "sage", "sand", "shell", "silver",
            "sky", "snow", "sparrow", "spring", "spruce", "star", "stone", "summer",
            "sun", "sunset", "teal", "thistle", "tide", "tulip", "valley", "violet",
            "willow", "wind", "winter", "wood", "wren", "yellow", "zephyr", "zinnia",
            "anchor", "bamboo", "copper", "cricket", "feather", "harvest", "marble", "ribbon",
            "rocket", "shadow", "timber", "whistle", "yarrow", "almond", "badger", "canvas",
        )

        internal fun forTesting(
            dataStore: DataStore<Preferences>,
            iterations: Int = MIN_ACCEPTED_ITERATIONS,
            minimumAcceptedIterations: Int = MIN_ACCEPTED_ITERATIONS,
            random: SecureRandom = SecureRandom(),
            nowMillis: () -> Long = System::currentTimeMillis,
        ): SupervisedParentAuthStore = SupervisedParentAuthStore(
            dataStore = dataStore,
            iterations = iterations,
            minimumAcceptedIterations = minimumAcceptedIterations,
            random = random,
            nowMillis = nowMillis,
        )

        internal val recordKeyForTesting: Preferences.Key<String> = KEY_RECORD
    }
}
