import { Terminal } from './vendor/xterm/xterm.mjs';
import { FitAddon } from './vendor/xterm/addon-fit.mjs';

const tauri = window.__TAURI__;
const invoke = tauri.core.invoke;
const listen = tauri.event.listen;

const routes = {
  overview: document.querySelector('#overviewView'),
  pair: document.querySelector('#pairView'),
  chat: document.querySelector('#chatView'),
  tui: document.querySelector('#tuiView'),
  connect: document.querySelector('#connectView'),
  sessions: document.querySelector('#sessionsView'),
  plugins: document.querySelector('#pluginsView'),
  voice: document.querySelector('#voiceView'),
  diagnostics: document.querySelector('#diagnosticsView'),
  devices: document.querySelector('#devicesView'),
  grants: document.querySelector('#grantsView'),
  log: document.querySelector('#logView'),
  settings: document.querySelector('#settingsView')
};

const titles = {
  overview: 'Overview',
  pair: 'Pair',
  chat: 'Chat',
  tui: 'Embedded TUI',
  connect: 'Terminal / CLI',
  sessions: 'TUI Sessions',
  plugins: 'Plugins',
  voice: 'Voice Mode',
  diagnostics: 'Diagnostics',
  devices: 'Devices',
  grants: 'Grant Requests',
  log: 'Task Log',
  settings: 'Settings'
};

let voiceCurrentUrl = null;
const chatState = {
  mode: 'relay',
  busy: false,
  turnId: null,
  sessionId: null,
  messages: [],
  lastPrompt: '',
  freshNext: true,
  apiKey: ''
};

let state = null;
let activeRoute = 'overview';
let activePairMethod = 'qr';
let loadSerial = 0;
let controlBusy = false;
let pairBusy = false;
let terminalSessions = null;
let sessionsBusy = false;
let embeddedBusy = false;
let pluginBusy = null;
const embeddedTerminal = {
  term: null,
  fit: null,
  resizeObserver: null,
  id: null,
  running: false
};

function text(id, value) {
  const el = document.querySelector(id);
  if (el) el.textContent = value;
}

function setRoute(route) {
  activeRoute = routes[route] ? route : 'overview';
  for (const [name, el] of Object.entries(routes)) {
    el.classList.toggle('active', name === activeRoute);
  }
  document.querySelectorAll('.nav-item').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.route === activeRoute);
  });
  text('#viewTitle', titles[activeRoute]);
  if (activeRoute === 'devices') {
    if (hasActiveRelay()) refreshDevices();
    else renderDevicesView();
  }
  if (activeRoute === 'pair') refreshPairPreview();
  if (activeRoute === 'chat') renderChatView();
  if (activeRoute === 'connect') {
    renderConnectView();
  }
  if (activeRoute === 'tui') {
    renderEmbeddedTerminalControls();
    ensureEmbeddedTerminal();
    fitEmbeddedTerminal();
  }
  if (activeRoute === 'sessions') refreshTerminalSessions();
  if (activeRoute === 'plugins') renderPluginsView();
  if (activeRoute === 'voice') refreshVoiceView();
  if (activeRoute === 'diagnostics') renderDiagnostics();
}

// ─── Voice mode ──────────────────────────────────────────────────────
// The iframe loads the daemon's local voice page (http://127.0.0.1:PORT/v/NONCE/).
// We pull the URL from the daemon's discovery file via a Rust command;
// if that returns nothing we fall back to a manual paste field so the
// user can still verify the path while the daemon-side work lands.

async function refreshVoiceView() {
  const empty = document.querySelector('#voiceEmpty');
  const frame = document.querySelector('#voiceFrame');
  let url = null;
  try {
    url = await invoke('get_voice_url');
  } catch {
    // Command may not exist yet on older tray binaries; treat as "no URL".
    url = null;
  }
  if (typeof url === 'string' && url.length > 0) {
    loadVoiceUrl(url);
  } else if (voiceCurrentUrl) {
    // Re-show the previously-loaded iframe (user navigated away and back).
    loadVoiceUrl(voiceCurrentUrl);
  } else {
    empty.hidden = false;
    frame.hidden = true;
    frame.src = 'about:blank';
  }
}

function loadVoiceUrl(url) {
  const empty = document.querySelector('#voiceEmpty');
  const frame = document.querySelector('#voiceFrame');
  if (!url || !/^https?:\/\/127\.0\.0\.1[:/]/.test(url)) {
    empty.hidden = false;
    frame.hidden = true;
    return;
  }
  // Loopback-only guard: refuse to load anything that doesn't start with
  // http(s)://127.0.0.1 — prevents the URL field being abused to load arbitrary
  // pages inside the tray webview.
  voiceCurrentUrl = url;
  empty.hidden = true;
  frame.hidden = false;
  if (frame.src !== url) frame.src = url;
}

function bindVoiceControls() {
  const reload = document.querySelector('#voiceReloadBtn');
  const copy = document.querySelector('#voiceCopyUrlBtn');
  const manualInput = document.querySelector('#voiceManualUrl');
  const manualLoad = document.querySelector('#voiceManualLoad');

  reload?.addEventListener('click', () => {
    if (voiceCurrentUrl) {
      const frame = document.querySelector('#voiceFrame');
      // Force a refresh; src= alone is a no-op if the URL is identical.
      frame.src = 'about:blank';
      setTimeout(() => loadVoiceUrl(voiceCurrentUrl), 30);
    } else {
      refreshVoiceView();
    }
  });

  copy?.addEventListener('click', async () => {
    if (!voiceCurrentUrl) return;
    try { await navigator.clipboard.writeText(voiceCurrentUrl); } catch { /* best-effort */ }
  });

  manualLoad?.addEventListener('click', () => {
    const v = (manualInput?.value ?? '').trim();
    if (v) loadVoiceUrl(v);
  });
  manualInput?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      const v = (manualInput.value ?? '').trim();
      if (v) loadVoiceUrl(v);
    }
  });
}

function hasActiveRelay() {
  return !!state?.selected_url;
}

function pairSubmitLabel() {
  return activePairMethod === 'stored' ? 'Use Stored Session' : 'Pair';
}

function setPairBusy(busy) {
  pairBusy = busy;
  document.querySelectorAll('.method-tab').forEach((btn) => {
    btn.disabled = busy;
  });
  ['#pairQr', '#pairRemote', '#pairCode', '#pairGrantTools', '#pairStartDaemon'].forEach((selector) => {
    const el = document.querySelector(selector);
    if (el) el.disabled = busy;
  });
  document.querySelectorAll('[data-use-session]').forEach((btn) => {
    btn.disabled = busy;
  });
  const submit = document.querySelector('#pairSubmitBtn');
  if (submit) {
    submit.disabled = busy;
    submit.textContent = busy ? 'Pairing...' : pairSubmitLabel();
  }
}

function setPairMethod(method) {
  if (pairBusy) return;
  activePairMethod = method;
  document.querySelectorAll('.method-tab').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.pairMethod === method);
  });
  document.querySelectorAll('[data-pair-panel]').forEach((panel) => {
    panel.classList.toggle('active', panel.dataset.pairPanel === method);
  });
  const submit = document.querySelector('#pairSubmitBtn');
  if (submit) {
    submit.textContent = pairSubmitLabel();
  }
  refreshPairPreview();
}

function daemonLabel(daemon) {
  if (daemon.running) return `Running pid ${daemon.pid}`;
  if (daemon.paused) return 'Paused';
  return 'Stopped';
}

function statusClass(daemon) {
  if (daemon.running) return 'ok';
  if (daemon.paused) return 'warn';
  return 'idle';
}

function cliLabel(cli) {
  if (!cli.available) return `Missing: ${cli.command}`;
  if (cli.mode === 'sidecar') return 'Bundled sidecar';
  if (cli.mode === 'local_dist') return 'Local dist/cli.js';
  if (cli.mode === 'install_dir') return 'Installed shim';
  if (cli.mode === 'env') return `Env override: ${cli.command}`;
  return cli.command;
}

function psQuote(value) {
  return `'${String(value ?? '').replaceAll("'", "''")}'`;
}

function cliCommandPrefix() {
  return 'hermes-relay';
}

function installCommand() {
  return "$env:HERMES_RELAY_INSTALL_SURFACE='cli'; irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex";
}

function terminalCliStatus() {
  return state?.terminal_cli ?? state?.cli ?? { available: false, command: 'hermes-relay', mode: 'path' };
}

function cliInstallState() {
  const cli = terminalCliStatus();
  if (cli.mode === 'path' || cli.mode === 'install_dir' || cli.mode === 'env') {
    return { installed: !!cli.available, label: cli.available ? 'CLI ready' : 'CLI missing' };
  }
  return { installed: false, label: 'Tray sidecar only' };
}

function remoteOverrideFlag() {
  const remote = state?.selected_url || state?.config?.relay_url || '';
  return remote ? ` --remote ${psQuote(remote)}` : '';
}

function commandUnavailableSuffix() {
  return state?.selected_url ? '' : ' # pair first or pass --remote ws://host:8767';
}

function shellCommand(options = {}) {
  return `${cliCommandPrefix()}${options.override ? remoteOverrideFlag() : ''}${commandUnavailableSuffix()}`;
}

function chatCommand(options = {}) {
  return `${cliCommandPrefix()} chat "summarize my current project"${options.override ? remoteOverrideFlag() : ''}${commandUnavailableSuffix()}`;
}

function daemonCommand(options = {}) {
  const computerUse = state?.config?.experimental_computer_use ? ' --experimental-computer-use' : '';
  return `${cliCommandPrefix()} daemon${options.override ? remoteOverrideFlag() : ''} --log-human${computerUse}${commandUnavailableSuffix()}`;
}

function statusCommand() {
  return `${cliCommandPrefix()} status`;
}

function doctorCommand() {
  return `${cliCommandPrefix()} doctor --json`;
}

function toolsCommand(options = {}) {
  return `${cliCommandPrefix()} tools${options.override ? remoteOverrideFlag() : ''}${commandUnavailableSuffix()}`;
}

function sessionsListCommand(options = {}) {
  return `${cliCommandPrefix()} sessions list${options.override ? remoteOverrideFlag() : ''}${commandUnavailableSuffix()}`;
}

function sessionResumeCommand(name) {
  return `${cliCommandPrefix()}${name ? ` --session ${psQuote(name)}` : ''}${commandUnavailableSuffix()}`;
}

function sessionKillCommand(name) {
  return `${cliCommandPrefix()} sessions kill ${psQuote(name)}${commandUnavailableSuffix()}`;
}

function setControlsBusy(busy) {
  controlBusy = busy;
  updateControlButtons(state?.daemon);
}

function updateControlButtons(daemon) {
  const start = document.querySelector('#startBtn');
  const pause = document.querySelector('#pauseBtn');
  const emergency = document.querySelector('#emergencyBtn');
  const paired = hasActiveRelay();
  const running = !!daemon?.running;
  const paused = !!daemon?.paused && !running;
  if (start) {
    start.disabled = controlBusy || running || !paired;
    start.textContent = running ? 'Running' : paired ? 'Start' : 'Pair first';
  }
  if (pause) {
    pause.disabled = controlBusy || !running;
    pause.textContent = paused ? 'Paused' : 'Pause';
  }
  if (emergency) {
    emergency.disabled = controlBusy;
  }
}

function showStatusError(err) {
  document.querySelector('#statusStrip')?.classList.add('error');
  document.querySelector('#statusStrip .dot').className = 'dot error';
  text('#statusText', String(err ?? 'Action failed'));
}

function setEmbeddedFeedback(message) {
  text('#embeddedTerminalFeedback', message ?? '');
}

function embeddedStatus() {
  return state?.embedded_terminal ?? { running: false, id: null };
}

function terminalSessionName() {
  return document.querySelector('#embeddedSessionName')?.value.trim() || null;
}

function ensureEmbeddedTerminal() {
  if (embeddedTerminal.term) return embeddedTerminal.term;
  const target = document.querySelector('#embeddedTerminal');
  if (!target) return null;
  const term = new Terminal({
    cursorBlink: true,
    convertEol: false,
    fontFamily: '"Cascadia Mono", "Consolas", monospace',
    fontSize: 13,
    lineHeight: 1.18,
    scrollback: 6000,
    theme: {
      background: '#06090c',
      foreground: '#dce7ee',
      cursor: '#58c7b2',
      selectionBackground: '#243847',
      black: '#0b1117',
      red: '#ff6b7a',
      green: '#58c7b2',
      yellow: '#e6b858',
      blue: '#6ca8ff',
      magenta: '#9b6bf0',
      cyan: '#72d8ea',
      white: '#dce7ee',
      brightBlack: '#516170',
      brightRed: '#ff8995',
      brightGreen: '#72d8a7',
      brightYellow: '#ffd27a',
      brightBlue: '#8dbbff',
      brightMagenta: '#b993ff',
      brightCyan: '#95e8f2',
      brightWhite: '#f7fbff'
    }
  });
  const fit = new FitAddon();
  term.loadAddon(fit);
  term.open(target);
  term.onData((data) => {
    if (!embeddedTerminal.running) return;
    invoke('write_embedded_terminal', { data }).catch((err) => {
      setEmbeddedFeedback(`Terminal input failed: ${String(err.message ?? err)}`);
    });
  });
  term.onResize(({ cols, rows }) => {
    if (!embeddedTerminal.running) return;
    invoke('resize_embedded_terminal', { cols, rows }).catch(() => {});
  });
  embeddedTerminal.term = term;
  embeddedTerminal.fit = fit;
  embeddedTerminal.resizeObserver = new ResizeObserver(() => fitEmbeddedTerminal());
  embeddedTerminal.resizeObserver.observe(target);
  fitEmbeddedTerminal();
  return term;
}

function fitEmbeddedTerminal() {
  if (!embeddedTerminal.fit || !document.querySelector('#tuiView')?.classList.contains('active')) {
    return;
  }
  requestAnimationFrame(() => {
    try {
      embeddedTerminal.fit.fit();
      if (embeddedTerminal.running && embeddedTerminal.term) {
        invoke('resize_embedded_terminal', {
          cols: embeddedTerminal.term.cols,
          rows: embeddedTerminal.term.rows
        }).catch(() => {});
      }
    } catch {
      /* xterm can throw while the panel is hidden during route changes. */
    }
  });
}

function renderEmbeddedTerminalControls() {
  const status = embeddedStatus();
  const running = !!status.running;
  embeddedTerminal.running = running;
  embeddedTerminal.id = status.id ?? embeddedTerminal.id;
  const label = status.surface_label ?? 'Embedded TUI';
  text('#embeddedTerminalBadge', running ? `${label} attached` : state?.selected_url ? 'Detached' : 'Pair first');
  text('#tuiActiveRelay', activeRelayText());
  text('#tuiRouteBadge', state?.selected_url ? activeRouteLabel() : 'No route');
  text('#tuiSessionStatus', running ? `${label} attached` : state?.selected_url ? 'Detached' : 'Pair first');
  const empty = document.querySelector('#embeddedTerminalEmpty');
  if (empty) {
    empty.classList.toggle('hidden', running || !!embeddedTerminal.term);
    if (!state?.selected_url) {
      empty.textContent = 'Pair a relay to start the in-app TUI.';
    } else if (!embeddedTerminal.term) {
      empty.textContent = 'Resume a session to attach the in-app TUI.';
    }
  }
  const resume = document.querySelector('#embeddedResumeBtn');
  const create = document.querySelector('#embeddedNewBtn');
  const stop = document.querySelector('#embeddedStopBtn');
  if (resume) resume.disabled = embeddedBusy || running || !state?.selected_url;
  if (create) create.disabled = embeddedBusy || running || !state?.selected_url;
  if (stop) stop.disabled = embeddedBusy || !running;
}

function formatTime(ts) {
  if (!ts) return '';
  return new Date(Number(ts)).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

function routeClass(value) {
  return String(value ?? 'unknown').toLowerCase().replace(/[^a-z0-9_-]/g, '-');
}

function renderLog(target, entries, compact = false) {
  const el = document.querySelector(target);
  if (!el) return;
  const list = compact ? entries.slice(-5) : entries.slice().reverse();
  if (list.length === 0) {
    el.innerHTML = '<div class="empty">No activity</div>';
    return;
  }
  el.innerHTML = list.map((entry) => `
    <div class="log-row ${routeClass(entry.level)}">
      <time>${formatTime(entry.ts_ms)}</time>
      <strong>${escapeHtml(entry.event)}</strong>
      <span>${escapeHtml(entry.message)}</span>
    </div>
  `).join('');
}

function appendPairLog(level, message) {
  const out = document.querySelector('#pairOutput');
  if (!out) return;
  const stamp = new Date().toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
  out.textContent += `${out.textContent ? '\n' : ''}[${stamp}] ${level.toUpperCase()} ${message}`;
  out.scrollTop = out.scrollHeight;
  text('#pairLogMeta', level === 'error' ? 'Failed' : 'Active');
}

function resetPairLog(message = 'Idle') {
  const out = document.querySelector('#pairOutput');
  if (out) out.textContent = '';
  text('#pairLogMeta', message);
}

function renderGrants(grants = []) {
  const list = document.querySelector('#grantList');
  if (!list) return;
  text('#grantStatus', grants.length ? `${grants.length} pending` : 'None pending');
  text('#grantBadge', grants.length ? `${grants.length} pending` : 'None pending');
  if (!grants.length) {
    list.innerHTML = '<div class="empty">No grant requests</div>';
    return;
  }
  list.innerHTML = grants.map((grant) => {
    const scope = grant.scope && Object.keys(grant.scope).length
      ? JSON.stringify(grant.scope)
      : 'All visible desktop context';
    return `
      <div class="grant-row">
        <div>
          <strong>${escapeHtml(String(grant.mode ?? '').toUpperCase())}</strong>
          <span>${escapeHtml(grant.duration_seconds)}s / ${escapeHtml(scope)}</span>
          <p>${escapeHtml(grant.reason || 'No reason provided')}</p>
        </div>
        <div class="grant-actions">
          <button class="danger small" data-grant-reject="${escapeHtml(grant.id)}">Reject</button>
          <button class="primary small" data-grant-approve="${escapeHtml(grant.id)}">Approve</button>
        </div>
      </div>
    `;
  }).join('');
}

function selectedSession() {
  const selected = state?.selected_url;
  if (!selected) return null;
  return state.sessions?.find((session) => session.url === selected) ?? null;
}

function looksLikeTailscale(hostOrUrl) {
  const value = String(hostOrUrl ?? '').toLowerCase();
  return value.includes('.ts.net') || value.includes('://100.') || value.startsWith('100.');
}

function roleLabel(role, url = '') {
  const normalized = String(role ?? '').toLowerCase();
  if (normalized === 'lan') return 'LAN';
  if (normalized === 'tailscale') return 'Tailscale';
  if (normalized === 'public') return 'Public';
  if (normalized === 'manual') return 'Manual';
  if (normalized) return normalized.replaceAll('_', ' ');
  if (looksLikeTailscale(url)) return 'Tailscale';
  return url ? 'Manual' : 'Unknown';
}

function activeRouteLabel() {
  const session = selectedSession();
  return roleLabel(session?.endpoint_role, state?.selected_url);
}

function activeRelayText() {
  if (state?.selected_url) return state.selected_url;
  return state?.config?.relay_url ? 'Not paired (stored token missing)' : 'Not paired';
}

function updateSettingsForm(config) {
  text('#settingsActiveRelay', activeRelayText());
  text('#settingsRoute', activeRouteLabel());
  document.querySelector('#settingsRelay').value = config.relay_url ?? '';
  document.querySelector('#settingsAutoStart').checked = !!config.auto_start_daemon;
  document.querySelector('#settingsComputerUse').checked = !!config.experimental_computer_use;
  document.querySelector('#settingsOverlayVisible').checked = config.overlay?.visible !== false;
  document.querySelector('#settingsShortcut').value = config.emergency_shortcut ?? 'Ctrl+Shift+H';
  document.querySelector('#settingsBlocklist').value = (config.blocklist ?? []).join('\n');
}

function renderConnectionSummary() {
  const selected = activeRelayText();
  const route = activeRouteLabel();
  const extra = Math.max(0, (state?.sessions?.length ?? 0) - (state?.selected_url ? 1 : 0));
  text('#selectedRelay', selected);
  text('#routeBadge', state?.selected_url ? route : 'No relay');
  text('#activeRouteBadge', state?.selected_url ? route : 'No route');
  text('#pairActiveBadge', state?.selected_url ? 'Active relay set' : 'No active relay');
  text('#pairCurrentRelay', selected);
  text('#pairCurrentRoute', route);
  text('#sessionStorePath', state?.session_store_path ?? 'Unknown');
  text('#extraSessionsNote', extra > 0
    ? `${extra} additional CLI session${extra === 1 ? '' : 's'} stored. Desktop is using only the active relay above.`
    : '');
}

function renderToolConsentControls() {
  const session = selectedSession();
  const hasRelay = !!state?.selected_url;
  const consented = !!session?.tools_consented;
  text('#toolsConsentState', !hasRelay ? 'Pair first' : consented ? 'Granted' : 'Required');
  text(
    '#toolsConsentHint',
    !hasRelay
      ? 'Pair a relay before exposing desktop tools.'
      : consented
        ? 'This relay can use desktop file, shell, and search tools.'
        : 'Grant desktop tools before starting the tray daemon.'
  );
  const grant = document.querySelector('#grantToolsBtn');
  const revoke = document.querySelector('#revokeToolsBtn');
  if (grant) grant.disabled = !hasRelay || consented;
  if (revoke) revoke.disabled = !hasRelay || !consented;
}

function chatGatewayInput() {
  return document.querySelector('#chatGatewayUrl')?.value.trim() || state?.config?.chat_gateway_url || '';
}

function chatCanSend() {
  if (chatState.busy) return false;
  if (chatState.mode === 'relay') return !!state?.chat?.can_use_relay;
  return !!chatGatewayInput();
}

function syncChatModeFromState() {
  const setup = state?.chat ?? {};
  if (chatState.mode === 'relay' && !setup.can_use_relay) {
    chatState.mode = setup.can_use_gateway || setup.default_mode === 'setup' ? 'gateway' : 'relay';
  }
  if (setup.default_mode === 'relay' && !chatState.sessionId) {
    chatState.mode = 'relay';
  } else if (setup.default_mode === 'gateway' && !setup.can_use_relay && !chatState.sessionId) {
    chatState.mode = 'gateway';
  }
}

function appendChatMessage(role, textValue = '', status = '') {
  const message = {
    id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    role,
    text: textValue,
    status
  };
  chatState.messages.push(message);
  renderChatTranscript();
  return message;
}

function currentAssistantMessage() {
  let message = chatState.messages[chatState.messages.length - 1];
  if (!message || message.role !== 'assistant' || message.status === 'complete') {
    message = appendChatMessage('assistant', '', 'streaming');
  }
  return message;
}

function renderChatTranscript() {
  const transcript = document.querySelector('#chatTranscript');
  if (!transcript) return;
  if (!chatState.messages.length) {
    transcript.innerHTML = '<div class="empty">No messages in this tray chat yet.</div>';
    return;
  }
  transcript.innerHTML = chatState.messages.map((message) => `
    <article class="chat-message ${escapeHtml(message.role)} ${escapeHtml(message.status)}">
      <div class="chat-message-role">${escapeHtml(message.role === 'user' ? 'You' : message.role === 'assistant' ? 'Hermes' : 'Event')}</div>
      <div class="chat-message-body">${escapeHtml(message.text || (message.status === 'streaming' ? 'Thinking...' : ''))}</div>
    </article>
  `).join('');
  transcript.scrollTop = transcript.scrollHeight;
}

function renderChatView() {
  if (!state) return;
  syncChatModeFromState();
  const setup = state.chat ?? {};
  const gatewayInput = document.querySelector('#chatGatewayUrl');
  const gatewayKey = document.querySelector('#chatGatewayKey');
  if (gatewayInput && !gatewayInput.value && state.config?.chat_gateway_url) {
    gatewayInput.value = state.config.chat_gateway_url;
  }
  if (gatewayKey && !gatewayKey.value && chatState.apiKey) {
    gatewayKey.value = chatState.apiKey;
  }

  const relayReady = !!setup.can_use_relay;
  const gatewayReady = !!chatGatewayInput();
  const routeLabel = chatState.mode === 'relay'
    ? (setup.relay_url ?? 'Pair required')
    : (chatGatewayInput() || 'Gateway URL required');
  text('#chatRouteBadge', chatState.busy ? 'Streaming' : chatState.mode === 'relay' ? 'Relay' : 'Gateway');
  text('#chatSetupBadge', relayReady || gatewayReady ? 'Ready' : 'Setup');
  text('#chatRouteHint', chatState.busy ? `Streaming via ${routeLabel}` : (setup.setup_hint ?? 'Choose a route to start.'));
  text('#chatRelayRoute', setup.relay_url ?? 'Not paired');
  text('#chatGatewayRoute', chatGatewayInput() || 'Not set');
  text('#chatSessionLabel', chatState.sessionId ? chatState.sessionId : chatState.freshNext ? 'New' : 'Current');
  text(
    '#chatDiagnostics',
    chatState.mode === 'relay' && !relayReady
      ? 'Pair a relay first, or switch to Gateway/API.'
      : chatState.mode === 'gateway' && !gatewayReady
        ? 'Enter a gateway URL such as http://host:8642. API key is optional and is not saved.'
        : ''
  );

  document.querySelectorAll('input[name="chatMode"]').forEach((input) => {
    input.checked = input.value === chatState.mode;
    input.disabled = chatState.busy || (input.value === 'relay' && !relayReady);
  });
  document.querySelector('#chatFirstRun')?.classList.toggle('hidden', relayReady || gatewayReady);
  const send = document.querySelector('#chatSendBtn');
  const stop = document.querySelector('#chatStopBtn');
  const retry = document.querySelector('#chatRetryBtn');
  if (send) send.disabled = !chatCanSend();
  if (stop) stop.disabled = !chatState.busy;
  if (retry) retry.disabled = chatState.busy || !chatState.lastPrompt;
  renderChatTranscript();
}

function handleChatEventLine(line) {
  let event;
  try {
    event = JSON.parse(line);
  } catch {
    return;
  }
  const type = event?.type;
  const payload = event?.payload ?? {};
  const sessionId = event?.session_id || payload?.id || payload?.session_id;
  if (sessionId && typeof sessionId === 'string' && sessionId !== 'runs') {
    chatState.sessionId = sessionId;
    chatState.freshNext = false;
  }
  if (type === 'message.delta') {
    const textValue = payload.text || payload.delta || payload.rendered || '';
    if (textValue) {
      const message = currentAssistantMessage();
      message.text += String(textValue);
      message.status = 'streaming';
      renderChatView();
    }
  } else if (type === 'message.complete') {
    const message = currentAssistantMessage();
    message.status = 'complete';
    renderChatView();
  } else if (type === 'tool.start') {
    appendChatMessage('event', `Tool started: ${payload.name || payload.tool_id || 'tool'}`, 'tool');
  } else if (type === 'tool.complete') {
    const label = payload.name || payload.tool_id || 'tool';
    const summary = payload.error || payload.summary || 'completed';
    appendChatMessage('event', `Tool ${label}: ${summary}`, payload.error ? 'error' : 'tool');
  } else if (type === 'reasoning.delta' || type === 'status.update') {
    text('#chatDiagnostics', payload.text || '');
  } else if (type === 'error') {
    appendChatMessage('event', payload.message || 'Chat failed', 'error');
  }
}

async function saveChatGatewayUrl() {
  const url = chatGatewayInput();
  if (!url) {
    showStatusError('Gateway URL is required.');
    return;
  }
  const config = {
    ...state.config,
    chat_gateway_url: url
  };
  await invoke('save_desktop_config', { config });
  await loadState();
  chatState.mode = 'gateway';
  renderChatView();
}

async function sendChatPrompt(promptOverride = '') {
  if (chatState.busy) return;
  const input = document.querySelector('#chatPrompt');
  const prompt = (promptOverride || input?.value || '').trim();
  if (!prompt) return;
  if (!chatCanSend()) {
    showStatusError(chatState.mode === 'relay' ? 'Pair a relay before chatting.' : 'Enter a gateway URL before chatting.');
    return;
  }
  chatState.apiKey = document.querySelector('#chatGatewayKey')?.value ?? chatState.apiKey;
  appendChatMessage('user', prompt, 'complete');
  appendChatMessage('assistant', '', 'streaming');
  chatState.busy = true;
  chatState.lastPrompt = prompt;
  renderChatView();
  try {
    const status = await invoke('start_chat_turn', {
      mode: chatState.mode,
      prompt,
      gatewayUrl: chatState.mode === 'gateway' ? chatGatewayInput() : null,
      apiKey: chatState.mode === 'gateway' ? chatState.apiKey : null,
      sessionId: chatState.freshNext ? null : chatState.sessionId,
      fresh: chatState.freshNext
    });
    chatState.turnId = status.id;
    if (input && !promptOverride) input.value = '';
  } catch (err) {
    chatState.busy = false;
    appendChatMessage('event', String(err.message ?? err), 'error');
    renderChatView();
  }
}

async function stopChatPrompt() {
  try {
    await invoke('stop_chat_turn');
  } catch (err) {
    showStatusError(err);
  } finally {
    chatState.busy = false;
    renderChatView();
  }
}

function newChatSession() {
  chatState.sessionId = null;
  chatState.freshNext = true;
  chatState.messages = [];
  renderChatView();
}

function activityStatus(nextState) {
  const daemon = nextState.daemon ?? {};
  const grants = nextState.pending_grants ?? [];
  if (grants.length) {
    return { label: `${grants.length} approval pending`, cls: 'warn', badge: 'Approval' };
  }
  if (!nextState.selected_url) {
    return { label: 'Pair required', cls: 'warn', badge: 'Pair first' };
  }
  if (daemon.paused && !daemon.running) {
    return { label: 'Paused', cls: 'warn', badge: 'Paused' };
  }
  const recent = (nextState.task_log ?? []).slice().reverse().find((entry) => {
    const age = Date.now() - Number(entry.ts_ms ?? 0);
    return age >= 0 && age < 45_000;
  });
  const event = String(recent?.event ?? '').toLowerCase();
  const message = String(recent?.message ?? '').toLowerCase();
  if (event.includes('grant') || message.includes('approval') || message.includes('grant')) {
    return { label: 'Approval activity', cls: 'warn', badge: 'Approval' };
  }
  if (event.includes('sessions') || event.startsWith('tui_')) {
    return { label: 'TUI session active', cls: 'ok', badge: 'TUI active' };
  }
  if (message.includes('desktop_') || message.includes('tool') || event.includes('tool')) {
    return { label: 'Tool activity', cls: 'ok', badge: 'Tools active' };
  }
  if (message.includes('gateway') || message.includes('auth') || message.includes('connected')) {
    return { label: 'Gateway active', cls: 'ok', badge: 'Gateway' };
  }
  if (daemon.running) {
    return { label: 'Connected - Observing', cls: 'ok', badge: 'Observing' };
  }
  return { label: 'Disconnected', cls: 'idle', badge: 'Disconnected' };
}

function commandRows() {
  const rows = [
    {
      title: 'Remote TUI / shell',
      detail: 'Attach to the server-side Hermes terminal using the saved active relay.',
      command: shellCommand()
    },
    {
      title: 'One-shot chat',
      detail: 'Send a prompt through the saved relay and return structured output to this terminal.',
      command: chatCommand()
    },
    {
      title: 'Headless tool daemon',
      detail: 'Keep this desktop available to the saved relay without an open terminal.',
      command: daemonCommand()
    },
    {
      title: 'Stored relays',
      detail: 'List paired relays, grants, token TTL, route, and consent state.',
      command: statusCommand()
    },
    {
      title: 'TUI sessions',
      detail: 'List resumable tmux sessions running on the active relay.',
      command: sessionsListCommand()
    },
    {
      title: 'Tool inventory',
      detail: 'Inspect the tools the current relay advertises before starting work.',
      command: toolsCommand()
    },
    {
      title: 'Local doctor',
      detail: 'Generate a local install and session report with secrets redacted.',
      command: doctorCommand()
    }
  ];
  if (state?.selected_url) {
    rows.push({
      title: 'Explicit relay override',
      detail: 'Use this only when you want to bypass the saved active relay for one command.',
      command: shellCommand({ override: true })
    });
  }
  return rows;
}

function renderConnectView() {
  if (!state) return;
  const route = activeRouteLabel();
  const installState = cliInstallState();
  const terminalCli = terminalCliStatus();
  text('#connectActiveRelay', activeRelayText());
  text('#connectRouteBadge', state.selected_url ? route : 'No route');
  text('#connectCliSource', cliLabel(terminalCli));
  text('#connectDaemonState', daemonLabel(state.daemon));
  text('#connectShimBadge', installState.label);
  text('#connectCommandPath', terminalCli.command || 'hermes-relay');
  text('#connectSessionStore', state.session_store_path ?? 'Unknown');
  text('#connectConfigPath', state.config_path ?? 'Unknown');
  text('#connectComputerUse', state.config.experimental_computer_use ? 'Enabled' : 'Disabled');
  text('#launchTuiCommand', shellCommand());
  text('#installCommand', installCommand());
  text('#installNudgeTitle', installState.installed ? 'CLI ready' : 'Install CLI shim');
  text(
    '#installNudgeText',
    installState.installed
      ? 'hermes-relay is available from your terminal; commands below use your saved active relay.'
      : 'The tray can use its bundled sidecar, but terminal commands and the TUI launcher need the hermes-relay shim.'
  );
  document.querySelector('#installNudge')?.classList.toggle('ready', installState.installed);
  document.querySelector('#copyInstallBtn')?.toggleAttribute('disabled', installState.installed);
  const openTuiBtn = document.querySelector('#openTuiBtn');
  if (openTuiBtn) {
    openTuiBtn.disabled = !installState.installed || !state.selected_url;
    openTuiBtn.textContent = state.selected_url
      ? (installState.installed ? 'Open TUI' : 'Install first')
      : 'Pair first';
  }
  const sessionInput = document.querySelector('#embeddedSessionName');
  if (sessionInput && !sessionInput.value && terminalSessions?.active?.name) {
    sessionInput.value = terminalSessions.active.name;
  }
  renderEmbeddedTerminalControls();

  const list = document.querySelector('#connectCommandList');
  if (!list) return;
  list.innerHTML = commandRows().map((row, index) => `
    <div class="command-row">
      <div>
        <strong>${escapeHtml(row.title)}</strong>
        <span>${escapeHtml(row.detail)}</span>
        <code>${escapeHtml(row.command)}</code>
      </div>
      <button class="secondary small" type="button" data-copy-command="${index}">Copy</button>
    </div>
  `).join('');
}

function pluginStateLabel(plugin) {
  if (plugin.installed) return 'Installed';
  if (plugin.available) return 'Fallback ready';
  return 'Setup needed';
}

function pluginPrimaryCommand(plugin) {
  if (plugin.installed) return plugin.launch?.display ?? plugin.command;
  return plugin.installer?.display ?? plugin.fallback?.display ?? plugin.command;
}

function renderCapabilityChips(items = []) {
  return items.map((item) => `<span>${escapeHtml(item.label)}</span>`).join('');
}

function renderPluginsView() {
  const plugins = state?.plugins ?? [];
  text('#pluginsBadge', plugins.length ? `${plugins.length} built-in` : 'None');
  const list = document.querySelector('#pluginList');
  if (!list) return;
  if (!plugins.length) {
    list.innerHTML = '<div class="empty">No desktop surface plugins are registered.</div>';
    return;
  }
  list.innerHTML = plugins.map((pluginStatus) => {
    const plugin = pluginStatus.descriptor ?? {};
    const busy = pluginBusy === plugin.id;
    const tabs = (plugin.tabs ?? []).slice(0, 8).join(', ');
    const moreTabs = Math.max(0, (plugin.tabs ?? []).length - 8);
    const fallback = pluginStatus.fallback?.display ?? 'Standard relay TUI';
    const version = pluginStatus.version ? ` / ${pluginStatus.version}` : '';
    const embeddedRunning = !!state?.embedded_terminal?.running;
    return `
      <section class="plugin-card ${pluginStatus.installed ? 'installed' : pluginStatus.available ? 'fallback' : 'missing'}">
        <div class="plugin-card-main">
          <div class="plugin-title-row">
            <div>
              <strong>${escapeHtml(plugin.name)}</strong>
              <span>${escapeHtml(plugin.package_name)}${escapeHtml(version)}</span>
            </div>
            <span class="badge">${escapeHtml(pluginStateLabel(pluginStatus))}</span>
          </div>
          <p>${escapeHtml(plugin.description)}</p>
          <code>${escapeHtml(pluginPrimaryCommand(pluginStatus))}</code>
          <div class="plugin-meta-grid">
            <div>
              <small>Panels</small>
              <span>${escapeHtml(tabs)}${moreTabs ? ` +${moreTabs}` : ''}</span>
            </div>
            <div>
              <small>Fallback</small>
              <span>${escapeHtml(fallback)}</span>
            </div>
            <div>
              <small>Source</small>
              <span>${escapeHtml(plugin.source_url)}</span>
            </div>
          </div>
          <div class="plugin-chip-row">
            ${renderCapabilityChips(plugin.keybindings)}
            ${renderCapabilityChips(plugin.session_actions)}
          </div>
        </div>
        <div class="plugin-actions">
          <button class="secondary small" type="button" data-plugin-action="install" data-plugin-id="${escapeHtml(plugin.id)}" ${busy || !pluginStatus.installer ? 'disabled' : ''}>Install</button>
          <button class="secondary small" type="button" data-plugin-action="update" data-plugin-id="${escapeHtml(plugin.id)}" ${busy || !pluginStatus.update ? 'disabled' : ''}>Update</button>
          <button class="primary small" type="button" data-plugin-action="launch" data-plugin-id="${escapeHtml(plugin.id)}" ${busy || !pluginStatus.launch ? 'disabled' : ''}>Open</button>
          <button class="secondary small" type="button" data-plugin-action="resume" data-plugin-id="${escapeHtml(plugin.id)}" ${busy || !pluginStatus.resume ? 'disabled' : ''}>Resume</button>
          <button class="secondary small" type="button" data-plugin-action="embed" data-plugin-id="${escapeHtml(plugin.id)}" ${busy || embeddedRunning || !pluginStatus.launch ? 'disabled' : ''}>Embed</button>
        </div>
      </section>
    `;
  }).join('');
}

function renderSessionsView() {
  if (!state) return;
  const route = activeRouteLabel();
  text('#sessionsActiveRelay', activeRelayText());
  text('#sessionsRouteBadge', state.selected_url ? route : 'No route');
  text('#sessionsCommand', sessionResumeCommand());
  const refresh = document.querySelector('#refreshSessionsBtn');
  const create = document.querySelector('#newSessionBtn');
  if (refresh) refresh.disabled = sessionsBusy || !state.selected_url;
  if (create) create.disabled = sessionsBusy || !state.selected_url;
  renderTerminalSessions();
}

function renderTerminalSessions() {
  const list = document.querySelector('#sessionsList');
  if (!list) return;
  if (!state?.selected_url) {
    list.innerHTML = '<div class="empty">Pair a relay before managing TUI sessions.</div>';
    return;
  }
  if (sessionsBusy) {
    list.innerHTML = '<div class="empty">Loading sessions...</div>';
    return;
  }
  if (!terminalSessions) {
    list.innerHTML = '<div class="empty">Refresh to load server-side tmux sessions.</div>';
    return;
  }
  const sessions = terminalSessions.sessions ?? [];
  if (!sessions.length) {
    list.innerHTML = `
      <div class="empty">
        No TUI sessions found. Open a new session or run bare hermes-relay from a terminal.
      </div>
    `;
    return;
  }
  const activeName = terminalSessions.active?.name ?? '';
  list.innerHTML = sessions.map((session) => {
    const active = session.name === activeName;
    const attached = session.attached ?? (session.live ? 1 : 0);
    const created = session.created_at ? new Date(Number(session.created_at) * 1000).toLocaleString() : 'Unknown';
    return `
      <div class="session-row ${active ? 'active' : ''}">
        <div class="session-main">
          <div class="session-title">
            <strong>${escapeHtml(session.name)}</strong>
            <span class="badge">${active ? 'Active' : attached ? `${attached} attached` : 'Idle'}</span>
          </div>
          <span>${escapeHtml(session.tmux_name ?? `hermes-${session.name}`)} / ${escapeHtml(created)}</span>
        </div>
        <div class="session-meta">
          <span>${escapeHtml(session.windows ?? 1)} window${Number(session.windows ?? 1) === 1 ? '' : 's'}</span>
          <span>${escapeHtml(session.shell ?? 'shell')}</span>
        </div>
        <div class="session-actions">
          <button class="primary small" type="button" data-session-action="resume" data-session-name="${escapeHtml(session.name)}">Resume</button>
          <button class="secondary small" type="button" data-session-action="copy" data-session-name="${escapeHtml(session.name)}">Copy</button>
          <button class="danger small" type="button" data-session-action="kill" data-session-name="${escapeHtml(session.name)}">Kill</button>
        </div>
      </div>
    `;
  }).join('');
}

function diagnosticRows() {
  const session = selectedSession();
  const terminalCli = terminalCliStatus();
  const checks = [
    {
      label: 'Active relay',
      value: activeRelayText(),
      status: state?.selected_url ? 'ok' : 'warn'
    },
    {
      label: 'Route',
      value: activeRouteLabel(),
      status: state?.selected_url ? 'ok' : 'idle'
    },
    {
      label: 'CLI shim',
      value: terminalCli.available ? cliLabel(terminalCli) : `Missing: ${terminalCli.command ?? 'hermes-relay'}`,
      status: terminalCli.available ? 'ok' : 'error'
    },
    {
      label: 'Daemon',
      value: daemonLabel(state?.daemon ?? {}),
      status: state?.daemon?.running ? 'ok' : state?.daemon?.paused ? 'warn' : 'idle'
    },
    {
      label: 'Desktop tools',
      value: session?.tools_consented ? 'Consented' : 'Not consented',
      status: session?.tools_consented ? 'ok' : 'warn'
    },
    {
      label: 'Computer use',
      value: state?.config?.experimental_computer_use ? 'Enabled' : 'Disabled',
      status: state?.config?.experimental_computer_use ? 'warn' : 'idle'
    },
    {
      label: 'Overlay',
      value: state?.config?.overlay?.visible === false ? 'Hidden' : 'Visible',
      status: state?.config?.overlay?.visible === false ? 'idle' : 'ok'
    },
    {
      label: 'Blocklist',
      value: `${state?.config?.blocklist?.length ?? 0} rules`,
      status: (state?.config?.blocklist?.length ?? 0) > 0 ? 'ok' : 'warn'
    },
    {
      label: 'Sessions',
      value: `${state?.sessions?.length ?? 0} stored`,
      status: (state?.sessions?.length ?? 0) > 0 ? 'ok' : 'warn'
    },
    {
      label: 'Session store',
      value: state?.session_store_path ?? 'Unknown',
      status: state?.session_store_path ? 'ok' : 'warn'
    },
    {
      label: 'Config',
      value: state?.config_path ?? 'Unknown',
      status: state?.config_path ? 'ok' : 'warn'
    },
    {
      label: 'Pending grants',
      value: `${state?.pending_grants?.length ?? 0}`,
      status: (state?.pending_grants?.length ?? 0) > 0 ? 'warn' : 'ok'
    }
  ];
  return checks;
}

function renderDiagnostics() {
  if (!state) return;
  const grid = document.querySelector('#diagnosticGrid');
  if (!grid) return;
  grid.innerHTML = diagnosticRows().map((check) => `
    <div class="diagnostic-row ${routeClass(check.status)}">
      <span class="diagnostic-status ${routeClass(check.status)}"></span>
      <div>
        <strong>${escapeHtml(check.label)}</strong>
        <span>${escapeHtml(check.value)}</span>
      </div>
    </div>
  `).join('');
}

function renderState(nextState) {
  state = nextState;
  document.querySelector('#statusStrip')?.classList.remove('error');
  const daemon = state.daemon;
  const activity = activityStatus(state);
  document.querySelector('#statusStrip .dot').className = `dot ${activity.cls}`;
  text('#statusText', activity.label);
  text('#cliStatus', cliLabel(state.cli));
  text('#daemonStatus', daemonLabel(daemon));
  text('#overlayStatus', state.config.overlay?.visible === false ? 'Hidden' : 'Visible');
  text('#controlBadge', activity.badge);
  text('#observeState', daemon.running ? 'Ready' : daemon.paused ? 'Paused' : 'Offline');
  text('#controlState', state.config.experimental_computer_use ? 'Flagged' : 'Disabled');
  text('#policyState', `${state.config.blocklist?.length ?? 0} rules`);
  text('#configPath', state.config_path);
  updateControlButtons(daemon);
  if (!document.querySelector('#pairRemote').value) {
    document.querySelector('#pairRemote').value = state.config.relay_url ?? '';
  }
  renderConnectionSummary();
  renderToolConsentControls();
  renderStoredSessions();
  setPairBusy(pairBusy);
  refreshPairPreview();
  renderChatView();
  renderConnectView();
  renderEmbeddedTerminalControls();
  renderPluginsView();
  renderSessionsView();
  renderDevicesView();
  renderDiagnostics();
  updateSettingsForm(state.config);
  renderGrants(state.pending_grants ?? []);
  renderLog('#recentLog', state.task_log, true);
  renderLog('#taskLog', state.task_log);
}

async function loadState() {
  const requestId = ++loadSerial;
  try {
    const nextState = await invoke('get_dashboard_state');
    if (requestId === loadSerial) {
      renderState(nextState);
    }
  } catch (err) {
    if (requestId === loadSerial) {
      showStatusError(err);
    }
    throw err;
  }
  return state;
}

async function runControlAction(action) {
  if (controlBusy) return;
  setControlsBusy(true);
  try {
    await action();
    await loadState();
  } catch (err) {
    showStatusError(err);
  } finally {
    setControlsBusy(false);
  }
}

function unwrapPairingInvite(raw) {
  const trimmed = raw.trim();
  if (!trimmed) return '';
  const match = trimmed.match(/hermes-relay:\/\/pair(?:\/([^?\s#]+))?(?:\?([^\s#]+))?/i);
  if (!match) return trimmed;
  const candidate = match[0];
  try {
    const url = new URL(candidate);
    const queryPayload = url.searchParams.get('payload') || url.searchParams.get('p');
    if (queryPayload?.trim()) return queryPayload.trim();
    const pathPayload = url.pathname.replace(/^\/+/, '');
    if (pathPayload) return decodeURIComponent(pathPayload);
  } catch {
    // Fall through to URLSearchParams for copied lines with a partial URL.
  }
  if (match[2]) {
    const params = new URLSearchParams(match[2]);
    const queryPayload = params.get('payload') || params.get('p');
    if (queryPayload?.trim()) return queryPayload.trim();
  }
  return match[1] ? decodeURIComponent(match[1]) : trimmed;
}

function parsePairingPayload(raw) {
  const trimmed = unwrapPairingInvite(raw);
  if (!trimmed) throw new Error('Paste a pairing invite first.');
  let text = trimmed;
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    try {
      const normalized = trimmed.replaceAll('-', '+').replaceAll('_', '/');
      const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=');
      text = atob(padded);
      parsed = JSON.parse(text);
    } catch {
      throw new Error('Invite must be JSON or base64-encoded JSON.');
    }
  }
  if (!parsed || typeof parsed !== 'object') throw new Error('Invite is not a JSON object.');
  if (typeof parsed.key !== 'string' || !parsed.key.trim()) {
    throw new Error('Invite is missing a pairing key.');
  }
  if (!parsed.relay || typeof parsed.relay.code !== 'string' || !parsed.relay.code.trim()) {
    throw new Error('Invite is missing relay.code. Start the relay on the server and mint a new invite.');
  }
  return parsed;
}

function payloadCandidates(payload) {
  if (Array.isArray(payload.endpoints) && payload.endpoints.length) {
    return payload.endpoints
      .filter((entry) => entry && typeof entry === 'object')
      .map((entry) => ({
        role: String(entry.role ?? 'unknown'),
        priority: Number.isFinite(entry.priority) ? entry.priority : 0,
        api: entry.api ?? {},
        relay: entry.relay ?? {}
      }))
      .filter((entry) => entry.relay?.url || entry.api?.host);
  }
  const host = String(payload.host ?? '');
  const role = looksLikeTailscale(host) ? 'tailscale' : 'lan';
  return [{
    role,
    priority: 0,
    api: { host, port: payload.port ?? 8642, tls: !!payload.tls },
    relay: payload.relay ?? {}
  }];
}

function manualCandidate() {
  const url = document.querySelector('#pairRemote').value.trim();
  if (!url) return [];
  return [{
    role: looksLikeTailscale(url) ? 'tailscale' : 'manual',
    priority: 0,
    api: {},
    relay: { url }
  }];
}

function renderEndpointPreview(candidates, error = '') {
  const target = document.querySelector('#endpointPreview');
  if (!target) return;
  if (error) {
    target.innerHTML = `<div class="empty error">${escapeHtml(error)}</div>`;
    return;
  }
  if (!candidates.length) {
    target.innerHTML = '<div class="empty">Endpoint candidates will appear here before pairing.</div>';
    return;
  }
  target.innerHTML = `
    <div class="mini-header">
      <h4>Endpoint candidates</h4>
      <span>${candidates.length} route${candidates.length === 1 ? '' : 's'}</span>
    </div>
    <div class="endpoint-list">
      ${candidates.map((candidate) => {
        const role = roleLabel(candidate.role, candidate.relay?.url);
        const api = candidate.api?.host
          ? `${candidate.api.host}:${candidate.api.port ?? ''}${candidate.api.tls ? ' tls' : ''}`
          : 'API resolved by relay URL';
        const relay = candidate.relay?.url || 'Relay URL from invite';
        return `
          <div class="endpoint-row ${routeClass(candidate.role)}">
            <span class="route-pill">${escapeHtml(role)}</span>
            <div>
              <strong>${escapeHtml(relay)}</strong>
              <small>priority ${escapeHtml(candidate.priority)} / ${escapeHtml(api)}</small>
            </div>
          </div>
        `;
      }).join('')}
    </div>
  `;
}

function refreshPairPreview() {
  if (activePairMethod === 'qr') {
    const raw = document.querySelector('#pairQr').value;
    if (!raw.trim()) {
      renderEndpointPreview([]);
      return;
    }
    try {
      const payload = parsePairingPayload(raw);
      renderEndpointPreview(payloadCandidates(payload));
    } catch (err) {
      renderEndpointPreview([], String(err.message ?? err));
    }
    return;
  }
  if (activePairMethod === 'manual') {
    renderEndpointPreview(manualCandidate());
    return;
  }
  renderEndpointPreview([]);
}

function renderStoredSessions() {
  const panel = document.querySelector('#storedSessionPanel');
  if (!panel) return;
  const sessions = state?.sessions ?? [];
  if (!sessions.length) {
    panel.innerHTML = '<div class="empty">No stored sessions found. Pair with an invite or manual code first.</div>';
    return;
  }
  panel.innerHTML = sessions.map((session) => {
    const active = session.url === state?.selected_url;
    const route = roleLabel(session.endpoint_role, session.url);
    return `
      <button class="stored-session-row ${active ? 'active' : ''}" type="button" data-use-session="${escapeHtml(session.url)}" ${pairBusy ? 'disabled' : ''}>
        <span class="route-pill">${escapeHtml(route)}</span>
        <span>${escapeHtml(session.url)}</span>
        <strong>${active ? 'Active' : 'Use'}</strong>
      </button>
    `;
  }).join('');
}

function parseRelayFromOutput(stdout) {
  const line = String(stdout ?? '').split(/\r?\n/).find((entry) => entry.trim().startsWith('Relay:'));
  return line?.split('Relay:')[1]?.trim() ?? '';
}

function looksLikeConsumedInvite(output) {
  return /Invalid pairing code or session token/i.test(String(output ?? ''));
}

async function useStoredSession(url) {
  if (!url) return;
  const config = {
    ...state.config,
    relay_url: url
  };
  await invoke('save_desktop_config', { config });
  appendPairLog('success', `Desktop active relay set to ${url}.`);
  await loadState();
  setRoute('overview');
}

async function pairWithCurrentMethod() {
  if (pairBusy) return;
  setPairBusy(true);
  try {
    const grantTools = document.querySelector('#pairGrantTools').checked;
    const startAfterPair = document.querySelector('#pairStartDaemon').checked;

    if (activePairMethod === 'stored') {
      const selected = state?.selected_url || state?.sessions?.[0]?.url;
      if (!selected) {
        appendPairLog('error', 'No stored session is available.');
        return;
      }
      await useStoredSession(selected);
      return;
    }

    const manualRemote = document.querySelector('#pairRemote').value.trim();
    const manualCode = document.querySelector('#pairCode').value.trim().toUpperCase();
    const pairQr = activePairMethod === 'qr' ? document.querySelector('#pairQr').value.trim() : '';
    if (activePairMethod === 'manual') {
      if (!manualRemote) throw new Error('Enter the relay URL before pairing.');
      if (!/^[A-Z0-9]{6}$/.test(manualCode)) {
        throw new Error('Pairing code must be 6 letters or numbers.');
      }
    }
    const nextTarget = activePairMethod === 'manual' ? manualRemote : 'the route selected from this invite';
    const replacing = state?.selected_url && (activePairMethod === 'qr' || manualRemote !== state.selected_url);

    if (replacing) {
      const ok = window.confirm(`Replace the active desktop relay?\n\nCurrent: ${state.selected_url}\nNew: ${nextTarget}`);
      if (!ok) {
        appendPairLog('warn', 'Pairing canceled before replacing the active relay.');
        return;
      }
    }

    resetPairLog('Pairing');
    appendPairLog('info', activePairMethod === 'qr' ? 'Validating pairing invite.' : 'Validating manual code.');
    if (activePairMethod === 'qr') {
      parsePairingPayload(pairQr);
      appendPairLog('info', 'CLI will probe LAN, Tailscale, and other invite routes by priority.');
    } else {
      appendPairLog('info', `Checking manual relay ${manualRemote}.`);
    }

    const result = await invoke('pair_relay', {
      remote: activePairMethod === 'manual' ? manualRemote : '',
      code: activePairMethod === 'manual' ? manualCode : '',
      pairQr: activePairMethod === 'qr' ? pairQr : null,
      grantTools,
      startAfterPair
    });
    const output = [result.stdout, result.stderr].filter(Boolean).join('\n').trim();
    if (output) appendPairLog(result.ok ? 'success' : 'error', output);
    await loadState();
    if (!result.ok) {
      if (looksLikeConsumedInvite(output) && state?.selected_url) {
        appendPairLog(
          'warn',
          `This invite was already used or expired, but this desktop is already paired to ${state.selected_url}.`
        );
        setRoute('overview');
      }
      return;
    }
    const relay = parseRelayFromOutput(result.stdout) || state?.selected_url || 'paired relay';
    appendPairLog('success', `Active desktop relay is ${relay}.`);
    setRoute('overview');
  } finally {
    setPairBusy(false);
  }
}

function renderDevicesView() {
  const list = document.querySelector('#devicesList');
  const refresh = document.querySelector('#refreshDevicesBtn');
  if (refresh) refresh.disabled = !hasActiveRelay();
  if (list && !hasActiveRelay()) {
    list.innerHTML = '<div class="empty">Pair a relay before managing devices.</div>';
  }
}

async function refreshDevices() {
  const list = document.querySelector('#devicesList');
  if (!hasActiveRelay()) {
    renderDevicesView();
    return;
  }
  list.innerHTML = '<div class="empty">Loading</div>';
  try {
    const result = await invoke('list_devices', { remote: state?.selected_url ?? null });
    if (!result.ok) {
      list.innerHTML = `<div class="empty error">${escapeHtml(result.stderr || result.stdout)}</div>`;
      await loadState();
      return;
    }
    const devices = JSON.parse(result.stdout || '[]');
    if (!devices.length) {
      list.innerHTML = '<div class="empty">No devices</div>';
      return;
    }
    list.innerHTML = devices.map((device) => `
      <div class="device-row">
        <div>
          <strong>${escapeHtml(device.device_name ?? '(unnamed)')}</strong>
          <span>${escapeHtml(device.token_prefix ?? '')}${device.is_current ? ' / current' : ''}</span>
        </div>
        <button class="danger small" data-revoke="${escapeHtml(device.token_prefix ?? '')}">Revoke</button>
      </div>
    `).join('');
  } catch (err) {
    list.innerHTML = `<div class="empty error">${escapeHtml(err)}</div>`;
  }
}

async function refreshTerminalSessions() {
  if (!state?.selected_url || sessionsBusy) {
    renderSessionsView();
    return;
  }
  sessionsBusy = true;
  renderSessionsView();
  try {
    const result = await invoke('list_terminal_sessions', { remote: state.selected_url });
    const combined = [result.stdout, result.stderr].filter(Boolean).join('\n').trim();
    if (!result.ok) {
      terminalSessions = null;
      text('#sessionsFeedback', combined || 'Session refresh failed.');
      await loadState();
      return;
    }
    terminalSessions = JSON.parse(result.stdout || '{"sessions":[]}');
    text('#sessionsFeedback', `Loaded ${(terminalSessions.sessions ?? []).length} session${(terminalSessions.sessions ?? []).length === 1 ? '' : 's'}.`);
  } catch (err) {
    terminalSessions = null;
    text('#sessionsFeedback', `Session refresh failed: ${String(err.message ?? err)}`);
  } finally {
    sessionsBusy = false;
    renderSessionsView();
  }
}

async function openTuiSession(name = null, fresh = false) {
  text('#sessionsFeedback', fresh ? 'Opening new TUI session...' : 'Opening TUI session...');
  try {
    await invoke('open_tui_session', {
      remote: null,
      sessionName: name,
      fresh
    });
    text('#sessionsFeedback', fresh ? 'Opened a new TUI session.' : `Opened ${name || 'the active session'}.`);
    await loadState();
  } catch (err) {
    text('#sessionsFeedback', `Open failed: ${String(err.message ?? err)}`);
  }
}

async function killTuiSession(name) {
  if (!name) return;
  const ok = window.confirm(`Kill TUI session "${name}"?\n\nThis destroys the server-side tmux session.`);
  if (!ok) return;
  text('#sessionsFeedback', `Killing ${name}...`);
  try {
    const result = await invoke('kill_tui_session', {
      remote: state?.selected_url ?? null,
      sessionName: name
    });
    const combined = [result.stdout, result.stderr].filter(Boolean).join('\n').trim();
    text('#sessionsFeedback', combined || (result.ok ? `Killed ${name}.` : `Kill failed for ${name}.`));
    await refreshTerminalSessions();
  } catch (err) {
    text('#sessionsFeedback', `Kill failed: ${String(err.message ?? err)}`);
  }
}

async function copySessionCommand(name) {
  await copyText(sessionResumeCommand(name));
  text('#sessionsFeedback', `Copied resume command for ${name}.`);
}

async function copyCommand(index) {
  const row = commandRows()[Number(index)];
  if (!row) return;
  await copyText(row.command);
  text('#copyFeedback', `Copied ${row.title}.`);
}

async function openTuiTerminal() {
  text('#copyFeedback', 'Opening Remote TUI...');
  try {
    await invoke('open_tui_terminal', { remote: null });
    text('#copyFeedback', 'Opened Remote TUI in a terminal.');
  } catch (err) {
    text('#copyFeedback', `Open TUI failed: ${String(err.message ?? err)}`);
  }
}

async function startEmbeddedTerminal(fresh = false) {
  if (embeddedBusy) return;
  if (!state?.selected_url) {
    setEmbeddedFeedback('Pair a relay before starting the embedded TUI.');
    setRoute('pair');
    return;
  }
  embeddedBusy = true;
  renderEmbeddedTerminalControls();
  const term = ensureEmbeddedTerminal();
  if (!term) {
    embeddedBusy = false;
    setEmbeddedFeedback('Embedded terminal could not initialize.');
    renderEmbeddedTerminalControls();
    return;
  }
  term.clear();
  term.write('\x1b[38;5;80mStarting Hermes embedded TUI...\x1b[0m\r\n');
  fitEmbeddedTerminal();
  try {
    const status = await invoke('start_embedded_terminal', {
      remote: null,
      sessionName: terminalSessionName(),
      fresh,
      cols: term.cols || 100,
      rows: term.rows || 30
    });
    embeddedTerminal.id = status.id ?? null;
    embeddedTerminal.running = !!status.running;
    setEmbeddedFeedback(fresh ? 'Started a new embedded TUI session.' : 'Attached embedded TUI session.');
    await loadState();
    term.focus();
  } catch (err) {
    term.write(`\r\n\x1b[31mEmbedded TUI failed: ${String(err.message ?? err)}\x1b[0m\r\n`);
    setEmbeddedFeedback(`Embedded TUI failed: ${String(err.message ?? err)}`);
  } finally {
    embeddedBusy = false;
    renderEmbeddedTerminalControls();
  }
}

async function stopEmbeddedTerminal() {
  if (embeddedBusy) return;
  embeddedBusy = true;
  renderEmbeddedTerminalControls();
  try {
    await invoke('stop_embedded_terminal');
    embeddedTerminal.running = false;
    setEmbeddedFeedback('Stopped embedded TUI.');
    await loadState();
  } catch (err) {
    setEmbeddedFeedback(`Stop failed: ${String(err.message ?? err)}`);
  } finally {
    embeddedBusy = false;
    renderEmbeddedTerminalControls();
  }
}

async function runPluginAction(pluginId, action) {
  if (!pluginId || pluginBusy) return;
  pluginBusy = pluginId;
  renderPluginsView();
  text('#pluginFeedback', `${action[0].toUpperCase()}${action.slice(1)} in progress...`);
  try {
    if (action === 'install') {
      const result = await invoke('install_plugin', { pluginId });
      const output = [result.stdout, result.stderr].filter(Boolean).join('\n').trim();
      text('#pluginFeedback', output || (result.ok ? 'Plugin installed.' : 'Plugin install failed.'));
    } else if (action === 'update') {
      const result = await invoke('update_plugin', { pluginId });
      const output = [result.stdout, result.stderr].filter(Boolean).join('\n').trim();
      text('#pluginFeedback', output || (result.ok ? 'Plugin updated.' : 'Plugin update failed.'));
    } else if (action === 'launch' || action === 'resume') {
      await invoke('launch_plugin_terminal', {
        pluginId,
        resume: action === 'resume'
      });
      text('#pluginFeedback', action === 'resume' ? 'Opened plugin resume session.' : 'Opened plugin terminal.');
    } else if (action === 'embed') {
      await startEmbeddedPluginTerminal(pluginId, false);
    }
    await loadState();
  } catch (err) {
    text('#pluginFeedback', `${action} failed: ${String(err.message ?? err)}`);
  } finally {
    pluginBusy = null;
    renderPluginsView();
  }
}

async function startEmbeddedPluginTerminal(pluginId, resume = false) {
  if (embeddedBusy) return;
  embeddedBusy = true;
  setRoute('tui');
  const term = ensureEmbeddedTerminal();
  if (!term) {
    embeddedBusy = false;
    text('#pluginFeedback', 'Embedded terminal could not initialize.');
    return;
  }
  term.clear();
  term.write('\x1b[38;5;80mStarting plugin surface...\x1b[0m\r\n');
  fitEmbeddedTerminal();
  try {
    const status = await invoke('start_plugin_embedded_terminal', {
      pluginId,
      resume,
      cols: term.cols || 100,
      rows: term.rows || 30
    });
    embeddedTerminal.id = status.id ?? null;
    embeddedTerminal.running = !!status.running;
    setEmbeddedFeedback(`Attached ${status.surface_label ?? 'plugin'} in the embedded terminal.`);
    await loadState();
    term.focus();
  } catch (err) {
    term.write(`\r\n\x1b[31mPlugin terminal failed: ${String(err.message ?? err)}\x1b[0m\r\n`);
    setEmbeddedFeedback(`Plugin terminal failed: ${String(err.message ?? err)}`);
  } finally {
    embeddedBusy = false;
    renderEmbeddedTerminalControls();
  }
}

async function copyText(value) {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(value);
      return;
    } catch {
      // Fall through to the textarea path for WebView clipboard restrictions.
    }
  }
  const scratch = document.createElement('textarea');
  scratch.value = value;
  scratch.setAttribute('readonly', '');
  scratch.style.position = 'fixed';
  scratch.style.left = '-9999px';
  document.body.appendChild(scratch);
  scratch.select();
  const ok = document.execCommand('copy');
  scratch.remove();
  if (!ok) {
    throw new Error('clipboard API unavailable');
  }
}

function renderDoctorJson(report) {
  const lines = [
    `version: ${report.version ?? 'unknown'}`,
    `binary: ${report.binary_path ?? 'unknown'}`,
    `platform: ${report.platform ?? 'unknown'}/${report.arch ?? 'unknown'}`,
    `on path: ${report.on_path ? 'yes' : 'no'}`,
    `sessions: ${report.sessions_count ?? 0}`,
    `sessions file: ${report.sessions_file ?? 'unknown'}`,
    `daemon: ${report.daemon_detected ? 'installed' : report.daemon_note ?? 'not installed'}`
  ];
  const workspace = report.workspace ?? {};
  if (workspace.cwd) lines.push(`cwd: ${workspace.cwd}`);
  if (workspace.git_branch) lines.push(`branch: ${workspace.git_branch}`);
  return lines.join('\n');
}

async function runDoctor() {
  const output = document.querySelector('#doctorOutput');
  if (output) output.textContent = 'Running hermes-relay doctor --json...';
  text('#doctorStatus', 'Running');
  try {
    const result = await invoke('run_doctor');
    const combined = [result.stdout, result.stderr].filter(Boolean).join('\n').trim();
    if (!result.ok) {
      text('#doctorStatus', `Failed ${result.code ?? ''}`.trim());
      if (output) output.textContent = combined || 'doctor failed without output';
      await loadState();
      return;
    }
    let rendered = combined || 'doctor completed without output';
    try {
      rendered = renderDoctorJson(JSON.parse(result.stdout || '{}'));
    } catch {
      // Keep raw output when an older CLI does not emit JSON.
    }
    text('#doctorStatus', 'Passed');
    if (output) output.textContent = rendered;
    await loadState();
  } catch (err) {
    text('#doctorStatus', 'Failed');
    if (output) output.textContent = String(err.message ?? err);
  }
}

document.querySelectorAll('[data-route]').forEach((btn) => {
  btn.addEventListener('click', () => setRoute(btn.dataset.route));
});

bindVoiceControls();

document.querySelectorAll('[data-route-target]').forEach((btn) => {
  btn.addEventListener('click', () => setRoute(btn.dataset.routeTarget));
});

document.querySelectorAll('.method-tab').forEach((btn) => {
  btn.addEventListener('click', () => setPairMethod(btn.dataset.pairMethod));
});

document.querySelector('#pairQr')?.addEventListener('input', refreshPairPreview);
document.querySelector('#pairRemote')?.addEventListener('input', refreshPairPreview);

document.querySelectorAll('input[name="chatMode"]').forEach((input) => {
  input.addEventListener('change', () => {
    chatState.mode = input.value;
    renderChatView();
  });
});

document.querySelector('#chatGatewayUrl')?.addEventListener('input', renderChatView);

document.querySelector('#chatSaveGatewayBtn')?.addEventListener('click', () => {
  saveChatGatewayUrl().catch(showStatusError);
});

document.querySelector('#chatForm')?.addEventListener('submit', (event) => {
  event.preventDefault();
  sendChatPrompt().catch(showStatusError);
});

document.querySelector('#chatPrompt')?.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault();
    sendChatPrompt().catch(showStatusError);
  }
});

document.querySelector('#chatStopBtn')?.addEventListener('click', stopChatPrompt);
document.querySelector('#chatRetryBtn')?.addEventListener('click', () => {
  sendChatPrompt(chatState.lastPrompt).catch(showStatusError);
});
document.querySelector('#chatNewBtn')?.addEventListener('click', newChatSession);
document.querySelector('#chatClearBtn')?.addEventListener('click', () => {
  chatState.messages = [];
  renderChatView();
});

document.querySelector('#storedSessionPanel')?.addEventListener('click', async (event) => {
  const btn = event.target.closest('[data-use-session]');
  if (!btn || pairBusy) return;
  resetPairLog('Pairing');
  try {
    await useStoredSession(btn.dataset.useSession);
  } catch (err) {
    appendPairLog('error', String(err));
  }
});

document.querySelector('#startBtn')?.addEventListener('click', async () => {
  if (!hasActiveRelay()) {
    showStatusError('Pair a relay before starting the daemon.');
    setRoute('pair');
    return;
  }
  if (!selectedSession()?.tools_consented) {
    showStatusError('Grant desktop tools for the active relay before starting the daemon.');
    setRoute('overview');
    return;
  }
  await runControlAction(() => invoke('start_daemon', { remote: state?.selected_url ?? null }));
});

async function setDesktopToolConsent(consented) {
  try {
    await invoke('set_desktop_tool_consent', {
      remote: state?.selected_url ?? null,
      consented
    });
    await loadState();
  } catch (err) {
    showStatusError(err);
  }
}

document.querySelector('#grantToolsBtn')?.addEventListener('click', () => {
  setDesktopToolConsent(true);
});

document.querySelector('#revokeToolsBtn')?.addEventListener('click', () => {
  setDesktopToolConsent(false);
});

document.querySelector('#pauseBtn')?.addEventListener('click', async () => {
  await runControlAction(() => invoke('stop_daemon'));
});

document.querySelector('#emergencyBtn')?.addEventListener('click', async () => {
  await runControlAction(async () => {
    await invoke('emergency_stop');
    setRoute('log');
  });
});

document.querySelector('#pairForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  try {
    await pairWithCurrentMethod();
  } catch (err) {
    appendPairLog('error', String(err.message ?? err));
  }
});

document.querySelector('#refreshDevicesBtn').addEventListener('click', refreshDevices);

document.querySelector('#connectCommandList')?.addEventListener('click', async (event) => {
  const btn = event.target.closest('[data-copy-command]');
  if (!btn) return;
  try {
    await copyCommand(btn.dataset.copyCommand);
  } catch (err) {
    text('#copyFeedback', `Copy failed: ${String(err.message ?? err)}`);
  }
});

document.querySelector('#copyInstallBtn')?.addEventListener('click', async () => {
  try {
    await copyText(installCommand());
    text('#copyFeedback', 'Copied CLI install command.');
  } catch (err) {
    text('#copyFeedback', `Copy failed: ${String(err.message ?? err)}`);
  }
});

document.querySelector('#openTuiBtn')?.addEventListener('click', openTuiTerminal);

document.querySelector('#embeddedResumeBtn')?.addEventListener('click', () => startEmbeddedTerminal(false));

document.querySelector('#embeddedNewBtn')?.addEventListener('click', () => startEmbeddedTerminal(true));

document.querySelector('#embeddedStopBtn')?.addEventListener('click', stopEmbeddedTerminal);

document.querySelector('#pluginList')?.addEventListener('click', async (event) => {
  const btn = event.target.closest('[data-plugin-action]');
  if (!btn) return;
  await runPluginAction(btn.dataset.pluginId, btn.dataset.pluginAction);
});

document.querySelector('#refreshSessionsBtn')?.addEventListener('click', refreshTerminalSessions);

document.querySelector('#newSessionBtn')?.addEventListener('click', () => openTuiSession(null, true));

document.querySelector('#sessionsList')?.addEventListener('click', async (event) => {
  const btn = event.target.closest('[data-session-action]');
  if (!btn) return;
  const name = btn.dataset.sessionName;
  if (btn.dataset.sessionAction === 'resume') {
    await openTuiSession(name, false);
  } else if (btn.dataset.sessionAction === 'kill') {
    await killTuiSession(name);
  } else if (btn.dataset.sessionAction === 'copy') {
    await copySessionCommand(name);
  }
});

document.querySelector('#refreshDiagnosticsBtn')?.addEventListener('click', async () => {
  await loadState();
  renderDiagnostics();
});

document.querySelector('#runDoctorBtn')?.addEventListener('click', runDoctor);

document.querySelector('#devicesList').addEventListener('click', async (event) => {
  const btn = event.target.closest('[data-revoke]');
  if (!btn) return;
  await invoke('revoke_device', { remote: state?.selected_url ?? null, prefix: btn.dataset.revoke });
  await loadState();
  await refreshDevices();
});

document.querySelector('#grantList').addEventListener('click', async (event) => {
  const approve = event.target.closest('[data-grant-approve]');
  const reject = event.target.closest('[data-grant-reject]');
  const id = approve?.dataset.grantApprove ?? reject?.dataset.grantReject;
  if (!id) return;
  await invoke('resolve_grant', {
    id,
    approved: !!approve,
    reason: approve ? '' : 'Rejected from Hermes Relay Desktop'
  });
  await loadState();
});

document.querySelector('#clearLogBtn').addEventListener('click', async () => {
  await invoke('clear_task_log');
  await loadState();
});

document.querySelector('#settingsForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  const relayOverride = document.querySelector('#settingsRelay').value.trim();
  const config = {
    ...state.config,
    relay_url: relayOverride || state.selected_url || null,
    auto_start_daemon: document.querySelector('#settingsAutoStart').checked,
    experimental_computer_use: document.querySelector('#settingsComputerUse').checked,
    emergency_shortcut: document.querySelector('#settingsShortcut').value.trim() || 'Ctrl+Shift+H',
    overlay: {
      ...(state.config.overlay ?? {}),
      visible: document.querySelector('#settingsOverlayVisible').checked
    },
    blocklist: document.querySelector('#settingsBlocklist').value
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean)
  };
  await invoke('save_desktop_config', { config });
  await loadState();
});

listen('dashboard://refresh', () => {
  loadState().catch(() => {});
}).catch(showStatusError);
listen('dashboard://route', (event) => setRoute(event.payload)).catch(showStatusError);
listen('chat://line', (event) => {
  const payload = event.payload ?? {};
  if (chatState.turnId !== null && Number(payload.id) !== Number(chatState.turnId)) return;
  if (payload.stream === 'stdout') {
    handleChatEventLine(String(payload.line ?? ''));
  } else if (payload.line) {
    text('#chatDiagnostics', String(payload.line));
  }
}).catch(showStatusError);
listen('chat://exit', (event) => {
  const payload = event.payload ?? {};
  if (chatState.turnId !== null && Number(payload.id) !== Number(chatState.turnId)) return;
  chatState.busy = false;
  chatState.turnId = null;
  if (payload.success === false && payload.message && !String(payload.message).includes('stopped')) {
    appendChatMessage('event', String(payload.message), 'error');
  }
  renderChatView();
  loadState().catch(() => {});
}).catch(showStatusError);
listen('terminal://output', (event) => {
  const payload = event.payload ?? {};
  if (embeddedTerminal.id !== null && payload.id !== embeddedTerminal.id) return;
  const term = ensureEmbeddedTerminal();
  if (term) {
    document.querySelector('#embeddedTerminalEmpty')?.classList.add('hidden');
    term.write(String(payload.data ?? ''));
  }
}).catch(showStatusError);
listen('terminal://exit', (event) => {
  const payload = event.payload ?? {};
  if (embeddedTerminal.id !== null && payload.id !== embeddedTerminal.id) return;
  embeddedTerminal.running = false;
  setEmbeddedFeedback(String(payload.message ?? 'Embedded terminal stopped.'));
  renderEmbeddedTerminalControls();
  loadState().catch(() => {});
}).catch(showStatusError);

loadState().then(() => setRoute('overview')).catch(() => {});
setPairMethod('qr');
setInterval(() => {
  loadState().catch(() => {});
}, 5000);
