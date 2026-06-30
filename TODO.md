# Hermes-Relay — TODO

Open items that don't fit a formal Phase plan but shouldn't be lost. Items move from here into a Plan in `docs/spec.md` or an Obsidian Phase plan once they're ready to schedule.

For shipped work, see `DEVLOG.md`. For architectural decisions, see `docs/decisions.md`.

---

## Connections UI / status banner (2026-06-30 restructure follow-ups)

The Connections screen was split into a scannable list + a tabbed detail screen
(Overview / Routes / Advanced / Security), and the non-error status banner became
take-space + animated + dismissible (see DEVLOG 2026-06-30). Deferred:

- **Resume suppression covers only the handoff path.** `recordConnectionHandoff`'s
  reconnect is now withheld during the foreground-resume grace window, but the
  *health* producer (`buildGlobalConnectionStatus` → "Checking Hermes connection" /
  "Connecting to Hermes") can still flash a brief banner on resume. Copy is
  accurate, so left as-is; if it reads as noisy, extend the same just-resumed
  guard to the health snapshots.
- **Non-active connection detail is Overview-only.** Routes/Advanced/Security tabs
  appear only for the active connection (they read the single active-connection VM
  state); a non-active connection shows a "Switch to this connection" CTA. A future
  read-only preview of a non-active connection's saved routes could be nice.
- **Store screenshot regeneration.** The `07_connections` scene mock was updated to
  the new list design; confirm the regenerated PNG + Play-graphics export at
  release-prep (only auto-publishes on a `main` release merge).

## Phone as a Hermes platform (proactive agent → phone)

Phase 1 (end-to-end spine) shipped on `Codename-11/phone-platform` — `send_message target=phone` → loopback `/phone/message` → relay `ProactiveChannel` → phone WSS → system notification, gated off by default (`PHONE_ENABLED` server-side + "Let Hermes message me" app-side + pairing). Remaining:

- **Phase 2a — dedicated "Hermes" inbox surface.** An always-present inbound conversation/section for agent-initiated messages (reuse chat *rendering* components, do NOT restyle — chat-ux worktree owns visuals). Land proactive messages there in addition to the notification. `ProactiveMessageHandler.onReceived` + `dispatch()` are the seams already in place; key the surfacing on `ProactiveMessage.surfacing` (notification / inbox / session / default = notification + inbox). Needs a small persistence store + a nav entry.
- **Phase 2b — session injection.** Deliver a proactive message into the relevant/active chat session (continue that conversation) when `surfacing == "session"`. Keep the `ChatViewModel` change SMALL/localized (one injection entry point) to avoid conflicting with the chat-ux branch.
- **Phase 2c — two-way reply. SHIPPED + DEVICE-VERIFIED (2026-06-29).** All three legs landed: (1) inline-reply notification (`RemoteInput` + `ProactiveReplyReceiver`) + a reply box in the Hermes inbox; (2) `proactive.reply` envelope (app→relay) buffered by `ProactiveChannel` + a loopback `GET /phone/replies` long-poll; (3) `PhoneAdapter.connect()` inbound loop drains `/phone/replies` → `handle_message()` so the reply continues the originating conversation (keyed by `chat_id`/`reply_to`), and the agent's answer rides the existing `send()` back. Inbound source is `role_authorized=True` (the relay pairing layer is the auth boundary), so replies work without `PHONE_ALLOW_ALL_USERS`. Verified via `python -m unittest` (proactive + phone tests) and `./gradlew :app:lint`, then **end-to-end on-device (2026-06-29)** after two faults the device test surfaced (see DEVLOG): `PhoneAdapter.connect()` was missing the `is_reconnect` kwarg the gateway passes (→ `TypeError`, adapter never connected, reply loop never polled); and a stale duplicate plugin copy in the user-plugins dir was winning the loader's name-dedup, so the gateway loaded old code and ignored every deploy. Residual deferred (below): outbound buffering / a persistent send-when-reconnected queue (currently the agent's answer `503`s and is **lost** if the phone's subscription dropped); inbound media in replies (text-first in v1).
- **Phase 3 — full controls.** DataStore-backed `ProactivePreferences` expanding `data/ProactivePrefs.kt`: quiet hours / DND (suppress or defer), per-profile push scoping, rate limiting (debounce/cap), and TTS-on-voice (route to the existing voice player API when a voice turn is active — call, don't modify, the voice path). Surface on the existing `ProactiveSettingsScreen`. Also: a persistent outbound-reply queue so a reply typed while the relay is disconnected (notification inline-reply in a killed process, or a dropped WS) is sent on the next connect instead of dropped.

**Maintainer verification (live box + device — can't be done off-device):**
- Live gateway must discover the plugin (`~/.hermes/plugins/hermes-relay` → `plugin/`) and `plugins.enabled` must include `hermes-relay` for the `phone` platform to register. Confirm `phone` appears in `hermes gateway status` with `PHONE_ENABLED=1`.
- End-to-end: with the app paired + "Let Hermes message me" on, run `send_message target=phone text=...` (and a cron `deliver=phone`) and confirm a notification on the device. Verify 503 (no phone) and the off-by-default gates.
- **Phase 2c reply round-trip — ✅ DONE (verified on-device 2026-06-29).** Confirmed: agent → phone notification → inline reply → drained through the relay's loopback `GET /phone/replies` (different process) → `handle_message` (`role_authorized=True`, no `PHONE_ALLOW_ALL_USERS`) → agent answer back in the *same* thread. Both fixes required (see DEVLOG / the Phase 2c bullet above).
- **FIX: cron `deliver=phone` / standalone send is broken.** Live testing: `hermes send --to phone` returns `{"error": "Unknown platform: phone"}`. The standalone (non-gateway) send path doesn't run a `kind=standalone` plugin's programmatic `ctx.register_platform`, so it never learns `phone` — only the running gateway (which loads `register()` at startup) does. The agent path (`send_message target=phone` in the gateway) works and was verified end-to-end on-device; the standalone/cron path needs the platform discoverable there too (declare it so the standalone loader picks it up, or route cron through the gateway). Until then `cron deliver=phone` won't work.
- **FIX: installer leaves stale plugin backup copies in the plugins dir (root cause of the 2026-06-29 round-trip failure).** `install.sh`'s plugin-clone rebuild backs the old copy up *inside* `~/.hermes/plugins/` (e.g. `hermes-relay.copy-backup-…`). Because the loader dedups discovered plugins by manifest `name` and both copies declare `name: hermes-relay`, the backup can win the dedup and the gateway loads stale code — so every later deploy is silently ignored. Fix: back up *outside* the plugins dir (or delete the old copy), and have `hermes relay doctor` warn when more than one directory under `~/.hermes/plugins/` resolves to the same plugin `name`.

## Phone platform — usability roadmap (post device-verification, 2026-06-29)

**North-star (2026-06-29): the phone should replace Discord-on-the-phone for agent contact.** The agent lane is meant to be a place you live in — proactive messages land, you reply inline or open a real thread, you multitask in and out of it like a chat app. That framing (not "an inbox of notifications") drives every item below: it must feel like a first-class messaging surface, attributed as its own gateway lane, with the conversation persisted and continuable.

**Decision (2026-06-29): "separate lanes, unified surface."** The phone/agent conversation stays its own **gateway-platform lane** — distinct from the Standard Chat tab, which must keep working on vanilla upstream Hermes with no plugin — but is surfaced as a **first-class chat-style thread** that reuses the chat UI and sits alongside Chat. NOT a Chat "transport": a transport is an interchangeable pipe for the *same* user-chat conversation; the phone platform is a *different* conversation (agent-initiated, own session store/attribution, relay auth), so treating it as a transport miscategorizes it and couples a standard surface to a relay-only capability.

**Refinement (2026-06-29) — unified-session model: "Threads."** Going further on "unified surface": the agent conversation is **not a separate tab/segment** at all — it is a **source-tagged session inside the one Chat surface**, a **Thread** (`source=phone`). What makes a Thread special vs. a normal gateway chat are *session properties*, not a separate UI: (a) the agent can initiate, (b) relay `proactive` transport + relay-gated, (c) standing/named DM. **Scrollback = the gateway session store** (same read path Chat uses); **live receive = relay `proactive` push** (→ notification); **send = `proactive.reply`**. `ProactiveInboxStore` is demoted to a live-push cache + outbox (no parallel history). The Thread capability shows in the **best-path/capability UI** (relay tier, like terminal/bridge/voice) and as a clean **Threads** entry — thread-spool icon, NOT a phone glyph — pinned atop the session drawer when active; never a connection-wizard step. Degrades cleanly (no plugin → no `source=phone` sessions → Chat unchanged). **Supersedes the "separate Agent lane / 4th nav segment" sketch** and merges with the "source attribution in Chat" goal below. Keep the two "gateway" senses straight: *platform layer* (the Thread's `source`) ≠ *dashboard `/api/ws` transport* (how live bytes flow). Full re-cut: docs/decisions.md ADR 12.

- **Outbound buffering — ✅ relay-side DONE (2026-06-29).** `ProactiveChannel.push()` now queues agent→phone messages in a bounded deque (drop-oldest, 24 h TTL) when no phone is subscribed and returns `{queued: true}` (not 503); `_flush_outbound` delivers FIFO on the next subscribe (stale pruned). Inspect/cancel via `peek_outbound`/`cancel_outbound` + loopback `GET`/`DELETE /phone/outbound`. **UI surfacing of the queued state** (host-side, since the queue exists while the phone is OFFLINE): (a) ✅ **desktop CLI `relay queue` / `relay queue --clear` / `--cancel <id>` DONE (2026-06-29)** over the new endpoints (loopback-only — run on the relay host); a dashboard Relay-tab view is the optional GUI equivalent; (b) **remaining** — in the threaded agent surface, mark messages that arrived-while-away, and show the user's OWN pending replies (the Phase 3 reply queue) with a sending/Cancel affordance — that's where phone-side "queued + cancel" belongs.
- **Threads surface (unified-session model — see ADR 12 + the Refinement above).** Build order, each shippable: **(1)** source tags in the session drawer (`source=phone` → clean **Threads** chip + thread-spool icon, NOT a phone glyph) — also delivers the "source attribution in Chat" goal; **(2)** open a Thread in Chat from its session-store history (reuse the existing message-history path); **(3)** route the live `proactive` push into the session view + notification + unread, demoting `ProactiveInboxStore` to cache/outbox; **(4)** reply from the Chat composer via `proactive.reply` + persist the user turn + local `Sending/Queued/Failed` status — **MVP**; **(5)** a **Threads capability row** in the best-path UI + a pinned **Threads** entry atop the drawer (thread-spool icon, shown only when relay-paired + opted-in) + retire `HermesInboxScreen`, re-point the notification deep-link + Settings "View messages"; **(6)** outbox/retry on reconnect; **(7)** relay `proactive.reply.ack` (honest Delivered) + `proactive.cancel`; **(8)** multi-thread `chat_id` (named/project Threads). **Verify gate before (1):** confirm the app's session-list/history path surfaces a `source=phone` session cleanly (upstream `session.list` returns all sources flat, so it should — but check whether the drawer currently filters it out). Honesty call: do NOT show "Delivered" until (7) lands (can't confirm it client-side before the ack).
  - **Status (2026-06-29, implemented UNBUILT — verify in Studio):** **CODE-COMPLETE on `dev`:** slice **1** (drawer source tags + `ThreadSpoolGlyph` + Threads filter), **2** (open a Thread from history — free via the existing `loadSessionHistory` path), **3-parse** (carry `reply_to` on `ProactiveMessage`), **4** (composer reply in a `source=phone` session routes over `proactive.reply`; `MessageDeliveryStatus` SENDING→DELIVERED/FAILED on the bubble), **5** (Threads capability row in `SessionPathCard` + `threadsCapabilityActive` drawer wiring), **7** (relay `proactive.reply.ack` + `proactive.cancel` — 25/25 `unittest` green — and client ack handling). **DONE since (2026-06-29, built + on phone):** live **in-thread reply rendering** (an agent reply lands in the open Thread as an ASSISTANT bubble, suppressing the notification/inbox — `injectIntoThread`); **user-created named Threads** ("+ New Thread"); **retire `HermesInboxScreen`** (deleted; route + nav removed; notification tap + Settings "View messages" re-pointed to Chat; surface renamed "Hermes messages" → **"Threads"**); relay slice-7 ack/cancel **DEPLOYED** to the host so **"Delivered" is live**. **DEFERRED (reasons):** per-session **unread badge**; **outbox/retry** (needs multiplexer connection-state); **exact-Thread deep-link** from the notification (opens Chat today, not the specific thread — needs select-session-on-entry); **remove the now-orphaned `ProactiveInboxStore`** (viewer-less write-only log); **agent-initiated** named Threads (upstream `send_message` thread param). On-device verifies for the create-flow: fresh-`chat_id` auto-create, the `…:dm:<chat_id>` id form, `renameSession` on a phone session.
  - **User-created Threads (slice 8, Discord-style) — CODE-COMPLETE on `dev` (built + installed 2026-06-29; on-device behavior pending).** "+ New Thread" in the drawer's Threads view → name dialog → `ChatViewModel.startNewThread` mints a fresh `chat_id`; the first composer message opens it over `proactive.reply` (gateway auto-creates the `source=phone` session) → `switchToCreatedThread` polls + switches to the real session + applies the name. Existing-thread replies route by the `chat_id` parsed from the session id (`…:dm:<chat_id>`; opaque id → home fallback). **On-device verifies:** (1) a fresh-`chat_id` no-`reply_to` inbound creates a new `source=phone` session; (2) the phone session id carries the `…:dm:<chat_id>` form the client parses; (3) `renameSession` titles a phone session. **Remaining slice-8:** AGENT-initiated named Threads (the upstream `send_message` thread/chat_id param so the agent can open its own named Threads).
  - **`chat_id` not exposed by `/api/sessions` (root cause of the 2026-06-29 on-device create-flow bugs — fixed client-side).** Confirmed on the host: a phone session's `id` is a timestamp (e.g. `20260629_204755_94f391d6`); the real `chat_id` lives in the `session_key` (`agent:main:phone:dm:<chat_id>`) and a `chat_id` column — but `/api/sessions` returns **neither `chat_id` nor `session_key`**, only `source` + the timestamp `id`. So the client could not map a session ↔ its `chat_id`, which broke create-thread switch/rename + reply routing + in-thread injection. **Client workaround shipped:** find a created thread by session-list **diff** (the new `source=phone` session), keep an in-memory `sessionId → chat_id` map (learned at creation + from incoming `phone.message`s) for reply routing, and inject by source (+ learned chat_id) rather than a parsed id. **Limitation:** for a thread the app didn't create *this* session (agent-created, another device, or after an app restart) `chat_id` is unknown until a message arrives while viewing it → its replies fall back to the home channel until then. **RESOLVED via the plugin (2026-06-29, per upstream-or-plugin policy):** the relay now exposes `GET /phone/threads` (`plugin/relay/session_store.py` reads the gateway store read-only → `[{session_id, chat_id, title}]`; `server.py` `handle_phone_threads`, bearer for the app / loopback for diag; 5 unit tests). The app (`RelayHttpClient.fetchPhoneThreads` → `ConnectionViewModel.phoneThreadChatIds` on every `auth.ok` → `ChatViewModel.seedThreadChatIds`, authoritative over the learned map) now routes replies correctly for **any** Thread — incl. ones it didn't create + after restart. Deployed + verified live. **Still-nice-to-have (lower priority): the upstream PR** to add `chat_id`/`session_key` to `/api/sessions` (the standard-path proper fix; the relay route then becomes redundant + the client prefers upstream when present).
- **Threads as named/project conversations (Discord-parity — folds into multi-thread #8).** A stable *named* `chat_id` per project = a persistent, agent-reachable project Thread (Discord named-thread parity for "persist a session for a project"). Enables: the agent **opening** a new named Thread for a background job/topic (a relay/gateway "open thread" affordance + a `send_message`-adjacent tool); cron/job updates landing in their own Thread; and replying to a Thread from any surface (desktop CLI / dashboard) since it is just a gateway session. Also evaluate per-Thread profile binding (a project Thread uses the "work" profile — ties to profile=contact).
- **Thread vs. normal gateway chat — keep complementary, don't force one.** A Thread is a gateway chat + proactive delivery + `source` attribution. Use a *normal* gateway chat for live foreground interactive work (live `reasoning.delta` over `/api/ws`); use a *Thread* for persistent/named/agent-reachable/background-delivered conversations. Possible future enhancement (verify first): when a Thread is open in the foreground, allow a live `/api/ws prompt.submit` turn into that `source=phone` session for live reasoning — but confirm it does NOT break platform attribution or the proactive reply loop before relying on it; the proven send path stays `proactive.reply`.
  - **LOOK INTO (own item, owner-requested 2026-06-29): live `/api/ws` transport for a foregrounded Thread.** Goal: when a Thread is open in the app foreground, give it the *same* live experience as Chat (live `reasoning.delta` + tool-progress) by running the turn over the `/api/ws` dashboard-gateway transport into that `source=phone` session, instead of the notification-grade `proactive.reply` path. Spec the experiment: (1) does `session.resume` + `prompt.submit` on a `source=phone` session over `/api/ws` keep `source=phone` (not silently re-tag `tui`)? (2) does it bypass `PhoneAdapter` / the role_authorized reply loop, and does that matter when the user is the one typing? (3) reconcile the two send paths (foreground→`/api/ws`, background/notification→`proactive.reply`) without double-sends. If it holds, a Thread becomes "background-delivered like a DM, but live like Chat when you open it" — the best of both. Until verified, `proactive.reply` stays the only send path.
- **Docs/user-docs for Threads (lockstep — author with the user-facing slices 4–5).** Dev refs are done (ADR 12 carries the unified-session decision + the two-"gateway" split). Still to write when the surface ships: a plain-language `user-docs/features/threads.md` — what a Thread *is*, **Chat vs Threads** (live foreground work vs. persistent, agent-reachable conversations), the two opt-in gates, that it's relay-only — plus a **brief in-app explainer** (e.g. a one-line hint on the Threads filter empty state or a small info affordance, not a wall of text), and `docs/relay-protocol.md` + relay-server route docs for the wire. Replace the stale user-docs "Coming Soon → Push Notifications" row; keep it distinct from the clipboard inbox and the inbound Notification Companion.
- **More Threads fold-ins (capture now, build with the relevant slice).** (a) **Read-state back to the agent** — tell the gateway you saw a proactive message (Discord-style read receipt) so the agent knows; fold into the `proactive.reply.ack` design (#7). (b) **Cross-surface reply** — because a Thread is just a gateway session, a reply could come from the desktop CLI / dashboard too, not only the phone; near-free once unified, verify the reply routing. (c) **Priority/importance on a proactive message** — let the agent mark urgent vs FYI → notification importance / quiet-hours bypass; small payload field + maps to the notifier channel.
- **Per-thread `chat_id`.** Everything is hardcoded `chat_id="phone"` (one thread) today; the adapter already plumbs `chat_id`, so varying it yields multiple threads (per topic, or the agent opening distinct conversations). Ties into the threaded surface.
- **Message status + delivery state.** Surface sent / delivered / queued / failed per message in the thread (depends on outbound buffering's queued state) so the user knows whether the agent actually reached them.
- **Auto-title the phone thread** like other sessions (first confirm whether the gateway already auto-titles platform sessions; wire it through if so).
### Discord/Telegram replacement — capability gaps (to fully retire reaching for them)

The gateway-platform model is the *correct + sufficient architecture* (the phone is a registered platform peer, so anything that routes to a platform — `send_message`, cron `deliver=`, channel directory, background jobs — can reach the phone). These are the concrete gaps between "architecturally a peer" and "I never open Discord":

- **Guaranteed background delivery (the biggest gap; no push today).** Delivery is **live-WSS-only** + a 24 h relay buffer; there is **no FCM/UnifiedPush** wake-up. If the app process is dead AND not holding a socket, a message waits for the next reconnect, and the relay buffer is ephemeral (lost on relay restart). Discord/Telegram feel instant because they wake the device via push even when the app is dead. Decide a **push transport**: **UnifiedPush/ntfy** (recommended — self-hostable, no Google dependency, upstream *already* ships an `ntfy` platform, on-brand for self-hosted) vs **FCM** (simplest UX but adds Play Services + a push relay; clashes with self-hosted ethos — at most the `googlePlay` flavor) vs **persistent foreground keep-alive service** holding the relay WSS (zero new infra, like `GatewayKeepAliveService`, but battery cost + Doze-fragile). Likely: UnifiedPush primary + foreground-keepalive fallback.
- **Cron / background-job delivery is BROKEN** (already tracked above): `deliver=phone` standalone path → `Unknown platform: phone`. This is load-bearing for "receiver of crons/background jobs" — fix is required, not optional, for the replacement goal.
- **Multi-thread is wired-for but never varied** (already tracked: per-thread `chat_id`). For real DM/channel parity the agent must *open distinct threads* (vary `chat_id` per topic/job), the app must render a **thread list** (N conversations, not one), and replies route back by `chat_id`+`reply_to` (already plumbed).
- **Durable history / scrollback.** The relay buffer is ephemeral; a real messaging surface needs persisted scrollback. Read the gateway **session store** for the `phone` platform's history (relay-exposed read path) so reopening a thread shows the full conversation, not just buffered-while-away.
- **Profile = contact mapping (new idea, fold in).** Multiple Hermes **profiles** (distinct agent personas/configs) could each be a distinct thread *source*/"contact" — DMing different agents. Maps cleanly onto the per-thread `chat_id` + source-attribution work; lets the app feel like a contact list of agents.
- **Per-thread notification controls + deep-link (Discord-parity affordances).** Per-thread notification channels, mute/DND/quiet-hours (Phase 3 partially), and a notification that **deep-links into the exact thread** (tap → land in that conversation) so dipping in/out while multitasking is frictionless.
- **Agent-initiated rich content.** Agent → phone thread with **images/cards** (relay media infra + `InboundAttachmentCard`/`HermesCardBubble` already exist on the chat side — reuse). Inbound (phone → agent) reply media stays deferred (text-first), but outbound rich content is low-cost parity.
- **In-thread "agent is working" indicator.** A typing/working state in the thread while the agent thinks/runs tools (Discord typing-dots parity) — the chat surface already has thinking indicators to reuse.

- **Source/platform attribution + filtering in the drawer (NOW READY — owner-requested 2026-06-29; the gateway/Threads surface has shipped).** `/api/sessions` DOES expose `source` (confirmed live: `tui`, `cli`, `api_server`, `web`, `discord`, `telegram`, `cron`, `webhook`, `phone`). Build: **(a)** a clean **source badge** per session in the drawer — phone → the thread-spool (done); discord / telegram / cron / webhook / web → a small per-platform chip/icon (match hermes-desktop's convention); the app's own `tui`/`api_server` chats get no badge (or a subtle one). **(b)** a **filter** (drawer dropdown) to show/hide sources. **(c)** a **setting** (Chat settings) for the default — **hide the agent's other-gateway/automation sessions (cron / webhook / discord / telegram) by default** so the drawer shows just your chats + Threads, with a toggle to reveal them (the live default `state.db` is full of cron/discord/webhook noise). Persist the visibility prefs. Can't see the official desktop (no clone) — infer its chip styling; match exactly if specifics surface. Standard-path: read-only display of the upstream `source` field. Fold cross-restart **Thread-name persistence** (currently in-memory) into this drawer pass.
- **Beta-gate the Threads featureset (owner direction 2026-06-29).** Mark Threads **Beta** with a clean badge in the UI (the Threads filter chip + the best-path "Threads" capability row) until the enhancements land. Full (non-beta) release is gated on: **live `/api/ws` transport for a foregrounded Thread** (an open Thread streams like Chat — the headline), per-session **unread**, the **`chat_id`-on-`/api/sessions` upstream fix** (so threads route after restart / cross-device), and **outbox/retry**.

## Voice — Standard-path parity follow-ups

- **On-device verification of the Server voice config card.** Static read + unit tests cover the client/merge logic, but the card's render, provider-scoped field switching, the ElevenLabs picker (key-present and `available:false`), and a live save round-trip against a real dashboard still need an on-device pass. Confirm a save reaches `config.yaml` and the next voice turn reflects it.
- **Generic Manage "Config" tab is still read-only.** This work added a *voice-scoped* editor; the Manage Config tab still renders `/api/config/schema` as two non-editable rows (`fields`, `category_order`). A full schema-driven editor for all categories (general/agent/terminal/…) grouped by `category_order`, GET-merge-PUT-whole, is a separate, larger task if we want full desktop Config parity.
- **`silenceThresholdMs` default change (3000 → 1250 ms) is user-facing.** Confirm on-device that 1.25 s end-of-speech doesn't clip slow speakers in real use, and that the new 12 s idle/no-speech auto-close (which now also applies to Tap-to-talk — previously "wait forever") feels right. Easy to revert the default if too aggressive.
- **Standard voice config targets the launch-profile config (`profile = null`).** Standard voice is host-global, so the editor writes the base `config.yaml`. If a user runs a non-default *launch* profile, revisit whether to scope the config write to the active profile (the dashboard `/api/config?profile=` supports it).
- **CLI `voice.*` config not surfaced.** The editor covers `tts.*`/`stt.*`; the separate `voice.*` block (record_key, beep_enabled, the CLI's own silence_threshold/duration) is intentionally out of scope — surface it only if a phone use-case appears.
## Chat UI refresh — follow-ups

- **`/font <name>` slash command (optional, deferred).** The chat-UX brief floated a chat slash command mirroring the Appearance Font picker. Deferred to keep the work inside the chat-UI/theme lane: it needs the command intercepted in the chat send path (`ChatViewModel`) before it forwards to the server, plus a `SlashCommand` palette entry. The settings picker is the primary, shipped surface. Add `/font` later as a thin wrapper over `ConnectionViewModel.setAppFont`, discoverable via the slash palette.
- **On-device verification of the chat refresh.** The Roborazzi harness proves layout + that Inter/Nunito load as distinct faces host-side, but the final typeface crispness and feel (avatar size, bubble width, density on a real Samsung) are a maintainer on-device gate. Confirm the variable-font weights (400/500/600/700) resolve on-device and Inter reads clean at body sizes.
- **Growing the font set.** The `AppFont` registry is open — add more OFL/Apache faces (e.g. a serif or a display mono) by dropping a TTF into `app/src/main/res/font` and adding one enum entry; keep the license text in `licenses/`.

## Crash-class follow-ups

- **Audit remaining throwing URL-build sites for the "Invalid URL host" class (#131).** The #131 fix guarded the two clients that take a user-entered base URL on the Manage/voice path (`DashboardApiClient`, `StandardHermesVoiceClient`) and validates input at entry, but two lower-risk site groups still call okhttp's throwing `url(String)` / `.toHttpUrl()`:
  - `HermesApiClient` streaming methods (`sendChatStream` / `sendCompletionsStream` / `sendRunStream`) build `authRequest("$baseUrl/…")` *outside* the surrounding `try`. Latent only — the non-streaming methods (incl. `checkHealth`) already `try/catch`, so a bad `apiServerUrl` is caught and marks the connection unreachable before streaming is reached. Consider a non-throwing `authRequestOrNull()` chokepoint → `onError`.
  - Relay clients (`RelayHttpClient`, `RelayProfileInspectorClient`, `RelayVoiceClient`, `ConnectionManager`) use `.toHttpUrl()` on `$httpBase/…`. These ride post-pairing relay URLs (from a signed QR / pairing payload), not free-text fields, so the input-validation layer doesn't cover them — route them through `ServerAddress`/`toHttpUrlOrNull` for defense-in-depth.

## Session titles (#133) — follow-ups beyond the client fixes

The client-side mitigations shipped (see DEVLOG 2026-06-27): the `updateSessions` clobber guard, the post-turn title reconcile (gateway), and the subtle "not auto-named here" drawer note on SSE. These two are the larger follow-ups:

- **Upstream PR: auto-title on the api_server surface.** `APIServerAdapter._run_agent` (`gateway/platforms/api_server.py:3492`) calls `agent.run_conversation(...)` and returns without ever invoking `agent.title_generator.maybe_auto_title` — so `/api/sessions/*/chat[/stream]`, `/v1/runs`, and `/v1/chat/completions` never auto-name sessions (only the gateway/tui_gateway → cli.py path does). Mirror the gateway call site (`gateway/run.py:15493`): after a successful first exchange, fire `maybe_auto_title(self._ensure_session_db(), session_id, user_message, final_response, history, main_runtime={...})` in the existing thread-executor return path. Standard-path rule applies — it's an upstream contribution; our client degrades gracefully until it merges. This is the proper fix for the SSE-surface half of #133.

- **Relay-side patch (interim, until the upstream PR lands).** Because the phone's SSE chat hits the upstream api_server **directly** on `:8642` (not through the relay on `:8767`), the relay can't intercept the turn to title it inline. Options to evaluate:
  - A relay background reconciler that periodically scans the shared `state.db` for untitled sessions with ≥1 exchange and titles them via the same auxiliary-LLM logic (`agent.title_generator.generate_title`) — essentially running upstream's titler out-of-band. Lowest client impact, but couples the relay to the session DB schema.
  - A relay `/sessions/{id}/title` helper the client can POST after an SSE turn to request server-side generation, keeping the LLM call (and key) server-side. More explicit, needs a client call.
  - Decision gate: prefer the upstream PR; only ship a relay patch if upstream review stalls. Keep it behind the relay (never the Vanilla Hermes path).

- **(Separate feature — DROPPED 2026-06-27) Client-side title generation via the main LLM.** Idea: when a session still lacks a server title after its first turn, have the app ask the main model for a 3–7-word title and persist it via `renameSession`. **Dropped because there is no client-reachable LLM endpoint that doesn't persist a session** — which would put phantom title-generation sessions in the drawer/history (the explicit no-go):
  - `/v1/chat/completions`: when no `X-Hermes-Session-Id` is sent, the server *derives* a session_id from the prompt fingerprint (`_derive_chat_session_id`) and `_create_agent` runs with `session_db=_ensure_session_db()` → the turn persists. 1 user + 1 assistant msg passes the drawer's `min_messages=1` filter → phantom row.
  - `/v1/responses` with `store:false`: `store` only governs the in-memory response-chaining store; `session_id = stored_session_id or uuid4()` is still passed to `_run_agent`, so it *also* persists a session row.
  - Reusing the chat's own session id would append the title prompt/response to the real conversation history — worse.
  - Why upstream is clean: `agent/title_generator.py` calls `auxiliary_client.call_llm` directly (raw provider call with the server's keys, no session machinery). The phone has neither provider keys nor a non-persisting endpoint, so it can't replicate that.
  - **Correct home = server-side** (the upstream api_server titler PR above, or the relay-side titler). A create-then-delete hack on the client (read `X-Hermes-Session-Id`, then `DELETE`) is fragile/racy and still flashes a row — not worth it. Revisit only if upstream ever exposes a non-persisting utility-completion endpoint.

- [x] **Session rename now profile-scoped on the gateway (fixed 2026-06-27).** Added `DashboardApiClient.renameSession`/`patchJsonObject` + `ConnectionViewModel.renameProfileScopedSession` + `ChatViewModel.profileSessionRenamer` (wired in `RelayApp`); `renameSession` routes through it when `streamingEndpoint == "gateway"`, falling back to the unscoped api_server PATCH otherwise. Verify on-device: rename a session on a non-default profile and confirm the title survives a drawer refresh / app restart.
  - **Profile-scoping audit result (2026-06-27):** rename was the *only* remaining gap. List (`profileSessionLister`), messages (`profileMessageLoader`), and delete (`profileSessionDeleter`) are already scoped; create on the gateway goes through `session.create` over `/api/ws` (inherently profile-correct); the SSE create-path auto-title PATCH (`ChatViewModel:2692`) targets the shared api_server DB where there are no profiles, so unscoped is correct; `/branch` is a server-side slash command. No further client-side session ops bypass profile scoping.

## User-Added:

- [x] **Clean-chat: taller scrollable text viewport** *(impl 2026-06-22, orchestration batch — unbuilt; verify in Studio.)* Replaced the fragile `screenHeightDp*0.34f` cap with a weight split (sphere `weight(1f)` / flow `weight(1.1f)` ≈ 52% of the vertical slack); kept the internal scroll + top-fade + `min=96.dp` floor. `AgentTextFlow.kt` (`1dca285`).
- [ ] Verify profile selection retains voice config selections in all voice modes/configuration combinations - enhance UI/configurability/management for this.
- [x] **Session delete on a non-default profile now persists** *(impl 2026-06-22, orchestration batch — unbuilt; verify in Studio.)* Root cause: a non-default profile's sessions live in that profile's own `state.db`, but the delete went through the unscoped api_server `DELETE /api/sessions/{id}` (shared DB) so the row survived and the next profile-scoped list resurrected it. Fix routes gateway deletes through the dashboard profile-scoped surface (write twin of the list path) + `refreshSessions()` after success. `DashboardApiClient`/`ConnectionViewModel`/`ChatViewModel`/`RelayApp` (`6552566`).
- [x] **Voice-settings profile override in 'auto' mode** *(impl 2026-06-21, orchestration batch — unbuilt; verify in Studio. See DEVLOG + "Orchestration batch (2026-06-21)" below.)* Root cause: `VoiceViewModel.shouldPreferRealtimeVoice()` gated on `.route` (configured) not `.effectiveRoute` (resolved), so 'auto'+relay never engaged the override-capable relay path and fell back to host-global Standard `/api/audio/speak` (no override slot). Fixed + wired `connectionId` for per-profile voice-prefs namespacing. Original note: *Look into the voice-settings profile specific capabilities - in 'auto' mode the user-override voice wasn't applied (system default used) despite being displayed; only 'Relay' applied it.*

- [x] **Analytics + Diagnostics overhaul** *(impl 2026-06-22, orchestration batch — unbuilt; verify in Studio.)* Diagnostics is now a full-screen `DiagnosticsScreen` (new `Screen.Diagnostics` route, replacing the modal sheet) led by a vertical status-check timeline — Network, API server, capabilities, chat transport, pairing/auth, relay, voice — each a green/amber/red/gray dot on a connecting rail with an inline failure reason; checks backed by a logged error are tappable into `DiagnosticDetailDialog`. Derived read-only from existing `ConnectionViewModel` flows + recent `DiagnosticsLog` via a pure `buildStatusChecks()`; recent-activity log kept below. Analytics hierarchy tidied. `c3098a9`. See follow-ups below.
- [x] **Realtime voice stall + over-chatty status** *(client half impl 2026-06-21, orchestration batch — unbuilt; server half deferred, see below.)* Client now relaxes the 90s idle watchdog on promoted/long runs (5-min backstop kept) and throttles spoken status (≥22s gap, ≤3/turn); realtime waveform now gates on real playback-start. Original note: *Realtime voice mode stalls/times-out when calling a background Hermes task and repeatedly reports status vocally when not necessary.*
- [x] **Connections reframe: "Vanilla/Standard Hermes" → "Hermes"** *(impl 2026-06-22, orchestration batch — unbuilt; verify in Studio.)* 28 user-facing display strings across 10 connection/voice/permissions files; "Hermes-Relay plugin" → "Relay plugin" where it reads naturally. Display text only — no enum names, sealed types, when-branches, or stored route values touched. `c9fa8f7`.
- [x] **Lock app to a specific profile** *(impl 2026-06-21, orchestration batch — unbuilt; verify in Studio.)* Per-connection lock: new `ProfileLockStore`, `ProfileController` lock flows + enforcement, `ConnectionInfoSheet` collapses the picker to a static "Locked to <name>" row, `SettingsScreen` adds the lock card + dialog (the one surface still listing all profiles). Original note: *Allow locking app to a specific profile, hiding all other profiles except from this setting - cleanly hide profile specific UI elements based on this gate.*
- [x] **Profile icon in the floating voice overlay** *(impl 2026-06-21, orchestration batch — unbuilt.)* `VoiceModeOverlay` header pill now shows the per-profile icon (`LocalAgentIconPath`); sphere/pet stays the fallback.
- [x] **Voice dropdown state mixes + label overflow** *(impl 2026-06-21, orchestration batch — unbuilt.)* Invalid engine/route combos made unreachable (RealtimeAgent disabled without relay, unavailable routes disabled, `coerceAudioRoute` auto-corrects); long dropdown/provider labels get `maxLines=1`+ellipsis. Original note: *Fix the voice dropdown mode toggles to not allow weird state mixes - labels need overflow control to prevent 2 lines or crunching.*

- [x] **Per-profile agent icon + static-image avatar (shipped 2026-06-20 —** `d827e46`**, see DEVLOG).** Per-profile icon: client-side `ProfileIconStore` (per `(connection, profile)`, never sent to Hermes; stores a copied-file path) → small Coil image beside the agent name in `MessageBubble` via `LocalAgentIconPath`; picker is `AgentIconRow` under the local-name row in `ConnectionInfoSheet`. Static image: "Add a pet" accepts a single image (magic-byte detect → one-frame static pet). Scope shipped: small name-adjacent icon only; big avatar stays global. Follow-ups: on-device smoke (import an image as a pet; set a profile icon, confirm it shows by the name + persists across restart); optionally also show the icon in the profile picker.

- [ ] **Dot-matrix "thinking" indicator** *(prototype impl 2026-06-28 — unbuilt; verify in Studio.)* New `DotMatrixIndicator` (`ui/components/DotMatrixIndicator.kt`): a Compose-`Canvas` dot grid with a brightness wave sweeping left→right — the dot-anime-react concept reimplemented natively (not a port). Swaps the in-bubble `StreamingDots` working indicator via `LocalThinkingIndicator` (provided in `ChatScreen` around the message `LazyColumn`), behind a new **Chat settings → "Thinking indicator" (Dots / Matrix)** selector with a live preview (`thinkingIndicatorStyle` pref on `ConnectionViewModel`, default "matrix"). Brand-themed (uses the bubble `textColor`), frame-throttled via `rememberAmbientPhase` (not `rememberInfiniteTransition`), and renders a static frame when `animationEnabled` is off. Follow-ups once the base motion is approved:
  - [x] **Preset frame patterns** *(impl 2026-06-28)* — `ThinkingMatrixPattern` (Wave/Pulse/Bounce/Sparkle): Wave stays procedural, the rest are authored `List<Set<Int>>` frame sequences (built generatively in `buildMatrixFrames`, addressed `row*cols+col`), crossfaded between frames. New `thinkingMatrixPattern` pref + a Matrix-only "Pattern" selector in Chat settings. Width widened twice on request (column pitch now 9dp).
  - [x] **Per-indicator color** *(impl 2026-06-28)* — `ThinkingMatrixColor` (Auto + brand accents relay/cyan/green/amber/purple/pink) resolved against `LocalBrand` via `toColor()`, so accents re-theme per app theme. New `thinkingMatrixColor` pref + a Matrix-only swatch row in Chat settings; Auto follows the bubble text color. Possible later add-on: a freeform custom-color picker.
  - **OS-level reduce-motion / TalkBack** — currently gates only on the app's `animationEnabled` pref. Also honor OS reduce-motion + touch-exploration like `CleanChatMode` does (`rememberCleanMotionState().osAnimations`).
  - **Optional: promote to a full avatar style** — the alternative scope (a `DotMatrixAvatar` `AgentAvatar` shown everywhere via `LocalAvailableAvatars`, selected in Appearance). Deferred in favor of the narrower in-bubble indicator.

## Demo mode (2026-06-27) — deferred polish

Shipped offline Demo / Explore mode (see DEVLOG 2026-06-27). Core is in; these are non-blocking polish items, none required for the Play "App access" fix:

- **On-device verify (Studio).** Confirm: "Try the demo" on the onboarding Connect page and the standalone Connect screen lands on Chat showing the canned transcript (Markdown, tool-progress card, weather card, code block); the persistent banner shows and its Connect exits demo into the real wizard; demo runs in airplane mode with no network; Manage/Voice show the demo empty state; Bridge/Terminal show their pair-gate; backing out of demo Chat clears the flag so a real connection still works.
- **Demo composer is a silent no-op.** `ChatViewModel.sendMessage()` early-returns with no API client, so typing + Send in demo does nothing. Polish: intercept sends while `isDemoMode` to append a canned "This is a demo — connect your Hermes server to chat for real" assistant bubble (or disable the composer with a hint), so it doesn't read as broken.
- **Live voice mode in demo.** The voice-mode overlay (mic) launched from Chat isn't demo-gated — a tap would attempt a transcribe (fails gracefully, no crash). Add a demo notice / disable the mic in demo. (Voice settings screen already shows the demo empty state.)
- **Light typewriter/stream simulation.** The transcript is statically populated; an optional per-token reveal on first entry would better convey the "streaming" feel. Acceptable as static for v1.
- **Optional richer demo.** Could add a second tool type or an image attachment to the transcript to showcase more surfaces; kept minimal/one-file for now.

## Orchestration batch (2026-06-22) — deferred follow-ups

Four User-Added items resolved via a 4-worker orchestration pass (disjoint file ownership, coordinator-serialized commits): clean-chat viewport (`1dca285`), connections reframe (`c9fa8f7`), diagnostics/analytics (`c3098a9`), session-delete fix (`6552566`). Plus a follow-on profile-isolation fix raised mid-session: cold-start session-drawer hydration (`889273a`). **Committed to `dev`, NOT built/linted/verified.** Remaining:

- **Build + lint + on-device verify all five (Studio).** Run `./gradlew lint` and a Studio build before pushing `dev` (workers couldn't run gradle). Then confirm on device: clean-chat shows a noticeably taller text area that scrolls; deleting a session on a *non-default* profile sticks (no resurrection after the drawer re-fetches); the Diagnostics screen renders honest per-check status + failure reasons and opens detail on a failing tappable row; connections/voice/permissions copy reads "Hermes"/"Relay"; **and on a cold start while a non-default profile is selected, the session drawer loads that profile's sessions directly with no flash of the server-default list.**
- **Profile isolation — broader sweep (cold-start race).** The session drawer + restored session context are now gated on `ProfileController.selectionSettled` (`889273a`), so they no longer load the server-default profile before the persisted profile resolves. Other profile-scoped surfaces read the *live* `selectedProfile.value` and self-correct when it resolves but aren't gated: voice prefs (`VoiceViewModel.onProfileChanged` at the `RelayApp` voice effect), `profileDisplayAlias`, `profileIcon`. They re-seed on resolution (no visible content-flash like the drawer), but if any shows a wrong-profile beat on cold start, gate its first use on `profileSelectionSettled` the same way. Also: `selectionSettled`'s decision logic is unit-testable (pure over connId/selected/pending/profiles) — add a `ProfileControllerSettledTest` when convenient.
- **Diagnostics: no live re-probe trigger.** The status checks reflect the *last* probe state (read-only snapshot). A "Re-run checks" button would need `ConnectionViewModel` to expose probe methods — deferred so the diagnostics work didn't have to edit a concurrently-owned VM.
- **Diagnostics: Pass checks lack a last-checked timestamp/duration.** `StatusCheck` carries `timestampMs`/`durationMs`, but the VM doesn't expose probe timing, so passing rows show no "checked Ns ago". Wire when/if the VM surfaces probe timestamps.
- **Connections reframe — out-of-scope occurrences left intentionally.** `ConnectionViewModel.kt`, `VoiceAudioClient.kt`, `VoiceViewModel.kt`, `BridgeCoreScreen.kt`, and `RelayApp.kt` still contain "Standard"/"Vanilla" in code identifiers/log strings; only user-facing display copy was reframed. Revisit if any of those surface to users.

## Orchestration batch (2026-06-21) — deferred follow-ups

Client-side profile-lock + voice fixes (the items marked above) landed via a planning→implementation orchestration pass, **built + deployed to device as 1.2.1 (versionCode 15)**; new unit suite green (36 Kotlin + 11 Python). On-device behaviour verification still pending. Remaining from that batch:

- **Realtime voice: server-side half (Python) — DONE + DEPLOYED 2026-06-21.** `plugin/relay/realtime_agent/broker.py`: `_send_hermes_run_progress` now heartbeats while `session.hermes_task` is unfinished (helper `_should_continue_heartbeat`), closing the 90s stall at the source; spoken-status repeat raised 30s→90s and gated on a *coarse* status change (`_coarse_spoken_status_key` / `_should_repeat_spoken_status`) so tool-message churn no longer re-narrates. `plugin/tests/test_realtime_heartbeat.py` 11/11; `test_realtime_promotion` regression 5/5. Deployed: committed `d1820fb` → pushed to `origin/dev` → server `~/.hermes/hermes-relay` fast-forwarded + `hermes-relay` restarted (active, clean startup) — both client + server halves now live end-to-end (re-pair the phone after the relay restart). Optional follow-up: flip `promotion_enabled` default to True so long runs detach.
- **Voice override on the streaming path (open question).** The `.route`→`.effectiveRoute` fix makes 'auto'+relay engage the override-capable path, but the streaming `/voice/output` renderer reads the relay's server-saved `voice_output:` config, not the UI `enhancedVoice` override. Decide whether the override card should also push to `updateVoiceOutputConfig`, or whether an override should force the basic `/voice/synthesize` path.
- **Per-profile voice on Standard (upstream).** `/api/audio/*` is host-global/text-only; the Standard surface still can't carry a per-request voice. Needs the upstream profile-voice / `/v1/audio/*` PR. Until then the client prefers the relay path; consider surfacing an honest "override needs Relay" state when Standard is the effective surface.
- **Profile lock: ChatScreen glyph + export.** The optional lock glyph on the chat-header avatar was skipped (`ChatScreen.kt` is owned by a concurrent session). Decide whether the per-connection lock belongs in settings export/import (it rides the `profile_selections` DataStore).
- **Unit tests — DONE 2026-06-21 (36/36 pass via `:app:testSideloadDebugUnitTest`).** `ProfileLockStoreTest` (9 — uses an in-memory `DataStore` harness; the file-backed factory hits a Windows write-rename/instance race), `ProfileControllerLockTest` (8, Robolectric), `CoerceAudioRouteTest` (7), `VoiceStatusGatesTest` (12).
- **CHANGELOG.** Add `[Unreleased]` entries (Profile lock → Added; voice override + realtime → Fixed) at build-verify/PR time.
- **On-device verification.** Override applies in 'auto'+relay; realtime survives a &gt;90s background task without stalling and stops over-narrating; Speaking waveform unfolds at first audible frame; profile lock hides pickers + holds on a missing profile; overlay shows the profile icon.

## Hands-free agentic voice backlog

Goal: make Hermes usable for hands-free work without leaving the operator blind

to tool state, safety prompts, or the current task.

- **Waveform output-start sync** — current input waveform timing feels good, but

the agent-output waveform can unfold and begin movement before audible speech

starts. Split "preparing audio" from "speaking audio" in the visual layer, or

gate the unfolded Speaking waveform on the first real playback frame/audio

amplitude. Processing can stay as the folded circular spinner until output is

actually audible.

- **Voice command layer** — reserve local commands that bypass normal agent

routing: "pause", "resume", "stop talking", "cancel", "repeat that", "open

overlay", "return to Hermes", and "new chat". These should work while the

agent is thinking, speaking, or using tools.

- **Spoken tool progress** — when Hermes uses tools, voice mode should speak

short status updates such as "I'm checking the relay logs" or "I found an

error" without waiting for final assistant text. Long tool calls should emit

periodic, low-noise progress updates.

- **Realtime tool timeline parity** — the voice overlay should render the same

live thinking blocks, streaming assistant text, and tool call progress as the

normal chat surface without requiring exit/reload.

- **Hands-free confirmation flow** — risky actions need first-class spoken and

visual confirmation: "yes", "no", "cancel", "confirm", plus a visible and

audible countdown for destructive actions.

- **Voice session memory/status** — add a compact "where are we?" summary for

the current voice task: active objective, last tool result, pending next step,

and whether the agent is waiting on the user.

- **Mode presets** — add presets such as Hands-free, Low latency, Careful tool

mode, and Quiet/visual-only. Hands-free should favor Continuous listening,

spoken tool progress, confirmations, and overlay availability.

- **Barge-in hardening** — keep barge-in experimental until echo/self-recording

is solved. The target path is proper AEC, playback-ducking, and a rule that

output audio can never become a user turn.

- **Audio quality guardrails** — normalize output volume across realtime and

fallback TTS providers, keep pronunciation hints/profile voice tuning, and

measure provider-specific delay, chunk gaps, and tail clipping.

- **Pluggable Realtime Agent media transports** — add an OpenAI-first WebRTC

transport option for Realtime Agent so mobile audio can use provider-native

jitter buffering, interruption, and media handling instead of only relay

WebSocket PCM. Design this as a provider transport interface

(`websocket`, `webrtc`, future `livekit`/SIP-style bridges) so other

realtime providers can opt in without forking the Hermes broker/tool

contract. Hermes must still own tools, memory, confirmations, current data,

and durable transcript state.

- **Voice engine selector** — implemented as an opt-in experimental Realtime

Agent engine in `docs/plans/2026-05-19-realtime-hermes-voice-agent.md`.

Follow-up work is provider-native turn-taking, richer confirmation handling,

and quality/latency evaluation before promotion beyond Experimental.

- **Realtime-native Hermes bridge prototype** — first relay-brokered slice

implemented in `docs/plans/2026-05-19-realtime-hermes-voice-agent.md`.

Remaining work: let OpenAI/xAI realtime sessions own more of the live speech

turn while still proxying every tool, confirmation, memory, and Android bridge

action through Hermes/relay safety.

---

## Research / open questions

### Proper Hermes plugin / skill / tool distribution

**Status:** open question, no plan yet.

We currently distribute Hermes-Relay via a one-shot `install.sh` that clones the repo, `pip install -e`s the package into the user's hermes-agent venv, and registers `skills/` via the `external_dirs` config knob. This works but it's a custom protocol — every project that wants to ship a Hermes plugin reinvents it.

Things to look into:

- **Does upstream hermes-agent have or plan a canonical plugin registry / package format?** If yes, we should migrate to it. If no, we may want to propose one upstream so third-party plugins (ours and others) get a standard install path.
- **Skill distribution as separate from plugin distribution** — right now skills ride along with the plugin install via `external_dirs`. Should skills be installable independently (e.g. `hermes skill install <git-url>`)? Would that fragment maintenance or improve reuse?
- **Tool registration discoverability** — `android_*` tools register at gateway import time. There's no canonical "list installed plugin tools" API. Would adding one to upstream make sense, or is `gateway tool list` already enough?
- **Versioning + compatibility ranges** — `pip install -e` doesn't enforce version pins between hermes-agent and our plugin. A breaking change in upstream's plugin loader could silently break us. Do we need a `hermes_compat: ">=0.8.0,<1.0.0"` field somewhere?
- **Update discovery (shipped 2026-06-30 — CLI + dashboard + app).** `hermes relay update-check`, a dashboard "Plugin version" card, and an app **About → "Relay"** row all compare the installed plugin against the latest `plugin-v*` release and surface the right update command (`hermes plugins update hermes-relay` vs `hermes-relay-update`). The app polls the relay's `GET /relay/update-check` (`:8767`, bearer) on each `auth.ok`; the relay is the single source of truth (the app never hits GitHub). Possible polish (deferred): a more prominent dismissible "relay is behind" banner outside About (today it's capability-first + the About row), and showing the app's own version alongside the relay's in the same readout (the app-Version row already exists separately just above it).
- **Per-profile enablement (shipped 2026-06-30).** `hermes relay profiles list|enable [--all|NAME]` + `plugin/profiles.py` resolve the install-once/enable-per-profile papercut; docs now cover the pair-once/one-relay model. Possible follow-up: an `install.sh` / `hermes plugins install` prompt offering "enable for all existing profiles" so new installs don't need the manual `profiles enable --all`.
- `**hermes-relay-self-setup` SKILL.md as a precedent** — we just shipped a self-installing skill that an LLM can fetch from a raw GitHub URL and execute. Does this pattern generalize? Could it become a recommended way for any third-party Hermes project to ship setup automation?
- **Bootstrap injection** — `hermes_relay_bootstrap/` monkey-patches `aiohttp.web.Application` to inject endpoints into vanilla upstream. This is intentional but feels like a hack. Upstream PR #8556 (`feat/session-api`) will eventually let us delete it — verified 2026-04-15 that its scope covers the full bootstrap surface (sessions, memory, skills, config, available-models). Track that PR's status periodically.
- **Gateway slash-command preprocessor — upstream Stage 1 PR.** Sibling follow-up to #8556. Intercepts known gateway commands on `/v1/runs` + `/v1/chat/completions`, dispatches the stateless ones (`/help`, `/commands`) via `gateway_help_lines()`, returns a deterministic "use a channel with session state" notice for the stateful majority. Currently being prepared in `C:/Users/Bailey/Desktop/Open-Projects/hermes-agent-pr-prep/` on branch `feat/api-server-gateway-commands`; awaiting subagent's code + draft PR body before pushing. See `docs/upstream-contributions.md` §5.
- **Gateway slash-command preprocessor — bootstrap middleware (Stage 1 equivalent).** Sibling shim in `hermes_relay_bootstrap/_command_middleware.py` that mirrors the upstream Stage 1 PR as an aiohttp middleware injected at bootstrap time. Ships the hallucination fix to vanilla-upstream installs before the upstream PR lands. Planned for v0.4.1, after the current bridge feature branch wraps. See `ROADMAP.md` v0.4.1 entry.
- **Stage 2 — stateful slash-command dispatch on `/api/sessions/{id}/chat/stream`.** Blocked on PR #8556 merging. Once session primitives ship upstream, add a preprocessor scoped to the session chat stream endpoint only, using `session_id` as the persistence handle. Separate upstream PR + matching bootstrap middleware. See `docs/upstream-contributions.md` §5 ("Stage 2").

When the answer becomes clearer, this section becomes either an ADR in `docs/decisions.md` or a Plan under `Plans/`.

---

## Smaller deferred items

- **MediaProjection consent flow** — wired in MainActivity (2026-04-12), needs end-to-end test on a real device
- **WorkManager upgrade for auto-disable timer** — currently a coroutine `Job + delay()` in `AutoDisableWorker.kt`; documented at top of file. Upgrade when androidx.work joins the classpath
- **Wave 3 voice-bridge multi-turn confirmation** — currently a 5s TTS countdown with cancel; conversational confirmation is the follow-up
- **LLM client wiring for `android_navigate`** — `_default_vision_model` is stubbed; production swap to a real Anthropic/OpenAI vision client
- **Real screenshots of each flavor's a11y permission dialog** — for `user-docs/guide/release-tracks.md`
- `**llms.txt` standard** — explicitly skipped in favor of the `hermes-relay-self-setup` SKILL.md path; revisit if the standard gains traction in the agent ecosystem
- `**markdown-renderer`/`lifecycle` compileSdk ceiling — RESOLVED via compileSdk 37 (2026-06-22).** `MarkdownContent.kt` is on the 0.4x API, and `markdown-renderer 0.42.0` / `lifecycle 2.11.0` (the Dependabot bumps) require `compileSdk 37`. The project moved to **compileSdk 37** (`206d182`, across app/quest/relay-core/relay-ui; `targetSdk` stays 35), which satisfies them — so the temporary 1.2.2-prep pins (0.41.0 / 2.10.0 on compileSdk 36) were dropped when integrating `origin/dev`. Docs/refs reconciled to 37 (2026-06-23): CLAUDE.md, `docs/spec.md`, and the `android.suppressUnsupportedCompileSdk` flags in `gradle.properties` + `quest/gradle.properties`. A Dependabot ignore rule is still worth adding so a future bump that raises the compileSdk floor again fails loudly rather than silently (see next item).
- **Dependabot auto-merge guardrails** — Dependabot merged breaking bumps despite CI failing. Investigate why `.github/workflows/dependabot-auto-merge.yml` isn't gating on CI status, and consider adding an ignore rule for packages we know need manual attention on major bumps (`markdown-renderer`, compose BOM, activity-compose).

---

## Crash reporting + foldable hardening (shipped 2026-06-20)

Triggered by a Play Store review: app "keeps crashing" during setup on a Samsung Galaxy Z Fold7 (Android 16 / SDK 36, version code 13). Shipped: in-app crash capture (`util/CrashReporter.kt` — uncaught handler that persists a report then re-raises so Play vitals still collects; `ui/components/CrashReportDialog.kt` — show-once dialog with Copy + pre-filled GitHub-issue "Report"); QR camera-init hardening (`QrPairingScanner.kt` — try/catch around `ProcessCameraProvider.get()` and `InputImage.fromMediaImage()`, graceful `CameraUnavailableCard` → manual pairing instead of force-close).

Follow-ups:

- **Confirm the actual crash from Play vitals.** Pull the top crash cluster for Galaxy Z Fold7 / version code 13 (Quality → Android vitals → Crashes &amp; ANRs) to verify the camera path is the real cause vs. another setup-path throw. The hardening is correct regardless, but the trace closes the loop.
- **Portrait lock is moot on large screens under SDK 36.** `android:screenOrientation="portrait"` is largely ignored by Android 16's mandatory large-screen orientation override on foldables/tablets. Decide whether to keep the lock (it still applies on phones) or make it conditional; either way it does not *cause* the crash.
- **Foldable camera lifecycle races (from the 2026-06-20 audit, not yet fixed).** `QrPairingScanner` can still hit bind/unbind races on rapid fold/unfold recomposition (the `DisposableEffect` `unbindAll()` vs. an in-flight `addListener` bind), and `mapBoxToViewport` runs on possibly-stale `viewportSizePx` during a fold transition. Not crash-fatal after the try/catch hardening (logged + skipped), but worth a fold-aware guard if foldable adoption grows.
- **Optional: surface crash history in Settings.** The reporter keeps only the most recent crash (`files/crash/last-crash.json`, consumed on view). If repeat-crash diagnosis becomes common, keep a small ring of recent reports + a Settings entry to view/copy them.

---

## Relay enhancement layer + agent-context injection (shipped 2026-06-20 — `docs/plans/2026-06-20-relay-enhancement-layer.md`)

Shipped: `plugin/enhancements/` (registry + fail-open `context_injection` wrap of `AIAgent._build_system_prompt`), the `media-sensitivity` block, `GET /context/injected` audit route, dashboard toggles, client sensitivity re-thread + "Relay context (server-side)" audit section, and the transport-path UI (`ChatTransportStatusBadge` / `RelayStatusStrip` + tier ladder). OFF by default, removable, vanilla-safe.

Follow-ups:

- **Confirm the `AIAgent` seam on the live host before relying on it.** `context_injection._resolve_ai_agent_class()` tries `agent.system_prompt` / `run_agent`. When you flip `RELAY_AGENT_CONTEXT_ENABLED=1`, verify `GET /context/injected` shows the block AND that it actually lands in the prompt (the wrap is fail-open, so a wrong module = inert, not broken). If the class lives elsewhere, widen the module list.
- **Retire the monkey-patch when upstream adds a plugin context hook.** Drop `context_injection` (and migrate to the native hook) the moment hermes-agent ships a first-class system-prompt contributor — same as we retire bootstrap routes for native upstream routes.
- **Incremental bootstrap migration.** Fold the existing `hermes_relay_bootstrap` route-patches into `plugin/enhancements/` per-surface (startup phase) so patching is one surface; don't big-bang the working compat.
- **Structured media channel** — `docs/plans/2026-06-20-structured-media-channel.md` (design only). Replace fragile `MEDIA:`/markdown text markers with a structured channel carrying `sensitive` natively; lead with a relay `relay_send_media(path, sensitive, …)` tool.
- **Gateway voice-ephemeral via the same slot.** The enhancement layer's server-side injection can carry per-turn voice instructions on the gateway (which has no ephemeral `system_message`), letting voice stay on the gateway instead of being forced to SSE. Wire when the voice path is revisited.

---

## Attachments (shipped 2026-06-18 — `docs/plans/2026-06-18-attachment-experience.md`)

- **B3 — download progress + cancel.** Inbound fetch is un-cancelable; the previews work scaffolded an indeterminate bar + nullable `onCancel`. Live wiring needs the fetch-path owner (`ChatViewModel`/`Attachment`) to expose determinate progress (Content-Length) + a cancel hook.
- **A6 — multi-image gallery.** N images in one message → grid + swipe-across viewer (Telegram media-group parity).
- **C5 — agent-side sensitivity config gate.** `RELAY_MEDIA_SENSITIVITY_HINTS` (env or per-profile) instructing the agent to annotate sensitive media via the prompt-builder. Transport (relay `X-Media-Sensitive` header + client blur) already ships; the agent isn't asked to set the bit yet.
- **Relay thumbnails (D6).** Server-side thumbnail generation to avoid full-size download for cards/galleries. Needs an image lib (Pillow not currently a dep) — evaluate before adding.
- **D5 — outbound upload progress.** No per-attachment progress during the 60s gateway PDF-render window.

## Voice overhaul (shipped 2026-06-18 — `docs/plans/2026-06-18-voice-overhaul.md`)

- **Per-profile voice on Standard (upstream PR).** Upstream `/api/profiles/*` has no voice field and `/api/audio/*` is host-global. Long-term: PR a voice section to the profile config + make `/api/audio/*` honor the active/`?profile=` profile. The relay path already carries per-profile voice; ship that first.
- **Wire connectionId for per-profile voice namespacing.** `VoicePreferencesRepository` is scope-aware (`base_connId_profile`), but `RelayApp` passes only the profile *name* to `onProfileChanged`, so `connectionId` is null and keys namespace by profile-only. Wire `setVoicePrefsConnection` to `ConnectionViewModel.activeConnectionId` (in `RelayApp`) so two connections with same-named profiles don't share voice settings.
- **Realtime-PCM waveform output gating.** The basic-TTS output waveform is now Visualizer-accurate (gated on real playback amplitude), but the realtime path gates `outputAudioActive` on `audioSeen` (first decoded PCM bytes) in `VoiceViewModel.handleRealtimeVoiceEvent`, which can still lead audible output by the `RealtimePcmPlayer` start prebuffer. Gate realtime on actual playback-start (head moved) to match the basic-TTS path.

## Chat clean-mode + pets (shipped 2026-06-18 — `docs/plans/2026-06-18-chat-clean-mode-and-pets.md`)

- **Part-A chat polish (optional bundle).** Per-code-block copy + horizontal scroll, visible copy affordance, mid-stream stall feedback, profile/skill-aware empty-state chips, the ~40-flow recomposition hotspot at the top of `ChatScreen`. (Sphere `contentDescription`/reduced-motion was handled by the clean-mode a11y work.)
- **Pet hot-load + in-app add/remove (shipped 2026-06-20).** Pets now live-refresh: an `avatarsRefreshTick` keys the avatar `produceState` in `RelayApp`, and Appearance re-scans `pets/` on open and after in-app import/delete — no app restart. Appearance gained "Add a pet" (SAF `.zip` import via `PetImporter`, zip-slip/zip-bomb guarded + validated through `toAvatar`) and an "Installed pets" list with per-pet remove (`PetLoader.deletePet`, confirm dialog, Sphere fallback). Remaining:
  - **Sphere-skin parity.** Skins are still process-scoped + `adb push` only — the live tick and the importer cover pets, not skins. Extend the tick to `loadUserSkins` and add a `.json` skin import if hot-loading/adding skins in-app is wanted.
  - `**adb push` into `Android/data` hangs on Samsung scoped storage.** Confirmed: pushing a pet pack to `/sdcard/Android/data/<pkg>/files/pets/` stalls (no bytes written) although `adb shell ls` of the dir works. In-app `.zip` import is the supported path; `/sdcard/Download` pushes fine. Consider softening `docs/pet-spec.md` + user-docs to lead with in-app import over adb.
  - **On-device import/delete smoke.** Import `/sdcard/Download/lucy.zip` via Add a pet → confirm Lucy appears, selects, and animates all states; then remove it and confirm the avatar falls back to the Sphere.
- **Pet state-change re-decode can flash one blank frame.** When the agent state switches clips, the first frame of the new clip may briefly be blank during decode; prewarm/hold-last-frame to smooth it. Root cause is the same as the next item: `PetAvatar.Render` re-decodes from disk on every clip change.
- **Pet frame-sequence memory: no cap or downsample (audit 2026-06-19).** `decodeClip` decodes every frame of the selected clip into `List<ImageBitmap>` at full resolution with no `inSampleSize` downscale to the display size and no frame-count/dimension ceiling — a long sequence of large PNGs can use a lot of RAM and a single very large image can OOM `BitmapFactory`. Add `inSampleSize` downsampling to the avatar's draw size and/or a documented hard cap. Spec now warns authors (prefer sprite sheets), but the renderer doesn't enforce it.
- **Pet decoded-clip cache (audit 2026-06-19).** `PetAvatar.Render` keys `produceState` on `clip`, so idle→thinking→speaking→idle within one turn re-runs `BitmapFactory.decodeFile` from disk each transition (repeated I/O + GC churn, and the blank-frame flash above). Add a small per-avatar `Map<SphereState, PetFrames>` decode cache.
- **Pet behavior model — richer state association (spec'd 2026-06-19, `docs/pet-spec.md` "Agent states &amp; pet behavior").** Shipped: the honesty clamp (declared reactivity ∩ `PET_RENDERER_CAPABILITIES`), the friendly `writing` alias, the `**working`/tool-use overlay** (pet-local sub-state from `toolCallBurst`; opt-in `working` clip drives both the swap and the Tools badge), the **one-shot reaction layer** (`greet`/`wake` on appear, `done`/`celebrate` on turn-finish — opt-in, play-once-then-revert, transition-derived; `ONE_SHOT_MAX_MS` backstop), and `**intensity` modulation** (opt-in `reactive.intensity` → live playback speedup ≤1.6× via `rememberUpdatedState`; un-clamps the Activity badge). Voice · Tools · Activity reactivity is now complete. Remaining:
  - `**attention` one-shot (only deferred behavior).** A reaction on notification arrival — needs a host event the avatar doesn't yet receive (unlike `greet`/`done`, which ride state transitions). Would plumb a notification edge into `AvatarRenderState` (or a side channel) + a `PetOneShot.Attention`. Low priority: the avatar is rarely on-screen when notifications land (backgrounded) — see the value analysis; revisit only if the avatar becomes an always-on surface (persistent overlay / Quest port).
  - **On-device verification (working + one-shots + intensity).** Best seen in clean mode (`AgentTextFlow` feeds `toolCallBurst` + `streamingIntensity` + state transitions). Confirm: a `working` clip swaps in during a tool run and releases ~600ms after (`WORKING_BURST_THRESHOLD` 0.5); a `done` clip plays once on reply completion then returns to idle; a `greet` clip plays once when the avatar appears; with `intensity:true`, a writing/working loop visibly quickens while streaming. Watch for the known clip re-decode flash on each swap (separate TODO — decoded-clip cache).
- **Undecodable-but-present image appears valid (audit 2026-06-19).** A file that exists but isn't a decodable image passes the loader's `isFile` check, so the pet shows in the picker but renders blank. Documented as a caveat; consider a cheap header sniff at load time if false-valid pets become a support issue.

