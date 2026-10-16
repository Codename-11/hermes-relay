package com.hermesandroid.relay.viewmodel

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.reliability.ReliabilityCenter
import com.hermesandroid.relay.ui.components.CrashReportGate
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.util.CrashReporter
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatMemoryRecoveryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun memoryReportDismissalAndLifecycleDoNotReopenHistoryUntilSelection() {
        val reads = AtomicInteger()
        val owner = AgentDisplay.profileContextKey("fixture", null)
        lateinit var vm: ChatViewModel
        compose.runOnUiThread {
            val context = compose.activity
            ReliabilityCenter.recordFatal(context, OutOfMemoryError("synthetic bounded recovery test"), "fixture")
            vm = ChatViewModel().also {
                it.pauseAutomaticSessionRestoreAfterMemoryFailure(
                    CrashReporter.isMemoryFailure(CrashReporter.peekPending(context)),
                )
                it.initialize(null, ChatHandler())
                it.streamingEndpoint = "gateway"
                it.setProfileMessageLoader {
                    reads.incrementAndGet()
                    Result.success(listOf(MessageItem(id = "row", role = "assistant", content = JsonPrimitive("Saved answer"))))
                }
                it.switchProfileContext(owner, "stored-long")
            }
        }
        compose.setContent {
            val messages by vm.messages.collectAsStateWithLifecycle()
            HermesRelayTheme(themePreference = "dark") {
                Column {
                    Button(onClick = { vm.switchSession("stored-long") }) { Text("Open saved conversation") }
                    messages.forEach { Text(it.content) }
                }
                CrashReportGate()
            }
        }
        val explanation = compose.activity.getString(R.string.crash_memory_recovery_body)
        compose.onNodeWithText(explanation).assertIsDisplayed()
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?.let(::File) ?: compose.activity.cacheDir
            output.mkdirs()
            File(output, "memory-recovery-dialog.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.common_dismiss)).performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { vm.reconcileProfileContext(owner, "stored-long") }
        compose.waitForIdle()
        assertEquals(0, reads.get())
        compose.onNodeWithText("Open saved conversation").performClick()
        compose.waitUntil(5_000) { reads.get() == 1 }
        compose.onNodeWithText("Saved answer").assertIsDisplayed()
    }
}
