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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Phase 3 — accessibility `accessibility-runtime`
 *
 * Captures the phone's screen via the Android [MediaProjection] API, encodes
 * it as PNG, and publishes it to the relay so the agent can fetch it.
 *
 * ## Permission flow (blocker for Agent bridge-ui / UI to wire)
 *
 * `MediaProjection` cannot be granted by the app itself — it needs an
 * explicit user consent dialog per session, launched via
 * [MediaProjectionManager.createScreenCaptureIntent] from an `Activity`.
 * The resulting `Intent` is then passed to [MediaProjectionManager.getMediaProjection]
 * to build an actual projection.
 *
 * Because the grant lives on an `Activity` result, this class can only
 * provide the capture loop — **the consent flow must be wired by the
 * Bridge UI screen (Agent bridge-ui)**. Suggested contract:
 *
 *  1. `BridgeScreen` holds an `ActivityResultLauncher<Intent>` registered
 *     with `ActivityResultContracts.StartActivityForResult()`.
 *  2. On "Enable screenshots" tap, bridge-ui calls
 *     `MediaProjectionManager.createScreenCaptureIntent()` and launches it.
 *  3. On result, bridge-ui passes `(resultCode, data)` into a central holder
 *     (e.g. a ViewModel singleton or [MediaProjectionHolder]).
 *  4. [ScreenCapture] reads from that holder on each capture call and
 *     rebuilds a `MediaProjection` when needed. The projection will need
 *     to be backed by a foreground service on Android 10+ — Agent safety-rails
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
 *    is a clean server-side change that Agent bridge-server could land in parallel.
 *
 * 2. **Local-only screenshots** (fallback) — the phone writes the PNG to
 *    its own cache dir, emits `MEDIA:file://<cache_path>` in the response,
 *    and the agent fetches it via an on-device tool (N/A — the agent runs
 *    on the host, not the phone). So option 2 doesn't actually work.
 *
 * This class implements option 1 via [uploadViaMultipart]. If the endpoint
 * returns 404 (not yet deployed), we surface the error to the agent with a
 * clear message. **Agent bridge-server owns the `POST /media/upload` endpoint** — it's
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

        /**
         * ImageReader buffer count — we only need the latest frame, but the
         * reader requires at least 2 slots so the producer (VirtualDisplay)
         * can keep writing while we acquire the previous one.
         */
        private const val MAX_IMAGES = 2

        /** Capture timeout — if no frame arrives in this window, fail loudly. */
        private const val CAPTURE_TIMEOUT_MS = 2_500L
    }

    // === PHASE3-bridge-ui-followup: MediaProjection reuse fix ===
    //
    // Starting in Android 14 (API 34), each MediaProjection instance supports
    // exactly ONE createVirtualDisplay() call per session. Calling it a
    // second time throws with the error:
    //     "Don't re-use the resultData... Don't take multiple captures by
    //      invoking MediaProjection#createVirtualDisplay multiple times on
    //      the same instance."
    //
    // The old implementation built a fresh VirtualDisplay + ImageReader on
    // EVERY screenshot call and released it after, which worked on Android
    // 13 and below but breaks the second /screenshot request on 14+.
    //
    // Fix: keep the VirtualDisplay + ImageReader + HandlerThread alive
    // across captures, keyed by the MediaProjection instance. Rebuild only
    // when the projection reference changes (fresh consent grant) or the
    // dimensions change (orientation flip). The ImageReader's
    // setOnImageAvailableListener drains the buffer continuously; each
    // captureAndUpload() installs a one-shot [pendingCapture] callback
    // that fires on the next frame.
    //
    // Thread model:
    //  - `captureMutex` serializes concurrent captureAndUpload() calls
    //  - `cacheLock` protects the cached-state fields against the listener
    //     thread (which runs on `captureThread.looper`) racing with rebuild
    //  - The listener always acquires the latest frame; the pendingCapture
    //    deferred is completed with the encoded PNG bytes inside the
    //    listener callback on the capture thread.
    private val captureMutex = kotlinx.coroutines.sync.Mutex()
    private val cacheLock = Any()
    private var cachedProjection: MediaProjection? = null
    private var cachedReader: ImageReader? = null
    private var cachedDisplay: VirtualDisplay? = null
    private var cachedThread: HandlerThread? = null
    private var cachedHandler: Handler? = null
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var cachedDensity: Int = 0

    /**
     * Pending capture request, populated on [captureAndUpload] entry and
     * completed by the persistent ImageReader listener on the next frame.
     * `@Volatile` so the listener thread sees assignments made from the
     * capture coroutine. AtomicReference-style swap semantics via
     * [pendingCaptureRef] avoid a stale completion racing a new request.
     */
    private val pendingCaptureRef = java.util.concurrent.atomic.AtomicReference<
        kotlinx.coroutines.CompletableDeferred<ByteArray>?
    >(null)
    // === END PHASE3-bridge-ui-followup ===

    /**
     * Build the consent intent that `BridgeScreen` launches via an
     * `ActivityResultLauncher`. Callers should launch the intent with
     * `StartActivityForResult` and on success route the result to
     * `BridgeForegroundService.grantMediaProjection(...)`, which handles
     * the Android 14+ FGS-type-upgrade dance and stores the projection
     * inside the holder. Calling
     * [MediaProjectionHolder.acceptGrantInsideForegroundService] from
     * outside a foreground service is a known footgun — see that method's
     * docstring for the full explanation.
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
     *  - `MediaProjection not granted` → bridge-ui needs to run the consent flow
     *  - `relay URL not configured` / `session token missing` → pair first
     *  - `relay upload endpoint not found` → bridge-server needs to ship `/media/upload`
     *  - `capture timeout` → the virtual display never emitted a frame
     */
    suspend fun captureAndUpload(): Result<String> = withContext(Dispatchers.IO) {
        val projection = mediaProjectionProvider()
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "MediaProjection not granted — enable Bridge screenshots in the app"
                )
            )

        // Serialize concurrent capture requests so only one pendingCapture
        // is in flight at a time. The bridge command handler is the usual
        // caller and it's single-threaded per /screenshot request, but the
        // mutex keeps us honest if anything ever parallelizes.
        val pngBytes = try {
            captureMutex.withLock {
                captureFrame(projection)
            }
        } catch (e: Exception) {
            Log.w(TAG, "captureFrame failed: ${e.message}")
            return@withContext Result.failure(e)
        }

        uploadViaMultipart(pngBytes)
    }

    /**
     * Release any cached VirtualDisplay / ImageReader / HandlerThread. Call
     * this when the MediaProjection is revoked (the holder's `onStop`
     * callback, or an explicit revoke) so a subsequent grant starts with
     * a clean slate. Safe to call multiple times.
     *
     * NOTE: this does NOT stop the MediaProjection itself — that's the
     * holder's responsibility. We only own the capture pipeline built on
     * top of the projection.
     */
    fun releaseCache() {
        synchronized(cacheLock) {
            runCatching { cachedDisplay?.release() }
            runCatching { cachedReader?.close() }
            runCatching { cachedThread?.quitSafely() }
            cachedDisplay = null
            cachedReader = null
            cachedThread = null
            cachedHandler = null
            cachedProjection = null
            cachedWidth = 0
            cachedHeight = 0
            cachedDensity = 0
        }
        // Fail any pending capture with a descriptive error so the caller
        // doesn't hang for the timeout.
        pendingCaptureRef.getAndSet(null)?.takeIf { it.isActive }?.completeExceptionally(
            IOException("capture pipeline released before frame arrived")
        )
    }

    /**
     * Capture one frame from the cached VirtualDisplay + ImageReader,
     * rebuilding them if the projection reference changed or dimensions
     * drifted (orientation flip). Returns the PNG-encoded bytes.
     *
     * The ImageReader's persistent listener is set up once inside
     * [ensureCacheFor]. Each call here installs a fresh
     * [pendingCaptureRef] deferred that the listener completes on the
     * next frame; the listener drains non-waiting frames so the buffer
     * doesn't back up while nothing is asking for screenshots.
     */
    private suspend fun captureFrame(projection: MediaProjection): ByteArray {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        ensureCacheFor(projection, width, height, densityDpi)

        val deferred = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        // Replace any stale pending capture (shouldn't exist because of
        // the mutex, but defensive). If there's a previous one, fail it
        // so nobody ends up stuck.
        val previous = pendingCaptureRef.getAndSet(deferred)
        if (previous != null && previous.isActive) {
            previous.completeExceptionally(
                IOException("capture superseded by a newer request")
            )
        }

        return try {
            kotlinx.coroutines.withTimeout(CAPTURE_TIMEOUT_MS) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pendingCaptureRef.compareAndSet(deferred, null)
            throw IOException("screen capture timed out")
        } catch (t: Throwable) {
            pendingCaptureRef.compareAndSet(deferred, null)
            throw t
        }
    }

    /**
     * Build (or reuse) the cached VirtualDisplay + ImageReader + HandlerThread
     * for this projection. Rebuilds when:
     *
     *  - The projection reference has changed (new consent grant landed)
     *  - The captured dimensions don't match the current display (orientation
     *    flipped, foldable opened/closed, display switched)
     *
     * Must be called while [captureMutex] is held so the cached fields
     * aren't racing another capture.
     */
    private fun ensureCacheFor(
        projection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int,
    ) {
        synchronized(cacheLock) {
            val projectionChanged = cachedProjection !== projection
            val dimensionsChanged = width != cachedWidth || height != cachedHeight
            if (!projectionChanged && !dimensionsChanged && cachedDisplay != null && cachedReader != null) {
                return
            }

            // Tear down any stale cache before building fresh.
            runCatching { cachedDisplay?.release() }
            runCatching { cachedReader?.close() }
            runCatching { cachedThread?.quitSafely() }

            val thread = HandlerThread("HermesScreenCapture").apply { start() }
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, MAX_IMAGES
            )

            // Persistent listener — fires on every frame the VirtualDisplay
            // produces. If there's a pending capture request, we encode
            // the frame and complete it; otherwise we just drain the image
            // so the ImageReader buffer stays clear.
            reader.setOnImageAvailableListener({ r ->
                val waiter = pendingCaptureRef.get()
                if (waiter == null || !waiter.isActive) {
                    // Drain-and-drop — nobody's asking for a screenshot
                    // right now but frames are still arriving.
                    runCatching { r.acquireLatestImage() }.getOrNull()?.close()
                    return@setOnImageAvailableListener
                }
                var image: Image? = null
                try {
                    image = r.acquireLatestImage()
                        ?: return@setOnImageAvailableListener
                    val png = imageToPngBytes(image, width, height)
                    // Only complete the EXACT deferred we latched onto,
                    // so a stale listener firing after supersession doesn't
                    // resolve a new request.
                    if (pendingCaptureRef.compareAndSet(waiter, null)) {
                        waiter.complete(png)
                    }
                } catch (t: Throwable) {
                    if (pendingCaptureRef.compareAndSet(waiter, null)) {
                        waiter.completeExceptionally(t)
                    }
                } finally {
                    runCatching { image?.close() }
                }
            }, handler)

            val display = try {
                projection.createVirtualDisplay(
                    "hermes-bridge-capture",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    handler,
                )
            } catch (t: Throwable) {
                // Build failed — roll back so the next attempt tries fresh.
                runCatching { reader.close() }
                runCatching { thread.quitSafely() }
                throw t
            }

            // Register the MediaProjection.Callback so if the system stops
            // this projection out from under us, we release our cache
            // instead of holding dead handles. The holder's own callback
            // is separate — it clears projectionFlow; ours clears the
            // capture pipeline. Both are safe and complementary.
            try {
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        releaseCache()
                    }
                }, handler)
            } catch (_: Throwable) {
                // Some OEMs log but don't throw if the callback is already
                // registered by another party (e.g. the holder). Ignore.
            }

            cachedProjection = projection
            cachedReader = reader
            cachedDisplay = display
            cachedThread = thread
            cachedHandler = handler
            cachedWidth = width
            cachedHeight = height
            cachedDensity = densityDpi
            Log.i(TAG, "screen capture pipeline built ${width}x$height dpi=$densityDpi")
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
                                "Phase 3 bridge-server migration"
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
 * Holds the per-session [MediaProjection] grant.
 *
 * # Android 14+ rule
 *
 * `MediaProjectionManager.getMediaProjection()` MUST be called only after a
 * foreground service has called `startForeground()` with type
 * `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`, and that call must happen
 * AFTER the user has granted the consent dialog. Calling it before — even
 * if you're inside the launcher result callback — gives you a projection
 * that the system auto-revokes within a frame, with no error visible to
 * the app. Symptom: consent dialog appears, user allows, dialog closes,
 * grant evaporates. Sample-tested on Samsung S24 / Android 14, 2026-04-12.
 *
 * Because of that rule, this holder no longer constructs the projection
 * itself — it can only be populated from inside a foreground service that
 * has already called `startForeground(type=mediaProjection)`. The phone-side
 * entry point is `BridgeForegroundService.handleGrantedIntent`, which is
 * dispatched from `MainActivity.mediaProjectionLauncher`.
 *
 * The projection state is exposed as a [StateFlow] so the UI can react to
 * grants/revocations without polling. [BridgeViewModel] observes this and
 * calls `refreshPermissionStatus()` on every emission, so the green check
 * lights up immediately rather than waiting for the next lifecycle resume.
 *
 * Cleared on [revoke] (user disabled screenshots) or when the projection's
 * own `onStop` callback fires (system revoked it).
 */
object MediaProjectionHolder {
    private val _projectionFlow = kotlinx.coroutines.flow.MutableStateFlow<MediaProjection?>(null)

    /**
     * Reactive view of the current projection. Emits a fresh value every
     * time the holder is populated or cleared; null means "no active grant."
     */
    val projectionFlow: kotlinx.coroutines.flow.StateFlow<MediaProjection?> = _projectionFlow

    /**
     * Synchronous read used by [ScreenCapture] on each capture call. Always
     * matches the latest [projectionFlow] value.
     */
    val projection: MediaProjection? get() = _projectionFlow.value

    /**
     * Build a [MediaProjection] from a consent intent result and store it.
     * **Caller must already be inside a foreground service that has called
     * `startForeground(type=mediaProjection)`** — otherwise Android 14+ will
     * silently auto-revoke the projection. The canonical caller is
     * [com.hermesandroid.relay.bridge.BridgeForegroundService.handleGrantedIntent].
     *
     * Returns true on success, false on user-rejected consent or any
     * downstream API error.
     */
    fun acceptGrantInsideForegroundService(
        context: Context,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Log.i("MediaProjectionHolder", "consent rejected (resultCode=$resultCode)")
            return false
        }
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        val newProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.w(
                "MediaProjectionHolder",
                "getMediaProjection threw: ${t.message} — is this called inside a " +
                    "foreground service that already did startForeground(mediaProjection)?"
            )
            null
        } ?: return false

        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                _projectionFlow.value = null
            }
        }, Handler(android.os.Looper.getMainLooper()))

        _projectionFlow.value = newProjection
        Log.i("MediaProjectionHolder", "MediaProjection grant accepted and stored")
        return true
    }

    fun revoke() {
        try { _projectionFlow.value?.stop() } catch (_: Throwable) {}
        _projectionFlow.value = null
    }
}
