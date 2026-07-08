package com.hermesandroid.relay.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.network.relay.ConnectionState
import com.hermesandroid.relay.ui.components.ConnectionStatusBadge
import com.hermesandroid.relay.ui.components.ExtraKeysToolbar
import com.hermesandroid.relay.ui.components.TerminalSearchBar
import com.hermesandroid.relay.ui.components.TerminalSessionInfoSheet
import com.hermesandroid.relay.ui.components.TerminalTabBar
import com.hermesandroid.relay.ui.components.TerminalWebView
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.TerminalViewModel
import org.json.JSONObject

// xterm.js theme background — kept in one place so the Compose shell and the
// WebView content don't flash against each other.
private val TerminalBackground = Color(0xFF1A1A2E)

@Composable
fun TerminalScreen(
    terminalViewModel: TerminalViewModel,
    connectionViewModel: ConnectionViewModel,
    /** Header back affordance — Terminal is a pushed destination, not a tab. */
    onBack: (() -> Unit)? = null,
) {
    val connectionState by connectionViewModel.relayConnectionState.collectAsState()
    val tabs by terminalViewModel.tabs.collectAsState()
    val activeTabId by terminalViewModel.activeTabId.collectAsState()
    val activeTab by terminalViewModel.activeTab.collectAsState()
    val pairedSession by connectionViewModel.currentPairedSession.collectAsState()

    val isConnected = connectionState == ConnectionState.Connected
    val isConnecting = connectionState == ConnectionState.Connecting ||
        connectionState == ConnectionState.Reconnecting

    // Per-tab WebView reference cache. Each TerminalWebView pushes its
    // underlying WebView through onWebViewReady so we can drive search
    // commands against the *active* tab without having to plumb the JS
    // bridge through the ViewModel. The map is keyed on the stable tab id
    // so it survives recompositions; entries get pruned when their tab is
    // closed.
    //
    // We deliberately keep all live WebViews in this map (rather than
    // destroying inactive ones) so per-tab terminal output keeps streaming
    // while the user is looking at a different tab — closing a tab still
    // releases the WebView via TerminalWebView's DisposableEffect.
    val webViewByTab = remember { mutableStateMapOf<Int, WebView>() }

    // Prune the map any time a tab disappears so we don't leak the WebView
    // reference past its DisposableEffect teardown.
    val tabIdSet = tabs.map { it.tabId }.toSet()
    val staleIds = webViewByTab.keys.filter { it !in tabIdSet }
    for (id in staleIds) webViewByTab.remove(id)

    var showSearch by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }
    // When non-null, a kill-vs-detach confirmation dialog is open for this
    // tab id. Set by the tab-strip × button and the title-bar close gesture
    // so the user has to pick explicitly between preserving the tmux session
    // (Detach) or tearing it down (Kill).
    var closeConfirmTabId by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground)
    ) {
        // Compact custom header (replaces Material's fixed 64dp TopAppBar):
        // one ~52dp row that shows status ONCE — a connection dot + a single
        // concise word inline with the title, the whole block tappable for the
        // full session info sheet — plus a tidy action cluster. statusBarsPadding
        // clears the system status bar (TopAppBar used to own that inset).
        val current = activeTab
        // One concise status line. When attached we show the user's display
        // name if set, else "ready"; the full `hermes-<deviceId>-tabN` wire id
        // lives in the tappable info sheet, not the header.
        val statusWord = when {
            current == null -> stringResource(R.string.term_status_starting)
            current.attached -> current.displayName ?: stringResource(R.string.term_status_ready)
            current.attaching -> stringResource(R.string.term_status_attaching)
            !isConnected -> stringResource(R.string.term_status_disconnected)
            current.error != null -> current.error!!
            else -> stringResource(R.string.term_status_ready)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.term_back),
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Title + inline status (dot + word). Whole block opens the info
            // sheet. weight(1f) lets the status word ellipsize instead of
            // pushing the action icons off-screen.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showInfoSheet = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.term_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                ConnectionStatusBadge(
                    isConnected = isConnected && (activeTab?.attached == true),
                    isConnecting = isConnecting || (activeTab?.attaching == true),
                    size = 8.dp,
                )
                Text(
                    text = statusWord,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            // New-tab action while the tab strip is hidden (single-tab case);
            // with 2+ tabs the strip shows its own "+".
            if (tabs.size <= 1) {
                IconButton(
                    onClick = { terminalViewModel.openNewTab() },
                    enabled = tabs.size < TerminalViewModel.MAX_TABS,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.term_new_tab),
                    )
                }
            }
            IconButton(
                onClick = {
                    showSearch = !showSearch
                    if (!showSearch) {
                        // Clear decorations on the active tab when the user
                        // toggles search closed via the action icon.
                        webViewByTab[activeTabId]?.evaluateJavascript(
                            "if (window.clearSearch) { window.clearSearch(); }",
                            null,
                        )
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.term_search),
                )
            }
            IconButton(
                onClick = {
                    activeTab?.let { terminalViewModel.reattach(it.tabId) }
                },
                // Refresh is only meaningful for a tab that has actually been
                // started — re-attaching a never-started tab would spawn a
                // shell without flipping userStarted.
                enabled = isConnected &&
                    (activeTab?.attaching != true) &&
                    (activeTab?.userStarted == true),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.term_reattach),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

        // Tab strip — only with 2+ tabs. A single tab makes the strip pure
        // overhead (one chip + a plus), so we hide it and surface the new-tab
        // "+" in the app bar instead; it reappears the moment a second tab
        // opens. Most sessions use a single tab.
        if (tabs.size > 1) {
            TerminalTabBar(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelectTab = { terminalViewModel.selectTab(it) },
                onCloseTab = { closeConfirmTabId = it },
                onNewTab = { terminalViewModel.openNewTab() },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        }

        if (showSearch) {
            TerminalSearchBar(
                onSearchNext = { query ->
                    val js = "window.searchNext(${JSONObject.quote(query)});"
                    webViewByTab[activeTabId]?.evaluateJavascript(js, null)
                },
                onSearchPrev = { query ->
                    val js = "window.searchPrev(${JSONObject.quote(query)});"
                    webViewByTab[activeTabId]?.evaluateJavascript(js, null)
                },
                onClose = {
                    showSearch = false
                    webViewByTab[activeTabId]?.evaluateJavascript(
                        "if (window.clearSearch) { window.clearSearch(); }",
                        null,
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(TerminalBackground)
        ) {
            // Render every tab's WebView, stacked on top of each other in a
            // single Box. Only the active tab is visually + interactively in
            // front (zIndex 1f, full alpha); inactive tabs sit at zIndex 0
            // with alpha 0 so they keep their layout dimensions and continue
            // receiving terminal output in the background.
            //
            // We deliberately do NOT use AnimatedContent here — it tears down
            // the inactive tab's layout pass on every switch, which retriggers
            // the rows=1 viewport latch bug we just fixed. Stacking everything
            // at fillMaxSize gives every WebView the same measurements all the
            // time, so xterm's fit() lands on a sane cols/rows on the first
            // composition for each tab and never recomputes against a stale
            // 0-tall viewport.
            //
            // Each TerminalWebView remembers its underlying WebView keyed on
            // tabId, so once a tab is created its WebView survives for the
            // lifetime of the tab and gets torn down by DisposableEffect only
            // when [closeTab] removes the entry from [tabs]. fontScale is
            // threaded in from ConnectionViewModel so every tab inherits the
            // global font size preference.
            for (tab in tabs) {
                val isActive = tab.tabId == activeTabId
                TerminalWebView(
                    viewModel = terminalViewModel,
                    tabId = tab.tabId,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (isActive) 1f else 0f)
                        .alpha(if (isActive) 1f else 0f),
                    fontScale = connectionViewModel.fontScale,
                    onWebViewReady = { wv ->
                        webViewByTab[tab.tabId] = wv
                    },
                )
            }

            // Overlay priority (highest first):
            //   1. Relay disconnected — blocks everything until the wire is back
            //   2. Tab error — surfaces the last server-side failure
            //   3. Not-started — shows the Start overlay; this is the opt-in
            //      gate that replaces auto-attach. The user has to explicitly
            //      spawn the tmux-backed shell.
            // When the tab is attached and healthy, no overlay — the WebView
            // shows through.
            val tabNotStarted = activeTab != null && activeTab?.userStarted == false
            val showBlockingOverlay = !isConnected ||
                ((activeTab?.attached == false) && (activeTab?.error != null))
            // zIndex(2f) on both overlays lifts them above the active tab's
            // WebView (zIndex 1f). Without this the stacked-WebView trick
            // keeps the active PTY surface on top and the overlay is drawn
            // but invisible — same-Box children without an explicit zIndex
            // default to 0f and get occluded by the active WebView.
            when {
                showBlockingOverlay -> TerminalOverlay(
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    error = activeTab?.error,
                    modifier = Modifier.zIndex(2f),
                )
                tabNotStarted -> StartSessionOverlay(
                    sessionName = activeTab?.sessionName ?: "",
                    isReady = isConnected,
                    onStart = {
                        activeTab?.let { terminalViewModel.startSession(it.tabId) }
                    },
                    modifier = Modifier.zIndex(2f),
                )
            }

            // "Jump to latest" pill — shows when the user has scrolled up off
            // the live tail (xterm onScroll → bridge). Tap snaps back to the
            // bottom. Hidden while an overlay owns the surface.
            if (activeTab?.scrolledUp == true && !showBlockingOverlay && !tabNotStarted) {
                androidx.compose.material3.Surface(
                    onClick = {
                        webViewByTab[activeTabId]?.evaluateJavascript(
                            "window.scrollTerminalToBottom && window.scrollTerminalToBottom();",
                            null,
                        )
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .zIndex(2f),
                ) {
                    Text(
                        text = stringResource(R.string.term_jump_latest),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        ExtraKeysToolbar(
            ctrlActive = activeTab?.ctrlActive == true,
            altActive = activeTab?.altActive == true,
            onEsc = {
                activeTab?.let {
                    terminalViewModel.sendKey(it.tabId, TerminalViewModel.SpecialKey.ESC)
                }
            },
            onTab = {
                activeTab?.let {
                    terminalViewModel.sendKey(it.tabId, TerminalViewModel.SpecialKey.TAB)
                }
            },
            onCtrlToggle = {
                activeTab?.let { terminalViewModel.toggleCtrl(it.tabId) }
            },
            onAltToggle = {
                activeTab?.let { terminalViewModel.toggleAlt(it.tabId) }
            },
            onArrow = { key ->
                // Encode arrows in JS where xterm's DECCKM (application cursor
                // keys) mode is known, so they match a real keypress (\eOA vs
                // \e[A) inside TUIs like vim/less. ESC/TAB stay on the direct
                // path via onEsc/onTab — they're mode-independent.
                val keyName = when (key) {
                    TerminalViewModel.SpecialKey.ARROW_UP -> "ArrowUp"
                    TerminalViewModel.SpecialKey.ARROW_DOWN -> "ArrowDown"
                    TerminalViewModel.SpecialKey.ARROW_LEFT -> "ArrowLeft"
                    TerminalViewModel.SpecialKey.ARROW_RIGHT -> "ArrowRight"
                    TerminalViewModel.SpecialKey.HOME -> "Home"
                    TerminalViewModel.SpecialKey.END -> "End"
                    TerminalViewModel.SpecialKey.PAGE_UP -> "PageUp"
                    TerminalViewModel.SpecialKey.PAGE_DOWN -> "PageDown"
                    else -> null
                }
                if (keyName != null) {
                    webViewByTab[activeTabId]?.evaluateJavascript(
                        "window.termSendKey('$keyName');",
                        null,
                    )
                } else {
                    activeTab?.let { terminalViewModel.sendKey(it.tabId, key) }
                }
            },
            onPaste = {
                val pasted = clipboardManager.getText()?.text
                if (!pasted.isNullOrEmpty()) {
                    // Route through xterm's term.paste() so the text is wrapped
                    // in bracketed-paste markers (\e[200~ … \e[201~) when the
                    // running app enabled bracketed paste — stops a multi-line
                    // paste from auto-executing each newline in shells/editors.
                    val js = "window.pasteToTerminal(${JSONObject.quote(pasted)});"
                    webViewByTab[activeTabId]?.evaluateJavascript(js, null)
                }
            },
            // Copy the current xterm selection to the system clipboard. The
            // selection lives in JS, so read it back asynchronously and only
            // commit a non-empty result. WebView long-press copy is unreliable;
            // this is the dependable path (pairs with PASTE).
            onCopy = {
                webViewByTab[activeTabId]?.evaluateJavascript(
                    "window.getSelectionText && window.getSelectionText();"
                ) { result ->
                    val text = runCatching {
                        org.json.JSONTokener(result).nextValue() as? String
                    }.getOrNull()
                    if (!text.isNullOrEmpty()) {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                    }
                }
            },
            // Show/hide the soft keyboard for the active tab — tapping the
            // terminal doesn't reliably raise the IME on phones. Toggle off the
            // current IME visibility; on show, focus xterm first so keystrokes land.
            onToggleKeyboard = {
                webViewByTab[activeTabId]?.let { wv ->
                    val imeType = androidx.core.view.WindowInsetsCompat.Type.ime()
                    val visible = androidx.core.view.ViewCompat
                        .getRootWindowInsets(wv)?.isVisible(imeType) == true
                    val controller = androidx.core.view.ViewCompat.getWindowInsetsController(wv)
                    if (visible) {
                        controller?.hide(imeType)
                    } else {
                        wv.requestFocus()
                        wv.evaluateJavascript(
                            "window.focusTerminal && window.focusTerminal();", null,
                        )
                        controller?.show(imeType)
                    }
                }
            },
            // Scroll callbacks bypass the ViewModel — they're pure JS calls
            // against the active WebView, not envelopes to the relay.
            // scrollTerminalLines(±n) moves n lines in xterm's scrollback;
            // negative = toward older content, positive = newer, matching
            // the touch-gesture path in index.html.
            onScrollUp = {
                webViewByTab[activeTabId]?.evaluateJavascript(
                    "if (window.scrollTerminalLines) window.scrollTerminalLines(-10);",
                    null,
                )
            },
            onScrollDown = {
                webViewByTab[activeTabId]?.evaluateJavascript(
                    "if (window.scrollTerminalLines) window.scrollTerminalLines(10);",
                    null,
                )
            },
            onScrollToBottom = {
                webViewByTab[activeTabId]?.evaluateJavascript(
                    "if (window.scrollTerminalToBottom) window.scrollTerminalToBottom();",
                    null,
                )
            },
            // No navigationBarsPadding here: this screen lives inside the app
            // Scaffold whose bottomBar already owns the nav-bar inset, so
            // padding again left a dead gap below the keys. imePadding alone
            // keeps the bar above the soft keyboard when it's open.
            modifier = Modifier.imePadding()
        )
    }

    // Bottom-sheet info dialog for the active tab. Mirrors Chat's tappable-
    // header pattern but uses ModalBottomSheet because terminal sessions
    // carry more metadata than chat does (PID, shell, grid, transport,
    // grants, expiry). Same trigger gesture though: tapping the title bar.
    if (showInfoSheet) {
        val tab = activeTab
        if (tab != null) {
            TerminalSessionInfoSheet(
                tab = tab,
                pairedSession = pairedSession,
                canCloseTab = tabs.size > 1,
                onStart = { terminalViewModel.startSession(tab.tabId) },
                onReattach = { terminalViewModel.reattach(tab.tabId) },
                onCloseTab = { closeConfirmTabId = tab.tabId },
                onKillTab = { terminalViewModel.killTab(tab.tabId) },
                onRename = { name -> terminalViewModel.setTabName(tab.tabId, name) },
                onDismiss = { showInfoSheet = false },
            )
        } else {
            // Defensive — shouldn't happen because the title is only tappable
            // once tabs exist, but if it does we just close.
            showInfoSheet = false
        }
    }

    closeConfirmTabId?.let { tabId ->
        val tab = tabs.firstOrNull { it.tabId == tabId }
        if (tab == null) {
            closeConfirmTabId = null
        } else {
            CloseTabConfirmDialog(
                tab = tab,
                onDetach = {
                    terminalViewModel.closeTab(tabId)
                    closeConfirmTabId = null
                },
                onKill = {
                    terminalViewModel.killTab(tabId)
                    closeConfirmTabId = null
                },
                onDismiss = { closeConfirmTabId = null },
            )
        }
    }
}

/**
 * Confirmation dialog for closing a tab — the user must pick between
 * preserving the tmux session (Detach) or tearing it down (Kill).
 *
 * Never-started tabs skip the "Detach" path entirely; there's no server
 * state to preserve, so both buttons collapse to a single "Close tab" that
 * calls the kill path (which no-ops on the wire for un-started tabs).
 */
@Composable
private fun CloseTabConfirmDialog(
    tab: TerminalViewModel.TabState,
    onDetach: () -> Unit,
    onKill: () -> Unit,
    onDismiss: () -> Unit,
) {
    val everStarted = tab.userStarted
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.term_close_tab_title, tab.tabId)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (everStarted) {
                    Text(
                        stringResource(R.string.term_close_tab_detach_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        stringResource(R.string.term_close_tab_fresh_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            if (everStarted) {
                TextButton(
                    onClick = onKill,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.term_kill)) }
            } else {
                TextButton(onClick = onKill) { Text(stringResource(R.string.term_close)) }
            }
        },
        dismissButton = {
            if (everStarted) {
                TextButton(onClick = onDetach) { Text(stringResource(R.string.term_detach)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.term_cancel)) }
            }
        },
    )
}

@Composable
private fun StartSessionOverlay(
    sessionName: String,
    isReady: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Centered, scroll-wrapped so tall phones in landscape + a future
    // session picker / hint text don't clip the primary action. The scroll
    // is a no-op on a normal-size screen; only kicks in when content
    // overflows.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBackground.copy(alpha = 0.94f))
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 48.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }

            Text(
                text = stringResource(R.string.term_no_session),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.92f),
            )
            Text(
                text = stringResource(R.string.term_start_session_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
            if (sessionName.isNotEmpty()) {
                Text(
                    text = sessionName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.45f),
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.size(4.dp))
            Button(
                onClick = onStart,
                enabled = isReady,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.term_start_session))
            }
            if (!isReady) {
                Text(
                    text = stringResource(R.string.term_waiting_relay),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun TerminalOverlay(
    isConnected: Boolean,
    isConnecting: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBackground.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }

            val title = when {
                error != null -> stringResource(R.string.term_error_title)
                isConnecting -> stringResource(R.string.term_connecting_title)
                !isConnected -> stringResource(R.string.term_disconnected_title)
                else -> stringResource(R.string.term_ready_title)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.9f)
            )

            val detail = when {
                error != null -> error
                !isConnected -> stringResource(R.string.term_overlay_detail)
                else -> null
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
