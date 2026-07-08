package com.hermesandroid.relay.share

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class CaptureShareHermesSender(
    baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : ShareCaptureContract {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun createCapture(payload: SharePayload): Result<CreatedShareCapture> =
        withContext(Dispatchers.IO) {
            runCatching {
                val requestPayload = CreateShareCaptureRequest(
                    payload = ShareCapturePayload(
                        raw_text = payload.text,
                        mime_type = payload.mimeType,
                        requested_action = ShareAction.Save.canonicalName,
                    ),
                    summary = payload.text,
                    provenance = ShareCaptureProvenance(
                        source_app = payload.sourceApp,
                        timestamp = payload.timestamp,
                    ),
                    proposed_routing = ShareCaptureRouting(
                        requested_action = ShareAction.Save.canonicalName,
                    ),
                )
                val request = Request.Builder()
                    .url("$baseUrl/api/captures")
                    .header("Authorization", "Bearer $apiKey")
                    .post(json.encodeToString(requestPayload).toRequestBody(JSON_MEDIA))
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        throw IOException("capture create failed: HTTP ${response.code}")
                    }
                    val capture = json.decodeFromString<ShareCaptureResponse>(body)
                    CreatedShareCapture(
                        id = capture.resolvedId
                            ?: throw IOException("capture create response missing id"),
                    )
                }
            }
        }

    override suspend fun actOnCapture(captureId: String, action: ShareAction): Result<ShareActionResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val requestPayload = ActOnShareCaptureRequest(
                    action = action.canonicalName,
                    renderer_id = captureId,
                )
                val request = Request.Builder()
                    .url("$baseUrl/api/captures/$captureId/act")
                    .header("Authorization", "Bearer $apiKey")
                    .post(json.encodeToString(requestPayload).toRequestBody(JSON_MEDIA))
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        throw IOException("capture action failed: HTTP ${response.code}")
                    }
                    val decoded = if (body.isBlank()) {
                        ActOnShareCaptureResponse(status = "handled")
                    } else {
                        json.decodeFromString<ActOnShareCaptureResponse>(body)
                    }
                    ShareActionResult(decoded.terminalStatus)
                }
            }
        }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
