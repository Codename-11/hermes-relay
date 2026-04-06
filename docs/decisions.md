# Hermes Relay — Decisions & Implementation Guide

> Finalized: 2026-04-05
>
> Read this before SPEC.md — it tells you what to build, what was deferred, and why.

---

## Framework Decision: Kotlin + Jetpack Compose

**Chosen over:** React Native, Flutter, Kotlin + XML (upstream)

**Why Compose:**
- 80% of the app is native Android services (AccessibilityService, PTY, foreground services, biometrics). Cross-platform frameworks would still need Kotlin native modules for all of that, plus a bridge layer.
- Compose is declarative like React — same mental model (state → UI), different syntax. Familiar to React developers.
- Material 3 / Material You theming is first-class. Dynamic color from wallpaper, proper motion system.
- OkHttp WebSocket supports `wss://` natively. No bridge layer to debug.
- Background foreground service keeps the WSS connection alive when the phone locks. React Native's background execution is fragile.
- Single language (Kotlin) for the entire app — services, UI, networking.

**Why not React Native:**
- Would need native modules for: AccessibilityService, foreground service, biometric auth, EncryptedSharedPreferences, MediaProjection. That's most of the app written in Kotlin anyway, plus JS bridge overhead.
- Background reliability issues on Android are well-documented.
- Only makes sense if the UI were 80%+ of the codebase. Here it's ~20%.

**Why not Flutter:**
- Same native bridge problem as RN, but with Dart (new language) instead of familiar React patterns.
- Platform channels for every native API.
- Smaller Android-specific ecosystem for security/biometric libraries.

**Why not staying with Kotlin + XML (upstream):**
- XML layouts are legacy. Compose is the modern Android UI toolkit.
- Upstream UI is functional but not polished. Full rewrite needed anyway.
- Compose gives us better animation, theming, and state management primitives.

---

## Architecture Decisions

### 1. Single WSS Connection with Channel Multiplexing

**Decision:** One WebSocket connection carries all three channels (chat, terminal, bridge) via typed message envelopes.

**Why:** Simpler connection management, single auth flow, single reconnect handler. Mobile networks are flaky — one connection is easier to keep alive than three.

**Trade-off:** If one channel floods (e.g., terminal output), it could delay others. Mitigated by: terminal output batching (16ms frames), priority queuing (system > chat > terminal > bridge).

### 2. Relay Server as Separate Service (Port 8767)

**Decision:** New Python relay service, separate from the existing bridge relay (8766) and the Hermes gateway.

**Why:** 
- The existing relay is single-purpose (bridge only). Extending it risks breaking upstream compatibility.
- Separate service means we can deploy/restart independently of the gateway.
- Future: can merge into gateway as a platform adapter if it stabilizes.

**Alternative considered:** Adding as a gateway platform adapter. Deferred — too coupled to gateway lifecycle for MVP.

### 3. Chat via Direct API, Not Relay Proxy

**Decision:** ~~Chat channel proxies through the relay to the WebAPI.~~ **Updated:** Chat now connects directly from the Android app to the Hermes API Server via the Sessions API (`/api/sessions/{id}/chat/stream`) with HTTP/SSE. The relay server is only used for bridge and terminal channels.

**Why (original relay approach):**
- WebAPI already handles session management, agent creation, SSE streaming, tool progress events.
- Gateway integration would require implementing a new platform adapter. Much more work.
- WebAPI is stable, documented, and used by ClawPort.

**Why direct API (updated 2026-04-05):**
- The relay was an unnecessary middleman for chat — it just converted SSE to WebSocket envelopes.
- Every other Hermes frontend (Open WebUI, ClawPort, LobeChat, etc.) connects directly to the API server.
- The Hermes API Server exposes a Sessions API (`/api/sessions/{id}/chat/stream`) with SSE streaming and rich event types (session.created, run.started, message.started, assistant.delta, tool.progress, tool.pending/started/completed/failed, assistant.completed, run.completed, done).
- Direct connection is simpler, removes the relay as a single point of failure for chat, and reduces latency.
- The relay remains for bridge (device control) and terminal (tmux/PTY) which require custom bidirectional protocols.

**Architecture:**
```
Phone (HTTP/SSE) → Hermes API Server (:8642)   [chat — direct]
Phone (WSS)      → Relay Server (:8767)          [bridge, terminal]
```

**Auth:** Optional Bearer token (`API_SERVER_KEY`) stored in EncryptedSharedPreferences on device. Most local Hermes setups don't require a key.

### 4. xterm.js in WebView for Terminal

**Decision:** Use xterm.js running in a local WebView for the terminal emulator, not a native Compose canvas renderer.

**Why:**
- xterm.js is battle-tested — handles all ANSI escape sequences, Unicode, colors, scrollback.
- A native Compose terminal renderer would be weeks of work for inferior rendering.
- The WebView is a single composable in an otherwise fully native app — acceptable trade-off.
- Can replace with native renderer later if WebView performance is insufficient.

### 5. tmux for Terminal Session Management

**Decision:** Terminal channel attaches to tmux sessions, not raw PTY.

**Why:**
- tmux gives persistence — disconnect from the app, reconnect, session is still there.
- Named sessions let you manage multiple contexts (different projects, different servers).
- Shared sessions — agent and user can see the same terminal (future collaboration).

### 6. Auth: Pairing Code → Session Token

**Decision:** Initial pairing via 6-char code (upstream pattern), then long-lived session token for subsequent connections.

**Why:**
- Pairing codes are user-friendly and don't require pre-shared secrets.
- Session tokens avoid re-pairing on every app restart.
- Tokens stored in EncryptedSharedPreferences (Android Keystore-backed AES-256-GCM).

### 7. Biometric Gate for Terminal Only

**Decision:** Biometric/PIN required before terminal access. Chat and bridge don't require it.

**Why:**
- Terminal = shell access to your server. Highest privilege.
- Chat is conversational — no more dangerous than Discord.
- Bridge is controlled by the agent, not the user — gating it behind biometrics doesn't help.

---

## Deferrals

| Feature | Reason | When |
|---------|--------|------|
| iOS support | Android-first, platform-specific APIs | v2+ |
| Multi-device | Single-device simplifies auth and state | Phase 6 |
| On-device model | Complexity, unclear value for relay use case | Phase 6 |
| Voice mode | Depends on Hermes TTS/STT maturity | Phase 6 |
| Notification listener | Not needed for app core | Phase 6 |
| File transfer | Can use agent tools (terminal) as workaround | Phase 6 |
| Gateway platform adapter | WebAPI proxy works well, adapter is overengineering for now | If WebAPI becomes limiting |

---

## CI/CD Patterns (from ARC)

Adopting from ARC's workflow patterns:

1. **CI workflow:** Lint (ktlint) → Build (debug + release matrix) → Test → Upload APK artifact
2. **Release workflow:** Tag `v*` → version validation (build.gradle.kts vs tag) → signed APK → GitHub Release
3. **Concurrency groups:** Cancel in-progress CI on new push to same branch
4. **Dependabot:** Auto-merge minor/patch dependency updates
