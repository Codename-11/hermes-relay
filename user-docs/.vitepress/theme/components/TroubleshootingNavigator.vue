<template>
  <nav class="symptom-nav" aria-labelledby="symptom-nav-title">
    <header>
      <span>Start with the symptom</span>
      <h2 id="symptom-nav-title">What do you see?</h2>
    </header>
    <div class="symptom-nav__grid">
      <a href="#cannot-connect"><strong>Red dot or no connection</strong><span>Check routes, host binding, and firewall.</span></a>
      <a href="#messages-not-streaming"><strong>Chat does not stream</strong><span>Check authentication and server logs.</span></a>
      <a href="#no-internet"><strong>No internet banner</strong><span>Confirm Android network state.</span></a>
      <a href="#session-history"><strong>Sessions are missing</strong><span>Reconnect before loading history.</span></a>
      <a href="#app-crashes"><strong>App crashes on startup</strong><span>Reset local connection state safely.</span></a>
      <a href="#tailscale-checklist"><strong>Works at home, not away</strong><span>Verify the saved Tailscale route.</span></a>
    </div>
  </nav>
</template>

<style scoped>
.symptom-nav {
  margin: 1.5rem 0 2rem;
  border: 1px solid var(--vp-c-divider);
  border-radius: 10px;
  overflow: hidden;
  background: var(--vp-c-bg-alt);
}

.symptom-nav header {
  padding: 18px 20px;
  border-bottom: 1px solid var(--vp-c-divider);
}

.symptom-nav header span {
  font-family: var(--vp-font-family-mono);
  font-size: 0.66rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--vp-c-brand-1);
}

.symptom-nav h2 {
  margin: 4px 0 0;
  padding: 0;
  border: 0;
  font-size: 1.2rem;
}

.symptom-nav__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.symptom-nav__grid a {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
  padding: 15px 18px;
  border-right: 1px solid var(--vp-c-divider);
  border-bottom: 1px solid var(--vp-c-divider);
  color: var(--vp-c-text-1);
  text-decoration: none;
}

.symptom-nav__grid a:nth-child(2n) {
  border-right: 0;
}

.symptom-nav__grid a:nth-last-child(-n + 2) {
  border-bottom: 0;
}

.symptom-nav__grid a:hover,
.symptom-nav__grid a:focus-visible {
  background: var(--vp-c-brand-soft);
}

.symptom-nav__grid strong {
  font-size: 0.9rem;
}

.symptom-nav__grid span {
  color: var(--vp-c-text-2);
  font-size: 0.78rem;
  line-height: 1.45;
}

@media (max-width: 600px) {
  .symptom-nav__grid {
    grid-template-columns: 1fr;
  }

  .symptom-nav__grid a,
  .symptom-nav__grid a:nth-child(2n),
  .symptom-nav__grid a:nth-last-child(-n + 2) {
    border-right: 0;
    border-bottom: 1px solid var(--vp-c-divider);
  }

  .symptom-nav__grid a:last-child {
    border-bottom: 0;
  }
}
</style>
