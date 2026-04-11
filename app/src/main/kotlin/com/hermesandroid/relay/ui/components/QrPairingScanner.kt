package com.hermesandroid.relay.ui.components

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors

/**
 * Parsed result from a Hermes pairing QR code.
 *
 * QR payload format (v1, extended 2026-04-11):
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
 * Top-level fields configure the direct-chat Hermes API server. The optional
 * [relay] block configures the Hermes-Relay WSS connection used by the
 * terminal and bridge channels — present only when the operator's
 * `hermes pair` invocation found a running relay and pre-registered a
 * pairing code with it. Old QRs without the relay block still parse cleanly
 * because the field is nullable and the JSON parser ignores unknown keys.
 */
@Serializable
data class HermesPairingPayload(
    val hermes: Int,
    val host: String,
    val port: Int = 8642,
    val key: String = "",
    val tls: Boolean = false,
    val relay: RelayPairing? = null
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
 */
@Serializable
data class RelayPairing(
    val url: String,
    val code: String
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Try to parse a scanned string as a Hermes pairing QR payload.
 * Returns null if it's not a valid Hermes QR (no "hermes":1 field).
 */
fun parseHermesPairingQr(raw: String): HermesPairingPayload? {
    return try {
        // Quick check: must contain "hermes" key with value 1
        val obj = json.decodeFromString<JsonObject>(raw)
        val version = obj["hermes"]?.jsonPrimitive?.int ?: return null
        if (version != 1) return null
        json.decodeFromString<HermesPairingPayload>(raw)
    } catch (_: Exception) {
        null
    }
}

/**
 * Full-screen QR code scanner overlay.
 * Detects Hermes pairing QR codes and calls [onPairingDetected] with the parsed payload.
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

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            cameraProviderRef.value?.unbindAll()
            cameraExecutor.shutdown()
        }
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

            Spacer(modifier = Modifier.height(32.dp))

            // Camera preview
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(16.dp)),
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
                                        if (mediaImage != null && !hasDetected.get()) {
                                            val inputImage = InputImage.fromMediaImage(
                                                mediaImage,
                                                imageProxy.imageInfo.rotationDegrees
                                            )
                                            barcodeScanner.process(inputImage)
                                                .addOnSuccessListener { barcodes ->
                                                    for (barcode in barcodes) {
                                                        if (barcode.valueType == Barcode.TYPE_TEXT ||
                                                            barcode.valueType == Barcode.TYPE_UNKNOWN
                                                        ) {
                                                            val rawValue = barcode.rawValue ?: continue
                                                            val payload = parseHermesPairingQr(rawValue)
                                                            if (payload != null && hasDetected.compareAndSet(false, true)) {
                                                                onPairingDetected(payload)
                                                                return@addOnSuccessListener
                                                            }
                                                        }
                                                    }
                                                }
                                                .addOnCompleteListener {
                                                    imageProxy.close()
                                                }
                                        } else {
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
