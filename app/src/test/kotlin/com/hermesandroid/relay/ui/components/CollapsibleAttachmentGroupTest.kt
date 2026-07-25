package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.AttachmentRenderMode
import com.hermesandroid.relay.data.AttachmentState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class CollapsibleAttachmentGroupTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `summary keeps filename type and count for any attachment state`() {
        val summary = attachmentGroupSummary(
            listOf(
                Attachment(
                    contentType = "application/pdf",
                    content = "",
                    fileName = "report.pdf",
                    state = AttachmentState.FAILED,
                ),
                Attachment(
                    contentType = "application/octet-stream",
                    content = "",
                    state = AttachmentState.LOADING,
                ),
            ),
        )

        assertEquals(
            AttachmentGroupSummary(
                count = 2,
                firstName = "report.pdf",
                firstType = AttachmentRenderMode.PDF,
                remainingCount = 1,
            ),
            summary,
        )
    }

    @Test
    fun `collapsed group stays collapsed when attachment lifecycle updates`() {
        var attachments by mutableStateOf(
            listOf(
                Attachment(
                    contentType = "image/png",
                    content = "",
                    fileName = "result.png",
                    state = AttachmentState.LOADING,
                ),
            ),
        )

        compose.setContent {
            MaterialTheme {
                CollapsibleAttachmentGroup(
                    messageKey = "stable-message",
                    attachments = attachments,
                ) {
                    Text("Attachment preview")
                }
            }
        }

        compose.onNodeWithText("Attachment preview").assertExists()
        compose.onNodeWithContentDescription("Collapse attachments").assertExists()
        compose.onNodeWithTag("attachment-group-toggle-stable-message").performClick()
        compose.onNodeWithText("Attachment preview").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand attachments").assertExists()

        compose.runOnIdle {
            attachments = attachments.map {
                it.copy(
                    content = "loaded",
                    cachedUri = "content://media/result",
                    state = AttachmentState.LOADED,
                )
            }
        }

        compose.onNodeWithText("Attachment preview").assertDoesNotExist()
        compose.onNodeWithTag("attachment-group-toggle-stable-message").performClick()
        compose.onNodeWithText("Attachment preview").assertExists()
    }
}
