package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.bridge.BridgeCapability
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
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
