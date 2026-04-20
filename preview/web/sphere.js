// MorphingSphere — JavaScript port.
//
// This is a line-for-line mirror of MorphingSphereCore.kt. Keep them in sync.
// No build step, no dependencies — loaded directly by index.html.

// ── State + params ───────────────────────────────────────────────────

export const SphereState = Object.freeze({
  Idle: "Idle",
  Thinking: "Thinking",
  Streaming: "Streaming",
  Listening: "Listening",
  Speaking: "Speaking",
  Error: "Error",
});

export function paramsFor(state) {
  switch (state) {
    case SphereState.Idle:
      return { breatheSpeed: 0.5, breatheAmp: 0.04, lightSpeedX: 0.25, lightSpeedY: 0.18, lightInfluence: 0.35, coreTightness: 0.75, turbulenceAmp: 0.06, rippleScale: 1.0, heartbeatSpeed: 1.0, radialFlowSpeed: 0.2 };
    case SphereState.Thinking:
      return { breatheSpeed: 0.8, breatheAmp: 0.02, lightSpeedX: 0.5, lightSpeedY: 0.35, lightInfluence: 0.30, coreTightness: 0.90, turbulenceAmp: 0.12, rippleScale: 1.5, heartbeatSpeed: 4.0, radialFlowSpeed: 0.1 };
    case SphereState.Streaming:
      return { breatheSpeed: 0.3, breatheAmp: 0.06, lightSpeedX: 0.15, lightSpeedY: 0.10, lightInfluence: 0.25, coreTightness: 0.60, turbulenceAmp: 0.08, rippleScale: 2.0, heartbeatSpeed: 1.5, radialFlowSpeed: 0.5 };
    case SphereState.Listening:
      return { breatheSpeed: 0.55, breatheAmp: 0.035, lightSpeedX: 0.22, lightSpeedY: 0.16, lightInfluence: 0.38, coreTightness: 0.78, turbulenceAmp: 0.05, rippleScale: 0.9, heartbeatSpeed: 1.2, radialFlowSpeed: 0.18 };
    case SphereState.Speaking:
      return { breatheSpeed: 0.45, breatheAmp: 0.05, lightSpeedX: 0.20, lightSpeedY: 0.14, lightInfluence: 0.30, coreTightness: 0.55, turbulenceAmp: 0.07, rippleScale: 1.8, heartbeatSpeed: 1.8, radialFlowSpeed: 0.45 };
    case SphereState.Error:
      return { breatheSpeed: 1.2, breatheAmp: 0.03, lightSpeedX: 0.7, lightSpeedY: 0.6, lightInfluence: 0.40, coreTightness: 0.80, turbulenceAmp: 0.15, rippleScale: 0.5, heartbeatSpeed: 6.0, radialFlowSpeed: 0.3 };
  }
  throw new Error(`Unknown state: ${state}`);
}

export function colorsFor(state) {
  switch (state) {
    case SphereState.Idle:      return { r1: 0.25, g1: 0.85, b1: 0.40, r2: 0.61, g2: 0.42, b2: 0.94 };
    case SphereState.Thinking:  return { r1: 0.30, g1: 0.55, b1: 0.95, r2: 0.55, g2: 0.35, b2: 0.90 };
    case SphereState.Streaming: return { r1: 0.20, g1: 0.90, b1: 0.50, r2: 0.25, g2: 0.80, b2: 0.85 };
    case SphereState.Listening: return { r1: 0.35, g1: 0.55, b1: 0.95, r2: 0.65, g2: 0.45, b2: 0.95 };
    case SphereState.Speaking:  return { r1: 0.25, g1: 0.92, b1: 0.55, r2: 0.30, g2: 0.85, b2: 0.88 };
    case SphereState.Error:     return { r1: 0.90, g1: 0.30, b1: 0.25, r2: 0.85, g2: 0.50, b2: 0.20 };
  }
  throw new Error(`Unknown state: ${state}`);
}

// ── Math helpers ─────────────────────────────────────────────────────

export function lerp(a, b, t) { return a + (b - a) * t; }

function clamp(x, lo, hi) { return Math.max(lo, Math.min(hi, x)); }
function atLeast(x, lo) { return Math.max(lo, x); }

// 32-bit int hash mirroring the Kotlin implementation exactly.
// Math.imul + |0 force 32-bit signed overflow semantics.
function hash(x, y) {
  let h = (Math.imul(x, 374761393) + Math.imul(y, 668265263)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177) | 0;
  h = h ^ (h >>> 16);
  return (h & 0x7fffffff) / 2147483647;
}

function smoothNoise(x, y) {
  const xi = Math.floor(x);
  const yi = Math.floor(y);
  const xf = x - xi;
  const yf = y - yi;
  const u = xf * xf * (3 - 2 * xf);
  const v = yf * yf * (3 - 2 * yf);
  const n00 = hash(xi, yi);
  const n10 = hash(xi + 1, yi);
  const n01 = hash(xi, yi + 1);
  const n11 = hash(xi + 1, yi + 1);
  return lerp(lerp(n00, n10, u), lerp(n01, n11, u), v);
}

export function fbm(x, y, octaves = 3) {
  let value = 0, amplitude = 0.5, frequency = 1;
  for (let i = 0; i < octaves; i++) {
    value += amplitude * smoothNoise(x * frequency, y * frequency);
    amplitude *= 0.5;
    frequency *= 2;
  }
  return value;
}

// ── Sphere iteration ─────────────────────────────────────────────────

const CHAR_SETS = [
  " ·:;=+*#%@",
  " .:;=+*#%@",
  " ·;:+=*%#@",
  " .:;+=*#@%",
];
const DATA_CHARS = "01<>[]{}|/\\~^";

/**
 * Mirrors `forEachSphereCell` in MorphingSphereCore.kt.
 *
 * @param frame - See SphereFrame shape in the Kotlin core.
 * @param onCell - Invoked as (col, row, char, r, g, b, alpha). RGB and alpha are 0..1.
 */
export function forEachSphereCell(frame, onCell) {
  const amp = clamp(frame.voiceAmplitude, 0, 1);

  // ── Voice modulation ────────────────────────────────────────
  let breatheSpeed = frame.breatheSpeed;
  let turbulenceAmp = frame.turbulenceAmp;
  let coreWarmth = 0.30;
  let wobbleAmplitude = 0.06;
  let dataRingSpeed = 0.4;

  if (frame.state === SphereState.Listening) {
    breatheSpeed = lerp(frame.breatheSpeed, frame.breatheSpeed * 1.3, amp * 0.5);
    turbulenceAmp = frame.turbulenceAmp + amp * 0.15;
    wobbleAmplitude = 0.06 * (1 + amp * 0.3);
  } else if (frame.state === SphereState.Speaking) {
    breatheSpeed = lerp(frame.breatheSpeed, frame.breatheSpeed * 2.0, amp);
    turbulenceAmp = frame.turbulenceAmp + amp * 0.5;
    coreWarmth = lerp(0.30, 1.0, amp);
    wobbleAmplitude = 0.06 * (1 + amp * 0.8);
    dataRingSpeed = 0.4 * (1 + amp * 3);
  }

  const cx = frame.cols / 2;
  const cy = frame.rows / 2;
  const charAspect = frame.charAspect;

  const maxRadiusFromRows = (frame.rows / 2) * 0.60;
  const maxRadiusFromCols = (frame.cols / 2) * charAspect * 0.60;
  const baseRadius = Math.min(maxRadiusFromRows, maxRadiusFromCols) * frame.voiceRadiusScale;
  const t = frame.time;

  const breathe = Math.sin(t * breatheSpeed) * frame.breatheAmp;
  const breathingRadius = baseRadius * (1 + breathe);

  const noiseJitter1 = fbm(t * 0.05 + 7.3, 1.7) * 0.5;
  const noiseJitter2 = fbm(3.1, t * 0.04 + 13.7) * 0.5;
  const naturalAngle1 = t * frame.lightSpeedX + noiseJitter1;
  const naturalAngle2 = t * frame.lightSpeedY + noiseJitter2;
  // Gaze bias — mirrors MorphingSphereCore.kt. Defaults keep original behavior.
  const biasX = frame.lightAngleBiasX ?? 0;
  const biasY = frame.lightAngleBiasY ?? 0;
  const blend = clamp(frame.lightAngleBlend ?? 0, 0, 1);
  const lightAngle1 = naturalAngle1 * (1 - blend) + biasX * blend;
  const lightAngle2 = naturalAngle2 * (1 - blend) + biasY * blend;
  const lx = Math.sin(lightAngle1) * 0.65;
  const ly = Math.cos(lightAngle2) * 0.65;
  const lz = Math.sqrt(atLeast(1 - lx * lx - ly * ly, 0.01));

  const heartbeat = Math.sin(t * frame.heartbeatSpeed) * 0.5 + 0.5;

  const pulse = Math.sin(frame.colorPhase) * 0.5 + 0.5;
  const colR = lerp(frame.cr1, frame.cr2, pulse);
  const colG = lerp(frame.cg1, frame.cg2, pulse);
  const colB = lerp(frame.cb1, frame.cb2, pulse);

  const distWeight = 1 - frame.lightInfluence;

  const effTurbulence = turbulenceAmp + frame.intensity * 0.04 + frame.toolCallBurst * 0.15;
  const effRadialFlow = frame.radialFlowSpeed + frame.intensity * 0.3;
  const effRipple = frame.rippleScale + frame.intensity * 0.5 + frame.toolCallBurst * 1.0;

  for (let row = 0; row < frame.rows; row++) {
    for (let col = 0; col < frame.cols; col++) {
      const dx = (col - cx) * charAspect;
      const dy = (row - cy);
      const dist = Math.sqrt(dx * dx + dy * dy);
      const angle = Math.atan2(dy, dx);

      const perimeterNoise = fbm(
        angle * 1.8 + t * 0.08,
        angle * 0.7 + t * 0.12
      ) * 2 - 1;
      const distortedRadius = breathingRadius * (1 + perimeterNoise * wobbleAmplitude);
      const glowRadius = distortedRadius * 1.35;
      const dataRingInner = distortedRadius * 1.40;
      const dataRingOuter = distortedRadius * 1.55;
      const normDist = dist / distortedRadius;

      if (dist > dataRingOuter) continue;

      if (normDist <= 1) {
        // ── INSIDE SPHERE ────────────────────────────
        const nx = dx / distortedRadius;
        const ny2 = dy / distortedRadius;
        const nzSq = atLeast(1 - nx * nx - ny2 * ny2, 0);
        const nz = Math.sqrt(nzSq);

        const distBrightness = atLeast(1 - normDist * normDist * frame.coreTightness, 0.15);

        const directionalLight = clamp(nx * lx + ny2 * ly + nz * lz, 0, 1);

        const structural = fbm(col * 0.25 + t * 0.18, row * 0.25 + t * 0.13, 2) * 0.15 - 0.075;

        const turbulence = fbm(col * 0.8 + t * 0.6, row * 0.8 + t * 0.45, 2) * effTurbulence - effTurbulence * 0.5;

        const radialFlow = fbm(angle * 2 + t * 0.15, dist * 0.3 - t * effRadialFlow, 2) * 0.06 - 0.03;

        const ripple = (
          Math.sin(normDist * 8 - t * 1.2) * 0.04 * (1 - normDist) +
          Math.sin(normDist * 5 - t * 0.7 + 2) * 0.03 * (1 - normDist)
        ) * effRipple;

        const heartbeatFx = heartbeat * 0.05 * (1 - normDist * normDist);

        // Shadow modulation — mirrors MorphingSphereCore.kt. shadowStrength
        // defaults to 0 (legacy pearl shading preserved byte-for-byte).
        const shadowStrength = frame.shadowStrength ?? 0;
        const shadowFactor = 1 - shadowStrength * (1 - directionalLight);
        const brightness = distWeight * distBrightness * shadowFactor + frame.lightInfluence * directionalLight + heartbeatFx;
        const charNoise = structural + turbulence + radialFlow + ripple;

        // Kotlin: `(t * 0.3f + col * 0.17f + row * 0.13f).toInt()` — truncation toward zero.
        const rotationPhase = Math.trunc(t * 0.3 + col * 0.17 + row * 0.13);
        const chars = CHAR_SETS[rotationPhase & 3];

        const charIdx = clamp(Math.trunc((brightness + charNoise) * (chars.length - 1)), 1, chars.length - 1);
        const ch = chars[charIdx];

        let edgeFade = 1;
        if (normDist > 0.80) {
          const ef = (normDist - 0.80) / 0.20;
          edgeFade = 1 - ef * ef;
        }

        const scanline = (row % 2 === 1) ? 0.82 : 1;
        const alpha = clamp((brightness * 0.4 + 0.6) * edgeFade * scanline, 0.1, 1);

        const warmth = (1 - normDist * normDist) * (coreWarmth * 0.40);
        const lightBoost = directionalLight * 0.08;

        onCell(col, row, ch,
          clamp(colR + lightBoost + warmth, 0, 1),
          clamp(colG + lightBoost * 0.5 + warmth, 0, 1),
          clamp(colB + lightBoost + warmth, 0, 1),
          alpha);

      } else if (dist <= glowRadius) {
        // ── GLOW / DEBRIS ZONE ───────────────────────
        const glowT = (dist - distortedRadius) / (glowRadius - distortedRadius);
        const glowFalloff = clamp(1 - glowT, 0, 1);

        const sparsityNoise = fbm(angle * 3.5 + t * 0.25, dist * 0.4 + t * 0.08, 2);
        const sparsityThreshold = 0.35 + glowT * 0.25;
        if (sparsityNoise < sparsityThreshold) continue;

        const debrisChars = "·:;- ";
        const debrisIdx = clamp(Math.trunc((1 - glowFalloff) * (debrisChars.length - 1)), 0, debrisChars.length - 1);
        const ch = debrisChars[debrisIdx];
        if (ch === ' ') continue;

        const alpha = glowFalloff * 0.85;
        onCell(col, row, ch, clamp(colR, 0, 1), clamp(colG, 0, 1), clamp(colB, 0, 1), alpha);

      } else if (dist >= dataRingInner) {
        // ── DATA RING ────────────────────────────────
        const ringT = (dist - dataRingInner) / (dataRingOuter - dataRingInner);
        const orbitAngle = angle - t * dataRingSpeed + ringT * 1.5;
        const ringNoise = fbm(orbitAngle * 4 + t * 0.3, ringT * 3 + t * 0.15, 2);
        if (ringNoise < 0.55) continue;

        // Kotlin `.mod(n)` is floored-positive modulo.
        const rawIdx = Math.trunc((orbitAngle * 2 + t * 0.5) * DATA_CHARS.length);
        const dataIdx = ((rawIdx % DATA_CHARS.length) + DATA_CHARS.length) % DATA_CHARS.length;
        const ch = DATA_CHARS[dataIdx];

        const ringFade = clamp(1 - ringT, 0, 1);
        const alpha = ringFade * 0.65;
        onCell(col, row, ch,
          clamp(colR * 0.85, 0, 1),
          clamp(colG * 0.85, 0, 1),
          clamp(colB * 0.85, 0, 1),
          alpha);
      }
    }
  }
}
