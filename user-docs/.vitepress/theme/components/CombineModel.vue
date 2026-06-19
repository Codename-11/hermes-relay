<script setup lang="ts">
// "How the pieces combine" — the two-axis model (server Relay plugin × app
// build flavor). Each tier is ADDITIVE: it includes everything above it plus
// its own row, and names the prerequisite that unlocks it. Mirrors the cockpit
// theme used by FeatureMatrix / SurfaceCards: flat, bordered, single
// --vp-c-brand-1 accent, Space Grotesk + Space Mono, no gradients or shadows.
//
// Source of truth for the gating: app/src/main/kotlin/.../data/FeatureFlags.kt
// (BuildFlavor) for the flavor axis; the relay plugin owns every /relay channel
// (terminal, voice, notifications, media, desktop, bridge/device control).

interface Tier {
  step: string
  title: string
  /** Short prerequisite chip — what you must have for this tier. */
  requires: string
  /** Plain-language list of what this tier adds on top of the one above. */
  unlocks: string
  /** Emphasis on the highest, full-capability tier. */
  accent?: boolean
}

const tiers: Tier[] = [
  {
    step: '1',
    title: 'Vanilla Hermes',
    requires: 'Nothing on your Hermes',
    unlocks: 'Chat · Manage · standard voice — runs on unmodified upstream Hermes',
  },
  {
    step: '2',
    title: '+ Relay plugin (paired)',
    requires: 'Relay plugin on your Hermes host',
    unlocks:
      'Terminal / TUI · relay & enhanced voice · notification forwarding · media handoff · desktop tools',
  },
  {
    step: '3',
    title: '+ Sideload build',
    requires: 'Relay plugin AND the sideload APK',
    unlocks:
      'Device Control — screen reading · taps · typing · vision-driven navigation',
    accent: true,
  },
]
</script>

<template>
  <section class="cm" aria-label="How the Relay plugin and the app build flavor combine">
    <ol class="cm-list">
      <li
        v-for="t in tiers"
        :key="t.step"
        :class="['cm-tier', { 'cm-tier--accent': t.accent }]"
      >
        <div class="cm-step" aria-hidden="true">{{ t.step }}</div>
        <div class="cm-body">
          <div class="cm-head">
            <div class="cm-title">{{ t.title }}</div>
            <span class="cm-requires">{{ t.requires }}</span>
          </div>
          <p class="cm-unlocks">{{ t.unlocks }}</p>
        </div>
      </li>
    </ol>
    <p class="cm-note">
      Two independent prerequisites. The <strong>Relay plugin</strong> is
      server-side (installed on the machine running Hermes); the
      <strong>Sideload build</strong> is app-side (which APK you install).
      <strong>Device Control needs both</strong> — a sideload app with no paired
      relay still can't drive your phone, and a paired relay on the Google Play
      build has no phone-control surface.
    </p>
  </section>
</template>

<style scoped>
/* ══════════════════════════════════════════════════════════════════════
   CombineModel — additive three-tier ladder. Cockpit theme, flat + bordered.
   ══════════════════════════════════════════════════════════════════════ */

.cm {
  max-width: 960px;
  margin: 2rem auto;
  border: 1px solid var(--vp-c-divider);
  border-radius: 10px;
  background: var(--vp-c-bg-alt);
  overflow: hidden;
  font-family: var(--vp-font-family-base);
}

.cm-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.cm-tier {
  display: grid;
  grid-template-columns: 52px 1fr;
  align-items: stretch;
  border-bottom: 1px solid var(--vp-c-divider);
}
.cm-tier:last-child {
  border-bottom: 0;
}
.cm-tier--accent {
  background: var(--vp-c-brand-soft);
}

.cm-step {
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--vp-font-family-mono);
  font-size: 1rem;
  color: var(--vp-c-text-3);
  border-right: 1px solid var(--vp-c-divider);
}
.cm-tier--accent .cm-step {
  color: var(--vp-c-brand-1);
}

.cm-body {
  padding: 16px 20px;
}

.cm-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 12px;
}
.cm-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--vp-c-text-1);
  letter-spacing: -0.01em;
}
.cm-requires {
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--vp-c-text-3);
  border: 1px solid var(--vp-c-divider);
  border-radius: 999px;
  padding: 2px 9px;
  white-space: nowrap;
}
.cm-tier--accent .cm-requires {
  color: var(--vp-c-brand-1);
  border-color: var(--vp-c-brand-1);
}

.cm-unlocks {
  margin: 6px 0 0;
  font-size: 0.9rem;
  color: var(--vp-c-text-2);
  line-height: 1.5;
}

.cm-note {
  margin: 0;
  padding: 14px 20px;
  border-top: 1px solid var(--vp-c-divider);
  background: var(--vp-c-bg);
  font-size: 0.8rem;
  color: var(--vp-c-text-2);
  line-height: 1.55;
}
.cm-note strong {
  color: var(--vp-c-text-1);
}

@media (max-width: 560px) {
  .cm-tier {
    grid-template-columns: 40px 1fr;
  }
  .cm-body {
    padding: 14px 16px;
  }
  .cm-requires {
    white-space: normal;
  }
  .cm-note {
    padding: 12px 16px;
  }
}
</style>
