package com.hermesandroid.relay.network.upstream

import android.content.Context
import com.hermesandroid.relay.network.shared.GptLiveCallbacks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.audio.AudioDeviceModule

class GptLiveLocalCloseTest {
    @Test fun successfulRemoteCloseStillReleasesLocalMicrophoneImmediately() = checkClose(false)
    @Test fun nativeSendFailureStillReleasesLocalMicrophoneImmediately() = checkClose(true)

    private fun checkClose(sendThrows: Boolean) {
        val context = mockk<Context>(relaxed = true)
        val audioDevice = mockk<AudioDeviceModule>(relaxed = true)
        val microphone = mockk<AudioTrack>(relaxed = true)
        val channel = mockk<DataChannel>(relaxed = true)
        every { channel.state() } returns DataChannel.State.OPEN
        if (sendThrows) every { channel.send(any()) } throws IllegalStateException("Native channel closed")
        else every { channel.send(any()) } returns true
        val closed = mutableListOf<String>()
        val session = AndroidGptLiveSession(
            context = context,
            callbacks = GptLiveCallbacks(onDelegation = { _, _, _ -> }, onClosed = { reason, _ -> closed += reason }),
            createSession = { error("No network session needed") },
            json = Json,
            audioDeviceModule = audioDevice,
        )
        AndroidGptLiveSession::class.java.getDeclaredField("events").apply {
            isAccessible = true
            set(session, channel)
        }
        AndroidGptLiveSession::class.java.getDeclaredField("microphoneTrack").apply {
            isAccessible = true
            set(session, microphone)
        }
        session.close()
        session.close()
        assertEquals(listOf("close_requested"), closed)
        verify(exactly = 1) { microphone.setEnabled(false) }
        verify(exactly = 1) { microphone.dispose() }
        verify(exactly = 1) { audioDevice.release() }
        verify(exactly = 1) { channel.close() }
    }
}
