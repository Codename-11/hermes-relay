package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.MessageDeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class MessageDeliveryIndicatorTest {
    @get:Rule
    val compose = createComposeRule()

    private val copy = MessageDeliveryIndicatorText(
        sending = "Sending…",
        queued = "Queued",
        steered = "Steered",
        delivered = "Delivered",
        failed = "Failed",
        tapToRetry = "Tap to retry",
    )

    @Test
    fun statusLineModelsAllLifecycleStates() {
        assertEquals("Sending…", messageDeliveryStatusLine(MessageDeliveryStatus.SENDING, copy))
        assertEquals("Queued", messageDeliveryStatusLine(MessageDeliveryStatus.QUEUED, copy))
        assertEquals("Steered", messageDeliveryStatusLine(MessageDeliveryStatus.STEERED, copy))
        assertEquals("Delivered", messageDeliveryStatusLine(MessageDeliveryStatus.DELIVERED, copy))
        assertEquals("Failed", messageDeliveryStatusLine(MessageDeliveryStatus.FAILED, copy))
        assertEquals(
            "Failed · Connection lost · Tap to retry",
            messageDeliveryStatusLine(
                status = MessageDeliveryStatus.FAILED,
                text = copy,
                failureMessage = " Connection lost ",
                retryable = true,
            ),
        )
    }

    @Test
    fun everyStateHasVisibleTextAndMergedAccessibilityMeaning() {
        compose.setContent {
            MaterialTheme {
                Column {
                    MessageDeliveryStatus.entries.forEach { status ->
                        MessageDeliveryIndicator(status = status, text = copy)
                    }
                }
            }
        }

        listOf("Sending…", "Queued", "Steered", "Delivered", "Failed").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
        }
    }

    @Test
    fun failedStateInvokesRetryFromTheWholeStatusAffix() {
        var retryCount = 0
        compose.setContent {
            MaterialTheme {
                MessageDeliveryIndicator(
                    status = MessageDeliveryStatus.FAILED,
                    text = copy,
                    failureMessage = "Connection lost",
                    onRetry = { retryCount += 1 },
                )
            }
        }

        compose.onNodeWithContentDescription("Failed · Connection lost · Tap to retry")
            .assertHasClickAction()
            .performClick()

        assertEquals(1, retryCount)
    }

    @Test
    fun nonFailureNeverExposesRetryEvenWhenCallbackIsPassed() {
        compose.setContent {
            MaterialTheme {
                MessageDeliveryIndicator(
                    status = MessageDeliveryStatus.DELIVERED,
                    text = copy,
                    onRetry = { error("Delivered messages must not retry") },
                )
            }
        }

        compose.onNodeWithText("Delivered")
            .assertTextEquals("Delivered")
            .assertHasNoClickAction()
    }
}
