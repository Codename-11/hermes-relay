<script setup lang="ts">
import { ref } from 'vue'
import { withBase } from 'vitepress'

const installCommand = 'curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash'
const pairCommand = 'hermes pair'

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
    <h2>Install in 30 seconds</h2>
    <p class="install-tagline">
      One line installs the server plugin into your Hermes agent. After restarting hermes, run <code>hermes pair</code> to generate a QR code the Android app can scan.
    </p>

    <div class="install-code">
      <pre><code>{{ installCommand }}</code></pre>
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

    <p class="install-then">Then:</p>

    <div class="install-code">
      <pre><code>{{ pairCommand }}</code></pre>
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

    <p class="install-note">
      Installs the 14 <code>android_*</code> device control tools and the <code>hermes pair</code> CLI command. Requires hermes-agent v0.8.0+ and Python 3.11+.
    </p>

    <p class="install-cta">
      <a :href="withBase('/guide/getting-started')" class="install-cta-link">Full setup guide →</a>
    </p>

    <div class="install-extras">
      <div class="install-extra-card">
        <h3>Sideload the APK</h3>
        <p>
          Prefer not to use Google Play? Grab <code>app-release.apk</code> from the latest GitHub Release and install it directly. The <code>.aab</code> file is a Google Play format — make sure you download the <code>.apk</code>.
        </p>
        <p class="install-extra-actions">
          <a
            href="https://github.com/Codename-11/hermes-relay/releases/latest"
            class="install-cta-link"
            target="_blank"
            rel="noopener"
          >Download APK →</a>
          <a
            :href="withBase('/guide/getting-started') + '#sideload-apk'"
            class="install-extra-secondary"
          >Sideload guide</a>
        </p>
      </div>

      <div class="install-extra-card">
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
  text-align: center;
  color: var(--vp-c-text-2);
  max-width: 640px;
  margin: 1rem auto;
}
.install-code {
  position: relative;
  max-width: 720px;
  margin: 1rem auto;
  background: var(--vp-c-bg-alt);
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
  padding: 14px 58px 14px 18px;
  overflow-x: auto;
}
.install-code pre {
  margin: 0;
  background: transparent;
  white-space: pre;
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
  background: var(--vp-c-bg);
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
.install-note {
  font-size: 0.8125rem;
  color: var(--vp-c-text-3);
  text-align: center;
  margin: 1.25rem auto 0;
  max-width: 640px;
}
.install-note code {
  font-size: 0.8125rem;
}
.install-cta {
  text-align: center;
  margin-top: 1.75rem;
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
.install-extras {
  display: grid;
  grid-template-columns: 1fr;
  gap: 1rem;
  max-width: 720px;
  margin: 2.5rem auto 0;
}
@media (min-width: 720px) {
  .install-extras {
    grid-template-columns: 1fr 1fr;
  }
}
.install-extra-card {
  background: var(--vp-c-bg-alt);
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
  padding: 1.25rem 1.25rem 1rem;
}
.install-extra-card h3 {
  margin: 0 0 0.5rem;
  font-size: 1.05rem;
  border-top: none;
  padding-top: 0;
}
.install-extra-card p {
  color: var(--vp-c-text-2);
  font-size: 0.9rem;
  margin: 0.5rem 0;
}
.install-extra-card code {
  font-size: 0.8125rem;
}
.install-extra-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 0.9rem !important;
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
</style>
