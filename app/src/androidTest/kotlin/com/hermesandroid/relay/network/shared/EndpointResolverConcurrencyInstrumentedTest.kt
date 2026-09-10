package com.hermesandroid.relay.network.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class EndpointResolverConcurrencyInstrumentedTest {

    @Test
    fun probeCompletionRacingInvalidation_staysCrashFreeOnAndroidCollections() = runBlocking {
        repeat(25) { iteration ->
            val candidateCount = 8
            val requestsStarted = CountDownLatch(candidateCount)
            val releaseRequests = CountDownLatch(1)
            val raceGate = CountDownLatch(1)
            val requestSequence = AtomicInteger(0)
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    if (requestSequence.incrementAndGet() <= candidateCount) {
                        requestsStarted.countDown()
                        releaseRequests.await(5, TimeUnit.SECONDS)
                        throw InterruptedIOException("instrumented invalidation race")
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{}".toResponseBody())
                        .build()
                }
                .build()
            val resolver = EndpointResolver(client)
            val candidates = (1..candidateCount).map { index ->
                EndpointCandidate(
                    role = "instrumented-$iteration-$index",
                    priority = 0,
                    api = ApiEndpoint(host = "127.0.0.1", port = 1, tls = false),
                )
            }

            try {
                val staleResolve = async(start = CoroutineStart.UNDISPATCHED) {
                    resolver.resolve(candidates, EndpointSurface.Api)
                }
                assertTrue(requestsStarted.await(5, TimeUnit.SECONDS))

                val invalidation = async(Dispatchers.Default) {
                    raceGate.await(5, TimeUnit.SECONDS)
                    resolver.clearCache()
                }
                val completions = async(Dispatchers.Default) {
                    raceGate.await(5, TimeUnit.SECONDS)
                    releaseRequests.countDown()
                }
                raceGate.countDown()

                withTimeout(2_000L) {
                    invalidation.await()
                    completions.await()
                    staleResolve.await()
                }
                assertTrue(resolver.cacheSnapshot().isEmpty())

                resolver.clearCache()
                val freshWinner = withTimeout(2_000L) {
                    resolver.resolve(listOf(candidates.first()), EndpointSurface.Api)
                }
                assertEquals(candidates.first(), freshWinner)
                assertTrue(
                    resolver.probeOutcomes.value.getValue(
                        EndpointResolver.cacheKey(candidates.first(), EndpointSurface.Api),
                    ).reachable,
                )
            } finally {
                raceGate.countDown()
                releaseRequests.countDown()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
