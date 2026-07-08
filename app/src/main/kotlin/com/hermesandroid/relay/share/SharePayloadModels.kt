package com.hermesandroid.relay.share

import kotlinx.serialization.Serializable

@Serializable
data class ShareCapturePayload(
    val raw_text: String,
    val mime_type: String = "text/plain",
    val requested_action: String = ShareAction.Save.canonicalName,
)

@Serializable
data class ShareCaptureProvenance(
    val device: String = "android",
    val source_app: String? = null,
    val timestamp: String,
)

@Serializable
data class ShareCaptureRouting(
    val destination: String = "command-center",
    val domain: String = "unknown",
    val confidence: Double = 0.6,
    val reason: String = "Android share sheet capture",
    val requested_action: String = ShareAction.Save.canonicalName,
)

@Serializable
data class CreateShareCaptureRequest(
    val payload: ShareCapturePayload,
    val summary: String,
    val provenance: ShareCaptureProvenance,
    val proposed_routing: ShareCaptureRouting,
    val ttl_seconds: Int = 259_200,
)

@Serializable
data class ShareCaptureResponse(
    val id: String? = null,
    val capture_id: String? = null,
) {
    val resolvedId: String?
        get() = id ?: capture_id
}

@Serializable
data class ActOnShareCaptureRequest(
    val action: String,
    val domain_tag: String = "unknown",
    val renderer: String = "android-review-ui",
    val renderer_id: String,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class ActOnShareCaptureResponse(
    val status: String? = null,
    val result: String? = null,
) {
    val terminalStatus: String
        get() = status ?: result ?: ""
}

enum class ShareAction(val label: String, val canonicalName: String) {
    Save("Save", "save"),
    Research("Research", "research"),
    Task("Task", "task"),
    OpenOnDesktop("Open on desktop", "open_on_desktop"),
}
