package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.network.upstream.DashboardAuthSession
import com.hermesandroid.relay.network.upstream.DashboardStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Summary models for the Manage tab's section payloads. These used to be
 * private to DashboardManagementScreen.kt; they moved here (internal +
 * @Serializable) so [DashboardManageDiskCache] can persist Loaded cache
 * entries across process death without a parallel DTO layer.
 */
@Serializable
internal data class DashboardSummaryItem(
    val id: String = "",
    val title: String,
    val subtitle: String? = null,
    val meta: String? = null,
    val profile: String? = null,
    val actions: List<DashboardItemAction> = emptyList(),
)

@Serializable
internal data class DashboardItemAction(
    val label: String,
    val kind: DashboardActionKind,
    val destructive: Boolean = false,
)

internal enum class DashboardActionKind {
    EnableSkill,
    DisableSkill,
    ViewCronRuns,
    PauseCron,
    ResumeCron,
    TriggerCron,
    DeleteCron,
    EnableMcp,
    DisableMcp,
    TestMcp,
    AuthenticateMcp,
    RemoveMcp,
    InstallMcpCatalog,
    ViewProfileSoul,
    ActivateProfile,
    DeleteProfile,
    EditCustomEndpoint,
    ValidateCustomEndpoint,
    ActivateCustomEndpoint,
    DeleteCustomEndpoint,

    // Input-backed kinds — intercepted before runAction and routed to a
    // text-input or model-picker dialog instead of firing immediately.
    SetEnvKey,
    EditProfileDescription,
    SetProfileModel,
    EditProfileSoul,

    // Direct env actions.
    RevealEnvKey,
    ClearEnvKey,
}

/**
 * Disk mirror of one Loaded cache entry. `fetchedAtMillis` survives the
 * round trip on purpose: hydrated entries are older than the
 * stale-while-revalidate window, so the existing refresh path treats them
 * as "render now, re-fetch quietly" with zero extra logic.
 */
@Serializable
internal data class PersistedDashboardPayload(
    val status: DashboardStatus? = null,
    val session: DashboardAuthSession? = null,
    val items: List<DashboardSummaryItem> = emptyList(),
    val rawSummary: String = "",
    val fetchedAtMillis: Long = 0L,
)

@Serializable
internal data class DashboardManageCacheFile(
    val version: Int = DashboardManageDiskCache.SCHEMA_VERSION,
    val entries: Map<String, PersistedDashboardPayload> = emptyMap(),
)

/**
 * Plain-JSON disk persistence for the Manage tab's payload cache, so a cold
 * app start (new process) renders the last-seen dashboard data instantly
 * instead of skeletons.
 *
 * Deliberately a flat file under `cacheDir` and NOT EncryptedSharedPrefs:
 * `cacheDir` is already app-private, the payload carries no credentials
 * (dashboard cookies live in their own encrypted store), and every
 * EncryptedSharedPreferences construction pays a multi-second process-global
 * Keystore lock on StrongBox devices — the exact mechanism behind the
 * frozen-sphere startup incident. The OS may evict `cacheDir` under disk
 * pressure; that simply degrades back to the skeleton-then-fetch path.
 *
 * Keys are the in-memory cache's `"connectionId|dashboardUrl|sectionPath"`
 * strings, so LAN- and Tailscale-keyed entries coexist and a remote cold
 * start hydrates the route it will actually use.
 */
internal object DashboardManageDiskCache {
    const val SCHEMA_VERSION = 1
    private const val FILE_NAME = "dashboard-manage-cache.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Serializes writers; reads are lock-free (rename makes writes atomic). */
    private val writeMutex = Mutex()

    fun cacheFileFor(directory: File): File = File(directory, FILE_NAME)

    fun encode(entries: Map<String, PersistedDashboardPayload>): String =
        json.encodeToString(
            DashboardManageCacheFile.serializer(),
            DashboardManageCacheFile(version = SCHEMA_VERSION, entries = entries),
        )

    /** Corrupt, unreadable, or version-mismatched content decodes to empty. */
    fun decode(text: String): Map<String, PersistedDashboardPayload> = try {
        val parsed = json.decodeFromString(DashboardManageCacheFile.serializer(), text)
        if (parsed.version == SCHEMA_VERSION) parsed.entries else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    suspend fun read(directory: File): Map<String, PersistedDashboardPayload> =
        withContext(Dispatchers.IO) {
            val file = cacheFileFor(directory)
            if (!file.isFile) return@withContext emptyMap()
            try {
                decode(file.readText())
            } catch (_: Exception) {
                emptyMap()
            }
        }

    suspend fun write(directory: File, entries: Map<String, PersistedDashboardPayload>) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val file = cacheFileFor(directory)
                    val tmp = File(directory, "$FILE_NAME.tmp")
                    tmp.writeText(encode(entries))
                    if (!tmp.renameTo(file)) {
                        // Windows-style rename-over-existing failure — fall
                        // back to delete + rename (still app-private dir).
                        file.delete()
                        tmp.renameTo(file)
                    }
                } catch (_: Exception) {
                    // Best-effort cache — never let a disk hiccup surface.
                }
            }
        }
    }

    suspend fun clear(directory: File) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    cacheFileFor(directory).delete()
                } catch (_: Exception) {
                    // Best-effort.
                }
            }
        }
    }
}
