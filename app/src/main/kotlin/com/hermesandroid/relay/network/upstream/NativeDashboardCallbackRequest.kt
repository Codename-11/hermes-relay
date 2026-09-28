package com.hermesandroid.relay.network.upstream

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class NativeCallbackRequestException(val stage: String) : IOException(stage)

/** Only origin-form HTTP GET requests for this listener are accepted. Never log input. */
internal fun readNativeCallbackTarget(input: InputStream, expectedPort: Int): String {
    fun reject(stage: String): Nothing = throw NativeCallbackRequestException(stage)
    fun line(limit: Int): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < limit) {
            val byte = input.read()
            if (byte == -1) reject("request_rejected_eof")
            if (byte == '\n'.code && bytes.lastOrNull() == '\r'.code.toByte()) {
                bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            if (byte !in 32..126 && byte != '\r'.code && byte != '\t'.code) {
                reject("request_rejected_syntax")
            }
            bytes.add(byte.toByte())
        }
        reject("request_rejected_size")
    }

    val parts = line(8 * 1024).split(' ')
    if (parts.size != 3 || parts[0] != "GET" ||
        parts[2] !in setOf("HTTP/1.0", "HTTP/1.1") ||
        parts[1].substringBefore('?') != "/callback" || '#' in parts[1]
    ) reject("request_rejected_target")

    var remaining = 16 * 1024
    var host: String? = null
    while (true) {
        val header = line(remaining)
        remaining -= header.length + 2
        if (header.isEmpty()) break
        val name = header.substringBefore(':')
        if (':' !in header || name.isEmpty() ||
            !name.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" } ||
            '\r' in header
        ) reject("request_rejected_syntax")
        if (name.equals("Host", ignoreCase = true)) {
            if (host != null) reject("request_rejected_host")
            host = header.substringAfter(':').trim()
        }
    }
    if (host != "127.0.0.1:$expectedPort") reject("request_rejected_host")
    return parts[1]
}

/** Coroutine cancellation must close the descriptor, not merely interrupt its IO thread. */
internal suspend fun <T> closingOnCancellation(resource: Closeable, block: suspend () -> T): T =
    coroutineScope {
        val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                runCatching { resource.close() }
            }
        }
        try {
            block()
        } finally {
            closer.cancel()
        }
    }
