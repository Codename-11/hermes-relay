package com.hermesandroid.relay.ui.components

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.Attachment
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w480dp-h720dp-xhdpi")
class PendingAttachmentComposerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `image reports loading then ready and opens preview`() {
        val decoded = CompletableDeferred<androidx.compose.ui.graphics.ImageBitmap?>()
        var previewed: Pair<Attachment, Int>? = null
        val attachment = imageAttachment(fileSize = 2_048)

        compose.setContent {
            MaterialTheme {
                PendingAttachmentComposer(
                    attachments = listOf(attachment),
                    onPreview = { item, index -> previewed = item to index },
                    onRemove = {},
                    onMove = { _, _ -> },
                    imageDecoder = { decoded.await() },
                )
            }
        }

        compose.onNodeWithTag("pending-attachment-0").assertIsNotEnabled()
        compose.onNodeWithContentDescription(
            "photo.jpg, Image, 2 KB, Preparing preview",
        ).assertExists()

        decoded.complete(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap())
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription(
                "photo.jpg, Image, 2 KB, Ready",
            ).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("pending-attachment-0").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(attachment to 0, previewed) }
    }

    @Test
    fun `decode failure is visible and preview stays disabled`() {
        compose.setContent {
            MaterialTheme {
                PendingAttachmentComposer(
                    attachments = listOf(imageAttachment()),
                    onPreview = { _, _ -> error("Preview must stay disabled") },
                    onRemove = {},
                    onMove = { _, _ -> },
                    imageDecoder = { null },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription(
                "photo.jpg, Image, Preview unavailable",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pending-attachment-0").assertIsNotEnabled()
        compose.onNodeWithContentDescription("photo.jpg, Image, Preview unavailable").assertIsNotEnabled()
    }

    @Test
    fun `remove and explicit reorder actions report current indices`() {
        val moves = mutableListOf<Pair<Int, Int>>()
        var removed: Int? = null
        val attachments = listOf(
            textAttachment("one.txt"),
            textAttachment("two.txt"),
            textAttachment("three.txt"),
        )

        compose.setContent {
            MaterialTheme {
                PendingAttachmentComposer(
                    attachments = attachments,
                    onPreview = { _, _ -> },
                    onRemove = { removed = it },
                    onMove = { from, to -> moves += from to to },
                )
            }
        }

        compose.onNodeWithContentDescription("Move one.txt left").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move one.txt right").performClick()
        compose.onNodeWithContentDescription("Move two.txt left").performClick()
        compose.onNodeWithContentDescription("Move three.txt right").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Remove two.txt").performClick()

        compose.runOnIdle {
            assertEquals(listOf(0 to 1, 1 to 0), moves)
            assertEquals(1, removed)
        }
    }

    @Test
    fun `attachment summary includes filename type size and status`() {
        compose.setContent {
            MaterialTheme {
                PendingAttachmentComposer(
                    attachments = listOf(textAttachment("notes.md", fileSize = 1_572_864)),
                    onPreview = { _, _ -> },
                    onRemove = {},
                    onMove = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("notes.md, Text, 1.5 MB, Ready").assertExists()
    }

    @Test
    fun `all attachment action targets are at least 48 dp`() {
        compose.setContent {
            MaterialTheme {
                PendingAttachmentComposer(
                    attachments = listOf(textAttachment("one.txt"), textAttachment("two.txt")),
                    onPreview = { _, _ -> },
                    onRemove = {},
                    onMove = { _, _ -> },
                )
            }
        }

        listOf(
            "pending-attachment-move-left-0",
            "pending-attachment-move-right-0",
            "pending-attachment-remove-0",
        ).forEach { tag ->
            compose.onNodeWithTag(tag)
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
    }

    private fun imageAttachment(fileSize: Long? = null) = Attachment(
        contentType = "image/jpeg",
        content = "unused-by-injected-decoder",
        fileName = "photo.jpg",
        fileSize = fileSize,
    )

    private fun textAttachment(name: String, fileSize: Long? = null) = Attachment(
        contentType = "text/plain",
        content = "dGVzdA==",
        fileName = name,
        fileSize = fileSize,
    )
}
