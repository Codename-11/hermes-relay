// Embedded single-page UI for `hermes-relay voice mode`.
//
// Everything lives in one HTML string so the Node binary stays self-contained
// (no static asset directory to ship). Vanilla JS only — no framework, no
// build step, no external CDNs. The page talks ONLY to the local Node server
// over loopback; the relay bearer token never leaves the Node process.
//
// Wire protocol with the Node side:
//   POST {base}/turn      Content-Type: audio/<mime>   Body: raw audio bytes
//                         → SSE stream:
//                             event: transcript  data: {"text": "..."}
//                             event: delta       data: {"text": "..."}
//                             event: complete    data: {"text": "..."}
//                             event: error       data: {"message": "..."}
//   POST {base}/synthesize Content-Type: application/json
//                         Body: {"text": "..."}
//                         → audio/mpeg bytes (binary)
//
// The {base} prefix is `/v/<nonce>` — see voiceServer.ts for why.

export function renderVoicePage(opts: { base: string; relayUrl: string }): string {
  const safeBase = JSON.stringify(opts.base)
  const safeRelayUrl = escapeHtml(opts.relayUrl)
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Hermes Voice Mode</title>
<style>
  :root {
    color-scheme: dark;
    --bg: #0b0d10;
    --panel: #14181d;
    --panel-2: #1b2028;
    --fg: #e6e8eb;
    --muted: #8a939c;
    --accent: #5eead4;
    --accent-fg: #042f2c;
    --you: #93c5fd;
    --agent: #fbcfe8;
    --err: #fca5a5;
    --border: #232a31;
  }
  html, body {
    margin: 0; height: 100%; background: var(--bg); color: var(--fg);
    font-family: ui-sans-serif, -apple-system, "Segoe UI", system-ui, sans-serif;
    font-size: 15px;
  }
  body { display: grid; grid-template-rows: auto 1fr auto; }
  header {
    padding: 14px 20px; border-bottom: 1px solid var(--border);
    display: flex; align-items: center; gap: 16px;
  }
  header h1 { font-size: 14px; margin: 0; font-weight: 600; letter-spacing: 0.04em; text-transform: uppercase; color: var(--muted); }
  header .relay { font-size: 12px; color: var(--muted); margin-left: auto; }
  #status {
    font-size: 12px; padding: 4px 10px; border-radius: 999px;
    background: var(--panel-2); color: var(--muted);
    border: 1px solid var(--border);
  }
  #status[data-state="recording"] { color: var(--err); border-color: var(--err); }
  #status[data-state="thinking"] { color: var(--accent); border-color: var(--accent); }
  #status[data-state="speaking"] { color: var(--you); border-color: var(--you); }
  #status[data-state="error"] { color: var(--err); border-color: var(--err); }
  main {
    padding: 20px; overflow-y: auto;
    display: flex; flex-direction: column; gap: 12px;
  }
  .turn {
    display: flex; flex-direction: column; gap: 6px;
    padding: 12px 14px; border-radius: 10px; background: var(--panel);
    border: 1px solid var(--border);
  }
  .turn .label { font-size: 11px; letter-spacing: 0.06em; text-transform: uppercase; color: var(--muted); }
  .turn.you .label { color: var(--you); }
  .turn.agent .label { color: var(--agent); }
  .turn.error .label { color: var(--err); }
  .turn .body { white-space: pre-wrap; line-height: 1.5; }
  footer {
    padding: 18px 20px; border-top: 1px solid var(--border);
    display: flex; flex-direction: column; align-items: center; gap: 10px;
    background: var(--panel);
  }
  .mic {
    width: 96px; height: 96px; border-radius: 50%;
    background: var(--accent); color: var(--accent-fg);
    border: none; cursor: pointer; user-select: none;
    font-size: 13px; font-weight: 600; letter-spacing: 0.04em;
    box-shadow: 0 6px 20px rgba(94, 234, 212, 0.18);
    transition: transform 60ms ease;
  }
  .mic:active, .mic[data-recording="true"] {
    transform: scale(0.96);
    background: var(--err); color: white;
    box-shadow: 0 6px 20px rgba(252, 165, 165, 0.18);
  }
  .mic:disabled { opacity: 0.5; cursor: not-allowed; }
  .hint { font-size: 12px; color: var(--muted); text-align: center; }
  kbd {
    background: var(--panel-2); border: 1px solid var(--border);
    padding: 1px 6px; border-radius: 4px; font-size: 11px;
  }
  /* Three-layer SVG sine waveform (ported from conjure/AudioWaveform.tsx).
     Dimensions match the conjure pill overlay (PillOverlayRoot). Color
     shifts with state via CSS custom property set on the host. */
  .wave-host {
    position: relative; width: 256px; height: 64px;
    --wave-color: var(--accent);
  }
  .wave-host[data-state="recording"] { --wave-color: var(--err); }
  .wave-host[data-state="speaking"]  { --wave-color: var(--you); }
  .wave-host[data-state="thinking"]  { --wave-color: var(--accent); }
  .wave-host svg { width: 100%; height: 100%; display: block; }
  .wave-host path { fill: none; stroke: var(--wave-color); stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
</style>
</head>
<body>
<header>
  <h1>Hermes Voice Mode</h1>
  <span id="status" data-state="idle">Idle</span>
  <span class="relay">${safeRelayUrl}</span>
</header>
<main id="log">
  <div class="turn agent">
    <div class="label">Hermes</div>
    <div class="body">Hold the mic button (or press &amp; hold <kbd>Space</kbd>) and start talking. Release to send.</div>
  </div>
</main>
<footer>
  <div class="wave-host" id="waveHost" data-state="idle">
    <svg viewBox="0 0 256 64" preserveAspectRatio="none" aria-hidden="true">
      <path id="wave0" opacity="1"></path>
      <path id="wave1" opacity="0.78"></path>
      <path id="wave2" opacity="0.56"></path>
    </svg>
  </div>
  <button id="mic" class="mic" disabled>Hold to talk</button>
  <div class="hint">Mic permission required. The bearer token stays on the Node side.</div>
</footer>
<script>
(() => {
  const BASE = ${safeBase};
  const statusEl = document.getElementById('status');
  const logEl = document.getElementById('log');
  const micBtn = document.getElementById('mic');
  const waveHost = document.getElementById('waveHost');
  const wavePaths = [
    document.getElementById('wave0'),
    document.getElementById('wave1'),
    document.getElementById('wave2')
  ];

  let stream = null;
  let recorder = null;
  let recording = false;
  let busy = false;
  let chunks = [];
  let currentAudio = null;

  // ── Waveform — verbatim port of conjure/AudioWaveform.tsx ─────────
  // Constants, WAVE_CONFIG, createWavePath, animation step, and both
  // state-transition effects mirror the source 1:1. Dimensions match the
  // conjure PillOverlayRoot usage (256×64, baselineOffset=0). Levels come
  // from a single AnalyserNode tap on the mic stream — the TTS playback
  // does NOT feed the wave (matches conjure: when speaking, the processing
  // flag drives the constant PROCESSING_BASE_LEVEL breathe, no analyser).
  const TAU = Math.PI * 2;
  const LEVEL_SMOOTHING = 0.14;
  const TARGET_DECAY_PER_FRAME = 0.988;
  const WAVE_BASE_PHASE_STEP = 0.065;
  const WAVE_PHASE_GAIN = 0.2;
  const MIN_AMPLITUDE = 0.03;
  const MAX_AMPLITUDE = 1.3;
  const PROCESSING_BASE_LEVEL = 0.16;
  const WAVE_CONFIG = [
    { frequency: 0.8,  multiplier: 2.0, phaseOffset: 0,    opacity: 1    },
    { frequency: 1.0,  multiplier: 1.7, phaseOffset: 0.85, opacity: 0.78 },
    { frequency: 1.25, multiplier: 1.3, phaseOffset: 1.7,  opacity: 0.56 }
  ];
  const WAVE_WIDTH = 256;
  const WAVE_HEIGHT = 64;
  const BASELINE_OFFSET = 0;

  // Animation state — mirrors conjure's animationStateRef.
  const waveState = { phase: 0, currentLevel: 0, targetLevel: 0 };
  // Phase flags — mirrors conjure's phaseStateRef. Read inside the rAF
  // step so transitions take effect on the next frame without a restart.
  const phaseState = { active: false, processing: false };
  let waveRafId = null;

  // Single mic analyser — matches conjure's levels prop coming from the
  // recording capture only. TTS playback does NOT tap an analyser.
  let audioCtx = null;
  let micAnalyser = null;
  let analyserBuf = null;

  function ensureAudioCtx() {
    if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    return audioCtx;
  }

  function attachMicAnalyser(mediaStream) {
    const ctx = ensureAudioCtx();
    const src = ctx.createMediaStreamSource(mediaStream);
    micAnalyser = ctx.createAnalyser();
    micAnalyser.fftSize = 256;
    micAnalyser.smoothingTimeConstant = 0.6;
    src.connect(micAnalyser);
    analyserBuf = new Uint8Array(micAnalyser.frequencyBinCount);
  }

  // ── Effect 1 — conjure: useEffect([active, processing]) ────────────
  // When !active: target = processing ? max(target, BASE) : 0
  //               if !processing: currentLevel *= 0.4 (sharp drop on stop)
  function applyTransitionEffect() {
    if (!phaseState.active) {
      waveState.targetLevel = phaseState.processing
        ? Math.max(waveState.targetLevel, PROCESSING_BASE_LEVEL)
        : 0;
      if (!phaseState.processing) {
        waveState.currentLevel *= 0.4;
        if (waveState.currentLevel < 0.0002) waveState.currentLevel = 0;
      }
    }
  }

  // ── Effect 2 — conjure: useEffect([active, processing, width, height])
  // FULL idle branch — hard-reset state, snap paths flat, cancel rAF.
  // Active branch — (re)start the rAF loop.
  function applyIdleOrStartEffect() {
    if (!(phaseState.active || phaseState.processing)) {
      waveState.targetLevel = 0;
      waveState.currentLevel = 0;
      waveState.phase = 0;
      const baseline = WAVE_HEIGHT / 2 + BASELINE_OFFSET;
      const flat = 'M 0 ' + baseline + ' L ' + WAVE_WIDTH + ' ' + baseline;
      for (let i = 0; i < WAVE_CONFIG.length; i++) {
        wavePaths[i].setAttribute('d', flat);
        wavePaths[i].setAttribute('opacity', WAVE_CONFIG[i].opacity.toString());
      }
      if (waveRafId != null) {
        cancelAnimationFrame(waveRafId);
        waveRafId = null;
      }
      return;
    }
    if (waveRafId == null) {
      waveRafId = requestAnimationFrame(waveStep);
    }
  }

  // ── Effect-equivalent: useEffect([levels, active]) ─────────────────
  // Called each frame from the rAF step when mic is recording. Same
  // average×0.75 + peak×0.65 → pow(0.7)*1.2 boost → target *= 0.35 + 0.65.
  function feedMicLevels() {
    if (!phaseState.active || !micAnalyser || !analyserBuf) return;
    try {
      micAnalyser.getByteFrequencyData(analyserBuf);
    } catch { return; }
    let sum = 0;
    let peak = 0;
    for (let i = 0; i < analyserBuf.length; i++) {
      const v = analyserBuf[i] / 255;
      sum += v;
      if (v > peak) peak = v;
    }
    const average = sum / analyserBuf.length;
    const combined = Math.min(1, average * 0.75 + peak * 0.65);
    const boosted = Math.min(1, Math.pow(combined, 0.7) * 1.2);
    waveState.targetLevel = Math.min(1, waveState.targetLevel * 0.35 + boosted * 0.65);
  }

  // ── rAF step — verbatim from conjure's step() closure. ────────────
  function waveStep() {
    feedMicLevels();

    waveState.currentLevel +=
      (waveState.targetLevel - waveState.currentLevel) * LEVEL_SMOOTHING;
    if (waveState.currentLevel < 0.0002) waveState.currentLevel = 0;

    waveState.targetLevel *= TARGET_DECAY_PER_FRAME;
    if (waveState.targetLevel < 0.0005) waveState.targetLevel = 0;

    const baseLevel =
      phaseState.processing && !phaseState.active ? PROCESSING_BASE_LEVEL : 0;
    const level = Math.max(baseLevel, waveState.currentLevel);

    const advance = WAVE_BASE_PHASE_STEP + WAVE_PHASE_GAIN * level;
    waveState.phase = (waveState.phase + advance) % TAU;

    const baseline = WAVE_HEIGHT / 2 + BASELINE_OFFSET;
    const waveHeight = WAVE_HEIGHT;
    const waveWidth = WAVE_WIDTH;

    for (let i = 0; i < WAVE_CONFIG.length; i++) {
      const config = WAVE_CONFIG[i];
      const amplitudeFactor = Math.min(
        MAX_AMPLITUDE,
        Math.max(MIN_AMPLITUDE, level * config.multiplier)
      );
      const amplitude = Math.max(1, waveHeight * 0.75 * amplitudeFactor);
      const phase = waveState.phase + config.phaseOffset;
      wavePaths[i].setAttribute('d', createWavePath(waveWidth, baseline, amplitude, config.frequency, phase));
      wavePaths[i].setAttribute('opacity', config.opacity.toString());
    }

    waveRafId = requestAnimationFrame(waveStep);
  }

  // ── createWavePath — verbatim from conjure. ────────────────────────
  function createWavePath(width, baseline, amplitude, frequency, phase) {
    const segments = Math.max(72, Math.floor(width / 2));
    let path = 'M 0 ' + (baseline + amplitude * Math.sin(phase));
    for (let index = 1; index <= segments; index += 1) {
      const t = index / segments;
      const x = width * t;
      const theta = frequency * t * TAU + phase;
      const y = baseline + amplitude * Math.sin(theta);
      path += ' L ' + x + ' ' + y;
    }
    return path;
  }

  // Map our 5-state UI to conjure's (active, processing) pair:
  //   recording → active=true,  processing=false
  //   thinking  → active=false, processing=true
  //   speaking  → active=false, processing=true  (TTS playback still
  //              breathes at PROCESSING_BASE_LEVEL — same as conjure)
  //   idle/error → both false
  function setWaveState(uiState) {
    waveHost.dataset.state = uiState;
    phaseState.active = uiState === 'recording';
    phaseState.processing = uiState === 'thinking' || uiState === 'speaking';
    applyTransitionEffect();
    applyIdleOrStartEffect();
  }

  function setStatus(state, label) {
    statusEl.dataset.state = state;
    statusEl.textContent = label;
    setWaveState(state);
  }

  function appendTurn(role, text) {
    const t = document.createElement('div');
    t.className = 'turn ' + role;
    const label = document.createElement('div');
    label.className = 'label';
    label.textContent = role === 'you' ? 'You' : role === 'agent' ? 'Hermes' : 'Error';
    const body = document.createElement('div');
    body.className = 'body';
    body.textContent = text;
    t.appendChild(label);
    t.appendChild(body);
    logEl.appendChild(t);
    logEl.scrollTop = logEl.scrollHeight;
    return body;
  }

  async function initMic() {
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      // Let the browser pick its best supported codec (Chrome=webm/opus,
      // Firefox=webm/opus, Safari=mp4). Relay's transcribe accepts all three.
      const mime = pickMime();
      recorder = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined);
      recorder.ondataavailable = (e) => { if (e.data && e.data.size > 0) chunks.push(e.data); };
      recorder.onstop = onRecorderStop;
      attachMicAnalyser(stream);
      micBtn.disabled = false;
      setStatus('idle', 'Idle');
    } catch (e) {
      setStatus('error', 'Mic denied');
      appendTurn('error', 'Could not access microphone: ' + (e && e.message ? e.message : String(e)));
      micBtn.disabled = true;
    }
  }

  function pickMime() {
    const candidates = [
      'audio/webm;codecs=opus',
      'audio/webm',
      'audio/ogg;codecs=opus',
      'audio/mp4'
    ];
    for (const m of candidates) {
      if (window.MediaRecorder && MediaRecorder.isTypeSupported(m)) return m;
    }
    return null;
  }

  function startRecording() {
    if (busy || recording || !recorder) return;
    chunks = [];
    recorder.start();
    recording = true;
    micBtn.dataset.recording = 'true';
    micBtn.textContent = 'Release';
    setStatus('recording', 'Recording');
    if (currentAudio) {
      try { currentAudio.pause(); } catch {}
      currentAudio = null;
    }
  }

  function stopRecording() {
    if (!recording || !recorder) return;
    recording = false;
    micBtn.dataset.recording = 'false';
    micBtn.textContent = 'Working...';
    micBtn.disabled = true;
    recorder.stop();
  }

  async function onRecorderStop() {
    busy = true;
    setStatus('thinking', 'Transcribing');
    const blob = new Blob(chunks, { type: recorder.mimeType || 'audio/webm' });
    chunks = [];

    let youBody = null;
    let agentBody = null;
    let finalText = '';

    try {
      const res = await fetch(BASE + '/turn', {
        method: 'POST',
        headers: { 'Content-Type': blob.type },
        body: blob
      });
      if (!res.ok || !res.body) {
        const txt = await safeText(res);
        throw new Error('turn failed (' + res.status + '): ' + txt);
      }
      const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
      let buf = '';
      while (true) {
        const r = await reader.read();
        if (r.done) break;
        buf += r.value;
        let idx;
        while ((idx = buf.indexOf('\\n\\n')) >= 0) {
          const frame = buf.slice(0, idx);
          buf = buf.slice(idx + 2);
          const ev = parseSseFrame(frame);
          if (!ev) continue;
          if (ev.event === 'transcript') {
            const t = (ev.data && ev.data.text) || '';
            youBody = appendTurn('you', t);
            agentBody = appendTurn('agent', '');
            setStatus('thinking', 'Thinking');
          } else if (ev.event === 'delta') {
            const t = (ev.data && ev.data.text) || '';
            if (agentBody) agentBody.textContent += t;
            finalText += t;
          } else if (ev.event === 'complete') {
            const t = (ev.data && ev.data.text) || finalText;
            if (agentBody) agentBody.textContent = t;
            finalText = t;
          } else if (ev.event === 'error') {
            const msg = (ev.data && ev.data.message) || 'unknown error';
            appendTurn('error', msg);
            setStatus('error', 'Error');
            return;
          }
        }
      }
    } catch (e) {
      appendTurn('error', e && e.message ? e.message : String(e));
      setStatus('error', 'Error');
      busy = false;
      micBtn.disabled = false;
      micBtn.textContent = 'Hold to talk';
      return;
    }

    // Synthesize the final reply and play it.
    if (finalText.trim().length > 0) {
      setStatus('speaking', 'Speaking');
      try {
        const tts = await fetch(BASE + '/synthesize', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: finalText })
        });
        if (!tts.ok) {
          const txt = await safeText(tts);
          appendTurn('error', 'TTS failed (' + tts.status + '): ' + txt);
        } else {
          const audioBlob = await tts.blob();
          const url = URL.createObjectURL(audioBlob);
          currentAudio = new Audio(url);
          // Conjure design: TTS playback does NOT feed the waveform. The
          // 'speaking' state sets processing=true → wave breathes at the
          // constant PROCESSING_BASE_LEVEL. Keep playback as plain audio.
          await currentAudio.play().catch(() => {});
          currentAudio.onended = () => {
            URL.revokeObjectURL(url);
            currentAudio = null;
          };
        }
      } catch (e) {
        appendTurn('error', 'TTS error: ' + (e && e.message ? e.message : String(e)));
      }
    }

    setStatus('idle', 'Idle');
    busy = false;
    micBtn.disabled = false;
    micBtn.textContent = 'Hold to talk';
  }

  async function safeText(res) {
    try { return await res.text(); } catch { return '<no body>'; }
  }

  function parseSseFrame(frame) {
    const lines = frame.split('\\n');
    let event = 'message';
    let dataLines = [];
    for (const line of lines) {
      if (line.startsWith('event: ')) event = line.slice(7).trim();
      else if (line.startsWith('data: ')) dataLines.push(line.slice(6));
    }
    if (dataLines.length === 0) return null;
    const raw = dataLines.join('\\n');
    try { return { event, data: JSON.parse(raw) }; }
    catch { return { event, data: { _raw: raw } }; }
  }

  // Pointer controls — desktop and touch.
  micBtn.addEventListener('mousedown', startRecording);
  micBtn.addEventListener('mouseup', stopRecording);
  micBtn.addEventListener('mouseleave', () => { if (recording) stopRecording(); });
  micBtn.addEventListener('touchstart', (e) => { e.preventDefault(); startRecording(); });
  micBtn.addEventListener('touchend', (e) => { e.preventDefault(); stopRecording(); });

  // Spacebar push-to-talk. Ignore when focus is in an editable element.
  let spaceHeld = false;
  document.addEventListener('keydown', (e) => {
    if (e.code !== 'Space' || e.repeat) return;
    const t = e.target;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
    e.preventDefault();
    if (!spaceHeld) { spaceHeld = true; startRecording(); }
  });
  document.addEventListener('keyup', (e) => {
    if (e.code !== 'Space') return;
    if (spaceHeld) { spaceHeld = false; stopRecording(); }
  });

  initMic();
})();
</script>
</body>
</html>
`
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
