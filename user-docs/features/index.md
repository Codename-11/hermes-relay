# Features

## Chat & Communication

| Feature | Description |
|---------|-------------|
| [Direct API Connection](/features/direct-api) | HTTP/SSE streaming to Hermes API Server |
| [Voice Mode](/features/voice) | Real-time voice conversation — sphere listens, agent speaks back via your server's configured TTS/STT providers |
| [Markdown Rendering](/features/markdown) | Full markdown with syntax-highlighted code blocks |
| [Reasoning Display](/features/reasoning) | Collapsible extended-thinking blocks |
| [Personalities](/features/personalities) | Dynamic from `GET /api/config` — picker, agent name on bubbles |
| [Command Palette](/guide/chat#command-palette) | Searchable command browser — 29 gateway commands, personalities, 90+ skills |
| [Slash Commands](/guide/chat#inline-autocomplete) | Inline autocomplete as you type `/` |
| [QR Code Pairing](/guide/getting-started#qr-code-pairing-recommended) | Scan `hermes-pair` QR to auto-configure connection |
| [Token Tracking](/features/tokens) | Per-message usage and cost |
| [Tool Progress](/features/tools) | Configurable display — Off, Compact, or Detailed |

## Session Management

| Feature | Description |
|---------|-------------|
| Session drawer | Create, switch, rename, delete sessions |
| Auto-titles | Sessions titled from first message |
| Message history | Loads from server on session switch |
| Persistence | Last session resumes on app restart |

## Analytics

| Feature | Description |
|---------|-------------|
| Stats for Nerds | TTFT, completion times, token usage, health latency, stream rates |
| Canvas bar charts | Purple gradient charts in Settings |

## UX Polish

| Feature | Description |
|---------|-------------|
| Animated splash screen | Scale + overshoot + fade animation, hold-while-loading |
| Chat empty state | Logo + suggestion chips |
| Animated streaming dots | Pulsing 3-dot indicator during streaming |
| Haptic feedback | On send, copy, stream complete, error |
| App context prompt | Toggleable system message for mobile context |

## Security

| Feature | Description |
|---------|-------------|
| EncryptedSharedPreferences | API keys encrypted at rest (AES-256-GCM) |
| Network security config | HTTPS enforced, cleartext only for localhost |
| Bearer token auth | Optional API key authentication |

## Coming Soon

| Feature | Phase |
|---------|-------|
| Remote Terminal | Phase 2 — xterm.js + tmux |
| Device Bridge | Phase 3 — AccessibilityService control |
| Push Notifications | Future — Agent-initiated alerts |
| Memory Viewer | Future — View/edit agent memories |
