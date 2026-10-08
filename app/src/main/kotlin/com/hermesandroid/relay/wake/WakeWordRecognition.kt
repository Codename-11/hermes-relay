package com.hermesandroid.relay.wake

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One reader's resources; stop unblocks read, close runs after read returns. */
internal interface WakeWordAudio : AutoCloseable {
    suspend fun detect(onListening: () -> Unit, onSamples: (ShortArray, Int) -> Unit): Boolean
    fun stop()
}

/**
 * Main-thread lifecycle shared by both wake services. Each attempt owns its
 * resources and lease through teardown. Cancelled starts and queued callbacks
 * cannot act on a replacement reader or a destroyed service.
 */
internal class WakeWordRecognition(
    private val scope: CoroutineScope,
    private val readerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var generation = 0L
    private var job: Job? = null
    private var readerJob: Job? = null
    private var audio: WakeWordAudio? = null
    val isActive: Boolean get() = job?.isActive == true

    fun stop(): Job? {
        generation++
        audio?.stop()
        job?.cancel()
        readerJob?.cancel()
        return readerJob
    }

    fun start(
        canListen: () -> Boolean,
        createAudio: () -> WakeWordAudio,
        onListening: () -> Unit,
        onSamples: (ShortArray, Int) -> Unit = { _, _ -> },
        onDetected: () -> Unit,
        onBusy: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val previous = stop()
        val expected = generation
        val next = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            if (generation != expected || !canListen()) return@launch
            val lease = MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.WakeWord)
            if (lease == null) {
                onBusy()
                return@launch
            }
            readerJob = currentCoroutineContext().job
            var reader: WakeWordAudio? = null
            val detected = try {
                val owned = createAudio()
                reader = owned
                audio = owned
                withContext(readerDispatcher) {
                    owned.detect(
                        onListening = {
                            scope.launch {
                                if (generation == expected && audio === owned && canListen()) onListening()
                            }
                        },
                        onSamples = onSamples,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (generation == expected && canListen()) onError(failure)
                false
            } finally {
                withContext(NonCancellable + readerDispatcher) {
                    try { reader?.close() } finally {
                        MicrophoneOwnershipCoordinator.release(lease)
                    }
                }
                if (audio === reader) audio = null
            }
            if (generation == expected && canListen() && detected) onDetected()
        }
        job = next
        next.start()
    }
}
