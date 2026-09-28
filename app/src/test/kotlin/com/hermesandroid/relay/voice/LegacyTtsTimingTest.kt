package com.hermesandroid.relay.voice

import com.hermesandroid.relay.viewmodel.runTtsPlayWorker
import com.hermesandroid.relay.viewmodel.runTtsSynthWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LegacyTtsTimingTest {
    @Test
    fun `new response does not count time since the preceding response as starvation`() = runTest {
        val audio = Channel<File>(2)
        var turn = 0L
        val waits = mutableListOf<Long>()
        val worker = launch {
            runTtsPlayWorker(audio, {}, { delay(100) }, {}, mutableSetOf(), {},
                elapsedRealtimeMs = { currentTime }, onQueueWait = { waits += it }, turnId = { turn })
        }
        audio.send(File("first"))
        delay(200)
        turn++
        audio.send(File("next-response"))
        audio.close()
        worker.join()
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun `ready followup has no queue wait but still pays player startup`() = runTest {
        measure(synthMs = 100, speechMs = 1000, expectedWait = 0)
    }

    @Test
    fun `short speech exposes synthesis starvation separately from player startup`() = runTest {
        measure(synthMs = 500, speechMs = 100, expectedWait = 350)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.measure(
        synthMs: Long,
        speechMs: Long,
        expectedWait: Long,
    ) {
        val sentences = Channel<String>(Channel.UNLIMITED)
        val audio = Channel<File>(2)
        val pending = mutableSetOf<File>()
        val waits = mutableListOf<Long>()
        val starts = mutableListOf<Long>()
        val finishes = mutableListOf<Long>()
        val synth = launch {
            runTtsSynthWorker(sentences, audio, { text ->
                delay(synthMs)
                Result.success(File(text))
            }, pending, {})
        }
        val play = launch {
            runTtsPlayWorker(
                input = audio,
                play = {},
                awaitCompletion = {
                    // Model a fixed player restart independently of synthesis.
                    delay(50)
                    starts += currentTime
                    delay(speechMs)
                    finishes += currentTime
                },
                onFileReady = {}, pendingFiles = pending, onQueueDrained = {},
                elapsedRealtimeMs = { currentTime }, onQueueWait = { waits += it },
            )
        }
        sentences.send("first")
        sentences.send("second")
        sentences.close()
        synth.join()
        play.join()
        assertEquals(listOf(expectedWait), waits)
        assertEquals(expectedWait + 50, starts[1] - finishes[0])
        assertEquals(emptySet<File>(), pending)
    }
}
