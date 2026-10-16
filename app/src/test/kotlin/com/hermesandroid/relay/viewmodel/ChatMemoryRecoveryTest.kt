package com.hermesandroid.relay.viewmodel

import android.os.Looper
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.ChatComposerDraft
import com.hermesandroid.relay.data.ChatComposerDraftKey
import com.hermesandroid.relay.data.ChatTurnCheckpoint
import com.hermesandroid.relay.data.ChatTurnCheckpointStore
import com.hermesandroid.relay.data.InMemoryChatComposerDraftStore
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.network.upstream.models.SessionItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatMemoryRecoveryTest {
    @Test
    fun startupPauseKeepsDirectoryDraftAndCheckpointUntilExplicitSelection() {
        val handler = ChatHandler().also { it.setSessionId("long-session") }
        val owner = AgentDisplay.profileContextKey("connection", null)
        val draftKey = ChatComposerDraftKey("connection", "default", "long-session")
        val drafts = InMemoryChatComposerDraftStore()
        runBlocking { drafts.save(draftKey, ChatComposerDraft("unsent draft")) }
        val historyReads = mutableListOf<String>()
        val persistedSelections = mutableListOf<String?>()
        var checkpointReads = 0
        var checkpointClears = 0
        var checkpointWrites = 0
        val vm = ChatViewModel().also {
            it.pauseAutomaticSessionRestoreAfterMemoryFailure(true)
            it.installComposerDraftStore(drafts)
            it.initialize(null, handler)
            it.streamingEndpoint = "gateway"
            it.setProfileMessageLoader { id ->
                historyReads += id
                Result.success(listOf(MessageItem(id = "row", role = "assistant", content = JsonPrimitive("Saved answer"))))
            }
            it.setProfileSessionLister { Result.success(listOf(SessionItem(id = "long-session"))) }
            it.onSessionChanged = { id -> persistedSelections += id }
            it.setChatTurnCheckpointStore(object : ChatTurnCheckpointStore {
                override suspend fun read(): ChatTurnCheckpoint? { checkpointReads++; return null }
                override suspend fun write(checkpoint: ChatTurnCheckpoint) { checkpointWrites++ }
                override suspend fun clear() { checkpointClears++ }
            })
        }
        vm.switchProfileContext(owner, "long-session")
        vm.reconcileProfileContext(owner, "long-session")
        vm.refreshSessions()
        await { handler.sessions.value.isNotEmpty() }
        assertNull(handler.currentSessionId.value)
        assertTrue(vm.initialChatSettled.value)
        assertTrue(historyReads.isEmpty())
        assertTrue(persistedSelections.isEmpty())
        assertEquals(0, checkpointReads)
        assertEquals(0, checkpointClears)
        assertEquals(0, checkpointWrites)
        assertEquals("unsent draft", runBlocking { drafts.snapshot(draftKey).text })

        vm.switchSession("long-session")
        await { handler.messages.value.isNotEmpty() }
        assertEquals(listOf("long-session"), historyReads)
        assertEquals("long-session", handler.currentSessionId.value)
        assertEquals("unsent draft", runBlocking { drafts.snapshot(draftKey).text })
        vm.reconcileProfileContext(owner, "long-session")
        assertEquals("long-session", handler.currentSessionId.value)
        assertEquals(0, checkpointClears)
    }

    @Test
    fun explicitNewChatRetiresOnlyTheRememberedSelection() {
        val handler = ChatHandler()
        val selections = mutableListOf<String?>()
        val owner = AgentDisplay.profileContextKey("connection", null)
        val vm = ChatViewModel().also {
            it.pauseAutomaticSessionRestoreAfterMemoryFailure(true)
            it.initialize(null, handler)
            it.streamingEndpoint = "gateway"
            it.onSessionChanged = { id -> selections += id }
        }
        vm.switchProfileContext(owner, "long-session")
        assertTrue(selections.isEmpty())
        vm.createNewChat()
        assertEquals(listOf<String?>(null), selections)
        vm.reconcileProfileContext(owner, null)
        assertNull(handler.currentSessionId.value)
    }

    @Test
    fun normalStartupStillLoadsItsRememberedConversation() {
        val handler = ChatHandler()
        val reads = mutableListOf<String>()
        val vm = ChatViewModel().also {
            it.initialize(null, handler)
            it.streamingEndpoint = "gateway"
            it.setProfileMessageLoader { id ->
                reads += id
                Result.success(emptyList())
            }
        }
        vm.switchProfileContext(AgentDisplay.profileContextKey("connection", null), "saved-session")
        await { reads.isNotEmpty() }
        assertEquals(listOf("saved-session"), reads)
        assertEquals("saved-session", handler.currentSessionId.value)
    }

    @Test
    fun explicitVoiceTurnIsNotReplacedByAutomaticReconciliation() {
        val handler = ChatHandler()
        val owner = AgentDisplay.profileContextKey("connection", null)
        val vm = ChatViewModel().also {
            it.pauseAutomaticSessionRestoreAfterMemoryFailure(true)
            it.initialize(null, handler)
            it.streamingEndpoint = "gateway"
        }
        vm.switchProfileContext(owner, "long-session")
        vm.startRealtimeAgentTurn("spoken prompt", "voice-session")
        vm.reconcileProfileContext(owner, "voice-session")
        assertEquals("voice-session", handler.currentSessionId.value)
        assertTrue(handler.messages.value.any { it.content == "spoken prompt" })
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(condition())
    }
}
