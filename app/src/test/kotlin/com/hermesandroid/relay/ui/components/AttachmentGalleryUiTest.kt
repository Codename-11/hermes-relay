package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.hermesandroid.relay.data.Attachment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class AttachmentGalleryUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `tile opens its page and the viewer swipes across the image group`() {
        val attachments = listOf("one.png", "two.png", "three.png").map { name ->
            Attachment(
                contentType = "image/png",
                content = ONE_PIXEL_PNG,
                fileName = name,
            )
        }

        compose.setContent {
            MaterialTheme {
                AttachmentGallery(attachments = attachments)
            }
        }

        compose.onNodeWithContentDescription("3 image gallery").assertExists()
        compose.onNodeWithTag("attachment-gallery-tile-0").performClick()
        compose.onNodeWithText("1 / 3").assertExists()

        compose.onNodeWithTag("attachment-gallery-pager").performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("2 / 3").assertExists()
    }

    @Test
    fun `tapped tile opens the matching page`() {
        val attachments = listOf("one.png", "two.png", "three.png").map { name ->
            Attachment(
                contentType = "image/png",
                content = ONE_PIXEL_PNG,
                fileName = name,
            )
        }

        compose.setContent {
            MaterialTheme {
                AttachmentGallery(attachments = attachments)
            }
        }

        compose.onNodeWithTag("attachment-gallery-tile-1").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("2 / 3").assertExists()
    }

    @Test
    fun `sensitive page actions stay disabled until that page is revealed`() {
        val attachments = listOf(
            Attachment(
                contentType = "image/png",
                content = ONE_PIXEL_PNG,
                fileName = "safe.png",
            ),
            Attachment(
                contentType = "image/png",
                content = ONE_PIXEL_PNG,
                fileName = "sensitive.png",
                sensitive = true,
            ),
        )

        compose.setContent {
            MaterialTheme {
                AttachmentGallery(attachments = attachments)
            }
        }

        compose.onNodeWithTag("attachment-gallery-tile-0").performClick()
        compose.onNodeWithTag("attachment-gallery-pager").performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Share").assertIsNotEnabled()
    }

    private companion object {
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
