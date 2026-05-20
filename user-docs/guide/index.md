# Hermes-Relay — Android

This section covers the **Android client** for [Hermes Agent](https://hermes-agent.nousresearch.com): chat, voice, relay pairing, terminal/TUI relay, notifications, and optional sideload Device Control.

::: tip Looking for the desktop CLI?
The desktop terminal client (Windows / macOS / Linux) lives in its own section: **[Desktop CLI →](/desktop/)**. Both clients pair against the same Hermes-Relay server and share `~/.hermes/remote-sessions.json` — pair once from either, both work.
:::

Hermes-Relay is a native Android app for [Hermes Agent](https://hermes-agent.nousresearch.com). Chat with your agent, manage sessions, use voice, pair to the relay, and access remote terminal/TUI surfaces. The Google Play build ships Bridge Core only; sideload builds add AccessibilityService-backed Device Control.

## Quick Install

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

This installs the server-side plugin. One command, full features — sessions browser, conversation history, personality picker, command palette, memory management, relay WSS for terminal/TUI and Bridge Core, sideload Device Control routes, and relay HTTP voice routes all work out of the box on any standard `hermes-agent` install. Grab the Android app from [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases), then either type `/hermes-relay-pair` in any Hermes chat surface or run `hermes-pair` from a shell to generate a pairing QR. See [Installation & Setup](/guide/getting-started) for the full walkthrough.

To uninstall later:

```bash
bash ~/.hermes/hermes-relay/uninstall.sh
```

Or via curl if the clone is already gone:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/uninstall.sh | bash
```

The uninstaller is idempotent and never touches state shared with other Hermes tools. Flags: `--dry-run`, `--keep-clone`, `--remove-secret`.

## Connection Model

```
Phone (HTTP/SSE) → Hermes API Server (:8642)   [chat — direct]
Phone (WSS/HTTP) → Relay Server (:8767)        [Bridge Core, terminal, TUI, media, voice]
```

Chat connects directly to the Hermes API Server using the Sessions API with SSE streaming. The relay server handles Bridge Core, terminal, TUI, media, notification companion, sessions, and `/voice/*` routes. Sideload builds additionally expose Android Device Control routes.

## Current Status — v0.4.0

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
| Terminal/TUI relay | Beta |
| Bridge Core | Complete |
| Device Control | Beta (sideload track) |

## Quick Links

- [Installation & Setup](/guide/getting-started) — Get the app running
- [Chat Guide](/guide/chat) — Using the chat interface
- [Sessions](/guide/sessions) — Managing conversations
- [Features](/features/) — All features at a glance
- [Architecture](/architecture/) — How it works under the hood
