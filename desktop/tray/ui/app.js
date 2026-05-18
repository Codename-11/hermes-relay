const tauri = window.__TAURI__;
const invoke = tauri.core.invoke;
const listen = tauri.event.listen;

const routes = {
  overview: document.querySelector('#overviewView'),
  pair: document.querySelector('#pairView'),
  connect: document.querySelector('#connectView'),
  diagnostics: document.querySelector('#diagnosticsView'),
  devices: document.querySelector('#devicesView'),
  grants: document.querySelector('#grantsView'),
  log: document.querySelector('#logView'),
  settings: document.querySelector('#settingsView')
};

const titles = {
  overview: 'Overview',
  pair: 'Pair',
  connect: 'Terminal / CLI',
  diagnostics: 'Diagnostics',
  devices: 'Devices',
  grants: 'Grant Requests',
  log: 'Task Log',
  settings: 'Settings'
};

let state = null;
let activeRoute = 'overview';
let activePairMethod = 'qr';
let loadSerial = 0;
let controlBusy = false;

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
  if (activeRoute === 'devices') refreshDevices();
  if (activeRoute === 'pair') refreshPairPreview();
  if (activeRoute === 'connect') renderConnectView();
  if (activeRoute === 'diagnostics') renderDiagnostics();
}

function setPairMethod(method) {
  activePairMethod = method;
  document.querySelectorAll('.method-tab').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.pairMethod === method);
  });
  document.querySelectorAll('[data-pair-panel]').forEach((panel) => {
    panel.classList.toggle('active', panel.dataset.pairPanel === method);
  });
  const submit = document.querySelector('#pairSubmitBtn');
  if (submit) {
    submit.textContent = method === 'stored' ? 'Use Stored Session' : 'Pair';
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
  return `${cliCommandPrefix()} shell${options.override ? remoteOverrideFlag() : ''}${commandUnavailableSuffix()}`;
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

function setControlsBusy(busy) {
  controlBusy = busy;
  updateControlButtons(state?.daemon);
}

function updateControlButtons(daemon) {
  const start = document.querySelector('#startBtn');
  const pause = document.querySelector('#pauseBtn');
  const emergency = document.querySelector('#emergencyBtn');
  const running = !!daemon?.running;
  const paused = !!daemon?.paused && !running;
  if (start) {
    start.disabled = controlBusy || running;
    start.textContent = running ? 'Running' : 'Start';
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
  return state?.selected_url ?? 'Not paired';
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
      title: 'Stored sessions',
      detail: 'List paired relays, grants, token TTL, route, and consent state.',
      command: statusCommand()
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

function diagnosticRows() {
  const session = selectedSession();
  const terminalCli = terminalCliStatus();
  const checks = [
    {
      label: 'Active relay',
      value: state?.selected_url || 'Not paired',
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
  const dotClass = statusClass(daemon);
  document.querySelector('#statusStrip .dot').className = `dot ${dotClass}`;
  text('#statusText', daemon.running ? 'Connected - Observing' : daemon.paused ? 'Paused' : 'Disconnected');
  text('#cliStatus', cliLabel(state.cli));
  text('#daemonStatus', daemonLabel(daemon));
  text('#overlayStatus', state.config.overlay?.visible === false ? 'Hidden' : 'Visible');
  text('#controlBadge', daemon.running ? 'Observing' : daemon.paused ? 'Paused' : 'Disconnected');
  text('#observeState', daemon.running ? 'Ready' : 'Offline');
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
  refreshPairPreview();
  renderConnectView();
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
      <button class="stored-session-row ${active ? 'active' : ''}" type="button" data-use-session="${escapeHtml(session.url)}">
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
  if (result.ok) {
    const relay = parseRelayFromOutput(result.stdout) || state?.selected_url || 'paired relay';
    appendPairLog('success', `Active desktop relay is ${relay}.`);
    setRoute('overview');
  }
}

async function refreshDevices() {
  const list = document.querySelector('#devicesList');
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

document.querySelectorAll('[data-route-target]').forEach((btn) => {
  btn.addEventListener('click', () => setRoute(btn.dataset.routeTarget));
});

document.querySelectorAll('.method-tab').forEach((btn) => {
  btn.addEventListener('click', () => setPairMethod(btn.dataset.pairMethod));
});

document.querySelector('#pairQr')?.addEventListener('input', refreshPairPreview);
document.querySelector('#pairRemote')?.addEventListener('input', refreshPairPreview);

document.querySelector('#storedSessionPanel')?.addEventListener('click', async (event) => {
  const btn = event.target.closest('[data-use-session]');
  if (!btn) return;
  resetPairLog('Pairing');
  try {
    await useStoredSession(btn.dataset.useSession);
  } catch (err) {
    appendPairLog('error', String(err));
  }
});

document.querySelector('#startBtn')?.addEventListener('click', async () => {
  if (state?.selected_url && !selectedSession()?.tools_consented) {
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

loadState().then(() => setRoute('overview')).catch(() => {});
setPairMethod('qr');
setInterval(() => {
  loadState().catch(() => {});
}, 5000);
