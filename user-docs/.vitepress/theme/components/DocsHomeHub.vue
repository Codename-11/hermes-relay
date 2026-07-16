<script setup lang="ts">
import { withBase } from 'vitepress'

const paths = [
  {
    label: 'Android companion',
    state: 'Stable',
    title: 'Put Hermes in your pocket',
    description:
      'Set up streaming chat, voice, Manage, notifications, and the optional sideload Device Control track.',
    links: [
      { text: 'Quick start', href: withBase('/guide/quick-start.html') },
      { text: 'Installation & setup', href: withBase('/guide/getting-started.html') },
      { text: 'Release tracks', href: withBase('/guide/release-tracks.html') },
    ],
  },
  {
    label: 'CLI · remote hands',
    state: 'Experimental',
    title: 'Pair another machine',
    description:
      'Install the single binary, pair it with Hermes, and expose consent-gated filesystem, terminal, and capture tools.',
    links: [
      { text: 'Install the CLI', href: withBase('/desktop/installation.html') },
      { text: 'Pair a machine', href: withBase('/desktop/pairing.html') },
      { text: 'Commands', href: withBase('/desktop/subcommands.html') },
    ],
  },
]

const references = [
  {
    label: 'FEATURES',
    title: 'Understand a capability',
    description: 'Chat, voice, profiles, connections, tools, themes, and phone control.',
    href: withBase('/features/'),
  },
  {
    label: 'ARCHITECTURE',
    title: 'Trace the connection',
    description: 'See which Hermes, dashboard, and Relay surfaces handle each feature.',
    href: withBase('/architecture/'),
  },
  {
    label: 'REFERENCE',
    title: 'Look up the contract',
    description: 'API routes, configuration, Relay server deployment, and operational details.',
    href: withBase('/reference/api.html'),
  },
]

const commonTasks = [
  { text: 'Connect from outside your LAN', href: withBase('/guide/remote-access.html') },
  { text: 'Troubleshoot a connection', href: withBase('/guide/troubleshooting.html') },
  { text: 'Choose Google Play or sideload', href: withBase('/guide/release-tracks.html') },
  { text: 'Add the optional Relay plugin', href: withBase('/guide/getting-started.html#relay-server-optional') },
  { text: 'Check whether a connection is secure', href: withBase('/architecture/connection-security.html') },
  { text: 'Troubleshoot the CLI', href: withBase('/desktop/troubleshooting.html') },
]
</script>

<template>
  <main class="docs-hub" aria-label="Documentation paths">
    <section class="docs-hub__section" aria-labelledby="choose-surface">
      <div class="docs-hub__heading">
        <p class="docs-hub__eyebrow">CHOOSE A SURFACE</p>
        <h2 id="choose-surface">Two clients. One Hermes.</h2>
        <p>Start with the device you want to connect. Relay stays optional until you need power tools.</p>
      </div>

      <div class="surface-grid">
        <article v-for="path in paths" :key="path.label" class="surface-card">
          <header>
            <span>{{ path.label }}</span>
            <span class="surface-card__state">{{ path.state }}</span>
          </header>
          <h3>{{ path.title }}</h3>
          <p>{{ path.description }}</p>
          <nav :aria-label="`${path.label} documentation`">
            <a v-for="link in path.links" :key="link.href" :href="link.href">{{ link.text }} <span aria-hidden="true">→</span></a>
          </nav>
        </article>
      </div>
    </section>

    <section class="relay-path" aria-labelledby="relay-path-title">
      <div>
        <p class="docs-hub__eyebrow"><span class="status-dot" aria-hidden="true"></span> OPTIONAL POWER PATH</p>
        <h2 id="relay-path-title">Add Relay when you need hands.</h2>
        <p>
          Phone control, terminal sessions, remote desktop tools, notification forwarding, and paired CLI machines all begin here.
        </p>
      </div>
      <div class="relay-path__actions">
        <a class="primary-link" :href="withBase('/guide/getting-started.html#relay-server-optional')">Install the Relay plugin <span aria-hidden="true">→</span></a>
        <a :href="withBase('/reference/relay-server.html')">Relay server reference</a>
      </div>
    </section>

    <section class="docs-hub__section" aria-labelledby="find-answer">
      <div class="docs-hub__heading">
        <p class="docs-hub__eyebrow">GO DEEPER</p>
        <h2 id="find-answer">Find the level of detail you need.</h2>
      </div>

      <div class="reference-grid">
        <a v-for="item in references" :key="item.href" class="reference-card" :href="item.href">
          <span>{{ item.label }}</span>
          <h3>{{ item.title }}</h3>
          <p>{{ item.description }}</p>
          <strong aria-hidden="true">→</strong>
        </a>
      </div>
    </section>

    <section class="common-tasks" aria-labelledby="common-tasks-title">
      <div>
        <p class="docs-hub__eyebrow">COMMON TASKS</p>
        <h2 id="common-tasks-title">Jump straight to the answer.</h2>
      </div>
      <nav aria-label="Common documentation tasks">
        <a v-for="task in commonTasks" :key="task.href" :href="task.href">
          <span>{{ task.text }}</span>
          <span aria-hidden="true">→</span>
        </a>
      </nav>
    </section>
  </main>
</template>

<style scoped>
.docs-hub {
  width: min(1120px, calc(100% - 48px));
  margin: 8px auto 0;
  padding-bottom: 80px;
}

.docs-hub__section {
  padding: 56px 0;
  border-top: 1px solid var(--hr-line);
}

.docs-hub__heading {
  max-width: 660px;
  margin-bottom: 28px;
}

.docs-hub__eyebrow {
  margin: 0 0 10px;
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.docs-hub h2,
.docs-hub h3,
.docs-hub p {
  margin: 0;
}

.docs-hub h2 {
  color: var(--vp-c-text-1);
  font-size: clamp(26px, 3vw, 38px);
  line-height: 1.15;
  letter-spacing: -0.025em;
}

.docs-hub__heading > p:last-child,
.relay-path p,
.surface-card > p,
.reference-card p {
  color: var(--vp-c-text-2);
  line-height: 1.65;
}

.docs-hub__heading > p:last-child {
  margin-top: 12px;
  font-size: 16px;
}

.surface-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.surface-card,
.reference-card,
.relay-path {
  border: 1px solid var(--hr-line);
  background: var(--vp-c-bg-alt);
  border-radius: 8px;
}

.surface-card {
  display: flex;
  min-height: 330px;
  padding: 26px;
  flex-direction: column;
}

.surface-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.surface-card__state {
  padding: 4px 7px;
  border: 1px solid var(--hr-line);
  border-radius: 3px;
  color: var(--vp-c-text-3);
  letter-spacing: 0.04em;
}

.surface-card h3 {
  margin-top: 38px;
  color: var(--vp-c-text-1);
  font-size: 25px;
  letter-spacing: -0.02em;
}

.surface-card > p {
  margin-top: 12px;
}

.surface-card nav {
  display: grid;
  margin-top: auto;
  padding-top: 26px;
  gap: 0;
}

.surface-card nav a,
.common-tasks nav a {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: space-between;
  border-top: 1px solid var(--hr-line);
  color: var(--vp-c-text-1);
  font-family: var(--vp-font-family-mono);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
  text-decoration: none;
}

.surface-card nav a:hover,
.common-tasks nav a:hover {
  color: var(--vp-c-brand-1);
}

.relay-path {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(260px, 0.7fr);
  gap: 40px;
  padding: 32px;
  align-items: center;
}

.relay-path p:not(.docs-hub__eyebrow) {
  max-width: 650px;
  margin-top: 12px;
}

.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 7px;
  border-radius: 50%;
  background: var(--hr-green);
}

.relay-path__actions {
  display: grid;
  gap: 8px;
}

.relay-path__actions a {
  padding: 12px 14px;
  border: 1px solid var(--hr-line);
  border-radius: 4px;
  color: var(--vp-c-text-1);
  font-family: var(--vp-font-family-mono);
  font-size: 12px;
  font-weight: 700;
  text-align: center;
  text-decoration: none;
}

.relay-path__actions .primary-link {
  border-color: var(--vp-c-brand-2);
  background: var(--vp-c-brand-2);
  color: white;
}

.reference-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.reference-card {
  position: relative;
  min-height: 220px;
  padding: 22px;
  color: inherit;
  text-decoration: none;
  transition: border-color 160ms ease, transform 160ms ease;
}

.reference-card:hover {
  border-color: var(--hr-line-strong);
  transform: translateY(-2px);
}

.reference-card > span {
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.reference-card h3 {
  margin-top: 28px;
  color: var(--vp-c-text-1);
  font-size: 19px;
}

.reference-card p {
  margin-top: 10px;
  font-size: 14px;
}

.reference-card strong {
  position: absolute;
  right: 22px;
  bottom: 18px;
  color: var(--vp-c-brand-1);
}

.common-tasks {
  display: grid;
  grid-template-columns: minmax(240px, 0.8fr) minmax(0, 1.2fr);
  gap: 64px;
  padding: 56px 0 0;
  border-top: 1px solid var(--hr-line);
}

.common-tasks h2 {
  font-size: clamp(24px, 2.5vw, 32px);
}

.common-tasks nav {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 28px;
}

.common-tasks nav a {
  min-height: 52px;
  font-family: var(--vp-font-family-base);
  font-size: 14px;
  font-weight: 500;
  letter-spacing: 0;
}

@media (max-width: 767px) {
  .docs-hub {
    width: min(100% - 32px, 640px);
    margin-top: 0;
    padding-bottom: 56px;
  }

  .docs-hub__section {
    padding: 42px 0;
  }

  .surface-grid,
  .reference-grid,
  .relay-path,
  .common-tasks,
  .common-tasks nav {
    grid-template-columns: 1fr;
  }

  .surface-card {
    min-height: 0;
    padding: 22px;
  }

  .surface-card h3 {
    margin-top: 30px;
  }

  .relay-path {
    gap: 26px;
    padding: 24px;
  }

  .reference-card {
    min-height: 190px;
  }

  .common-tasks {
    gap: 24px;
    padding-top: 42px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .reference-card {
    transition: none;
  }
}
</style>
