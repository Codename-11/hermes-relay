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
