# Features

## Chat & Communication

| Feature | Description |
|---------|-------------|
| [Direct API Connection](/features/direct-api) | HTTP/SSE streaming to Hermes API Server |
| [Markdown Rendering](/features/markdown) | Full markdown with syntax-highlighted code blocks |
| [Reasoning Display](/features/reasoning) | Collapsible extended-thinking blocks |
| [Personalities](/features/personalities) | 8 communication styles |
| [Token Tracking](/features/tokens) | Per-message usage and cost |
| [Tool Progress](/features/tools) | Rich tool execution cards |

## Session Management

| Feature | Description |
|---------|-------------|
| Session drawer | Create, switch, rename, delete sessions |
| Auto-titles | Sessions titled from first message |
| Message history | Loads from server on session switch |
| Persistence | Last session resumes on app restart |

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
| Voice Input | Future — Android SpeechRecognizer |
| Memory Viewer | Future — View/edit agent memories |
