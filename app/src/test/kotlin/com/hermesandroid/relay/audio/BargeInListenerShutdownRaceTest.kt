package com.hermesandroid.relay.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BargeInListenerShutdownRaceTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0

        mockkStatic(AcousticEchoCanceler::class)
        every { AcousticEchoCanceler.isAvailable() } returns false
        mockkStatic(NoiseSuppressor::class)
        every { NoiseSuppressor.isAvailable() } returns false
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkStatic(AcousticEchoCanceler::class)
        unmockkStatic(NoiseSuppressor::class)
    }

    @Test
    fun `closed vad during shutdown exits reader without crashing`() = runTest {
        val vadEngine = mockk<VadEngine>()
        every { vadEngine.analyze(any()) } throws
            IllegalArgumentException("You can't use Vad after closing session!")

        val source = OneFrameAudioSource()
        val listener = BargeInListener(
            audioSource = source,
            vadEngine = vadEngine,
            audioSessionIdProvider = { 42 },
            readerDispatcher = StandardTestDispatcher(testScheduler),
        )

        listener.start(this)
        advanceUntilIdle()

        assertEquals(1, source.readCount)
        assertTrue("source should be released after VAD shutdown race", source.released)
    }

    private class OneFrameAudioSource : BargeInListener.AudioFrameSource {
        var readCount: Int = 0
            private set
        var released: Boolean = false
            private set

        override fun initialize(): Boolean = true
        override fun start() { /* no-op */ }

        override fun read(buffer: ShortArray, sizeInShorts: Int): Int {
            readCount++
            return if (readCount == 1) {
                sizeInShorts
            } else {
                0
            }
        }

        override fun stop() { /* no-op */ }
        override fun release() {
            released = true
        }
    }
}
