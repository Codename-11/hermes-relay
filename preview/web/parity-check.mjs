// Parity harness — renders sphere.js deterministically at the same frames the
// Compose @Preview decorators in MorphingSphere.kt use, then dumps each frame
// as:
//   - an ASCII silhouette (58 × 34 grid, max-alpha winner per cell)
//   - cell-by-cell tuple list: (row, col, char, r, g, b, alpha)
//   - a zone histogram: inside / glow / data-ring counts
//   - a deterministic FNV-1a checksum over the tuple stream
//
// Running it against Kotlin's MorphingSphereCore.forEachSphereCell would produce
// the same checksum — that is the bar for algorithmic parity.
//
// Run: node preview/web/parity-check.mjs
//   or: node preview/web/parity-check.mjs --state Idle

import { forEachSphereCell, paramsFor, colorsFor, SphereState } from "./sphere.js";

// ── Compose @Preview fixtures ────────────────────────────────────────
// Mirrors MorphingSphere.kt:157-240. widthDp/heightDp here drive the
// canvas aspect used to derive charAspect. The algorithm only cares about
// cols/rows/charAspect — pixel dims are advisory.
const FIXTURES = [
  { name: "Idle",             state: "Idle",      widthDp: 360, heightDp: 640, time: 5, colorPhase: 0.5 },
  { name: "Thinking",         state: "Thinking",  widthDp: 360, heightDp: 640, time: 5, colorPhase: 1.5 },
  { name: "Streaming",        state: "Streaming", widthDp: 360, heightDp: 640, time: 5, colorPhase: 2.5 },
  { name: "Error",            state: "Error",     widthDp: 360, heightDp: 640, time: 5, colorPhase: 3.0 },
  { name: "Compact",          state: "Idle",      widthDp: 200, heightDp: 200, time: 8, colorPhase: 2.0 },
  { name: "Listening",        state: "Listening", widthDp: 360, heightDp: 640, time: 5, colorPhase: 1.0, voiceAmplitude: 0.4,  voiceMode: true },
  { name: "Speaking (low)",   state: "Speaking",  widthDp: 360, heightDp: 640, time: 5, colorPhase: 2.0, voiceAmplitude: 0.2,  voiceMode: true },
  { name: "Speaking (peak)",  state: "Speaking",  widthDp: 360, heightDp: 640, time: 5, colorPhase: 2.5, voiceAmplitude: 0.95, voiceMode: true },
];

const COLS = 58;
const ROWS = 34;

function buildFrame(fx) {
  const p = paramsFor(fx.state);
  const c = colorsFor(fx.state);
  // Mirror MorphingSphere.kt: cellW = canvasW/cols, cellH = canvasH/rows, charAspect = cellW/cellH.
  // @Preview density doesn't matter (cancels); canvas is widthDp×heightDp "units".
  const cellW = fx.widthDp / COLS;
  const cellH = fx.heightDp / ROWS;
  const charAspect = cellW / cellH;
  const voiceMode = fx.voiceMode ?? false;
  return {
    cols: COLS,
    rows: ROWS,
    charAspect,
    state: fx.state,
    time: fx.time,
    colorPhase: fx.colorPhase,
    breatheSpeed: p.breatheSpeed,
    breatheAmp: p.breatheAmp,
    lightSpeedX: p.lightSpeedX,
    lightSpeedY: p.lightSpeedY,
    lightInfluence: p.lightInfluence,
    coreTightness: p.coreTightness,
    turbulenceAmp: p.turbulenceAmp,
    rippleScale: p.rippleScale,
    heartbeatSpeed: p.heartbeatSpeed,
    radialFlowSpeed: p.radialFlowSpeed,
    cr1: c.r1, cg1: c.g1, cb1: c.b1,
    cr2: c.r2, cg2: c.g2, cb2: c.b2,
    intensity: 0,
    toolCallBurst: 0,
    voiceAmplitude: fx.voiceAmplitude ?? 0,
    voiceMode,
    voiceRadiusScale: voiceMode ? 1.08 : 1.0, // Matches Compose animateFloatAsState at steady state
  };
}

// FNV-1a 32-bit — stable, portable, easy to reproduce in Kotlin test.
function fnv1a(str) {
  let h = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, "0");
}

const CLASSIFY_DEBRIS = new Set("·:;-".split(""));
const CLASSIFY_DATARING = new Set("01<>[]{}|/\\~^".split(""));

function classifyZone(ch) {
  if (CLASSIFY_DEBRIS.has(ch)) return "glow";
  if (CLASSIFY_DATARING.has(ch)) return "ring";
  return "inside";
}

function renderFrame(fx) {
  const frame = buildFrame(fx);

  const cells = [];
  const grid = Array.from({ length: ROWS }, () => Array(COLS).fill({ ch: " ", alpha: 0 }));
  const zones = { inside: 0, glow: 0, ring: 0 };

  forEachSphereCell(frame, (col, row, ch, r, g, b, alpha) => {
    cells.push({ col, row, ch, r, g, b, alpha });
    // When two zones draw the same cell (e.g., glow overdraws inside), keep highest alpha.
    // In practice this doesn't happen — the zones are disjoint — but be defensive.
    if (alpha > grid[row][col].alpha) {
      grid[row][col] = { ch, alpha };
    }
    zones[classifyZone(ch)]++;
  });

  // Stable ordering — already emitted in (row, col) order by the loop.
  // Two checksums:
  //   structural: discrete fields only (row,col,char) — robust to Float/Double drift
  //   full: structural + color/alpha rounded to 3 decimals — catches color drift too
  const structuralLines = cells.map((c) => `${c.row},${c.col},${c.ch.charCodeAt(0)}`);
  const fullLines = cells.map(
    (c) =>
      `${c.row},${c.col},${c.ch.charCodeAt(0)},` +
      `${c.r.toFixed(3)},${c.g.toFixed(3)},${c.b.toFixed(3)},${c.alpha.toFixed(3)}`
  );
  const structural = fnv1a(structuralLines.join("\n"));
  const full = fnv1a(fullLines.join("\n"));

  return { frame, cells, grid, zones, structural, full };
}

function printSilhouette(grid) {
  for (let r = 0; r < ROWS; r++) {
    let line = "";
    for (let c = 0; c < COLS; c++) {
      line += grid[r][c].ch;
    }
    console.log("  " + line.replace(/\s+$/, ""));
  }
}

function main() {
  const args = process.argv.slice(2);
  const pick = (flag) => {
    const i = args.indexOf(flag);
    return i >= 0 ? args[i + 1] : undefined;
  };
  const filterName = pick("--name");
  const filterState = pick("--state");
  const onlyChecksum = args.includes("--checksum-only");
  const dumpTuples = args.includes("--dump-tuples");

  const fixtures = FIXTURES.filter((fx) => {
    if (filterName && fx.name !== filterName) return false;
    if (filterState && fx.state !== filterState) return false;
    return true;
  });

  if (fixtures.length === 0) {
    console.error(`No fixtures matched filters: name=${filterName} state=${filterState}`);
    process.exit(1);
  }

  for (const fx of fixtures) {
    const r = renderFrame(fx);
    const canvasAspect = (fx.widthDp / fx.heightDp).toFixed(4);

    if (onlyChecksum) {
      console.log(`${fx.name.padEnd(18)}  struct=${r.structural}  full=${r.full}  inside=${r.zones.inside} glow=${r.zones.glow} ring=${r.zones.ring}`);
      continue;
    }

    console.log(`── ${fx.name} ───────────────────────────────────────────────────────`);
    console.log(`  state=${fx.state} time=${fx.time} colorPhase=${fx.colorPhase}`);
    console.log(`  widthDp=${fx.widthDp} heightDp=${fx.heightDp} canvasAspect=${canvasAspect} charAspect=${r.frame.charAspect.toFixed(5)}`);
    if (fx.voiceAmplitude !== undefined) {
      console.log(`  voiceAmplitude=${fx.voiceAmplitude} voiceMode=${fx.voiceMode}`);
    }
    console.log(`  zones: inside=${r.zones.inside} glow=${r.zones.glow} ring=${r.zones.ring} total=${r.cells.length}`);
    console.log(`  structural checksum: ${r.structural}`);
    console.log(`  full checksum:       ${r.full}`);
    console.log(``);
    printSilhouette(r.grid);
    console.log(``);

    if (dumpTuples) {
      console.log(`  ── first 20 tuples (row,col,char,r,g,b,alpha) ──`);
      r.cells.slice(0, 20).forEach((c) => {
        console.log(`    ${c.row},${c.col},${JSON.stringify(c.ch)},${c.r.toFixed(5)},${c.g.toFixed(5)},${c.b.toFixed(5)},${c.alpha.toFixed(5)}`);
      });
      console.log(``);
    }
  }
}

main();
