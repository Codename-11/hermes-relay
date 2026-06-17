package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.network.upstream.DashboardAuthProvider
import com.hermesandroid.relay.network.upstream.DashboardAuthSession
import com.hermesandroid.relay.network.upstream.DashboardStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DashboardManageDiskCacheTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File =
        createTempDirectory("manage-cache-test").toFile().also { tempDirs.add(it) }

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun sampleEntries(): Map<String, PersistedDashboardPayload> = mapOf(
        "conn-1|http://100.71.8.56:9119|/api/skills" to PersistedDashboardPayload(
            status = DashboardStatus(
                authRequired = true,
                authProviders = listOf("password"),
                authProviderDetails = listOf(
                    DashboardAuthProvider(
                        name = "password",
                        displayName = "Password",
                        supportsPassword = true,
                    ),
                ),
                version = "0.6.0",
            ),
            session = DashboardAuthSession(
                authenticated = true,
                username = "bailey",
                provider = "password",
            ),
            items = listOf(
                DashboardSummaryItem(
                    id = "skill-1",
                    title = "hermes-relay-pair",
                    subtitle = "Pairing helper",
                    meta = "enabled",
                    actions = listOf(
                        DashboardItemAction(
                            label = "Disable",
                            kind = DashboardActionKind.DisableSkill,
                        ),
                        DashboardItemAction(
                            label = "Delete",
                            kind = DashboardActionKind.DeleteCron,
                            destructive = true,
                        ),
                    ),
                ),
            ),
            rawSummary = "1 skill",
            fetchedAtMillis = 1_750_000_000_000L,
        ),
        "conn-1|http://192.168.1.20:9119|/api/env" to PersistedDashboardPayload(
            status = DashboardStatus(authRequired = false),
            session = null,
            items = emptyList(),
            rawSummary = "empty",
            fetchedAtMillis = 1_750_000_100_000L,
        ),
    )

    @Test
    fun `encode then decode round-trips every field`() {
        val entries = sampleEntries()
        val decoded = DashboardManageDiskCache.decode(DashboardManageDiskCache.encode(entries))
        assertEquals(entries, decoded)
    }

    @Test
    fun `decode rejects corrupt json as empty`() {
        assertTrue(DashboardManageDiskCache.decode("not json {").isEmpty())
        assertTrue(DashboardManageDiskCache.decode("").isEmpty())
    }

    @Test
    fun `decode rejects future schema versions as empty`() {
        val futureVersion = DashboardManageDiskCache
            .encode(sampleEntries())
            .replace(
                "\"version\":${DashboardManageDiskCache.SCHEMA_VERSION}",
                "\"version\":${DashboardManageDiskCache.SCHEMA_VERSION + 1}",
            )
        assertTrue(DashboardManageDiskCache.decode(futureVersion).isEmpty())
    }

    @Test
    fun `decode tolerates unknown keys for forward compatibility`() {
        val withExtra = DashboardManageDiskCache
            .encode(sampleEntries())
            .replaceFirst("{", "{\"futureField\":42,")
        assertEquals(sampleEntries(), DashboardManageDiskCache.decode(withExtra))
    }

    @Test
    fun `write then read round-trips through the filesystem`() = runBlocking {
        val dir = tempDir()
        val entries = sampleEntries()
        DashboardManageDiskCache.write(dir, entries)
        assertTrue(DashboardManageDiskCache.cacheFileFor(dir).isFile)
        assertEquals(entries, DashboardManageDiskCache.read(dir))
    }

    @Test
    fun `read of a missing file is empty`() = runBlocking {
        assertTrue(DashboardManageDiskCache.read(tempDir()).isEmpty())
    }

    @Test
    fun `clear deletes the cache file`() = runBlocking {
        val dir = tempDir()
        DashboardManageDiskCache.write(dir, sampleEntries())
        DashboardManageDiskCache.clear(dir)
        assertFalse(DashboardManageDiskCache.cacheFileFor(dir).exists())
        assertTrue(DashboardManageDiskCache.read(dir).isEmpty())
    }

    @Test
    fun `write replaces previous content atomically`() = runBlocking {
        val dir = tempDir()
        DashboardManageDiskCache.write(dir, sampleEntries())
        val smaller = mapOf(
            "conn-2|http://host:9119|/api/profiles" to PersistedDashboardPayload(
                rawSummary = "replacement",
                fetchedAtMillis = 5L,
            ),
        )
        DashboardManageDiskCache.write(dir, smaller)
        assertEquals(smaller, DashboardManageDiskCache.read(dir))
    }
}
