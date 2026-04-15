# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.4.0] - 2026-04-14

### Added — Bridge feature expansion (the big one)

v0.4 roughly triples the bridge surface. The agent can now do everything
v0.3 could do, plus long-press, drag, full clipboard access, system-wide
media control, raw Android Intents, an accessibility-event stream, app
launching, app listing, multi-window screen reads, filtered node search,
screen-hash change detection, stable per-node IDs, three-tier
`tap_text` fallback, a batched macro dispatcher, wake-lock-guarded
gesture dispatch, and a per-app skill playbook for common flows. The
sideload track additionally ships direct SMS, contact lookup, one-tap
dialing, and location awareness.

**Read surface**

- **`/long_press`** (A1) — long-press gesture by coordinate or node ID,
  covering context menus, text selection, and widget rearranging
- **`/drag`** (A2) — drag gesture from point A → point B over a
  configurable duration
- **`/find_nodes`** (A3) — filtered accessibility-tree search (text,
  clickable flag, class name, resource ID) instead of returning the
  whole tree
- **`/describe_node`** (A4) — full property bag for a stable node ID,
  plus `nodeId` wiring for `/tap` and `/scroll` so the agent can hand
  IDs forward without re-resolving coordinates
- **`/screen_hash`** + **`/diff_screen`** (A5) — cheap SHA-256 screen
  fingerprint and diff tools for "wait until this screen changes"
  loops without re-downloading the full accessibility tree
- **`/events`** + **`/events/stream`** (B1) — accessibility-event
  stream. In-memory `EventStore` buffers recent `AccessibilityEvent`
  objects so the agent can poll for UI events or wait for a specific
  trigger instead of hammering `/screen`. Toggle capture on/off via
  `/events/stream`.
- **Multi-window `ScreenReader`** (P1) — `/screen` now walks every
  accessibility window (system UI, popups, notification shade) instead
  of only the active app's window

**Act surface**

- **`/clipboard`** (A6) — bidirectional system clipboard read/write
- **`/media`** (A7) — system-wide playback control (play, pause, next,
  previous, volume) via `MediaSessionManager`
- **`/send_intent`** + **`/broadcast`** (B4) — raw Android Intent /
  broadcast escape hatch for apps that expose deep-link actions
- **Three-tier `tap_text` cascade** (A9) — exact match → clickable
  ancestor walk → substring fallback, fixes apps that wrap labels in
  non-clickable parents
- **`android_macro`** (A10) — batched workflow dispatcher runs a
  sequence of bridge commands as one call with configurable pacing,
  no round-trip per step
- **`WakeLockManager`** (A8) — `PARTIAL_WAKE_LOCK` scope wrapper around
  gesture dispatch so commands still land on dim or idle screens.
  Scoped try/finally semantics, never a stale hold.

**Tier C — sideload-only phone utilities**

- **`/location`** (C1) — GPS last-known-location read for "where am
  I?" and location-scoped commands
- **`/search_contacts`** (C2) — contact lookup by name → phone number
  for voice intents like "text Mom"
- **`/call`** (C3) — direct call via `ACTION_CALL`, with an
  `ACTION_DIAL` fallback where the flavor can't hold `CALL_PHONE`
- **`/send_sms`** (C4) — direct SMS send via `SmsManager` with
  send-result confirmation (no dialer bounce)

**Docs + skills**

- **`skills/android/SKILL.md`** (A11) — per-app playbook with reusable
  flows for common apps, agent-discoverable via the Hermes skills
  system
- **`docs/spec.md` + `docs/decisions.md`** — v0.4 bridge surface
  documented, Phase 3 status marked shipped, 15-item spec rot pass

### Fixed

- **Missing Kotlin handlers for `/open_app`, `/get_apps`, `/apps`,
  and `/setup`** — latent v0.3.0 regression. The Python relay side had
  the routes and the plugin tools were calling them, but the in-app
  `BridgeCommandHandler` had never wired the corresponding `when (path)
  ->` branches. Commands silent-dropped until this release.
- **Android 11+ package visibility for `/get_apps`** — added a
  `<queries>` element to the main manifest so
  `PackageManager.queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)`
  returns the full launchable app list. Without this, the tool returned
  an empty list on modern Android targets.

### Docs

- **`user-docs` expansion** — added the full 27-route bridge HTTP
  inventory to `reference/relay-server.md`, rewrote
  `architecture/security.md` around the five-stage safety gate + Tier 5
  rails, added ADR-9 through ADR-13 (bridge safety gate, wake-scope,
  event stream, MediaProjection FGS type, build flavors), and retired
  all remaining "Bridge :8766" references now that the bridge is
  unified on `:8767`.

## [0.3.0] - 2026-04-13

### Added

**Bridge channel (the big one)** — the agent can now read the phone's
screen, tap, type, swipe, and take screenshots. Gated behind a
deliberate in-app master toggle, per-channel session grants, Android
Accessibility Service permission, MediaProjection consent, and the
safety rails system (blocklist, destructive-verb confirmation modal,
idle auto-disable timer, optional persistent status overlay).

- **`HermesAccessibilityService`** — Android `AccessibilityService`
  subclass that reads the active window's UI tree, dispatches taps /
  types / swipes / scrolls / key presses via `GestureDescription` and
  `ACTION_SET_TEXT`, and caches the foregrounded package
- **`ScreenCapture.kt`** — `MediaProjection` → `VirtualDisplay` →
  `ImageReader` → PNG bytes, uploaded to the relay via `/media/upload`
- **`BridgeCommandHandler`** — routes inbound `bridge.command` envelopes
  to the executor, with the three-stage safety check
  (blocklist → destructive-verb confirmation → auto-disable reschedule)
- **`BridgeSafetyManager`** — process-wide safety enforcement singleton
  with DataStore-backed blocklist (30 default banking/payments/2FA
  apps), destructive verb list (`send`/`pay`/`delete`/`transfer`/etc.),
  auto-disable timer, and confirmation timeout
- **`BridgeForegroundService`** — persistent "Hermes has device control"
  notification with Disable + Settings actions, deep-linked to the
  Bridge Safety settings screen
- **`BridgeStatusOverlay`** — `WindowManager` overlay host for the
  destructive-verb confirmation modal and optional floating status chip
- **Bridge UI** — new Bridge tab with master toggle, permission
  checklist (accessibility / screen capture / overlay / notification
  listener), status card, activity log, and safety summary card
- **Bridge Safety settings screen** — blocklist editor (searchable
  package picker), destructive verb editor, auto-disable timer slider,
  status overlay toggle, confirmation timeout slider
- **14 `android_*` plugin tools** routed through the new unified bridge
  channel (migrated from the legacy standalone `android_relay.py`)
- **`android_navigate`** — vision-driven close-the-loop navigation tool
  (sideload track only)
- **`android_phone_status`** — agent-callable introspection tool that
  reports live bridge state (`device`, `bridge` permissions, `safety`
  config) via the new `/bridge/status` relay endpoint

**Voice mode** — real-time voice conversation via relay TTS/STT:

- Tap the mic in the chat bar to enter voice mode with the ASCII
  morphing sphere, layered-sine-wave waveform visualizer, and
  streaming sentence-level TTS playback
- Three interaction modes (Tap-to-talk, Hold-to-talk, Continuous)
- Sphere voice states — Listening (blue/purple), Speaking (green/teal)
- Voice settings screen (interaction mode, silence threshold, provider
  info, Test Voice)
- New relay endpoints — `POST /voice/transcribe`, `POST /voice/synthesize`,
  `GET /voice/config`
- **Voice-to-bridge intent routing** (sideload track only) —
  spoken commands like "text Mom saying on my way" route to the bridge
  channel instead of the chat channel, with destructive-verb
  confirmation flow

**Notification companion** — `HermesNotificationCompanion`
(`NotificationListenerService`) reads posted notifications and forwards
them to the relay over a new `notifications` channel for agent
summaries. Opt-in via the standard Android notification-access grant.

**Self-setup skill** — `/hermes-relay-self-setup` and
`skills/devops/hermes-relay-self-setup/SKILL.md`. Single-source agent
install recipe — raw URL fetch for pre-install users, Hermes skill
discovery for post-install users. Zero drift.

**Manual pairing fallback** — `hermes-pair --register-code ABCD12`
pre-registers an arbitrary 6-char code with the relay for the rare
"no camera available" case (SSH-only, single-device pair). Phone-side
"Manual pairing code (fallback)" card in Settings → Connection walks
the user through the three-step workflow with a real Connect button.

**Per-channel grant revoke** — each device on the Paired Devices screen
now has tappable per-channel grant chips (chat / voice / terminal /
bridge) with an inline x icon. Revoking a single channel leaves the
other channels' expiries intact.

**`hermes-status`** and **`hermes-relay-update`** shell shims — alongside
`hermes-pair`, these three shims give full discoverable CLI coverage for
pair / status / update.

**`/health` + `/bridge/status` relay endpoints** — loopback-only
structured phone-status endpoint (device, bridge permissions, safety
state) that backs `hermes-status`, `android_phone_status()`, and the
`/hermes-relay-status` skill.

**ConnectionWizard + onboarding unification** — shared three-step
pairing wizard (Scan → Confirm → Verify) used by both first-run
onboarding and re-pair from Settings. Eliminates the "half-paired" state
where onboarding configured the API side but dropped the relay block.

**Lifecycle-aware health checks** — `ConnectionViewModel.revalidate()`
fires on `ON_RESUME` and on `ConnectivityObserver` `Available`
transitions, with a new `Probing` tri-state and a new gray pulsing
`ConnectionStatusBadge` pose. Kills the "foreground lag flash" where
badges showed stale Connected/Disconnected for 30s after foregrounding.

**Two build flavors** — `googlePlay` (Play Store track, conservative
Accessibility use case) and `sideload` (`.sideload` applicationId
suffix, full feature set including voice-to-bridge intents and
`android_navigate`). `sideload` shows as "Hermes Dev" in the launcher
for side-by-side disambiguation.

**TOFU cert pinning**, **Android Keystore session token storage**
(StrongBox-preferred with `EncryptedSharedPreferences` fallback),
**transport security badge**, **session TTL picker dialog** (1d / 7d /
30d / 90d / 1y / never), **Paired Devices screen** with full revoke
flow, **Tailscale detector**, **insecure-mode ack dialog** with reason
picker.

### Changed

- **`install.sh` TUI polish** — ANSI colors (TTY-aware, `NO_COLOR`
  respected), boxed banner, unicode step bullets, spinner for the long
  pip install, polished closing message with structured Pair / Update /
  Manage / Uninstall sections
- **`install.sh` restart semantics** — the restart-relay path now uses
  explicit `systemctl --user restart` instead of `enable --now` (the
  latter is a no-op on already-active services and silently left
  editable-install code refreshes stranded)
- **`install.sh` step 6b** — offer (don't force) hermes-gateway restart
  so new plugin tools re-import. Interactive prompt (default no), env
  var opt-in via `HERMES_RELAY_RESTART_GATEWAY=1`, or flag opt-out
- **Connection settings card rename** — "Bridge pairing code" → "Manual
  pairing code (fallback)" with a walkthrough UX instead of a bare
  code display. The old label implied bridge-specific 2FA; it's
  actually the auth fallback for the whole handshake.
- **Sideload flavor strings** — `app_name` → `Hermes Dev`,
  `a11y_service_label` → `Hermes-Bridge Dev`, notification companion
  label → `Hermes Dev notification companion`. Disambiguates side-by-side
  installs in launcher / recents / Settings → Apps.
- **Google Play flavor a11y label** → `Hermes-Bridge` (with hyphen)
  for consistency with the sideload naming
- **`BridgeStatusReporter`** — pushed envelope now includes the full
  nested `device` / `bridge` / `safety` contract instead of four flat
  keys, with a new `pushNow()` method for out-of-band emission on
  master toggle flips
- **`hermes-relay.service` systemd unit** — runs the relay on port
  8767 with `--no-ssl --log-level INFO`, loads `~/.hermes/.env` via
  `_env_bootstrap.py` at import time (no `EnvironmentFile=` needed)

### Fixed

- **Android 14 MediaProjection grant evaporation** — on API 34+,
  `getMediaProjection()` returned projections the system auto-revoked
  within frames because `BridgeForegroundService` was declared as
  `specialUse` only. Added `mediaProjection` to the FGS type slot,
  updated `startForeground()` to OR both type constants, and gated
  `requestScreenCapture()` on the master toggle so the FGS is
  guaranteed running before consent fires.
- **Master toggle gate broken end-to-end** — `cachedMasterEnabled` was
  never written because nothing called `updateMasterEnabledCache`. The
  cache was permanently `false` and `BridgeCommandHandler` 403'd every
  command except `/ping` and `/current_app`. Service now owns a
  coroutine that observes the DataStore flow and feeds the cache.
- **MediaProjection consent flow never wired** — `MediaProjectionHolder.
  onGranted` existed but no `ActivityResultLauncher` was registered.
  `MainActivity` now registers a launcher and a new
  `ScreenCaptureRequester` process-singleton bridges non-Activity
  callers (`BridgeViewModel.requestScreenCapture()`).
- **Manifest dedupe** — duplicate `HermesAccessibilityService` entry in
  Android Settings caused by a stub `<service>` block in the flavor
  manifests that pointed at a class that didn't exist
- **Gradle deprecation** — `android.dependency.
  excludeLibraryComponentsFromConstraints=true` collapsed into
  `useConstraints=false`
- **Version drift** — `pyproject.toml` had speculatively bumped to
  `0.5.0` and `plugin/relay/__init__.py::__version__` was stuck at
  `0.2.0`. Both synced to `0.3.0` via the new `bump-version.sh` script.

### Docs

- **`hermes-relay-self-setup`**, **`hermes-relay-pair`**, and
  **`hermes-relay-status`** skills — agent-readable setup / pair /
  status recipes via the Hermes skills system
- **`RELEASE.md`** — expanded with the three-source version contract,
  feature-branch workflow, `--no-ff` merge style, branch protection
  policy, and `bump-version.sh` recipe
- **`CLAUDE.md`** — updated Git section with the new branching policy,
  added file-table entries for `hermes-relay-update`,
  `register_code_command`, and the expanded `install.sh`
- **`TODO.md`** — captures open research questions around proper
  Hermes plugin/skill/tool distribution
- **`user-docs` vitepress site** — new "For AI Agents" copy-paste
  block on the home view, Feature Matrix component, two-track explainer,
  manual-pair workflow walkthrough in configuration.md

## [0.2.0] - 2026-04-12

### Added
- **Voice mode** — real-time voice conversation via relay TTS/STT endpoints. Tap the mic in the chat bar to enter voice mode with the sphere, waveform visualizer, and streaming sentence-level TTS playback
  - Three interaction modes (Tap-to-talk, Hold-to-talk, Continuous)
  - Streaming TTS with sentence-boundary detection
  - Interrupt: tap stop during Speaking to cancel TTS + SSE stream
  - Sphere voice states: Listening (blue/purple), Speaking (green/teal)
  - Voice settings screen (interaction mode, silence threshold, provider info, Test Voice)
  - Relay endpoints: `POST /voice/transcribe`, `POST /voice/synthesize`, `GET /voice/config`
  - 6 TTS + 5 STT providers via hermes-agent config
  - Voice messages appear as normal chat messages in session history
- **Reactive layered-sine waveform** — three overlapping waves with amplitude-driven phase velocity (`withFrameNanos` ticker), pill-shaped edge merge (geometric `sin(πt)` taper + `BlendMode.DstIn` gradient mask), color-keyed to voice state
- **Enter/exit voice chimes** — synthesized 200ms PCM sweeps via AudioTrack (440→660 Hz enter, mirror exit)
- **Terminal (preview)** — tmux-backed persistent shells with tabs, scrollback search, and session info sheet
- **Session TTL picker** — choose 1d / 7d / 30d / 90d / 1y / Never at pair time
- **Per-channel grants** — control terminal/bridge access per paired device
- **Android Keystore token storage** — StrongBox-preferred hardware-backed encrypted storage with TEE fallback
- **TOFU certificate pinning** — SHA-256 SPKI fingerprints per host:port, wiped on re-pair
- **Paired Devices screen** — list all paired devices with metadata, extend sessions, revoke access
- **Transport security badges** — three-state visual indicator (secure / insecure-with-reason / insecure-unknown)
- **HMAC-SHA256 QR signing** — pairing QR codes signed via host-local secret
- **Insecure connection acknowledgment dialog** — first-time consent with threat model explanation + reason picker
- **Inbound media pipeline** — agent-produced files via relay `MediaRegistry` with opaque tokens, Discord-style rendering for image/video/audio/PDF/text/generic attachments
- **`/media/by-path` endpoint** — LLM-emitted `MEDIA:/path` markers fetched directly by the phone
- **Settings refactor** — category-list landing page with dedicated sub-screens (Connection, Chat, Voice, Media, Appearance, Paired Devices, Analytics, Developer)
- **Global font-scale preference** — applies to both chat and terminal
- **RelayErrorClassifier** — converts any `Throwable` into a user-facing `HumanError(title, body, retryable, actionLabel)` with context-aware titles
- **Global SnackbarHost** — `LocalSnackbarHost` CompositionLocal at RelayApp scope so any screen can surface classified errors
- **Mic permission banner** — rebuilt with "Open Settings" action button instead of a toast
- **Relay `.env` autoload** — `plugin/relay/_env_bootstrap.py` loads `~/.hermes/.env` at Python import time, matching the gateway pattern
- **systemd user service** — `install.sh` step [6/6] installs and enables `hermes-relay.service` automatically
- **Save & Test health probe** — relay connection verification with classified error feedback
- **Gradle logcat task** — `silenceAndroidViewLogs` auto-runs `adb shell setprop log.tag.View SILENT` after every install to suppress Compose Android 15 VRR spam
- **App screenshots** in `assets/screenshots/`

### Fixed
- **Voice replying to wrong turn** — `ignoreAssistantId` baseline prevents the stream observer from replaying the previous turn's response as TTS for the new question
- **Waveform flatline between sentences** — TTS consumer restructured from `for` loop to `while` + `tryReceive` so `maybeAutoResume` only fires when the queue is actually drained, not after every sentence
- **Stop button during Speaking** — `interruptSpeaking()` now cancels the SSE stream via `chatViewModel.cancelStream()`, drains TTS queue, and returns to Idle (previously only paused playback)
- **Waveform unresponsive to speech** — perceptual amplitude curve at the source (noise-floor subtraction + speech-ceiling rescale + sqrt boost), attack/release envelope follower (0.75/0.10 at 60Hz), killed Compose spring double-smoothing
- **Stop button color** — hardcoded vivid red `Color(0xFFE53935)` for Listening + Speaking (Material 3 dark `colorScheme.error` resolved to pale pink)
- **NaN amplitude propagation** — guards in VoicePlayer.computeRms and VoiceViewModel.sanitizeAmplitude (IEEE 754: `Float.coerceIn` silently passes NaN)
- **Relay voice 500 on restart** — `.env` not loaded into relay process when started via nohup/systemd without shell sourcing
- **Rate-limit block on re-pair** — `/pairing/register` clears all rate-limit blocks on success
- **Paired devices JSON unwrap** — permissive `/media/by-path` sandbox
- **Settings status flicker** — unified relay status as "Reconnecting..." on Settings entry

### Changed
- Smart-swap trailing input button (empty → Mic, text → Send) replacing the floating Mic FAB
- Voice overlay is fully opaque surface (was 0.95 alpha — "phantom pencil" bleed-through from chat)
- Bottom nav hidden during voice mode
- Scrollable response text in voice overlay (long responses no longer clip)
- `install.sh` is now 6 steps (was 5) — new step [6/6] for systemd user service
- Relay restart is `systemctl --user restart hermes-relay` (nohup era ended)

## [0.1.0] - 2026-04-07

### Added
- **ASCII morphing sphere** — animated 3D character sphere on empty chat screen (pure Compose Canvas, `. : - = + * # % @` characters, green-purple color pulse, 3D lighting)
- **Ambient mode** — toggle in chat header hides messages and shows sphere fullscreen; tap to return to chat
- **Animation behind messages** — sphere renders at 15% opacity behind chat message list as subtle background (toggleable)
- **Animation settings** — Settings > Appearance section with "ASCII sphere" and "Behind messages" toggles
- **File attachments** — attach files via `+` button; images, documents, PDFs sent as base64 in the Hermes API `attachments` format
- **Attachment preview** — horizontal strip above input shows thumbnails (images) or file badges (other types) with remove button
- **Message queuing** — send messages while the agent is streaming; queued messages auto-send when the current response completes
- **Queue indicator** — animated bar above input shows queued count with clear button
- **Configurable limits** — expandable Limits section in Chat settings for max attachment size (1–50 MB) and message length (1K–16K chars)
- **Stats for Nerds enhancements** — reset button, tokens per message average, peak TTFT, slowest completion, seconds subtext on all ms values
- **Feature gating** — `FeatureFlags` singleton with compile-time defaults (`BuildConfig.DEV_MODE`) and runtime DataStore overrides
- **Developer Options** — hidden settings section, tap version 7 times to unlock (same pattern as Android system Developer Options)
- **Relay feature toggle** — relay server settings and pairing sections gated behind developer options in release builds
- **Dynamic onboarding** — terminal, bridge, and relay pages excluded from onboarding when relay feature disabled
- **Parse tool annotations** — experimental annotation parsing for Sessions mode (marked with badge, disabled for Runs mode)
- **Privacy policy link** — accessible from Settings → About
- **MCP tooling docs** — `docs/mcp-tooling.md` reference for android-tools-mcp + mobile-mcp development setup
- **Dev scripts** — added `release`, `bundle`, `version` commands to `scripts/dev.bat`
- **MIT LICENSE** — added project license file

### Fixed
- **Empty bubbles** — messages with blank content and no tool calls are now hidden from chat
- **App icon** — adaptive icon foreground scaled to 75% via `<group>` transform for proper safe zone padding
- **Token tracking** — usage data now extracted before SSE event type resolution, fixing 0 token counts when server sends OpenAI-format events
- **Token field compatibility** — `UsageInfo` accepts both `input_tokens`/`output_tokens` (Hermes) and `prompt_tokens`/`completion_tokens` (OpenAI)
- **Keyboard gap** — removed Scaffold content window insets that stacked with ChatScreen's IME padding
- **Session drawer highlight** — active session now properly highlighted (background color was computed but not applied)
- **Privacy doc** — added CAMERA permission, corrected network security description
- **CHANGELOG URLs** — fixed comparison links to use correct GitHub repository
- **FOREGROUND_SERVICE** — removed unused permission from AndroidManifest
- **Plugin refs** — updated from raulvidis to Codename-11

### Changed
- Version bumped from `0.1.0-beta` to `0.1.0` for Google Play release
- Input bar shows both Stop and Send buttons during streaming (previously only Stop)
- Onboarding page flow now uses enum-based dynamic list instead of hardcoded indices

## [0.1.0-beta] - 2026-04-06

MVP release — native Android companion app for Hermes agent with direct API chat, session management, and full Compose UI.

### Added

#### Core Chat
- **Direct API chat** — connects to Hermes API Server via `/api/sessions/{id}/chat/stream` with SSE streaming
- **HermesApiClient** — full session CRUD + SSE streaming, health checks, cancel support
- **Dual connection model** — API Server (HTTP) for chat, Relay Server (WSS) for bridge/terminal
- **API key auth** — optional Bearer token stored in EncryptedSharedPreferences
- **Cancel streaming** — stop button to cancel in-flight chat responses
- **Error retry** — retry button in error banner re-sends last failed message

#### Session Management
- **Session CRUD** — create, switch, rename, delete chat sessions via Sessions API
- **Session drawer** — slide-out panel listing all sessions with title, timestamp, message count
- **Message history** — loads from server when switching sessions
- **Auto-session titles** — first user message auto-titles the session (truncated to 50 chars)
- **Session persistence** — last session ID saved to DataStore, resumes on app restart

#### Chat UI
- **Markdown rendering** — assistant messages render code blocks, bold, italic, links, lists (mikepenz multiplatform-markdown-renderer)
- **Reasoning display** — collapsible thinking block above assistant responses (toggle in Settings)
- **Token tracking** — per-message input/output token count and estimated cost
- **Personality picker** — dynamic personalities from server config (`config.agent.personalities`), agent name on chat bubbles
- **Message copy** — long-press any message to copy text to clipboard
- **Enriched tool cards** — tool-type icons, completion duration tracking
- **Responsive layout** — bubble widths adapt to phone, tablet, and landscape
- **Input character limit** — 4096 character limit with counter
- **Haptic feedback** — on send, stream complete, error, and message copy

#### App Foundation
- **Jetpack Compose scaffold** — bottom nav with Chat, Terminal (stub), Bridge (stub), Settings
- **WSS connection manager** — OkHttp WebSocket with auto-reconnect and exponential backoff
- **Channel multiplexer** — typed envelope protocol for chat/terminal/bridge/system
- **Auth flow** — 6-character pairing code with session token persistence
- **Material 3 + Material You** — dynamic theming with light/dark/auto
- **Onboarding** — multi-page pager with feature overview and connection setup
- **Settings** — API Server + Relay Server config, theme, reasoning toggle, data export/import/reset
- **Offline detection** — banner shown when network connectivity is lost
- **What's New dialog** — shown automatically when app version changes
- **Splash screen** — branded splash via core-splashscreen API
- **Network security** — cleartext restricted to localhost only

#### Infrastructure
- **Relay server** — Python aiohttp WSS server for bridge/terminal channels
- **CI/CD** — GitHub Actions for lint, build, test, and tag-driven releases
- **Claude Code automation** — issue triage, PR fix, chat, and code review workflows
- **Dependabot** — weekly Gradle + GitHub Actions dependency updates with auto-merge
- **Dev scripts** — build, install, run, test, relay via scripts/dev.bat
- **ProGuard rules** — okhttp-sse, markdown renderer, intellij-markdown parser

[Unreleased]: https://github.com/Codename-11/hermes-relay/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Codename-11/hermes-relay/compare/v0.1.0-beta...v0.1.0
[0.1.0-beta]: https://github.com/Codename-11/hermes-relay/releases/tag/v0.1.0-beta
