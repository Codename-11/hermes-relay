package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hermesandroid.relay.bridge.BridgeCapability
import com.hermesandroid.relay.bridge.BridgeCapabilityPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Connection-scoped Bridge authority. Missing, malformed, or future schemas deny all. */
class BridgeCapabilityPolicyRepository(private val context: Context) {
    companion object {
        private val KEY_POLICIES = stringPreferencesKey("bridge_capability_policies_v1")
    }

    @Serializable
    private data class StoredPolicies(
        val schemaVersion: Int = BridgeCapabilityPolicy.CURRENT_SCHEMA_VERSION,
        val installId: String = "",
        val byConnection: Map<String, BridgeCapabilityPolicy> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val installId: String = localInstallId(context)

    fun policy(connectionId: String?): Flow<BridgeCapabilityPolicy> =
        context.relayDataStore.data.map { prefs ->
            readPolicies(prefs[KEY_POLICIES])[connectionId.normalizedPolicyKey()]
                ?.takeIf { it.schemaVersion == BridgeCapabilityPolicy.CURRENT_SCHEMA_VERSION }
                ?: BridgeCapabilityPolicy()
        }

    suspend fun snapshot(connectionId: String?): BridgeCapabilityPolicy =
        policy(connectionId).first()

    suspend fun setPermanent(connectionId: String?, capability: BridgeCapability, allowed: Boolean) {
        require(!capability.timed) { "Timed capabilities require an expiry" }
        update(connectionId) { current ->
            current.copy(
                permanentGrants = if (allowed) {
                    current.permanentGrants + capability
                } else {
                    current.permanentGrants - capability
                },
            )
        }
    }

    suspend fun replacePermanent(
        connectionId: String?,
        capabilities: Set<BridgeCapability>,
    ) {
        require(capabilities.none { it.timed }) { "Timed capabilities require an expiry" }
        update(connectionId) { current -> current.copy(permanentGrants = capabilities) }
    }

    suspend fun grantTimed(
        connectionId: String?,
        capability: BridgeCapability,
        expiresAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        require(capability.timed) { "Permanent capabilities do not accept an expiry" }
        update(connectionId) { current ->
            current.copy(
                timedExpiriesMs = (
                    current.timedExpiriesMs.filterValues { it > nowMs }.keys + capability
                )
                    .associateWith { expiresAtMs },
            )
        }
    }

    suspend fun revoke(connectionId: String?, capability: BridgeCapability) {
        update(connectionId) { current ->
            current.copy(
                permanentGrants = current.permanentGrants - capability,
                timedExpiriesMs = current.timedExpiriesMs - capability,
            )
        }
    }

    suspend fun revokeTimed(connectionId: String?) {
        update(connectionId) { it.copy(timedExpiriesMs = emptyMap()) }
    }

    suspend fun replaceTimed(
        connectionId: String?,
        capabilities: Set<BridgeCapability>,
        expiresAtMs: Long,
    ) {
        require(capabilities.all { it.timed }) { "Permanent capabilities cannot be timed" }
        update(connectionId) { current ->
            current.copy(timedExpiriesMs = capabilities.associateWith { expiresAtMs })
        }
    }

    suspend fun refreshActiveTimed(connectionId: String?, expiresAtMs: Long) {
        update(connectionId) { current ->
            current.copy(
                timedExpiriesMs = current.timedExpiriesMs.mapNotNull { (capability, currentExpiry) ->
                    when {
                        currentExpiry == BridgeCapabilityPolicy.NEVER_EXPIRES_AT_MS ->
                            capability to currentExpiry
                        currentExpiry > System.currentTimeMillis() -> capability to expiresAtMs
                        else -> null
                    }
                }.toMap(),
            )
        }
    }

    suspend fun pruneExpired(connectionId: String?, nowMs: Long) {
        update(connectionId) { current ->
            current.copy(timedExpiriesMs = current.timedExpiriesMs.filterValues { it > nowMs })
        }
    }

    suspend fun clearConnection(connectionId: String) {
        val key = connectionId.normalizedPolicyKey()
        context.relayDataStore.edit { prefs ->
            val current = readPolicies(prefs[KEY_POLICIES]).toMutableMap()
            current.remove(key)
            prefs[KEY_POLICIES] = json.encodeToString(
                StoredPolicies(installId = installId, byConnection = current),
            )
        }
    }

    private suspend fun update(
        connectionId: String?,
        transform: (BridgeCapabilityPolicy) -> BridgeCapabilityPolicy,
    ) {
        val key = connectionId.normalizedPolicyKey()
        context.relayDataStore.edit { prefs ->
            val current = readPolicies(prefs[KEY_POLICIES]).toMutableMap()
            current[key] = transform(current[key] ?: BridgeCapabilityPolicy())
            prefs[KEY_POLICIES] = json.encodeToString(
                StoredPolicies(installId = installId, byConnection = current),
            )
        }
    }

    private fun readPolicies(raw: String?): Map<String, BridgeCapabilityPolicy> {
        if (raw.isNullOrBlank()) return emptyMap()
        val stored = runCatching { json.decodeFromString<StoredPolicies>(raw) }.getOrNull()
            ?: return emptyMap()
        if (stored.schemaVersion != BridgeCapabilityPolicy.CURRENT_SCHEMA_VERSION ||
            stored.installId != installId
        ) return emptyMap()
        return stored.byConnection
    }

    private fun String?.normalizedPolicyKey(): String =
        this?.trim()?.takeIf { it.isNotEmpty() } ?: "__unbound__"

    private fun localInstallId(context: Context): String {
        val file = File(context.noBackupFilesDir, "bridge-policy-install-id")
        return runCatching {
            if (file.isFile) {
                file.readText().trim().takeIf { it.isNotEmpty() }
            } else {
                null
            } ?: UUID.randomUUID().toString().also { id ->
                file.parentFile?.mkdirs()
                file.writeText(id)
            }
        }.getOrElse {
            // An unavailable no-backup fence must never make restored grants
            // usable. This process-only value causes every persisted read to
            // mismatch and therefore deny.
            "unavailable-${UUID.randomUUID()}"
        }
    }
}
