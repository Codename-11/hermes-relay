package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HermesProcessNotificationTest {

    @Test
    fun completionEnvelopeParsesWithoutChangingCanonicalMessageRole() {
        val content = """
            [IMPORTANT: Background process 42 completed normally (exit code 0).
            Command: ./gradlew test
            Output:
            BUILD SUCCESSFUL]
        """.trimIndent()
        val message = ChatMessage(
            id = "server-user-row",
            role = MessageRole.USER,
            content = content,
            timestamp = 1L,
        )

        val parsed = message.hermesProcessNotificationOrNull()

        assertEquals(MessageRole.USER, message.role)
        assertEquals("42", parsed?.processId)
        assertEquals(
            "Background process 42 completed normally (exit code 0).",
            parsed?.headline,
        )
        assertEquals(
            "Command: ./gradlew test\nOutput:\nBUILD SUCCESSFUL",
            parsed?.detail,
        )
    }

    @Test
    fun watchMatchEnvelopePreservesMultilineDetail() {
        val content = """
            [IMPORTANT: Background process proc-7 matched watch pattern "ready".
            Command: python server.py
            Matched output:
            Server ready
            Listening on 127.0.0.1]
        """.trimIndent()

        val parsed = HermesProcessNotificationParser.parse(content)

        assertEquals("proc-7", parsed?.processId)
        assertEquals(
            "Command: python server.py\nMatched output:\nServer ready\nListening on 127.0.0.1",
            parsed?.detail,
        )
    }

    @Test
    fun compactEnvelopeWithoutOutputStillParses() {
        val parsed = HermesProcessNotificationParser.parse(
            "[IMPORTANT: Background process 123 finished]",
        )

        assertEquals("123", parsed?.processId)
        assertEquals("Background process 123 finished", parsed?.headline)
        assertNull(parsed?.detail)
    }

    @Test
    fun nonProcessImportantMessageIsNotClaimed() {
        assertNull(
            HermesProcessNotificationParser.parse(
                "[IMPORTANT: Process watch was disabled]",
            ),
        )
    }

    @Test
    fun markerEmbeddedInHumanTextIsNotClaimed() {
        assertNull(
            HermesProcessNotificationParser.parse(
                "Hermes said [IMPORTANT: Background process 12 finished] yesterday",
            ),
        )
    }

    @Test
    fun malformedEnvelopeWithoutStatusIsNotClaimed() {
        assertNull(
            HermesProcessNotificationParser.parse(
                "[IMPORTANT: Background process 12]",
            ),
        )
    }

    @Test
    fun assistantCopyOfProcessEnvelopeUsesNormalPresentation() {
        val message = ChatMessage(
            id = "assistant-copy",
            role = MessageRole.ASSISTANT,
            content = "[IMPORTANT: Background process 123 finished]",
            timestamp = 1L,
        )

        assertNull(message.hermesProcessNotificationOrNull())
    }
}
