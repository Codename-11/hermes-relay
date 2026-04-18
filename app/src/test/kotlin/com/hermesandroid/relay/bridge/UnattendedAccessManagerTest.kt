package com.hermesandroid.relay.bridge

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v0.4.1 unit tests for [UnattendedAccessManager].
 *
 * Covers the state-machine surface that doesn't require a live Android
 * runtime: enabled ↔ disabled transitions, credential-lock detection,
 * and the [WakeOutcome.Disabled] short-circuit when the user hasn't
 * opted in. Live wake-lock acquisition is exercised on-device only —
 * `PowerManager.newWakeLock` returns a system service handle that
 * doesn't behave under mock without rewiring the entire system service
 * pipeline, which is more invasive than the bug surface justifies.
 *
 * The manager is a process-singleton object. Each test drives it back
 * to a known initial state via [resetSingleton], reflecting on the
 * private fields rather than adding a `@VisibleForTesting reset()`
 * to the production class — keeping the production API surface
 * minimal.
 */
class UnattendedAccessManagerTest {

    private lateinit var context: Context
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var wakeLock: PowerManager.WakeLock

    @Before
    fun setUp() {
        // Mock the Android system services. We don't instantiate a real
        // Activity / Application — UnattendedAccessManager only needs
        // PowerManager + KeyguardManager handles, which we can fake.
        context = mockk(relaxed = true)
        powerManager = mockk(relaxed = true)
        keyguardManager = mockk(relaxed = true)
        wakeLock = mockk(relaxed = true)

        every { context.applicationContext } returns context
        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { context.getSystemService(Context.KEYGUARD_SERVICE) } returns keyguardManager
        every { powerManager.newWakeLock(any(), any()) } returns wakeLock
        every { wakeLock.isHeld } returns false
        // Default: no credential lock, keyguard not present.
        every { keyguardManager.isDeviceSecure } returns false
        every { keyguardManager.isKeyguardLocked } returns false

        resetSingleton()
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    // --- enabled ↔ disabled toggling ---

    @Test
    fun setEnabled_true_flipsEnabledFlow() {
        UnattendedAccessManager.initialize(context)
        UnattendedAccessManager.setEnabled(true)
        assertTrue(
            "enabled.value should be true after setEnabled(true)",
            UnattendedAccessManager.enabled.value,
        )
    }

    @Test
    fun setEnabled_false_flipsEnabledFlow() {
        UnattendedAccessManager.initialize(context)
        UnattendedAccessManager.setEnabled(true)
        UnattendedAccessManager.setEnabled(false)
        assertFalse(
            "enabled.value should be false after setEnabled(false)",
            UnattendedAccessManager.enabled.value,
        )
    }

    @Test
    fun setEnabled_default_isDisabled() {
        // No initialize, no setEnabled — default state is disabled.
        assertFalse(
            "enabled.value should default to false on a fresh manager",
            UnattendedAccessManager.enabled.value,
        )
    }

    // --- credential lock detection ---

    @Test
    fun refreshKeyguardState_secureDevice_setsTrue() {
        every { keyguardManager.isDeviceSecure } returns true
        UnattendedAccessManager.initialize(context)
        UnattendedAccessManager.refreshKeyguardState()
        assertTrue(
            "credentialLockDetected should be true when isDeviceSecure=true",
            UnattendedAccessManager.credentialLockDetected.value,
        )
    }

    @Test
    fun refreshKeyguardState_nonSecureDevice_setsFalse() {
        every { keyguardManager.isDeviceSecure } returns false
        UnattendedAccessManager.initialize(context)
        UnattendedAccessManager.refreshKeyguardState()
        assertFalse(
            "credentialLockDetected should be false when isDeviceSecure=false",
            UnattendedAccessManager.credentialLockDetected.value,
        )
    }

    @Test
    fun refreshKeyguardState_threwException_doesNotCrash() {
        every { keyguardManager.isDeviceSecure } throws RuntimeException("boom")
        UnattendedAccessManager.initialize(context)
        // Should not throw — defensive try/catch in the manager.
        UnattendedAccessManager.refreshKeyguardState()
        assertFalse(
            "credentialLockDetected should default to false on exception",
            UnattendedAccessManager.credentialLockDetected.value,
        )
    }

    @Test
    fun setEnabled_refreshesKeyguardState() {
        // Verify the side-effect: setEnabled(true) re-probes the
        // keyguard state so the chip updates immediately on toggle.
        every { keyguardManager.isDeviceSecure } returns true
        UnattendedAccessManager.initialize(context)
        // Ensure the seeded state is false before the toggle.
        every { keyguardManager.isDeviceSecure } returns false
        UnattendedAccessManager.refreshKeyguardState()
        assertFalse(UnattendedAccessManager.credentialLockDetected.value)

        // Now flip the keyguard state and toggle enable. setEnabled
        // should call refreshKeyguardState internally and pick up the
        // new value.
        every { keyguardManager.isDeviceSecure } returns true
        UnattendedAccessManager.setEnabled(true)
        assertTrue(
            "setEnabled should refresh credential-lock state",
            UnattendedAccessManager.credentialLockDetected.value,
        )
    }

    // --- acquireForAction outcomes ---

    @Test
    fun acquireForAction_disabled_returnsDisabled() {
        UnattendedAccessManager.initialize(context)
        UnattendedAccessManager.setEnabled(false)
        val outcome = UnattendedAccessManager.acquireForAction()
        assertEquals(
            "acquireForAction should fast-path to Disabled when feature is off",
            UnattendedAccessManager.WakeOutcome.Disabled,
            outcome,
        )
    }

    @Test
    fun acquireForAction_uninitialized_returnsDisabled() {
        // Skip initialize entirely — manager has no PowerManager handle.
        UnattendedAccessManager.setEnabled(true)
        val outcome = UnattendedAccessManager.acquireForAction()
        assertEquals(
            "acquireForAction should return Disabled when PowerManager is unavailable",
            UnattendedAccessManager.WakeOutcome.Disabled,
            outcome,
        )
    }

    @Test
    fun acquireForAction_enabled_noKeyguard_returnsSuccessNoKeyguardChange() {
        // Enabled, no host activity, no keyguard locked → no dismiss attempt.
        every { keyguardManager.isKeyguardLocked } returns false
        UnattendedAccessManager.initialize(context)
        UnattendedAccessManager.setEnabled(true)
        UnattendedAccessManager.setHostActivity(null)
        val outcome = UnattendedAccessManager.acquireForAction()
        // Without a host activity we can't requestDismissKeyguard, so the
        // outcome is SuccessNoKeyguardChange (the wake lock is acquired,
        // but no keyguard manipulation occurs).
        assertEquals(
            UnattendedAccessManager.WakeOutcome.SuccessNoKeyguardChange,
            outcome,
        )
    }

    @Test
    fun acquireForAction_enabled_keyguardLockedWithCredentialLock_returnsBlocked() {
        // Setup: enabled, host activity present, credential lock set,
        // keyguard currently locked.
        every { keyguardManager.isDeviceSecure } returns true
        every { keyguardManager.isKeyguardLocked } returns true
        UnattendedAccessManager.initialize(context)
        UnattendedAccessManager.setEnabled(true)
        UnattendedAccessManager.setHostActivity(mockk(relaxed = true))

        val outcome = UnattendedAccessManager.acquireForAction()
        assertEquals(
            "Credential-lock-bearing device should report KeyguardBlocked",
            UnattendedAccessManager.WakeOutcome.KeyguardBlocked,
            outcome,
        )
    }

    @Test
    fun acquireForAction_enabled_keyguardLockedNoCredentialLock_returnsSuccess() {
        // None / Swipe lock — keyguard is locked but isDeviceSecure is false,
        // so requestDismissKeyguard will succeed.
        every { keyguardManager.isDeviceSecure } returns false
        every { keyguardManager.isKeyguardLocked } returns true
        UnattendedAccessManager.initialize(context)
        UnattendedAccessManager.setEnabled(true)
        UnattendedAccessManager.setHostActivity(mockk(relaxed = true))

        val outcome = UnattendedAccessManager.acquireForAction()
        assertEquals(
            "Non-secure keyguard should report Success after dismiss",
            UnattendedAccessManager.WakeOutcome.Success,
            outcome,
        )
    }

    // --- release safety ---

    @Test
    fun release_neverInitialized_doesNotCrash() {
        // Should be a clean no-op even without initialize().
        UnattendedAccessManager.release()
        // No assertion — the test passes if no exception was thrown.
    }

    @Test
    fun release_withHeldLock_releasesCleanly() {
        every { wakeLock.isHeld } returns true
        UnattendedAccessManager.initialize(context)
        UnattendedAccessManager.setEnabled(true)
        UnattendedAccessManager.acquireForAction()
        // Should not throw.
        UnattendedAccessManager.release()
    }

    // --- WakeOutcome enum semantics ---

    @Test
    fun wakeOutcome_distinguishesAllFourStates() {
        // Sanity check that the enum has exactly the four entries our
        // contract documents — protects against accidental additions
        // that callers wouldn't handle.
        val values = UnattendedAccessManager.WakeOutcome.entries.toSet()
        assertEquals(4, values.size)
        assertTrue(UnattendedAccessManager.WakeOutcome.Success in values)
        assertTrue(UnattendedAccessManager.WakeOutcome.SuccessNoKeyguardChange in values)
        assertTrue(UnattendedAccessManager.WakeOutcome.KeyguardBlocked in values)
        assertTrue(UnattendedAccessManager.WakeOutcome.Disabled in values)
    }

    // --- internals ---

    /**
     * Drive the singleton back to its initial state between tests.
     * Reflective rather than a production-side reset() because we don't
     * want to expose this surface to non-test callers.
     */
    private fun resetSingleton() {
        try {
            val cls = UnattendedAccessManager::class.java
            cls.getDeclaredField("powerManager").apply {
                isAccessible = true
                set(UnattendedAccessManager, null)
            }
            cls.getDeclaredField("keyguardManager").apply {
                isAccessible = true
                set(UnattendedAccessManager, null)
            }
            cls.getDeclaredField("hostActivity").apply {
                isAccessible = true
                set(UnattendedAccessManager, null)
            }
            cls.getDeclaredField("wakeLock").apply {
                isAccessible = true
                set(UnattendedAccessManager, null)
            }
            cls.getDeclaredField("lockCount").apply {
                isAccessible = true
                setInt(UnattendedAccessManager, 0)
            }
            // StateFlow MutableStateFlow has a `value` setter — drive both
            // back to false via reflection on the property.
            UnattendedAccessManager.setEnabled(false)
            // Force the credential lock flow back to false too. Backing
            // field is MutableStateFlow; we mutate it through the public
            // refreshKeyguardState path with a no-op KM, but at the start
            // of setUp the test hasn't initialized yet so just reach in.
            cls.getDeclaredField("_credentialLockDetected").apply {
                isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val flow = get(UnattendedAccessManager)
                    as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
                flow.value = false
            }
        } catch (_: Throwable) {
            // Best-effort — if reflection fails the next test's setUp
            // will still drive things forward.
        }
    }
}
