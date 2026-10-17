package com.hermesandroid.relay.ui.screens

import android.Manifest
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.data.BotGatewayRouteKey
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BotVoiceHostTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun routeRegistersItsOwnVoiceAndDisposalEndsIt() {
        val mounted = mutableStateOf(true)
        val registrations = mutableListOf<Pair<VoiceViewModel, Boolean>>()
        mount(mounted, registrations)
        compose.runOnIdle {
            val voice = registrations.single().first
            assertFalse(voice.uiState.value.voiceMode)
            voice.enterVoiceMode()
            assertTrue(voice.uiState.value.voiceMode)
            mounted.value = false
        }
        compose.runOnIdle {
            assertEquals(listOf(true, false), registrations.map { it.second })
            assertSame(registrations.first().first, registrations.last().first)
            assertFalse(registrations.first().first.uiState.value.voiceMode)
        }
    }

    @Test
    fun backgroundingEndsBotCall() {
        val registrations = mutableListOf<Pair<VoiceViewModel, Boolean>>()
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        mount(mutableStateOf(true), registrations, owner)
        compose.runOnIdle {
            registrations.single().first.enterVoiceMode()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            assertFalse(registrations.single().first.uiState.value.voiceMode)
        }
    }

    @Test
    fun permissionGrantWithoutTalkNeverStartsVoice() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val registrations = mutableListOf<Pair<VoiceViewModel, Boolean>>()
        mount(mutableStateOf(true), registrations)
        compose.runOnIdle {
            assertFalse(registrations.single().first.uiState.value.voiceMode)
            shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        }
        compose.runOnIdle { assertFalse(registrations.single().first.uiState.value.voiceMode) }
    }

    private fun mount(
        mounted: androidx.compose.runtime.MutableState<Boolean>,
        registrations: MutableList<Pair<VoiceViewModel, Boolean>>,
        owner: LifecycleOwner? = null,
    ) {
        val chat = mockk<ChatViewModel>(relaxed = true)
        every { chat.currentSessionId } returns MutableStateFlow("bot-session")
        every { chat.isLoadingHistory } returns MutableStateFlow(false)
        every { chat.error } returns MutableStateFlow(null)
        val dashboard = DashboardApiClient("https://bot.example.test", ownsHttpClient = false)
        compose.setContent {
            MaterialTheme {
                if (mounted.value) {
                    CompositionLocalProvider(LocalLifecycleOwner provides (owner ?: LocalLifecycleOwner.current)) {
                        BotChatVoiceHost(
                            route = BotGatewayRoute(BotGatewayRouteKey("bot-connection", "research"), "Bot gateway"),
                            sessionId = "bot-session",
                            dashboard = dashboard,
                            chat = chat,
                            onVoiceSessionChanged = { voice, active -> registrations += voice to active },
                        ) { }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
