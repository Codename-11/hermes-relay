package com.hermesandroid.relay.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatComposerDraftStoreTest {
    private val store = InMemoryChatComposerDraftStore()

    @Test
    fun draftsAreIsolatedByCompleteOwnerIdentity() = runBlocking {
        val original = key(connection = "connection-a", profile = "coder", session = "session-a")
        val otherConnection = original.copy(connectionId = "connection-b")
        val otherProfile = original.copy(profileId = "default")
        val otherSession = original.copy(sessionId = "session-b")
        val otherSlot = original.copy(draftId = "alternate")

        store.save(original, ChatComposerDraft(text = "owned by original"))

        assertEquals("owned by original", store.snapshot(original).text)
        listOf(otherConnection, otherProfile, otherSession, otherSlot).forEach { owner ->
            assertEquals(ChatComposerDraft(), store.snapshot(owner))
        }
    }

    @Test
    fun preservesSelectionContextAndPendingAttachments() = runBlocking {
        val attachment = Attachment(
            contentType = "image/jpeg",
            content = "base64-payload",
            fileName = "portrait.jpg",
            fileSize = 42L,
        )
        val pendingAttachments = mutableListOf(attachment)
        val draft = ChatComposerDraft(
            text = "hello world",
            selectionStart = 2,
            selectionEnd = 7,
            context = ChatComposerDraftContext(
                quotedMessageId = "quoted-message",
                editingMessageId = "edited-message",
            ),
            attachments = pendingAttachments,
        )

        store.save(key(), draft)
        pendingAttachments.clear()

        assertEquals("hello world", store.snapshot(key()).text)
        assertEquals(2, store.snapshot(key()).selectionStart)
        assertEquals(7, store.snapshot(key()).selectionEnd)
        assertEquals("quoted-message", store.snapshot(key()).context.quotedMessageId)
        assertEquals("edited-message", store.snapshot(key()).context.editingMessageId)
        assertEquals(listOf(attachment), store.snapshot(key()).attachments)
    }

    @Test
    fun updateUsesCurrentSessionDraftWithoutTouchingAnotherSession() = runBlocking {
        val first = key(session = "first")
        val second = key(session = "second")
        store.save(first, ChatComposerDraft(text = "one"))
        store.save(second, ChatComposerDraft(text = "two"))

        store.update(first) { it.copy(text = "one updated", selectionStart = 3) }

        assertEquals("one updated", store.snapshot(first).text)
        assertEquals(3, store.snapshot(first).selectionStart)
        assertEquals("two", store.snapshot(second).text)
    }

    @Test
    fun invalidSelectionIsClampedAndOrderedWhenSaved() = runBlocking {
        store.save(
            key(),
            ChatComposerDraft(text = "hello", selectionStart = 99, selectionEnd = -4),
        )

        assertEquals(0, store.snapshot(key()).selectionStart)
        assertEquals(5, store.snapshot(key()).selectionEnd)
    }

    @Test
    fun blankContextIdsNormalizeAndEmptyDraftRemovesStoredState() = runBlocking {
        val owner = key()
        store.save(owner, ChatComposerDraft(text = "temporary"))

        store.save(
            owner,
            ChatComposerDraft(
                context = ChatComposerDraftContext(
                    quotedMessageId = " ",
                    editingMessageId = "",
                ),
            ),
        )

        assertEquals(ChatComposerDraft(), store.snapshot(owner))
        assertEquals(ChatComposerDraft(), store.observe(owner).first())
    }

    @Test
    fun removeSessionClearsEveryDraftSlotOnlyForExactNamespace() = runBlocking {
        val primary = key()
        val alternate = primary.copy(draftId = "alternate")
        val otherProfile = primary.copy(profileId = "other")
        store.save(primary, ChatComposerDraft(text = "primary"))
        store.save(alternate, ChatComposerDraft(text = "alternate"))
        store.save(otherProfile, ChatComposerDraft(text = "keep"))

        store.removeSession(primary.connectionId, primary.profileId, primary.sessionId)

        assertTrue(store.snapshot(primary).isEmpty)
        assertTrue(store.snapshot(alternate).isEmpty)
        assertEquals("keep", store.snapshot(otherProfile).text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankIdentityIsRejected() {
        key(session = "")
    }

    private fun key(
        connection: String = "connection-a",
        profile: String = "coder",
        session: String = "session-a",
    ) = ChatComposerDraftKey(
        connectionId = connection,
        profileId = profile,
        sessionId = session,
    )
}
