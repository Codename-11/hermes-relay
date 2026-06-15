<script setup lang="ts">
// Three-step "how it works" strip — sits between the sphere mark and the
// install section on the home page (slotted via theme/index.ts, since
// markdown body content always renders after the VPFeatures grid).
const steps = [
  {
    num: '01 · Run',
    title: 'Hermes on your machine',
    body: 'Any box that runs Hermes — server, desktop, laptop. Its API serves chat; its dashboard serves Manage and voice. Nothing extra to install.',
  },
  {
    num: '02 · Install',
    title: 'The app on your phone',
    body: 'Google Play or sideload — two tracks, one app. The sideload track adds full Device Control later if you want it.',
  },
  {
    num: '03 · Connect',
    title: 'Point it at your Hermes',
    body: 'The wizard scans your LAN, accepts a setup QR, or takes a plain URL. Chat, voice, and Manage light up.',
  },
]
</script>

<template>
  <section class="how-strip" aria-label="How Hermes-Relay works">
    <div class="how-strip-label">How it works</div>
    <div class="how-steps">
      <div v-for="step in steps" :key="step.num" class="how-step">
        <span class="num">{{ step.num }}</span>
        <h4>{{ step.title }}</h4>
        <p>{{ step.body }}</p>
      </div>
    </div>
    <p class="how-power-note">
      Want phone control, terminal, or hands on your computers? Add the relay plugin — the power path below.
    </p>
  </section>
</template>

<style scoped>
.how-strip {
  max-width: 960px;
  margin: 3rem auto 0;
  padding: 0 24px;
}
.how-strip-label {
  text-align: center;
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--vp-c-text-3);
  margin-bottom: 1.25rem;
}
.how-steps {
  display: grid;
  gap: 1rem;
  grid-template-columns: 1fr;
}
@media (min-width: 768px) {
  .how-steps { grid-template-columns: 1fr 1fr 1fr; }
}
.how-step {
  background: var(--vp-c-bg-alt);
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
  padding: 1.1rem 1.25rem;
}
.how-step .num {
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--vp-c-brand-1);
}
.how-step h4 {
  margin: 0.35rem 0 0.3rem;
  font-size: 1rem;
  font-weight: 600;
}
.how-step p {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.55;
  color: var(--vp-c-text-2);
}
.how-power-note {
  text-align: center;
  font-size: 0.85rem;
  color: var(--vp-c-text-3);
  margin: 1.25rem auto 0;
  max-width: 640px;
}
</style>
