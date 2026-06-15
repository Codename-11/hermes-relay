<script setup lang="ts">
import { ref } from 'vue'
import { withBase } from 'vitepress'

const installCommand = 'curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash'
const pairCommand = 'hermes-pair'
const desktopInstallPwsh = 'irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex'

// Copy-paste prompt block for AI agents (Claude, GPT, etc.) — points the
// agent at the canonical SKILL.md raw URL, no inline recipe duplication.
// Single source of truth lives at skills/devops/hermes-relay-self-setup/SKILL.md.
const agentPrompt = `You are helping me install and maintain Hermes-Relay (https://github.com/Codename-11/hermes-relay) — a native Android client + a CLI + a Python plugin for the Hermes AI agent platform.

Read the canonical setup recipe before acting:
  https://raw.githubusercontent.com/Codename-11/hermes-relay/main/skills/devops/hermes-relay-self-setup/SKILL.md

Then guide me through:
- Verifying hermes-agent is already installed (it's a prerequisite — Hermes-Relay is a plugin, not standalone)
- Running the install one-liner: \`curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash\`
- Pairing my phone via \`hermes-pair\` or \`/hermes-relay-pair\` (Android), or another machine via the \`hermes-relay\` CLI (Windows binary one-liner: \`irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex\`; macOS/Linux builds coming soon)
- Verifying with \`hermes-status\`

Always confirm before running shell commands. Never restart hermes-gateway without asking. If any step fails, consult the Troubleshooting section in the SKILL.md and ask me for the exact error.`

const copiedKey = ref<string | null>(null)
let resetTimer: ReturnType<typeof setTimeout> | null = null

async function copy(key: string, text: string) {
  try {
    await navigator.clipboard.writeText(text)
    copiedKey.value = key
    if (resetTimer) clearTimeout(resetTimer)
    resetTimer = setTimeout(() => {
      copiedKey.value = null
    }, 2000)
  } catch {
    // Clipboard API can reject on insecure contexts — silently ignore.
  }
}
</script>

<template>
  <section class="install-section">
    <h2>Get started</h2>
    <p class="install-tagline">
      Two paths. Most people start with the first — the second adds the agent's hands when you want them.
    </p>

    <!-- ── Quick path — no server install ─────────────────────────────── -->
    <div class="path-card">
      <div class="path-tag">Quick path · No server install</div>
      <h3>Just connect</h3>
      <p>
        Already running <a href="https://hermes-agent.nousresearch.com" target="_blank" rel="noopener">Hermes</a>? Then the server side is done. Install the Android app, open it, and point it at your Hermes — the wizard scans your LAN, accepts a setup QR, or takes a plain URL. Chat, voice, and Manage light up.
      </p>
      <p class="path-note">
        Chat talks to the Hermes API server; Manage and voice use the dashboard — most setups run both. Pure chat technically needs only the API. No relay plugin required.
      </p>
      <p class="install-extra-actions">
        <a :href="withBase('/guide/getting-started')" class="install-cta-link">Android setup →</a>
        <a
          href="https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay"
          class="install-extra-secondary"
          target="_blank"
          rel="noopener"
        >Google Play</a>
        <a
          href="https://github.com/Codename-11/hermes-relay/releases/latest"
          class="install-extra-secondary"
          target="_blank"
          rel="noopener"
        >Sideload APK</a>
      </p>
    </div>

    <!-- ── Power path — relay plugin ──────────────────────────────────── -->
    <div class="path-card">
      <div class="path-tag">Power path · Relay plugin</div>
      <h3>Give it hands</h3>
      <p>
        One install on your Hermes host unlocks the rest: phone control on the sideload track, terminal and relay sessions on Android, and the CLI that puts hands on your computers.
      </p>

      <div class="install-code">
        <div class="install-code-scroll">
          <pre><code>{{ installCommand }}</code></pre>
        </div>
        <button
          type="button"
          class="copy-btn"
          :class="{ copied: copiedKey === 'install' }"
          :aria-label="copiedKey === 'install' ? 'Copied' : 'Copy install command'"
          @click="copy('install', installCommand)"
        >
          <span class="copy-btn-label">{{ copiedKey === 'install' ? 'Copied!' : 'Copy' }}</span>
        </button>
      </div>

      <p class="install-then">Then mint a pairing QR / 6-char code and scan it from the app:</p>

      <div class="install-code">
        <div class="install-code-scroll">
          <pre><code>{{ pairCommand }}</code></pre>
        </div>
        <button
          type="button"
          class="copy-btn"
          :class="{ copied: copiedKey === 'pair' }"
          :aria-label="copiedKey === 'pair' ? 'Copied' : 'Copy pair command'"
          @click="copy('pair', pairCommand)"
        >
          <span class="copy-btn-label">{{ copiedKey === 'pair' ? 'Copied!' : 'Copy' }}</span>
        </button>
      </div>

      <p class="path-note">
        Installs the 18 <code>android_*</code> + 9 <code>desktop_*</code> tool surfaces, the <code>/hermes-relay-pair</code> skill, and a <code>hermes-pair</code> shell shim. Requires hermes-agent v0.8.0+ and Python 3.11+.
      </p>

      <p>
        For hands on a computer, add the CLI:
      </p>

      <div class="install-code">
        <div class="install-code-scroll">
          <pre><code>{{ desktopInstallPwsh }}</code></pre>
        </div>
        <button
          type="button"
          class="copy-btn"
          :class="{ copied: copiedKey === 'desktop-pwsh' }"
          :aria-label="copiedKey === 'desktop-pwsh' ? 'Copied' : 'Copy CLI install (pwsh)'"
          @click="copy('desktop-pwsh', desktopInstallPwsh)"
        >
          <span class="copy-btn-label">{{ copiedKey === 'desktop-pwsh' ? 'Copied!' : 'Copy' }}</span>
        </button>
      </div>

      <p class="path-note">
        Windows today — the macOS / Linux one-liner lands with those builds. Track it on <a :href="withBase('/desktop/installation')">CLI setup</a>.
      </p>

      <p class="install-extra-actions">
        <a :href="withBase('/desktop/')" class="install-cta-link">CLI setup →</a>
        <a :href="withBase('/features/phone-control-tools')" class="install-extra-secondary">Phone control tools</a>
      </p>
    </div>

    <!-- ── Bug-report card ────────────────────────────────────────────── -->
    <div class="path-card path-card--quiet">
      <h3>Found a bug? We'd love to hear about it.</h3>
      <p>
        This is an indie project and every report helps. If something feels off, broken, or just weird, open an issue — we read every one.
      </p>
      <p class="install-extra-actions">
        <a
          href="https://github.com/Codename-11/hermes-relay/issues/new"
          class="install-cta-link"
          target="_blank"
          rel="noopener"
        >Open an issue →</a>
      </p>
    </div>

    <!-- For AI Agents — copy-paste block that points an LLM at the canonical
         SKILL.md raw URL. Same prompt block lives in README.md. -->
    <div class="agent-section">
      <h3 class="agent-section-title">For AI Agents</h3>
      <p class="agent-section-tagline">
        On the power path? Have an AI assistant install + maintain the relay plugin for you. Paste this block into Claude, GPT, or any agent — it'll fetch the canonical setup recipe and walk you through verification, pairing, and troubleshooting.
      </p>

      <div class="install-code agent-prompt-code">
        <pre><code>{{ agentPrompt }}</code></pre>
        <button
          type="button"
          class="copy-btn"
          :class="{ copied: copiedKey === 'agent' }"
          :aria-label="copiedKey === 'agent' ? 'Copied' : 'Copy agent prompt'"
          @click="copy('agent', agentPrompt)"
        >
          <span class="copy-btn-label">{{ copiedKey === 'agent' ? 'Copied!' : 'Copy' }}</span>
        </button>
      </div>

      <p class="agent-section-note">
        Already have Hermes-Relay installed? The same recipe ships as a Hermes skill — invoke it from any chat with <code>/hermes-relay-self-setup</code> for re-setup, troubleshooting, or "is everything wired correctly?" checks. Single source, two delivery modes, no drift.
      </p>
    </div>
  </section>
</template>

<style scoped>
.install-section {
  max-width: 960px;
  margin: 3rem auto 1rem;
  padding: 0 24px;
}
.install-section h2 {
  text-align: center;
  border-top: none;
  margin-top: 0;
  padding-top: 0;
  font-size: 2rem;
  margin-bottom: 1rem;
}
.install-tagline,
.install-then {
  color: var(--vp-c-text-2);
  margin: 1rem 0;
}
.install-tagline {
  text-align: center;
  max-width: 640px;
  margin: 1rem auto 1.75rem;
}

/* ── Path cards — stacked, Standard first ─────────────────────────── */
.path-card {
  max-width: 720px;
  margin: 1rem auto;
  background: var(--vp-c-bg-alt);
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
  padding: 1.5rem 1.5rem 1.25rem;
}
.path-card h3 {
  margin: 0.35rem 0 0.5rem;
  font-size: 1.35rem;
  border-top: none;
  padding-top: 0;
  letter-spacing: -0.01em;
}
.path-card p {
  color: var(--vp-c-text-2);
  font-size: 0.95rem;
  line-height: 1.6;
  margin: 0.5rem 0;
}
.path-card code {
  font-size: 0.8125rem;
}
.path-tag {
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--vp-c-brand-1);
}
.path-note {
  font-size: 0.8125rem !important;
  color: var(--vp-c-text-3) !important;
}
.path-platform {
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--vp-c-text-3);
}
.path-card--quiet h3 {
  font-size: 1.05rem;
  margin-top: 0;
}

/* ── Code blocks ──────────────────────────────────────────────────── */
.install-code {
  position: relative;
  min-width: 0;
  margin: 1rem 0;
  background: var(--vp-c-bg);
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
}
/* Scrolling lives on the inner container so the absolutely-positioned copy
   button stays pinned to the visible top-right corner of .install-code,
   instead of scrolling out of view with the overflowing command text.
   (overflow-x is a fallback — commands wrap below, so it rarely engages.) */
.install-code-scroll {
  min-width: 0;
  overflow-x: auto;
  padding: 14px 58px 14px 18px;
}
/* Wrap one-liners instead of horizontal-scrolling them — a curl command on
   two lines reads better than a scrollbar hiding half the command. */
.install-code pre {
  margin: 0;
  background: transparent;
  white-space: pre-wrap;
  word-break: break-all;
}
.install-code code {
  font-family: var(--vp-font-family-mono);
  font-size: 0.875rem;
  color: var(--vp-c-text-1);
  background: transparent;
  padding: 0;
}
.copy-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  height: 26px;
  padding: 0 10px;
  border-radius: 6px;
  border: 1px solid var(--vp-c-divider);
  background: var(--vp-c-bg-alt);
  color: var(--vp-c-text-2);
  font-size: 0.75rem;
  font-family: var(--vp-font-family-base);
  cursor: pointer;
  transition: color 0.2s, border-color 0.2s, background 0.2s;
}
.copy-btn:hover {
  color: var(--vp-c-brand-1);
  border-color: var(--vp-c-brand-1);
}
.copy-btn:focus-visible {
  outline: 2px solid var(--vp-c-brand-1);
  outline-offset: 2px;
}
.copy-btn.copied {
  color: var(--vp-c-brand-1);
  border-color: var(--vp-c-brand-1);
}
.copy-btn-label {
  line-height: 1;
}

/* ── Action links ─────────────────────────────────────────────────── */
.install-extra-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 1rem !important;
}
.install-cta-link {
  display: inline-block;
  padding: 0.5rem 1.25rem;
  border-radius: 20px;
  border: 1px solid var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
  font-weight: 500;
  text-decoration: none !important;
  transition: all 0.2s;
}
.install-cta-link:hover {
  background: var(--vp-c-brand-1);
  color: var(--vp-c-white);
}
.install-extra-secondary {
  font-size: 0.85rem;
  color: var(--vp-c-text-2);
  text-decoration: none;
  border-bottom: 1px dashed var(--vp-c-divider);
  transition: color 0.2s, border-color 0.2s;
}
.install-extra-secondary:hover {
  color: var(--vp-c-brand-1);
  border-bottom-color: var(--vp-c-brand-1);
}

/* For AI Agents — sits cleanly below the path cards */
.agent-section {
  max-width: 720px;
  margin: 3rem auto 0;
  padding-top: 2rem;
  border-top: 1px solid var(--vp-c-divider);
}
.agent-section-title {
  text-align: center;
  font-size: 1.5rem;
  margin: 0 0 0.75rem;
  border-top: none;
  padding-top: 0;
}
.agent-section-tagline {
  text-align: center;
  color: var(--vp-c-text-2);
  font-size: 0.95rem;
  max-width: 620px;
  margin: 0 auto 1.25rem;
}
.agent-prompt-code {
  max-width: 720px;
  padding: 18px 58px 18px 22px;
}
.agent-prompt-code pre {
  white-space: pre-wrap;
  word-break: break-word;
}
.agent-prompt-code code {
  font-size: 0.8125rem;
  line-height: 1.55;
}
.agent-section-note {
  font-size: 0.8125rem;
  color: var(--vp-c-text-3);
  text-align: center;
  margin: 1.25rem auto 0;
  max-width: 620px;
  line-height: 1.55;
}
.agent-section-note code {
  font-size: 0.8125rem;
}
</style>
