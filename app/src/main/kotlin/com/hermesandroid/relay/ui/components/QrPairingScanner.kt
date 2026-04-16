package com.hermesandroid.relay.ui.components

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Parsed result from a Hermes pairing QR code.
 *
 * **Supported versions:** v1 and v2.
 *
 * v1 (legacy, pre-2026-04-11):
 * ```json
 * {
 *   "hermes": 1,
 *   "host": "172.16.24.250",
 *   "port": 8642,
 *   "key": "bearer-token",
 *   "tls": false,
 *   "relay": { "url": "ws://172.16.24.250:8767", "code": "ABCD12" }
 * }
 * ```
 *
 * v2 (security overhaul, 2026-04-11):
 * ```json
 * {
 *   "hermes": 2,
 *   "host": "192.168.1.100",
 *   "port": 8642,
 *   "key": "optional-api-key",
 *   "tls": true,
 *   "relay": {
 *     "url": "ws://192.168.1.100:8767",
 *     "code": "ABC123",
 *     "ttl_seconds": 2592000,
 *     "grants": { "terminal": 2592000, "bridge": 604800 },
 *     "transport_hint": "wss"
 *   },
 *   "sig": "base64-hmac-sha256"
 * }
 * ```
 *
 * The top-level fields configure the direct-chat Hermes API server. The
 * optional [relay] block configures the Hermes-Relay WSS connection used by
 * the terminal and bridge channels.
 *
 * **Forward/backward compatibility:**
 *  - `hermes` now has a default of `1` so v1 QRs without the field parse.
 *  - `sig` is captured but **not verified** — we don't have the server's
 *    HMAC secret. Stored for future verification and for operator audit.
 *    TODO: once the server exposes a pairing public key, verify.
 *  - Unknown fields are tolerated via `ignoreUnknownKeys = true`. v3+ QRs
 *    will still parse on this phone.
 *  - The `ttl_seconds`, `grants`, and `transport_hint` fields on [RelayPairing]
 *    are nullable so v1 QRs with only `url` + `code` still deserialize.
 *
 * Old QRs without the relay block still parse cleanly because the field is
 * nullable.
 */
@Serializable
data class HermesPairingPayload(
    val hermes: Int = 1,
    val host: String = "",
    val port: Int = 8642,
    val key: String = "",
    val tls: Boolean = false,
    val relay: RelayPairing? = null,
    val sig: String? = null,
) {
    /** Build the full API server URL from host, port, and tls flag. */
    val serverUrl: String
        get() = "${if (tls) "https" else "http"}://$host:$port"
}

/**
 * Relay connection details carried in a Hermes pairing QR.
 *
 * - [url] is the full WebSocket URL the phone should connect to, e.g.
 *   `ws://172.16.24.250:8767` for dev or `wss://relay.example.com:8767`
 *   for a TLS-fronted relay.
 * - [code] is a 6-char one-shot pairing code that the relay has already
 *   registered via its localhost-only `/pairing/register` endpoint. The
 *   phone sends this code in its first `system/auth` envelope; the relay
 *   consumes it and returns a long-lived session token for subsequent
 *   reconnects.
 * - [ttlSeconds] is an operator-preselected session TTL. When non-null the
 *   [com.hermesandroid.relay.ui.components.SessionTtlPickerDialog] defaults
 *   to this value so users can override it if they want. `0` means "never
 *   expire"; `null`/missing means "use the phone-side default".
 * - [grants] is an optional per-channel TTL map. Keys are channel names
 *   (`"chat"`, `"terminal"`, `"bridge"`); values are seconds. When the
 *   phone authenticates it includes these grants in its auth envelope so
 *   the relay can issue channel-specific tokens.
 * - [transportHint] is `"wss"` / `"ws"` / `null`. Drives the default TTL
 *   selection and the [com.hermesandroid.relay.ui.components.TransportSecurityBadge]
 *   label.
 */
@Serializable
data class RelayPairing(
    val url: String = "",
    val code: String = "",
    @SerialName("ttl_seconds")
    val ttlSeconds: Long? = null,
    val grants: Map<String, Long>? = null,
    @SerialName("transport_hint")
    val transportHint: String? = null,
)

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Try to parse a scanned string as a Hermes pairing QR payload.
 *
 * Accepts both v1 and v2 (or anything without a `hermes` field — we default
 * to `1`). Returns null when the payload is not valid JSON, has no `host`
 * field, or fails strict decoding.
 */
fun parseHermesPairingQr(raw: String): HermesPairingPayload? {
    return try {
        // Quick check: must contain a `host` field and be valid JSON. We no
        // longer reject based on the `hermes` version int — future v3+ QRs
        // should still parse on this phone so Bailey doesn't have to ship a
        // whole release to keep up with wire-format growth.
        val obj = json.decodeFromString<JsonObject>(raw)
        val version = obj["hermes"]?.jsonPrimitive?.intOrNull ?: 1
        if (version < 1) return null
        val decoded = json.decodeFromString<HermesPairingPayload>(raw)
        if (decoded.host.isBlank()) return null

        // TODO(security): verify `decoded.sig` against the server's HMAC
        // secret once the pairing protocol exposes a public verification
        // path. For now we parse and store the signature but do not reject
        // unsigned payloads — the phone has no way to fetch the server's
        // secret in-band.

        decoded
    } catch (_: Exception) {
        null
    }
}

/**
 * A bounding rect in *viewport* pixel coordinates (top-left origin), produced
 * by mapping a barcode's image-space bounding box through the camera rotation
 * + FILL_CENTER scale of the PreviewView. Used to drive the dynamic
 * "snap-to-QR" corner brackets in [ScannerCornersOverlay].
 */
private data class ViewportRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * One L-shaped corner bracket — origin point + the two arm endpoints + its
 * core/glow colors. Pulled out to a top-level class so the draw loop can be
 * a regular `for` over a typed list (Kotlin local data classes inside
 * lambdas have edge-case restrictions; safer to declare here).
 */
private data class CornerBracket(
    val origin: Offset,
    val horiz: Offset,
    val vert: Offset,
    val core: Color,
    val glow: Color,
)

/**
 * Map a barcode bounding box in **image buffer coordinates** through the
 * camera rotation and FILL_CENTER scaling of a square viewport, returning
 * the rect in viewport pixel coordinates.
 *
 * Math notes:
 *  - The camera buffer arrives in sensor orientation (typically landscape
 *    e.g. 1280×720), with [rotationDegrees] indicating how many degrees the
 *    image needs to be rotated CW to display upright on the device.
 *  - We rotate the bounding box first, then scale-and-offset it into the
 *    viewport. FILL_CENTER picks the *larger* of (vp/imgW, vp/imgH) so the
 *    image fully covers the viewport (cropping the longer side).
 *  - For 90°/270° rotations the post-rotation dimensions are swapped.
 */
private fun mapBoxToViewport(
    box: android.graphics.Rect,
    imgW: Int,
    imgH: Int,
    rotationDegrees: Int,
    viewportSize: IntSize,
): ViewportRect {
    // Rotate the box into display orientation.
    val rotated = when (rotationDegrees) {
        90 -> floatArrayOf(
            (imgH - box.bottom).toFloat(),
            box.left.toFloat(),
            (imgH - box.top).toFloat(),
            box.right.toFloat(),
        )
        180 -> floatArrayOf(
            (imgW - box.right).toFloat(),
            (imgH - box.bottom).toFloat(),
            (imgW - box.left).toFloat(),
            (imgH - box.top).toFloat(),
        )
        270 -> floatArrayOf(
            box.top.toFloat(),
            (imgW - box.right).toFloat(),
            box.bottom.toFloat(),
            (imgW - box.left).toFloat(),
        )
        else -> floatArrayOf(
            box.left.toFloat(),
            box.top.toFloat(),
            box.right.toFloat(),
            box.bottom.toFloat(),
        )
    }
    val rotW = if (rotationDegrees == 90 || rotationDegrees == 270) imgH else imgW
    val rotH = if (rotationDegrees == 90 || rotationDegrees == 270) imgW else imgH

    // FILL_CENTER: the image is scaled to fully cover the viewport, then
    // centered. The visible portion is the central `viewport`-sized window
    // of the scaled image. We map by applying the scale + the centering offset.
    val vpW = viewportSize.width.toFloat()
    val vpH = viewportSize.height.toFloat()
    val scale = max(vpW / rotW, vpH / rotH)
    val scaledW = rotW * scale
    val scaledH = rotH * scale
    val offsetX = (vpW - scaledW) / 2f
    val offsetY = (vpH - scaledH) / 2f

    return ViewportRect(
        left = (rotated[0] * scale + offsetX).coerceIn(0f, vpW),
        top = (rotated[1] * scale + offsetY).coerceIn(0f, vpH),
        right = (rotated[2] * scale + offsetX).coerceIn(0f, vpW),
        bottom = (rotated[3] * scale + offsetY).coerceIn(0f, vpH),
    )
}

/**
 * Full-screen QR code scanner overlay.
 *
 * Layout:
 *  - Header bar with a Close button + "Scan Hermes QR" title
 *  - Square camera viewport at 50% of the screen width, with rounded corners
 *  - Sci-fi L-bracket overlay drawn on top of the viewport. When no QR is
 *    in frame the brackets sit at a centered "ready" position with a slow
 *    pulse animation. When a barcode is detected the brackets snap (with
 *    a spring) to the bounding box of the QR — defining a live "lock-on"
 *    indicator. Brackets release back to the centered ready state ~600ms
 *    after the QR leaves the frame.
 *  - Instruction copy below
 *
 * Detects Hermes pairing QR codes and calls [onPairingDetected] with the
 * parsed payload after a brief delay, so the user actually sees the lock-on
 * snap animation before the screen transitions away.
 */
@Composable
fun QrPairingScanner(
    onPairingDetected: (HermesPairingPayload) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // AtomicBoolean for thread-safe detection flag (accessed from camera executor thread)
    val hasDetected = remember { AtomicBoolean(false) }
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Viewport is sized at 50% of the screen width via Modifier.fillMaxWidth(0.5f)
    // below — comfortable scan target without dominating the screen, and
    // matches the "futuristic scan port" aesthetic the brackets are drawn around.

    // Live viewport pixel size — captured via onSizeChanged so the analyzer
    // thread can compute viewport-space coordinates for the corner brackets.
    var viewportSizePx by remember { mutableStateOf(IntSize.Zero) }

    // Latest detected QR bounding box in viewport pixel coordinates. Updated
    // continuously by the analyzer for any successfully decoded QR (not just
    // valid Hermes ones). null = no current detection → brackets fall back
    // to centered ready position.
    var detectedBox by remember { mutableStateOf<ViewportRect?>(null) }
    // Frame counter from the analyzer — bumped every analyzed frame so the
    // "release back to ready position" timer can detect when detections stop
    // arriving. Volatile because it's written from the camera executor thread
    // and read from the main thread coroutine.
    var lastDetectionAtMs by remember { mutableStateOf(0L) }

    // Lock-on state — set true when we've parsed a valid Hermes payload.
    // Drives the brief settle delay before navigating away so the user sees
    // the snap animation actually land on the QR.
    var lockedPayload by remember { mutableStateOf<HermesPairingPayload?>(null) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            cameraProviderRef.value?.unbindAll()
            cameraExecutor.shutdown()
        }
    }

    // Release the brackets back to the centered ready position when no
    // detection has arrived for ~600ms. Otherwise a stale detection from
    // a frame ago would keep the brackets "stuck" off-center after the QR
    // has left the frame.
    LaunchedEffect(lastDetectionAtMs) {
        if (detectedBox == null) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        if (System.currentTimeMillis() - lastDetectionAtMs >= 600) {
            detectedBox = null
        }
    }

    // After we lock on a valid Hermes payload, hold the snap animation for
    // ~450ms so the user perceives the lock-on, then forward to onPairingDetected.
    LaunchedEffect(lockedPayload) {
        val payload = lockedPayload ?: return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        onPairingDetected(payload)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.95f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close scanner",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "Scan Hermes QR",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Camera preview viewport (75% of screen width, square). Wider
            // than the original 50% pass — a generous scan target makes
            // framing the QR effortless and gives the bracket animations
            // more room to read as a "lock-on" instead of a tiny pop.
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .onSizeChanged { viewportSizePx = it },
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }

                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            cameraProviderRef.value = cameraProvider

                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val barcodeScanner = BarcodeScanning.getClient()

                            @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { analysis ->
                                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                        val mediaImage = imageProxy.image
                                        if (mediaImage == null || hasDetected.get()) {
                                            imageProxy.close()
                                            return@setAnalyzer
                                        }
                                        val rotation = imageProxy.imageInfo.rotationDegrees
                                        val imgW = mediaImage.width
                                        val imgH = mediaImage.height
                                        val inputImage = InputImage.fromMediaImage(
                                            mediaImage,
                                            rotation
                                        )
                                        barcodeScanner.process(inputImage)
                                            .addOnSuccessListener { barcodes ->
                                                // Drive the brackets off ANY decoded QR so
                                                // the lock-on snap is visible even before
                                                // we've parsed it as a valid Hermes payload.
                                                val first = barcodes.firstOrNull { b ->
                                                    b.boundingBox != null &&
                                                        (b.valueType == Barcode.TYPE_TEXT ||
                                                            b.valueType == Barcode.TYPE_UNKNOWN)
                                                }
                                                val box = first?.boundingBox
                                                val vpSize = viewportSizePx
                                                if (box != null && vpSize.width > 0 && vpSize.height > 0) {
                                                    detectedBox = mapBoxToViewport(
                                                        box = box,
                                                        imgW = imgW,
                                                        imgH = imgH,
                                                        rotationDegrees = rotation,
                                                        viewportSize = vpSize,
                                                    )
                                                    lastDetectionAtMs = System.currentTimeMillis()
                                                }
                                                // Then try to parse for the actual lock.
                                                for (barcode in barcodes) {
                                                    if (barcode.valueType == Barcode.TYPE_TEXT ||
                                                        barcode.valueType == Barcode.TYPE_UNKNOWN
                                                    ) {
                                                        val rawValue = barcode.rawValue ?: continue
                                                        val payload = parseHermesPairingQr(rawValue)
                                                        if (payload != null && hasDetected.compareAndSet(false, true)) {
                                                            lockedPayload = payload
                                                            return@addOnSuccessListener
                                                        }
                                                    }
                                                }
                                            }
                                            .addOnCompleteListener {
                                                imageProxy.close()
                                            }
                                    }
                                }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                Log.e("QrPairingScanner", "Camera bind failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Sci-fi L-bracket overlay. When detectedBox is null the
                // brackets sit at a centered ready inset; when present they
                // spring to the bounding box of the live detection.
                ScannerCornersOverlay(
                    detected = detectedBox,
                    locked = lockedPayload != null,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Instructions
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "Point at a Hermes pairing QR code",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Generate one on your server with: hermes-pair",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Sci-fi L-bracket overlay drawn on top of the camera viewport. Renders four
 * corner brackets that:
 *
 *  - Sit at a centered "ready" inset (~12% of viewport from each edge) when
 *    no QR is detected, with a slow breathing pulse on alpha.
 *  - Spring to the bounding box of a live detection when [detected] is non-null
 *    — animated independently per side so the snap reads as a genuine "lock-on"
 *    rather than a translation.
 *  - Switch from the primary cyan tint to a vivid green when [locked] is true,
 *    so the brief settle delay before navigation reads as confirmation.
 *
 * The brackets themselves are drawn with `Stroke(cap = StrokeCap.Round)` so
 * the L-corners blend cleanly. Two passes — a soft outer glow at low alpha
 * + a crisp inner stroke — give the futuristic glow without needing actual
 * blur shaders.
 */
@Composable
private fun ScannerCornersOverlay(
    detected: ViewportRect?,
    locked: Boolean,
    modifier: Modifier = Modifier,
) {
    // Themed idle: two-tone gradient between primary (top-left/bottom-right)
    // and tertiary (top-right/bottom-left). Both are brand purples in this
    // theme, so the corners read as cohesive but not flat.
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    // Vivid Material A400 success green — much more saturated than the
    // generic 500-shade we had before, reads as "lock-on confirmed" instead
    // of "neutral status indicator".
    val successCore = Color(0xFF00E676)
    val successGlow = Color(0xFF69F0AE)

    // Slow breathing pulse on alpha when idle. Locked state stays solid +
    // gets its own one-shot ramp so the green burst is unmistakable.
    val infiniteTransition = rememberInfiniteTransition(label = "scan-corners")
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idle-pulse",
    )

    // One-shot ramp that fires when `locked` flips true. Drives the
    // outward scale pop on the corners + the green tint flash overlay.
    val lockRamp by animateFloatAsState(
        targetValue = if (locked) 1f else 0f,
        animationSpec = if (locked) {
            spring(dampingRatio = 0.55f, stiffness = 220f)
        } else {
            tween(180)
        },
        label = "lock-ramp",
    )

    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    // Compute the target rect (left/top/right/bottom in px). When idle we
    // inset from the viewport edges by ~10%; when detected we use the
    // detected box. Each side animates independently with a snappy spring.
    val readyInsetFrac = 0.10f
    val targetLeft: Float
    val targetTop: Float
    val targetRight: Float
    val targetBottom: Float
    if (detected != null) {
        targetLeft = detected.left
        targetTop = detected.top
        targetRight = detected.right
        targetBottom = detected.bottom
    } else if (size.width > 0 && size.height > 0) {
        targetLeft = size.width * readyInsetFrac
        targetTop = size.height * readyInsetFrac
        targetRight = size.width * (1f - readyInsetFrac)
        targetBottom = size.height * (1f - readyInsetFrac)
    } else {
        targetLeft = 0f
        targetTop = 0f
        targetRight = 0f
        targetBottom = 0f
    }

    val springSpec = spring<Float>(
        dampingRatio = 0.7f,
        stiffness = 280f,
    )
    val animLeft by animateFloatAsState(targetLeft, springSpec, label = "snap-l")
    val animTop by animateFloatAsState(targetTop, springSpec, label = "snap-t")
    val animRight by animateFloatAsState(targetRight, springSpec, label = "snap-r")
    val animBottom by animateFloatAsState(targetBottom, springSpec, label = "snap-b")

    Canvas(
        modifier = modifier.onSizeChanged { size = it }
    ) {
        if (animRight <= animLeft || animBottom <= animTop) return@Canvas

        // On lock, push the brackets outward by ~10dp so they pop OUT past
        // the QR boundary like a "got it" flourish, then settle.
        val popPx = with(density) { 10.dp.toPx() } * lockRamp
        val left = animLeft - popPx
        val top = animTop - popPx
        val right = animRight + popPx
        val bottom = animBottom + popPx

        val w = right - left
        val h = bottom - top
        // Corner arm length scales with the smaller box side so the brackets
        // stay proportional whether snapped to a small QR or sitting at the
        // ready inset. Bumped from 22% → 26% for a more pronounced sci-fi look.
        val arm = (kotlin.math.min(w, h) * 0.26f).coerceAtLeast(with(density) { 18.dp.toPx() })
        val coreStroke = with(density) { 4.dp.toPx() }
        val glowStroke = with(density) { 14.dp.toPx() }
        val pipRadius = with(density) { 3.dp.toPx() }

        // Idle alpha breathes; detected/locked are solid + amped by the lockRamp.
        val baseAlpha = if (detected != null || locked) 1f else idlePulse
        val glowAlpha = if (detected != null || locked) {
            0.55f + 0.25f * lockRamp
        } else {
            idlePulse * 0.35f
        }

        // Diagonal pairing: TL+BR get the primary; TR+BL get the tertiary.
        // Gives a cohesive two-tone "diagonal scan" feel. When locked, all
        // four corners flip to the success green.
        val tlBrCore = if (locked) successCore.copy(alpha = baseAlpha) else primary.copy(alpha = baseAlpha)
        val trBlCore = if (locked) successCore.copy(alpha = baseAlpha) else tertiary.copy(alpha = baseAlpha)
        val tlBrGlow = if (locked) successGlow.copy(alpha = glowAlpha) else primary.copy(alpha = glowAlpha)
        val trBlGlow = if (locked) successGlow.copy(alpha = glowAlpha) else tertiary.copy(alpha = glowAlpha)
        val pipColor = if (locked) successGlow.copy(alpha = baseAlpha) else onPrimary.copy(alpha = baseAlpha * 0.85f)

        val corners = listOf(
            CornerBracket(
                origin = Offset(left, top),
                horiz = Offset(left + arm, top),
                vert = Offset(left, top + arm),
                core = tlBrCore,
                glow = tlBrGlow,
            ),
            CornerBracket(
                origin = Offset(right, top),
                horiz = Offset(right - arm, top),
                vert = Offset(right, top + arm),
                core = trBlCore,
                glow = trBlGlow,
            ),
            CornerBracket(
                origin = Offset(left, bottom),
                horiz = Offset(left + arm, bottom),
                vert = Offset(left, bottom - arm),
                core = trBlCore,
                glow = trBlGlow,
            ),
            CornerBracket(
                origin = Offset(right, bottom),
                horiz = Offset(right - arm, bottom),
                vert = Offset(right, bottom - arm),
                core = tlBrCore,
                glow = tlBrGlow,
            ),
        )

        // Pass 1 — wide soft glow underneath (low alpha, fat stroke)
        for (c in corners) {
            drawLine(
                color = c.glow,
                start = c.origin,
                end = c.horiz,
                strokeWidth = glowStroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = c.glow,
                start = c.origin,
                end = c.vert,
                strokeWidth = glowStroke,
                cap = StrokeCap.Round,
            )
        }
        // Pass 2 — crisp core stroke
        for (c in corners) {
            drawLine(
                color = c.core,
                start = c.origin,
                end = c.horiz,
                strokeWidth = coreStroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = c.core,
                start = c.origin,
                end = c.vert,
                strokeWidth = coreStroke,
                cap = StrokeCap.Round,
            )
        }
        // Pass 3 — pip dots at each L-corner origin. Tiny detail that reads
        // as "targeting reticle" rather than "rounded rectangle".
        for (c in corners) {
            drawCircle(
                color = pipColor,
                radius = pipRadius,
                center = c.origin,
            )
        }

        // Lock flash — brief green tint over the entire viewport that fades
        // out as lockRamp settles. Driven by the same spring as the corner
        // pop so they read as one event.
        if (lockRamp > 0f) {
            drawRect(
                color = successCore.copy(alpha = 0.18f * lockRamp),
                topLeft = Offset.Zero,
                size = this.size,
            )
        }
    }
}
