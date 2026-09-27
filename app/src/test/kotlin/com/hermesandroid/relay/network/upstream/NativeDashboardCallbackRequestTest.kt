package com.hermesandroid.relay.network.upstream

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeDashboardCallbackRequestTest {
    @Test
    fun readsOneByteAtATimeIncludingSplitCrLf() {
        val bytes = "GET /callback?code=fixture&state=fixture HTTP/1.1\r\nHost: 127.0.0.1:42\r\nX-Test: yes\r\n\r\n"
        assertEquals("/callback?code=fixture&state=fixture", parse(bytes))
    }

    @Test
    fun rejectsEofAndMissingTerminatorAndOversizedInput() {
        listOf("", "GET /callback HTTP/1.1\r", "GET /callback HTTP/1.1\r\nHost: 127.0.0.1:42\r\n").forEach {
            assertEquals("request_rejected_eof", rejection(it))
        }
        assertEquals("request_rejected_size", rejection("GET /callback?" + "a".repeat(8192)))
        assertEquals("request_rejected_size", rejection("GET /callback HTTP/1.1\r\nX: " + "a".repeat(16384)))
        assertEquals("request_rejected_size", rejection("GET /callback HTTP/1.1\r\n" + "X: a\r\n".repeat(3000)))
    }

    @Test
    fun requiresExactHostPortPathAndUnambiguousHttp() {
        listOf("localhost:42", "[::1]:42", "127.0.0.1:43", "example.test:42", "127.0.0.1").forEach {
            assertEquals("request_rejected_host", rejection("GET /callback HTTP/1.1\r\nHost: $it\r\n\r\n"))
        }
        assertEquals("request_rejected_host", rejection("GET /callback HTTP/1.1\r\nHost: 127.0.0.1:42\r\nHost: 127.0.0.1:42\r\n\r\n"))
        listOf("/", "/other/../callback", "//callback", "/callback#fragment", "http://127.0.0.1:42/callback").forEach {
            assertEquals("request_rejected_target", rejection("GET $it HTTP/1.1\r\nHost: 127.0.0.1:42\r\n\r\n"))
        }
        assertEquals("request_rejected_target", rejection("OPTIONS /callback HTTP/1.1\r\n\r\n"))
        assertEquals("request_rejected_syntax", rejection("GET /callback HTTP/1.1\r\n Host: 127.0.0.1:42\r\n\r\n"))
    }

    private fun parse(request: String) = readNativeCallbackTarget(
        ByteArrayInputStream(request.toByteArray(Charsets.US_ASCII)), 42,
    )

    private fun rejection(request: String) = assertThrows(NativeCallbackRequestException::class.java) {
        parse(request)
    }.stage
}
