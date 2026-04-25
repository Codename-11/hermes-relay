---
layout: home

hero:
  name: Hermes-Relay
  text: One Hermes agent. Two ways to use it.
  tagline: An Android remote-control app for your phone, plus a desktop CLI that lets you use a server-deployed Hermes from your laptop as if it were running locally.
  actions:
    - theme: brand
      text: Use Hermes from my desktop
      link: /desktop/
    - theme: alt
      text: Control my phone with Hermes
      link: /guide/getting-started

features:
  - icon:
      src: /icons/chat.svg
      width: 40
      height: 40
    title: Two surfaces, one pair
    details: Pair once. Both the Android app and the desktop CLI share `~/.hermes/remote-sessions.json` and the same WSS relay — sign in on either, both light up.
  - icon:
      src: /icons/sessions.svg
      width: 40
      height: 40
    title: Server-deployed Hermes, native feel
    details: The agent brain (LLM, tools, memory, sessions) lives on your Hermes host. The desktop CLI gives you the same shell, the same TUI, the same `/paste` flow — over WSS, no Python on your laptop.
  - icon:
      src: /icons/reasoning.svg
      width: 40
      height: 40
    title: Native paste in your shell
    details: "`Win+Shift+S` → `Ctrl+A v` inside `hermes-relay shell` ships the screenshot to the server inbox and types `/paste` into the TUI. Identical UX to a local Hermes install."
  - icon:
      src: /icons/personalities.svg
      width: 40
      height: 40
    title: Phone control (Android)
    details: The agent reads your screen and acts on it — tap, type, swipe, screenshots, clipboard, media, notifications. Voice mode, multi-Connection, agent profiles, full safety rails.
  - icon:
      src: /icons/tokens.svg
      width: 40
      height: 40
    title: Local tool routing (Desktop)
    details: The remote agent can read, write, search, run shell commands, capture screenshots, and read your clipboard — on your machine — through the same relay it uses for chat. Consent-gated.
  - icon:
      src: /icons/tools.svg
      width: 40
      height: 40
    title: Multi-endpoint pairing
    details: One QR carries LAN + Tailscale + public URLs. The client races them in priority order on every connect and on network change, so the same pair just works whether you're at home, on the train, or behind your VPN.
  - icon:
      src: /icons/markdown.svg
      width: 40
      height: 40
    title: Streaming chat + structured tools
    details: Direct SSE to the Hermes API server with markdown rendering, syntax-highlighted code, reasoning blocks, tool-progress cards, and a 29-command searchable palette.
  - icon:
      src: /icons/security.svg
      width: 40
      height: 40
    title: Secure by default
    details: QR pairing, session tokens (Android Keystore / mode-0600 file), TOFU cert pinning, per-channel grants, user-chosen TTLs, revocable from any client.
---

<style>
:root {
  --hr-card-bg: var(--vp-c-bg-soft);
  --hr-card-border: var(--vp-c-divider);
}
.dark {
  --hr-card-bg: var(--vp-c-bg-alt);
}
.surface-grid {
  display: grid;
  gap: 1.25rem;
  grid-template-columns: 1fr;
  margin: 2rem auto 0;
  max-width: 1152px;
  padding: 0 1.5rem;
}
@media (min-width: 768px) {
  .surface-grid { grid-template-columns: 1fr 1fr; }
}
.surface-card {
  background: var(--hr-card-bg);
  border: 1px solid var(--hr-card-border);
  border-radius: 12px;
  padding: 1.5rem;
}
.surface-card h3 {
  margin: 0 0 .25rem;
  font-size: 1.25rem;
}
.surface-card .surface-tag {
  font-size: .8rem;
  font-weight: 500;
  letter-spacing: .04em;
  text-transform: uppercase;
  color: var(--vp-c-text-2);
}
.surface-card p { margin: .75rem 0; line-height: 1.55; }
.surface-card ul { padding-left: 1.1rem; margin: .5rem 0 1rem; }
.surface-card li { margin: .25rem 0; }
.surface-card .cta {
  display: inline-block;
  font-weight: 500;
  margin-top: .5rem;
}
</style>

<div class="surface-grid">
  <div class="surface-card">
    <div class="surface-tag">Surface 1</div>
    <h3>Android — phone control</h3>
    <p>Native app. Talks directly to the Hermes API server for chat, plus a WSS relay for voice, bridge (the agent reads your screen and acts on it), and notification companion.</p>
    <ul>
      <li>Streaming chat, multi-Connection, agent profiles, personalities</li>
      <li>Voice mode with the morphing sphere + your server's TTS/STT</li>
      <li>Screen reading, gestures, clipboard, media control, macros</li>
      <li>Per-app blocklist, destructive-verb confirmation, auto-disable</li>
    </ul>
    <a class="cta" href="./guide/getting-started.html">Install the Android app →</a>
  </div>
  <div class="surface-card">
    <div class="surface-tag">Surface 2 · Experimental</div>
    <h3>Desktop CLI — terminal</h3>
    <p>One binary, no Node required. Pipes the full Hermes TUI over a PTY, or streams structured chat events for scripting. The remote agent can also reach back through the relay and run tools on <em>your</em> machine.</p>
    <ul>
      <li><code>shell</code> mode — full Hermes CLI verbatim, in tmux on the server</li>
      <li><code>chat</code> mode — REPL or one-shot, JSON event stream for scripting</li>
      <li>In-shell <code>Ctrl+A v</code> paste · multi-monitor screenshots · editor tool</li>
      <li>Daemon mode keeps tools advertised even when no shell is open</li>
    </ul>
    <a class="cta" href="./desktop/">Use Hermes from my desktop →</a>
  </div>
</div>
