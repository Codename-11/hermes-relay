package com.hermesandroid.relay.voice

import android.app.Application
import android.util.Log
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.R
import com.hermesandroid.relay.audio.RealtimePcmPlayer
import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.network.relay.RealtimeVoiceEvent
import com.hermesandroid.relay.network.relay.VoiceOutputConfig
import com.hermesandroid.relay.network.relay.VoiceOutputSummary
import com.hermesandroid.relay.network.shared.VoiceAudioClient
import com.hermesandroid.relay.ui.UiMessageBus
import com.hermesandroid.relay.ui.UiMessageEvent
import com.hermesandroid.relay.ui.UiMessageSeverity
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceOutputFallbackTest {
    private lateinit var client: RelayVoiceClient
    private lateinit var vm: VoiceViewModel
    private lateinit var pcmPlayer: RealtimePcmPlayer
    private val providerResult = CompletableDeferred<Result<VoiceOutputSummary>>()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(Log::class)
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 100L
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        client = mockk(relaxed = true)
        coEvery { client.getVoiceOutputConfig() } returns Result.success(VoiceOutputConfig(enabled = true))
        coEvery { client.runVoiceOutput(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            providerResult.await()
        }
        val app = mockk<Application>(relaxed = true)
        every { app.getString(R.string.voice_streaming_failed_basic) } returns "Streaming voice failed. Using basic speech."
        every { app.getString(R.string.voice_status_render_streaming) } returns "Streaming output"
        every { app.getString(R.string.voice_streaming_stopped_basic_next) } returns "Streaming voice stopped. Remaining speech will use basic speech."
        vm = VoiceViewModel(app)
        setField("voiceClient", client)
        pcmPlayer = mockk(relaxed = true)
        setField("realtimePcmPlayer", pcmPlayer)
        setField("voiceAudioClient", mockk<VoiceAudioClient> {
            every { effectiveRoute } returns VoiceAudioRoute.Relay
        })
        DiagnosticsLog.clear()
    }

    private fun setField(name: String, value: Any) {
        VoiceViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun queue(name: String): Channel<String> =
        VoiceViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as Channel<String>

    private fun takeFirst(): String {
        vm.enqueueSentenceForTts("First sentence. ".repeat(20))
        return queue("realtimeTtsQueue").tryReceive().getOrThrow()
    }

    @After
    fun cleanup() {
        vm.exitVoiceMode()
        vm.viewModelScope.cancel()
        DiagnosticsLog.clear()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `provider rejection warns once and preserves queued chunks and short tail in order`() = runTest {
        val notices = mutableListOf<UiMessageEvent.Show>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UiMessageBus.events.collect { if (it is UiMessageEvent.Show) notices += it }
        }
        val first = takeFirst()
        val attempt = launch { vm.speakSentenceViaRealtime(first) }
        runCurrent()
        val next = "Next sentence. ".repeat(20).trim()
        vm.enqueueSentenceForTts(next)
        vm.enqueueSentenceForTts("Short tail.")
        providerResult.complete(Result.failure(IOException("permission denied; private-account-credit-detail")))
        attempt.join()
        vm.enqueueSentenceForTts("Later sentence.")

        val legacy = queue("ttsQueue")
        assertEquals(listOf(first, next, "Short tail.", "Later sentence."),
            generateSequence { legacy.tryReceive().getOrNull() }.toList())
        assertTrue(queue("realtimeTtsQueue").tryReceive().isFailure)
        assertEquals(1, notices.size)
        assertEquals(UiMessageSeverity.Warning, notices.single().message.severity)
        assertTrue(notices.single().message.text.contains("Using basic speech"))
        assertTrue(DiagnosticsLog.entries.value.any { it.detail?.contains("render_path=legacy_hermes_tts") == true })
        assertFalse(DiagnosticsLog.entries.value.toString().contains("private-account"))
        coVerify(exactly = 1) { client.runVoiceOutput(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancelled provider attempt never creates fallback audio or warning`() = runTest {
        val first = takeFirst()
        val attempt = launch { vm.speakSentenceViaRealtime(first) }
        runCurrent()
        attempt.cancel()
        attempt.join()
        assertTrue(queue("ttsQueue").tryReceive().isFailure)
        assertFalse(DiagnosticsLog.entries.value.any { it.detail?.contains("streaming_attempt_failed") == true })
    }

    @Test
    fun `failure from previous profile cannot choose fallback for current profile`() = runTest {
        val first = takeFirst()
        val attempt = launch { vm.speakSentenceViaRealtime(first) }
        runCurrent()
        vm.onProfileChanged("other")
        vm.onProfileChanged(null)
        providerResult.complete(Result.failure(IOException("old failure")))
        attempt.join()
        assertTrue(queue("ttsQueue").tryReceive().isFailure)
        assertFalse(DiagnosticsLog.entries.value.any { it.detail?.contains("streaming_attempt_failed") == true })
    }

    @Test
    fun `stale failure after exit cannot warn or resume speech`() = runTest {
        val first = takeFirst()
        val attempt = launch { vm.speakSentenceViaRealtime(first) }
        runCurrent()
        vm.exitVoiceMode()
        providerResult.complete(Result.failure(IOException("late provider failure")))
        attempt.join()
        assertTrue(queue("ttsQueue").tryReceive().isFailure)
        assertFalse(DiagnosticsLog.entries.value.any { it.detail?.contains("streaming_attempt_failed") == true })
    }

    @Test
    fun `successful response without audio still discloses and uses fallback`() = runTest {
        val first = takeFirst()
        providerResult.complete(Result.success(VoiceOutputSummary("stub", "stub", "stub", 24000, 0, 0, null, null, null)))
        vm.speakSentenceViaRealtime(first)
        assertEquals(first, queue("ttsQueue").tryReceive().getOrThrow())
        assertTrue(DiagnosticsLog.entries.value.any { it.detail?.contains("replay_chunk=true") == true })
    }

    @Test
    fun `partially spoken failed chunk is not replayed`() = runTest {
        coEvery { client.runVoiceOutput(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<(RealtimeVoiceEvent) -> Unit>(8)(
                RealtimeVoiceEvent(type = "voice.audio.delta", audioBase64 = "AAAAAA==", sampleRate = 24000, raw = "{}"),
            )
            Result.failure(IOException("provider disconnected"))
        }
        vm.speakSentenceViaRealtime(takeFirst())
        assertTrue(queue("ttsQueue").tryReceive().isFailure)
        assertTrue(DiagnosticsLog.entries.value.any { it.detail?.contains("replay_chunk=false") == true })
        verify(exactly = 1) { pcmPlayer.stop() }
    }
}
