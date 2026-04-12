package com.hermesandroid.relay.util

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Turns raw Throwables from the network/media/voice layers into short,
 * human-readable [HumanError] values the UI can show in a snackbar.
 *
 * Exists so nothing fails silently — every caught error must name what
 * broke and, when possible, suggest a next step. Agents B (voice) and
 * C (chat + settings) call [classifyError] from their LaunchedEffect
 * error collectors before handing the result to SnackbarHostState via
 * showHumanError in RelayApp.kt.
 */

data class HumanError(
    val title: String,
    val body: String,
    val retryable: Boolean = false,
    val actionLabel: String? = null,
)

private fun titlePrefix(context: String?): String = when (context) {
    "transcribe" -> "Voice transcription failed"
    "synthesize" -> "Voice playback failed"
    "voice_config" -> "Voice config unavailable"
    "record" -> "Can't record"
    "pair" -> "Pairing failed"
    "save_and_test" -> "Relay check failed"
    "media_fetch" -> "Couldn't fetch attachment"
    "send_message" -> "Couldn't send message"
    else -> "Something went wrong"
}

private fun nullFallback(context: String?): HumanError {
    val base = when (context) {
        "transcribe" -> "Voice transcription failed — no details available"
        "synthesize" -> "Voice playback failed — no details available"
        "voice_config" -> "Voice config unavailable — no details available"
        "record" -> "Recording failed — no details available"
        "pair" -> "Pairing failed — no details available"
        "save_and_test" -> "Relay check failed — no details available"
        "media_fetch" -> "Couldn't fetch attachment — no details available"
        "send_message" -> "Couldn't send message — no details available"
        else -> "Something went wrong — no details available"
    }
    return HumanError(title = titlePrefix(context), body = base, retryable = false)
}

private fun classifyIoMessage(msg: String, context: String?): HumanError? {
    // Ordered most-specific-first; callers have already handled the typed
    // SSL / timeout / connect exceptions so this only runs on generic IOs.
    return when {
        "401" in msg || "unauthorized" in msg -> HumanError(
            title = "Session expired",
            body = "Your session is no longer valid — re-pair this device",
            retryable = false,
            actionLabel = "Re-pair",
        )
        "403" in msg || "forbidden" in msg -> HumanError(
            title = "Not allowed",
            body = "The server refused this action",
            retryable = false,
        )
        "404" in msg -> HumanError(
            title = "Endpoint not found",
            body = if (context == "voice_config")
                "The relay doesn't have voice endpoints — it may be an older version"
            else "The relay doesn't have this endpoint — it may be an older version",
            retryable = false,
        )
        "413" in msg -> HumanError(
            title = "Too large",
            body = "The request exceeded the server's size limit",
            retryable = false,
        )
        "503" in msg -> HumanError(
            title = "Service unavailable",
            body = "The underlying provider is offline — try again in a moment",
            retryable = true,
            actionLabel = "Retry",
        )
        "500" in msg || "internal" in msg -> HumanError(
            title = "Server error",
            body = "The relay hit an error — check its logs",
            retryable = true,
            actionLabel = "Retry",
        )
        else -> null
    }
}

/**
 * Convert an arbitrary Throwable into a user-facing [HumanError].
 *
 * @param context short tag that shapes the title ("transcribe", "synthesize",
 *                "voice_config", "record", "pair", "save_and_test",
 *                "media_fetch", "send_message", or null for generic)
 */
fun classifyError(t: Throwable?, context: String? = null): HumanError {
    if (t == null) return nullFallback(context)

    val msg = t.message.orEmpty().lowercase()

    // Typed exceptions are checked first because an IOException message scan
    // would otherwise swallow SSL/timeout/connect errors whose messages
    // happen to contain HTTP-ish substrings. Only fall through to the
    // message scan once we know it's a plain IOException.
    return when (t) {
        is UnknownHostException -> HumanError(
            title = "Can't reach server",
            body = "Check your network and the relay URL in Settings",
            retryable = true,
            actionLabel = "Retry",
        )
        is ConnectException -> HumanError(
            title = "Connection refused",
            body = "The relay isn't accepting connections — make sure it's running",
            retryable = true,
            actionLabel = "Retry",
        )
        is SocketTimeoutException -> HumanError(
            title = "Network timeout",
            body = "The relay took too long to respond",
            retryable = true,
            actionLabel = "Retry",
        )
        is SSLPeerUnverifiedException, is SSLException -> HumanError(
            title = "Certificate mismatch",
            body = "The server certificate changed since you paired — re-pair to trust it",
            retryable = false,
            actionLabel = "Re-pair",
        )
        is SecurityException -> HumanError(
            title = "Permission needed",
            body = t.message?.takeIf { it.isNotBlank() }
                ?: "This action needs a permission Android hasn't granted",
            retryable = false,
            actionLabel = "Open Settings",
        )
        is IllegalStateException -> HumanError(
            title = titlePrefix(context),
            body = "Not ready — check that the relay is paired and online",
            retryable = true,
        )
        is IOException -> {
            if ("timeout" in msg) {
                HumanError(
                    title = "Network timeout",
                    body = "The relay took too long to respond",
                    retryable = true,
                    actionLabel = "Retry",
                )
            } else {
                classifyIoMessage(msg, context) ?: HumanError(
                    title = "Network error",
                    body = t.message?.takeIf { it.isNotBlank() }
                        ?: "Couldn't complete the request",
                    retryable = true,
                    actionLabel = "Retry",
                )
            }
        }
        else -> HumanError(
            title = titlePrefix(context),
            body = t.message ?: "Unknown error",
            retryable = false,
        )
    }
}
