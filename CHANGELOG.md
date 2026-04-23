# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

- **desktop CLI binary segfaulted at startup on Bun 1.3.13 Windows x64.** The `--bytecode` flag in `bun build --compile` is experimental and crashes during bytecode-rehydration on 1.3.x Windows x64 before `main()` runs (`panic(main thread): Segmentation fault at address 0x100000D9C`). Dropped `--bytecode` from all four platform build commands in `desktop/package.json`. Cold-start regresses ~50 ms; in exchange the binary actually runs. Versions affected: only `desktop-v0.3.0-alpha.1`. Fix ships as `desktop-v0.3.0-alpha.2`.
- **Installer couldn't find alpha-only releases.** GitHub's `/releases/latest/download/` URL deliberately skips prereleases, so the default `curl | sh` / `irm | iex` one-liner failed against alpha.1 with "maybe no Windows release for this version yet?" Both `install.sh` and `install.ps1` now query the Releases API directly (`GET /repos/.../releases`, filter to `desktop-v*` tags, take first) when `HERMES_RELAY_VERSION=latest`. Pinned versions unchanged.

### Added

- **Pre-release hardening: uninstall, doctor, first-run prompts, version-aware install.** Four parallel workstreams that close the "feels like a dev preview" gap before tagging `desktop-v0.3.0-alpha.1`. (1) **Uninstall scripts** — new `desktop/scripts/uninstall.{sh,ps1}` matching install one-liners, 3-tier: default `--binary-only` (removes binary + PATH entry, preserves `~/.hermes/remote-sessions.json`), `--purge` (also wipes the shared session store with a loud cross-surface warning about Ink TUI + Android tooling dependencies), `--service` (stub for when daemon service installers ship — prints canonical systemd/launchd/sc.exe paths without acting). iex-pipe safety: Windows falls back to `HERMES_RELAY_UNINSTALL_{PURGE,SERVICE}` env vars since `$args` drops through `irm | iex`. Shell rc files deliberately untouched (mirrors install.sh philosophy). (2) **`hermes-relay doctor` subcommand** — local-only diagnostic report (225 lines, `src/commands/doctor.ts`); human format uses `!!` prefix for warnings + hint line at bottom, `--json` for support-paste / scripts. Fields: version / binary_path / install_dir / on_path / sessions file + size + count + summaries (no tokens — total omission, not even prefix) / daemon detection (stat of canonical service unit file paths) / platform + node version. Case-insensitive PATH comparison on Windows. (3) **Interactive first-run fallback** — new `src/relayUrlPrompt.ts` (~180 lines) with `promptForRelayUrl()` (readline on stderr, `^wss?:\/\/\S+$` validation, 3 retries) and `resolveFirstRunUrl()` (auto-picks single stored session, numbered picker for multiple, first-run banner for zero). Wired into `connectAndAuth` in `shell.ts` / `chat.ts` / `tools.ts` and `resolvePairTarget` in `pair.ts`, replacing the hard `No relay URL` error. Fresh-install UX: bare `hermes-relay` now prints `Welcome to hermes-relay. No stored sessions yet — let's pair with a relay server.` → URL prompt → pairing code prompt → drops into shell. `--non-interactive` still fails fast. Daemon command deliberately untouched — headless binaries must never prompt; fails closed on missing credentials/consent as before. (4) **Version-aware install** — `install.{sh,ps1}` now read `$target --version` before download and print one of `upgrading X → Y`, `reinstalling X`, `will replace (could not read version)`, or `installing fresh` (no prior install); post-install readback re-invokes the new binary to confirm. Pinned-version mismatches (`HERMES_RELAY_VERSION=desktop-v0.3.0-alpha.1`) print a non-fatal WARN rather than failing (pre-release version-name drift is expected). 5s timeout on the version call (where `timeout(1)` available); all diagnostic failures fall through to the "could not read version" path. Cross-version normalizer strips `desktop-v` / `v` prefix + `-alpha.N` / `-beta.N` / `-rc.N` suffix for matching. All structural flow (SHA256 verify, tmp cleanup, PATH injection, quarantine note) preserved additively. Type-check + build green; live smoke: `doctor` both modes, `daemon` fails-closed without credentials, help text includes all new surfaces.

- **`hermes-relay daemon` — headless WSS + tool router, lifts the "tools only work while a shell is open" ceiling.** New `desktop/src/commands/daemon.ts` subcommand that opens a persistent relay connection and attaches `DesktopToolRouter` without a TTY. The agent can now reach the user's machine any time of day — first step toward "feels-local" parity. Fails closed on missing credentials (no stored session + no `--token` → exits 1) and on missing consent (no `toolsConsented: true` on the stored record → exits 1 unless `--allow-tools` is passed alongside an explicit `--token`); a headless binary must never be the thing that first grants tool access. Inherits `RelayTransport`'s reconnect state machine as-is — exp backoff 1s → 30s (5min on 429), reconnect listeners persistent across close/reconnect cycles because `channelListeners` is a Map on the transport (not wiped on socket close), so the router's `attach()` fires exactly once. Structured logging defaults to JSON-line on stderr (parseable by journald / log shippers / jq), auto-switches to human-readable when stderr is a TTY, or force either with `--log-json` / `--log-human`. Lifecycle events: `starting` → `authed` (includes `server_version`, `transport`) → `ready` (with `advertised_tools` list) → `reconnecting` (attempt + delay_ms) / `reconnected` → `shutdown` on SIGTERM/SIGINT/SIGHUP → `transport_exited` when the transport exhausts reconnects (exits 1 so the service manager restarts fresh). Live smoke against `ws://172.16.24.250:8767`: `starting` → `authed` (server 0.6.0) → `ready` (5 tools advertised) in ~120ms. New BOOLEAN_FLAGS entries: `log-human`, `log-json`, `allow-tools`. Service installers for Windows `sc.exe` / systemd user unit / macOS launchd plist are the obvious follow-up; the daemon binary is runnable standalone today via `hermes-relay daemon --remote <url>`.

- **Desktop CLI v0.2 — PTY shell, local tool routing, multi-endpoint pairing, reconnect + TOFU, devices, contextual banner.** The `@hermes-relay/cli` package at `desktop/` grew from a chat-only scripting surface into a full Hermes-experience thin client. Bare `hermes-relay` now drops into `shell` mode (interactive PTY pipe through the existing relay `terminal` channel → `tmux new-session -A` + post-attach `exec hermes` → the full local `hermes` banner/skin/session id verbatim, zero server changes). `Ctrl+A .` detaches preserving tmux; `Ctrl+A k` destroys it. New `devices` subcommand drives the relay's `GET/DELETE/PATCH /sessions` HTTP endpoints for listing, revoking, and extending server-side paired-device tokens. Status now surfaces `grants:` (per-channel expiry) and `expires:` (session TTL) pulled from the `auth.ok` handshake the transport already received — `RemoteSessionRecord` gained `grants`, `ttlExpiresAt`, `endpointRole`, `toolsConsented` (additive, back-compat preserved via a `SaveSessionOptions | string | null` overload on `saveSession`). Contextual connect banner (`Connected via LAN (plain) — server 0.6.0`) replaces the flat `Connected (server X)` line across `chat` + `shell`. Multi-endpoint pairing (ADR 24): `--pair-qr <payload>` / `HERMES_RELAY_PAIR_QR` accepts a full v3 QR payload (compact JSON or base64), decodes the `endpoints[]` array, probes each candidate with strict-priority-within-tier racing (`Promise.any` + `AbortSignal.any`, 4 s per-candidate timeout, 60 s reachability cache), and auto-selects the first reachable — role propagates into the banner + stored record. Reconnect-on-drop: `RelayTransport` gained a `ReconnectState` machine (`idle|connecting|connected|reconnecting`), exponential backoff (1 s → 30 s, 5 min on 429), `reconnectGate` re-checked both at schedule time and post-backoff (matches Android's mid-sleep purge-race lesson), `'reconnecting'` + `'reconnected'` events, and bufferedEvents-cleared-on-reconnect. TOFU cert pinning: TLS probe runs before the WebSocket opens on `wss://`, extracts peer-cert SPKI sha256 (`sha256/<base64>`, OkHttp-compatible), compares against the stored pin or captures it first-time; mismatches error out with a human-readable "re-pair to reset" pointer. Client-side tool routing (Phase B): new `desktop` relay channel on the server (`plugin/relay/channels/desktop.py` + `plugin/tools/desktop_tool.py` registering `desktop_read_file` / `desktop_write_file` / `desktop_terminal` / `desktop_search_files` / `desktop_patch`) forwards tool calls from Hermes to the connected Node CLI; client-side `DesktopToolRouter` dispatches to in-process handlers (`fs`, `terminal`, `search`) under a 30 s AbortController, 30 s heartbeat advertising the tool names. Gated behind a one-time per-URL consent prompt (`toolsConsented` on the session record) + `--no-tools` kill-switch; non-TTY stdin fails closed. New files on the client: `src/banner.ts`, `src/endpoint.ts`, `src/pairingQr.ts`, `src/certPin.ts`, `src/commands/devices.ts`, `src/tools/router.ts`, `src/tools/consent.ts`, `src/tools/handlers/{fs,terminal,search}.ts`. New files on the server: `plugin/relay/channels/desktop.py`, `plugin/tools/desktop_tool.py`, `docs/relay-protocol.md §3.5`. Still zero runtime deps on the client (Node ≥21 global `WebSocket` + `fetch` + `tls.connect` + `node:crypto` X509Certificate + `AbortSignal.any`). Build clean; live smoke passed for `status` / `tools` / `devices`; interactive `shell` + tool-call smoke pending user walk-through. Delivered as four parallel implementation agents (multi-endpoint, reconnect+TOFU, server-side desktop, client-side tool handlers) + one synthesis-and-integration pass; the `connectAndAuth → {relay, url, endpointRole}` return-shape refactor in `chat.ts` / `shell.ts` / `tools.ts` unifies how `--pair-qr`'s winning-endpoint URL overrides `--remote` across every subcommand.

- **Desktop thin-client CLI (`@hermes-relay/cli`) v0.1 under `desktop/`.** Node ≥21 package — installable via `npm install -g @hermes-relay/cli`, `npx @hermes-relay/cli`, or the new `scripts/install.sh` / `install.ps1` curl+iwr one-liners. One `hermes-relay` binary with four subcommands: `chat` (REPL + one-shot + piped-stdin, default), `pair` (one-time handshake → persists session token), `status` (local read of `~/.hermes/remote-sessions.json`), `tools` (`tools.list` RPC → enabled/available toolsets on the server). Credential precedence matches the Ink TUI exactly: `--token` → `HERMES_RELAY_TOKEN` → `--code` → `HERMES_RELAY_CODE` → stored session → interactive readline prompt. Reuses the **same** `~/.hermes/remote-sessions.json` store as the TUI, so a user paired via either surface sees the other work with no re-pair. Zero server changes: the CLI consumes the existing relay `tui` WSS channel + `tui_gateway` subprocess events (`message.delta`, `tool.start/complete`, `thinking.delta`, `status.update`, `error`, `approval.request`, …) and renders them as plain lines to stdout, with decorated tool arrows on stderr. Flags: `--remote <url>`, `--code <CODE>`, `--token <TOKEN>`, `--session <id>`, `--json` (event-per-line for `jq`), `--verbose`, `--quiet`, `--no-color`, `--non-interactive`, `--reveal-tokens` (opt-in full-token output on `status --json` — default redacts). Transport, gateway types, session storage, graceful-exit, and rpc helpers are **vendored verbatim** from `hermes-agent-tui-smoke/ui-tui/src/` (feat/tui-transport-pluggable) with a header note; the CLI and TUI stay in lockstep on the envelope protocol (docs/relay-protocol.md §3.7) until the shared surface can be lifted into a `@hermes-relay/core` package post-stabilization. SIGINT during a turn calls `session.interrupt` via a per-turn `{ promise, cancel }` handle — the REPL's cancellation state lives and dies with the turn so a late-arriving `error` event for a cancelled turn can't be misread by the next turn's handler. Smoke-tested end-to-end against `ws://172.16.24.250:8767` (hermes-relay 0.6.0, hermes-agent 0.10.0): connect/auth/session.create/prompt.submit/tools.list/--json/piped-stdin all clean. Not yet wired: interactive approval/clarify/sudo/secret request response (renderer logs a warning; out of scope for v0.1). Upstream PR candidate once the sibling Ink TUI stabilizes — see `desktop/README.md` and vault `Desktop Client.md` for the broader thin-client roadmap.

### Changed

- **Transport Security badge is now role-aware — "Plain (on LAN)" instead of "Insecure (network unknown)".** The previous badge derived its label from `PairingPreferences.insecureReason`, which only got populated when the user toggled "Allow insecure connections" ON via the Ack dialog and picked a reason. If a user paired directly from a plain-`ws://` LAN QR, they never had to toggle that flag — the connection was already `ws://` — so the reason stayed blank and the badge degraded to the alarming `"Insecure (network unknown)"` even though the multi-endpoint resolver was tracking `activeEndpointRole = "lan"` in real time. Fix: `insecureReasonLabel` now accepts an optional `activeRole: String?` and prefers the live role over the stored ack reason (`Plain (on LAN)` / `Plain (on Tailscale)` / `Plain (on public URL)`). Neutral fallback when both role and reason are unknown is `"Plain (no TLS)"` — matches the new "Plain / Secure" vocabulary, drops the scary "Insecure" adjective. Binary-boolean `TransportSecurityBadge(isSecure, reason, ...)` overload gains an optional `activeRole` param with default `null` so existing call sites compile unchanged. `ConnectionViewModel.applyPairingPayload` auto-stamps `PairingPreferences.insecureReason` at pair time based on the selected endpoint's role (`lan` → `lan_only`, `tailscale` → `tailscale_vpn`, `public`/unknown → leave blank so the user thinks); clears any stale reason when upgrading to a secure endpoint. Only overwrites blank values — never clobbers a user-selected reason. Two user-visible "Insecure" strings inside the Advanced section's insecure-toggle subsection also rewritten to "Plain" for consistency (`"Plain connection — traffic is not encrypted"`, `"Allow plain (unencrypted) connections"`).

### Added

- **Bridge destructive-verb "Don't ask again" per verb.** `BridgeSafetyManager` now consults a new `trustedDestructiveVerbs: Flow<Set<String>>` in `BridgeSafetyPreferences` and short-circuits the confirmation overlay when the incoming verb is in the set (logging the auto-approval to the activity log so the trail is preserved). The `DestructiveVerbConfirmDialog` gets a `Don't ask again for "{verb}"` checkbox — off on every dialog open, so the user has to actively opt in per-action. Deny path never persists trust (denying a command is not consent). Kill-switch precedence is preserved and strictly ordered: master-disable wins over blocklist wins over per-verb trust. A trusted verb in a blocklisted app still 403s. `BridgeScreen` surfaces a `Trusted actions · N actions bypass confirmation` row with a `Reset` button under the existing safety section so a user who changes their mind can find the escape hatch without deep-linking to developer options. Addresses the confirmation-fatigue trap where approving `send_sms` 50 times trains the user to click through without reading the 51st.

- **AllInsecure pairing — one-time acknowledgment gate.** When every endpoint in a scanned QR is plain `ws://` / `http://` (no secure sibling to fall back to), `ConnectionWizard.ConfirmStep` now renders an `"I understand this pairing sends traffic in plain text — visible to anyone on the network."` checkbox that gates the Pair button. Per-install via new `PairingPreferences.allInsecurePairAckSeen` — once the user has acknowledged it, subsequent AllInsecure pairs pair one-tap. Mixed and AllSecure pairings are ungated (the amber "Mixed — secure fallback available" warning on Mixed is sufficient because the secure route exists). Matches the `InsecureConnectionAckDialog` precedent of per-install Tier-1 consent and complements the UX pass's explicit "subtle warning for Tier-2, forced confirm for Tier-1 absolute boundaries" philosophy documented in DEVLOG 2026-04-22.

### Changed

- **Connection UX self-narration pass — Route / Relay sessions vocabulary + section headers + per-route security chips.** Three linked problems shipped as one commit: (1) pairing step 2 read as "you're stuck with insecure" for any multi-endpoint QR with LAN first, because the security badge + warning card were both computed from `endpoints[0]` alone — never acknowledging a secure Tailscale fallback in the same list; (2) the post-refactor active card had the right structure but no narration — sections stacked without headers, no captions explaining what Routes / Advanced / Security are for, Advanced surfaced manual URLs with no "most people don't need this" framing; (3) "Paired Devices" sounded like Bluetooth to anyone outside the project — the actual concept is server-side relay sessions with per-channel grants. Fix: introduce a shared vocabulary (Route for network path, Active/Fallback for state, Secure/Plain for transport, Relay sessions for server records) used consistently across `ConnectionWizard.kt` ConfirmStep, `ActiveConnectionSections.kt` (all three body sections), `EndpointsCard.kt`, and `PairedDevicesScreen.kt`. New `TransportSecurityState` tri-state (`AllSecure` / `Mixed` / `AllInsecure`) drives a context-aware pairing badge — the Mixed case now reads "LAN is plain ws:// — fine at home or the office, not on public Wi-Fi. Tailscale is encrypted (wss://) and the app uses it automatically when LAN is unreachable. You're safe on any network." — so users see they have a secure fallback without needing to understand the candidate-list mental model. Active card gains four labelMedium section headers (Connection health / Routes (N) / Advanced / Security) each with a one-line bodySmall caption above the section body. Endpoint rows in both surfaces carry per-row Secure/Plain chips (green 🔒 / amber 🔓, not scary red) so each route's security is visible at a glance; ordinal labels are humanized (`1st choice` / `Fallback` / `Fallback 2` on pairing step 2; `Active` / `Fallback` on the active card — different framings because pre-connection the commitment is ordinal and post-connection what matters is state). `PairedDevices` Kotlin identifier and deep-link route string stay — only the user-visible labels change — so nav deep links are unaffected. New intro paragraph on the Relay sessions screen explains that rows are sessions (not Bluetooth pairings), and a tap-for-info icon on "Channel grants" opens a dialog explaining that chat/bridge/voice are per-feature permissions with independent expiries. Delivered as three parallel `general-purpose` implementation agents (one per surface, isolated file ownership) plus a post-implementation `code-reviewer` sweep that caught seven leftover `endpoint`/`Paired Devices` strings across `ConnectionInfoSheet.kt`, `SessionTtlPickerDialog.kt`, `EndpointsCard.kt`, `SettingsScreen.kt`, and the `Screen.PairedDevices` nav title — all corrected before commit.

### Fixed

- **Add-Connection navigation now fires on the tap instead of waiting for placeholder persistence.** Pre-fix, `RelayApp.kt`'s `onAddConnection` lambda awaited `beginAddConnection().join()` *before* calling `navController.navigate(Screen.Pair)` — so three serialized DataStore writes (addConnection / persistUrls / setActiveConnection) blocked the QR scanner appearing. On a warm device this was ~15-50 ms; on a cold / flash-pressured device it spiked to 100-150 ms, a visible freeze on every FAB tap. Fix pre-allocates the placeholder UUID synchronously on the UI thread, fires `navController.navigate(Screen.Pair.route(connectionId = id, autoStart = "scan"))` immediately, and runs `connectionViewModel.beginAddConnection(preAllocatedId = id)` in a fire-and-forget background coroutine. `ConnectionViewModel.beginAddConnection` gains an optional `preAllocatedId: String? = null` param — when provided, skips UUID generation, does an existence check (idempotent re-entry on double-tap / recomposition), and falls through to the existing mutex-guarded placeholder-build path. PairScreen's existing reactive `collectAsState` on `connectionStore.connections` / `activeConnectionId` picks up the placeholder milliseconds later — the user is still framing the QR. Critical path drops from three DataStore writes to zero; the writes still happen, just off the critical path. Zero behavior change for `preAllocatedId == null` callers (the legacy placeholder-reuse scan path is preserved byte-for-byte).

### Added

- **`relayReady` signal gates voice + bridge surfaces.** New `ConnectionViewModel.relayReady: StateFlow<Boolean>` composes three inputs — WSS `ConnectionState.Connected`, `AuthState.Paired`, AND non-blank `relayUrl` — into a single "WSS is actually functional" truth. ChatScreen's mic button dims + Toasts "Voice mode unavailable — relay not connected" instead of launching an overlay that would immediately fail on `/voice/transcribe`. BridgeScreen surfaces an error-container banner at the top of the scroll region so the user doesn't enable the master toggle expecting commands to flow. Soft-gate semantics — neither surface hard-disables, matching the existing Chat-send / Terminal-Refresh patterns; BridgeScreen intentionally still lets the user pre-configure permissions and safety rails before a relay pairs. Three-input (rather than the simpler two-input `chatReady` form) because the Case-C teardown edge — last connection removed, `_apiServerUrl`/`_relayUrl` blanked — can leave a stale `Paired` token alive alongside a dead URL; without the URL check the banner would never surface in that state.

### Changed

- **Connection settings unified — one screen, one mental model.** The pre-refactor app had two near-identically-named screens (`ConnectionSettings` singular, `ConnectionsSettings` plural) reached from two different Settings-top surfaces (Active Connection quick-look card vs. "Connections" category row), each covering overlapping functionality. Everything the singular screen did — pair QR entry, manual URL config, insecure toggle, manual pairing code fallback, 3 tappable status rows — now folds inline onto the **active card** of the plural screen as expandable body sections. The singular `ConnectionSettings` screen (1429 lines), its route, its `Screen` enum entry, its `onNavigateToConnectionSettings` param chain, and the Active Connection quick-look card on Settings have all been removed. New active-card structure: Status rows (always visible) → Endpoints expander → Advanced expander (manual URL / insecure toggle / manual pairing code) → Security posture strip (transport badge + Tailscale chip + hardware keystore badge + Paired Devices row). Non-active cards stay flat. Navigation path throughout the user docs updates from `Settings → Connection → X` to `Settings → Connections → [active card] → X` (or `→ Advanced → X`). New file `ui/components/ActiveConnectionSections.kt` (~650 lines) owns the active-card bodies; `ui/screens/ConnectionsSettingsScreen.kt` is rewritten (~580 lines) with screen-scope hoisting for info sheets + the insecure-Ack dialog so `LazyColumn` item disposal can't silently dismiss them mid-scroll. Team-delivered: three parallel `feature-dev:code-explorer` agents produced the full feature inventory + integration map + caller trace in under 2 minutes, which made the synthesis + implementation mechanical.

### Fixed

- **Voice-exit chime firing on every Add-connection tap.** `ConnectionSwitchCoordinator.switchConnection` fires the `voiceStopCallback` unconditionally at step 3 (correct for connection-to-connection switches while voice is active), but `beginAddConnection` also routes through `switchConnection` to bind the placeholder Connection's auth store before the pair wizard runs — and `VoiceViewModel.exitVoiceMode()` was playing `sfxPlayer.playExit()` regardless of whether voice mode was actually on. Logcat confirmed the chime on every Add-connection FAB tap. Fix adds an idempotence guard at the top of `exitVoiceMode()`: early-return when `_uiState.value.voiceMode` is already false. Teardown is still safe to skip because every inner statement is null-guarded + try/catch-wrapped and would be a no-op on an already-stopped voice session; the only meaningful line is the `playExit()` SFX, which is what we're silencing.
- **500 ms freeze on every Add-connection tap.** `ConnectionSwitchCoordinator.switchConnection` runs a `withTimeoutOrNull(AUTH_HYDRATE_TIMEOUT_MS = 500L)` block at step 10 to wait for the freshly-bound `AuthManager` to flip `AuthState` from `Loading` to `Paired`. The comment acknowledged Add-connection is the common path and the 500 ms was meant to be "imperceptible," but on-device it wasn't — the user perceived the delay (and the voice chime masking it) on every tap. The placeholder Connection created by `beginAddConnection` has `pairedAt == null` and an empty EncryptedSharedPreferences store, so `AuthState` will NEVER reach `Paired` — the 500 ms is pure stall. Fix short-circuits the hydrate wait when `target.pairedAt == null`: skip `withTimeoutOrNull` entirely for placeholders and log at DEBUG instead of the misleading "auth hydrate timeout" INFO. Real paired-to-paired switches still run the full hydrate wait because both sides have `pairedAt != null`.
- **KDoc nested-comment trap in `ConnectionViewModel.relayReady` doc block.** A literal `/voice/*` path pattern inside the `relayReady` KDoc opened a nested block comment (Kotlin supports nested `/* */`, Java does not) whose `*/` then closed only the nested level — leaving the outer `/**` open for the remaining ~2200 lines of the file. Symptom: `MainActivity.kt:67` "Unresolved reference 'isReady'" plus ~50 cascading "Cannot infer type" errors across `PairedDevicesScreen`, `SettingsScreen`, `TerminalScreen`. Real errors (`Missing '}`, `Unclosed comment`) were the last two lines of `./gradlew compileGooglePlayDebugKotlin` output, easy to miss. Fix was a two-character rewrite: path patterns now wrapped in backticks AND `/*` → `/...` so the glob-looking character isn't in a block-comment position. Lesson logged in `DEVLOG.md` 2026-04-21; worth a sweep of other KDoc blocks for shell/regex-looking patterns before the next large diff.

- **Orphan placeholder connections from abandoned Add-connection flows.** The `beginAddConnection` path pre-creates a placeholder Connection and switches to it before the pair wizard runs — so `applyPairingPayload` lands the token in the right auth store. Previously, cleanup of the placeholder was wired only to the explicit Cancel button and TopAppBar back arrow. System back (gesture back / predictive back) bypassed that branch, leaving the placeholder in the connection list forever. Two-part fix: (a) `PairScreen` now installs a `BackHandler` that routes system back through the same `onCancel` → `discardPlaceholderConnection` branch the explicit back arrow uses; (b) `ConnectionViewModel.init` sweeps for any existing orphans (tuple: `pairedAt == null && apiServerUrl.isBlank() && label == PLACEHOLDER_LABEL`) on cold start and removes them — the tuple cannot be produced by any real pairing, so the sweep is safe without a dry-run. If the active connection at startup points at an orphan, the sweep switches to the first surviving real connection before deleting. Fixes the "why does my chip say 'New connection…'" symptom on devices that were affected pre-fix.
- **Pair flow now auto-starts the camera on Add connection.** `ConnectionWizard` gains an `autoStart: String?` param (currently only `"scan"` is honored). The Add-connection FAB on `ConnectionsSettingsScreen` passes it so the wizard fires the camera permission launcher on first composition instead of forcing users through the Method chooser — one obvious next step, one-tap flow. Re-pair surfaces intentionally leave `autoStart` null so the full Scan / Enter code / Show code chooser stays available there. The deep-link arg is plumbed through `Screen.Pair`'s route (`pair?connectionId=...&autoStart=...`) and `PairScreen`'s new `autoStart` param; unrecognized values fall through to the default Method step so future builds can add more targets without breaking old ones.

### Changed

- **Top-bar connection chip → inline switcher in the Agent sheet.** The app-wide `ConnectionChip` row that used to sit above every primary tab has been removed. Multi-connection switching now renders as a radio list inside the existing Agent sheet's Connection section (matching the visual pattern of the Profile and Personality sections above it), visible only when ≥2 connections are paired. Tapping a non-active connection fires `switchConnection` + a confirmation toast. Reasons: the chip duplicated the Agent sheet's Connection metadata, ate vertical space above every screen, and exposed the placeholder's `New connection…` label whenever an orphan existed (the root cause of Bailey's double-pair confusion). Dead code removed: the `ConnectionChip` import, the `connectionSheetVisible` state, the `ConnectionSwitcherSheet` render block at the bottom of `RelayApp`, and the `connectionChipVisible` / `activeConnection` vals. `ConnectionSwitcherSheet.kt` itself is kept for future programmatic callers.

### Added

- **Card-dispatch → server session sync** (completes ADR 26). Every [HermesCardDispatch] now carries a `syncedToServer` idempotency flag; on the next chat send, `CardDispatchSyncBuilder` synthesizes unsynced dispatches into OpenAI-format `assistant`+`tool` pairs under a namespaced synthetic tool name `hermes_card_action` and splices them into the request body alongside the existing voice-intent synthetic messages. `ChatHandler.markCardDispatchesSynced` commits the flag after the API client accepts the request — same post-handoff timing as voice intents, so a thrown request-building exception leaves both streams retryable. Guarantees the LLM sees prior card interactions ("you approved the `Run shell command?` card") across server restarts and reconnects, including `open_url` dispatches that never go through `sendMessage`. Unit-tested under `CardDispatchSyncBuilderTest` (pure-function JVM tests, no Android deps).
- **Rich cards in chat via `CARD:{json}` inline markers** (ADR 26). Assistant messages can now surface structured Material 3 cards — skill results, approval prompts, link previews, calendar entries, weather — emitted as a single-line `CARD:{...}` alongside prose text. Follows the same streaming-endpoint-agnostic marker recipe as `MEDIA:`, so it works unchanged on `/v1/runs`, `/api/sessions/{id}/chat/stream`, and `/v1/chat/completions`. New `HermesCard` data class (`@Serializable`, `ignoreUnknownKeys=true` so newer agent schemas don't crash older phone builds) carries `title` / `subtitle` / `body` (markdown) / `fields` / `actions` / `footer` / `accent` (`info`/`success`/`warning`/`danger`). Built-in types: `skill_result`, `approval_request`, `link_preview`, `calendar_event`, `weather`; unknown types render via a generic fallback. `approval_request` intentionally mirrors Slack's exec-approval pattern (Allow / Deny with primary/danger button styles) so upstream Phase B adapter parity is a translation exercise, not a data-model rethink. Action dispatch (`send_text` default, `slash_command`, `open_url`) routes through `ChatViewModel.dispatchCardAction`, which stamps a `HermesCardDispatch` on the owning message before forwarding so the card collapses into a "Chose: X" confirmation even if the side effect fails. Renderer is `HermesCardBubble.kt` — accent stripe + Icon + Title/Subtitle + markdown body + fields table + FlowRow of action buttons. Cards render between the assistant's prose and any attachments in `MessageBubble`.
- **CI test jobs advisory on `dev`, strict on `main`.** Both `.github/workflows/ci-android.yml` (`test`) and `.github/workflows/ci-relay.yml` (`unit-tests`) now carry `continue-on-error: ${{ github.ref != 'refs/heads/main' && github.base_ref != 'main' }}` — tests still run on every dev push/PR and surface annotations and reports, but they no longer red-gate the merge. Lint stays strict on both branches (Bailey's call: lint debt should still block). The release-merge PR from `dev` → `main` flips tests back to strict, so nothing sneaks through to a tagged release.
- **MorphingSphere on the docs site.** New `SphereMark.vue` component (in `user-docs/.vitepress/theme/components/`) renders a 58×34 sphere directly above the "Install in 30 seconds" block — mounted in the `home-hero-after` slot alongside `InstallSection` for a hero → sphere → install stack. Imports `preview/web/sphere.js` directly so `MorphingSphereCore.kt` remains the single source of truth across app / preview / docs. The cursor reactivity is **eye-only** — the sphere body stays anchored while the bright-spot gaze tracks the pointer (no canvas translate / body bounce). Gaze composition: **scroll-tracking is the always-on baseline** — the eye anchors to the Install section's top edge (via `.install-section` DOM query), not to the viewport center. `installGap = installRect.top − viewportH` is the runway until install enters view; as it shrinks below 50 % viewport-height, `scrollVy` ramps linearly to 1, so by the time install's top crosses into the viewport the eye is already looking straight down at it. Before that runway, the eye sits forward (`scrollVy = 0`). **Cursor-tracking is a soft overlay** — inside a rectangular detection band (full viewport width × container height, linear falloff over 1.0 × container height past the top/bottom edges) the cursor's unit-vector direction crossfades into the scroll target via `cursorWeight`. The eye always has one coherent target — no mode switching, no fbm drift fighting the cursor at the band boundary, no eye-flip between modes. Palette retarget Idle ↔ Listening is gated on `cursorWeight` (0.2 / 0.5 hysteresis) so the sphere reads as *calmly watching* at the scroll baseline and *attentive* on direct hover. A tiny fbm wander (±0.07 on top of the target) keeps the eye breathing when both scroll and cursor are stationary. Fallback when the install element isn't on the page: viewport-center reference preserves the gaze-follows-scroll feel without the anchor. Pointer inputs pass through a per-frame EMA low-pass (180 ms direction / 280 ms proximity time constants) before any math runs — stops the per-event jitter from `pointermove`'s big discrete jumps; asin/acos inputs are capped at ±0.9 so we stay off the infinite-slope end of the inverse-trig curves. Canvas is square (`aspect-ratio: 1 / 1`, `clamp(280px, 48vw, 420px)`) so the sphere fills the frame at the algorithm's natural 0.60-envelope sizing — no dead space between the phone video and the Install block. Respects `prefers-reduced-motion` (zeroes the gaze blend so the eye stops tracking but the ambient animation continues), pauses drawing while scrolled off-screen via `IntersectionObserver`, and resizes via `ResizeObserver` on the container. SSR-safe without a `<ClientOnly>` wrapper — `sphere.js` has no side-effectful imports and all DOM access lives inside `onMounted`, which Vue 3 never runs on the server.
- **`SphereFrame` gaze-bias fields in `MorphingSphereCore.kt` (mirrored in `sphere.js`).** New `lightAngleBiasX`, `lightAngleBiasY`, `lightAngleBlend` (all default 0f / 0) let callers aim the sphere's bright spot at a specific direction without touching the sphere body. The light-angle computation blends between the natural `t * lightSpeedX + noise` rotation (`blend = 0`) and the caller-supplied bias (`blend = 1`). Defaults preserve byte-identical behavior for every existing caller — Android `MorphingSphere.kt` composable, the parity test, and the JS parity harness all stay green because they never set the new fields. First consumer: `SphereMark.vue` on the docs site, which uses the bias to make the sphere's eye track the reader's cursor without bouncing the canvas.
- **`SphereFrame.shadowStrength`** (mirrored in `sphere.js`, default 0f / 0). Darkens `distBrightness` on the hemisphere facing away from the light, scaling it by `(1 − shadowStrength · (1 − directionalLight))` — the lit side is untouched, the shadow side dims proportionally. At 0 the legacy uniform "pearl" shading is preserved byte-for-byte. Docs-site `SphereMark.vue` uses 0.6 so the eye reads clearly against the unlit half of the sphere; Android composable doesn't set it and stays on legacy shading.
- **`MorphingSphereCore.kt` — pure, platform-agnostic sphere algorithm.** Extracted from `MorphingSphere.kt` as the single source of truth for the sphere going forward. Uses only `kotlin.math` — no Android, no Compose, no `Paint` — so the same math can back a terminal TUI (Hermes CLI), the codename-11.dev user site, or a Compose Desktop port without visual drift between surfaces.
- **`preview/web/` — zero-dep browser harness for the sphere.** `sphere.js` is a line-for-line JS mirror of `MorphingSphereCore.kt` (`Math.imul` + `|0` for Kotlin `Int` overflow, floored modulo for `.mod()`, `Math.trunc` for `.toInt()`). `index.html` exposes live panel controls for state / voice / layout (cols, rows, fill%, aspect, char size) + a `phone 9:16` preset matching Compose `@Preview(widthDp=360, heightDp=640)`. Serve via `python3 -m http.server --directory preview/web`.
- **Runtime parity harness for the sphere.** `preview/web/parity-check.mjs` + JVM `MorphingSphereCoreParityTest` render the 8 Compose `@Preview` fixtures on both sides and emit FNV-1a 32-bit checksums. **8/8 structural checksums** (over discrete `(row, col, char)` tuples) and **8/8 zone histograms** match between JS and Kotlin; 6/8 full (color/alpha-inclusive) checksums match — the 2 voice-modulated fixtures drift at the 3rd decimal due to Float (Kotlin) vs Double (JS) precision in compound expressions, sub-perceptible.
- **Multi-endpoint pairing QR** (ADR 24). A single pairing now carries an ordered list of endpoint candidates (`lan` / `tailscale` / `public` / operator-defined) so the same phone works seamlessly across LAN, Tailscale, and a public reverse-proxy URL. The phone picks the highest-priority reachable candidate at connect time and re-probes reachability on every `ConnectivityManager` network change with a 30s per-candidate cache. Strict-priority semantics — reachability only breaks ties among equal priorities, never promotes a lower priority over a higher one. New `plugin/pair.py` CLI flags `--mode {auto,lan,tailscale,public}` (default auto) and `--public-url <url>` drive candidate emission. See [`docs/remote-access.md`](docs/remote-access.md).
- **First-class Tailscale helper** (ADR 25). New `plugin/relay/tailscale.py` + `hermes-relay-tailscale` CLI shim fronts the loopback-bound relay with `tailscale serve --bg --https=<port>` so the port is reachable over the tailnet with managed TLS + ACL-based identity. Safe to call unconditionally — no-ops with structured-dict failure when the `tailscale` binary is absent. `install.sh` gains an optional step [7/7] offering Tailscale enablement; skipped silently when the binary is missing, when `TS_DECLINE=1`, or under non-interactive shells without `TS_AUTO=1`. Auto-retires when upstream PR [#9295](https://github.com/NousResearch/hermes-agent/pull/9295) merges.
- **Remote Access dashboard tab** (in the dashboard plugin). Operators can enable/disable the Tailscale helper, mint multi-endpoint pairing QRs, and inspect which endpoint modes are currently active — all from the hermes-agent web UI.
- **Reachability probe + network-change re-probe** in the Android client. `ConnectionManager.resolveBestEndpoint()` does `HEAD /health` against each API candidate with a 2s timeout + 30s cache; `NetworkCallback.onAvailable` / `onLost` triggers a re-probe. `RelayUiState` gains `activeEndpointRole` so the UI can render which endpoint (LAN / Tailscale / Public) is currently serving.
- **Opt-in terminal sessions.** Fresh terminal tabs no longer auto-attach — each tab shows a centered **Start session** overlay and spawns the tmux-backed shell only after the user taps it. Tabs that have already been started still auto-reattach on reconnect. Removes the previous behavior of creating persistent server-side shells just by opening the Terminal tab.
- **`terminal.kill` envelope** — hard-destroy a session. The relay runs `tmux kill-session -t <name>` out-of-band before tearing down the PTY so the background shell (and any running commands) die with it. Closing a tab now opens a confirmation dialog with explicit **Detach** (preserve tmux session) vs **Kill** (destroy it) choices; the session info sheet also gains an error-tinted **Kill session** button.
- **Touch-scroll + scrollback buttons for the terminal.** A vertical swipe on the terminal surface now moves xterm.js's scrollback (with a 12 px deadzone so long-press-to-select still works); the extras toolbar gains ⇑ / ⇓ / ⇲ buttons for ten-line scroll up, ten-line scroll down, and jump-to-bottom. Scrollback depth is unchanged at 10 000 lines.
- **Friendly names for terminal tabs.** The session info sheet now has an inline rename field that persists a cosmetic name (up to 40 chars) keyed on the wire-side `session_name`. Names survive app restart and re-pair; cleared on Kill but preserved on Detach. The tab chip renders `1 · build` when named.
- **`--prefer <role>` priority override** on every pair surface (`hermes-pair --prefer tailscale`, the `/hermes-relay-pair` skill, and the dashboard Remote Access tab's "Prefer role" dropdown). Open-vocab role string — promotes the named role to priority 0 with the rest renumbered in natural order. Unknown role emits a stderr warning and keeps the natural order. Case-insensitive matching; role string preserved verbatim for HMAC round-trip.
- **Active-endpoint chip in the Chat top bar.** Compact tappable chip (e.g. "LAN" / "Tailscale" / "Public" / "Custom VPN (…)") rendered next to the ambient-mode button when the resolver has picked an endpoint. Tap jumps to the Connections screen so the user can probe / override / re-pair without leaving chat. Hidden for single-endpoint legacy pairings — the existing Settings row already spells the host out.
- **Re-pair hint on single-endpoint connections.** When the active connection has exactly one endpoint (legacy single-URL pair), the Connections list card shows a tertiary-container info strip suggesting "Re-pair with Mode = Auto to get LAN + Tailscale + Public in one QR" with an inline Re-pair button. Silent when zero or ≥2 endpoints are stored.
- **Tailscale Funnel auto-detect for the public candidate.** `plugin.relay.tailscale.funnel_url(port)` probes `tailscale serve status --json` for `AllowFunnel` flags and returns the `https://<hostname>/` URL when the relay port is funneled. `plugin/pair.py` `build_endpoint_candidates` calls it as a fallback whenever `mode=auto` or `mode=public` is picked without an explicit `--public-url` — removes the "pin the public URL on Remote Access tab" step when Funnel is already publishing. Soft-fail on every error path; missing CLI / non-funneled port / unparseable JSON all return None.

### Changed

- **Install-command copy buttons stay pinned.** The copy buttons on the docs home's "Install in 30 seconds" commands used to scroll out of view with long one-liners because `.install-code` had both `position: relative` and `overflow-x: auto` — the button's absolute coordinates anchored to the scrolling content box, not the visible viewport. Split into `.install-code` (positioning context, no overflow) wrapping a new `.install-code-scroll` (padding + horizontal overflow). Button now overlays the code as a proper static copy affordance.
- **Docs hero (mobile).** VitePress's default `.image-container` is a fixed 320×320 square on mobile (designed for round illustrations) with negative margins on `.image` that overlap `.main`. On a 9:16 phone-frame video this caused the frame to overflow the square and the text/CTAs to sit on top of the video. `custom.css` now overrides the container to `height: auto` and zeroes the negative margins below 960 px, and `HeroDemo.vue` swaps three breakpoint widths (280/240/200 px) for one `clamp(180px, 62vw, 280px)` rule with a `max-height: 70vh` safety rail so the frame can't dominate the fold on tall narrow viewports.
- **`MorphingSphere.kt` is now a thin Compose renderer** that delegates all math to `MorphingSphereCore`. Public `@Composable` API is unchanged (same params, same defaults); call sites in `VoiceModeOverlay` and the chat empty state need no updates. Renderer also swapped legacy `android.graphics.Paint` + `Typeface` + `nativeCanvas.drawText` for Compose's `rememberTextMeasurer()` + `drawText`, dropping all `android.graphics.*` imports.
- **Pairing QR now carries the `hermes: 3` schema when endpoints are emitted.** `plugin/pair.py` → `build_payload(endpoints=...)` bumps the version only when the `endpoints` array is present; pairs without endpoint candidates continue to emit `hermes: 2`. `canonicalize()` in `plugin/relay/qr_sign.py` preserves array order and role strings verbatim (no case/whitespace normalization) so HMAC signatures round-trip across Python / Kotlin.
- **Paired Devices screen renders per-endpoint rows.** Each paired device now shows one row per `(device, endpoint)` pair, with a styled chip per role (LAN / Tailscale / Public / Custom VPN). Settings and Paired Devices both read from the new `PairingPreferences` per-device endpoint store.
- **Terminal session info sheet is vertically scrollable** — tall phones in landscape with the new Start / Reattach / Kill action rows no longer clip the Done button.
- **Connections list subtitle shows role names, not count.** Active card's subtitle was "hostname • Connected • LAN • 2 endpoints" — accurate but opaque (users couldn't tell which endpoints the QR carried without expanding). Now shows "hostname • Connected • Active: LAN • LAN + Public" — role set on display, not count. Non-active cards unchanged.
- **Looser resolver probe timing.** Per-candidate HEAD `/health` timeout raised from 2s → 4s and cache TTL from 30s → 60s. ADR 24's 2s was tight enough that LTE hand-off and slow hotel Wi-Fi routinely got marked unreachable spuriously; 4s preserves fast-fail-on-real-outage while surviving the flaky-network case. NetworkCallback still invalidates the cache on real network changes, so the longer cache is functionally equivalent but saves battery.

### Backward compatible

- **Old v1 / v2 QRs keep parsing unchanged.** The Android parser's `ignoreUnknownKeys = true` plus the nullable `endpoints` field means pre-v3 QRs work on new phones (the phone synthesizes a single priority-0 `role: lan` candidate from the top-level fields, promoted to `role: tailscale` when the host matches `100.64.0.0/10` / `.ts.net`), and v3 QRs work on v0.6.x and earlier clients (they ignore `endpoints` and use the top-level fields). No forced re-pair for existing installs.

### Fixed

- **Profile `PUT` endpoints restored.** The ADR 24 commit collaterally deleted ~479 lines of `handle_profile_soul_put` / `handle_profile_memory_put` while adding multi-endpoint passthrough to the pairing handlers. `PUT /api/profiles/{name}/soul` and `PUT /api/profiles/{name}/memory/{filename}` are back at their canonical positions; atomic-write semantics and loopback-or-bearer auth unchanged.
- **Stray terminal errors no longer poison the wrong tab.** Server-level error envelopes without a `session_name` (e.g. "Unknown terminal message type" from an older relay) previously fell through to the active tab and flashed an error overlay on whichever tab the user happened to be looking at. Errors without session scope now log only.
- **Dashboard-minted QRs now show the correct 10-minute expiry.** `handle_pairing_mint` was returning `expires_at = now + 60` whenever the caller didn't pin a session TTL (every dashboard mint), which conflated the pairing-code window with the future session's lifetime and made the dashboard dialog count down from ~1 minute even though the underlying code was valid for 10. Now stamps `expires_at = now + _PAIRING_CODE_TTL` explicitly — the pairing-code TTL is what the UI cares about. Session TTL continues to ride the QR payload's `ttl_seconds` field for the phone's TTL picker.
- **PairDialog: multi-endpoint aware, Authelia-trap guardrail.** The dashboard Management tab's "Pair new device" button was still minting legacy single-endpoint QRs (no `endpoints[]`, no `mode`, no `prefer`) while the Remote Access tab had been on the modern path for months. Swapped to `mintPairingWithMode` with `Mode` + `Prefer role` dropdowns as primary inputs; the legacy host/port/tls fields moved under a collapsed "Advanced · API-server override" section with a warning that triggers when the typed host looks like a forward-auth-gated FQDN (the root cause of "relay pairs but phone drops config" reports: e.g. `wss://hermes.example.com` fronted by Authelia gets pinned into the QR's API block, relay WSS succeeds over LAN, then API probes return 401 and the wizard cleans up). Modal widened from `max-w-md` to `max-w-xl` to fit the endpoints receipt without horizontal scroll.
- **PairDialog: proxy-fronted override now requires explicit consent.** Previously the Advanced warning was purely informational — the dialog still auto-minted a QR the phone would fail to use. Now the auto-mint is gated: when the pinned host matches the proxy-fronted heuristic, the dialog pauses and shows "Mint anyway / Clear override" instead of proceeding. Consent is per-host — changing the host resets `proxyConfirmed` so a new host triggers a fresh confirm step.

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
