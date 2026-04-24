# Hermes-Relay Roadmap

> Where Hermes-Relay is headed. Short, high-level, grouped by release milestone. For detailed implementation plans of active work see [`docs/plans/`](docs/plans/); for shipped work see [`CHANGELOG.md`](CHANGELOG.md); for the session-by-session narrative see [`DEVLOG.md`](DEVLOG.md).

## Vision

Native Android companion for the [Hermes agent platform](https://github.com/NousResearch/hermes-agent) — chat, voice, and full phone control in one app. We're building toward a world where your AI agent has safe, graceful hands on your phone for the tasks where that matters most: messaging, navigation, music, day-to-day automation, and anything else that's currently a tap-through chore.

## Shipped

- **v0.3.0** — Bridge channel (sideload), voice mode, notification companion, two build flavors, full safety rails system. [CHANGELOG](CHANGELOG.md#030---2026-04-13)
- **v0.2.0** — Voice mode foundation, terminal preview, TOFU cert pinning, Paired Devices screen. [CHANGELOG](CHANGELOG.md)
- **v0.1.0** — Chat, sessions, QR pairing, encrypted storage, Play Store submission.

### Desktop track (parallel lane to Android) — **experimental**

Release tags: `desktop-v*` (separate cadence from Android `v*`). Curl-installed prebuilt binaries (no Node required); Windows first, macOS / Linux same release. Workflows: [`ci-desktop.yml`](.github/workflows/ci-desktop.yml) + [`release-desktop.yml`](.github/workflows/release-desktop.yml).

**Shipped (2026-04-23 — first tagged release `desktop-v0.3.0-alpha.1`):**

- **`@hermes-relay/cli` v0.1** — Node thin-client at [`desktop/`](desktop/). Remote chat + pair + status + tools subcommands over the relay's `tui` WSS channel. Shares `~/.hermes/remote-sessions.json` with the Android client (pair once, both work).
- **v0.2 — resilience + pairing UX** — multi-endpoint pairing (ADR 24: `--pair-qr` probes LAN/Tailscale/Public, strict-priority within-tier race, 4s timeout, 60s cache), reconnect-on-drop state machine (1s→30s exp backoff, 5min on 429, gate re-check post-sleep), TOFU cert pinning via pre-WS TLS probe (SPKI sha256, `sha256/<base64>` OkHttp-compatible).
- **v0.2 — UX polish** — bare `hermes-relay` → `shell` (full Hermes CLI over PTY with `clear; exec hermes` after tmux settles); contextual connect banner (`Connected via LAN (plain) — server 0.6.0`); `status` surfaces grants + TTL + endpoint role from `auth.ok`; new `devices` subcommand talking to relay `GET/DELETE/PATCH /sessions` over HTTP.
- **Phase B — client-side tool routing** — server-side `plugin/relay/channels/desktop.py` + `plugin/tools/desktop_tool.py` register `desktop_read_file` / `_write_file` / `_terminal` / `_search_files` / `_patch` via `tools.registry` (mirror of `android_*` pattern — **zero hermes-agent core change**). Client-side `DesktopToolRouter` attaches to the `desktop` channel, dispatches under a 30s AbortController, heartbeats `desktop.status` every 30s. One-time per-URL consent gate + `--no-tools` kill-switch.
- **`hermes-relay daemon`** — headless WSS + tool router that keeps desktop tools serving without a visible shell. Fails closed on missing stored consent (`--allow-tools` escape hatch with an explicit `--token`). JSON-line logs by default, auto-human on TTY. Inherits transport's reconnect state machine; `setImmediate(exit)` to flush final log line before process dies.
- **Pre-release hardening** — `hermes-relay doctor` (local diagnostic report, human + `--json`, no token leakage); `uninstall.{sh,ps1}` (3-tier: default keeps session store, `--purge` wipes it with cross-surface warning, `--service` stub); interactive first-run prompts (`resolveFirstRunUrl` — auto-picks single stored session, numbered picker for multiple, welcome banner for fresh install); version-aware install (`upgrading X → Y` readback pre-install, post-install confirmation).
- **Self-setup skill** — [`skills/devops/hermes-relay-desktop-setup/SKILL.md`](skills/devops/hermes-relay-desktop-setup/SKILL.md) lets any Hermes agent install, pair, and troubleshoot the CLI with **live local diagnostics** via `desktop_terminal` (can read the user's Node version, PATH, binary location directly — something the Android setup skill can't match).

**Shipped — `desktop-v0.3.0-alpha.6` (seamless-local dev pass, done 2026-04-23):** Plan at [`docs/plans/2026-04-23-desktop-alpha-6-seamless-local.md`](docs/plans/2026-04-23-desktop-alpha-6-seamless-local.md). Nine features across six parallel agent workstreams, all opt-in: workspace-awareness envelope + active-editor signal (#1+#8), `hermes-relay update` self-update subcommand (#2), `desktop_open_in_editor` tool + interactive patch approval with unified-diff rendering (#3+#4), conversation picker on connect (#5), clipboard bridge + screenshot handlers (#9+#12), and a `hermes` alias so muscle-memory works without the `-relay` suffix (#13). Integration day: 2026-04-23.

**Active — `desktop-v0.3.0-alpha.7` (native image paste):** Plan at [`docs/plans/2026-04-23-desktop-alpha-7-native-paste.md`](docs/plans/2026-04-23-desktop-alpha-7-native-paste.md). Two-repo workstream: client slash commands `/paste` (clipboard), `/screenshot` (primary display), `/image <path>` (file) land in `hermes-relay chat`, each echoes a one-line feedback and attaches the image to the next `prompt.submit` so the vision-capable model sees it in the same turn — parity with Claude Desktop's paste UX minus OS-level Ctrl+V (terminals don't pipe image bytes to stdin). Client half is new `desktop/src/chatAttach.ts` + slash-command branches in `desktop/src/commands/chat.ts`. Server half is ONE new `@method("image.attach.bytes")` on the fork's `tui_gateway/server.py` (branch `feat/image-attach-bytes` → merged to `axiom`); the fork's existing `_enrich_with_attached_images` already handles multimodal payload plumbing and session-scoped image state, so this release is almost entirely about bridging client-captured bytes to server-side state that's been there for months. Relay channel unchanged — `tui` is a transparent RPC forwarder. Graceful fallback when hermes-host hasn't been updated yet: client catches `method not found`, prints a pointer at the axiom rollout, REPL stays alive.

**Deferred to alpha.8 / alpha.9 / v1.0:**

- **Per-project session stickiness** — blocked on hermes-agent plugin hook that consumes the workspace envelope; premature until the envelope shape stabilizes in use.
- **Shell-history context hook** — needs rc-file-edit install path, which our install philosophy currently avoids. Design pass required.
- **Desktop notifications for long-running daemon work** — let daemon bake in real-world use first; latency/idle-detection thresholds best tuned with telemetry.
- **Environment-variable passthrough** — security-sensitive; needs per-var prompt UX + threat model before shipping.
- **Global hotkey to summon a prompt** — OS-specific helper installers; out of scope for binary-only release.
- **Watch mode** (`hermes-relay daemon --watch`) — needs a DSL and clear safety bounds; own feature branch.
- **Kitty / iTerm2 inline image protocols for paste feedback** — would show a thumbnail of the attached image directly in the terminal after `/paste` instead of a plain text line. Most terminals don't support them; the slash-command feedback line works anywhere. Revisit if users request it.

**Earlier alpha.2–alpha.5 workstreams (now in-flight / done — see DEVLOG 2026-04-23 entries for specifics):**

- **`hermes-relay update` subcommand + auto-update nudge.** The binary today does NOT self-update — users have to re-run the `curl | sh` / `irm | iex` one-liner to pick up a new release. Close the gap: `hermes-relay update` polls GitHub Releases API (`/repos/Codename-11/hermes-relay/releases/latest`), compares to `readVersion()`, and either shells out to the installer or downloads the binary directly + `rename` over the current one (Windows can rename while running; Linux/macOS atomic replace is fine for long-lived daemons because the running process keeps the old inode open). Add a once-per-day background check in `daemon` mode that emits `update_available` as a log event — opt-in via `--check-updates`, never auto-installs without user action. Signing prerequisite: SmartScreen/Gatekeeper would warn on every auto-downloaded binary until we sign, so this is behind code signing.
- **Workspace-awareness — desktop client sends cwd/git/hostname on connect.** Biggest lingering "is the agent working against the right tree?" problem. On WSS auth, the client advertises an ephemeral workspace descriptor — `cwd`, `git_root`, `git_branch`, `git_status_summary` (staged/modified counts), `repo_name`, `hostname`, `platform`, `active_shell`. Server-side `DesktopHandler` stashes it as live session metadata (NOT persistent state). New hermes-agent plugin hook injects a one-line ephemeral prompt prefix into the session context — *"Active desktop workspace: machine=Bailey-PC · repo=hermes-relay · branch=dev · staged=3"* — so the LLM reads it every turn without the operator having to explain. Also default `desktop_terminal` / `desktop_read_file` / `desktop_search_files` `cwd` to the repo root when unset. Expose the snapshot in `hermes-relay doctor` + `hermes-relay status` + a new `hermes-relay workspace` subcommand + a relay dashboard tab so both operator and agent have a common view. Pair with a `.hermes/workspace-context.json` file-based fallback for when the socket path can't be reached. Requires: new WSS envelope (`desktop.workspace` on connect), hermes-agent plugin hook for ephemeral context injection, schema coordination with the upstream `ContextVar` multi-client work.
- **Service installers** — `scripts/install-service-{win,linux,mac}.{ps1,sh}` — Windows Service via `sc.exe create`, `systemd --user` unit with `loginctl enable-linger`, `launchctl load` plist for macOS. Auto-start on login so the daemon is always reachable.
- **Multi-client routing on the `desktop` channel** — replace single-client MVP with per-token indexing + device-id reconnect handoff. Hermes session state carries `desktop_session_token` via a new `ContextVar` in `gateway/session_context.py` (hermes-agent PR candidate — won't affect Android). Natural pairing with the workspace-awareness envelope — the ContextVar scheme determines which client's workspace the active session sees.
- **Harden `release-desktop.yml` retag semantics.** The `softprops/action-gh-release` step failed during the alpha.1 retag with `tag_name already_exists` after deleting + re-uploading all 5 assets; recovered by `gh api` cleanup (delete orphan draft + PATCH draft→false on the release with the real assets). Follow-up: pin the action version, add `make_latest: false` + explicit `release_id` lookup, or switch to `ncipollo/release-action` which handles retags without the duplicate-draft creation.
- **Signed binaries** — Windows EV code-signing (~$300/yr, DigiCert or SSL.com) + Apple Developer ID + notarization ($99/yr). Removes SmartScreen/Gatekeeper warnings. Prerequisite for the auto-update path.
- **npm publish** — `@hermes-relay/cli` goes to the npm registry once v1.0 is cut, enabling `npm i -g` / `npx` for Node-having users in addition to the curl-binary path.
- **HMAC verification on QR payloads** — defer until a client-accessible secret story exists (same deferral as the Android app). Not blocking GA.

**Docs + references:** user-docs `/desktop/` section (Overview → Installation → Pairing → Subcommands → Local tool routing → Troubleshooting → FAQ) with an `<ExperimentalBadge />` Vue component on every page. README.md landing has a dedicated "Experimental: Desktop CLI" section with the install one-liners.

## Current — Axiom-Labs migration

Moving the Play Store listing from a personal account to the DUNS-verified Axiom-Labs LLC org account. Unblocks straight-to-production rollout (no 14-day closed-testing requirement). New applicationId `com.axiomlabs.hermesrelay`; keystore identity + SHA256 fingerprint preserved. In progress — waiting on Google DUNS verification.

## Next — v0.4: Bridge feature expansion

Detailed plan: [`docs/plans/2026-04-13-bridge-feature-expansion.md`](docs/plans/2026-04-13-bridge-feature-expansion.md).

Expands the bridge channel's tool surface substantially, ports reliability patterns from the broader Hermes-Android ecosystem, and ships a per-app playbook skill so the agent has ready-made procedures for common apps out of the box.

**Core gestures.** Long press, drag, and pinch — foundational interactions currently missing from the toolkit.

**Screen efficiency.** Lightweight screen hashing + diff for cheap change detection in navigation loops; targeted node search (`find_nodes`) to avoid dumping full accessibility trees; detailed node introspection (`describe_node`) for richer LLM context.

**System integration.** Clipboard bridge (read + write), system-wide media playback control (play / pause / next / previous), sequential macro execution for batched workflows.

**Reliability.** Wake-lock wrapping on gesture-dispatching actions, three-tier `tap_text` fallback cascade for apps that wrap clickable content in non-clickable views, multi-window accessibility tree traversal.

**Per-app playbook skill.** `skills/android/SKILL.md` gives the LLM ready-made step-by-step procedures for Uber, WhatsApp, Spotify, Maps, Settings, and Tinder — plus a hard "do not loop" rule for bounded tool-call budgets.

**Sideload-only additions.** Location (`ACCESS_FINE_LOCATION`), contact search (`READ_CONTACTS`), direct SMS (`SEND_SMS`), and direct-dial calling (`CALL_PHONE`). All gated behind the existing sideload flavor to preserve Play Store policy compliance on the `googlePlay` track.

## Next-next — v0.4.1: Bridge fast-follows

Small follow-ons to v0.4 deliberately deferred to keep the v0.4.0 release surface focused.

**Unattended access mode** *(sideload-only).* ~~Opt-in toggle on the Bridge tab that acquires `FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP`, raises `SCREEN_OFF_TIMEOUT` to max while active, and requests `KeyguardManager.requestDismissKeyguard()` so the agent can drive the device while the user is away.~~ **SHIPPED in v0.4.1** — see [`CHANGELOG.md`](CHANGELOG.md#041---unreleased). Final shape: opt-in toggle on the Bridge tab (sideload-only) that acquires `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE` per bridge action, calls `KeyguardManager.requestDismissKeyguard()` via the registered MainActivity host, and reports `keyguard_blocked` (HTTP 423) when a credential lock blocks the action. Hard-bounded by the existing bridge auto-disable timer; persistent foreground-service notification + amber "Unattended ON" status-overlay chip stay visible while active; first-enable shows a scary dialog explaining the security model and credential-lock limitation. The original spec mentioned a WiFi-disconnect failsafe — rejected during implementation because Tailscale / VPN invalidates the "leaving WiFi = leaving LAN" assumption; the existing relay-disconnect detection (master toggle drops on disconnect → `UnattendedAccessManager.release()`) plus the auto-disable timer cover that surface.

**Voice intent local dispatch loop.** The v0.4 voice intent handler builds `bridge.command` envelopes and routes them through the `ChannelMultiplexer` → WSS → relay → back-to-phone path, which the relay correctly rejects with `ignoring unexpected bridge.command from phone` (the wire protocol is server→phone only by design). Voice intents are phone-local, so the dispatch should be local: extend `BridgeCommandHandler` with a `handleLocalCommand(envelope)` entry point that runs the existing `when(path)` dispatch + the full Tier 5 safety check pipeline (blocklist → destructive verb modal → action executor) in-process, and have `RealVoiceBridgeIntentHandler.dispatch()` call it instead of `multiplexer.send()`. Single source of truth for "bridge command → action" preserved; safety modals still fire for destructive verbs; no WSS round-trip for an action that's happening on the same device. Caught by Bailey's on-device test 2026-04-14 after the multiplexer-wiring fix unblocked the dispatch path.

**~~Tiered permission checklist with JIT permission errors~~ — shipped on `feature/tiered-permissions` (v0.4.1).** See [CHANGELOG.md](CHANGELOG.md) under `[Unreleased] → v0.4.1 Bridge fast-follows` for the landed surface. Original scope:

- Tiered checklist with sideload-only sections gated on `BuildFlavor.SIDELOAD` (Core bridge / Notification companion / Voice & camera / Sideload features), Optional pills, runtime-permission launchers, ON_RESUME re-probes — done.
- JIT permission-denied surfacing — bridge tool error envelope carries canonical `code` + `permission` aliases, Python `ResolveResult` types in `plugin/tools/resolve_result.py`, agent-tool wrappers upgrade `permission_denied` responses to structured LLM-readable envelopes, voice-mode JIT chip deep-links to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the running package — done.

**Voice intent → server session sync.** ✅ **Shipped 2026-04-16** — see [CHANGELOG `[Unreleased]`](CHANGELOG.md#unreleased) for the implementation. Picked option (d) (not in the original menu): synthesize OpenAI-format `assistant` (with `tool_calls`) + `tool` (with `tool_call_id`) message pairs from local voice-intent traces and pass them under a new `messages` field on the existing `/v1/runs` and `/api/sessions/{id}/chat/stream` payloads. LLMs are trained on this exact shape so they read it as natural conversation history rather than a system-prompt side note (lower retry risk than option (b)). Zero server changes (option (a) avoided), no double-dispatch (option (c) avoided). Idempotency via a `syncedToServer` flag on each trace.

**Original problem statement (preserved for context):** Voice intents currently dispatch in-process (good for latency) and append a **local-only** trace to chat history (good for visual continuity), but the server-side session never sees them — so the gateway LLM has no memory of prior voice actions when the user follows up via text or voice. Symptom: user says "open Chrome" via voice (works), then says "did that work?" → LLM responds "I have no prior context for what you're asking about". Caught by Bailey's on-device test 2026-04-14: "The chat is resetting on voice or with our tools?" — actually voice intents bypass chat entirely, but the user-visible effect is the same.

**Gateway slash-command preprocessor — bootstrap middleware (Option B scope).** Built-in Hermes slash commands (`/model`, `/new`, `/retry`, the 29 in `hermes_cli/commands.py::GATEWAY_KNOWN_COMMANDS`) are intercepted by in-process platform adapters like Discord, Telegram, Slack, etc. — all of which route inbound `MessageEvent`s through `GatewayRouter._handle_message` at `gateway/run.py:2645–2929`, where a ~300-line dispatch chain mutates router-owned state (`_session_model_overrides`, `_agent_cache`). But `APIServerAdapter` **does not connect to the router** — it's intentionally excluded from the router notification path (see comment at `run.py:3148`), calls `_run_agent` directly, and **creates a fresh agent per request with no persistent session state**. This is the design intent of the OpenAI-compatible endpoint, not an oversight. The practical effect: on `POST /v1/runs` and `POST /v1/chat/completions`, slash commands pass through to the LLM verbatim; the LLM hallucinates a plausible-sounding but wrong reply ("`/model` is a client-side command"); the user is confused. Caught by Bailey on 2026-04-15 during a Hermes chat test from the Android app.

**What the middleware can do (near-term, ships via install.sh).** New aiohttp middleware in `hermes_relay_bootstrap/_command_middleware.py`, installed at the same `_PatchedApplication.__setitem__` hook as the current route injection so it lands before `AppRunner.setup()` freezes the app. Filters by `request.path in ("/v1/runs", "/v1/chat/completions")` — zero-cost fast path for everything else. On chat paths: parses the body, lazy-imports `GATEWAY_KNOWN_COMMANDS` + `resolve_command()` + `gateway_help_lines()` from `hermes_cli.commands`, and splits on command type:
- **Stateless commands** (`/help`, `/commands`, and any others the upstream Option B PR ends up supporting without router state) — actually dispatch, emit a synthetic SSE stream matching the runs handler's existing event shape so the Android client at `HermesApiClient.kt:655-715` renders it as a normal assistant turn.
- **Stateful commands** (`/model`, `/new`, `/retry`, `/undo`, `/compress`, `/title`, `/resume`, `/branch`, `/rollback`, `/yolo`, `/reasoning`, `/personality`, etc. — most of the registry) — emit a synthetic SSE stream whose content is a short, helpful notice: *"The `/model` command requires a persistent session and isn't available on the stateless `/v1/runs` endpoint. Use `/api/sessions/{id}/chat/stream` (post-PR-#8556) or a channel with session state. For commands that work here, type `/help`."* This replaces the LLM hallucination with a deterministic, accurate message that points the user at the real fix.

**On no match** (unknown command, cli-only command, or plain text): falls through to `handler(request)` unchanged. Fork-detects the same way the existing injection does — if the upstream preprocessor PR lands first, the middleware no-ops.

**Ceiling.** This middleware can **never** make `/model` actually switch models on `/v1/runs`, because there is no persistent session on that endpoint to switch. That's a Phase 2 follow-up (below), not a flaw in the middleware.

**Files.** New `hermes_relay_bootstrap/_command_middleware.py` (~150 LOC), one-line append in `_patch.py` inside `_maybe_register_routes`, stdlib `unittest` coverage in `plugin/tests/test_bootstrap_command_middleware.py` mirroring the existing `test_bootstrap_patch.py` harness. Mirrors the upstream Option B PR exactly so the two can be reviewed side-by-side.

**Phase 2 — stateful dispatch on the session chat stream endpoint (post PR #8556).** Once PR #8556 merges and `/api/sessions/{id}/chat/stream` ships natively in upstream, a separate middleware (or a follow-up upstream PR) can add a preprocessor **scoped to that endpoint only**, leveraging the `session_id` in the URL as the persistence handle. At that point stateful commands become a dict write against session-scoped state — `session.model_override = new_model` — without needing to refactor `GatewayRouter` or plumb api_server into the router. Much smaller than a full router refactor, and it matches upstream's partition: `/v1/*` stays stateless, statefulness lives on `/api/sessions/*`. Blocked on #8556 landing.

## Future — v0.5+

Shape subject to change. Each theme needs a separate design + plan pass before implementation; file design notes as research matures.

### Desktop thin-client — Phase B (client-side tool routing)

v0.1 ships a remote-chat CLI. Phase B is the bigger win: **per-tool dispatch routing** so file/terminal/browser tools run against the user's machine while state tools (memory, skills, sessions, cron) stay on the server. Design detailed in the vault under `Axiom-Vault/3. System/Projects/Hermes-Relay/Desktop Client.md`. Key insertion point is hermes-agent `model_tools.py::handle_function_call()` (~line 517) — before `registry.dispatch()`, consult a session-scoped routing table populated by a relay handshake extension where the client advertises which tools it can service. Isomorphic to how `android_*` tools already flow through the `bridge.command` channel. Proposed branch: `fork/tool-relay` on the hermes-agent fork; upstream issue to open before merging. Blocked on: (a) the handshake extension in `plugin/relay/auth.py` to carry the advertised-tools list, (b) a new `desktop.command` channel mirroring `bridge.command` semantics, (c) the upstream PR conversation.

### Observability & introspection
- Real-time accessibility event streaming for reactive workflows (`android_events`, `android_event_stream`)
- On-device text-to-speech through the phone's system speaker for hands-free responses (distinct from the in-app voice mode)
- Short MP4 screen recording for visual bug reports and "show me what happens when you tap this" flows
- Annotated failure screenshots — auto-capture a screenshot with the intended target highlighted when a tap or wait fails, so the LLM can self-correct with visual context
- Generalized loop guardrails across all bridge tools — extend `android_navigate`'s `max_iterations` cap into a per-session rolling counter covering every bridge tool call
- Raw Intent / Broadcast escape hatch for power workflows

### Automation & triggers
- **Scheduled automations** — "every weekday at 7am, open Maps, check my commute, report the time." Wires bridge commands to hermes-agent's existing cron tool
- **Event-triggered actions** — "when a notification from X arrives, do Y." Reactive rule engine on top of the notification companion + accessibility event stream
- **User-recorded macros** — watch a workflow once, replay it on demand via `android_macro`
- **Multi-phone pairing UX** — explicit "add another device" flow, per-device routing for tool calls
- **Phone → server file transfer** — reverse direction of the existing inbound media pipeline ("fetch my latest photo")

### Voice assistant
- Always-listening wake-word mode (Porcupine or equivalent), off-by-default with explicit opt-in
- Phone call handling — agent answers incoming calls, speaks via TTS, transcribes incoming audio, takes messages ("answer my phone, take a message, tell them I'll call back")

### Research horizon
- **On-device local model execution** — Gemma / Qwen running directly on the phone via MediaPipe or llama.cpp, for offline fallback and hybrid routing (simple tasks local, complex tasks remote)
- **Cross-app workflow execution** with inter-step state carry-over — "find a restaurant on Maps, share it on WhatsApp, book an Uber there"
- **Web dashboard** for monitoring bridge activity server-side
- **iOS support** via Shortcuts + accessibility bridge + App Intents (evaluate feasibility before committing)
- **Developer-mode embedded HTTP server** on the phone — a Ktor/Netty server on a local port for direct-to-phone testing over USB or LAN without routing through the relay (dev ergonomics, not user-facing)

### Vision
Dedicated **"Hermes Phone"** — a device (or phone ROM) that boots straight into agent mode, where the OS itself is the agent. Long-term north star, not a concrete deliverable.

---

## How this roadmap evolves

New ideas enter via: direct proposals in GitHub issues, comparison passes against similar projects, community feedback from users and contributors, or internal research that turns into a shipped prototype.

Active work waves (like the v0.4 bridge feature expansion above) get their detailed implementation plans in [`docs/plans/`](docs/plans/). When a plan wave ships, its plan file is archived or removed and the items migrate into [`CHANGELOG.md`](CHANGELOG.md).

Have an idea? [Open an issue](https://github.com/Codename-11/hermes-relay/issues/new) — every one is read.
