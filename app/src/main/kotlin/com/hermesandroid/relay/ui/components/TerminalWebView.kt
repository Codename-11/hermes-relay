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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Background matches xterm's theme so there's no white flash while the
    // HTML boots. Kept as a constant rather than pulling from MaterialTheme
    // because the WebView host color must match the xterm theme exactly.
    val webViewBackground = 0xFF1A1A2E.toInt()

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
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

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
