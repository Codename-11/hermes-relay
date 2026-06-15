<script setup lang="ts">
// "Meet the two surfaces" — sits between the How-it-works strip and the
// Get-started path cards (slotted via theme/index.ts), so readers know what
// the app and the CLI each are before they see install commands.
import { withBase } from 'vitepress'
</script>

<template>
  <section class="surface-section" aria-label="The two surfaces">
    <div class="surface-label">The two surfaces</div>
    <div class="surface-grid">
      <div class="surface-card">
        <div class="surface-tag">The companion · Android</div>
        <h3>Your agent, in your pocket</h3>
        <p>A native app that makes your Hermes agent feel local: streaming chat, hands-free voice, and the Manage surface for skills, cron, and profiles. The quick path needs nothing installed on your Hermes — Relay pairing is an optional power-up for terminal and phone control.</p>
        <ul>
          <li>Streaming chat, multi-Connection, agent profiles, personalities</li>
          <li>Voice mode with your server's TTS/STT — or the opt-in Realtime Agent</li>
          <li>Google Play track: chat, voice, Manage, terminal/TUI, notifications, media</li>
          <li>Sideload track: full Device Control — screen reading, taps, typing — behind a blocklist, confirmations, and auto-disable</li>
        </ul>
        <a class="cta" :href="withBase('/guide/getting-started')">Install the Android app →</a>
      </div>
      <div class="surface-card">
        <div class="surface-tag">The hand · CLI · Experimental</div>
        <h3>Give your agent hands on any machine</h3>
        <p>Not another chat app. A single binary you drop on a machine — desktop, laptop, or headless box — so your Hermes agent can <em>work there</em>: filesystem, terminal, processes, clipboard, screenshots — every action consent-gated per device.</p>
        <ul>
          <li>Agent-callable tools: read, write, patch, search, run, capture</li>
          <li><code>daemon</code> mode keeps hands available with no window open</li>
          <li>Terminal escape hatch: <code>hermes-relay</code> attaches your server's own TUI when <em>you</em> want to drive</li>
          <li>Windows today · macOS / Linux coming soon — one Bun-compiled binary, SHA256-verified install</li>
        </ul>
        <a class="cta" :href="withBase('/desktop/')">Put a hand on this machine →</a>
      </div>
    </div>
  </section>
</template>

<style scoped>
.surface-section {
  max-width: 1152px;
  margin: 3rem auto 0;
  padding: 0 1.5rem;
}
.surface-label {
  text-align: center;
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--vp-c-text-3);
  margin-bottom: 1.25rem;
}
.surface-grid {
  display: grid;
  gap: 1.25rem;
  grid-template-columns: 1fr;
}
@media (min-width: 768px) {
  .surface-grid { grid-template-columns: 1fr 1fr; }
}
.surface-card {
  background: var(--vp-c-bg-alt);
  border: 1px solid var(--vp-c-divider);
  border-radius: 12px;
  padding: 1.5rem;
}
.surface-card h3 {
  margin: 0 0 .25rem;
  font-size: 1.25rem;
  border-top: none;
  padding-top: 0;
}
.surface-card .surface-tag {
  font-size: .8rem;
  font-weight: 500;
  letter-spacing: .04em;
  text-transform: uppercase;
  color: var(--vp-c-text-2);
}
.surface-card p {
  margin: .75rem 0;
  line-height: 1.55;
  color: var(--vp-c-text-2);
}
.surface-card ul {
  padding-left: 1.1rem;
  margin: .5rem 0 1rem;
  color: var(--vp-c-text-2);
}
.surface-card li { margin: .25rem 0; }
.surface-card .cta {
  display: inline-block;
  font-weight: 500;
  margin-top: .5rem;
}
</style>
