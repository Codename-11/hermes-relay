# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- **Multi-endpoint pairing QR** (ADR 24). A single pairing now carries an ordered list of endpoint candidates (`lan` / `tailscale` / `public` / operator-defined) so the same phone works seamlessly across LAN, Tailscale, and a public reverse-proxy URL. The phone picks the highest-priority reachable candidate at connect time and re-probes reachability on every `ConnectivityManager` network change with a 30s per-candidate cache. Strict-priority semantics — reachability only breaks ties among equal priorities, never promotes a lower priority over a higher one. New `plugin/pair.py` CLI flags `--mode {auto,lan,tailscale,public}` (default auto) and `--public-url <url>` drive candidate emission. See [`docs/remote-access.md`](docs/remote-access.md).
- **First-class Tailscale helper** (ADR 25). New `plugin/relay/tailscale.py` + `hermes-relay-tailscale` CLI shim fronts the loopback-bound relay with `tailscale serve --bg --https=<port>` so the port is reachable over the tailnet with managed TLS + ACL-based identity. Safe to call unconditionally — no-ops with structured-dict failure when the `tailscale` binary is absent. `install.sh` gains an optional step [7/7] offering Tailscale enablement; skipped silently when the binary is missing, when `TS_DECLINE=1`, or under non-interactive shells without `TS_AUTO=1`. Auto-retires when upstream PR [#9295](https://github.com/NousResearch/hermes-agent/pull/9295) merges.
- **Remote Access dashboard tab** (in the dashboard plugin). Operators can enable/disable the Tailscale helper, mint multi-endpoint pairing QRs, and inspect which endpoint modes are currently active — all from the hermes-agent web UI.
- **Reachability probe + network-change re-probe** in the Android client. `ConnectionManager.resolveBestEndpoint()` does `HEAD /health` against each API candidate with a 2s timeout + 30s cache; `NetworkCallback.onAvailable` / `onLost` triggers a re-probe. `RelayUiState` gains `activeEndpointRole` so the UI can render which endpoint (LAN / Tailscale / Public) is currently serving.
- **Opt-in terminal sessions.** Fresh terminal tabs no longer auto-attach — each tab shows a centered **Start session** overlay and spawns the tmux-backed shell only after the user taps it. Tabs that have already been started still auto-reattach on reconnect. Removes the previous behavior of creating persistent server-side shells just by opening the Terminal tab.
- **`terminal.kill` envelope** — hard-destroy a session. The relay runs `tmux kill-session -t <name>` out-of-band before tearing down the PTY so the background shell (and any running commands) die with it. Closing a tab now opens a confirmation dialog with explicit **Detach** (preserve tmux session) vs **Kill** (destroy it) choices; the session info sheet also gains an error-tinted **Kill session** button.
- **Touch-scroll + scrollback buttons for the terminal.** A vertical swipe on the terminal surface now moves xterm.js's scrollback (with a 12 px deadzone so long-press-to-select still works); the extras toolbar gains ⇑ / ⇓ / ⇲ buttons for ten-line scroll up, ten-line scroll down, and jump-to-bottom. Scrollback depth is unchanged at 10 000 lines.
- **Friendly names for terminal tabs.** The session info sheet now has an inline rename field that persists a cosmetic name (up to 40 chars) keyed on the wire-side `session_name`. Names survive app restart and re-pair; cleared on Kill but preserved on Detach. The tab chip renders `1 · build` when named.
- **`--prefer <role>` priority override** on every pair surface (`hermes-pair --prefer tailscale`, the `/hermes-relay-pair` skill, and the dashboard Remote Access tab's "Prefer role" dropdown). Open-vocab role string — promotes the named role to priority 0 with the rest renumbered in natural order. Unknown role emits a stderr warning and keeps the natural order. Case-insensitive matching; role string preserved verbatim for HMAC round-trip.

### Changed

- **Pairing QR now carries the `hermes: 3` schema when endpoints are emitted.** `plugin/pair.py` → `build_payload(endpoints=...)` bumps the version only when the `endpoints` array is present; pairs without endpoint candidates continue to emit `hermes: 2`. `canonicalize()` in `plugin/relay/qr_sign.py` preserves array order and role strings verbatim (no case/whitespace normalization) so HMAC signatures round-trip across Python / Kotlin.
- **Paired Devices screen renders per-endpoint rows.** Each paired device now shows one row per `(device, endpoint)` pair, with a styled chip per role (LAN / Tailscale / Public / Custom VPN). Settings and Paired Devices both read from the new `PairingPreferences` per-device endpoint store.
- **Terminal session info sheet is vertically scrollable** — tall phones in landscape with the new Start / Reattach / Kill action rows no longer clip the Done button.

### Backward compatible

- **Old v1 / v2 QRs keep parsing unchanged.** The Android parser's `ignoreUnknownKeys = true` plus the nullable `endpoints` field means pre-v3 QRs work on new phones (the phone synthesizes a single priority-0 `role: lan` candidate from the top-level fields, promoted to `role: tailscale` when the host matches `100.64.0.0/10` / `.ts.net`), and v3 QRs work on v0.6.x and earlier clients (they ignore `endpoints` and use the top-level fields). No forced re-pair for existing installs.

### Fixed

- **Profile `PUT` endpoints restored.** The ADR 24 commit collaterally deleted ~479 lines of `handle_profile_soul_put` / `handle_profile_memory_put` while adding multi-endpoint passthrough to the pairing handlers. `PUT /api/profiles/{name}/soul` and `PUT /api/profiles/{name}/memory/{filename}` are back at their canonical positions; atomic-write semantics and loopback-or-bearer auth unchanged.
- **Stray terminal errors no longer poison the wrong tab.** Server-level error envelopes without a `session_name` (e.g. "Unknown terminal message type" from an older relay) previously fell through to the active tab and flashed an error overlay on whichever tab the user happened to be looking at. Errors without session scope now log only.

## [0.6.0] — 2026-04-18

### Added

- **Pair with multiple Hermes servers** and switch in one tap. A new Connection chip on the left of the Chat top bar opens a switcher sheet with a health indicator for each paired server — tap one to cancel in-flight chat, disconnect the old relay, rebind to the new server, and reload sessions + personalities + profiles. The chip is hidden automatically when you only have one Connection. Existing single-server installs migrate transparently on first launch of this version — zero re-pair, zero token migration. See `docs/decisions.md` §19.
- **Connections management screen** at Settings → Connections. Each paired server is a card with inline rename, re-pair (reuses the QR onboarding flow), revoke, and remove. Add a new Connection from the same screen. Per-connection state kept separate: sessions, memory, personalities, skills, profiles, relay URL + cert pin, voice endpoints, last-active session. Theme, bridge safety preferences, and TOFU cert-pin map stay global.
- **Agent Profiles** — the relay now auto-discovers upstream Hermes profiles by scanning `~/.hermes/profiles/*/` (plus a synthetic "default" for the root config) and advertises them in the `auth.ok` payload. On chat send with a profile selected, the phone overlays the request's `model` and `system_message` with the profile's `model.default` + `SOUL.md`. Selection is ephemeral and clears on Connection switch. Gated by `RELAY_PROFILE_DISCOVERY_ENABLED=1` (default on) — operators can set it to `false` to keep the picker empty. See `docs/decisions.md` §21.
- **Consolidated agent sheet** on the Chat top bar. Tap the agent name in the middle of the top bar to open a scrollable bottom sheet holding Profile selection, Personality selection, and session info + analytics (message count, tokens in/out, avg TTFT). Replaces the separate top-bar chips from intermediate v0.5.x builds. Toast confirmations fire on Profile and Personality switches.
- **"Active agent" card** at the top of Settings — summarizes the current Connection / Profile / Personality. Tap navigates to Chat with the agent sheet auto-opened via the `openAgentSheet` nav arg, giving Settings-originating users a one-tap path to change agent context.
- **Three-layer agent model** formalized: Connection (server) → Profile (agent directory) → Personality (system-prompt preset). Documented in `docs/spec.md`, `docs/decisions.md` §8 / §19 / §21, and `user-docs/features/{connections,profiles,personalities}.md`.
- **Pair wizard URL scheme cross-validation** — an inline hint fires when the API field is given a `wss://` URL (or any obviously-wrong scheme), so misplaced values surface before the pair attempt instead of after.
- **Pair-stamp on the active Connection** — successful auth now stamps the active Connection's pairing metadata (paired-at, transport hint, expiry) in place, so a re-pair from Settings doesn't leave stale state on the card.
- **Live WSS state on the active Connection row** in the Connections list — the active card now reflects Connected / Reconnecting… / Stale in real time instead of a static "Paired N minutes ago" timestamp. A Stale state also surfaces an inline **Reconnect** action button (promoted above Rename) tinted to signal "attention."
- **Reconnect taps get explicit feedback.** Every Stale-recovery affordance (the Relay row, the Reconnect button in Connection Settings, and the new Reconnect action in the Connections list) now shows a snackbar / toast "Reconnecting to relay…" so users know the tap registered even during the sub-second before the row flips to Connecting.

### Changed

- **Unified relay status across screens.** `SettingsScreen`, `ConnectionSettingsScreen`, and the Connections list used to resolve relay status independently (each with its own ad-hoc stale / auto-reconnect / probing combinator), which let them disagree on what state the relay was in — e.g. the Settings card said **Disconnected** red while the Connection sub-screen said **Reconnecting…** amber for the same moment. State resolution now lives on `ConnectionViewModel.relayUiState: StateFlow<RelayUiState>` with five well-defined cases (`NotConfigured` / `Connected` / `Connecting` / `Stale` / `Disconnected`) and a 5 s grace window before a Paired-but-Disconnected pose is promoted to `Stale` — every screen maps the single source of truth onto the existing `ConnectionStatusRow` API.
- **Settings "Connection" card → "Active Connection".** Title renamed, and the current Connection's label now renders as the card subtitle so installs with multiple servers can see at a glance which one the status rows describe. Fresh `reconnectIfStale()` tick on first compose so the Relay row doesn't flash red before the lifecycle observer's resume path lands.
- **Status-badge UX polish.** `ConnectionStatusBadge` top-aligns cleanly on multi-line rows (was vertically centered and drifted off-center when the label wrapped). The Settings screen now treats a paired Connection with a briefly-down relay as **Connecting** (amber) instead of **Disconnected** (red) — avoids scare-red during the few seconds around a relay restart.
- **Top-bar chip layout.** `ProfilePicker.kt` and `PersonalityPicker.kt` as standalone top-bar chips are gone; their selection now lives inside the consolidated agent sheet.

### Fixed

- **`POST /pairing/mint` emits the correct wire format.** Dashboard-minted QRs were unscannable — the relay endpoint put the freshly-minted pairing code in top-level `key` and defaulted the top-level port to the relay's own `8767` (its `server.config.port`) instead of the Hermes API server's `8642`. The Android scanner reads top-level `host:port` as the **API** server URL and expects the minted code inside `relay.code`, so phones saw `serverUrl=http://host:8767` (wrong port, no API reachable) and an empty `relay` block — `applyServerIssuedCodeAndReset` bailed on the empty code and the WSS never handshook. Silent fail. The `hermes-pair` CLI and `/hermes-relay-pair` skill were unaffected because they go through `pair.py`'s CLI path which builds the payload correctly; only the dashboard's "Pair new device" flow hit the bug. `handle_pairing_mint` now mirrors `pair.py:762` — top-level `host/port/key/tls` default from `RelayConfig.webapi_url` (resolved to a LAN-routable IP via `_resolve_lan_ip`) with `host`/`port`/`tls`/`api_key` body overrides, and the `relay` block carries `url` from `_relay_lan_base_url(server.config.host, server.config.port, ...)` plus the minted `code`. Shape now matches `docs/spec.md` §3.3.1 and `QrPairingScanner.kt`. Regression test at `plugin/tests/test_pairing_mint_schema.py` (8 cases) pins the payload shape against what the Android parser expects so the two sides can't drift silently again.
- **Dashboard Relay Management tab no longer crashes on paired-session list.** `RelayManagement.jsx:172` wrapped a dict-shaped `s.grants` (`{chat, terminal, bridge}`) in a 1-element array and rendered each entry as a React child, tripping minified React error #31 ("objects are not valid as a React child"). Now uses `Object.keys(s.grants)` when the value is dict-shaped so Badge children are always strings; existing array path preserved for future callers. Rebuilt bundle at `plugin/dashboard/dist/index.js` — the hermes-agent dashboard loads that file verbatim so source changes require a rebuild.

### Deferred

- True per-profile isolation on a single Connection (memory + sessions + `.env` shared today; use separate Connections for full isolation).
- Persisted Profile selection per Connection across app restarts.
- Gateway-running probe (hermes-desktop-inspired) on the Connection health indicator.

## [0.5.x] — Unreleased feature work

### Added — Voice silence auto-stop (2026-04-18)

- **Silence-based auto-stop for Listening turns.** `VoiceViewModel.startListening()`
  now arms a `silenceWatchdogJob` that polls `VoiceRecorder.amplitude` every
  150 ms and calls `stopListening()` after the user's configured
  `silenceThresholdMs` of continuous silence following at least one
  above-floor frame. Uses the existing `RESUME_SILENCE_THRESHOLD = 0.08f`
  floor (already tuned to reject mic hiss / room tone while catching
  whispered speech). Cancelled on manual stop, `interruptSpeaking`, and
  `onCleared`. Skipped in `InteractionMode.HoldToTalk` — the physical
  release is the authoritative stop there. Closes the previously-dead
  `VoiceSettings.silenceThresholdMs` preference, which was persisted
  + exposed via a Settings slider but never consumed by any code path.

### Fixed — Bootstrap crash when wrapping command middleware (2026-04-18)

- **`hermes_relay_bootstrap/_command_middleware.py`** — `maybe_install_middleware()`
  was replacing `app._middlewares` (an aiohttp `FrozenList`) with a plain
  tuple via `(*existing, middleware)`. When `AppRunner.setup()` later
  called `app._middlewares.freeze()`, tuples have no `.freeze()` method
  and the gateway crashed on startup with `'tuple' object has no
  attribute 'freeze'`. Switched to in-place `app._middlewares.append(middleware)`
  — the FrozenList is still mutable at middleware-install time. 31/31
  tests in `test_command_middleware.py` pass.

### Added — Dashboard plugin

- **Hermes-agent dashboard plugin** at `plugin/dashboard/` — surfaces
  relay state in the gateway's web UI via four tabs. **Relay
  Management** lists paired devices + health + relay version;
  **Bridge Activity** renders the in-memory ring buffer of recent
  bridge commands (method / path / decision, with safety-rail
  `executed` / `blocked` / `confirmed` / `timeout` / `error`
  filters); **Push Console** ships as a stub with an
  "FCM not configured" banner until FCM lands; **Media Inspector**
  lists active `MediaRegistry` tokens with live TTL countdowns and
  basename-only file names (absolute paths never leave the server).
  Frontend is a pre-built React IIFE at `plugin/dashboard/dist/index.js`
  (~16 KB) loaded verbatim by the dashboard shell; backend is a thin
  FastAPI proxy at `plugin/dashboard/plugin_api.py` mounted at
  `/api/plugins/hermes-relay/*`.
- **Three new loopback-only relay routes** feeding the plugin —
  `GET /bridge/activity` (ring buffer; `?limit=N`, max 500),
  `GET /media/inspect` (token list; `?include_expired=true` to
  include evicted entries), and `GET /relay/info` (aggregate
  `{version, uptime_seconds, session_count, paired_device_count,
  pending_commands, media_entry_count, health}`). Plus a
  loopback-exempt branch on the existing `GET /sessions` so the
  plugin proxy doesn't need to mint a bearer.
- **`BridgeCommandRecord` ring buffer** on `BridgeHandler`
  (`deque(maxlen=100)`) — records `request_id`, `method`, `path`,
  redacted `params`, `sent_at`, `response_status`, `result_summary`,
  `error`, and `decision`. Commit `777a06a` wires append/update into
  `handle_command()` / `handle_response()` without changing external
  behaviour; timeouts flip `decision=timeout`, phone-side safety
  denials flip `blocked`. Params are redacted for keys in
  `{password, token, secret, otp, bearer}`.
- **`MediaRegistry.list_all(include_expired=False)`** — lock-guarded
  snapshot method returning `{token, file_name, content_type, size,
  created_at, expires_at, last_accessed, is_expired}` dicts sorted
  newest-first. Absolute paths are never included. Commit `2212fbc`.
- **Pairing workflow from the dashboard** — new `POST /pairing/mint`
  relay route (loopback-only) generates a random 6-char A-Z/0-9 code,
  registers it with the existing `PairingManager`, and returns the
  signed QR payload built via `plugin.pair.build_payload`. The
  dashboard backend exposes it at
  `POST /api/plugins/hermes-relay/pairing`. A new **PairDialog** in
  the Management tab renders the QR (via the `qrcode` npm lib bundled
  into the IIFE), shows the code + expiry countdown, and lets the
  operator **override Host / Port / TLS** in the QR payload — useful
  for Traefik-fronted deploys where the phone needs
  `wss://relay.example.com:443` even when the dashboard itself is
  served at a different hostname. Settings persist per-browser in
  localStorage.
- **Functional session revocation** — loopback-exempt branch on
  `DELETE /sessions/{token_prefix}` plus a proxy route at
  `DELETE /api/plugins/hermes-relay/sessions/{prefix}`. The Revoke
  button on the Management tab now confirms via native dialog, calls
  the proxy, and auto-reloads the session list on success.

### Added — Installer

- **`--dashboard-plugin=yes|no`** flag on `install.sh` (default `yes`;
  also via `HERMES_RELAY_DASHBOARD_PLUGIN` env var). Passing `no`
  renames `plugin/dashboard/manifest.json` → `manifest.json.disabled`
  so the hermes-agent dashboard loader skips the plugin entirely.
  Re-running with the opposite flag flips it back — no config lives
  anywhere else.
- **Live dashboard rescan** in both `install.sh` and `uninstall.sh` —
  parses `hermes-dashboard.service` ExecStart for `--host` / `--port`
  and GETs `/api/dashboard/plugins/rescan`, falling back to loopback
  and common ports. The relay tab appears/disappears without a
  dashboard restart. Silent no-op when the dashboard isn't running.

### Fixed

- **Dashboard plugin UI** uses plain tab buttons instead of Radix
  `<Tabs>`: Radix's `Tabs` container expects `TabsContent` children
  (not exposed in the SDK whitelist) and its internal context blew
  up at first render as `o is not a function` after minification.
- **Install banner** no longer claims "Phase 3 — Bridge channel +
  status tool" (stale since v0.2.x). Phase-agnostic copy now.

### Added — Sideload in-app update check

- **In-app update banner** on the `sideload` flavor. On cold start (at
  most once every 6h) the app queries the GitHub `releases/latest`
  endpoint and, if it's behind, shows a slim `UpdateBanner` at the
  top of the scaffold with the current and latest versions. Tap
  **Update** → opens the `-sideload-release.apk` asset URL directly in
  the browser; Android's DownloadManager fetches it and hands it to
  the OS installer. Tap the **X** to dismiss for this version — the
  banner reappears automatically on the next release.
- **"Updates" row in About → About** card — manual "Check" button
  with the same plumbing. After a successful check shows either
  "You're on the latest release" or "Update available — v0.x.y" with
  a **Download** CTA. The row is hidden on the `googlePlay` flavor
  (Play Store owns update delivery there).
- **No new permissions** — the app never installs APKs itself; it
  only opens the asset URL via `ACTION_VIEW`. The Android download +
  install path is unchanged from what sideload users already use.
- Files: `update/UpdateChecker.kt`, `UpdatePreferences.kt`,
  `UpdateModels.kt`, `SemverCompare.kt`;
  `viewmodel/UpdateViewModel.kt`;
  `ui/components/UpdateBanner.kt`;
  wire-up in `ui/RelayApp.kt` + `ui/screens/AboutScreen.kt`.

### Added — v0.4.1 Bridge page polish pass

- **`UnattendedGlobalBanner`** — thin 28dp amber strip at the top of
  `RelayApp`'s scaffold, visible on every tab when master + unattended
  are both on (sideload only). Pulsing amber dot, copy "Unattended
  access ON — agent can wake and drive this device", chevron →
  navigates to the Bridge tab. Theme-aware colours (amber-on-dark in
  dark mode, dark-amber-on-pale-amber in light). Pairs with the existing
  `BridgeStatusOverlayChip` — banner handles the app-foregrounded case,
  the overlay chip handles the app-backgrounded case.
- **`PhoneSnapshot` agent-awareness fields** — `unattendedEnabled`,
  `credentialLockDetected`, `screenOn`.
  `PhoneStatusPromptBuilder.buildBridgeLine()` now appends explicit
  guidance so the LLM knows upfront whether commands will land on the
  device while the user is away, instead of finding out reactively via
  `keyguard_blocked` error responses.
- **`MASTER` pill** next to the master-toggle title, and leading
  "Master switch —" subtitle copy, so the parent-gate role of the
  toggle is legible without reading a wall of helper text.

### Changed — v0.4.1 Bridge page polish pass

- **Bridge tab card order** rewritten with a clear hierarchy: Master →
  Permission Checklist → [Advanced divider] → Unattended Access → Safety
  Summary → Activity Log. The previous standalone `BridgeStatusCard`
  was dropped from the layout because its device / battery / screen /
  current-app rows already render inline inside the master toggle card.
  (The component file remains in-tree and is still unit-testable; it's
  just not rendered by `BridgeScreen` any more.)
- **Unattended Access gated on the master toggle.** The Switch inside
  `UnattendedAccessRow` is now `enabled = masterEnabled` and the
  subtitle reads "Requires Agent Control — enable the master switch
  above first." when master is off. The standalone
  `KeyguardDetectedChip` card was inlined as a `KeyguardDetectedAlert`
  Surface band inside the Unattended Access card so the credential-lock
  warning lives next to the thing that triggers it (same concern, one
  card).
- **Persistent-notification copy corrected.** The unattended one-time
  scary dialog no longer implies the unattended toggle owns the
  "Hermes has device control" notification — explicitly attributes it
  to the master switch. The master-toggle info dialog gained a
  matching paragraph naming the persistent notification.

### Fixed — v0.4.1 Bridge page polish pass

- **Master toggle silent no-op** when Accessibility Service isn't
  granted. Tapping the disabled Switch used to do nothing (stock
  Android disabled-switch behavior); now it surfaces a snackbar —
  "Accessibility Service must be enabled first." — with an "Open
  Settings" action that deep-links to
  `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
- **Permission checklist Optional pill wrapped** on narrow titles
  (e.g. "Notification Listener"). Switched the row layout to
  `FlowRow` and forced `softWrap=false` on the pill's text so the
  pill renders as a single unbroken element.
- **Runtime-permission rows silently no-opped** after permanent denial.
  Mic / Camera / Contacts / SMS / Phone / Location rows now fall back
  to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` when the user has
  selected "Don't ask again", taking the user straight to the app's
  permission page instead of consuming the tap.

### Added — v0.4.1 Bridge fast-follows (in progress)

- **Tiered permission checklist** on the Bridge tab — the previously-flat
  4-row layout is now four explicit sections (Core bridge, Notification
  companion, Voice & camera, Sideload features). Each runtime dangerous
  permission gets its own row with a `RequestPermission` launcher that
  re-probes status on grant. Optional rows render an "Optional" Material 3
  pill so users don't perceive them as urgent. Sideload-only rows
  (Contacts, SMS, Phone, Location) are hidden on the googlePlay flavor
  via the existing `BuildFlavor.isSideload` gate.
- **JIT permission-denied surfacing** for the Tier C agent-tool wrappers
  (`android_search_contacts`, `android_send_sms`, `android_call`,
  `android_location`). When the phone reports a missing runtime permission,
  the wrapper upgrades the bridge response to a structured envelope
  carrying `code: "permission_denied"` + `permission:
  "android.permission.READ_CONTACTS"` + a deterministic LLM-readable
  explanation that names the exact Settings deep-link path. The LLM no
  longer has to guess from a free-text error string why the tool failed.
- **Voice-mode JIT chip** — when a voice intent dispatch returns
  `permission_denied`, a tappable errorContainer-coloured chip surfaces
  above the mic button with copy like "I need Contacts to Send SMS here.
  Tap to open Settings." Tapping deep-links to
  `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the running
  package's permission page. Cleared on tap or on the next mic tap.
- **`ResolveResult` typed-union** in `plugin/tools/resolve_result.py` —
  `Found(value)` / `NotFound(detail)` / `PermissionDenied(permission,
  reason)` dataclass hierarchy with a `from_bridge_response` classifier.
  Reads both the canonical wire keys (`code` / `permission`, v0.4.1) and
  the legacy aliases (`error_code` / `required_permission`, pre-v0.4.1)
  for forwards/backwards compatibility across the v0.4.x APK rollout.
- **17 new Python unit tests** in `plugin/tests/test_resolve_result.py`.

### Changed — v0.4.1 Bridge fast-follows (in progress)

- **`BridgeCommandHandler.respondFromResult`** now emits the canonical
  `code` + `permission` wire keys alongside the legacy `error_code` +
  `required_permission` on permission-failure bridge responses. Existing
  consumers that read the legacy keys keep working unchanged.
- **`BridgePermissionStatus`** extended with `microphonePermitted`,
  `cameraPermitted`, `contactsPermitted`, `smsPermitted`,
  `phonePermitted`, `locationPermitted`. `refreshPermissionStatus()`
  probes each on every `Lifecycle.Event.ON_RESUME`.

### Added — v0.4.1 unattended access mode (sideload-only)

Opt-in "Unattended Access" toggle on the Bridge tab that lets the
agent wake the screen and dismiss the keyguard while the user is
away from the phone. Sideload-only — the googlePlay flavor never
sees the toggle, never installs the wake lock, and never invokes
`requestDismissKeyguard`.

- **`UnattendedAccessManager`** — sideload-only singleton holding
  the screen-bright wake lock and orchestrating the `KeyguardManager.
  requestDismissKeyguard` call. `acquireForAction()` is invoked from
  the bridge command dispatcher pre-gate for any non-read-only route
  and returns one of `Success` / `SuccessNoKeyguardChange` /
  `KeyguardBlocked` / `Disabled`. The wake lock uses
  `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE`
  with a 30 s hard timeout per acquire.
- **One-time scary opt-in dialog** — fires the first time the user
  flips the unattended toggle ON. Explains the security model
  ("agent can drive your phone while you're away"), the credential-
  lock limitation ("Android won't let us dismiss PIN / pattern /
  biometric locks"), and the three disable paths (toggle off, auto-
  disable timer expiry, relay disconnect). Latched via
  `BridgeSafetySettings.unattendedWarningSeen` so it never re-appears
  after dismissal.
- **Persistent keyguard-detected chip** — when unattended is ON
  and the device has `KeyguardManager.isDeviceSecure == true`, an
  error-tinted Card on the Bridge tab warns the user that the
  screen will wake but stop at the lock screen.
- **Amber "Unattended ON" status-overlay chip** — when unattended
  is on, the existing `BridgeStatusOverlayChip` switches from the
  red-dot "Hermes active" variant to an amber-dot "Unattended ON"
  variant so the user (or anyone glancing at the device) can tell
  at a glance that the agent is permitted to wake the screen.
  Forced visible whenever unattended is on, even if the user has
  the regular status-overlay preference disabled.
- **`keyguard_blocked` structured error** — when the wake fires
  but the keyguard refuses to dismiss, the bridge dispatch short-
  circuits with HTTP 423 and `error_code = "keyguard_blocked"`
  before invoking the action. The LLM's tool wrapper sees the
  classification and can tell the user to change their lock screen
  to None / Swipe rather than blindly retrying. `ActionExecutor`
  also classifies dispatch failures against the live keyguard
  state via the new `classifyGestureFailure()` helper, so the
  same `error_code` surfaces if a gesture fails on a locked
  device with unattended OFF.
- **Manifest:** `DISABLE_KEYGUARD` declared in
  `app/src/sideload/AndroidManifest.xml`. WAKE_LOCK was already
  declared in the main manifest for the bridge gesture wake-lock
  scope and is reused.
- **Lifecycle wiring:** `MainActivity.onResume` registers the host
  activity for `requestDismissKeyguard`, `onPause` clears it.
  Master-toggle-off and relay-disconnect both call
  `UnattendedAccessManager.release()` so the screen-bright lock
  drops immediately and the screen returns to its natural timeout.

**Decisions documented during implementation, not re-litigated:**

- No WiFi-disconnect failsafe — rejected because Tailscale / VPN
  invalidates the "leaving WiFi = leaving LAN" assumption. Rely
  on existing relay-disconnect detection plus auto-disable timer.
- Default auto-disable timer stays as-is (30 minutes). No special
  unattended-mode default.
- Credential lock cannot be dismissed by third-party apps — surfaced
  via the one-time warning dialog, the persistent chip, and the
  `keyguard_blocked` error code rather than worked around.

### Added — Voice intent → server session sync (v0.4.1 fast-follow)

- **Voice actions now reach the server-side LLM's session memory.** Previously, phone-local voice intents (`open Chrome`, `text Sam saying hi`, etc.) ran in-process via `BridgeCommandHandler.handleLocalCommand` and appended local-only trace bubbles to the chat scroll. The Hermes API server's session never learned about them, so a follow-up text question like "did that work?" hit the LLM with no context and returned hallucinated answers (per Bailey's 2026-04-14 on-device repro).
- **Implementation.** Each phone-local voice intent now records a structured `VoiceIntentTrace` (tool name, JSON args, success, JSON result envelope) on the post-dispatch chat-trace bubble it produces. `VoiceIntentSyncBuilder` walks the chat history before each `POST /v1/runs` / `POST /api/sessions/{id}/chat/stream` call and synthesizes OpenAI-format `assistant` (with `tool_calls`) + `tool` (with `tool_call_id`) message pairs from any unsynced traces. The synthesized array rides under the existing payload's new `messages` field — additive, ignored by older servers, picked up by anything OpenAI Chat Completions–shaped. Idempotency: traces flip to `syncedToServer=true` the moment the API client takes ownership of the request, so subsequent turns don't re-emit them.
- **Zero server changes.** Frontend-only, no hermes-agent edits needed.
- **Files.** `data/ChatMessage.kt` (new `voiceIntent: VoiceIntentTrace?` field), `voice/VoiceIntentSyncBuilder.kt` (pure-function builder + helpers), `network/HermesApiClient.kt` (optional `voiceIntentMessages` parameter on both stream methods), `viewmodel/ChatViewModel.kt` (build + sync + flag flip in `startStream`), `viewmodel/VoiceViewModel.kt` (extended dispatch callback wires the structured trace into the chat-trace bubble), `voice/VoiceBridgeIntentHandler.kt` (new `androidToolName` + `androidToolArgsJson` on `IntentResult.Handled`), sideload `VoiceBridgeIntentHandlerImpl.kt` populates them per intent, sideload + googlePlay `VoiceBridgeIntentFactory.kt` typealias updates. Tests in `test/voice/VoiceIntentSyncBuilderTest.kt` (12 cases — empty input, single success, failure with error_code, idempotency, chronological order, prefix gate, blank-args gate, call-id pairing, helpers) and `test/network/handlers/ChatHandlerTest.kt` (4 new cases for trace storage + `markVoiceIntentsSynced`).

### Added — Barge-in (interrupt the agent)

Voice mode can now be interrupted by speaking while the agent is
replying — the same turn-taking pattern ChatGPT, Siri, and Google
Assistant use. Stops the current TTS response the moment your voice
is detected, flips to Listening, and hands the mic back to you
without you needing to tap anything. If you then stay quiet for
~600 ms, the agent resumes from the next sentence of the response
you interrupted — so a quick breath or pause won't throw away its
answer.

- **Duplex audio + Silero VAD.** A new `BargeInListener` runs a
  continuous `AudioRecord` (16 kHz mono PCM, `VOICE_COMMUNICATION`
  source) alongside TTS playback, feeding 32 ms frames to a bundled
  Silero voice-activity-detection model via `com.github.gkonovalov:
  android-vad:silero`. `AcousticEchoCanceler` + `NoiseSuppressor` are
  attached to the ExoPlayer audio session so the VAD doesn't trip on
  our own TTS output. A second hysteresis layer on top of the library
  (`2`–`3` consecutive speech frames depending on sensitivity) rejects
  isolated false-positive frames.
- **Two-stage ducking → cutoff.** A single raw speech frame fires a
  `maybeSpeech` event → TTS volume ducks to 30 % as a soft
  acknowledgement (user hears the shift, knows we heard something).
  If hysteresis passes → hard `bargeInDetected` → `interruptSpeaking()`
  fires (same path V4 wired for user-initiated interrupts in the
  voice-quality-pass — cancels synth/play workers, deletes pending
  cache files). If no follow-up detection within 500 ms, a watchdog
  un-ducks so a single stray frame doesn't leave playback quieted.
- **Resume-from-next-sentence.** `VoiceViewModel` tracks the list of
  sentence chunks the play worker has spoken plus the index the user
  interrupted at. After an interrupt, a 600 ms watchdog listens to
  `VoiceRecorder.amplitude` — if the user keeps speaking past the
  threshold, the new turn proceeds normally and the interrupted
  response is dropped. If silence wins, remaining chunks re-enqueue
  onto the TTS queue and playback resumes from the sentence after the
  cut. Controlled by the "Resume after interruption" sub-toggle
  (default on).
- **Settings UI.** New "Barge-in" section in Voice Settings: master
  toggle (default off), sensitivity segmented button (`Off / Low /
  Default / High` — inverted from the library's `Mode` enum so higher
  user-facing value = more sensitive), resume sub-toggle, and a
  compatibility warning badge that shows on devices where
  `AcousticEchoCanceler.isAvailable() == false` ("Your device may have
  limited echo cancellation. Barge-in quality will vary.").
  Preferences live in `BargeInPreferences` DataStore following the
  existing `BridgeSafetyPreferences` shape.
- **Shipped default-off.** AEC quality varies widely across Android
  OEMs — Pixel is solid, many mid-tier and older devices aren't. The
  feature ships disabled by default; users opt in from Voice Settings
  and see the compatibility badge if their device has no AEC. A
  `useExoPlayerVoice` flavor-safe architecture from the voice-quality-
  pass already exposed `VoicePlayer.audioSessionId`, which is what
  AEC binds against.
- **Live settings reactivity.** Toggling the feature on or off
  mid-conversation works without restarting voice mode; the
  coordinator observes `BargeInPreferences.flow` and starts/stops the
  listener on each emission.

Tests: 7 new `VoiceViewModelBargeInTest` cases covering the
interrupt path, resume-vs-keep-talking branches, the ducking
watchdog, and live prefs-change reactivity. Plus unit tests for each
new subsystem (VAD engine, duplex listener with `AudioFrameSource`
seam for non-instrumented tests, ducking helpers, DataStore).

### Changed — Voice output quality pass

Addresses four symptom classes that surfaced in on-device voice testing
after v0.4.0: voice output switching between crisp and muffled, volume
drifting between sentences, audible pauses between chunks, and occasional
jumbled-letter spell-outs when the agent emitted markdown, URLs, or
tool-annotation tokens. Root-caused across five compounding layers and
fixed end-to-end in a single agent-team session on
`feature/voice-quality-pass`.

- **Text sanitization, both ends.** A new `plugin/relay/tts_sanitizer`
  module strips markdown (code fences, links, URLs, bold/italic, inline
  code, headers, list markers, horizontal rules), Hermes tool-annotation
  tokens (`` `💻 terminal` ``, `` `🔧 android_foo` ``, etc.), and a
  conservative standalone-emoji set before `/voice/synthesize` hands
  text to the upstream `text_to_speech_tool`. The same regex set is
  mirrored client-side in `VoiceViewModel.sanitizeForTts` and applied
  per delta before the sentenceBuffer sees the text, with multi-delta
  code-fence deferral so unclosed fences don't leak orphaned backticks
  to the chunker. Kills the "jumbled letters" symptom — ElevenLabs no
  longer reads URLs character-by-character or speaks backtick+emoji
  wrappers aloud.
- **Coalescing chunker.** The old `MIN_SENTENCE_LEN=6` chunker emitted
  every tiny acknowledgement (`"Sure."`, `"Okay."`) as its own TTS
  call, guaranteeing audible inter-chunk variance. New
  `MIN_COALESCE_LEN=40` + `MAX_BUFFER_LEN=400` secondary-break escape
  merges short runs into one synthesize call, splits run-on sentences
  at the last comma/semicolon/em-dash inside the 400-char window, and
  preserves the `e.g.`/`U.S.` abbreviation lookahead. An 800 ms
  silent-delta timer force-flushes buffered text so trailing fragments
  on an abrupt stream-end don't strand in the buffer.
- **Prefetch pipelining.** `VoiceViewModel.startTtsConsumer` was
  previously a strictly serial `synthesize → play → awaitCompletion`
  loop — every sentence boundary cost one full network round-trip. Now
  split into two `supervisorScope`-rooted coroutines joined by a
  bounded `Channel<File>(capacity=2)`: the synth worker runs up to one
  sentence ahead of the play worker, so N+1's audio is already on disk
  when N's playback finishes. Synth failures on N+1 no longer stall
  N's playback. Cancellation paths (`stopVoice`,
  `interruptSpeaking`, `exitVoiceMode`) cancel the scope and delete any
  unplayed `voice_tts_<ts>.mp3` cache files; a `finally`-scoped
  cleanup catches any late-arriving synth results that beat the cancel
  signal.
- **Gapless ExoPlayer playback.** `VoicePlayer` swapped from
  recreating a `MediaPlayer` per file to a single persistent Media3
  ExoPlayer + `addMediaItem` queue. Appending is non-blocking;
  `awaitCompletion()` now returns when the queue is drained AND the
  player is idle (documented semantic change). Kills the codec-reset
  pop between sentences and composes naturally with the prefetcher —
  the play worker appends without blocking the synth worker. Visualizer
  attaches once against the ExoPlayer audio session (deferred to the
  first `onIsPlayingChanged(true)` since some OEMs initialize the
  session id lazily) and degrades gracefully if attach fails. Ships
  behind a `FeatureFlags.useExoPlayerVoice` hook as a safety net; no
  `MediaPlayer` fallback is currently wired.
- **ElevenLabs model flipped to `eleven_flash_v2_5`.** Operator change
  applied to `~/.hermes/config.yaml` on hermes-host ahead of the code
  work. `eleven_multilingual_v2` is expressive but re-interprets
  prosody per call — wrong model for a chunked pipeline.
  `eleven_flash_v2_5` is the streaming-optimized model (~75 ms
  per-request latency, lower per-call variance, designed exactly for
  sentence-scale pipelines) and is net-cheaper per character. Voice id
  unchanged. This single flip accounts for the bulk of the perceived
  "clear↔muffled switching" reduction; the code units below reduce
  what remained.

Deferred: upstream PR exposing `VoiceSettings` (stability /
similarity_boost / use_speaker_boost) in
`hermes-agent/tools/tts_tool.py::_generate_elevenlabs`. Useful once
merged — default `stability` is a hair too low for consistent chunked
output — but not blocking; the flash model already solves most of what
the settings would.

Tests: 33 new relay sanitizer tests, 4 new client test files covering
sanitization parity, chunking semantics, prefetch pipelining timing +
cancellation cleanup, and ExoPlayer queue behavior.

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
- **18 `android_*` plugin tools** routed through the new unified bridge (14 baseline + send_sms, call, search_contacts, return_to_hermes added in v0.4.0)
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
