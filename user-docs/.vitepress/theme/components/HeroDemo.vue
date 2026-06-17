<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
// Canonical sphere algorithm — same import path as SphereMark.vue. The hero
// demo is a code recreation of the app (not a video): DOM chat chrome over the
// real MorphingSphereCore algorithm, driven through the actual product state
// machine (Idle → Listening → Thinking + toolCallBurst → Speaking → Idle).
import {
  SphereState,
  paramsFor,
  colorsFor,
  forEachSphereCell,
} from '../../../../preview/web/sphere.js'

// ── Scene script ────────────────────────────────────────────────────────────
// One master clock, looped with modulo — every visual is a pure function of
// t, so the loop restart is just t wrapping to 0. Timings in seconds.
const PROMPT = 'Quick check — server uptime and memory?'
const ANSWER = 'Uptime: 46d 0h 42m\nMemory: 12.6 GiB / 32.4 GiB (39%)'
const TYPE_CPS = 17
const STREAM_CPS = 30

const CHECKS = ['state restored', 'route · LAN', 'hermes online']
const CHECK_AT = [0.7, 1.5, 2.3]
const BOOT_END = 3.3      // boot text fades, chat chrome fades in
const TYPE_START = 4.3
const TYPE_END = TYPE_START + PROMPT.length / TYPE_CPS
const SEND_AT = TYPE_END + 0.45
const INDICATOR_AT = SEND_AT + 0.5
const TOOL_AT = SEND_AT + 1.5
const TOOL_DONE = TOOL_AT + 3.0
const ANSWER_START = TOOL_DONE + 0.5
const ANSWER_END = ANSWER_START + ANSWER.length / STREAM_CPS
const IDLE_AT = ANSWER_END + 0.8
const FADE_AT = ANSWER_END + 3.6
const LOOP_LEN = FADE_AT + 0.8

// ── Reactive scene state (SSR renders the boot frame) ──────────────────────
const bootGone = ref(false)
const checksDone = ref(0)
const typed = ref('')
const sent = ref(false)
const indicatorOn = ref(false)
const toolState = ref<'none' | 'running' | 'done'>('none')
const answerText = ref('')
const answerDone = ref(false)
const fadingOut = ref(false)

// Which glyph the single trailing slot shows — mirrors ChatInputBar's
// AnimatedContent morph (SEND / VOICE / STOP). Typing → send (indigo arrow,
// glow); mid-stream with an empty field → stop (danger circle); at rest →
// voice (GraphicEq waveform). The dedicated slash button is gone.
const inputTrailing = computed<'send' | 'voice' | 'stop'>(() => {
  if (typed.value) return 'send'
  if (sent.value && !answerDone.value) return 'stop'
  return 'voice'
})

const canvasEl = ref<HTMLCanvasElement | null>(null)
const screenEl = ref<HTMLDivElement | null>(null)

// ── Sphere plumbing (lean cut of SphereMark's tween rig — no gaze) ──────────
const COLS = 58
const ROWS = 34

class Tween {
  current: number; target: number; start: number; startTime: number; duration: number
  constructor(value: number) {
    this.current = value; this.target = value; this.start = value
    this.startTime = 0; this.duration = 0.8
  }
  setTarget(v: number, nowSec: number, duration = 0.8) {
    if (v === this.target) return
    this.start = this.current; this.target = v
    this.startTime = nowSec; this.duration = duration
  }
  update(nowSec: number) {
    if (this.duration <= 0) { this.current = this.target; return }
    // Clamp at BOTH ends — smoothstep fed a negative time extrapolates
    // cubically and explodes the params (the periods-in-the-eye bug).
    const t = Math.min(1, Math.max(0, (nowSec - this.startTime) / this.duration))
    const e = t * t * (3 - 2 * t)
    this.current = this.start + (this.target - this.start) * e
  }
}

const initialP = paramsFor(SphereState.Thinking)
const initialC = colorsFor(SphereState.Thinking)
const tw = {
  breatheSpeed: new Tween(initialP.breatheSpeed),
  breatheAmp: new Tween(initialP.breatheAmp),
  lightSpeedX: new Tween(initialP.lightSpeedX),
  lightSpeedY: new Tween(initialP.lightSpeedY),
  lightInfluence: new Tween(initialP.lightInfluence),
  coreTightness: new Tween(initialP.coreTightness),
  turbulenceAmp: new Tween(initialP.turbulenceAmp),
  rippleScale: new Tween(initialP.rippleScale),
  heartbeatSpeed: new Tween(initialP.heartbeatSpeed),
  radialFlowSpeed: new Tween(initialP.radialFlowSpeed),
  cr1: new Tween(initialC.r1), cg1: new Tween(initialC.g1), cb1: new Tween(initialC.b1),
  cr2: new Tween(initialC.r2), cg2: new Tween(initialC.g2), cb2: new Tween(initialC.b2),
  intensity: new Tween(0),
}

let sphereState: string = SphereState.Thinking
function retargetTo(state: string, nowSec: number) {
  if (state === sphereState) return
  sphereState = state
  const p = paramsFor(state)
  const c = colorsFor(state)
  tw.breatheSpeed.setTarget(p.breatheSpeed, nowSec)
  tw.breatheAmp.setTarget(p.breatheAmp, nowSec)
  tw.lightSpeedX.setTarget(p.lightSpeedX, nowSec)
  tw.lightSpeedY.setTarget(p.lightSpeedY, nowSec)
  tw.lightInfluence.setTarget(p.lightInfluence, nowSec)
  tw.coreTightness.setTarget(p.coreTightness, nowSec)
  tw.turbulenceAmp.setTarget(p.turbulenceAmp, nowSec)
  tw.rippleScale.setTarget(p.rippleScale, nowSec)
  tw.heartbeatSpeed.setTarget(p.heartbeatSpeed, nowSec)
  tw.radialFlowSpeed.setTarget(p.radialFlowSpeed, nowSec)
  tw.cr1.setTarget(c.r1, nowSec); tw.cg1.setTarget(c.g1, nowSec); tw.cb1.setTarget(c.b1, nowSec)
  tw.cr2.setTarget(c.r2, nowSec); tw.cg2.setTarget(c.g2, nowSec); tw.cb2.setTarget(c.b2, nowSec)
}

// ── Clock — accumulates only while on screen, so the loop resumes where it
// paused instead of jump-cutting when the reader scrolls back up. ───────────
let rafId = 0
let elapsed = 0
let lastFrameMs = 0
let isVisible = true
let reduceMotion = false

function sceneStateFor(t: number) {
  if (t < BOOT_END) return SphereState.Thinking
  if (t >= TYPE_START && t < SEND_AT) return SphereState.Listening
  if (t >= SEND_AT && t < ANSWER_START) return SphereState.Thinking
  if (t >= ANSWER_START && t < IDLE_AT) return SphereState.Speaking
  return SphereState.Idle
}

function applyScene(t: number) {
  bootGone.value = t >= BOOT_END
  let n = 0
  for (let i = 0; i < CHECK_AT.length; i++) if (t >= CHECK_AT[i]) n++
  checksDone.value = n
  const typedCount = Math.max(0, Math.min(PROMPT.length, Math.floor((t - TYPE_START) * TYPE_CPS)))
  typed.value = t >= SEND_AT ? '' : PROMPT.slice(0, typedCount)
  sent.value = t >= SEND_AT
  indicatorOn.value = t >= INDICATOR_AT && t < TOOL_AT
  toolState.value = t < TOOL_AT ? 'none' : t < TOOL_DONE ? 'running' : 'done'
  const streamed = Math.max(0, Math.min(ANSWER.length, Math.floor((t - ANSWER_START) * STREAM_CPS)))
  answerText.value = t < ANSWER_START ? '' : ANSWER.slice(0, streamed)
  answerDone.value = t >= ANSWER_END
  fadingOut.value = t >= FADE_AT
}

// Keep the backing store in lockstep with the canvas's CURRENT css box and
// return the logical (css-px) dimensions to draw against. The canvas size is
// CSS-driven (88cqw, plus the 900ms boot→chat width tween) so it changes
// WITHOUT screenEl ever resizing — measuring it live and reallocating only on
// a real pixel-size change keeps the backing store and the per-frame draw math
// from ever diverging. That divergence was the "sphere shrinks to ~1/3 and
// hugs the top-left on mobile" bug: resize() snapshotted one size, drawSphere
// read another, and a bailed first resize left the 300×150 default store (with
// no dpr transform) that the truthy-width guard never retried.
function syncCanvasSize(canvas: HTMLCanvasElement): { w: number; h: number } | null {
  const rect = canvas.getBoundingClientRect()
  const cw = rect.width
  const ch = rect.height
  if (cw <= 0 || ch <= 0) return null
  const dpr = window.devicePixelRatio || 1
  const bw = Math.round(cw * dpr)
  const bh = Math.round(ch * dpr)
  if (canvas.width !== bw || canvas.height !== bh) {
    // Assigning width/height clears the canvas AND resets the transform, so
    // re-apply the dpr scale right after. We redraw every frame anyway.
    canvas.width = bw
    canvas.height = bh
    const ctx = canvas.getContext('2d')
    if (ctx) ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }
  return { w: cw, h: ch }
}

function resize() {
  const canvas = canvasEl.value
  if (canvas) syncCanvasSize(canvas)
}

// sceneT loops (drives WHAT the sphere is doing); clockT is monotonic and
// drives the tween rig + noise fields. The app's sphere clock never rewinds
// (MorphingSphere.kt animatedTime), so neither can ours — a looped clock sent
// the tweens a negative elapsed at every wrap and the extrapolation slammed
// char indices to the ramp floor: rings of '·'/'.' through the sphere's eye.
function drawSphere(sceneT: number, clockT: number) {
  const canvas = canvasEl.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  // Re-sync every frame so a late-resolving layout (mobile container queries)
  // or the boot→chat width tween can't leave the backing store stale.
  const dims = syncCanvasSize(canvas)
  if (!dims) return

  retargetTo(sceneStateFor(sceneT), clockT)
  for (const k in tw) (tw as Record<string, Tween>)[k].update(clockT)
  // Streaming shimmer while Speaking; toolCallBurst pulse decays from the
  // moment the tool card lands — same inputs the app feeds the renderer.
  tw.intensity.setTarget(sphereState === SphereState.Speaking ? 0.4 : 0, clockT, 0.4)
  const burst = sceneT >= TOOL_AT && sceneT < TOOL_AT + 1.2 ? Math.exp(-(sceneT - TOOL_AT) / 0.35) : 0

  const canvasW = dims.w
  const canvasH = dims.h
  const cellW = canvasW / COLS
  const cellH = canvasH / ROWS
  const charSize = Math.min(cellW * 1.3, cellH * 1.1)

  ctx.clearRect(0, 0, canvasW, canvasH)
  ctx.font = `${charSize}px "Space Mono", ui-monospace, Menlo, Consolas, monospace`
  ctx.textBaseline = 'alphabetic'

  const time = reduceMotion ? 0 : clockT
  const frame = {
    cols: COLS,
    rows: ROWS,
    charAspect: cellW / cellH,
    state: sphereState,
    time,
    colorPhase: (((time * 1000) % 8000) / 8000) * 6.2832,
    breatheSpeed: tw.breatheSpeed.current,
    breatheAmp: tw.breatheAmp.current,
    lightSpeedX: tw.lightSpeedX.current,
    lightSpeedY: tw.lightSpeedY.current,
    lightInfluence: tw.lightInfluence.current,
    coreTightness: tw.coreTightness.current,
    turbulenceAmp: tw.turbulenceAmp.current,
    rippleScale: tw.rippleScale.current,
    heartbeatSpeed: tw.heartbeatSpeed.current,
    radialFlowSpeed: tw.radialFlowSpeed.current,
    cr1: tw.cr1.current, cg1: tw.cg1.current, cb1: tw.cb1.current,
    cr2: tw.cr2.current, cg2: tw.cg2.current, cb2: tw.cb2.current,
    intensity: tw.intensity.current,
    toolCallBurst: burst,
    voiceAmplitude: 0,
    voiceMode: false,
    voiceRadiusScale: 1,
    lightAngleBiasX: 0,
    lightAngleBiasY: 0,
    // Natural light orbit only — gaze tracking is SphereMark's job further
    // down the page; two competing eyes on one page would fight for attention.
    lightAngleBlend: 0,
    // The app passes no shadowStrength (MorphingSphere.kt → core default 0):
    // legacy pearl shading. SphereMark's 0.6 is its own eye look, not the app's.
    shadowStrength: 0,
  }

  forEachSphereCell(frame, (col: number, row: number, ch: string, r: number, g: number, b: number, a: number) => {
    const px = col * cellW
    const py = row * cellH + cellH * 0.8
    ctx.fillStyle = `rgba(${Math.round(r * 255)},${Math.round(g * 255)},${Math.round(b * 255)},${a.toFixed(3)})`
    ctx.fillText(ch, px, py)
  })
}

function render() {
  const nowMs = performance.now()
  const dt = Math.min(0.1, (nowMs - lastFrameMs) / 1000)
  lastFrameMs = nowMs
  if (isVisible) {
    elapsed += dt
    applyScene(elapsed % LOOP_LEN)
    drawSphere(elapsed % LOOP_LEN, elapsed)
  }
  rafId = requestAnimationFrame(render)
}

// Static frames (scrubber / reduced motion): retarget once, then snap every
// tween to its target so the frozen frame shows settled params, not the
// mid-transition values a single update() at t=startTime would give.
function drawSphereSettled(sceneT: number, clockT: number) {
  drawSphere(sceneT, clockT)
  for (const k in tw) {
    const w = (tw as Record<string, Tween>)[k]
    w.current = w.target
    w.start = w.target
  }
  drawSphere(sceneT, clockT)
}

let intersectionObserver: IntersectionObserver | null = null
let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  resize()

  // Design-review scrubber: ?demoT=<seconds> freezes the scene at that point
  // of the timeline (also what headless screenshot tooling uses — rAF-driven
  // clocks don't advance reliably under virtual-time fast-forward).
  const forcedT = new URLSearchParams(window.location.search).get('demoT')
  if (forcedT !== null && !Number.isNaN(parseFloat(forcedT))) {
    const t = parseFloat(forcedT) % LOOP_LEN
    screenEl.value?.classList.add('had-static')
    applyScene(t)
    drawSphereSettled(t, t)
    return
  }

  if (reduceMotion) {
    // Static completed scene: full conversation visible, sphere drawn once.
    applyScene(IDLE_AT + 0.1)
    fadingOut.value = false
    drawSphereSettled(IDLE_AT + 0.1, 0)
    return
  }

  lastFrameMs = performance.now()
  if (screenEl.value) {
    intersectionObserver = new IntersectionObserver(
      (entries) => { for (const entry of entries) isVisible = entry.isIntersecting },
      { threshold: 0 }
    )
    intersectionObserver.observe(screenEl.value)
    // Observe the CANVAS, not screenEl: the canvas resizes on its own during
    // the 88cqw→80cqw boot→chat tween, which never changes screenEl's box.
    resizeObserver = new ResizeObserver(() => resize())
    if (canvasEl.value) resizeObserver.observe(canvasEl.value)
  }
  rafId = requestAnimationFrame(render)
})

onBeforeUnmount(() => {
  cancelAnimationFrame(rafId)
  intersectionObserver?.disconnect()
  resizeObserver?.disconnect()
})
</script>

<template>
  <div class="hero-demo" role="img"
    aria-label="Animated demo of the Hermes-Relay Android app: it connects to your Hermes agent, runs a server health check through a tool call, and streams the answer back.">
    <div class="hero-demo-frame" aria-hidden="true">
      <div ref="screenEl" class="had-screen" :class="{ 'had-fading': fadingOut }">
        <!-- Sphere — one canvas shared by boot and chat so the gate sphere
             visibly settles into being the conversation's backdrop. -->
        <canvas ref="canvasEl" class="had-sphere" :class="{ 'had-sphere-chat': bootGone }"></canvas>

        <!-- Boot gate -->
        <div class="had-boot" :class="{ 'had-hidden': bootGone }">
          <div class="had-boot-title">Hermes-Relay</div>
          <div class="had-boot-sub">agent interface</div>
          <div class="had-boot-checks">
            <div v-for="(label, i) in CHECKS" :key="label" class="had-check"
              :class="{ 'had-check-on': checksDone > i }">
              <span class="had-check-mark">{{ checksDone > i ? '✓' : '·' }}</span> {{ label }}
            </div>
          </div>
        </div>

        <!-- Chat -->
        <div class="had-chat" :class="{ 'had-hidden': !bootGone }">
          <!-- Header mirrors the live app 1:1 (assets/screenshots/02_chat.png):
               drawer hamburger · avatar+presence · name/model · LAN chip ·
               share / inspector / tune action cluster. -->
          <div class="had-header">
            <svg class="had-menu" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round">
              <path d="M4 7h16M4 12h16M4 17h16" />
            </svg>
            <div class="had-avatar">H<span class="had-avatar-dot"></span></div>
            <div class="had-id">
              <div class="had-name">Hermes</div>
              <div class="had-model">gpt-5.5 · default</div>
            </div>
            <div class="had-chip">LAN</div>
            <!-- Three SEPARATE bordered buttons in the app — not one cluster. -->
            <span class="had-btn">
              <svg viewBox="0 0 24 24" fill="currentColor">
                <circle cx="18" cy="5" r="2.7" /><circle cx="6" cy="12" r="2.7" /><circle cx="18" cy="19" r="2.7" />
                <path d="M8.3 10.9 15.7 6.1M8.3 13.1l7.4 4.8" fill="none" stroke="currentColor"
                  stroke-width="2" />
              </svg>
            </span>
            <span class="had-btn had-btn-code">&lt;/&gt;</span>
            <span class="had-btn">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                stroke-linecap="round">
                <path d="M4 7h9M19.5 7h.5M4 12h.5M11 12h9M4 17h9M19.5 17h.5" />
                <circle cx="16" cy="7" r="2.3" /><circle cx="7.5" cy="12" r="2.3" /><circle cx="16" cy="17" r="2.3" />
              </svg>
            </span>
          </div>
          <div class="had-tabs">
            <span class="had-tab had-tab-active">Chat</span>
            <span class="had-tab">Manage</span>
            <span class="had-tab">Bridge</span>
          </div>

          <div class="had-messages">
            <div class="had-day">Today</div>

            <div v-if="sent" class="had-bubble had-user">
              {{ PROMPT }}
              <div class="had-time">9:41 AM</div>
            </div>

            <div v-if="indicatorOn" class="had-agent-label">Hermes</div>
            <div v-if="indicatorOn" class="had-bubble had-agent had-indicator">
              <span></span><span></span><span></span>
            </div>

            <div v-if="toolState !== 'none'" class="had-tool">
              <span class="had-tool-icon">&lt;&gt;</span>
              <span class="had-tool-name">execute_code</span>
              <template v-if="toolState === 'done'">
                <span class="had-tool-done">✓</span>
                <span class="had-tool-chev">⌄</span>
              </template>
              <span v-else class="had-tool-bar"><span></span></span>
            </div>

            <div v-if="answerText" class="had-agent-label">Hermes</div>
            <div v-if="answerText" class="had-bubble had-agent had-answer">{{ answerText }}<span
                v-if="!answerDone" class="had-caret"></span>
              <div v-if="answerDone" class="had-time">9:41 AM</div>
            </div>
          </div>

          <!-- Input bar mirrors the app's ChatInputBar: a "+" (tap attaches,
               long-press opens the command palette — no dedicated slash
               button), a rounded pill field, then ONE trailing slot that
               morphs send ⇄ voice ⇄ stop without ever widening the bar. -->
          <div class="had-input">
            <span class="had-input-plus">+</span>
            <div class="had-field" :class="{ 'had-field-busy': sent && !answerDone }">
              <template v-if="typed">{{ typed }}<span class="had-caret"></span></template>
              <span v-else class="had-placeholder">Message…</span>
            </div>
            <!-- Trailing slot — one button, three faces. -->
            <span v-if="inputTrailing === 'send'" class="had-trailing had-trailing-send">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                stroke-linecap="round" stroke-linejoin="round">
                <path d="M5 12h14M13 6l6 6-6 6" />
              </svg>
            </span>
            <span v-else-if="inputTrailing === 'stop'" class="had-trailing had-trailing-stop">
              <span class="had-stop-square"></span>
            </span>
            <!-- GraphicEq waveform = "voice conversation", not "record". -->
            <span v-else class="had-trailing had-trailing-voice">
              <svg viewBox="0 0 24 24" fill="currentColor">
                <rect x="3.5" y="9" width="2.6" height="6" rx="1.3" />
                <rect x="8.0" y="5" width="2.6" height="14" rx="1.3" />
                <rect x="12.5" y="8" width="2.6" height="8" rx="1.3" />
                <rect x="17.0" y="3.5" width="2.6" height="17" rx="1.3" />
              </svg>
            </span>
          </div>
          <div class="had-status">
            <span class="had-status-ok">api online / LAN</span>
            <span>gpt-5.5 / profile: default</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hero-demo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
}
/* Phone bezel — carried over from the video hero so the silhouette is stable. */
.hero-demo-frame {
  position: relative;
  display: block;
  padding: 10px;
  border-radius: 36px;
  background: linear-gradient(160deg, #191B31 0%, #0B0C12 100%);
  box-shadow:
    0 0 0 1px var(--vp-c-divider),
    0 30px 60px -20px rgba(0, 0, 0, 0.5),
    0 0 80px -10px var(--vp-c-brand-soft);
  width: clamp(200px, 62vw, 290px);
  margin: 0 auto;
  box-sizing: border-box;
  overflow: hidden;
}
.hero-demo-frame::before {
  content: '';
  position: absolute;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  width: 80px;
  height: 6px;
  background: #000;
  border-radius: 3px;
  z-index: 3;
  opacity: 0.6;
}

/* The screen is always the app's dark cockpit — that's what the app looks
   like. Container-query units keep every element proportional to frame width. */
.had-screen {
  position: relative;
  container-type: inline-size;
  aspect-ratio: 1080 / 2244;
  border-radius: 28px;
  background: #08090D;
  overflow: hidden;
  transition: opacity 600ms ease;
}
.had-fading { opacity: 0; }

.had-hidden { opacity: 0; pointer-events: none; }

/* ── Sphere canvas — settles from gate centerpiece into chat backdrop ── */
.had-sphere {
  position: absolute;
  left: 50%;
  top: 26%;
  width: 88cqw;
  aspect-ratio: 1 / 1;
  transform: translateX(-50%);
  opacity: 0.95;
  transition: top 900ms ease, opacity 900ms ease, width 900ms ease;
  z-index: 0;
}
.had-sphere-chat {
  top: 34%;
  width: 80cqw;
  opacity: 0.6;
}

/* Scrubber mode (?demoT=) — freeze every transition so review frames are
   exact, not caught mid-fade. */
.had-static, .had-static * { transition: none !important; animation: none !important; }

/* ── Boot gate ── */
.had-boot {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  padding-bottom: 12cqw;
  transition: opacity 500ms ease;
}
.had-boot-title {
  color: #F7F6F0;
  font-size: 7.4cqw;
  font-weight: 600;
  letter-spacing: 0.02em;
}
.had-boot-sub {
  color: rgba(247, 246, 240, 0.5);
  font-family: var(--vp-font-family-mono);
  font-size: 3.4cqw;
  letter-spacing: 0.3em;
  margin: 1.5cqw 0 6cqw;
}
.had-boot-checks {
  font-family: var(--vp-font-family-mono);
  font-size: 3.2cqw;
  line-height: 1.9;
  color: rgba(247, 246, 240, 0.35);
}
.had-check { transition: color 300ms ease; }
.had-check-on { color: rgba(247, 246, 240, 0.85); }
.had-check-mark { color: var(--hr-green); display: inline-block; width: 4cqw; }

/* ── Chat chrome ── */
.had-chat {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  transition: opacity 600ms ease;
}
.had-header {
  display: flex;
  align-items: center;
  gap: 1.6cqw;
  padding: 6.5cqw 3.5cqw 2cqw;
}
.had-menu {
  width: 4.8cqw;
  height: 4.8cqw;
  flex: none;
  color: #F7F6F0;
  margin-right: 1cqw;
}
.had-btn {
  width: 6.6cqw;
  height: 6.6cqw;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #0B0C14;
  border: 1px solid rgba(247, 246, 240, 0.45);
  border-radius: 2.2cqw;
  color: #F7F6F0;
}
.had-btn svg { width: 3.7cqw; height: 3.7cqw; }
.had-btn-code {
  font-family: var(--vp-font-family-mono);
  font-size: 2.5cqw;
  font-weight: 700;
  line-height: 1;
}
/* Light avatar with dark initial — matches the app, not inverted. */
.had-avatar {
  position: relative;
  width: 9.6cqw;
  height: 9.6cqw;
  border-radius: 50%;
  background: #E9E7F2;
  color: #16172B;
  font-size: 4.2cqw;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.had-avatar-dot {
  position: absolute;
  right: -0.4cqw;
  bottom: -0.4cqw;
  width: 2.8cqw;
  height: 2.8cqw;
  border-radius: 50%;
  background: var(--hr-green);
  border: 0.6cqw solid #08090D;
}
.had-id { flex: 1; min-width: 0; margin-left: 0.8cqw; }
.had-name {
  color: #FFFFFF;
  font-size: 3.7cqw;
  font-weight: 700;
  line-height: 1.25;
}
.had-model {
  color: rgba(247, 246, 240, 0.55);
  font-size: 2.7cqw;
  line-height: 1.3;
}
/* Solid filled pill in the app — no outline. */
.had-chip {
  flex: none;
  font-size: 2.2cqw;
  font-weight: 600;
  color: #F7F6F0;
  background: #34343E;
  border-radius: 2.4cqw;
  padding: 0.9cqw 2cqw;
  margin-right: 0.6cqw;
}
.had-tabs {
  display: flex;
  gap: 2.4cqw;
  padding: 2cqw 3.5cqw 2.5cqw;
}
.had-tab {
  flex: 1;
  text-align: center;
  font-size: 3cqw;
  font-weight: 600;
  color: rgba(247, 246, 240, 0.85);
  background: #0A0A10;
  border: 1px solid rgba(247, 246, 240, 0.25);
  border-radius: 3.6cqw;
  padding: 2.1cqw 0;
}
.had-tab-active {
  background: #20225A;
  border-color: rgba(99, 110, 230, 0.65);
  color: #FFFFFF;
}

.had-messages {
  position: relative;
  flex: 1;
  padding: 1cqw 4.5cqw 0;
  overflow: hidden;
}
.had-day {
  width: fit-content;
  margin: 0 auto 3cqw;
  font-size: 2.8cqw;
  color: rgba(247, 246, 240, 0.55);
  background: #191B31;
  border-radius: 3cqw;
  padding: 0.9cqw 3cqw;
}
.had-bubble {
  max-width: 78%;
  border-radius: 3.6cqw;
  padding: 2.6cqw 3.4cqw;
  font-size: 3.4cqw;
  line-height: 1.45;
  margin-bottom: 2.6cqw;
  white-space: pre-line;
  animation: had-pop 350ms ease;
}
@keyframes had-pop {
  from { opacity: 0; transform: translateY(2cqw); }
  to { opacity: 1; transform: translateY(0); }
}
.had-user {
  margin-left: auto;
  background: #AEBFFF;
  color: #14152A;
  border-bottom-right-radius: 1.2cqw;
}
.had-agent {
  margin-right: auto;
  background: rgba(25, 27, 49, 0.92);
  color: #F7F6F0;
  border-bottom-left-radius: 1.2cqw;
}
/* The app renders the agent name as a small dark chip above the turn. */
.had-agent-label {
  width: fit-content;
  font-size: 2.7cqw;
  color: rgba(247, 246, 240, 0.7);
  background: #191B31;
  border-radius: 2.2cqw;
  padding: 0.8cqw 2.4cqw;
  margin: 0 0 1.4cqw 0;
}
.had-time {
  font-size: 2.5cqw;
  opacity: 0.55;
  margin-top: 1.2cqw;
}

.had-indicator { display: inline-flex; gap: 1.4cqw; padding: 3cqw 3.6cqw; }
.had-indicator span {
  width: 1.8cqw;
  height: 1.8cqw;
  border-radius: 50%;
  background: rgba(247, 246, 240, 0.7);
  animation: had-bounce 1.2s infinite ease-in-out;
}
.had-indicator span:nth-child(2) { animation-delay: 0.15s; }
.had-indicator span:nth-child(3) { animation-delay: 0.3s; }
@keyframes had-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.5; }
  30% { transform: translateY(-1.2cqw); opacity: 1; }
}

.had-tool {
  display: flex;
  align-items: center;
  gap: 2.2cqw;
  background: rgba(18, 20, 38, 0.95);
  border: 1px solid rgba(247, 246, 240, 0.1);
  border-radius: 2.6cqw;
  padding: 2.4cqw 3.2cqw;
  margin-bottom: 2.6cqw;
  font-family: var(--vp-font-family-mono);
  font-size: 3cqw;
  color: rgba(247, 246, 240, 0.85);
  animation: had-pop 350ms ease;
}
.had-tool-icon { color: var(--vp-c-brand-1); font-size: 2.8cqw; }
.had-tool-name { flex: 1; }
.had-tool-done { color: rgba(247, 246, 240, 0.75); font-size: 3cqw; }
.had-tool-chev { color: rgba(247, 246, 240, 0.45); font-size: 3cqw; line-height: 0.6; }
.had-tool-bar {
  flex: none;
  width: 16cqw;
  height: 1.1cqw;
  border-radius: 1cqw;
  background: rgba(247, 246, 240, 0.12);
  overflow: hidden;
}
.had-tool-bar span {
  display: block;
  width: 40%;
  height: 100%;
  border-radius: 1cqw;
  background: #6E7CFF;
  animation: had-scan 1.1s infinite ease-in-out;
}
@keyframes had-scan {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(250%); }
}

.had-caret {
  display: inline-block;
  width: 0.5cqw;
  height: 3.4cqw;
  background: #AEBFFF;
  margin-left: 0.6cqw;
  vertical-align: -0.5cqw;
  animation: had-blink 0.9s steps(1) infinite;
}
@keyframes had-blink { 50% { opacity: 0; } }

.had-input {
  display: flex;
  align-items: center;
  gap: 2.4cqw;
  padding: 2cqw 4cqw 1.6cqw;
}
.had-input-plus {
  flex: none;
  color: rgba(247, 246, 240, 0.6);
  font-size: 5cqw;
  line-height: 1;
  width: 7cqw;
  text-align: center;
}
/* Pill field — surfaceContainerHigh + hairline outline, like the app's
   BasicTextField (no heavy outlined-field chrome). */
.had-field {
  flex: 1;
  min-height: 9cqw;
  display: flex;
  align-items: center;
  background: rgba(247, 246, 240, 0.06);
  border: 1px solid rgba(247, 246, 240, 0.16);
  border-radius: 5cqw;
  padding: 1.4cqw 3.4cqw;
  font-size: 3.3cqw;
  color: #F7F6F0;
  transition: border-color 300ms ease;
}
.had-field-busy { border-color: rgba(110, 124, 255, 0.4); }
.had-placeholder { color: rgba(247, 246, 240, 0.35); }

/* One trailing slot, three faces — circular tap target, never widens. */
.had-trailing {
  flex: none;
  width: 8.8cqw;
  height: 8.8cqw;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}
.had-trailing svg { width: 5cqw; height: 5cqw; }
.had-trailing-send {
  color: #6E7CFF;
  /* purpleGlow — the bar's one flourish, dark-only like the app */
  box-shadow: 0 0 7cqw -1cqw rgba(110, 124, 255, 0.65);
}
.had-trailing-voice { color: #6E7CFF; }
.had-trailing-stop {
  border: 1px solid var(--hr-danger);
}
.had-stop-square {
  width: 3cqw;
  height: 3cqw;
  border-radius: 0.8cqw;
  background: var(--hr-danger);
}

.had-status {
  display: flex;
  justify-content: space-between;
  font-family: var(--vp-font-family-mono);
  font-size: 2.5cqw;
  color: rgba(247, 246, 240, 0.45);
  background: #0B0C14;
  padding: 1.6cqw 4.5cqw 2.2cqw;
}
.had-status-ok { color: var(--hr-green); }

@media (max-width: 640px) {
  .hero-demo-frame {
    padding: 8px;
    border-radius: 30px;
  }
  .had-screen { border-radius: 22px; }
}
</style>
