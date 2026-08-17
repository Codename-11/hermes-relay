package com.hermesandroid.relay.network.shared

import okhttp3.Request
import java.io.IOException

/** Secret-free failure raised before OkHttp sees a malformed credential. */
class InvalidCredentialException internal constructor(message: String) : IOException(message)

/**
 * Normalize only harmless surrounding horizontal whitespace. Credentials are
 * otherwise single-line visible ASCII: embedded whitespace, CR/LF, controls,
 * and non-ASCII input are rejected instead of repaired or logged.
 */
fun normalizeCredentialForHeader(raw: String, label: String): String {
    val normalized = raw.trim(' ', '\t')
    if (normalized.any { it < '!' || it > '~' }) {
        throw InvalidCredentialException(
            "Invalid $label — enter or import a single-line value.",
        )
    }
    return normalized
}

fun Request.Builder.bearerAuthorization(
    rawCredential: String,
    label: String,
): Request.Builder {
    val credential = normalizeCredentialForHeader(rawCredential, label)
    if (credential.isEmpty()) return this
    return header("Authorization", "Bearer $credential")
}

fun Request.Builder.credentialHeader(
    name: String,
    rawCredential: String,
    label: String,
): Request.Builder {
    val credential = normalizeCredentialForHeader(rawCredential, label)
    if (credential.isEmpty()) return this
    return header(name, credential)
}
