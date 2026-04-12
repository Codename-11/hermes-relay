package com.hermesandroid.relay.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.hermesandroid.relay.viewmodel.TerminalViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Composable hosting xterm.js inside a WebView.
 *
 * Loads `file:///android_asset/terminal/index.html`, installs a JS bridge
 * named `AndroidBridge`, and pipes `TerminalViewModel.outputFlow` into the
 * terminal via `window.writeTerminal`.
 *
 * Bridge methods are invoked on a WebView-owned binder thread — the ViewModel
 * it forwards to uses thread-safe state flows, so no extra synchronization
 * is needed here.
 */
@Composable
fun TerminalWebView(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier,
    fontScale: StateFlow<Float> = remember { MutableStateFlow(1.0f) }
) {
    val context = LocalContext.current
    // Background matches xterm's theme so there's no white flash while the
    // HTML boots. Kept as a constant rather than pulling from MaterialTheme
    // because the WebView host color must match the xterm theme exactly.
    val webViewBackground = 0xFF1A1A2E.toInt()
    // Device density — we pass CSS-pixel dimensions to xterm explicitly in
    // the layout listener below because Chromium's internal viewport on
    // Android WebView doesn't reliably update when Compose resizes the
    // parent View. Without this, xterm latches at rows=1 on first layout
    // and never grows, so command output scrolls straight off the viewport.
    val density = context.resources.displayMetrics.density

    val webView = remember {
        @SuppressLint("SetJavaScriptEnabled")
        WebView(context).apply {
            setBackgroundColor(webViewBackground)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = false
            settings.useWideViewPort = false
            settings.textZoom = 100 // prevent system font-size from scaling xterm
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true
            // Intentionally NOT setting LAYER_TYPE_HARDWARE — on Samsung WebView
            // it can latch the first rendered frame on the GPU layer and fail
            // to propagate subsequent xterm DOM updates, so the initial prompt
            // draws but command output never appears. Default layer type lets
            // Chromium pick its own compositing path.

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url?.toString() ?: return false
                    // Any navigation away from our asset bundle goes to the system browser.
                    if (!url.startsWith("file:///android_asset/terminal/")) {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) { /* no browser available */ }
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // Re-fire refit once the JS side is actually defined.
                    // The layout listener above may have fired before
                    // `window.refit` existed; fire it here with current
                    // dimensions so the first sensible fit lands regardless
                    // of event ordering.
                    val widthCss = (view.width / density).toInt()
                    val heightCss = (view.height / density).toInt()
                    if (widthCss > 0 && heightCss > 0) {
                        view.post {
                            view.evaluateJavascript(
                                "if (window.refit) { window.refit($widthCss, $heightCss); }",
                                null
                            )
                        }
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    message ?: return false
                    android.util.Log.d(
                        "TerminalWebView",
                        "${message.messageLevel()} [${message.sourceId()}:${message.lineNumber()}] ${message.message()}"
                    )
                    return true
                }
            }

            addJavascriptInterface(TerminalBridge(viewModel, this), "AndroidBridge")

            // Force xterm to refit every time Compose gives us a new layout.
            // Compose often measures the WebView at ~0-pixel-tall during the
            // AnimatedContent tab-switch animation and during IME transitions,
            // so the initial fit latches at rows=1. We forward the real CSS
            // dimensions explicitly here because Chromium's WebView doesn't
            // reliably propagate native-side resize to the HTML viewport — if
            // we rely on CSS `height:100%` or the `window.resize` event, the
            // internal viewport stays frozen at the first bad measurement.
            addOnLayoutChangeListener { view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom ->
                val widthPx = right - left
                val heightPx = bottom - top
                if (widthPx <= 0 || heightPx <= 0) return@addOnLayoutChangeListener
                val widthCss = (widthPx / density).toInt()
                val heightCss = (heightPx / density).toInt()
                // Defer to the next UI frame so layout has settled and the
                // WebView is attached to the Compose hierarchy before we run
                // JS against it.
                view.post {
                    (view as WebView).evaluateJavascript(
                        "if (window.refit) { window.refit($widthCss, $heightCss); }",
                        null
                    )
                }
            }

            loadUrl("file:///android_asset/terminal/index.html")
        }
    }

    // Stream outputs from the ViewModel into the WebView.
    LaunchedEffect(webView, viewModel) {
        viewModel.outputFlow.collect { base64 ->
            android.util.Log.d("TerminalWebView", "writeTerminal: ${base64.length} b64 chars")
            // evaluateJavascript must run on the UI thread; LaunchedEffect's
            // dispatcher is Main.
            webView.evaluateJavascript("window.writeTerminal('$base64');", null)
        }
    }

    // Push the user's global font scale into xterm. The base size matches
    // index.html (`fontSize: 13`), and `window.setFontSize` already calls
    // `fitAddon.fit()` so the layout listener picks up the resulting resize
    // automatically. We coerce to a sensible minimum so a tiny scale (or a
    // future smaller stop) can never produce sub-6px terminal glyphs.
    LaunchedEffect(webView, fontScale) {
        fontScale.collect { scale ->
            val target = (13 * scale).toInt().coerceAtLeast(6)
            webView.evaluateJavascript(
                "if (window.setFontSize) window.setFontSize($target);",
                null
            )
        }
    }

    DisposableEffect(webView) {
        onDispose {
            try {
                webView.loadUrl("about:blank")
                webView.stopLoading()
                webView.removeJavascriptInterface("AndroidBridge")
                webView.destroy()
            } catch (_: Exception) { /* best-effort teardown */ }
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier
    )
}

/**
 * JS → Kotlin bridge. Methods marked `@JavascriptInterface` are callable from
 * the HTML side as `AndroidBridge.<method>(...)`. Every call happens on a
 * WebView-owned binder thread — route straight into the ViewModel, which is
 * thread-safe.
 */
private class TerminalBridge(
    private val viewModel: TerminalViewModel,
    private val webView: WebView
) {

    @JavascriptInterface
    fun onReady(cols: Int, rows: Int) {
        viewModel.onTerminalReady(cols, rows)
    }

    @JavascriptInterface
    fun onInput(data: String) {
        android.util.Log.d("TerminalWebView", "bridge.onInput: ${data.length} bytes")
        viewModel.sendInput(data)
    }

    @JavascriptInterface
    fun onResize(cols: Int, rows: Int) {
        viewModel.resize(cols, rows)
    }

    @JavascriptInterface
    fun onLink(url: String) {
        val context = webView.context ?: return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { /* no browser available */ }
    }
}
