package com.hermesandroid.relay.voice

import com.hermesandroid.relay.viewmodel.runTtsPlayWorker
import com.hermesandroid.relay.viewmodel.runTtsSynthWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * V4 coverage (voice-quality-pass, 2026-04-16): exercises the two-worker
 * TTS prefetch pipeline in [runTtsSynthWorker] + [runTtsPlayWorker].
 *
 * The workers live as top-level `internal` suspend funcs so these tests
 * can drive them against fakes inside a [runTest]/[TestScope] harness
 * without pulling in `Application`, `Context`, or the real
 * [com.hermesandroid.relay.audio.VoicePlayer].
 *
 * Timing is asserted against [TestScope.currentTime] (the virtual-time
 * clock), so `delay` inside the fakes advances deterministically. The
 * critical invariant is **parallelism**: sentence N+1's synth overlaps
 * sentence N's playback, so total wall time ≈ `firstSynth + N * playback`
 * rather than `N * (synth + playback)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Synth delay that's large enough to make the serial vs pipelined
    // distinction unambiguous. 200 ms synth + 100 ms play chosen to match
    // the acceptance criteria in the voice quality pass plan (V4).
    private val synthDelayMs = 200L
    private val playDelayMs = 100L

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun newTempMp3(tag: String): File {
        val f = tempFolder.newFile("voice_tts_$tag.mp3")
        // Write a non-zero byte so `File.length()` is accurate if any
        // future assertion checks it.
        f.writeText("x")
        return f
    }

    /**
     * Launch the synth + play workers on [scope] under a [supervisorScope]
     * the way production does, returning both the parent [Job] and the
     * set of "pending" files (synthesized-but-unplayed) so the test can
     * assert cancellation cleanup.
     */
    private data class RunningPipeline(
        val job: Job,
        val pendingFiles: MutableSet<File>,
        val synthCallCount: () -> Int,
        val playedFiles: List<File>,
    )

    private fun TestScope.launchPipeline(
        sentences: Channel<String>,
        audioQueue: Channel<File>,
        synth: suspend (String) -> Result<File>,
        onSynthError: (Throwable?) -> Unit = { /* ignore */ },
        // play() mirrors VoicePlayer.play(): non-blocking append to the
        // underlying player queue. Default is an instant no-op; tests that
        // want to simulate work override via the awaitPlaybackDelay param.
        onPlay: (File) -> Unit = { /* non-blocking */ },
        // awaitCompletion() mirrors VoicePlayer.awaitCompletion(): suspend
        // for the duration of playback, then return when the queue has
        // drained. Default models a single-file playback of [playDelayMs].
        awaitPlaybackDelay: Long = playDelayMs,
    ): RunningPipeline {
        val pending = Collections.synchronizedSet(mutableSetOf<File>())
        val played = Collections.synchronizedList(mutableListOf<File>())
        var synthCalls = 0
        val countingSynth: suspend (String) -> Result<File> = { s ->
            synthCalls++
            synth(s)
        }
        val job = launch {
            supervisorScope {
                launch {
                    runTtsSynthWorker(
                        sentences = sentences,
                        output = audioQueue,
                        synthesize = countingSynth,
                        pendingFiles = pending,
                        onSynthError = onSynthError,
                    )
                }
                launch {
                    runTtsPlayWorker(
                        input = audioQueue,
                        play = { file ->
                            played.add(file)
                            onPlay(file)
                        },
                        awaitCompletion = { delay(awaitPlaybackDelay) },
                        onFileReady = { /* no-op in tests */ },
                        pendingFiles = pending,
                        onQueueDrained = { /* no-op */ },
                    )
                }
            }
        }
        return RunningPipeline(
            job = job,
            pendingFiles = pending,
            synthCallCount = { synthCalls },
            playedFiles = played,
        )
    }

    // ── Pipelining / parallelism ─────────────────────────────────────────

    @Test
    fun `three sentences pipeline in parallel, total time dominated by playback`() = runTest {
        val sentences = Channel<String>(Channel.UNLIMITED)
        val audioQueue = Channel<File>(capacity = 2)

        val synth: suspend (String) -> Result<File> = { text ->
            delay(synthDelayMs)
            Result.success(newTempMp3(tag = text.filter { it.isLetterOrDigit() }))
        }

        val t0 = currentTime
        val pipeline = launchPipeline(
            sentences = sentences,
            audioQueue = audioQueue,
            synth = synth,
        )

        sentences.send("one")
        sentences.send("two")
        sentences.send("three")
        sentences.close()

        pipeline.job.join()
        val elapsed = currentTime - t0

        // Serial baseline: 3*(synth+play) = 3*(200+100) = 900 ms.
        // Pipelined (200 ms synth > 100 ms play, so synth can't fully hide):
        //   t=0   synth(1) starts
        //   t=200 play(1) starts; synth(2) starts
        //   t=300 play(1) done; tryReceive empty; block (synth(2) still running)
        //   t=400 synth(2) done; play(2) starts; synth(3) starts
        //   t=500 play(2) done; block (synth(3) running)
        //   t=600 synth(3) done; play(3) starts
        //   t=700 play(3) done; input closed; exit
        // Expected total = 700 ms — 200 ms faster than the serial 900 ms.
        //
        // Bound is `<= 800` to leave a full synth-slot of headroom for
        // test-dispatcher jitter while still catching a regression back
        // to the serial loop (which would land at ~900 ms).
        assertTrue(
            "Expected pipelined execution (<=800 ms), got ${elapsed}ms — " +
                "regression to serial synth→play→synth loop?",
            elapsed <= 800L,
        )
        // Sanity floor: at least firstSynth + 3*playback = 500 ms.
        assertTrue(
            "Expected at least firstSynth + 3*playback ($synthDelayMs + 3*$playDelayMs = 500) ms, got ${elapsed}ms",
            elapsed >= synthDelayMs + 3 * playDelayMs,
        )
        assertEquals(3, pipeline.playedFiles.size)
        assertEquals(3, pipeline.synthCallCount())
        assertTrue(
            "No files should remain pending after normal drain",
            pipeline.pendingFiles.isEmpty(),
        )
    }

    // ── Cancellation cleanup ─────────────────────────────────────────────

    @Test
    fun `cancellation deletes all synthesized-but-unplayed files`() = runTest {
        val sentences = Channel<String>(Channel.UNLIMITED)
        val audioQueue = Channel<File>(capacity = 2)

        // Long playback (2 s) so files accumulate in the pipeline before
        // the cancel fires. Short synth (100 ms) lets the capacity-2 buffer
        // fill promptly.
        val allCreated = Collections.synchronizedList(mutableListOf<File>())
        val synth: suspend (String) -> Result<File> = { text ->
            delay(100)
            val f = newTempMp3(tag = text.filter { it.isLetterOrDigit() })
            allCreated.add(f)
            Result.success(f)
        }

        val pipeline = launchPipeline(
            sentences = sentences,
            audioQueue = audioQueue,
            synth = synth,
            // Long awaitCompletion so a file stays in "currently playing"
            // for the duration of the test — this guarantees the backpressure
            // buffer fills and the synth worker parks on `send`.
            awaitPlaybackDelay = 2000L,
        )

        repeat(5) { sentences.send("sentence-$it") }
        // Do NOT close sentences — we're simulating a user interrupt mid-turn.

        advanceTimeBy(500L)

        // Cancel the pipeline and simulate the deletePendingSynthFiles()
        // cleanup path that VoiceViewModel triggers from interruptSpeaking.
        pipeline.job.cancel()
        pipeline.job.join()

        val pendingSnapshot: List<File> = synchronized(pipeline.pendingFiles) {
            val copy = pipeline.pendingFiles.toList()
            pipeline.pendingFiles.clear()
            copy
        }
        for (f in pendingSnapshot) {
            f.delete()
        }

        advanceUntilIdle()

        // Every file that was synthesized either (a) reached the player
        // (was removed from pendingFiles by the play worker) and got
        // played/completed before cancel, or (b) was cleaned up by the
        // pendingFiles drain. Neither path leaves a file on disk unless
        // it's one the play worker is "currently playing" — in which case
        // we consider it owned by the player (VoicePlayer.stop() would
        // clear ExoPlayer's queue; the actual file on disk is reclaimable
        // by the LRU). So we assert: any file still on disk must NOT be
        // inside pendingFiles.
        assertTrue(
            "pendingFiles must be empty after cleanup",
            pipeline.pendingFiles.isEmpty(),
        )

        // Files that were played (handed to play()) are expected to still
        // exist — that's the LRU's territory. Files that reached pending
        // but never played should have been deleted by the snapshot drain.
        val leaked = allCreated.filter { it.exists() && !pipeline.playedFiles.contains(it) }
        assertTrue(
            "Leaked files after cancellation: $leaked",
            leaked.isEmpty(),
        )
    }

    // ── Synth-failure resilience ─────────────────────────────────────────

    @Test
    fun `middle synth failure does not stall surrounding sentences`() = runTest {
        val sentences = Channel<String>(Channel.UNLIMITED)
        val audioQueue = Channel<File>(capacity = 2)

        val synthErrors = Collections.synchronizedList(mutableListOf<Throwable?>())
        val synth: suspend (String) -> Result<File> = { text ->
            delay(synthDelayMs)
            if (text == "two") {
                Result.failure(RuntimeException("elevenlabs 503"))
            } else {
                Result.success(newTempMp3(tag = text))
            }
        }

        val pipeline = launchPipeline(
            sentences = sentences,
            audioQueue = audioQueue,
            synth = synth,
            onSynthError = { synthErrors.add(it) },
        )

        sentences.send("one")
        sentences.send("two")
        sentences.send("three")
        sentences.close()

        pipeline.job.join()

        // First and third sentences must both have reached the player
        // despite the middle failure.
        assertEquals(
            "First and third sentences should play; second should be skipped",
            2,
            pipeline.playedFiles.size,
        )
        // Synth was attempted for all three.
        assertEquals(3, pipeline.synthCallCount())
        // Exactly one error was surfaced (the middle one).
        assertEquals(1, synthErrors.size)
        assertTrue(
            "Error message should reference the upstream failure",
            synthErrors.first()?.message?.contains("503") == true,
        )
        // No files left pending after a natural drain.
        assertTrue(pipeline.pendingFiles.isEmpty())
    }

    // ── Backpressure sanity ──────────────────────────────────────────────

    @Test
    fun `capacity-2 audio queue bounds synth lead`() = runTest {
        // Verify the synth worker does NOT run unbounded ahead of play.
        // With capacity 2, the synth worker can be at most:
        //   currently-playing (1) + channel-buffered (2) + currently-synthing (1)
        //   = 4 sentences ahead of what the player has fully drained.
        // Anything beyond that and the Channel.send back-pressure kicks in.
        val sentences = Channel<String>(Channel.UNLIMITED)
        val audioQueue = Channel<File>(capacity = 2)

        val synth: suspend (String) -> Result<File> = { text ->
            delay(50)
            Result.success(newTempMp3(tag = text))
        }

        val pipeline = launchPipeline(
            sentences = sentences,
            audioQueue = audioQueue,
            synth = synth,
            awaitPlaybackDelay = 500L,
        )

        repeat(10) { sentences.send("s-$it") }

        // Advance well past when the buffer fills (50 ms per synth) but
        // NOT far enough for any playback to complete (500 ms). Synth
        // should have hit the send-backpressure wall long before consuming
        // all 10 sentences.
        advanceTimeBy(450L)

        val inFlightFromSynth = pipeline.synthCallCount()
        assertTrue(
            "Synth should be bounded by audioQueue capacity; saw $inFlightFromSynth calls out of 10 queued",
            inFlightFromSynth <= 4,
        )
        assertTrue(
            "Synth should have started at least a few calls; saw $inFlightFromSynth",
            inFlightFromSynth >= 3,
        )

        sentences.close()
        pipeline.job.cancel()
        pipeline.job.join()
    }
}
