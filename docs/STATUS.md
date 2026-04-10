# Hermes-Relay — Feature Status

> Single source of truth for what's done, what's in progress, and what's next.
> Update this file as features are completed.
> **Version:** v0.1.0 | **Updated:** 2026-04-07

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
- [x] Animated splash screen (AnimatedVectorDrawable, scale + overshoot + fade, hold-while-loading)
- [x] Centralized versioning (libs.versions.toml — appVersionName, appVersionCode)
- [x] Release signing config (env vars + local.properties fallback)
- [x] Package rename (com.hermesandroid.companion → com.hermesandroid.relay)

## Phase 1 — Chat Channel ✓

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
- [x] Animated streaming dots (pulsing 3-dot animation)
- [x] Tool progress cards (collapsible, animated, auto-expand/collapse)
- [x] Tool display configuration (Off/Compact/Detailed modes)
- [x] Compact tool call inline component
- [x] Personality picker with agent name on chat bubbles (from config.agent.personalities)
- [x] Command palette (searchable bottom sheet) + inline autocomplete
- [x] 29 gateway commands + dynamic personalities + server skills from GET /api/skills
- [x] QR code pairing (hermes-pair skill)
- [x] Session management (create, list, switch, rename, delete)
- [x] Session drawer (hamburger menu, title/timestamp/count)
- [x] Auto-titles from first message
- [x] Input bar with send button, stop button, 4096 char limit
- [x] Connection status indicator
- [x] Error banner with dismiss + retry
- [x] Auto-scroll on new messages
- [x] Chat empty state (ASCII morphing sphere + "Start a conversation" + suggestion chips)
- [x] ASCII morphing sphere animation (3D character sphere, green-purple color pulse, Compose Canvas)
- [x] Ambient mode (fullscreen sphere, toggle in chat header)
- [x] Animation behind messages (15% opacity sphere behind message list, toggleable)
- [x] Animation settings (ASCII sphere toggle + behind messages toggle in Settings > Appearance)
- [x] Markdown rendering (full markdown, syntax-highlighted code blocks)
- [x] Copy message text (long-press)
- [x] Reasoning/thinking display (collapsible blocks)
- [x] Token & cost tracking (per-message)
- [x] Haptic feedback (send, copy, stream complete, error)
- [x] Responsive layout (300dp/480dp/600dp breakpoints)
- [x] Accessibility (content descriptions on all interactive elements)
- [x] Offline detection (ConnectivityObserver, offline banner)
- [x] App context prompt (toggleable system message)
- [x] File attachments (images, documents, any file type via base64)
- [x] Client-side message queuing (send while streaming)
- [x] Configurable limits (attachment size, message length)
- [x] Parse tool annotations (experimental, Sessions mode only)
- [x] Empty bubble filtering (blank messages hidden)
- [ ] Message persistence (survive app restart)

### App (Analytics)
- [x] AppAnalytics singleton (in-memory, no persistence)
- [x] TTFT tracking (time to first token)
- [x] Completion time tracking
- [x] Token usage per message
- [x] Health check latency
- [x] Stream success/failure rates
- [x] Stats for Nerds UI (Canvas bar charts, purple gradient)

### Settings
- [x] API Server URL + key configuration
- [x] Relay Server URL + pairing code
- [x] Connection status + health check
- [x] Theme selector (auto/light/dark)
- [x] Dynamic colors toggle
- [x] Show reasoning toggle
- [x] Show token usage toggle
- [x] App context prompt toggle
- [x] Tool call display mode (Off/Compact/Detailed)
- [x] Stats for Nerds section
- [x] App version display (dynamic from BuildConfig)
- [x] About section (logo, links, credits)
- [x] What's New dialog (auto-shows on version change)
- [x] Data backup/restore/reset
- [x] Onboarding flow (API URL, key, relay URL)
- [x] Feature gating with Developer Options (tap version 7x to unlock)
- [x] Relay/pairing settings gated behind Developer Options in release builds
- [ ] Connection history
- [ ] Export/share pairing info

### Release Infrastructure
- [x] 3-job release workflow (validate → CI → release)
- [x] Version read from libs.versions.toml
- [x] CHANGELOG.md (Keep a Changelog format)
- [x] RELEASE_NOTES.md (GitHub Release body)
- [x] PR template with Android checklist
- [x] Dependabot (Gradle + Actions, weekly, grouped)
- [x] Claude automation (issue triage, fix, code review)

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

## Phase 5 — Polish & CI/CD ◐

- [x] GitHub Actions: CI workflow
- [x] GitHub Actions: release workflow
- [x] Claude automation workflows
- [x] Dependabot auto-merge
- [ ] ktlint integration
- [ ] Material You dynamic theming verification on device
- [x] Proper error states and empty states
- [ ] Notification channel for agent messages
- [x] App icon and branding (75% safe zone scaling)
- [x] Splash screen (animated)
- [x] VitePress documentation site
- [x] Dev scripts — build, run, test, relay, release, bundle, version
- [x] MCP tooling — android-tools-mcp + mobile-mcp configured
- [x] MIT LICENSE
- [x] Orphaned directories cleaned up (companion/, companion_relay/)

## Phase 6 — Future ☐

- [ ] Notification listener (agent reads phone notifications)
- [ ] Clipboard bridge
- [ ] File transfer (phone ↔ server)
- [ ] Multi-device support
- [ ] On-device model fallback
- [ ] Voice mode (TTS/STT)
- [ ] Push notifications (agent-initiated)
- [ ] Session search
- [ ] Memory viewer

---

## Legend

| Symbol | Meaning |
|--------|---------|
| ✓ | Phase complete |
| ◐ | Phase in progress |
| ☐ | Phase not started |
| [x] | Feature done |
| [ ] | Feature remaining |
