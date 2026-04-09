# What is Hermes Relay?

Hermes Relay is a native Android app for [Hermes Agent](https://hermes-agent.nousresearch.com). Chat with your agent, manage sessions, and — in future phases — control your phone via the device bridge and access a remote terminal.

## Quick Install

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

This installs the server-side plugin. Grab the Android app from [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases), then run `hermes pair` to generate a pairing QR. See [Installation & Setup](/guide/getting-started) for the full walkthrough.

## Connection Model

```
Phone (HTTP/SSE) → Hermes API Server (:8642)   [chat — direct]
Phone (WSS)      → Relay Server (:8767)          [bridge, terminal — future]
```

Chat connects directly to the Hermes API Server using the Sessions API with SSE streaming. The relay server handles bridge and terminal channels only.

## Current Status — v0.1.0

| Feature | Status |
|---------|--------|
| Chat (direct API) | Complete |
| Session management | Complete |
| Markdown + syntax highlighting | Complete |
| Reasoning display | Complete |
| Personality picker + agent name on bubbles | Complete |
| Command palette + inline autocomplete | Complete |
| QR code pairing | Complete |
| Token tracking | Complete |
| Tool progress cards (Off/Compact/Detailed) | Complete |
| In-app analytics (Stats for Nerds) | Complete |
| Animated splash screen | Complete |
| Terminal (remote shell) | Phase 2 |
| Bridge (device control) | Phase 3 |

## Quick Links

- [Installation & Setup](/guide/getting-started) — Get the app running
- [Chat Guide](/guide/chat) — Using the chat interface
- [Sessions](/guide/sessions) — Managing conversations
- [Features](/features/) — All features at a glance
- [Architecture](/architecture/) — How it works under the hood
