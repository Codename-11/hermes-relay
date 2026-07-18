<script setup lang="ts">
import { withBase } from 'vitepress'

defineProps<{ explainRelay?: boolean }>()
</script>

<template>
  <section class="track-chooser" aria-label="Choose an Android release track">

    <article class="track-card track-card--recommended">
      <div class="track-card__label">Recommended</div>
      <h3>Google Play</h3>
      <p>Fastest setup, automatic updates, and the complete everyday Hermes experience.</p>
      <ul>
        <li>Chat, voice, profiles, and Manage</li>
        <li>Terminal, media, and notifications with Relay</li>
        <li>No AccessibilityService or unattended phone control</li>
      </ul>
      <StoreBadge />
    </article>

    <article class="track-card">
      <div class="track-card__label">Power users</div>
      <h3>Sideload</h3>
      <p>The same app plus Device Control for people who want Hermes to operate the phone.</p>
      <ul>
        <li>Everything in the Google Play build</li>
        <li>Screen reading, taps, typing, and navigation</li>
        <li>Manual signed-APK installation and updates</li>
      </ul>
      <a class="track-card__action" href="https://github.com/Codename-11/hermes-relay/releases">
        Download the signed APK <span aria-hidden="true">→</span>
      </a>
    </article>

    <aside v-if="explainRelay" class="track-boundary">
      <strong>Build choice ≠ Relay choice.</strong>
      <span>
        Google Play versus Sideload controls what Android can do. The optional
        Relay plugin controls access to terminal, media, notifications, and
        device channels on your Hermes host.
      </span>
      <a :href="withBase('/guide/getting-started.html#relay-server-optional')">How Relay fits in →</a>
    </aside>
  </section>
</template>

<style scoped>
.track-chooser {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 1.5rem 0 2rem;
}

.track-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding: 22px;
  border: 1px solid var(--vp-c-divider);
  border-radius: 10px;
  background: var(--vp-c-bg-alt);
}

.track-card--recommended {
  border-color: color-mix(in srgb, var(--vp-c-brand-1) 55%, var(--vp-c-divider));
  background: color-mix(in srgb, var(--vp-c-brand-soft) 45%, var(--vp-c-bg-alt));
}

.track-card__label {
  width: fit-content;
  margin-bottom: 14px;
  font-family: var(--vp-font-family-mono);
  font-size: 0.66rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--vp-c-brand-1);
}

.track-card h3 {
  margin: 0;
  border: 0;
  font-size: 1.25rem;
}

.track-card p {
  margin: 8px 0 14px;
  color: var(--vp-c-text-2);
  line-height: 1.55;
}

.track-card ul {
  flex: 1;
  margin: 0 0 18px;
  padding-left: 1.15rem;
  color: var(--vp-c-text-2);
}

.track-card li {
  margin: 0.4rem 0;
  line-height: 1.5;
}

.track-card__action {
  display: inline-flex;
  width: fit-content;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  border: 1px solid var(--hr-line-strong);
  border-radius: 999px;
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--vp-c-text-1);
}

.track-card__action:hover,
.track-card__action:focus-visible {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.track-boundary {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 12px 18px;
  align-items: center;
  padding: 14px 16px;
  border-left: 2px solid var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-text-2);
  font-size: 0.9rem;
  line-height: 1.55;
}

.track-boundary strong {
  color: var(--vp-c-text-1);
}

.track-boundary a {
  white-space: nowrap;
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
  text-transform: uppercase;
}

@media (max-width: 720px) {
  .track-chooser {
    grid-template-columns: 1fr;
  }

  .track-boundary {
    grid-column: auto;
    grid-template-columns: 1fr;
  }
}
</style>
