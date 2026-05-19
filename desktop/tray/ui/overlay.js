const tauri = window.__TAURI__;
const invoke = tauri.core.invoke;
const listen = tauri.event.listen;
let refreshSerial = 0;

const STATUS_PROFILES = {
  running: {
    dot: 'ok',
    label: 'Observing',
    width: 118
  },
  session: {
    dot: 'ok',
    label: 'TUI active',
    width: 118
  },
  tools: {
    dot: 'ok',
    label: 'Tools',
    width: 86
  },
  gateway: {
    dot: 'ok',
    label: 'Gateway',
    width: 108
  },
  approval: {
    dot: 'warn',
    label: 'Approval',
    width: 112
  },
  paused: {
    dot: 'warn',
    label: 'Paused',
    width: 94
  },
  disconnected: {
    dot: 'idle',
    label: 'Offline',
    width: 90
  },
  unavailable: {
    dot: 'warn',
    label: 'Unavailable',
    width: 132
  }
};

function applyProfile(profileName) {
  const profile = STATUS_PROFILES[profileName] ?? STATUS_PROFILES.unavailable;
  const chip = document.querySelector('#overlayChip');
  const dot = chip.querySelector('.dot');
  const mode = document.querySelector('#overlayMode');
  dot.className = `dot ${profile.dot}`;
  mode.textContent = profile.label;
  chip.dataset.state = profileName;
  chip.style.setProperty('--pill-width', `${profile.width}px`);
  chip.setAttribute('aria-label', `Hermes Relay desktop status: ${profile.label}`);
}

function setChip(state) {
  const chip = document.querySelector('#overlayChip');
  const daemon = state.daemon;
  const running = daemon.running;
  const paused = !running && daemon.paused;
  const recent = (state.task_log ?? []).slice().reverse().find((entry) => {
    const age = Date.now() - Number(entry.ts_ms ?? 0);
    return age >= 0 && age < 45_000;
  });
  const event = String(recent?.event ?? '').toLowerCase();
  const message = String(recent?.message ?? '').toLowerCase();
  let profileName = running ? 'running' : paused ? 'paused' : 'disconnected';
  if ((state.pending_grants ?? []).length || event.includes('grant') || message.includes('approval')) {
    profileName = 'approval';
  } else if (event.includes('sessions') || event.startsWith('tui_')) {
    profileName = 'session';
  } else if (message.includes('desktop_') || message.includes('tool') || event.includes('tool')) {
    profileName = 'tools';
  } else if (message.includes('gateway') || message.includes('auth') || message.includes('connected')) {
    profileName = 'gateway';
  }
  applyProfile(profileName);
  chip.classList.toggle('running', ['running', 'session', 'tools', 'gateway'].includes(profileName));
  chip.classList.toggle('paused', profileName === 'paused' || profileName === 'approval');
  chip.classList.toggle('disconnected', profileName === 'disconnected');
}

async function refresh() {
  const requestId = ++refreshSerial;
  try {
    const state = await invoke('get_dashboard_state');
    if (requestId === refreshSerial) {
      setChip(state);
    }
  } catch {
    if (requestId === refreshSerial) {
      applyProfile('unavailable');
    }
  }
}

refresh();
listen('dashboard://refresh', () => {
  refresh();
}).catch(() => {
  applyProfile('unavailable');
});
setInterval(() => {
  refresh();
}, 3000);
