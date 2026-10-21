package com.hermesandroid.relay.viewmodel

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class DashboardHistoryRecoveryInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun oversizedHistoryRecoversAndSingleMessageFailureCanBeReopened() {
        MockWebServer().use { server ->
            val singleMessageTooLarge = java.util.concurrent.atomic.AtomicBoolean(true)
            val sidecar = "x".repeat(20_000)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (singleMessageTooLarge.get()) {
                        return MockResponse().setBody("{}").setHeader("Content-Length", 9 * 1024 * 1024)
                            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                    }
                    val url = request.requestUrl!!
                    val limit = url.queryParameter("limit")!!.toInt()
                    val offset = url.queryParameter("offset")!!.toInt()
                    val indices = (0 until 500).reversed().drop(offset).take(limit).reversed()
                    val rows = indices.joinToString(",") { index ->
                        """{"id":"$index","role":"user","content":"message $index","codex_reasoning_items":"$sidecar"}"""
                    }
                    return MockResponse().setChunkedBody(
                        """{"messages":[$rows],"pagination":{"returned":${indices.size}}}""", 8192,
                    )
                }
            }
            val dashboard = DashboardApiClient(server.url("/").toString())
            val handler = ChatHandler()
            val vm = ChatViewModel()
            compose.runOnUiThread {
                vm.initialize(null, handler)
                vm.streamingEndpoint = "gateway"
                vm.setProfileMessageLoaderWithMode { profile, session, mode ->
                    dashboard.getSessionMessages(session, profile, mode)
                }
            }
            compose.setContent {
                val messages by vm.messages.collectAsStateWithLifecycle()
                val failure by vm.chatFailure.collectAsStateWithLifecycle()
                Text(
                    text = failure?.rawError ?: "${messages.size}:${messages.firstOrNull()?.content}:${messages.lastOrNull()?.content}",
                    modifier = Modifier.testTag("history-result"),
                )
            }
            compose.runOnUiThread { vm.switchSession("history") }
            compose.waitUntil(30_000) { vm.chatFailure.value != null }
            assertFalse(vm.chatFailure.value!!.recoverable) // Never offer resend for a history read.
            assertEquals(0, vm.messages.value.size)
            compose.onNodeWithTag("history-result").assertTextEquals(vm.chatFailure.value!!.rawError)

            singleMessageTooLarge.set(false)
            compose.runOnUiThread { vm.switchSession("history") }
            compose.waitUntil(30_000) { vm.messages.value.size == 500 }
            compose.onNodeWithTag("history-result").assertTextEquals("500:message 0:message 499")
            assertEquals(null, vm.chatFailure.value)
            repeat(server.requestCount) {
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("true", request.requestUrl!!.queryParameter("include_compacted"))
            }
        }
    }
}
