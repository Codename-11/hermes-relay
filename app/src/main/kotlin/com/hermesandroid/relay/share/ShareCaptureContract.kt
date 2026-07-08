package com.hermesandroid.relay.share

interface ShareCaptureContract {
    suspend fun createCapture(payload: SharePayload): Result<CreatedShareCapture>
    suspend fun actOnCapture(captureId: String, action: ShareAction): Result<ShareActionResult>
}

data class CreatedShareCapture(val id: String)

data class ShareActionResult(val status: String) {
    val isTerminalSuccess: Boolean
        get() = status == "handled" || status == "already-handled"
}
