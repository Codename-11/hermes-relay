package com.hermesandroid.relay.audio

import android.media.AudioTrack
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the production writer with a finite AudioTrack sink, not a policy replica. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealtimePcmPlayerWriteTest {
    private val rate = 24_000
    private val capacity = 33_600 // 700 ms, smaller than one synchronous-provider sentence
    private val bytes = ByteArrayOutputStream()
    private var playing = false
    private var queued = 0
    private var head = 0
    private var maxWrite = Int.MAX_VALUE
    private val track = mockk<AudioTrack>(relaxed = true).also { track ->
        every { track.bufferSizeInFrames } returns capacity / 2
        every { track.playbackHeadPosition } answers { head }
        every { track.playState } answers {
            if (playing) AudioTrack.PLAYSTATE_PLAYING else AudioTrack.PLAYSTATE_STOPPED
        }
        every { track.play() } answers { playing = true }
        every { track.write(any<ByteArray>(), any(), any(), any()) } answers {
            val count = minOf(thirdArg<Int>(), maxWrite, if (playing) capacity else capacity - queued)
            if (count > 0) {
                bytes.write(firstArg<ByteArray>(), secondArg(), count)
                queued += count
                if (playing && queued > capacity) {
                    head += (queued - capacity) / 2
                    queued = capacity
                }
            }
            count
        }
    }
    private val player = RealtimePcmPlayer().also { player ->
        field(player, "audioTrack", track)
        field(player, "currentSampleRate", rate)
    }

    @Test
    fun wholeSentenceLargerThanTrackPreservesEverySampleIncludingTail() {
        val speech = ByteArray(90_000) { (it % 127 + 1).toByte() }
        player.write(speech, rate)
        assertSpeech(speech)
        assertTrue(playing)
        assertEquals(bytes.size() / 2L, player.snapshot().framesWritten)
        assertTrue(player.estimatedRemainingPlaybackMs() > 0)
    }

    @Test
    fun shortWritesRetryTheUnwrittenSuffixWithoutDuplicatingTheOpening() {
        maxWrite = 960
        val speech = ByteArray(9_600) { (it % 113 + 1).toByte() }
        player.write(speech, rate)
        player.flushBufferedPlayback()
        assertSpeech(speech)
        assertEquals(bytes.size() / 2L, player.snapshot().framesWritten)
    }

    @Test
    fun toolGapKeepsTheTrackAndAcceptsTheNextWholeSentence() {
        val first = ByteArray(90_000) { 1 }
        val last = ByteArray(48_000) { 2 }
        player.write(first, rate)
        head = bytes.size() / 2
        queued = 0
        player.write(last, rate)
        player.flushBufferedPlayback()
        assertSpeech(first + last)
        verify(exactly = 0) { track.release() }
    }

    @Test
    fun zeroProgressTerminatesWithoutSpinning() {
        maxWrite = 0
        player.write(ByteArray(90_000), rate)
        assertEquals(0, bytes.size())
    }

    @Test
    fun adaptivePrebufferCannotOverfillAStoppedTrack() {
        field(player, "adaptiveStartPrebufferMs", 1_200L)
        val speech = ByteArray(90_000) { 3 }
        player.write(speech, rate)
        assertSpeech(speech)
    }

    @Test
    fun shortTailAfterUnderrunDoesNotRequireAFullHardwareBuffer() {
        player.write(ByteArray(48_000) { 1 }, rate)
        head = bytes.size() / 2
        queued = 0
        player.write(ByteArray(960) { 2 }, rate)
        player.flushBufferedPlayback()
        verify(exactly = 1) { track.setStartThresholdInFrames(1) }
        verify(exactly = 1) { track.play() }
        assertTrue(queued < capacity)
    }

    @Test
    @Config(sdk = [30])
    fun olderAndroidPadsShortFinalTailWithoutChangingSpeech() {
        val speech = ByteArray(960) { 4 }
        player.write(speech, rate)
        player.flushBufferedPlayback()
        val prefix = ByteArray(RealtimePcmBufferPolicy.startupPrerollBytes(rate)) + speech
        assertArrayEquals(prefix, bytes.toByteArray().copyOf(prefix.size))
        assertEquals(capacity, bytes.size())
        assertTrue(bytes.toByteArray().drop(prefix.size).all { it == 0.toByte() })
        assertEquals(capacity / 2L, player.snapshot().framesWritten)
    }

    @Test
    fun stopUnblocksNativeWriteAndRejectsPreviouslyScheduledGeneration() {
        val oldGeneration = player.generation
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        every { track.write(any<ByteArray>(), any(), any(), any()) } answers {
            entered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
            thirdArg<Int>()
        }
        every { track.pause() } answers { unblock.countDown() }
        val threads = Executors.newFixedThreadPool(2)
        try {
            val write = threads.submit { player.write(ByteArray(90_000), rate, oldGeneration) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            threads.submit { player.stop() }.get(2, TimeUnit.SECONDS)
            write.get(2, TimeUnit.SECONDS)
            player.write(ByteArray(90_000), rate, oldGeneration)
            assertTrue(!player.isActive)
            assertEquals(0, player.snapshot().framesWritten)
            verify(exactly = 1) { track.write(any<ByteArray>(), any(), any(), any()) }
        } finally {
            unblock.countDown()
            threads.shutdownNow()
        }
    }

    private fun assertSpeech(expected: ByteArray) {
        val preroll = RealtimePcmBufferPolicy.startupPrerollBytes(rate)
        assertArrayEquals(ByteArray(preroll) + expected, bytes.toByteArray())
    }

    private fun field(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
