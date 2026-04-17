# Hermes-Relay Roadmap

> Where Hermes-Relay is headed. Short, high-level, grouped by release milestone. For detailed implementation plans of active work see [`docs/plans/`](docs/plans/); for shipped work see [`CHANGELOG.md`](CHANGELOG.md); for the session-by-session narrative see [`DEVLOG.md`](DEVLOG.md).

## Vision

Native Android companion for the [Hermes agent platform](https://github.com/NousResearch/hermes-agent) — chat, voice, and full phone control in one app. We're building toward a world where your AI agent has safe, graceful hands on your phone for the tasks where that matters most: messaging, navigation, music, day-to-day automation, and anything else that's currently a tap-through chore.

## Shipped

- **v0.3.0** — Bridge channel (sideload), voice mode, notification companion, two build flavors, full safety rails system. [CHANGELOG](CHANGELOG.md#030---2026-04-13)
- **v0.2.0** — Voice mode foundation, terminal preview, TOFU cert pinning, Paired Devices screen. [CHANGELOG](CHANGELOG.md)
- **v0.1.0** — Chat, sessions, QR pairing, encrypted storage, Play Store submission.

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

**Unattended access mode** *(sideload-only).* Opt-in toggle on the Bridge tab that acquires `FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP`, raises `SCREEN_OFF_TIMEOUT` to max while active, and requests `KeyguardManager.requestDismissKeyguard()` so the agent can drive the device while the user is away. Hard-bounded by the existing bridge auto-disable timer, fronted by a persistent foreground-service notification + status-overlay chip, and gated behind a scary opt-in dialog that explains the security model. **Documented hard limit:** Android does not let third-party apps dismiss credential locks (PIN / pattern / biometric) — the user has to set their lock screen to **None** or **Swipe** themselves for the wake to land them past the keyguard. Without that, the screen wakes but stays on the lock screen, and the bridge gracefully reports `keyguard_blocked`. Auto-revokes when the pairing token expires or the device leaves WiFi (failsafe).

**Voice intent local dispatch loop.** The v0.4 voice intent handler builds `bridge.command` envelopes and routes them through the `ChannelMultiplexer` → WSS → relay → back-to-phone path, which the relay correctly rejects with `ignoring unexpected bridge.command from phone` (the wire protocol is server→phone only by design). Voice intents are phone-local, so the dispatch should be local: extend `BridgeCommandHandler` with a `handleLocalCommand(envelope)` entry point that runs the existing `when(path)` dispatch + the full Tier 5 safety check pipeline (blocklist → destructive verb modal → action executor) in-process, and have `RealVoiceBridgeIntentHandler.dispatch()` call it instead of `multiplexer.send()`. Single source of truth for "bridge command → action" preserved; safety modals still fire for destructive verbs; no WSS round-trip for an action that's happening on the same device. Caught by Bailey's on-device test 2026-04-14 after the multiplexer-wiring fix unblocked the dispatch path.

**Tiered permission checklist with JIT permission errors** *(sideload-only sections gated on `BuildFlavor.SIDELOAD`).* Extend `BridgePermissionChecklist.kt` from the current 4-row design to a tiered surface with explicit grant affordances for every dangerous permission Hermes-Relay actually declares:

- **Core bridge** (both flavors) — Accessibility, Screen Capture, Overlay, **Notifications (Android 13+, currently missing)**
- **Notification companion** (both flavors, optional) — Notification Listener
- **Voice & camera** (both flavors) — Microphone, Camera
- **Sideload features** (sideload-only, optional) — Contacts, SMS, Phone, Location

Each row uses `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission)` for runtime grants and `ACTION_APPLICATION_DETAILS_SETTINGS` deep-links for special perms. Status re-probes on `Lifecycle.Event.ON_RESUME` (same pattern as the existing 4 rows). Optional perms get an "Optional" badge so users don't feel pressured to grant the full set.

**JIT permission-denied surfacing.** When a tool fails because of a missing runtime permission (e.g., `resolveContactPhone` returns null because `READ_CONTACTS` is denied), the failure path returns a structured `permission_denied` error code instead of generic "I couldn't find...". The voice flow speaks **"I need Contacts permission to look up Sam — open Settings to grant"** with a tap-target that deep-links to the app's permission page. The chat flow surfaces the same structured error to the agent so the LLM can recommend the fix in plain language instead of hallucinating about why the tool failed. Requires (a) splitting the resolver return type into a `sealed class ResolveResult<T> { Found / NotFound / PermissionDenied(perm, reason) }`, (b) adding a `code` field to bridge tool error envelopes, (c) the agent tool wrapper interpreting `code: permission_denied` and feeding it back to the LLM with context.

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
