# Hermes Relay — Feature Status

> Single source of truth for what's done, what's in progress, and what's next.
> Update this file as features are completed.

---

## Phase 0 — Project Setup ✓

- [x] Kotlin + Jetpack Compose project (AGP 8.13, Kotlin 2.0, Gradle 8.13)
- [x] Bottom nav scaffold — 4 tabs (Chat, Terminal, Bridge, Settings)
- [x] Material 3 + Material You dynamic theming
- [x] WSS connection manager (OkHttp, auto-reconnect, exponential backoff)
- [x] Channel multiplexer (typed envelope protocol)
- [x] Auth flow (6-char pairing code → EncryptedSharedPreferences)
- [x] GitHub Actions CI (lint → build → test → APK artifact)
- [x] GitHub Actions release workflow (tag → signed APK → GitHub Release)
- [x] Dev scripts (`scripts/dev.sh` / `scripts/dev.bat`)
- [x] Repo restructure — Android project at root, clean layout
- [x] App icon — Hermes wing-H monogram (adaptive icon)

## Phase 1 — Chat Channel ◐

### Server (Relay)
- [x] aiohttp WSS server on port 8767
- [x] Pairing code auth (10min expiry, one-time use)
- [x] Session token auth (30-day expiry)
- [x] Rate limiting (5 attempts/60s per IP)
- [x] Chat channel: proxy to Hermes WebAPI SSE
- [x] SSE → WebSocket bridge (streaming deltas, tool events)
- [x] Session management (create, list)
- [x] Config from env vars + ~/.hermes/config.yaml
- [ ] End-to-end test with running Hermes WebAPI

### App (Chat UI)
- [x] Message list with user/assistant bubbles
- [x] Streaming text display (delta updates)
- [x] Tool progress cards (collapsible, animated)
- [x] Profile selector dropdown
- [x] Session management (create, list, switch)
- [x] Input bar with send button
- [x] Connection status indicator
- [x] Error banner with dismiss
- [x] Auto-scroll on new messages
- [ ] Message persistence (survive app restart)
- [ ] Image/file attachments
- [ ] Markdown rendering in messages
- [ ] Copy message text

### Settings
- [x] Server URL configuration
- [x] Pairing code display + copy + regenerate
- [x] Connection status
- [x] Auth state display
- [x] Theme selector (auto/light/dark)
- [x] App version display
- [ ] Connection history
- [ ] Export/share pairing info

## Phase 2 — Terminal Channel ☐

- [ ] Server: PTY/tmux integration (`asyncio` + `pty`)
- [ ] Server: Terminal channel handler (attach, input, output, resize)
- [ ] App: WebView + xterm.js terminal emulator
- [ ] App: Soft keyboard toolbar (Ctrl, Tab, Esc, arrows)
- [ ] App: tmux session picker
- [ ] App: Biometric gate before terminal access
- [ ] App: Terminal resize on orientation change

## Phase 3 — Bridge Channel ☐

- [ ] Migrate upstream bridge protocol into multiplexed WSS
- [ ] Update `android_relay.py` to route through relay server
- [ ] App: Bridge status UI (connected, latency, battery)
- [ ] App: Permission management (accessibility, overlay)
- [ ] App: Activity log (recent agent commands)
- [ ] AccessibilityService integration

## Phase 4 — Security Hardening ☐

- [ ] TLS certificate setup (Let's Encrypt or pinning)
- [x] EncryptedSharedPreferences for token storage
- [ ] Biometric authentication (AndroidX Biometric)
- [ ] Token expiry and rotation
- [x] Rate limiting on auth endpoint

## Phase 5 — Polish & CI/CD ☐

- [x] GitHub Actions: CI workflow
- [x] GitHub Actions: release workflow
- [ ] ktlint integration
- [ ] Material You dynamic theming verification on device
- [ ] Proper error states and empty states
- [ ] Notification channel for agent messages
- [x] App icon and branding
- [ ] Splash screen

## Phase 6 — Future ☐

- [ ] Notification listener (agent reads phone notifications)
- [ ] Clipboard bridge
- [ ] File transfer (phone ↔ server)
- [ ] Multi-device support
- [ ] On-device model fallback
- [ ] Voice mode (TTS/STT)

---

## Legend

| Symbol | Meaning |
|--------|---------|
| ✓ | Phase complete |
| ◐ | Phase in progress |
| ☐ | Phase not started |
| [x] | Feature done |
| [ ] | Feature remaining |
