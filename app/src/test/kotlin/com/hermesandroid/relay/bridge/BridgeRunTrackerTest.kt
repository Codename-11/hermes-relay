package com.hermesandroid.relay.bridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BridgeRunTrackerTest {
    @Before
    fun setUp() {
        BridgeRunTracker.reset()
    }

    @After
    fun tearDown() {
        BridgeRunTracker.reset()
        BridgeRunTracker.configureIdleTimeout(12_000L)
    }

    @Test
    fun commandInactivityDoesNotCompleteRun() {
        val returned = CountDownLatch(1)
        val returns = AtomicInteger()
        // Wait beyond the released 12-second threshold. Do not rely on the
        // compatibility timeout setter, which is a no-op after the repair.
        BridgeRunTracker.configureIdleTimeout(12_000L)
        BridgeRunTracker.registerAutoReturnCallback {
            returns.incrementAndGet()
            returned.countDown()
        }
        BridgeRunTracker.markForegroundChanged()
        BridgeRunTracker.onBridgeCommandActivity()

        assertFalse("A gap between commands is not run completion", returned.await(13, TimeUnit.SECONDS))
        assertEquals(0, returns.get())

        // Inactivity must not discard the pending real completion either.
        BridgeRunTracker.notifyRunCompleted()
        assertEquals(1, returns.get())
        BridgeRunTracker.notifyRunCompleted()
        assertEquals(1, returns.get())
    }

    @Test
    fun completionWithoutForegroundChangeDoesNotReturn() {
        var returns = 0
        BridgeRunTracker.registerAutoReturnCallback { returns++ }
        BridgeRunTracker.onBridgeCommandActivity()
        BridgeRunTracker.notifyRunCompleted()
        assertEquals(0, returns)
    }

    @Test
    fun explicitReturnClearsPendingCompletion() {
        var returns = 0
        BridgeRunTracker.registerAutoReturnCallback { returns++ }
        BridgeRunTracker.markForegroundChanged()
        BridgeRunTracker.markReturnedToHermes()
        BridgeRunTracker.notifyRunCompleted()
        assertEquals(0, returns)

        BridgeRunTracker.markForegroundChanged()
        BridgeRunTracker.notifyRunCompleted()
        assertEquals(1, returns)
    }

    @Test
    fun completionWithoutCallbackDoesNotLeakIntoNextRun() {
        BridgeRunTracker.markForegroundChanged()
        BridgeRunTracker.notifyRunCompleted()
        var returns = 0
        BridgeRunTracker.registerAutoReturnCallback { returns++ }
        BridgeRunTracker.notifyRunCompleted()
        assertEquals(0, returns)
    }

    @Test
    fun callbackFailureDoesNotRepeatCompletedReturn() {
        var returns = 0
        BridgeRunTracker.registerAutoReturnCallback {
            returns++
            error("return failed")
        }
        BridgeRunTracker.markForegroundChanged()
        BridgeRunTracker.notifyRunCompleted()
        BridgeRunTracker.notifyRunCompleted()
        assertEquals(1, returns)
    }
}
