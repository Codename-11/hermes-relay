package com.hermesandroid.relay.data

import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentChatComposerDraftStoreTest {

    @Test
    fun `draft and attachment survive store recreation`() = runTest {
        val root = Files.createTempDirectory("composer-drafts").toFile()
        try {
            val key = ChatComposerDraftKey("connection", "profile", "session")
            val attachment = Attachment(
                contentType = "text/plain; charset=utf-8",
                content = Base64.getEncoder().encodeToString("large pasted text".toByteArray()),
                fileName = "pasted-text.txt",
                fileSize = 17,
                isLargePaste = true,
            )
            PersistentChatComposerDraftStore(root).save(
                key,
                ChatComposerDraft(
                    text = "survives process death",
                    selectionStart = 4,
                    selectionEnd = 9,
                    context = ChatComposerDraftContext(quotedMessageId = "quote"),
                    attachments = listOf(attachment),
                ),
            )

            val restored = PersistentChatComposerDraftStore(root).snapshot(key)

            assertEquals("survives process death", restored.text)
            assertEquals(4, restored.selectionStart)
            assertEquals(9, restored.selectionEnd)
            assertEquals("quote", restored.context.quotedMessageId)
            assertEquals(listOf(attachment), restored.attachments)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `session removal is exact and clears persisted blobs when unreferenced`() = runTest {
        val root = Files.createTempDirectory("composer-drafts").toFile()
        try {
            val store = PersistentChatComposerDraftStore(root)
            val removed = ChatComposerDraftKey("connection", "profile", "removed")
            val kept = ChatComposerDraftKey("connection", "profile", "kept")
            val attachment = Attachment(
                contentType = "text/plain",
                content = Base64.getEncoder().encodeToString("private draft".toByteArray()),
                fileName = "notes.txt",
                fileSize = 13,
            )
            store.save(removed, ChatComposerDraft(text = "remove", attachments = listOf(attachment)))
            store.save(kept, ChatComposerDraft(text = "keep"))

            store.removeSession("connection", "profile", "removed")

            assertTrue(store.snapshot(removed).isEmpty)
            assertEquals("keep", store.snapshot(kept).text)
            assertTrue(root.walkTopDown().none { it.isFile && it.extension == "blob" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `preparing large paste restores as a ready attachment`() = runTest {
        val root = Files.createTempDirectory("composer-drafts").toFile()
        try {
            val key = ChatComposerDraftKey("connection", "profile", "session")
            PersistentChatComposerDraftStore(root).save(
                key,
                ChatComposerDraft(
                    attachments = listOf(
                        Attachment(
                            contentType = "text/plain; charset=utf-8",
                            content = "",
                            fileName = "pasted-text.txt",
                            fileSize = 14,
                            state = AttachmentState.LOADING,
                            isLargePaste = true,
                            composerId = "preparing",
                            composerRawText = "unfinished app",
                        ),
                    ),
                ),
            )

            val restored = PersistentChatComposerDraftStore(root)
                .snapshot(key)
                .attachments
                .single()

            assertEquals(AttachmentState.LOADED, restored.state)
            assertEquals("preparing", restored.composerId)
            assertEquals(
                "unfinished app",
                String(Base64.getDecoder().decode(restored.content), Charsets.UTF_8),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
