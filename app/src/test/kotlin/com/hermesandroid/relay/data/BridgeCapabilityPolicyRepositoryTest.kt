package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.bridge.BridgeCapability
import com.hermesandroid.relay.bridge.BridgeCapabilityPolicy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BridgeCapabilityPolicyRepositoryTest {
    private lateinit var context: Context
    private lateinit var fence: File

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.relayDataStore.edit { it.clear() }
        fence = File(context.noBackupFilesDir, "bridge-policy-install-id")
        fence.delete()
    }

    @After
    fun tearDown() = runTest {
        context.relayDataStore.edit { it.clear() }
        fence.delete()
    }

    @Test
    fun grantsDefaultDeniedAndStayConnectionScoped() = runTest {
        val repo = BridgeCapabilityPolicyRepository(context)
        assertFalse(repo.snapshot("home").allows(BridgeCapability.CONTACTS_READ, 0L))

        repo.setPermanent("home", BridgeCapability.CONTACTS_READ, true)

        assertTrue(repo.snapshot("home").allows(BridgeCapability.CONTACTS_READ, 0L))
        assertFalse(repo.snapshot("work").allows(BridgeCapability.CONTACTS_READ, 0L))
    }

    @Test
    fun timedGrantUsesAbsoluteExpiryAndCanBeRevokedAsAGroup() = runTest {
        val repo = BridgeCapabilityPolicyRepository(context)
        repo.grantTimed("home", BridgeCapability.SCREEN_INSPECTION, 1_001L)
        repo.grantTimed("home", BridgeCapability.SCREEN_CONTROL, 1_001L)

        assertTrue(repo.snapshot("home").allows(BridgeCapability.SCREEN_CONTROL, 1_000L))
        assertFalse(repo.snapshot("home").allows(BridgeCapability.SCREEN_CONTROL, 1_001L))

        repo.revokeTimed("home")
        assertFalse(repo.snapshot("home").allows(BridgeCapability.SCREEN_INSPECTION, 0L))
    }

    @Test
    fun removingConnectionDeletesItsAuthority() = runTest {
        val repo = BridgeCapabilityPolicyRepository(context)
        repo.setPermanent("home", BridgeCapability.CLIPBOARD_READ, true)

        repo.clearConnection("home")

        assertFalse(repo.snapshot("home").allows(BridgeCapability.CLIPBOARD_READ, 0L))
    }

    @Test
    fun batchReplacementIsAtomicAndDoesNotCrossConnections() = runTest {
        val repo = BridgeCapabilityPolicyRepository(context)
        repo.replacePermanent(
            "home",
            setOf(BridgeCapability.DEVICE_INFO, BridgeCapability.CLIPBOARD_READ),
        )
        repo.replaceTimed(
            "home",
            setOf(BridgeCapability.SCREEN_INSPECTION, BridgeCapability.SCREEN_CONTROL),
            expiresAtMs = 5_000L,
        )

        val home = repo.snapshot("home")
        assertTrue(BridgeCapability.DEVICE_INFO in home.permanentGrants)
        assertEquals(2, home.timedExpiriesMs.size)
        assertFalse(repo.snapshot("work").hasAnyGrantForTest(0L))
    }

    @Test
    fun idleRefreshPreservesUnlimitedGrants() = runTest {
        val repo = BridgeCapabilityPolicyRepository(context)
        repo.replaceTimed(
            "home",
            setOf(BridgeCapability.SCREEN_CONTROL),
            BridgeCapabilityPolicy.NEVER_EXPIRES_AT_MS,
        )

        repo.refreshActiveTimed("home", expiresAtMs = 30_000L)

        val policy = repo.snapshot("home")
        assertEquals(
            BridgeCapabilityPolicy.NEVER_EXPIRES_AT_MS,
            policy.timedExpiriesMs[BridgeCapability.SCREEN_CONTROL],
        )
    }

    @Test
    fun idleRefreshDoesNotReviveExpiredSiblingGrant() = runTest {
        val repo = BridgeCapabilityPolicyRepository(context)
        repo.replaceTimed(
            "home",
            setOf(BridgeCapability.SCREEN_INSPECTION),
            expiresAtMs = 1L,
        )

        repo.refreshActiveTimed("home", expiresAtMs = Long.MAX_VALUE - 1)

        assertFalse(
            repo.snapshot("home").allows(BridgeCapability.SCREEN_INSPECTION, System.currentTimeMillis()),
        )
    }

    @Test
    fun restoredDataCannotCrossTheNoBackupInstallFence() = runTest {
        val originalInstall = BridgeCapabilityPolicyRepository(context)
        originalInstall.setPermanent("home", BridgeCapability.CONTACTS_READ, true)
        assertTrue(originalInstall.snapshot("home").allows(BridgeCapability.CONTACTS_READ, 0L))

        // Simulate DataStore restoration onto a new install: backed-up prefs
        // remain, but Android's no-backup installation marker does not.
        assertTrue(fence.delete())
        val restoredInstall = BridgeCapabilityPolicyRepository(context)

        assertFalse(restoredInstall.snapshot("home").allows(BridgeCapability.CONTACTS_READ, 0L))
    }
}

private fun com.hermesandroid.relay.bridge.BridgeCapabilityPolicy.hasAnyGrantForTest(
    nowMs: Long,
): Boolean = permanentGrants.isNotEmpty() || timedExpiriesMs.values.any { it > nowMs }
