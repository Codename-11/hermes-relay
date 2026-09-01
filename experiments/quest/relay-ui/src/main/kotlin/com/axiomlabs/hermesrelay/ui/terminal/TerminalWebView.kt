package com.axiomlabs.hermesrelay.ui.terminal

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.axiomlabs.hermesrelay.core.terminal.QuestTerminalController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter

@Composable
fun RelayTerminalWebView(
    controller: QuestTerminalController,
    tabId: Int,
    modifier: Modifier = Modifier,
    fontScale: StateFlow<Float> = remember { MutableStateFlow(1.0f) },
    onWebViewReady: ((WebView) -> Unit)? = null,
) {
    val context = LocalContext.current
    val webViewBackground = 0xFF0A0F17.toInt()
    val density = context.resources.displayMetrics.density

    val webView = remember(tabId) {
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
            settings.textZoom = 100
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url?.toString() ?: return false
                    if (!url.startsWith("file:///android_asset/terminal/")) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
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
                        "RelayTerminalWebView",
                        "tab=$tabId ${message.messageLevel()} [${message.sourceId()}:${message.lineNumber()}] ${message.message()}"
                    )
                    return true
                }
            }

            addJavascriptInterface(TerminalBridge(controller, this, tabId), "AndroidBridge")
            addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                val widthPx = right - left
                val heightPx = bottom - top
                if (widthPx <= 0 || heightPx <= 0) return@addOnLayoutChangeListener
                val widthCss = (widthPx / density).toInt()
                val heightCss = (heightPx / density).toInt()
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

    LaunchedEffect(webView) {
        onWebViewReady?.invoke(webView)
    }

    LaunchedEffect(webView, controller, tabId) {
        controller.outputFlow
            .filter { it.tabId == tabId }
            .collect { tabOutput ->
                webView.evaluateJavascript("window.writeTerminal('${tabOutput.b64}');", null)
            }
    }

    LaunchedEffect(webView, fontScale) {
        fontScale.collect { scale ->
            val target = (13 * scale).toInt().coerceAtLeast(6)
            webView.evaluateJavascript("if (window.setFontSize) window.setFontSize($target);", null)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            runCatching {
                webView.loadUrl("about:blank")
                webView.stopLoading()
                webView.removeJavascriptInterface("AndroidBridge")
                webView.destroy()
            }
        }
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

private class TerminalBridge(
    private val controller: QuestTerminalController,
    private val webView: WebView,
    private val tabId: Int,
) {
    @JavascriptInterface
    fun onReady(cols: Int, rows: Int) {
        controller.onTerminalReady(tabId, cols, rows)
    }

    @JavascriptInterface
    fun onInput(data: String) {
        controller.sendInput(tabId, data)
    }

    @JavascriptInterface
    fun onResize(cols: Int, rows: Int) {
        controller.resize(tabId, cols, rows)
    }

    @JavascriptInterface
    fun onLink(url: String) {
        runCatching {
            webView.context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
