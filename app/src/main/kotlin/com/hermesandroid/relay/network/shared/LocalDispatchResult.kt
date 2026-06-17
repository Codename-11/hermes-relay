package com.hermesandroid.relay.network.shared

import kotlinx.serialization.json.JsonObject

/**
 * Transport-neutral result of a phone-control dispatch.
 *
 * Shared vocabulary between the relay bridge path
 * ([com.hermesandroid.relay.network.relay.BridgeCommandHandler], which produces
 * it) and the upstream chat path
 * ([com.hermesandroid.relay.network.upstream.ChatHandler], which renders a
 * phone-action bubble from it). It is a passive DTO — not a client — so it
 * lives in `network.shared` to keep the upstream↔relay package fence intact
 * (ADR 34); neither side depends on the other to speak it.
 *
 *  - [status] — HTTP-style status of the dispatch (200 = ok).
 *  - [errorMessage] — human-readable failure text, or null on success.
 *  - [errorCode] — machine error code when the dispatch failed and was
 *    classified, or null.
 *  - [resultJson] — the raw result object, for callers that need
 *    action-specific fields (e.g. the resolved phone number from
 *    /search_contacts). Optional.
 */
data class LocalDispatchResult(
    val status: Int,
    val errorMessage: String?,
    val errorCode: String?,
    val resultJson: JsonObject?,
) {
    val isSuccess: Boolean get() = status in 200..299
}
