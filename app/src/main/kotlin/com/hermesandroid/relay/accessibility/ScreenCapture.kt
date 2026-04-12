package com.hermesandroid.relay.accessibility

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Phase 3 — γ `accessibility-runtime`
 *
 * Captures the phone's screen via the Android [MediaProjection] API, encodes
 * it as PNG, and publishes it to the relay so the agent can fetch it.
 *
 * ## Permission flow (blocker for Agent δ / UI to wire)
 *
 * `MediaProjection` cannot be granted by the app itself — it needs an
 * explicit user consent dialog per session, launched via
 * [MediaProjectionManager.createScreenCaptureIntent] from an `Activity`.
 * The resulting `Intent` is then passed to [MediaProjectionManager.getMediaProjection]
 * to build an actual projection.
 *
 * Because the grant lives on an `Activity` result, this class can only
 * provide the capture loop — **the consent flow must be wired by the
 * Bridge UI screen (Agent δ)**. Suggested contract:
 *
 *  1. `BridgeScreen` holds an `ActivityResultLauncher<Intent>` registered
 *     with `ActivityResultContracts.StartActivityForResult()`.
 *  2. On "Enable screenshots" tap, δ calls
 *     `MediaProjectionManager.createScreenCaptureIntent()` and launches it.
 *  3. On result, δ passes `(resultCode, data)` into a central holder
 *     (e.g. a ViewModel singleton or [MediaProjectionHolder]).
 *  4. [ScreenCapture] reads from that holder on each capture call and
 *     rebuilds a `MediaProjection` when needed. The projection will need
 *     to be backed by a foreground service on Android 10+ — Agent ζ
 *     owns the persistent-notification service declaration.
 *
 * Until that wiring lands, this class will fail gracefully with
 * `Result.failure(IllegalStateException("MediaProjection not granted"))`
 * and the agent will see the error text in the `bridge.response` body.
 *
 * ## Upload path — current limitation
 *
 * The relay's existing `/media/register` endpoint is **loopback-only and
 * path-based** (`plugin/relay/media.py`) — it registers a file path that
 * already exists on the relay host, with an optional content type and
 * filename. The phone is by definition not on the relay host, so it has no
 * usable path to register.
 *
 * Two options exist for bridging this gap:
 *
 * 1. **New relay endpoint** (preferred) — `POST /media/upload` accepts
 *    `multipart/form-data` with the PNG bytes, writes to a sandboxed tmp
 *    dir (`tempfile.gettempdir()`), then calls `MediaRegistry.register()`
 *    on the resulting path. Wire shape mirrors `/voice/transcribe`. This
 *    is a clean server-side change that Agent α could land in parallel.
 *
 * 2. **Local-only screenshots** (fallback) — the phone writes the PNG to
 *    its own cache dir, emits `MEDIA:file://<cache_path>` in the response,
 *    and the agent fetches it via an on-device tool (N/A — the agent runs
 *    on the host, not the phone). So option 2 doesn't actually work.
 *
 * This class implements option 1 via [uploadViaMultipart]. If the endpoint
 * returns 404 (not yet deployed), we surface the error to the agent with a
 * clear message. **Agent α owns the `POST /media/upload` endpoint** — it's
 * the only remaining server-side work to complete Tier 1 screenshots.
 *
 * ## Thread model
 *
 * `ImageReader` delivers frames on a background `HandlerThread` we own.
 * The PNG encode runs on [Dispatchers.IO] via [withContext]. The HTTP
 * upload is also IO-dispatched. All three can be cancelled by the caller.
 */
class ScreenCapture(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val relayUrlProvider: () -> String?,
    private val sessionTokenProvider: suspend () -> String?,
    private val mediaProjectionProvider: () -> MediaProjection?,
) {

    companion object {
        private const val TAG = "ScreenCapture"

        /** PNG quality is a no-op for PNG, but Bitmap.compress expects the arg. */
        private const val PNG_QUALITY = 100

        /** ImageReader buffer count — 2 is enough for our one-shot-at-a-time use. */
        private const val MAX_IMAGES = 2

        /** Capture timeout — if no frame arrives in this window, fail loudly. */
        private const val CAPTURE_TIMEOUT_MS = 2_500L
    }

    /**
     * Build the consent intent that `BridgeScreen` launches via an
     * `ActivityResultLauncher`. Callers should launch the intent with
     * `StartActivityForResult` and on success call
     * [MediaProjectionHolder.onGranted] with the result code + data Intent.
     */
    fun createConsentIntent(): Intent =
        (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .createScreenCaptureIntent()

    /**
     * Capture one frame and upload it to the relay. Returns the inbound
     * media marker (`MEDIA:hermes-relay://<token>`) on success so the
     * bridge command handler can embed it directly in the `bridge.response`
     * result.
     *
     * Fails fast and with clear messaging on every expected error path:
     *
     *  - `MediaProjection not granted` → δ needs to run the consent flow
     *  - `relay URL not configured` / `session token missing` → pair first
     *  - `relay upload endpoint not found` → α needs to ship `/media/upload`
     *  - `capture timeout` → the virtual display never emitted a frame
     */
    suspend fun captureAndUpload(): Result<String> = withContext(Dispatchers.IO) {
        val projection = mediaProjectionProvider()
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "MediaProjection not granted — enable Bridge screenshots in the app"
                )
            )

        val pngBytes = try {
            captureOnce(projection)
        } catch (e: Exception) {
            Log.w(TAG, "captureOnce failed: ${e.message}")
            return@withContext Result.failure(e)
        }

        uploadViaMultipart(pngBytes)
    }

    /**
     * Synchronously (inside a suspendCancellableCoroutine) capture exactly
     * one frame from a freshly-built VirtualDisplay + ImageReader, encode
     * it to PNG, and return the bytes.
     */
    private suspend fun captureOnce(projection: MediaProjection): ByteArray =
        suspendCancellableCoroutine { cont ->
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val densityDpi = metrics.densityDpi

            val reader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, MAX_IMAGES
            )

            val captureThread = HandlerThread("HermesScreenCapture").apply { start() }
            val captureHandler = Handler(captureThread.looper)

            var virtualDisplay: VirtualDisplay? = null
            var resolved = false

            fun cleanup() {
                try { virtualDisplay?.release() } catch (_: Throwable) {}
                try { reader.close() } catch (_: Throwable) {}
                try { captureThread.quitSafely() } catch (_: Throwable) {}
            }

            val postedTimeout = Handler(captureThread.looper)
            postedTimeout.postDelayed({
                if (!resolved) {
                    resolved = true
                    cleanup()
                    if (cont.isActive) {
                        cont.resumeWith(
                            Result.failure(IOException("screen capture timed out"))
                        )
                    }
                }
            }, CAPTURE_TIMEOUT_MS)

            reader.setOnImageAvailableListener({ r ->
                if (resolved) return@setOnImageAvailableListener
                var image: Image? = null
                try {
                    image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val png = imageToPngBytes(image, width, height)
                    resolved = true
                    cleanup()
                    if (cont.isActive) cont.resume(png)
                } catch (t: Throwable) {
                    resolved = true
                    cleanup()
                    if (cont.isActive) {
                        cont.resumeWith(Result.failure(t))
                    }
                } finally {
                    try { image?.close() } catch (_: Throwable) {}
                }
            }, captureHandler)

            try {
                virtualDisplay = projection.createVirtualDisplay(
                    "hermes-bridge-capture",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    captureHandler
                )
            } catch (t: Throwable) {
                resolved = true
                cleanup()
                if (cont.isActive) {
                    cont.resumeWith(Result.failure(t))
                }
            }

            cont.invokeOnCancellation {
                if (!resolved) {
                    resolved = true
                    cleanup()
                }
            }
        }

    /**
     * Convert an [Image] from `ImageReader` into a PNG byte array. The
     * plane's `rowStride` may be wider than `width * 4` — we must crop
     * the stride padding before [Bitmap.copyPixelsFromBuffer].
     */
    private fun imageToPngBytes(image: Image, width: Int, height: Int): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmapWidth = width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop to the exact screen width if rowStride padding widened us.
        val cropped = if (bitmapWidth != width) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                bitmap.recycle()
            }
        } else bitmap

        val out = ByteArrayOutputStream(256 * 1024)
        cropped.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        cropped.recycle()
        return out.toByteArray()
    }

    /**
     * Upload PNG bytes to the relay via `POST /media/upload` (multipart).
     *
     * **Blocker:** this endpoint does not exist server-side yet (see class
     * docstring). When it ships, it should accept a single `file` part and
     * return `{"ok": true, "token": "..."}` — the same JSON shape as
     * `/media/register`, minus the loopback restriction and with the
     * server sandboxing the temp-file write internally.
     */
    private suspend fun uploadViaMultipart(pngBytes: ByteArray): Result<String> {
        val relayUrl = relayUrlProvider()?.trim().orEmpty()
        if (relayUrl.isEmpty()) {
            return Result.failure(IllegalStateException("Relay URL not configured"))
        }
        val sessionToken = sessionTokenProvider()
        if (sessionToken.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException("Relay not paired — session token missing")
            )
        }

        val httpBase = relayUrl
            .replace(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replace(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            .trimEnd('/')

        val url = "$httpBase/media/upload"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "hermes-screenshot-${System.currentTimeMillis()}.png",
                pngBytes.toRequestBody("image/png".toMediaType())
            )
            .build()

        val fastClient = httpClient.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "application/json")
            .build()

        return try {
            fastClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val raw = response.body?.string().orEmpty()
                        val token = extractToken(raw)
                        if (token.isNullOrBlank()) {
                            Result.failure(
                                IOException("relay returned success but no token")
                            )
                        } else {
                            Result.success("MEDIA:hermes-relay://$token")
                        }
                    }
                    404 -> Result.failure(
                        IOException(
                            "relay /media/upload endpoint not found — server needs " +
                                "Phase 3 α migration"
                        )
                    )
                    401, 403 -> Result.failure(
                        IOException("unauthorized — re-pair with the relay")
                    )
                    413 -> Result.failure(
                        IOException("screenshot too large for relay media cap")
                    )
                    in 500..599 -> Result.failure(
                        IOException("relay error (HTTP ${response.code})")
                    )
                    else -> Result.failure(
                        IOException("HTTP ${response.code}: ${response.message}")
                    )
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "uploadViaMultipart failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Minimal JSON token extractor — the response body is small and has a
     * single interesting key. We avoid pulling in a full `Json` parse here
     * because `ScreenCapture` is already a heavy dependency graph (Android
     * media + OkHttp) and we don't want to add kotlinx.serialization
     * wiring for a 40-byte response.
     */
    private fun extractToken(body: String): String? {
        val match = Regex("""\"token\"\s*:\s*\"([^\"]+)\"""").find(body)
        return match?.groupValues?.get(1)
    }
}

/**
 * Holds the per-session [MediaProjection] grant. The Bridge UI (Agent δ)
 * calls [onGranted] from its `ActivityResultLauncher` callback; [ScreenCapture]
 * reads [projection] through the lambda passed to its constructor.
 *
 * Cleared on [revoke] (user disabled screenshots) or when the projection's
 * own `onStop` callback fires (system revoked it).
 */
object MediaProjectionHolder {
    @Volatile
    private var _projection: MediaProjection? = null

    val projection: MediaProjection? get() = _projection

    /**
     * Call from the Bridge UI's `ActivityResultLauncher` callback.
     * Returns true on success, false on user-rejected consent.
     */
    fun onGranted(context: Context, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            return false
        }
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        val newProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.w("MediaProjectionHolder", "getMediaProjection threw: ${t.message}")
            null
        } ?: return false

        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                _projection = null
            }
        }, Handler(android.os.Looper.getMainLooper()))

        _projection = newProjection
        return true
    }

    fun revoke() {
        try { _projection?.stop() } catch (_: Throwable) {}
        _projection = null
    }
}
