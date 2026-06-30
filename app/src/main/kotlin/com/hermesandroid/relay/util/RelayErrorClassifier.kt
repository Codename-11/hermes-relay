package com.hermesandroid.relay.util

import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
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
        "hermes broker auth failed" in msg ||
            "relay-side hermes credential" in msg -> HumanError(
            title = "Relay Hermes auth failed",
            body = "The relay could not authenticate to Hermes. Update the server-side Hermes credential and restart the relay.",
            retryable = false,
        )
        "xai realtime auth" in msg ||
            "openai realtime auth" in msg ||
            "realtime rejected the relay auth" in msg ||
            "realtime oauth refresh failed" in msg ||
            "realtime provider credentials" in msg -> HumanError(
            title = "Realtime provider auth unavailable",
            body = "The realtime voice provider is missing or rejected server-side auth. Refresh provider auth on the relay or choose another provider.",
            retryable = false,
            actionLabel = "Voice settings",
        )
        (
            "api key" in msg ||
                "sessions auth failed" in msg ||
                "api auth" in msg ||
                (context == "send_message" && ("401" in msg || "unauthorized" in msg))
            ) -> HumanError(
            title = "API key rejected",
            body = "The Hermes API rejected the saved API key - update it in Settings",
            retryable = false,
            actionLabel = "Settings",
        )
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
        // Version skew: the relay's strict allow-list rejected a field this
        // (newer) app sent — e.g. "unsupported voice output config field(s):
        // auto_speech_tags". Distinct from a bad *value* ("unsupported codec"),
        // which carries no "field", so requiring both avoids mislabeling real
        // input errors. Tell the user to update the relay, not to retry.
        "400" in msg && "unsupported" in msg && "field" in msg -> HumanError(
            title = "Relay update needed",
            body = "This feature needs a newer Hermes Relay plugin on your " +
                "server. Update the relay, then try again.",
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
 * Map the caller's [classifyError] context tag to a diagnostics category so the
 * recorded error lands under the right surface in the activity log. Defaults to
 * [DiagnosticCategory.Api] for unknown/null contexts.
 */
private fun categoryForContext(context: String?): DiagnosticCategory = when (context) {
    "transcribe", "synthesize", "voice_config", "record" -> DiagnosticCategory.Voice
    "pair" -> DiagnosticCategory.Auth
    "save_and_test", "media_fetch" -> DiagnosticCategory.Relay
    "send_message" -> DiagnosticCategory.Api
    else -> DiagnosticCategory.Api
}

/**
 * Convert an arbitrary Throwable into a user-facing [HumanError].
 *
 * **Side effect:** every classified error is also recorded to [DiagnosticsLog]
 * (Error severity, clean title + full redacted stacktrace) so the diagnostics
 * activity log captures it with zero call-site churn. The return value and all
 * existing copy are unchanged. The flow is one-way — [DiagnosticsLog.recordError]
 * never re-enters the classifier — so there is no recursion. A null throwable
 * produces a fallback but is NOT recorded (nothing actually failed).
 *
 * @param context short tag that shapes the title ("transcribe", "synthesize",
 *                "voice_config", "record", "pair", "save_and_test",
 *                "media_fetch", "send_message", or null for generic)
 */
/**
 * True if [t] is a "can't reach the server" connectivity failure — connection
 * refused, host unresolved, or a network timeout. These are exactly the states
 * the themed connection banner / startup sphere already surface, so callers on
 * cold-start / background bootstrap paths can use this to suppress a redundant,
 * scary snackbar (e.g. "The server isn't accepting connections" on first load)
 * while still recording the error to diagnostics.
 *
 * Deliberately excludes SSL/cert errors (actionable — re-pair) and generic
 * IOExceptions (could be anything but an unreachable server).
 */
fun isConnectivityError(t: Throwable?): Boolean = when (t) {
    is ConnectException, is UnknownHostException, is SocketTimeoutException -> true
    is IOException -> "timeout" in (t.message?.lowercase() ?: "")
    else -> false
}

fun classifyError(t: Throwable?, context: String? = null): HumanError {
    val human = classifyErrorInternal(t, context)
    if (t != null) {
        // Record after classification so the clean title and the raw trace both
        // reach the log. Defensive: never let logging turn a handled error fatal.
        runCatching {
            DiagnosticsLog.recordError(
                category = categoryForContext(context),
                title = human.title,
                detail = human.body,
                throwable = t,
            )
        }
    }
    return human
}

private fun classifyErrorInternal(t: Throwable?, context: String?): HumanError {
    if (t == null) return nullFallback(context)

    val msg = t.message.orEmpty().lowercase()

    // Typed exceptions are checked first because an IOException message scan
    // would otherwise swallow SSL/timeout/connect errors whose messages
    // happen to contain HTTP-ish substrings. Only fall through to the
    // message scan once we know it's a plain IOException.
    return when (t) {
        // Bodies say "server", not "relay" — these exceptions also surface
        // from the standard API/dashboard routes, where relay wording would
        // send users debugging the wrong box.
        is UnknownHostException -> HumanError(
            title = "Can't reach server",
            body = "Check your network and the server URL in Settings",
            retryable = true,
            actionLabel = "Retry",
        )
        is ConnectException -> HumanError(
            title = "Connection refused",
            body = "The server isn't accepting connections — make sure it's running",
            retryable = true,
            actionLabel = "Retry",
        )
        is SocketTimeoutException -> HumanError(
            title = "Network timeout",
            body = "The server took too long to respond",
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
            // Voice routing throws IllegalStateException with actionable copy
            // ("needs dashboard sign-in — open Manage"); preserve it instead
            // of rewriting every not-ready state into relay advice.
            body = t.message?.takeIf { it.isNotBlank() }
                ?: "Not ready — check that the relay is paired and online",
            retryable = true,
        )
        is IOException -> {
            if ("timeout" in msg) {
                HumanError(
                    title = "Network timeout",
                    body = "The server took too long to respond",
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
