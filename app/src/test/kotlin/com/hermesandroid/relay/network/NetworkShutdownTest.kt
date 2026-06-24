package com.hermesandroid.relay.network

import android.os.Looper
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Guards the fix for the `NetworkOnMainThreadException` crash (issues #70 /
 * #118 / #124): client `shutdown()` reaches `ConnectionPool.evictAll()`, which
 * closes live TLS sockets with a synchronous network write. The teardown block
 * must never execute on the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkShutdownTest {

    @Test
    fun whenCalledOnMainThread_runsTeardownOffTheMainThread() {
        // Robolectric drives the test body on the main looper — the same place
        // a viewModelScope (Dispatchers.Main.immediate) coroutine resumes and
        // shuts a dashboard/API client down in a `finally` block.
        assertSame(Looper.myLooper(), Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread

        val ranOn = AtomicReference<Thread>()
        val latch = CountDownLatch(1)
        shutdownOffMainThread("test-shutdown") {
            ranOn.set(Thread.currentThread())
            latch.countDown()
        }

        assertTrue("teardown block never ran", latch.await(5, TimeUnit.SECONDS))
        assertNotSame(
            "evictAll must not run on the main thread",
            mainThread,
            ranOn.get(),
        )
    }

    @Test
    fun whenCalledOffMainThread_runsTeardownInline() {
        val ranOn = AtomicReference<Thread>()
        val latch = CountDownLatch(1)
        val worker = Thread {
            shutdownOffMainThread("test-shutdown") { ranOn.set(Thread.currentThread()) }
            latch.countDown()
        }
        worker.start()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // Off the main thread the block runs inline (no extra hop), preserving
        // the blocking awaitTermination semantics for callers already off main.
        assertSame(worker, ranOn.get())
    }
}
