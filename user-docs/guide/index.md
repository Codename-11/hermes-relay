# Hermes-Relay — Android

Hermes-Relay puts the [Hermes Agent](https://hermes-agent.nousresearch.com) you already run on Android. Start with the standard Dashboard/Gateway connection for Chat, sessions, Manage, sign-in, voice, and inbound files. Pair the encouraged Relay extension for Terminal/TUI, notifications, desktop tools, enhanced voice, Relay sessions, optional Device Control, and media compatibility or metadata.

<AndroidSetupPath mode="overview" />

## What the two connections do

- **Connect mobile app** adds the standard Hermes Dashboard/Gateway connection.
- **Pair new device** adds a separate, consent-scoped Relay grant.

The two QR codes are deliberately separate. You can use standard Hermes without
Relay, and pairing Relay never replaces the upstream connection.

## Connection Model

```
Phone (WS)       → Hermes dashboard (:9119)    [gateway chat with live thinking]
Phone (HTTP/SSE) → Hermes API Server (:8642)   [chat fallback, sessions, runs]
Phone (HTTP)     → Hermes dashboard (:9119)    [Manage + Voice + inbound files]
Phone (WSS/HTTP) → Relay Server (:8767)        [Bridge Core, terminal, TUI, relay voice + media enhancements]
```

Chat prefers the dashboard gateway when Manage auth is ready and falls back to
the Hermes API Server's SSE routes. Authenticated Dashboard file routes handle
ordinary inbound media. The relay server handles Bridge Core, terminal, TUI,
notification companion, relay sessions, relay-backed voice, and additive media
compatibility. Sideload builds additionally expose Android Device Control routes.

## Feature status

| Feature | Status |
|---------|--------|
| Chat (Dashboard/Gateway primary, optional API fallback) | Complete |
| Session management | Complete |
| Profiles and personalities | Complete |
| Markdown + syntax highlighting | Complete |
| Reasoning display | Complete |
| Command palette + inline autocomplete | Complete |
| QR code pairing | Complete |
| Token tracking | Complete |
| Tool progress cards (Off/Compact/Detailed) | Complete |
| In-app analytics (Stats for Nerds) | Complete |
| Animated splash screen | Complete |
| Terminal/TUI relay | Beta |
| Bridge Core | Complete |
| Device Control | Beta (sideload track) |
| Hermes Chat + Voice Output | Complete |
| Realtime Agent | Experimental |
| Connection diagnostics | Complete |

## Quick Links

- [Quick Start](/guide/quick-start) — Recommended Android + Relay setup
- [Installation & Setup](/guide/getting-started) — Builds, manual setup, and fallbacks
- [Chat Guide](/guide/chat) — Using the chat interface
- [Supervised Mode](/guide/supervised-mode) — Planned parent-controlled, profile-pinned Android interface
- [Sessions](/guide/sessions) — Managing conversations
- [Features](/features/) — All features at a glance
- [Architecture](/architecture/) — How it works under the hood

::: tip Want Hermes to work on another computer?
The [Desktop CLI](/desktop/) pairs through the same Relay grant and gives Hermes
consent-gated filesystem, terminal, process, clipboard, and screenshot tools on
Windows, macOS, or Linux. The optional management UI is Windows-only.
:::
