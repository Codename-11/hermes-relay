# Hermes Companion — Dev Log

## 2026-04-05 — Project Restructuring

**Done:**
- Wrote SPEC.md (full spec — protocol, UI, 6 phases, tech stack)
- Wrote DECISIONS.md (framework choice, architecture rationale, deferrals)
- Restructured repo: moved docs to `docs/`, rewrote README to match ARC conventions
- Created CLAUDE.md handoff for agent team development
- Created DEVLOG.md

**Current state:**
- Upstream bridge app exists in `hermes-android-bridge/` (Kotlin + Ktor, XML layouts)
- Python plugin exists in `hermes-android-plugin/` (14 `android_*` tools, working)
- No Compose code yet — MVP will rewrite the Android app
- No companion relay yet — `companion-relay/` dir TBD

**Next:**
- MVP Phase 0: Compose scaffold, WSS connection manager, channel multiplexer, auth flow
- MVP Phase 1: Companion relay + chat channel (streaming UI)
- Need Android Studio locally to build/test the Compose app

## 2026-04-05 — MVP Phase 0 + Phase 1 Implementation

**Done:**
- Created `companion-app/` — full Jetpack Compose Android project (30 files, 2500+ lines)
  - Gradle setup: Kotlin 2.0.21, AGP 8.7.3, Compose BOM 2024.12.01, Gradle 8.9
  - Bottom nav scaffold with 4 tabs (Chat, Terminal, Bridge, Settings)
  - WSS connection manager (OkHttp, auto-reconnect with exponential backoff)
  - Channel multiplexer (typed envelope protocol)
  - Auth flow (6-char pairing code → session token in EncryptedSharedPreferences)
  - Chat UI: message bubbles, streaming text, tool progress cards, profile selector
  - Settings UI: server URL, connection status, theme selector
  - Material 3 + Material You dynamic theming
  - Terminal and Bridge tabs stubbed for Phase 2/3
- Created `companion_relay/` — Python aiohttp WSS relay server (10 files, 1500+ lines)
  - WSS server on port 8767 with health check
  - Auth: pairing codes (10min expiry), session tokens (30-day expiry), rate limiting
  - Chat channel: proxies to Hermes WebAPI SSE, re-emits as WS envelopes
  - Terminal/bridge channel stubs
  - Config from env vars + ~/.hermes/config.yaml profiles
  - Runnable via `python -m companion_relay`
- Created `.github/workflows/` — CI/CD pipeline
  - CI: lint → build (debug APK) → test, relay syntax check
  - Release: tag-triggered, version validation, signed APK → GitHub Release
- Generated Gradle wrapper (gradlew + jar) for Android Studio

**Current state:**
- Phase 0 complete — scaffold, networking, auth all in place
- Phase 1 server-side complete — relay handles chat channel routing
- Phase 1 client-side complete — chat UI with streaming support
- Ready for Android Studio build + device testing

**Next:**
- Open `companion-app/` in Android Studio, sync, and test on emulator/device
- Fix any build issues that surface
- Connect to a running Hermes WebAPI instance and test chat end-to-end
- Phase 2: Terminal channel (xterm.js in WebView, tmux integration)
- Phase 3: Bridge channel migration
