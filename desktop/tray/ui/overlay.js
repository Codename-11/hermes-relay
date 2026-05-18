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
  const profileName = running ? 'running' : paused ? 'paused' : 'disconnected';
  applyProfile(profileName);
  chip.classList.toggle('running', running);
  chip.classList.toggle('paused', paused);
  chip.classList.toggle('disconnected', !running && !paused);
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
