<script setup lang="ts">
import { ref, computed } from 'vue'

// ── Types ────────────────────────────────────────────────────────────

type Support = 'full' | 'limited' | 'none'

interface FeatureRow {
  /** Short, user-facing feature name. Shown in the leftmost column. */
  name: string
  /** One-line plain-language description shown under the name. */
  description: string
  /** Group label — used to render section headers in the table. */
  group: string
  /** How well the googlePlay flavor supports this feature. */
  googlePlay: Support
  /** How well the sideload flavor supports this feature. */
  sideload: Support
  /**
   * Optional per-row footnote shown when support differs between tracks
   * (or when a "limited" cell needs a one-line clarification).
   */
  note?: string
}

// ── Data — single source of truth for the matrix ────────────────────
//
// Tier mapping comes from `app/src/main/kotlin/.../data/FeatureFlags.kt`
// and the Phase 3 plan in the project vault. The user does not care
// about tier numbers — we present features instead.

const features: FeatureRow[] = [
  // Chat — both tracks
  {
    group: 'Chat & voice',
    name: 'Chat with your agent',
    description: 'Direct streaming chat over the Hermes API. No middleman.',
    googlePlay: 'full',
    sideload: 'full',
  },
  {
    group: 'Chat & voice',
    name: 'Voice mode (push-to-talk)',
    description: 'Hold a button, speak, the agent answers out loud.',
    googlePlay: 'full',
    sideload: 'full',
  },
  {
    group: 'Chat & voice',
    name: 'Sessions, personalities, slash commands',
    description: 'Full session browser, profile picker, searchable command palette.',
    googlePlay: 'full',
    sideload: 'full',
  },

  // Bridge — read-side
  {
    group: 'Bridge — read your phone',
    name: 'Read what is on your screen',
    description: 'Agent can see the active screen so it can answer "what does this say?"',
    googlePlay: 'full',
    sideload: 'full',
  },
  {
    group: 'Bridge — read your phone',
    name: 'Notification triage',
    description: 'Agent reads incoming notifications and summarizes them for you.',
    googlePlay: 'full',
    sideload: 'full',
  },
  {
    group: 'Bridge — read your phone',
    name: 'Calendar read',
    description: '"What is on my schedule today?" — read-only access to your calendar.',
    googlePlay: 'full',
    sideload: 'full',
  },

  // Bridge — write-side (the policy-sensitive stuff)
  {
    group: 'Bridge — control your phone',
    name: 'Tap, type, and swipe (with confirmation)',
    description: 'Agent can perform UI actions on your behalf.',
    googlePlay: 'limited',
    sideload: 'full',
    note: 'On Google Play, the accessibility service is read-only by design — write actions silently no-op so the build stays inside Play\u2019s policy envelope. Sideload ships the full gesture surface.',
  },
  {
    group: 'Bridge — control your phone',
    name: 'Reply to messages from voice',
    description: '"Text Sam I will be 10 min late" — fully hands-free.',
    googlePlay: 'none',
    sideload: 'full',
    note: 'Voice-routed bridge intents are sideload-only.',
  },
  {
    group: 'Bridge — control your phone',
    name: 'Vision-driven navigation',
    description: 'Agent looks at the screen and figures out what to tap on its own.',
    googlePlay: 'none',
    sideload: 'full',
    note: 'Vision-driven UI navigation requires the unrestricted accessibility surface and is sideload-only.',
  },
  {
    group: 'Bridge — control your phone',
    name: 'Workflow recording (future)',
    description: 'Show the agent something once, ask it to repeat the workflow later.',
    googlePlay: 'none',
    sideload: 'full',
  },

  // Safety — both tracks
  {
    group: 'Safety rails',
    name: 'App blocklist',
    description: 'Banking, password managers, and work email default-blocked from bridge actions.',
    googlePlay: 'full',
    sideload: 'full',
  },
  {
    group: 'Safety rails',
    name: 'Confirmation on destructive verbs',
    description: '"Send", "pay", "delete", "transfer" — always prompt before acting.',
    googlePlay: 'full',
    sideload: 'full',
  },
  {
    group: 'Safety rails',
    name: 'Auto-disable + activity log',
    description: 'Bridge turns itself off when idle. Every action is logged with a thumbnail.',
    googlePlay: 'full',
    sideload: 'full',
  },

  // Distribution — practical differences users will care about
  {
    group: 'Install & updates',
    name: 'One-tap install',
    description: 'Install from the Play Store with no special permissions.',
    googlePlay: 'full',
    sideload: 'none',
    note: 'Sideload requires enabling "Install unknown apps" for your browser the first time.',
  },
  {
    group: 'Install & updates',
    name: 'Automatic updates',
    description: 'Get new versions without thinking about it.',
    googlePlay: 'full',
    sideload: 'limited',
    note: 'Sideload updates are a manual download from GitHub Releases.',
  },
]

// ── View state ──────────────────────────────────────────────────────

const grouped = computed(() => {
  const map = new Map<string, FeatureRow[]>()
  for (const row of features) {
    const arr = map.get(row.group) ?? []
    arr.push(row)
    map.set(row.group, arr)
  }
  return Array.from(map.entries())
})

const activeMobileTrack = ref<'googlePlay' | 'sideload'>('googlePlay')

function setMobileTrack(t: 'googlePlay' | 'sideload') {
  activeMobileTrack.value = t
}

function supportLabel(s: Support): string {
  switch (s) {
    case 'full': return 'Included'
    case 'limited': return 'Limited'
    case 'none': return 'Not in this track'
  }
}
</script>

<template>
  <section class="fm" aria-label="Feature comparison between Google Play and Sideload tracks">
    <!-- Header strip — purely visual, repeats the column meaning that the
         table itself encodes semantically. -->
    <header class="fm-header">
      <div class="fm-header-row">
        <div class="fm-header-feature">Feature</div>
        <div class="fm-header-track fm-header-track--gp">
          <span class="fm-track-name">Google Play</span>
          <span class="fm-track-sub">Easy install. Conservative scope.</span>
        </div>
        <div class="fm-header-track fm-header-track--sl">
          <span class="fm-track-name">Sideload</span>
          <span class="fm-track-sub">Manual install. Full feature set.</span>
        </div>
      </div>
    </header>

    <!-- Mobile track switcher — only visible below 720px via CSS. -->
    <div class="fm-mobile-tabs" role="tablist" aria-label="Choose track to display">
      <button
        type="button"
        role="tab"
        :aria-selected="activeMobileTrack === 'googlePlay'"
        :class="['fm-tab', { 'fm-tab--active': activeMobileTrack === 'googlePlay' }]"
        @click="setMobileTrack('googlePlay')"
      >
        Google Play
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="activeMobileTrack === 'sideload'"
        :class="['fm-tab', { 'fm-tab--active': activeMobileTrack === 'sideload' }]"
        @click="setMobileTrack('sideload')"
      >
        Sideload
      </button>
    </div>

    <!-- Semantic table — accessible to screen readers regardless of layout. -->
    <table class="fm-table">
      <caption class="fm-sr-only">
        Comparison of features available in the Google Play and Sideload release
        tracks of Hermes-Relay.
      </caption>
      <thead>
        <tr>
          <th scope="col" class="fm-th-feature">Feature</th>
          <th scope="col" class="fm-th-track fm-th-track--gp">Google Play</th>
          <th scope="col" class="fm-th-track fm-th-track--sl">Sideload</th>
        </tr>
      </thead>

      <template v-for="[group, rows] in grouped" :key="group">
        <tbody class="fm-tbody">
          <tr class="fm-group-row">
            <th scope="rowgroup" colspan="3" class="fm-group-th">{{ group }}</th>
          </tr>
          <tr
            v-for="row in rows"
            :key="row.name"
            class="fm-row"
          >
            <th scope="row" class="fm-cell-feature">
              <div class="fm-feature-name">{{ row.name }}</div>
              <div class="fm-feature-desc">{{ row.description }}</div>
              <div v-if="row.note" class="fm-feature-note">{{ row.note }}</div>
            </th>
            <td
              :class="[
                'fm-cell-track',
                'fm-cell-track--gp',
                `fm-cell--${row.googlePlay}`,
                { 'fm-cell--mobile-hidden': activeMobileTrack !== 'googlePlay' },
              ]"
              :aria-label="`Google Play: ${supportLabel(row.googlePlay)}`"
            >
              <component :is="iconFor(row.googlePlay)" />
              <span class="fm-cell-label">{{ supportLabel(row.googlePlay) }}</span>
            </td>
            <td
              :class="[
                'fm-cell-track',
                'fm-cell-track--sl',
                `fm-cell--${row.sideload}`,
                { 'fm-cell--mobile-hidden': activeMobileTrack !== 'sideload' },
              ]"
              :aria-label="`Sideload: ${supportLabel(row.sideload)}`"
            >
              <component :is="iconFor(row.sideload)" />
              <span class="fm-cell-label">{{ supportLabel(row.sideload) }}</span>
            </td>
          </tr>
        </tbody>
      </template>
    </table>

    <p class="fm-footnote">
      Both tracks build from the same Kotlin source tree. Tier 3, 4, and 6 features
      are <em>compiled out</em> of the Google Play APK at build time via Gradle product
      flavors — they are not present in the binary, not just hidden behind a switch.
    </p>
  </section>
</template>

<script lang="ts">
import { h } from 'vue'

// Inline SVG icon components — keeps the file dependency-free.
// Color is inherited via `currentColor` from the cell's CSS class.

const Check = {
  render() {
    return h(
      'svg',
      {
        width: 18,
        height: 18,
        viewBox: '0 0 24 24',
        fill: 'none',
        stroke: 'currentColor',
        'stroke-width': 2.5,
        'stroke-linecap': 'round',
        'stroke-linejoin': 'round',
        'aria-hidden': 'true',
      },
      [h('polyline', { points: '20 6 9 17 4 12' })]
    )
  },
}

const Half = {
  render() {
    return h(
      'svg',
      {
        width: 18,
        height: 18,
        viewBox: '0 0 24 24',
        fill: 'none',
        stroke: 'currentColor',
        'stroke-width': 2,
        'stroke-linecap': 'round',
        'stroke-linejoin': 'round',
        'aria-hidden': 'true',
      },
      [
        h('circle', { cx: 12, cy: 12, r: 9 }),
        h('path', { d: 'M12 3 a9 9 0 0 1 0 18 z', fill: 'currentColor', stroke: 'none' }),
      ]
    )
  },
}

const Dash = {
  render() {
    return h(
      'svg',
      {
        width: 18,
        height: 18,
        viewBox: '0 0 24 24',
        fill: 'none',
        stroke: 'currentColor',
        'stroke-width': 2.5,
        'stroke-linecap': 'round',
        'aria-hidden': 'true',
      },
      [h('line', { x1: 6, y1: 12, x2: 18, y2: 12 })]
    )
  },
}

export function iconFor(s: 'full' | 'limited' | 'none') {
  if (s === 'full') return Check
  if (s === 'limited') return Half
  return Dash
}

export default {}
</script>

<style scoped>
/* ══════════════════════════════════════════════════════════════════════
   FeatureMatrix — flat, border-separated, single accent.
   Matches the Nothing-inspired theme used by HermesFlow / InstallSection /
   HeroDemo: Space Grotesk + Space Mono, --vp-c-brand-1 purple accent, no
   gradients, no shadows, no blur.
   ══════════════════════════════════════════════════════════════════════ */

.fm {
  max-width: 960px;
  margin: 2rem auto;
  border: 1px solid var(--vp-c-divider);
  border-radius: 10px;
  background: var(--vp-c-bg-alt);
  overflow: hidden;
  font-family: var(--vp-font-family-base);
}

/* ── Header strip ──────────────────────────────────────────────────── */

.fm-header {
  border-bottom: 1px solid var(--vp-c-divider);
  background: var(--vp-c-bg);
}
.fm-header-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr;
  align-items: stretch;
}
.fm-header-feature {
  display: flex;
  align-items: center;
  padding: 18px 20px;
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--vp-c-text-3);
}
.fm-header-track {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 16px 20px;
  border-left: 1px solid var(--vp-c-divider);
}
.fm-header-track--sl {
  background: var(--vp-c-brand-soft);
}
.fm-track-name {
  font-size: 1rem;
  font-weight: 600;
  color: var(--vp-c-text-1);
  letter-spacing: -0.01em;
}
.fm-header-track--sl .fm-track-name {
  color: var(--vp-c-brand-1);
}
.fm-track-sub {
  font-size: 0.78rem;
  color: var(--vp-c-text-2);
  margin-top: 2px;
}

/* ── Mobile tab switcher (hidden on desktop) ───────────────────────── */

.fm-mobile-tabs {
  display: none;
  border-bottom: 1px solid var(--vp-c-divider);
  background: var(--vp-c-bg);
}
.fm-tab {
  flex: 1;
  padding: 14px 16px;
  background: transparent;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--vp-c-text-2);
  font-family: var(--vp-font-family-base);
  font-size: 0.9rem;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.2s, border-color 0.2s;
}
.fm-tab:hover {
  color: var(--vp-c-text-1);
}
.fm-tab--active {
  color: var(--vp-c-brand-1);
  border-bottom-color: var(--vp-c-brand-1);
}
.fm-tab:focus-visible {
  outline: 2px solid var(--vp-c-brand-1);
  outline-offset: -2px;
}

/* ── Semantic table ─────────────────────────────────────────────────── */

.fm-table {
  width: 100%;
  border-collapse: collapse;
  margin: 0;
  font-size: 0.92rem;
}
.fm-table thead {
  /* Visually hidden — the .fm-header strip above carries the design. The
     <thead> is here for screen-reader column-association only. */
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  border: 0;
}

.fm-tbody {
  border-top: 1px solid var(--vp-c-divider);
}
.fm-tbody:first-of-type {
  border-top: 0;
}

.fm-group-row {
  background: var(--vp-c-bg);
}
.fm-group-th {
  text-align: left;
  padding: 14px 20px 8px;
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--vp-c-text-3);
  font-weight: 400;
  border-bottom: 1px solid var(--vp-c-divider);
}

.fm-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr;
  align-items: stretch;
  border-bottom: 1px solid var(--vp-c-divider);
}
.fm-row:last-child {
  border-bottom: 0;
}
.fm-row:hover {
  background: var(--vp-c-bg-soft);
}

.fm-cell-feature {
  text-align: left;
  padding: 16px 20px;
  font-weight: 400;
}
.fm-feature-name {
  font-size: 0.95rem;
  font-weight: 500;
  color: var(--vp-c-text-1);
  letter-spacing: -0.005em;
}
.fm-feature-desc {
  font-size: 0.82rem;
  color: var(--vp-c-text-2);
  margin-top: 4px;
  line-height: 1.45;
}
.fm-feature-note {
  font-size: 0.76rem;
  color: var(--vp-c-text-3);
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--vp-c-divider);
  font-style: italic;
  line-height: 1.5;
}

.fm-cell-track {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 20px;
  border-left: 1px solid var(--vp-c-divider);
  font-family: var(--vp-font-family-mono);
  font-size: 0.74rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.fm-cell-track--sl {
  background: rgba(124, 58, 237, 0.04);
}

/* Support states — color, opacity, weight */
.fm-cell--full {
  color: var(--vp-c-brand-1);
}
.fm-cell--limited {
  color: var(--vp-c-text-2);
}
.fm-cell--limited .fm-cell-label::after {
  content: ' \2022 see note';
  color: var(--vp-c-text-3);
  text-transform: none;
  font-size: 0.7rem;
  letter-spacing: 0;
  margin-left: 2px;
}
.fm-cell--none {
  color: var(--vp-c-text-3);
  opacity: 0.6;
}

/* ── Footnote ──────────────────────────────────────────────────────── */

.fm-footnote {
  margin: 0;
  padding: 14px 20px;
  border-top: 1px solid var(--vp-c-divider);
  background: var(--vp-c-bg);
  font-size: 0.78rem;
  color: var(--vp-c-text-2);
  line-height: 1.55;
}
.fm-footnote em {
  font-style: italic;
  color: var(--vp-c-text-1);
}

/* ── Screen-reader-only utility ────────────────────────────────────── */

.fm-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

/* ══════════════════════════════════════════════════════════════════════
   Responsive — collapse to a single visible track below 720px.
   The semantic <table> rows always stay in place; we just hide the
   non-active track column via display:none on mobile.
   ══════════════════════════════════════════════════════════════════════ */

@media (max-width: 720px) {
  .fm {
    margin: 1.5rem 0;
    border-radius: 8px;
  }
  .fm-header {
    display: none;
  }
  .fm-mobile-tabs {
    display: flex;
  }
  .fm-row {
    grid-template-columns: 1.4fr 1fr;
  }
  .fm-cell-feature {
    padding: 14px 16px;
  }
  .fm-cell-track {
    padding: 14px 16px;
  }
  .fm-cell--mobile-hidden {
    display: none;
  }
  .fm-group-th {
    padding: 12px 16px 6px;
  }
  .fm-feature-name {
    font-size: 0.92rem;
  }
  .fm-feature-desc {
    font-size: 0.8rem;
  }
  .fm-footnote {
    padding: 12px 16px;
  }
}

@media (max-width: 480px) {
  .fm-cell-label {
    /* On very narrow screens we lean on the icon + aria-label and hide
       the redundant text label so the row stays one line. */
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
  }
  .fm-cell-track {
    justify-content: center;
  }
  .fm-cell--limited .fm-cell-label::after {
    /* Text "see note" can also drop on narrow screens; the dashed border
       under the description still flags that there's more context. */
    display: none;
  }
}
</style>
