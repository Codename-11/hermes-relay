# Hermes-Relay — Android

This section covers the **Android client** for [Hermes Agent](https://hermes-agent.nousresearch.com): standard API/dashboard setup, chat, Manage, voice, optional Relay pairing, terminal/TUI relay, notifications, and optional sideload Device Control.

::: tip Want the agent to have hands on your other machines too?
The Hermes-Relay CLI (Windows today; macOS / Linux coming soon) gives your Hermes agent consent-gated filesystem, terminal, and screenshot access on any machine you pair — plus a terminal escape hatch for you: **[CLI →](/desktop/)**. Both surfaces share the same relay pairing and `~/.hermes/remote-sessions.json`.
:::

Hermes-Relay is a native Android app for [Hermes Agent](https://hermes-agent.nousresearch.com). Chat with your agent through the Hermes API server, manage Skills/Cron/MCP/Profile surfaces through the dashboard, use voice, and optionally pair Relay for terminal/TUI and bridge power tools. The Google Play build ships Bridge Core only; sideload builds add AccessibilityService-backed Device Control.

## Quick Start

1. Install Hermes and run the API server/dashboard on your host.
2. Install the Android app.
3. Choose **Standard Hermes** and enter the API URL/key.
4. Add Relay pairing later only if you want Terminal, Bridge, Relay sessions, or device-control power tools.

See [Installation & Setup](/guide/getting-started) for copy/paste host commands and upstream Hermes links.

If you installed the optional Relay plugin and want to uninstall it later:

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

## Current Status — v1.0.0

| Feature | Status |
|---------|--------|
| Chat (direct API) | Complete |
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

- [Installation & Setup](/guide/getting-started) — Get the app running
- [Chat Guide](/guide/chat) — Using the chat interface
- [Sessions](/guide/sessions) — Managing conversations
- [Features](/features/) — All features at a glance
- [Architecture](/architecture/) — How it works under the hood
