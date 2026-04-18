package com.hermesandroid.relay.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for [VoicePlayer] — the Media3-backed TTS playback surface.
 *
 * ExoPlayer construction requires a live Android pipeline, so we mock the
 * builder + player pair with MockK. These tests cover the queue-management
 * contract, not real audio playback (that's verified manually on device
 * by listening for the seam between sentences).
 *
 * Covers the V5 acceptance criteria from
 * `docs/plans/2026-04-16-voice-quality-pass.md`:
 *   - `play(file)` enqueues a MediaItem
 *   - `stop()` clears items and stops the player
 *   - `awaitCompletion()` suspends while items are queued and resumes
 *     when the queue is empty and the player is not playing
 *   - amplitude StateFlow is exposed and initialized to 0f
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoicePlayerTest {

    private lateinit var context: Context
    private lateinit var exoPlayer: ExoPlayer
    private var listener: Player.Listener? = null

    // Mirrors the real ExoPlayer's counter so the test's view and the
    // VoicePlayer's view agree without having to drive every listener
    // callback from MockK answers.
    private var fakeMediaItemCount: Int = 0
    private var fakeIsPlaying: Boolean = false
    private var fakePlaybackState: Int = Player.STATE_IDLE
    private var fakeVolume: Float = 1f

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true).also {
            every { it.applicationContext } returns it
        }

        // Construct-mock the Builder chain so `ExoPlayer.Builder(context)
        // .setHandleAudioBecomingNoisy(true).build()` returns our mock.
        mockkConstructor(ExoPlayer.Builder::class)
        exoPlayer = mockk(relaxed = true)

        every { anyConstructed<ExoPlayer.Builder>().setHandleAudioBecomingNoisy(any()) } answers {
            self as ExoPlayer.Builder
        }
        every { anyConstructed<ExoPlayer.Builder>().build() } returns exoPlayer

        // Listener registration — capture so tests can drive state transitions.
        val listenerSlot = slot<Player.Listener>()
        every { exoPlayer.addListener(capture(listenerSlot)) } answers {
            listener = listenerSlot.captured
        }

        // Queue + state inspection read from the fake counters so the
        // VoicePlayer sees consistent values whether it reads them in
        // play(), stop(), or from the listener callback.
        every { exoPlayer.mediaItemCount } answers { fakeMediaItemCount }
        every { exoPlayer.isPlaying } answers { fakeIsPlaying }
        every { exoPlayer.playbackState } answers { fakePlaybackState }
        every { exoPlayer.playWhenReady } returns false
        every { exoPlayer.audioSessionId } returns 0

        every { exoPlayer.addMediaItem(any<MediaItem>()) } answers {
            fakeMediaItemCount++
        }
        every { exoPlayer.clearMediaItems() } answers {
            fakeMediaItemCount = 0
        }
        every { exoPlayer.prepare() } just Runs
        every { exoPlayer.play() } answers {
            fakeIsPlaying = true
            fakePlaybackState = Player.STATE_READY
            listener?.onIsPlayingChanged(true)
        }
        every { exoPlayer.stop() } answers {
            fakeIsPlaying = false
            fakePlaybackState = Player.STATE_IDLE
            listener?.onIsPlayingChanged(false)
        }
        every { exoPlayer.release() } just Runs

        // Volume is a var in ExoPlayer; back it with a field so set/get
        // round-trips consistently (matches how the other counters work).
        every { exoPlayer.volume = any() } answers {
            fakeVolume = firstArg()
        }
        every { exoPlayer.volume } answers { fakeVolume }
    }

    @After
    fun tearDown() {
        unmockkAll()
        listener = null
        fakeMediaItemCount = 0
        fakeIsPlaying = false
        fakePlaybackState = Player.STATE_IDLE
        fakeVolume = 1f
    }

    @Test
    fun `amplitude StateFlow is exposed and initialized to zero`() {
        val player = VoicePlayer(context)
        assertEquals(0f, player.amplitude.value, 0.0001f)
    }

    @Test
    fun `play appends a MediaItem and prepares the player when idle`() {
        val voicePlayer = VoicePlayer(context)
        val file = File("/tmp/voice_tts_0.mp3")

        voicePlayer.play(file)

        verify(exactly = 1) { exoPlayer.addMediaItem(any<MediaItem>()) }
        verify(exactly = 1) { exoPlayer.prepare() }
        verify(exactly = 1) { exoPlayer.play() }
        assertTrue("queue should report a pending item", voicePlayer.isPlaying())
    }

    @Test
    fun `stop clears media items and stops the player`() {
        val voicePlayer = VoicePlayer(context)
        voicePlayer.play(File("/tmp/voice_tts_0.mp3"))

        voicePlayer.stop()

        verify(exactly = 1) { exoPlayer.clearMediaItems() }
        verify(exactly = 1) { exoPlayer.stop() }
        assertFalse("queue should be empty after stop", voicePlayer.isPlaying())
        assertEquals(0f, voicePlayer.amplitude.value, 0.0001f)
    }

    @Test
    fun `awaitCompletion returns immediately when queue is already drained`() = runTest {
        val voicePlayer = VoicePlayer(context)
        // Never played anything — queue is empty, player is not playing.
        withTimeout(200) { voicePlayer.awaitCompletion() }
    }

    @Test
    fun `awaitCompletion suspends while queue is non-empty and resumes on drain`() = runTest {
        val voicePlayer = VoicePlayer(context)
        voicePlayer.play(File("/tmp/voice_tts_0.mp3"))

        val waiter = async { voicePlayer.awaitCompletion() }

        // Give the collector a scheduler tick to latch onto the StateFlow.
        // While queued + playing, awaitCompletion must not complete.
        var completedEarly = false
        try {
            withTimeout(50) { waiter.await() }
            completedEarly = true
        } catch (_: TimeoutCancellationException) {
            // expected — still suspended on the non-empty queue.
        }
        assertFalse("awaitCompletion returned while queue was non-empty", completedEarly)

        // Drain the queue + stop playback the way ExoPlayer would at
        // end-of-queue: isPlaying → false, mediaItemCount → 0.
        fakeMediaItemCount = 0
        fakeIsPlaying = false
        fakePlaybackState = Player.STATE_ENDED
        listener?.onIsPlayingChanged(false)
        listener?.onPlaybackStateChanged(Player.STATE_ENDED)

        withTimeout(500) { waiter.await() }
    }

    @Test
    fun `duck sets exoPlayer volume to 0_3f and unduck restores to 1_0f`() {
        val voicePlayer = VoicePlayer(context)

        voicePlayer.duck()

        verify { exoPlayer.volume = 0.3f }
        assertEquals(0.3f, fakeVolume, 0.0001f)

        voicePlayer.unduck()

        verify { exoPlayer.volume = 1.0f }
        assertEquals(1.0f, fakeVolume, 0.0001f)
    }
}
