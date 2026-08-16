package com.hermesandroid.relay.network

import android.content.Context
import android.os.Looper
import com.hermesandroid.relay.network.relay.ChannelMultiplexer
import com.hermesandroid.relay.network.relay.ConnectionManager
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.HermesApiClient
import com.hermesandroid.relay.viewmodel.connection.UpstreamTransportController
import io.mockk.mockk
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Owner-level coverage for every production ConnectionPool.evictAll() teardown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkShutdownOwnerTest {

    @Test
    fun hermesApiClient_shutdownLeavesMainThread() {
        val teardown = TrackingExecutorService()
        val client = HermesApiClient(
            baseUrl = "https://hermes.example.test",
            apiKey = "test-key",
            okHttpClient = clientWith(teardown),
        )

        client.shutdown()

        teardown.assertShutdownOffMainThread()
    }

    @Test
    fun dashboardApiClient_shutdownLeavesMainThread() {
        val teardown = TrackingExecutorService()
        val client = DashboardApiClient(
            baseUrl = "https://hermes.example.test",
            okHttpClient = clientWith(teardown),
        )

        client.shutdown()

        teardown.assertShutdownOffMainThread()
    }

    @Test
    fun connectionManager_shutdownLeavesMainThread() {
        val teardown = TrackingExecutorService()
        val manager = ConnectionManager(
            multiplexer = ChannelMultiplexer(),
            okHttpClientFactory = { clientWith(teardown) },
        )

        manager.shutdown()

        teardown.assertShutdownOffMainThread()
    }

    @Test
    fun upstreamTransportController_connectionSwitchResetLeavesMainThread() {
        val dashboardUrl = "https://hermes.example.test"
        val teardown = TrackingExecutorService()
        val controller = UpstreamTransportController(
            context = mockk<Context>(relaxed = true),
            activeConnectionIdProvider = { null },
            dashboardUrlProvider = { dashboardUrl },
            gatewayKeepAliveProvider = { false },
            dashboardHttpClientFactory = { _, _ -> clientWith(teardown) },
        )

        controller.dashboardHttpClientForActive(dashboardUrl)
        controller.resetGatewayForConnectionSwitch()

        teardown.assertShutdownOffMainThread()
    }

    private fun clientWith(executor: ExecutorService): OkHttpClient =
        OkHttpClient.Builder()
            .dispatcher(Dispatcher(executor))
            .build()

    private class TrackingExecutorService : AbstractExecutorService() {
        private val shutdown = AtomicBoolean(false)
        private val shutdownLatch = CountDownLatch(1)
        private val shutdownThread = AtomicReference<Thread>()

        override fun shutdown() {
            shutdownThread.compareAndSet(null, Thread.currentThread())
            shutdown.set(true)
            shutdownLatch.countDown()
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown()
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown.get()

        override fun isTerminated(): Boolean = shutdown.get()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
            shutdownLatch.await(timeout, unit)

        override fun execute(command: Runnable) = command.run()

        fun assertShutdownOffMainThread() {
            assertSame(Looper.myLooper(), Looper.getMainLooper())
            assertTrue("owner did not shut its OkHttp dispatcher down", shutdownLatch.await(5, TimeUnit.SECONDS))
            assertNotSame(
                "owner performed OkHttp teardown on the main thread",
                Looper.getMainLooper().thread,
                shutdownThread.get(),
            )
        }
    }
}
