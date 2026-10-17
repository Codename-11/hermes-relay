package com.hermesandroid.relay.runtime

import android.app.Application
import com.hermesandroid.relay.HermesRelayApp
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BotVoiceSessionOwnershipTest {
    @Test
    fun staleDestinationDisposalCannotUnregisterReplacement() {
        val runtime = HermesProcessRuntime(mockk<HermesRelayApp>(relaxed = true))
        val first = mockk<VoiceViewModel>(relaxed = true)
        val second = mockk<VoiceViewModel>(relaxed = true)

        runtime.registerBotVoiceSession(first)
        runtime.registerBotVoiceSession(second)
        verify(exactly = 1) { first.exitVoiceMode() }
        runtime.unregisterBotVoiceSession(first)
        assertSame(second, runtime.botVoiceSession.value)

        runtime.stopBotVoiceSession()
        verify(exactly = 1) { second.exitVoiceMode() }
        assertSame(second, runtime.botVoiceSession.value)

        runtime.unregisterBotVoiceSession(second)
        assertNull(runtime.botVoiceSession.value)
    }
}
