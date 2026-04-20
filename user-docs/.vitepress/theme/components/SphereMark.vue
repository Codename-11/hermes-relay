<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
// Canonical algorithm lives in preview/web/sphere.js — line-for-line mirror of
// MorphingSphereCore.kt. This path reaches it from .vitepress/theme/components/.
import {
  SphereState,
  paramsFor,
  colorsFor,
  forEachSphereCell,
  fbm,
} from '../../../../preview/web/sphere.js'

const canvasEl = ref<HTMLCanvasElement | null>(null)
const containerEl = ref<HTMLDivElement | null>(null)

// Grid density mirrors the on-device MorphingSphere (58×34). Canvas aspect is
// nudged toward square so the sphere fills the frame instead of floating inside
// a tall 9:16 envelope — the algorithm's baseRadius = 0.60 × min(rows/2, cols*charAspect/2)
// leaves ~40% margin, so a square canvas gets the sphere+ring filling ~93% of it.
const COLS = 58
const ROWS = 34
// Rectangular detection field: full viewport width × container height. Cursor
// anywhere inside the horizontal band gives full proximity; past the top / bottom
// of the container the proximity falls off linearly over this many heights.
// Picked wider than the visual falloff (0.6) so the blend between cursor-gaze and
// scroll-gaze plays out over enough cursor travel to pair smoothly with the
// 180 ms EMA — stops the eye from feeling jumpy near the band edges.
const VERT_FALLOFF_FRACTION = 1.0
// Scroll-tracking gaze anchors to the Install section's top edge. When the top
// is this fraction of the viewport height away from entering view, the eye is
// halfway to fully-down; at 0 gap (install touching viewport bottom), eye is
// fully down. Ratios picked so the eye is already looking at install by the
// time install becomes visible.
const INSTALL_SELECTOR = '.install-section'
const SCROLL_RAMP_VIEWPORT_FRACTION = 0.5

let rafId = 0
let timeOrigin = performance.now()
let lastFrameMs = performance.now()
let reduceMotion = false
let isVisible = true
let installEl: HTMLElement | null = null
const mouse = { x: 0, y: 0, valid: false }

// Smoothed gaze inputs — an EMA over the raw pointer vector prevents the
// per-frame jitter that comes from pointermove's big discrete jumps. Separate
// time constants because proximity wants a bit more hang-time than direction.
let smoothLookVx = 0
let smoothLookVy = 0
let smoothProximity = 0
const LOOK_TAU_SEC = 0.18
const PROXIMITY_TAU_SEC = 0.28
// Cap asin/acos inputs below 1 so we stay in the soft-slope middle of the
// inverse-trig curve. Tiny reduction in effective gaze range, huge smoothness win.
const GAZE_INPUT_CAP = 0.9

class Tween {
  current: number
  target: number
  start: number
  startTime: number
  duration: number
  constructor(value: number) {
    this.current = value
    this.target = value
    this.start = value
    this.startTime = 0
    this.duration = 0.8
  }
  setTarget(v: number, nowSec: number, duration = 0.8) {
    if (v === this.target) return
    this.start = this.current
    this.target = v
    this.startTime = nowSec
    this.duration = duration
  }
  update(nowSec: number) {
    if (this.duration <= 0) {
      this.current = this.target
      return
    }
    const t = Math.min(1, (nowSec - this.startTime) / this.duration)
    this.current = this.start + (this.target - this.start) * fastOutSlowIn(t)
  }
}

function fastOutSlowIn(t: number): number {
  if (t <= 0) return 0
  if (t >= 1) return 1
  const cx = 1.2,
    bx = -0.6 - cx,
    ax = 1 - cx - bx
  const cy = 0,
    by = 3 - cy,
    ay = 1 - cy - by
  let u = t
  for (let i = 0; i < 6; i++) {
    const x = ((ax * u + bx) * u + cx) * u
    const dx = (3 * ax * u + 2 * bx) * u + cx
    if (Math.abs(dx) < 1e-6) break
    u -= (x - t) / dx
  }
  return ((ay * u + by) * u + cy) * u
}

const initialP = paramsFor(SphereState.Idle)
const initialC = colorsFor(SphereState.Idle)
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
  cr1: new Tween(initialC.r1),
  cg1: new Tween(initialC.g1),
  cb1: new Tween(initialC.b1),
  cr2: new Tween(initialC.r2),
  cg2: new Tween(initialC.g2),
  cb2: new Tween(initialC.b2),
  intensity: new Tween(0),
}

let currentState: string = SphereState.Idle
function retargetTo(state: string, nowSec: number) {
  if (state === currentState) return
  currentState = state
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

function onPointerMove(e: PointerEvent) {
  mouse.x = e.clientX
  mouse.y = e.clientY
  mouse.valid = true
}
function onPointerLeave() {
  mouse.valid = false
}

function resize() {
  const canvas = canvasEl.value
  const container = containerEl.value
  if (!canvas || !container) return
  const dpr = window.devicePixelRatio || 1
  const cw = container.clientWidth
  const ch = container.clientHeight
  if (cw <= 0 || ch <= 0) return
  canvas.style.width = `${cw}px`
  canvas.style.height = `${ch}px`
  canvas.width = Math.floor(cw * dpr)
  canvas.height = Math.floor(ch * dpr)
  const ctx = canvas.getContext('2d')
  if (ctx) ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
}

function render() {
  const canvas = canvasEl.value
  // Refs may briefly be null between parent mount and child patch — reschedule
  // instead of dying, so the loop survives template timing quirks.
  if (!canvas) {
    rafId = requestAnimationFrame(render)
    return
  }
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    rafId = requestAnimationFrame(render)
    return
  }
  // If canvas has no sized backing store yet (pre-resize), try once and bail
  // this frame if still empty.
  if (!canvas.width || !canvas.height) {
    resize()
    if (!canvas.width || !canvas.height) {
      rafId = requestAnimationFrame(render)
      return
    }
  }

  const nowSec = (performance.now() - timeOrigin) / 1000
  for (const k in tw) (tw as Record<string, Tween>)[k].update(nowSec)

  const canvasW = parseFloat(canvas.style.width)
  const canvasH = parseFloat(canvas.style.height)
  const cellW = canvasW / COLS
  const cellH = canvasH / ROWS
  const charSize = Math.min(cellW * 1.3, cellH * 1.1)
  const charAspect = cellW / cellH

  // ── Gaze via eye (bright spot), not body translation ───────
  // One unified target. Scroll-tracking is the baseline ("watching" the reader
  // scroll toward the Install block); cursor tracking overlays on top only
  // inside/near the detection band, blended by a rectangular falloff. This
  // avoids the cursor↔drift flicker at the band boundary — there's never
  // competing sources, just a smooth weight from 0 (scroll only) to 1 (cursor).
  const rect = canvas.getBoundingClientRect()
  const spCx = rect.left + rect.width / 2
  const spCy = rect.top + rect.height / 2

  // Scroll baseline — anchor to the Install section's top edge, not the
  // viewport center. That's the *semantic* target: what the reader is about to
  // read. installGap = "how much vertical space between install's top and the
  // bottom of the viewport". Positive → install still below the fold; zero →
  // install just entering view; negative → install already scrolled into view.
  //
  // Ramp: when installGap ≥ rampStart (install is more than 50 % viewport-height
  // away from entering) the eye sits neutral (scrollVy = 0, bright spot
  // forward). As the gap shrinks, scrollVy ramps linearly to 1. By the time
  // install's top touches the viewport bottom, scrollVy = 1 and the eye is
  // already looking straight down at it. Anchors the animation to the reader's
  // semantic focus, not to viewport geometry.
  const viewportH = window.innerHeight
  if (!installEl) {
    installEl = document.querySelector<HTMLElement>(INSTALL_SELECTOR)
  }
  let scrollVy = 0
  if (installEl) {
    const installRect = installEl.getBoundingClientRect()
    const installGap = installRect.top - viewportH
    const rampStart = Math.max(1, viewportH * SCROLL_RAMP_VIEWPORT_FRACTION)
    if (installGap <= 0) {
      scrollVy = 1
    } else if (installGap < rampStart) {
      scrollVy = 1 - installGap / rampStart
    }
  } else {
    // Fallback when the install section isn't on the page (other routes, SSR
    // race): aim at the viewport center. Preserves the gaze-follows-scroll
    // feel without the install anchor.
    const fallbackOffset = viewportH / 2 - spCy
    const fallbackScale = Math.max(1, viewportH * 0.6)
    scrollVy = Math.max(-1, Math.min(1, fallbackOffset / fallbackScale))
  }

  // Cursor overlay weight — 1 inside the container's vertical band, linear
  // falloff over VERT_FALLOFF_FRACTION × height past top/bottom edges, 0 far
  // away. No horizontal falloff — "fill width" detection.
  let cursorWeight = 0
  let cursorVx = 0
  let cursorVy = 0
  if (mouse.valid) {
    const mdx = mouse.x - spCx
    const mdy = mouse.y - spCy
    const vertBandHalf = rect.height / 2
    const vertFalloffPx = rect.height * VERT_FALLOFF_FRACTION
    const absMdy = Math.abs(mdy)
    if (absMdy <= vertBandHalf) {
      cursorWeight = 1
    } else if (vertFalloffPx > 0) {
      cursorWeight = Math.max(0, 1 - (absMdy - vertBandHalf) / vertFalloffPx)
    }
    if (cursorWeight > 0) {
      // Scaled coordinates — NOT unit vector. Unit-vector `mdy/dist` saturates
      // at ±1 from the first pixel of offset, which means the direction snaps
      // through zero as the cursor crosses near the sphere center (or as the
      // sphere scrolls past a stationary cursor). Dividing by half the
      // container gives a linear gradient from 0 (at center) to ±1 (at the
      // container edge), with Math.min/max clamping cursors farther away.
      // Result: smooth near-center, same "cursor far = full gaze" at the edges.
      const scale = Math.max(1, rect.height / 2)
      cursorVx = Math.max(-1, Math.min(1, mdx / scale))
      cursorVy = Math.max(-1, Math.min(1, mdy / scale))
    }
  }

  // Blend cursor into the scroll baseline. Scroll X is 0, so rawLookVx is just
  // the weighted cursor X; rawLookVy crossfades from scroll (far) to cursor (in
  // band). The eye always has a coherent target — no underlying discontinuity
  // for the EMA to smooth away.
  const rawLookVx = cursorVx * cursorWeight
  const rawLookVy = cursorVy * cursorWeight + scrollVy * (1 - cursorWeight)
  // Baseline lock 0.75 (scroll-watching), ramp to 1.0 inside the band so
  // gazeBlend reaches its max on direct hover.
  const rawProximity = 0.75 + 0.25 * cursorWeight

  // EMA smooth the raw pointer inputs. `alpha = 1 - exp(-dt/tau)` is a
  // frame-rate-independent low-pass — same responsiveness at 30 fps as 144 fps.
  const nowMs = performance.now()
  const dt = Math.min(0.1, (nowMs - lastFrameMs) / 1000)
  lastFrameMs = nowMs
  const alphaLook = 1 - Math.exp(-dt / LOOK_TAU_SEC)
  const alphaProx = 1 - Math.exp(-dt / PROXIMITY_TAU_SEC)
  smoothLookVx += (rawLookVx - smoothLookVx) * alphaLook
  smoothLookVy += (rawLookVy - smoothLookVy) * alphaLook
  smoothProximity += (rawProximity - smoothProximity) * alphaProx
  const proximity = smoothProximity
  const lookVx = smoothLookVx
  const lookVy = smoothLookVy

  // Ambient wander — tiny fbm offset so the eye has a little breathing motion
  // when neither scroll nor cursor is moving. Small enough it can't compete
  // with the coherent target, big enough you feel the sphere's alive.
  const wanderX = (fbm(nowSec * 0.05 + 2.1, 7.7, 2) * 2 - 1) * 0.07
  const wanderY = (fbm(3.3, nowSec * 0.06 + 11.1, 2) * 2 - 1) * 0.07

  // Convert to light angles. The algorithm uses:
  //   lx = sin(lightAngle1) * 0.65   ly = cos(lightAngle2) * 0.65
  // Solving for lx = cursorDx (normalized) and ly = cursorDy (normalized):
  //   lightAngle1 = asin(cursorDx), lightAngle2 = acos(cursorDy)
  // Cap inputs at ±0.9 to stay in the soft-slope middle region of asin/acos
  // (their derivatives blow up at ±1), which kills the micro-jerk near the
  // sphere's edges without a visible loss of gaze range.
  const tvx = Math.max(-GAZE_INPUT_CAP, Math.min(GAZE_INPUT_CAP, lookVx + wanderX))
  const tvy = Math.max(-GAZE_INPUT_CAP, Math.min(GAZE_INPUT_CAP, lookVy + wanderY))
  const gazeAngleX = Math.asin(tvx)
  const gazeAngleY = Math.acos(tvy)

  // How hard to lock the light to the bias. proximity is in [0.75, 1.0] now
  // (scroll-baseline → cursor-lock), so gazeBlend lives in [0.76, 0.90] —
  // always locked to the target. Reduced motion zeroes it.
  const gazeBlend = reduceMotion ? 0 : 0.35 + 0.55 * proximity

  // Intensity + state respond to cursor engagement specifically, not to the
  // scroll-watching baseline. Use cursorWeight (raw, not smoothed) so the
  // palette shift reads as "hover = attentive", "no hover = calm watching."
  tw.intensity.setTarget(cursorWeight * 0.7, nowSec, 0.4)
  if (cursorWeight > 0.5) retargetTo(SphereState.Listening, nowSec)
  else if (cursorWeight < 0.2) retargetTo(SphereState.Idle, nowSec)

  ctx.clearRect(0, 0, canvasW, canvasH)

  if (!isVisible) {
    rafId = requestAnimationFrame(render)
    return
  }

  ctx.font = `${charSize}px "Space Mono", ui-monospace, Menlo, Consolas, monospace`
  ctx.textBaseline = 'alphabetic'

  const t = reduceMotion ? 0 : nowSec
  const colorPhase = (((t * 1000) % 8000) / 8000) * 6.2832
  const frame = {
    cols: COLS,
    rows: ROWS,
    charAspect,
    state: currentState,
    time: t,
    colorPhase,
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
    toolCallBurst: 0,
    voiceAmplitude: 0,
    voiceMode: false,
    voiceRadiusScale: 1,
    lightAngleBiasX: gazeAngleX,
    lightAngleBiasY: gazeAngleY,
    lightAngleBlend: gazeBlend,
    // Darken the shadow hemisphere so the bright-spot "eye" reads clearly
    // against the rest of the sphere. 0.6 halves distBrightness on the fully
    // unlit side while leaving the lit side untouched.
    shadowStrength: 0.6,
  }

  forEachSphereCell(frame, (col: number, row: number, ch: string, r: number, g: number, b: number, a: number) => {
    const px = col * cellW
    const py = row * cellH + cellH * 0.8
    ctx.fillStyle = `rgba(${Math.round(r * 255)},${Math.round(g * 255)},${Math.round(b * 255)},${a.toFixed(3)})`
    ctx.fillText(ch, px, py)
  })

  rafId = requestAnimationFrame(render)
}

let intersectionObserver: IntersectionObserver | null = null
let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  lastFrameMs = performance.now()
  installEl = document.querySelector<HTMLElement>(INSTALL_SELECTOR)
  resize()
  window.addEventListener('pointermove', onPointerMove, { passive: true })
  document.addEventListener('pointerleave', onPointerLeave)

  if (containerEl.value) {
    intersectionObserver = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) isVisible = entry.isIntersecting
      },
      { threshold: 0 }
    )
    intersectionObserver.observe(containerEl.value)

    resizeObserver = new ResizeObserver(() => resize())
    resizeObserver.observe(containerEl.value)
  }

  rafId = requestAnimationFrame(render)
})

onBeforeUnmount(() => {
  cancelAnimationFrame(rafId)
  window.removeEventListener('pointermove', onPointerMove)
  document.removeEventListener('pointerleave', onPointerLeave)
  intersectionObserver?.disconnect()
  resizeObserver?.disconnect()
})
</script>

<template>
  <div class="sphere-mark-wrap">
    <div ref="containerEl" class="sphere-mark">
      <canvas ref="canvasEl" class="sphere-mark-canvas" aria-hidden="true"></canvas>
    </div>
  </div>
</template>

<style scoped>
.sphere-mark-wrap {
  display: flex;
  justify-content: center;
  /* Zero bottom padding — install-section owns the 3rem separator below us. */
  padding: 0 24px 0;
  /* Negative margin-top borrows back some of VPHero's 48px bottom padding so
     the sphere tucks up closer to the actions. Without this we get a visible
     empty strip between the buttons and the sphere top. */
  margin-top: -32px;
}
.sphere-mark {
  /* Floor at 240px (down from 280px) so mobile — where clamp() lands on the
     floor because viewport width × 45vw is below it — gets a smaller canvas
     and therefore a smaller vertical footprint. Desktop caps at 380px. */
  width: clamp(240px, 45vw, 380px);
  aspect-ratio: 1 / 1;
  display: block;
}
.sphere-mark-canvas {
  display: block;
  width: 100%;
  height: 100%;
  background: transparent;
}
</style>
