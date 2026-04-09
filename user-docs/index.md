---
layout: home

hero:
  name: Hermes Relay
  text: Your AI agent, in your pocket
  tagline: Chat, control, and connect — a native Android app for Hermes Agent
  image:
    src: /logo.svg
    alt: Hermes Relay
  actions:
    - theme: brand
      text: Get Started
      link: /guide/getting-started
    - theme: alt
      text: View on GitHub
      link: https://github.com/NousResearch/hermes-agent

features:
  - icon:
      src: /icons/chat.svg
      width: 40
      height: 40
    title: Direct API Chat
    details: Stream conversations to the Hermes API Server via SSE. No relay middleman for chat.
  - icon:
      src: /icons/sessions.svg
      width: 40
      height: 40
    title: Session Management
    details: Create, switch, rename, and delete sessions. Auto-titles from your first message.
  - icon:
      src: /icons/reasoning.svg
      width: 40
      height: 40
    title: Reasoning Display
    details: Collapsible extended-thinking blocks above responses. Toggle in settings.
  - icon:
      src: /icons/personalities.svg
      width: 40
      height: 40
    title: Personalities & Command Palette
    details: Dynamic personalities from the server. Searchable command palette with 29 gateway commands, personality switching, and 90+ server skills.
  - icon:
      src: /icons/tokens.svg
      width: 40
      height: 40
    title: Token Tracking
    details: Per-message input/output counts and estimated cost, displayed inline.
  - icon:
      src: /icons/tools.svg
      width: 40
      height: 40
    title: Tool Progress
    details: Rich execution cards with type-specific icons, arguments, and duration.
  - icon:
      src: /icons/markdown.svg
      width: 40
      height: 40
    title: Markdown Rendering
    details: Full markdown with syntax-highlighted code blocks, links, and lists.
  - icon:
      src: /icons/security.svg
      width: 40
      height: 40
    title: Secure by Default
    details: EncryptedSharedPreferences for keys. Network config enforces HTTPS.
---

<div class="install-section">

## Install in 30 seconds

One line installs the server plugin into your Hermes agent. After restarting hermes, run `hermes pair` to generate a QR code the Android app can scan.

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

Then:

```bash
hermes pair
```

<p class="install-note">Installs the 14 <code>android_*</code> device control tools and the <code>hermes pair</code> CLI command. Requires hermes-agent v0.8.0+ and Python 3.11+.</p>

<p class="install-cta">
  <a href="/guide/getting-started" class="install-cta-link">Full setup guide →</a>
</p>

</div>

<style scoped>
.install-section {
  max-width: 960px;
  margin: 4rem auto 2rem;
  padding: 0 24px;
}
.install-section h2 {
  text-align: center;
  border-top: none;
  margin-top: 0;
  padding-top: 0;
  font-size: 2rem;
}
.install-section > p {
  text-align: center;
  color: var(--vp-c-text-2);
  max-width: 640px;
  margin: 1rem auto;
}
.install-note {
  font-size: 0.875rem;
  color: var(--vp-c-text-3);
}
.install-cta {
  text-align: center;
  margin-top: 2rem;
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
</style>
