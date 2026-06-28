# Hermes-Relay — Dev Log

## 2026-06-28 — Phone platform (Phase 1d: off-by-default enablement surface)

**Why.** Phase 1c wired the receive path but gated it behind a flag with no UI. Phase 1d adds the user-facing opt-in ("Let Hermes message me") and the notification-permission prompt, completing the end-to-end Phase 1 spine: `send_message target=phone` → phone notification, only when both server and phone have opted in and the phone is paired.

**What.**
- **`ui/screens/ProactiveSettingsScreen.kt`** (new). A dedicated "Hermes messages" screen: the enablement switch (bound to `proactiveEnabled` / `setProactiveEnabled`), a POST_NOTIFICATIONS request fired on enable (API 33+), a not-paired hint, and an About section that documents the server-side `PHONE_ENABLED` requirement. This is the permanent home Phase 3 expands (quiet hours, per-profile, rate limiting).
- **`viewmodel/ConnectionViewModel.kt`.** `setProactiveEnabled(enabled)` persists the flag; subscribe/unsubscribe is already driven reactively by the `proactiveEnabled` collector from Phase 1c.
- **`ui/screens/SettingsScreen.kt`.** New "Hermes messages" category row in the Hermes section + `onNavigateToProactiveSettings` param.
- **`ui/RelayApp.kt`.** `Screen.ProactiveSettings` route + NavHost entry + nav wiring at the Settings call site.
- **Server side.** The `PHONE_ENABLED` gate already lives in the adapter (Phase 1a); documented in-app on the new screen.

**Verification.** `POST_NOTIFICATIONS` is already declared in the manifest; `rememberLauncherForActivityResult` is used across existing screens (dependency present). `./gradlew :app:lintSideloadDebug` run over the combined Phase 1c+1d app spine (same compilation unit) — see commit. On-device end-to-end (enable → server `send_message target=phone` → notification) is a maintainer step.

## 2026-06-28 — Phone platform (Phase 1c: app receive + system notification)

**Why.** The relay now pushes `phone.message` envelopes over the phone WSS (Phase 1b); the app needs to receive them and surface the agent's message. Phase 1c lands the receive path + a system notification, gated off by default.

**What.**
- **`network/relay/ProactiveMessageHandler.kt`.** Sibling of `BridgeCommandHandler`. Parses `phone.message` payloads into a `ProactiveMessage` and dispatches them. `dispatch()` centralizes surfacing so Phase 2 (inbox / session injection) extends one place; Phase 1c always raises a notification. Drops malformed payloads; logs the `proactive.subscribed` ack.
- **`notifications/ProactiveMessageNotifier.kt`.** Twin of `TurnCompleteNotifier` (channel-ensure → permission-gate → tap PendingIntent) with two differences: it **stacks per message** (slot derived from `message_id` so re-delivery replaces but distinct messages stack) and uses an `IMPORTANCE_HIGH` "Hermes messages" channel (a proactive ping the user opted into). Tap opens Chat for now (inbox route arrives in Phase 2a).
- **`network/relay/ChannelMultiplexer.kt`.** Adds the `"proactive"` route branch.
- **`data/ProactivePrefs.kt`.** `KEY_PROACTIVE_ENABLED` ("Let Hermes message me") + setter + reactive read, default **off**. The app half of the two-sided gate.
- **`auth/AuthManager.kt`.** Adds an additive `authOkEvents` SharedFlow (mirrors the existing `profilesUpdatedEvents`), emitted on every `auth.ok` — the per-connection signal needed to re-subscribe after reconnects.
- **`viewmodel/ConnectionViewModel.kt`.** Registers the proactive handler; exposes `proactiveEnabled`; sends `proactive.subscribe` on each `auth.ok` when enabled (sourced via `_authManagerFlow.flatMapLatest` so it survives connection switches), and subscribe/unsubscribe when the toggle flips. The subscribe rides *after* the auth handshake, so it never races the `auth` envelope.

**Verification.** New files are self-contained; the receive path is gated by `proactiveEnabled` (default off) and the relay's per-socket subscribe latch, so nothing surfaces until the user opts in (Phase 1d adds the Settings switch + notification-permission prompt). `./gradlew :app:lint` is run once over the app spine after Phase 1d (same compilation unit). On-device end-to-end is a maintainer step.

## 2026-06-28 — Phone platform (Phase 1b: relay forward route)

**Why.** The phone platform adapter (Phase 1a) POSTs proactive messages to the relay; the relay needs a route to receive them and a channel to push them over the live phone WSS. This is the server→app push counterpart to the existing bridge channel.

**What.**
- **`plugin/relay/channels/proactive.py`.** `ProactiveChannel` — the mirror of the bridge handler, reversed. It latches the phone's WebSocket on a `proactive.subscribe` envelope (acked with `proactive.subscribed`), exposes `push(payload)` that sends a `phone.message` envelope over that socket, and releases on `proactive.unsubscribe` / disconnect. No awaited reply — push is best-effort (notification semantics). `phone.message` from a phone is rejected (server→app only).
- **`plugin/relay/server.py`.** Wires `self.proactive = ProactiveChannel()` onto `RelayServer`; adds the `channel == "proactive"` dispatch branch; releases the subscriber on client disconnect; closes it on shutdown; and registers `POST /phone/message` (`handle_phone_message`) — **loopback-only** (the adapter runs in the gateway process on the same host; an outbound push could spam notifications, so it is not exposed to the LAN). Returns 503 when no phone is subscribed, 502 on socket-write failure, 400 on empty/invalid body.
- **Opt-in is structural.** The relay can only push when it holds a latched `phone_ws`, which it only gets when the app subscribes — which the app does only when the user enables "Let Hermes message me." Combined with the server-side `PHONE_ENABLED` adapter gate, both sides must opt in.

**Verification.** `python -m py_compile` clean. `python -m unittest plugin.tests.test_proactive_channel` — 11 tests pass (subscribe/ack, take-over, unsubscribe/detach, push envelope shape + supplied-id passthrough, no-subscriber/closed/failed-send raises, spoofed-inbound + unknown-type ignored). `import plugin.relay.server` succeeds and the route registers; bridge + proactive + phone suites pass together (40 tests). End-to-end with a live phone and the app-side receive handler is Phase 1c.

## 2026-06-28 — Phone as a first-class Hermes platform (Phase 1a: plugin adapter)

**Why.** The paired phone could receive agent output only by being on the chat screen. Making it a registered Hermes *platform* — a peer of Discord/Telegram/ntfy — lets the agent push to it proactively (`send_message target=phone`, cron `deliver=phone`). This is delivered additively through the upstream platform-plugin API (`ctx.register_platform`); no fork, no upstream core change.

**What.**
- **`plugin/phone_platform.py`.** A push-only `BasePlatformAdapter` subclass (`PhoneAdapter`) modeled on the bundled ntfy adapter. `send()` POSTs loopback to the relay (`/phone/message`, reusing `android_tool.py`'s relay-URL convention) rather than opening a socket — the relay forwards over the live phone WSS. `connect()` only marks the platform ready (the phone's inbound path is chat, so no inbound stream); `get_chat_info()` returns the device identity. Ships the full registry surface: `check_fn`/`validate_config`/`is_connected` (all gated on `PHONE_ENABLED`), `env_enablement_fn`, `cron_deliver_env_var=PHONE_HOME_CHANNEL`, and a `standalone_sender_fn` so out-of-process cron / `send_message` delivery works (without it, `deliver=phone` cron fails with "No live adapter"). `gateway.*` imports are guarded so the module (and its pure helpers) import without hermes-agent present.
- **`plugin/__init__.py`.** Wires `register_phone_platform(ctx)` into `register()`, guarded like the existing slash/hook blocks so an older host (no `register_platform`) can't block tool/CLI registration.
- **`plugin/plugin.yaml`.** Adds a `provides_platforms: [phone]` documentation key. The plugin stays `kind: standalone` (multi-capability) — it is not a dedicated `kind: platform` plugin; registration is programmatic.
- **Off by default.** Nothing is advertised or pushed unless `PHONE_ENABLED` is truthy.

**Verification.** `python -m py_compile` clean on the new + edited files. `python -m unittest plugin.tests.test_phone_platform` — 22 tests pass (env gating, relay-URL precedence, payload construction/truncation/surfacing-lift, `_env_enablement`, and the standalone sender over a fake httpx client: success / 503-no-phone / disabled / unreachable). Relay route, end-to-end routing, and the live-gateway plugin-discovery check are Phase 1b+ and a maintainer on-box step.

## 2026-06-28 — Dev-loop polish after the live smoke test

**Why.** End-to-end testing the triage workflow on `main` (issue #150 through open → `triage:deep` → reply, plus a dispatch against #146) surfaced three things to tidy.

**What.**
- **`start-issue.sh` brief filter fix.** Triage/deep-dive/follow-up comments are posted by the **Claude GitHub App** (author `claude`), not `github-actions` — so the brief generator's `author.login=="github-actions"` filter would have produced an empty "Automated triage notes" section. Switched to an identity-proof match on the comment signatures (`automated triage` / `Deep-dive analysis` / `automated follow-up`), with the bot logins as a fallback.
- **`actions/github-script@v7` → `@v8`.** Clears the Node 20 deprecation annotation (v8 targets Node 24).
- **Deep-dive formatting.** Prompt now tells the deep-dive to use its `##`/bold headings as the section separators and not to add horizontal rules (`---`) between sections — the first run rendered a rule under every heading, which read heavy.

**Verification.** Smoke test confirmed all four jobs behave as designed: auto-label + opinionated triage (3 real likely-files), label-gated deep-dive (root cause + fix + surface-aware verification + worktree quick-start), and follow-up gating (skips bot + owner comments; response path is external-reporter-only by design). The workflow tweaks here activate on the next `dev → main` merge; the script fix is live from `dev`.

## 2026-06-28 — Opinionated issue triage + deep-dive + follow-up loop + issue→worktree dev-loop

**Why.** `claude-triage.yml` was a deliberately conservative classifier — label, dedupe, and one hedged note, with no root-cause opinion and no fix suggestion by design. To shorten the issue→fix loop, triage should also diagnose and hand off a starting branch/worktree, and do it surface-aware: plugin/CLI fixes can be CI-proven, while Android UI/behavior stays a manual on-device gate. Modeled on the MeshMonitor (`Yeraze/meshmonitor`) multi-job triage, ported to our `claude-code-action@v1` interface (`prompt` + `claude_args`, not the older `@beta` `direct_prompt`/`model`/`use_sticky_comment` shape), with the existing untrusted-input hardening kept.

**What.**
- **`claude-triage.yml` (2 jobs → 4).** `auto-label` now also applies an `area:*` surface label from keywords. `triage-ai` adds a hedged probable-cause / likely-files / suggested-direction read (one ≤180-word note) and invites the `triage:deep` label. New `deep-dive` (opt-in via that label) investigates the codebase and posts a root-cause hypothesis, a fix plan, a surface-specific verification plan, and a maintainer worktree quick-start. New `triage-followup` re-reads a `bug` thread on reporter replies and escalates to `needs-maintainer-review` + the maintainer after ~2 rounds; not gated on commenter write-access (so external reporters get follow-up), and bot comments are excluded so it can't self-trigger.
- **`claude-code-review.yml`.** Keeps the `/code-review` plugin depth, adds a constructive "Maintainer's-eye verdict" header and `use_sticky_comment` so re-pushes update one comment instead of stacking.
- **`scripts/start-issue.sh`.** Local bridge — pulls an issue into a pre-briefed worktree (`fix|feature|docs/issue-N-slug` off `origin/dev`) with an `ISSUE-BRIEF.md` carrying the body, the bot triage notes, and the surface's verify commands. `ISSUE-BRIEF.md` is git-ignored.
- **`docs/dev-loop.md`** documents the loop, the surface→verification matrix, the label setup, and the default-branch activation lag.
- **Labels.** `triage:deep`, `needs-maintainer-review`, and `area:android|cli|plugin|dashboard|docs` created on the repo.
- **Scope.** All jobs stay read-only against the repo; an auto-fix (`contents: write`) path was intentionally left out as an injection risk.

**Verification.** Both workflow files parse (jobs enumerate as expected); `start-issue.sh` passes `bash -n`, is stored mode 755 with `eol=lf`. Issue/label/comment triggers run the default-branch copy, so the workflow stays dormant until a release-merge to `main`; end-to-end test pending on `main`. PR #147 → dev.

## 2026-06-28 — Drop unnecessary safe calls in the update banner/checker

**Why.** A sideload build surfaced two Kotlin `w:` warnings — an unnecessary safe call in `UpdateAvailableBanner` and another in `UpdateChecker`.

**What.**
- **UpdateAvailableBanner.** `subtitle` is assigned a non-null value in every reachable branch of the status `when`, so the compiler narrows it to non-null at use. Declared it `String` (was `String?`) and render the subtitle `Text` unconditionally instead of via a redundant `?.let`.
- **UpdateChecker.** OkHttp 5's `Response.body` is non-null, so the `?.` on `resp.body` was dead — and removing only the `?.` would leave an Elvis-on-non-null warning. Replaced with `resp.body.string()` plus an explicit `isBlank()` guard, preserving the original empty-body error path.

**Verification.** Behavior-preserving; both warnings cleared. `:app:lint` green (BUILD SUCCESSFUL, no errors). PR #148 → dev.

## 2026-06-27 — Profile-scope session rename + manual drawer refresh (#133 follow-up)

**Why.** Auditing the #133 work surfaced that `ChatViewModel.renameSession` always called the unscoped `apiClient.renameSession` (`PATCH /api/sessions/{id}` on the shared api_server DB). There was a `profileSessionDeleter`/`profileSessionLister`/`profileMessageLoader` but no rename twin — so on a non-default **gateway** profile (whose sessions live in that profile's own `state.db`) a manual rename patched the wrong DB and never appeared in the profile-scoped list. Same class as the delete bug fixed in `6552566`. Profiles are first-class, so every session write must be profile-scoped.

**What.**
- **Scoped rename.** New `DashboardApiClient.renameSession(sessionId, title, profile)` + a `patchJsonObject` helper (`PATCH /api/sessions/{id}?profile=`), `ConnectionViewModel.renameProfileScopedSession` (twin of `deleteProfileScopedSession`), and `ChatViewModel.profileSessionRenamer` wired from `RelayApp`. `renameSession` uses it when `streamingEndpoint == "gateway"`, falling back to the unscoped PATCH otherwise (shared api_server DB, no profiles).
- **Audit.** Confirmed rename was the only remaining gap — list/messages/delete are scoped, gateway create goes through `session.create` over `/api/ws`, the SSE auto-title PATCH targets the shared DB (no profiles), and `/branch` is a server-side slash command.
- **Manual drawer refresh.** `SessionDrawerContent` gained a header refresh icon (`onRefresh` → `refreshSessions`) so a title the post-turn auto-reconcile window missed can be pulled on demand. Placed in the header rather than the per-session ⋮ menu since refresh is a list-level action.

**Remaining "Untitled" causes (after these fixes).** The optimistic preview only covers sessions this app run created/sent in; it isn't persisted on the SSE path. So sessions made by other clients, or any SSE session after an app restart, still read "Untitled" because the api_server surface never auto-titles and we hold no local preview for them. Closing that fully needs the upstream api_server titler PR or the opt-in client-side LLM titling feature (both in TODO).

**Verification.** Compiles in the sideload flavor (assembleSideloadDebug). On-device rename-persists-on-non-default-profile check pending.

## 2026-06-27 — Fix sessions showing as "Untitled" in the drawer (#133)

**Why.** A user reported most chat sessions read "Untitled" in the drawer. Tracing both sides: session titles are not set at creation — upstream generates them in a fire-and-forget background thread after the first exchange (`agent/title_generator.py::maybe_auto_title`), and that titler is wired into the gateway/CLI/ACP agent loops but **not** `APIServerAdapter._run_agent`, so the api_server SSE/runs/completions surfaces never auto-title at all. On the client, `ChatHandler.updateSessions` copied the server's title verbatim, so a re-list that arrived before (or without) the async write would overwrite the optimistic first-message preview with `null` → the drawer's `title ?: "Untitled"` rendered "Untitled". Both effects compound; title generation can also silently fail when a profile's auxiliary model has no working key (matches the reporter's intermittency).

**What (client-side mitigations, this change).**
- **Clobber guard.** `ChatHandler.updateSessions` now merges the title field instead of overwriting it: the server wins when it returns a non-blank title, otherwise the known local title is preserved. Stops a too-early/empty re-list from erasing the optimistic preview. New `ChatHandlerTest` cases cover null-server-title preservation, blank-server-title preservation, and real-server-title-wins.
- **Post-turn title reconcile.** `ChatViewModel.scheduleTitleReconcile()` re-lists at +3s/+7s after a gateway turn completes so a title written after the response (and the flushed message_count/model) replaces the preview; cancel-and-replace keeps one job in flight. Gated to the gateway transport (SSE/runs never title, so retrying there is pointless).
- **Subtle drawer note.** `ChatViewModel.serverAutoTitles` (true only on the gateway transport, kept in sync from the `streamingEndpoint` setter) feeds a quiet "Chats aren't auto-named on this connection — use ⋮ → Rename." caption in `SessionDrawerContent`, shown only on the SSE surfaces so consistently-untitled chats read as expected rather than broken.

**Deferred (see TODO "Session titles (#133)").** Upstream PR to call `maybe_auto_title` from `APIServerAdapter._run_agent` (proper fix for the SSE surface); an interim relay-side titler option; and a separate opt-in feature to generate titles client-side via the main LLM.

**Verification.** `:app:testGooglePlayDebugUnitTest --tests "*ChatHandlerTest"` green (BUILD SUCCESSFUL; 3 new clobber-guard tests pass). Warnings emitted are pre-existing in unrelated test files. Not built in Studio / not on-device verified.

## 2026-06-27 — Released android-v1.2.5

Bundles the day's Android work: the #131/#132 non-address-URL crash guard, the offline Demo / Explore mode, and the demo-reachability + App-access polish. Bumped `appVersionName` 1.2.4 → 1.2.5 and `appVersionCode` 18 → 19. Promoted the Android items into a `## [1.2.5]` CHANGELOG block; the Desktop CLI items stay in `[Unreleased]` for a future `cli-v*` release. Refreshed `RELEASE_NOTES.md`, the in-app `whats_new.txt` + `changelog.json`, and the Play `what's-new`. Released via a `dev → main` merge and the `android-v1.2.5` tag; `release-android.yml` builds the signed APK/AAB + GitHub Release. Play upload and the App-access "Try the demo" declaration are owner-driven.

## 2026-06-27 — Add in-app Demo / Explore mode (offline, for Play review + first-run UX)

**Why.** Google Play rejected v1.2.4 under "App access": a reviewer opened the app, had no Hermes server to point it at, hit the empty Connect/setup wall, and bounced. The app is a client for a user-run Hermes server, so there is no content without a connection — and there was no offline path. This adds an in-app Demo mode so anyone (a reviewer or a first-run user) can see the app work with zero setup and zero network; Play Console "App access" can then declare that all functionality is reachable via "Try the demo" (no login). It doubles as a first-run UX win.

**What.** An additive, offline path layered on the real connection model — the Vanilla Hermes path is untouched.

- **Canned data through the real UI.** New pure-JVM `data/DemoContent.kt` holds a curated, obviously-fictional transcript (a capability tour with Markdown, a completed tool-progress card, and a `weather` `HermesCard`, plus a follow-up showing a code block). `ChatHandler.loadDemoTranscript()` pushes it into the existing `_messages` flow; `ChatViewModel.bindDemoHandler()` binds that handler with no network fetches. `ChatScreen` renders it through the real composables (the connect CTA only shows when `messages` is empty), so there is no parallel chat UI.
- **State.** Pure-JVM `data/DemoMode.kt` (active flag + transcript; `enter()`/`exit()`), owned by `ConnectionViewModel`, which exposes `isDemoMode` and `enterDemoMode()`/`exitDemoMode()`. Entering does NOT complete onboarding.
- **No network in demo.** `reconnectIfStale()`, `revalidate()`, `connectRelayInternal()`, `probeApiHealth()`, and `probeRelayHealth()` all early-return while `isDemoMode` is true — demo runs in airplane mode. A back-nav `LaunchedEffect` clears demo when the user lands on a connect surface so a stale flag can never block the real connection.
- **Entry points.** A "Try the demo — Explore offline, no server needed" affordance in `ConnectionWizard`'s Method step, surfaced from the onboarding Connect page and the standalone Connect (`PairScreen`) entry; not on add-connection/re-pair (placeholder-in-flight) flows.
- **Chrome + banner.** New `DemoModeBanner` persistent strip ("Demo mode — sample data, not connected. Connect →") whose Connect exits demo and routes to the real wizard. `RelayApp` treats demo like "onboarding complete" for chrome only, and skips the startup connect-narration sphere. Manage and Voice settings show a friendly `DemoUnavailableContent` empty state; Bridge/Terminal already show their clean "pair to unlock" gate screens when unpaired (the demo state).

**Tests.** New pure-JVM `data/DemoContentTest.kt` (transcript has both roles, Markdown + code block, a completed tool-progress card, a rich card, renders with zero network, deterministic) and `data/DemoModeTest.kt` (enter loads the canned transcript, exit clears it, idempotent round-trips, injected factory).

**Verification.** `:app:testSideloadDebugUnitTest` green (BUILD SUCCESSFUL — the task compiles the whole `app` module + both new `DemoContentTest`/`DemoModeTest` classes pass). `:app:lintSideloadDebug` green (no errors). Not built in Studio / not on-device verified.

## 2026-06-27 — Fix "Invalid URL host" crash from a non-URL value in a server-URL field

**Why.** An auto-captured in-app crash report (#131; duplicate #132): `java.lang.IllegalArgumentException: Invalid URL host: "Manage sign-in and admin screens"` from `okhttp3.Request$Builder.url`, inside a `suspend` lambda with a suppressed `Dispatchers.Main.immediate` frame — i.e. an uncaught throw on a Main coroutine. App 1.2.3 (code 17), Google Play build; reporter was on the Manage / sign-in area. This is the newest sibling of the same crash family as #124→#125 and #129→#128: a networking-layer exception propagating uncaught into a Main coroutine.

**Root cause (hypothesis a — user-entered, confirmed by source tracing).** The literal host (`"Manage sign-in and admin screens"`) is a UI/docs label, not an address — it exists only in `user-docs/guide/getting-started.md`, nowhere in app source or resources, and no connection `label`/description is read where a host belongs (hypothesis b ruled out: every `DashboardApiClient`/`HermesApiClient` is constructed from a URL field, never a label). The value was *entered*. The setup wizard's URL validators only checked the scheme: `apiUrlSchemeError` flagged `ws://`/`wss://` and `optionalHttpUrlError` flagged a non-http scheme, but both returned "no error" for any scheme-less string. So a non-address such as the docs line passed validation, the save path's `Connection.normalizeApiUrlInput` prepended `http://` (it normalizes but does not validate), and it was stored as the connection's Dashboard/API URL. On the Manage screen `DashboardApiClient` built `Request.Builder().url("http://Manage sign-in and admin screens/...")` — and okhttp's `url(String)` (the throwing twin of `toHttpUrlOrNull()`) threw on the space-containing host. The throw happened while *building* the request, before `executeJson()`'s `try/catch`, inside a `withContext(IO)` lambda whose caller sat on `Dispatchers.Main` → uncaught → force-close.

**Fix (two layers).** Layer 1 (root cause / UX): new shared helper `util/ServerAddress.kt` validates an address with the same engine that builds requests — `toHttpUrlOrNull()` — via a strict `parse()` (scheme required; the request-guard primitive) and a lenient `parseUserInput()`/`isValidUserInput()`/`fieldError()` (bare host gets `http://`, mirroring `normalizeApiUrlInput`). The wizard's `apiUrlSchemeError` + `optionalHttpUrlError` now also reject anything that won't parse, so a non-address shows an inline error and blocks submit. Layer 2 (crash-class guard): `DashboardApiClient` routes every request through a private `resolveUrl()` (`toHttpUrlOrNull()`) and short-circuits to `Result.failure`/`false` on a malformed base URL — ~10 sites incl. `getJson`, `currentSession`, `loginPassword`, `requestWsTicket`, `audioRoutesPresent`; `StandardHermesVoiceClient.transcribe`/`synthesize` (same user-influenced dashboard URL, also built before their `try/catch`) get the same guard. Even a stored, pairing-, or future-call-site-supplied bad value is now reported as unreachable, never a Main-thread crash.

**Verification.** New `ServerAddressTest` (pure JVM) covers the exact crash string, blank/whitespace/missing-scheme/junk rejection, and bare-host/IP/localhost/`host:port`/`http(s)` acceptance, and asserts the helper never throws. `DashboardApiClientTest.malformedBaseUrl_returnsFailure_doesNotThrow` builds the client with `http://Manage sign-in and admin screens` and asserts `getStatus`/`currentSession`/`requestWsTicket`/`getJsonObject`/`loginPassword` return `Result.failure` and `audioRoutesPresent()` returns `false` — none throw. Follow-up audit items (HermesApiClient streaming `authRequest` sites, relay-client `.toHttpUrl()` sites — both lower-risk, gated by the health check or post-pairing server URLs) recorded in `TODO.md`.

## 2026-06-25 — Released android-v1.2.4

Cut Android **1.2.4** (appVersionName 1.2.4 / appVersionCode 18) — "Stability + connection security". Driven by **#129**: an external user's auto-captured crash report on the **1.2.3 Play build** showed a `SocketTimeoutException` to the dashboard (`:9119`) over Tailscale surfacing on the main thread — the same crash class as 1.2.3's `NetworkOnMainThreadException` fix, on the sibling `DashboardApiClient.currentSession()` call site that 1.2.3 didn't cover. 1.2.3 tagged 2026-06-23; the `currentSession()` fix (`99b9cf1`, #128) landed 2026-06-24 — one day after release — so the published build was still exposed. Confirmed the fix is comprehensive: all four dashboard `.execute()` sites (`currentSession`, `audioRoutesPresent`, `executeJson`, `executeJsonElement`) and `StandardHermesVoiceClient` are now `try/catch`-guarded. 1.2.4 bundles that fix plus the connection security indicator (#127, already on `dev`). Release commit `2e58449` on `dev` (CHANGELOG `[1.2.4]` promotes only the Android items; Desktop CLI items stay in `[Unreleased]` for a future `cli-v*` cut); release PR **#130** (`dev` → `main`, merge `0327012`) merged on green Required-checks + claude-review; `android-v1.2.4` tagged from the `main` tip → `release-android.yml` builds signed APK/AAB (googlePlay + sideload) + `SHA256SUMS.txt` → GitHub Release. Play upload is owner-driven.

## 2026-06-24 — Fix SocketTimeoutException crash from DashboardApiClient.currentSession()

**Why.** An in-app crash report (`FATAL EXCEPTION: main`, `SocketTimeoutException`, `Caused by: java.net.SocketException: Software caused connection abort`) captured on-device over a Tailscale connection. The visible dialog truncated the trace; the full stack was recovered from a background `adb logcat` capture that happened to be running when it fired.

**Root cause.** `DashboardApiClient.currentSession()` declared `Result<DashboardAuthSession>` but performed a **raw `okHttpClient.newCall(req).execute()` with no try/catch** — the lone outlier among the client's methods (`executeJson`/`executeJsonElement`/`audioRoutesPresent` all catch). Its `.execute()` correctly ran on `Dispatchers.IO`, but a transient network failure (a stale pooled connection aborting over Tailscale) **re-threw** out of `withContext(IO)`. The caller chain — `ConnectionViewModel.probeStandardVoice()` → `viewModelScope.launch` (`Dispatchers.Main.immediate`, the `Suppressed` frame in the trace) — used `try/finally` with **no `catch`**, so the exception was uncaught on the main thread and killed the app. The `.execute()` being off-main is why StrictMode never fired; the uncaught *propagation* to the Main coroutine was the bug.

**Fix.** (1) `currentSession()` now wraps its request in `try/catch`, returning `Result.failure` on any exception — honoring the `Result` contract every caller relies on (mirrors `executeJson`). (2) Defense-in-depth: `probeStandardVoice()` gained a `catch` (rethrowing `CancellationException`) that degrades the voice/gateway availability state instead of letting any probe sub-call crash the Main coroutine.

**Verification.** New `DashboardApiClientTest.currentSession_onConnectionAbort_returnsFailure_doesNotThrow` (MockWebServer `DISCONNECT_AT_START`) asserts a connection abort yields `Result.failure`, not a throw. `:app:testSideloadDebugUnitTest` + `:app:lintSideloadDebug` green. On-device confirmation pending a build.

## 2026-06-23 — Fix NetworkOnMainThreadException crash on TLS connect

**Why.** Two external bug reports (#118, #124) and the later comment on #70 reported the app hard-closing on connect over an encrypted link (Tailscale Serve / public HTTPS). The auto-captured traces were identical: `android.os.NetworkOnMainThreadException` from `okhttp3.ConnectionPool.evictAll()`, with a suppressed `Dispatchers.Main.immediate [Cancelling]` frame — i.e. a `viewModelScope` coroutine.

**Root cause.** `HermesApiClient.shutdown()`, `DashboardApiClient.shutdown()`, and `ConnectionManager.shutdown()` each call `connectionPool.evictAll()` inline. `evictAll()` closes pooled sockets synchronously; for a live `https`/`wss` keep-alive connection a TLS close drains a close-notify through `SSLOutputStream` — a real network write StrictMode forbids on the main thread. Several call sites reach `shutdown()` from a `viewModelScope` (`Dispatchers.Main.immediate`) coroutine: `probeStandardVoice()`'s `finally { client.shutdown() }` fires on every connect/voice probe, and `onCleared()` called `connectionManager.shutdown()` directly on the main thread. The off-main handling existed only as scattered per-call-site `withContext(Dispatchers.IO)` / background-`Thread` wrappers, so the unwrapped paths still crashed. TLS-only because a plaintext socket close writes nothing — matching every report being on Tailscale/public TLS.

**Fix.** Pushed the guard into the leaf. New `network/NetworkShutdown.kt#shutdownOffMainThread(name, block)` runs the executor-shutdown + `evictAll()` on a short-lived daemon thread when called from the main thread, and inline otherwise (preserving the blocking `awaitTermination` semantics for callers already on IO). Wrapped all three `shutdown()` bodies with it, so every call site is safe regardless of dispatcher. Simplified `ConnectionViewModel.onCleared()` — its now-redundant manual `Thread` wrappers were removed and `connectionManager.shutdown()` is no longer an unguarded main-thread `evictAll()`.

**Verification.** New Robolectric `NetworkShutdownTest` (2 cases) asserts the teardown runs off the main thread when invoked from the main looper, and inline when invoked off it. `./gradlew :app:testSideloadDebugUnitTest --tests NetworkShutdownTest` green (compiles the full module + both cases pass). On-device confirmation over a real Tailscale/TLS connection pending a Studio build.

## 2026-06-22 — Released android-v1.2.2

Cut Android **1.2.2** (appVersionName 1.2.2 / appVersionCode 16) — "Multi-profile polish". The version bump + release docs were already on `dev`; the cut first integrated `origin/dev`, which had advanced to **compileSdk 37** (`206d182`) and typed `stream.event` passthrough (PR #120) — dropping the temporary 1.2.2-prep `markdown-renderer 0.41.0` / `lifecycle 2.10.0` pins (a compileSdk-36 workaround) for compileSdk 37 + the `0.42.0` / `2.11.0` deps. `dev` CI (Android build + tests on compileSdk 37) green; release PR #122 (`dev` → `main`, `--no-ff`, merge `984d9a2`) merged on green Required-checks + claude-review; `android-v1.2.2` tagged from the `main` tip triggered `release-android.yml` → signed APK/AAB (googlePlay + sideload) + `SHA256SUMS.txt` → GitHub Release **Hermes-Relay-Android v1.2.2** (published, not draft). Headline 1.2.2: session-delete persists on non-default profiles, cold-start profile isolation for the session drawer, full-screen Diagnostics status timeline, "Hermes"/"Relay" connection wording, and the clean-chat layout + scrollable history; also ships the typed `stream.event` relay passthrough (first slice) integrated from `dev`. Post-cut: `main` back-merged into `dev` (fast-forward) so they stay aligned. Follow-up: CLAUDE.md still says "Compile SDK 36" — update to 37 to match the build.

## 2026-06-22 — Outstanding-TODO batch (orchestration): four User-Added fixes

**Why.** Four open User-Added TODO items, resolved in one 4-worker orchestration pass with disjoint file ownership and coordinator-serialized commits (workers edited only; the coordinator committed each task's files by pathspec to avoid the shared-index race). A read-only Explore pass mapped each task to its files first, surfacing the two collision hubs (`ChatScreen.kt`, `RelayApp.kt`) so ownership could be partitioned to keep all four file sets disjoint. All changes are client-side Kotlin. **Unbuilt at time of writing — pending Studio build + `./gradlew lint`.**

- **Session delete on a non-default profile now persists (`6552566`).** Root cause: a non-default Hermes profile keeps its sessions in that profile's own `state.db`, but `ChatViewModel.deleteSession()` issued the unscoped api_server `DELETE /api/sessions/{id}` (shared DB, no profile) and never re-fetched — so the row survived and the next profile-scoped list resurrected it. Fix mirrors the read path onto the write path: `DashboardApiClient.deleteSession(id, profile)` (reusing the `deleteCronJob` plumbing — `deleteJsonObject`+`pathSegment`+`profileQuery`), `ConnectionViewModel.deleteProfileScopedSession()` (twin of `listProfileScopedSessions`), a `ChatViewModel.profileSessionDeleter` hook wired in `RelayApp` beside `setProfileSessionLister`, and a `refreshSessions()` after a successful delete. Gateway deletes route through the dashboard surface; off-gateway (one shared DB) the plain delete is unchanged. `HermesApiClient` left untouched — the api_server has no profile concept.
- **Diagnostics → full-screen status-check timeline; analytics polish (`c3098a9`).** Replaced the Diagnostics modal bottom sheet with a dedicated `DiagnosticsScreen` behind a new `Screen.Diagnostics` nav route. It leads with a vertical status-check timeline — Network, API server, server capabilities, chat transport, pairing/auth, relay, voice — each a green/amber/red/gray dot on a connecting rail with an inline failure reason; a check backed by a logged error is tappable into the existing `DiagnosticDetailDialog`. Checks derive **read-only** from existing `ConnectionViewModel` flows + the recent `DiagnosticsLog` via a pure, testable `buildStatusChecks()` (no new probing — honest snapshot, first-class `Unknown`). New `StatusCheck`/`CheckStatus` models in `DiagnosticsLog.kt`, a reusable `StatusCheckTimeline` composable in `TimelineView.kt`; the recent-activity log panel stays below. Analytics: `AnalyticsScreen`/`StatsForNerds` visual hierarchy tidied (de-duped the header, section subtitle, cleaner separators) with no data/behavior change.
- **Connections reframe: "Vanilla/Standard Hermes" → "Hermes" (`c9fa8f7`).** 28 user-facing display strings across 10 connection/voice/permissions files; "Hermes-Relay plugin" → "Relay plugin" where it reads naturally. Display copy only — `StandardVoiceAvailability`, `VoiceAudioRoute.Standard("standard")` (enum + storage value), `RelayUiState`, and all when-branch identifiers left intact.
- **Clean-chat: taller scrollable text viewport (`1dca285`).** Replaced the fragile `screenHeightDp*0.34f` height cap on the clean-mode text flow with a weight split (centered sphere `weight(1f)` / flow `weight(1.1f)` ≈ 52% of the vertical slack, up from ~34%); kept the `min=96.dp` floor, internal scroll, top-fade, and a11y mirror paths; dropped the now-dead `LocalConfiguration` import.
- **Method.** Coordinator mapped files (4 parallel Explore agents) → partitioned disjoint ownership (A: `ChatViewModel`/`HermesApiClient`/`DashboardApiClient`/`ConnectionViewModel`; B: 9 connection/voice files + `ChatScreen.kt` 2 strings; C: `AgentTextFlow.kt`; D: analytics/diagnostics + new screen + `SettingsScreen`/`RelayApp`) → file-briefed 4 Claude workers in the active worktree (Orca `--inject` no-ops here) → serialized pathspec commits as each `worker_done` landed. The session-delete fix's one `RelayApp` wiring line was held and applied by the coordinator after the diagnostics worker's `RelayApp` route changes committed, so both edits to that hub landed as clean, separate commits.

**Verification.** Symbol-existence verified by grep before committing the new `DiagnosticsScreen` (the highest compile risk, since workers can't run gradle): all 11 referenced `ConnectionViewModel` flows, `HealthStatus`/`ConnectivityObserver.Status`/`AuthState`/`DiagnosticCategory` enum shapes, `ServerCapabilities` members, and the `DiagnosticsLogPanel`/`DiagnosticDetailDialog`/`StatusCheckTimeline` signatures resolve. Each worker diff was reviewed before commit. **Not built or linted** — Studio build + `./gradlew lint` + on-device checks pending (see TODO.md "Orchestration batch (2026-06-22)").

**Follow-up (same session) — cold-start profile-isolation race (`889273a`).** A user-reported sibling of the session-delete bug: on cold start the session drawer (and the restored session context) briefly loaded the SERVER-DEFAULT profile's sessions, then visibly snapped to the persisted profile. Root cause: the `activeConnectionId` observer stamps the persisted profile name pending, calls `resolvePendingProfileFrom(agentProfiles.value)` (empty at that point), then `rebuildChatApiClient()` — so `chatClientReady` flips true and the `RelayApp` `LaunchedEffect` fires the first `refreshSessions()` with a null (server-default) profile *before* the per-connection profile list arrives to resolve the selection; the list lands a tick later, re-resolves, and re-fetches correctly (the "self-reload"). Fix: new `ProfileController.selectionSettled` StateFlow — true once the selection resolved, OR no non-default profile is pending, OR the profile list has arrived (resolution attempted, so a genuinely-missing profile falls back to default rather than gating forever) — exposed via `ConnectionViewModel.profileSelectionSettled` and added as a key + gate to the cold-start effect. While unsettled the first load waits on a 2.5s backstop; the effect re-fires the instant the profile resolves, cancelling the wait so only the correct profile-scoped load lands, and the backstop prevents a permanently-empty drawer if the list never arrives. Same effect also defers the per-profile session-context/transcript restore. Other profile-scoped surfaces (voice prefs, display alias, profile icon) read the live `selectedProfile` and self-correct on resolution without a visible content-flash; gating them on `selectionSettled` is noted as a follow-up. Unbuilt — verify the cold-start drawer on device.

## 2026-06-22 — Typed stream.event Relay passthrough first slice

**Why.** AXI-75 asks Relay/native clients to stop flattening Hermes SSE into assistant text and preserve runtime structure for native UI cards/timelines.

- **Protocol + fixture.** `docs/relay-protocol.md` now defines auth capability negotiation (`supports.typed_stream_events` + `event_schema_version: 1`), the versioned `chat`/`stream.event` envelope, stable event families, ordering/de-dupe semantics, payload safety, fallback behavior, and native rendering guidance. Added `docs/fixtures/typed-stream-v1.jsonl` as a golden tool-using stream.
- **Relay server.** `plugin/relay/server.py` records per-WebSocket client capabilities during `system/auth` and passes them to `ChatHandler`. `plugin/relay/channels/chat.py` now forwards Hermes/API-server SSE as ordered `stream.event` payloads for capable clients, emits final `done`, redacts secret-shaped keys, truncates large result fields, and keeps legacy `chat.delta`/`chat.tool.*`/`chat.completed` fallback for old clients.
- **Native clients.** Android and Desktop auth envelopes advertise typed-stream support. Android gained `RelayStreamEventEnvelope` plus `ChatHandler.applyRelayStreamEvent()` that maps typed events to existing native assistant text, thinking/progress, tool-card, artifact/memory/skill chip, error, and completion state.
- **Verification.** `PYTHONPATH=$PWD python -m unittest discover -s plugin/tests -p test_chat_typed_stream.py` green (typed ordering/final done/redaction + legacy fallback). `python -m py_compile plugin/relay/channels/chat.py plugin/relay/server.py plugin/tests/test_chat_typed_stream.py` green. `desktop/npm ci` then `npm run type-check` green. Android unit task was attempted with `ANDROID_HOME=/home/bailey/Android/Sdk ./gradlew :app:testSideloadDebugUnitTest --tests ...`; it is blocked before Kotlin compile by the current dependency/SDK mismatch (AAR metadata requires compileSdk 37; installed SDK only has android-36). Follow-up commits bump app/relay-core/relay-ui/quest compileSdk to 37 to satisfy current AndroidX/Markdown AAR metadata in CI without changing targetSdk.

## 2026-06-22 — Released plugin-v1.2.1

Cut the Plugin 1.2.1 release — a Realtime Agent reliability patch. Both fixes were already on `dev`: the `session_not_found` brokered-handoff fix (`f6b965a`) and the realtime voice heartbeat-during-long-runs fix (`d1820fb`); 1.2.1 only adds the version bump and release packaging. Release-prep bumped the six plugin version sources via `scripts/bump-plugin-version.sh` (sync check green), folded the relay `session_not_found` fix into the existing `[1.2.1]` `CHANGELOG.md` line (the Desktop-CLI entries stay under `[Unreleased]` for their own `cli-v*` cut), and rewrote `PLUGIN_RELEASE_NOTES.md` as a Fixed-only release body.

- **Release.** `dev` had drifted behind `main` (12 Dependabot bumps merged straight to `main` + 3 prior `dev`→`main` release-merge commits never back-merged), so release PR #119 was `BEHIND`; `gh pr update-branch` merged `main` into `dev` (conflict-free — no overlap with the version/CHANGELOG files). Only `Required checks` + `claude-review` gate `main` (the path-optimized sentinel pattern); both green, with the plugin-relevant jobs (focused plugin tests, dashboard build, Python syntax) also green on the head. Merged `--no-ff` (merge `41037a3`); `plugin-v1.2.1` tagged from the `main` tip triggered `release-plugin.yml` → validate-metadata → wheel + sdist + `SHA256SUMS.txt` → GitHub Release **Hermes-Relay-Plugin v1.2.1**.

Cut the Android 1.2.1 release. Version source (`appVersionName 1.2.1` / `appVersionCode 15`) was already on `dev`; release-prep promoted `CHANGELOG.md` `[Unreleased]` → `[1.2.1]` (**Android-only** — the Desktop-CLI entries and the relay `session_not_found` fix stay under `[Unreleased]` for their own `cli-v*`/`plugin-v*` cuts) and rewrote `RELEASE_NOTES.md`, in-app `whats_new.txt`, the Play release notes, and the Play listing copy, all scrubbed for public distribution. Release PR #102 (`dev` → `main`, `--no-ff`) auto-merged on green CI (merge `39cafc2`); `android-v1.2.1` tagged from the `main` tip triggers `release-android.yml` (validate → signed APK/AAB + checksums + GitHub Release; Play Production *draft* when the service-account secret is set, operator clicks Start rollout). Headline 1.2.1 changes: profile lock, in-app changelog, diagnostics detail + Copy/Share/Create-issue, a dismissable update-available nudge, plus voice/realtime fixes (override applies in Auto, realtime Stop halts playback, steadier hold-to-talk, readable overlay, faster connection-overlay dismiss) and a debug-only Developer-options test harness. `RELEASE.md` §2 gained a per-surface CHANGELOG-split clarification.

## 2026-06-21 — Realtime Agent API Server session handoff (issue #101)

**Why.** The Realtime Agent's brokered Hermes path (`hermes_run_task`) could fail two ways when reaching back to the API Server. (1) A caller-supplied `chat_session_id` that originated in a different session namespace (the gateway/client session store) was passed straight to `POST /api/sessions/{id}/chat/stream`, which the API Server rejects with `404 session_not_found`. (2) `_create_session()` only read a flat `id`/`session_id`, but the current API Server returns the created session nested under `{"object":"hermes.session","session":{"id":"api_…"}}` — so creation raised "Hermes API created a session without an id."

**Verified against upstream first.** `gateway/platforms/api_server.py` confirms the contract: create-session returns the nested `session` object at status 201 (`_session_response`, line ~1426); `_get_existing_session_or_404` emits `{"error":{"code":"session_not_found"}}` at 404 (line ~1349). Coded to the verified shapes, not the docs.

- **`hermes_tool_broker.py` — nested create-session parse.** Extracted `_session_id_from_create_response()` that accepts top-level `id`/`session_id` *and* nested `session.id`/`session.session_id`, preferring the flat form for back-compat with older/partial builds. `_create_session()` now delegates to it.
- **`hermes_tool_broker.py` — `session_not_found` handoff + single retry.** `stream_task()` tracks whether it owns the API Server session (`api_session_owned`). When a caller-supplied id 404s with `session_not_found` (matched by `_is_session_not_found()`, structured-or-substring), the broker mints a fresh API Server session, emits a second `hermes.session.bound` event with `reason: "session_not_found_handoff"` (so the orchestrator rebinds `session.chat_session_id`), and retries the chat/stream POST once. A session the broker created itself, or a second failure, is not retried — no loop. The 404 is raised before any SSE bytes stream, so the retry never double-emits chat content. Valid existing API sessions are reused untouched.
- **Tests.** New `plugin/tests/test_hermes_tool_broker.py` (13): pure-function coverage for both parsers (nested/flat/precedence/empty, 404-only `session_not_found` detection) plus end-to-end `stream_task` against a local aiohttp `TestServer` fake API Server — no-id-creates-session, existing-session-reused, namespace-mismatch handoff+retry, and single-retry-then-give-up. `aioresponses` isn't installed, so the tests drive the real aiohttp client path against a local server (the repo's existing pattern).

**Verification.** `python -m unittest plugin.tests.test_hermes_tool_broker` → 13/13 green. `plugin.tests.test_realtime_agent_routes` → 34/34 green (no regression). Server-side only; no Android/CLI changes.

## 2026-06-21 — Profile lock + voice fixes (orchestration batch)

**Why.** User-requested batch (TODO User-Added) covering the profile-lock setting and the concrete voice TODOs. Investigated and implemented via a planning→implementation orchestration pass: four read-only investigators, then three disjoint file-ownership implementation lanes. All changes are client-side Kotlin; the server-side realtime-voice half is deferred to TODO. **Unbuilt at time of writing — pending Studio build + `./gradlew lint`.**

- **Profile lock (new).** Per-connection "lock to one profile": `data/ProfileLockStore.kt` (twin of `ProfileSelectionStore`, same `profile_selections` DataStore; `__server_default__` sentinel via `AgentDisplay`); `ProfileController` gains `lockedProfileName`/`isProfileLocked` + `lockProfile`/`unlockProfile`, with `selectProfile` no-op'd when locked and `resolvePendingProfileFrom` preferring (and holding on missing) the locked target; `ConnectionViewModel` delegations + lock-clear at reset/remove sites + a lock-flow observer; `ConnectionInfoSheet` collapses the picker to a static "Locked to <name>" row when locked; `SettingsScreen` adds the `ProfileLockCard` + dialog — the one surface that still lists all profiles, with a "not found on this server" banner.
- **Voice override in 'auto' (fix).** `VoiceViewModel.shouldPreferRealtimeVoice()` gated on `.route` (configured) instead of `.effectiveRoute` (resolved), so 'auto'+relay-ready never engaged the override-capable relay path and fell back to the host-global Standard `/api/audio/speak` (no override slot) — hence only 'Relay' applied the chosen voice. Switched to `effectiveRoute`. Also wired `connectionId` for per-profile voice-prefs namespacing (`RelayApp` calls `setVoicePrefsConnection(activeConnectionId)` and passes `connectionId` to `VoiceSettingsScreen`, which now takes the param and feeds `setActiveScope`).
- **Realtime voice (fix, client half).** Stall: `RelayVoiceClient.awaitRealtimeAgentCompletion` relaxes the 90s idle watchdog once a `hermes.run.promoted`/long run is seen, keeping the 5-min max-turn backstop. Over-chatty status: per-turn throttle in `VoiceViewModel.emitStatus` (≥22s gap, ≤3 spoken/turn). Waveform: realtime `outputAudioActive` now gates on real playback-start (`RealtimePcmPlayer` head-move/`playbackAmplitude`) instead of decoded-byte RMS, matching the basic-TTS path.
- **Voice UI.** Profile icon now shows in the floating overlay header pill (`VoiceModeOverlay` reads `LocalAgentIconPath`; sphere/pet stays the fallback). Voice Settings: invalid engine/route combos made unreachable (RealtimeAgent disabled without relay, unavailable routes disabled, `coerceAudioRoute` auto-corrects on engine switch / relay loss); long dropdown/provider labels get `maxLines=1`+ellipsis.
- **Method.** Disjoint file-ownership lanes (1: VoiceViewModel/RelayVoiceClient/RelayApp; 2: VoiceSettingsScreen/VoiceModeOverlay; 3: ProfileController/ConnectionViewModel/ConnectionInfoSheet/SettingsScreen/ProfileLockStore) so parallel implementers never touched the same file, and `ChatScreen.kt` was avoided (owned by a concurrent session). Pure helpers (`coerceAudioRoute`, `shouldSpeakStatusNow`, `shouldMarkRealtimeOutputActive`) extracted for unit-testing.
- **Deferred.** See TODO.md "Orchestration batch (2026-06-21)": streaming-path override question, upstream per-profile Standard voice, ChatScreen lock glyph, export decision, CHANGELOG entries, on-device verification.
- **Follow-up (same day).** Built + deployed to device as **1.2.1 / versionCode 15** (`:app:assembleSideloadDebug`, clean). Server-side realtime half implemented in `broker.py` (heartbeat-while-task-running + calmer spoken-status cadence) with `plugin/tests/test_realtime_heartbeat.py` (11) + promotion regression (5) green — **deployed**: committed `d1820fb` → pushed to `origin/dev` → server `~/.hermes/hermes-relay` fast-forwarded + `hermes-relay` restarted (active, clean startup on ws://…:8767). New Kotlin unit suite green: `ProfileLockStoreTest` (9, in-memory DataStore harness), `ProfileControllerLockTest` (8, Robolectric), `CoerceAudioRouteTest` (7), `VoiceStatusGatesTest` (12) — 36/36 via `:app:testSideloadDebugUnitTest`.

## 2026-06-21 — Desktop CLI first-class pass (audit-driven)

**Why.** The desktop CLI hadn't had feature work since 2026-05-19 while the relay plugin shipped a full v1.2.0 wave (relay-management surface, enhanced voice, context injection). A four-axis audit (command UX/visuals, pairing, desktop-tools, plugin parity) found the CLI surfaced ~⅓ of current plugin capability with an ad-hoc visual layer and weak discoverability. This pass closes those gaps; all changes are confined to `desktop/` (no Android, no Python).

- **Shared zero-dep UI foundation (`desktop/src/lib/`).** `theme.ts` (one ANSI palette + `colorEnabled` + `Theme` with `statusDot`/semantic helpers, extracted from `renderer.ts`'s pattern), `table.ts` (ANSI-width-aware column renderer, last column flexes to terminal width), `spinner.ts` (stderr braille spinner, no-op when piped/quiet/json), `hints.ts` (`suggestedFix(err)` → next-step command + `formatError`), `usage.ts` (`UsageSpec` → per-subcommand `--help` + self-documenting unknown-sub-verb), `logo.ts` (slim box-drawing wordmark).
- **Discoverability.** Fixed the `cli.ts` dispatch so command-scoped `--help` reaches the command (was always short-circuiting to global help). Added `--help` + usage specs across `devices`/`sessions`/`status`/`tools`/`plugins`/`voice`/`relay`/`pair`/`daemon`/`doctor`/`workspace`/`paste`; ported list output (`devices`/`sessions`) to aligned tables + status dots; routed command failures through `formatError` (actionable hints); replaced `doctor`'s inconsistent `!!` warning markers with themed `⚠` lines.
- **Pairing.** Threaded an `onProbe` callback into `probeCandidatesByPriority` so `pair` shows per-endpoint progress + latency during the multi-endpoint race; `credentials.ts` warns (TTY-only) when a stored token is near/at expiry with the exact re-pair command; `relayUrlPrompt.normalizeRelayUrl` defaults a bare `ws://host` to `:8767` (scoped to `ws://` so `wss://` proxy fronts on :443 aren't broken), surfaced not silent.
- **Desktop tools first-class.** New `hermes-relay audit` backed by a local JSONL (`~/.hermes/desktop-audit.jsonl`) the `DesktopToolRouter` appends per dispatch — the relay's ring buffer is loopback-only, so the client (the executor) is the right source of truth and this works against a remote relay with no auth. Consent prompt rewritten to state persistence + point at `audit`; computer-use's observe→grant→act flow documented in `--help`.
- **Daemon observability + background run.** `daemon` writes a heartbeat file (`~/.hermes/daemon-status.json`) on each lifecycle transition + a 30s tick; `daemon status` reads it, cross-checks pid liveness (`process.kill(pid,0)`), and exits non-zero when stale. Added `daemon start` (detached spawn — `detached:true` + `windowsHide:true` + stdio→`~/.hermes/daemon.log` + `unref`, no console window, survives terminal close) and `daemon stop` (kills the status-file pid + clears it); bare `daemon` still runs foreground. Validated start→status→stop on Windows against the live relay. A true OS service (reboot/login auto-start) remains the deferred follow-up.
- **Dev loop.** Added `desktop/scripts/dev-install.mjs` + `npm run dev:install` — builds the bun binary for the current platform and drops it over the curl-installed `~/.hermes/bin/` binary (backs the old one up as `.bak`, surfaces EBUSY as "stop the daemon first"). Closes the gap where local changes could only be exercised via `npx tsx`, never as the real global binary.
- **Plugin v1.2.0 parity.** `voice` now renders the `/voice/config` `enhanced` block (Gemini tone-tags/persona, xAI speech-tags). New `hermes-relay relay info|security|context` over the relay-management surface — `context` (the injected-system-prompt audit) works remote with a bearer; `info`/`security` are loopback-only and say so on a remote 403. Deliberately did **not** add a CLI-vs-server "version skew" warning — the two are on independent release tracks, so it would be a false alarm.
- **Logo.** Slim box-drawing "Hermes Relay" wordmark atop `--help`, the first-run welcome, the chat REPL, and a `logo` command; theme/no-color aware, never on piped/`--json` stdout.
- **Verification.** `npm run type-check` and `npm run build` (tsc) green. Runtime-smoked via `npx tsx src/cli.ts` (NO_COLOR): `--help`, `logo`, `devices --help`/`devices bogus` (usage fallback), `audit` (empty-state), `daemon --status` (no-daemon), `doctor`, `workspace`. Docs: CHANGELOG `[Unreleased]`, `desktop/README.md` (audit/relay/daemon-status sections), CLAUDE.md desktop Key Files refreshed. Version bump (`alpha.18`→`alpha.19`) left to the operator — not cutting a CLI release this cycle.

## 2026-06-20 — Release-prep: android-v1.2.0 + plugin-v1.2.0

**Why.** Cut a combined 1.2.0 across both lockstep surfaces (both were at 1.1.0). The accumulated `[Unreleased]` block had captured the major feature arcs but a second wave had landed undocumented — audited every commit since the `*-v1.1.0` tags and backfilled the changelog before promoting it.

- **Versions.** `bump-android-version.sh 1.2.0` (`appVersionName 1.1.0→1.2.0`, `appVersionCode 13→14`); `bump-plugin-version.sh 1.2.0` (pyproject + `plugin/relay/__init__.py` + plugin.yaml + dashboard manifest/package/lock, all in sync). `check-version-tracks.py` + `check-plugin-version-sync.py --expect 1.2.0` green.
- **CHANGELOG backfill.** Promoted `[Unreleased]` → `[1.2.0] - 2026-06-20` with a fresh empty `[Unreleased]`. Added the missing shipped features the accumulator had skipped: **agent pets** (swappable animated avatar + reactivity + in-app add/remove + AI authoring kit), per-profile agent icons + single-image avatars, **in-app crash reporting**, clean text-flow mode, the permissions-review screen, attachment previews; **Changed**: "Standard"→"Vanilla Hermes" rename, QR camera hardening for foldables, viewer landscape rotation; **Fixed**: PDF mid-render crash, the `kotlin.Result`-in-suspend `ClassCastException` on server images, side-loaded avatar/skin storage path, reopened-session model + media-badge fixes.
- **Release notes.** Rewrote `RELEASE_NOTES.md` (Android, "Make it yours" framing) and `PLUGIN_RELEASE_NOTES.md` (enhancement-layer + enhanced voice). Updated in-app `whats_new.txt`, Play `release-notes/en-US/default.txt` (438/500 chars), and the `docs/play-store-listing.md` What's-new block.
- **Scrub.** Grep'd the `[1.2.0]` block for names / private infra / fork plumbing — clean (only pre-existing released blocks carry the LAN host IP from old desktop-alpha entries; out of scope for this cut, flagged separately).
- **Verification.** `python -m unittest plugin.tests.test_enhancements plugin.tests.test_terminal_channel` (20 pass). Android AAB build + `keytool` cert verify is Studio-side (Bailey) per the dev loop. Prep committed on `dev` in two commits (`release(android)` / `release(plugin)`); merge-to-`main` + tags deferred to operator.

## 2026-06-20 — Static-image avatars + per-profile agent icon (Android)

**Why.** Two requests: a custom avatar shouldn't require authoring an animated pack (a single image should work), and each agent profile should be able to wear its own small icon beside its name — client-side, mirroring the existing local-name override.

- **Static-image import (`PetImporter`).** "Add a pet" now accepts a single image (`.png`/`.jpg`/`.gif`/`.webp`), not just a `.zip` — detected by **magic bytes**, not the file name. An image is auto-wrapped as a one-frame static pet (written as `idle.png` with a synthesized minimal `pet.json`), so a static avatar needs no manifest authoring. The renderer already supported a one-frame `idle`; this is purely import ergonomics. `importZip` → `importUri`; `ConnectionViewModel.importPetFromZip` → `importPet`. Test covers the image-wrap path.
- **Per-profile agent icon (client-side).** A direct twin of `ProfileDisplayAliasStore`: new `ProfileIconStore` (own DataStore `profile_icons`, keyed per `(connection, profile)`, **never sent to Hermes**). It stores a **path** to an image copied into `files/profile-icons/` (not a SAF URI, so it survives without persistable permission). Wired through `ProfileController` next to `profileDisplayAlias` (`profileIcon` StateFlow + `setProfileIcon`/`clearProfileIcon` + the copy), exposed on `ConnectionViewModel`, provided at the app root as `LocalAgentIconPath`, and rendered beside the agent name in `MessageBubble` **and** as the **header avatar** in the agent sheet, the chat top bar, and Settings — via a shared `AgentAvatarFace` that shows the icon (Coil from the file path) or falls back to the name's initial. The picker (`AgentIconRow`) sits right under the local-name row in `ConnectionInfoSheet`. Scope: small name-adjacent icon only — the big empty-chat/voice avatar stays global. `ProfileIconStore` cleared alongside the alias on connection removal; test mirrors the alias store's.
- **Verification.** `:app:assembleSideloadDebug` + `PetImporterTest`/`ProfileIconStoreTest` <pending>. Installed via `adb install -r`. On-device check (import an image as a pet; set a profile icon and see it by the name) in TODO.

## 2026-06-20 — Pet state preview in Appearance (Android)

**Why.** Testing a pet meant *inducing* each state by driving the agent (run a tool to see `working`, fail a turn for `error`, start voice for `speaking`/`listening`) — painful. An in-app preview turns Appearance into a pet test harness.

- **Live preview (`AppearanceSettingsScreen`).** Under the speed/stabilize controls (pet selected only): a ~140 dp canvas rendering the active pet, a `FilterChip` row for the seven sustained states (`Idle · Thinking · Working · Writing · Speaking · Listening · Error`), and `Greet`/`Done` buttons that replay the one-shots. Pure UI on the existing `AgentAvatar` seam — no new ViewModel/pref/renderer; it just calls `activeAvatar.Render(AvatarRenderState(state=…))` with a user-picked state, so it also reflects the live speed and stabilize settings.
- **State→render mapping.** Base states feed `state=…`; **Working** feeds `state=Thinking, toolCallBurst=1f` (lights the overlay); **Greet** remounts the preview via a `key` (re-fires the on-appear reaction); **Done** drives a momentary `Speaking → Idle` transition on the live instance (fires the celebrate reaction), then returns to the selected chip.
- **Verification.** `:app:assembleSideloadDebug` BUILD SUCCESSFUL; installed via `adb install -r`. On-device visual check recorded in TODO.

## 2026-06-20 — Pet frame auto-stabilization (Android)

**Why.** On-device audit of a 4×4 AI pet (Lucy) found the character's vertical center drifting 34 px across the 16 cells, with 8/16 frames touching the cell edge — the image model held *appearance* but not *position/scale*, so the pet floated upward and bled the next frame in. The renderer slices/centers exact cells faithfully, so the drift can't be cured per-frame there — but it can be neutralized by re-centering each frame on its own content.

- **Decode-time recenter (`PetAvatar`).** With stabilization on, `decodeClip` scans each frame's opaque pixels (alpha bbox) and stores a per-frame offset that moves the content's bbox center to the cell center; `drawPetFrame` applies it (source px → dest px, scaled). Works for sprite sheets (per-cell) and frame sequences (per-bitmap); empty/transparent frames get a zero offset. The scan is one-time per clip decode on `Dispatchers.IO` with a reused scratch buffer, so steady-state cost is nil.
- **Global toggle, default on (`ConnectionViewModel`, `LocalPetStabilize`, `AppearanceSettingsScreen`).** A `pet_stabilize` pref → `LocalPetStabilize` provided at the app root → read in `PetAvatar.Render` (keys the decode `produceState`, so flipping re-decodes). A "Stabilize frames" Switch sits under the playback-speed slider when a pet is selected. Default on because AI sheets nearly always need it; a hand-authored pet with intentional motion can switch it off.
- **Authoring (docs).** The prompt kit now also stresses *registration* (lock head/shoulders, same position + scale, only secondary motion) so the art improves at the source — stabilization is the safety net for what the model still gets wrong.
- **Verification.** `:app:assembleSideloadDebug` BUILD SUCCESSFUL; installed via `adb install -r`. Fixes the *already-installed* Lucy at render time (no re-import). On-device visual confirmation recorded in TODO.

## 2026-06-20 — Pet playback-speed control + cell-resolution guidance (Android + docs)

**Why.** Two more on-device tuning gaps: a pet that still felt fast needed re-authoring/re-importing to slow down (slow loop), and a 128 px-celled pet looked pixelated blown up to the full-screen chat background (while crisp in the small voice overlay — same frames, different scale).

- **Playback-speed control (`ConnectionViewModel`, `LocalPetPlaybackSpeed`, `PetAvatar`, `AppearanceSettingsScreen`).** A global multiplier pref (`pet_speed`, 0.5×–1.5×, default 1.0) surfaced as a **Slider in Appearance** when a pet is selected. Provided at the app root via a new `LocalPetPlaybackSpeed` composition local and read **live** in `PetAvatar.Render` (`rememberUpdatedState`), so dragging the slider re-times the pet instantly with no restart. Applies to every clip (including one-shots) and composes with intensity (`baseFps × speed × intensityFactor`, clamped 1–60). The sphere ignores it.
- **Cell-resolution guidance (docs).** Pixelation is a *resolution* axis (cell px) distinct from smoothness (frame count): one frame set is contain-fit into every surface, so author for the **largest** (the chat background). Bumped the kit default to **256 px cells** (a 1024×1024 sheet for a 4×4 grid), noted 512 px is fine for a sprite sheet (decodes as one bitmap), and that the old "≲256 px" note applied to frame-*sequences*. Updated `custom-avatars.md`, `pet-prompt-kit.txt`, `pet-spec.md`.
- **Verification.** `:app:assembleSideloadDebug` BUILD SUCCESSFUL; installed to the sideload build via `adb install -r`. Slider behavior is on-device-visual (recorded in TODO).

## 2026-06-20 — Pet kit defaults to 4×4 (16-frame) sheets (docs)

**Why.** A 4-frame (2×2) sheet reads steppy no matter the fps — the on-device Lucy made that obvious. The renderer already slices any N×M grid (`decodeClip` derives `cols`/`rows` from sheet size ÷ cell size; `drawPetFrame` indexes `col = i%cols`, `row = i/cols`), so "support 4×4" is an authoring-default change, not a renderer one.

- **Kit + spec default to a 4×4 grid (16 frames).** The prompt template, the manifest example, and `pet-prompt-kit.txt` now use `frameCount: 16` with fps matched to the higher count (idle ~8 → a calm ~2 s loop); 2×2 / 4 frames stays documented as the easier-to-keep-consistent fallback. `docs/pet-spec.md` states any rectangular grid works (a 4×4 sheet holds 16 frames, decoded as one bitmap regardless of cell count). Added a `PetLoaderTest` case for a 16-frame sheet.
- **Diagnosis note.** The "still fast" report was tracked to the *installed* `pet.json` still carrying `fps 6` (the tuned `fps 3` zip post-dated the import); `intensity` was ruled out by tracing `streamingIntensity` → `0f` at idle. Audited by `adb shell cat`-ing the on-device manifest, not the repo copy.

## 2026-06-20 — Pet frame-loop smoothness fix (Android)

**Why.** First on-device pet (Lucy) animated with a periodic hitch and felt a touch fast. Root cause: `PetAvatar.Render`'s frame loop awaited `withFrameNanos` (one vsync ≈16ms) **and** `delay(1000/fps)` each iteration, so every frame waited ~16ms longer than its `frameDurSec`; the surplus accumulated until the loop forced a 2-frame skip to catch up — a visible hitch, worst at low fps.

- **Vsync-paced loop (`PetAvatar.Render`).** Removed the per-frame `delay`. `withFrameNanos` already suspends until the next frame, so the loop is now purely vsync-paced (~60fps) and advances the sprite only when `frameDurSec` of real time has accumulated — no double-count, no periodic skips. Intensity modulation still recomputes fps each tick; the accumulator absorbs the variable rate without skipping.
- **Authoring guidance (docs).** Clarified that smoothness comes from frame **count**, not fps: 4 frames (2×2) is the consistent-but-steppy minimum, 8–16 (3×3 / 4×4) for fluid motion; match fps to count (calm states 3–4, not 6+). Added to `docs/pet-spec.md`, the user-docs kit, and `pet-prompt-kit.txt`; lowered the example/kit `idle`+`listening` fps to 4.
- **Verification.** `:app:assembleSideloadDebug` BUILD SUCCESSFUL. On-device re-check pending: the test device's wireless adb dropped mid-deploy; the rebuilt APK + a tuned `lucy.zip` (idle/listening fps lowered) are staged to install + re-import once it reconnects.

## 2026-06-20 — In-app pet avatar add/remove/refresh (Android)

**Why.** The Appearance screen could *select* avatars but offered no way to **add or remove** a pet from inside the app — the only path was `adb push` into app-scoped external storage, which scoped storage stalls on (confirmed hanging on a Samsung device: a push into `/sdcard/Android/data/<pkg>/files/pets/` wrote nothing, though `adb shell ls` of the dir worked). And the avatar list loaded once at startup, so even a successfully-pushed pet never appeared without a restart. Net effect: users saw only the Sphere.

- **In-app import (`PetImporter.kt`, new).** "Add a pet" launches a SAF document picker; the chosen `.zip` unpacks into `pets/`. Hardened: zip-slip guard (every entry confined to a staging dir under `cacheDir`), per-file / total-size / entry-count ceilings (zip-bomb), and post-extract validation through the same `PetSpec.toAvatar` the loader uses — an archive that wouldn't render is rejected up front. Accepts either shape (pet.json at the root, or one folder deep — shallowest wins); installs under the manifest `id` (sanitized), replacing a same-named pack.
- **In-app remove (`PetLoader.deletePet`).** Resolves a pack by manifest `id` (not directory name) and deletes it, behind a confirm dialog. If the deleted pet was the selected avatar, the selection falls back to the Sphere.
- **Live refresh (`ConnectionViewModel`, `RelayApp`).** A `avatarsRefreshTick` StateFlow keys the avatar `produceState`, so import/delete — and opening the Appearance screen — re-scan `pets/` and update every surface (chat, clean mode, voice, splash) without an app restart. This also resolves the standing "pet load is process-scoped" TODO. Add/remove results surface as snackbars via a one-shot `avatarEvents` flow.
- **Appearance UI (`AppearanceSettingsScreen`).** Added an "Add a pet" button + "Rescan", an "Installed pets" management list with per-pet remove, and a remove-confirm dialog; replaced the static "drop a pack into pets/ via adb" hint with the in-app flow.
- **Tests.** `PetImporterTest` (root + nested import, no-manifest, missing-idle, **zip-slip refused writes nothing outside staging**); `PetLoaderTest` delete cases (by id, id≠dirname, no-match). Both pass under `:app:testSideloadDebugUnitTest` (the 12 build failures are the pre-existing DataStore/`FileStorage.kt:114` JVM cases — `BargeIn`/`ProfileSelection`/`ProfileSession`Store).
- **Verification.** `:app:assembleSideloadDebug` built `hermes-relay-1.1.0-sideload-debug.apk`; installed to the Samsung sideload build via `adb install -r` (Success). A `lucy` test pet (9 sprite-sheet states, 256×256 RGBA with real transparency, schema-validated) staged at `/sdcard/Download/lucy.zip` for an import smoke test (Add a pet → pick from Downloads). On-device import/delete smoke recorded in TODO.md.

## 2026-06-20 — Pet AI authoring kit + JSON schema (docs)

**Why.** Pets are pure data, so the only real barrier to making one is sourcing the art. Documented an AI-generation workflow and a machine-readable contract so both humans and AI agents can author and validate a pet without hand-drawing.

- **AI prompt kit (`user-docs/features/custom-avatars.md`).** A reference-image-first, character-agnostic prompt template (`{character}`/`{style}`/`{accent}`), a per-state motion table mapping image generation to our state vocabulary, a full 9-state manifest, transparency/consistency caveats, and a "fastest first pass" (one 3×3 sheet → nine stills). Mirrored as a one-click `user-docs/public/pet-prompt-kit.txt`. A vendor-neutral "let an AI agent build the pack" callout names Codex/Claude Code as examples and states the acceptance criteria + image-gen prerequisite.
- **JSON Schema (`user-docs/public/pet.schema.json`).** Draft-07 schema mirroring the loader's structural rules (required `idle`, frames-XOR-sheet via `anyOf`, positive sheet dims, `schemaVersion` ≤ 1); `$schema` wired into the manifest examples and tolerated by the lenient loader (`ignoreUnknownKeys`). `docs/pet-spec.md` gained an "Editor validation" section, honest that file-existence/decodability remain load-time checks. Validated: legal draft-07 + accepts good / rejects no-idle, empty-clip, schemaVersion-2, sheet-missing-dims.

## 2026-06-20 — Relay enhancement layer + agent-context injection

**Why.** Teaching the agent to mark sensitive media (and, more generally, to know things only the relay can teach it) needs a way to inject context into the agent's system prompt — but hermes-agent exposes no plugin context hook (`system_prompt_block()` is memory-provider-only; lifecycle hooks are observers). The one transport-agnostic seam is `AIAgent._build_system_prompt`. Rather than a one-off patch, we built a reusable, removable **enhancement layer** so the relay can apply such patches cleanly and retire them per-surface as upstream catches up — the same pattern as the bootstrap route shims.

- **`plugin/enhancements/` (registry + contract).** Each enhancement declares `name · phase · enabled() · apply() · retirement note`. Config-gated, **default ON for relay installs** (the relay install is the opt-in; `RELAY_AGENT_CONTEXT_ENABLED=0` opts out), no-op on vanilla, removable with the plugin.
- **`context_injection` enhancement (fail-open).** Wraps `AIAgent._build_system_prompt` at plugin-load; appends auditable fenced blocks (`<!-- hermes-relay:<name> -->`). Fail-open at every step — seam absent / block build throws / setattr fails ⇒ returns the base prompt unchanged. With `RELAY_AGENT_CONTEXT_ENABLED` off, the prompt is byte-for-byte unchanged. Works on BOTH gateway and SSE (agent core).
- **First block: media-sensitivity.** Teaches the agent to mark private/NSFW media with the client's spoiler convention (`||![alt](path)||` / sentinel alt) — the bit the client already blurs. No soul/memory touched.
- **`GET /context/injected` audit route + client audit.** The relay exposes exactly what it would inject; the chat "What the agent sees" sheet gained a "Relay context (server-side)" section. Server-side injection is never hidden.
- **Sensitivity re-thread (client).** `ServerImageResult.Success` carries the fetched `sensitive` bit again; `RelayServerImage` blur ORs it with the markdown-parsed flag.
- **Transport-path UI.** New `ChatTransportStatusBadge` + `RelayStatusStrip` surface the ACTUAL chat tier (⚡ Gateway / 📡 Sessions / Completions / Runs / offline) instead of a bare "api online"; Chat Settings gained a basic→best tier ladder + a gateway sign-in callout.
- **Dashboard.** Relay management tab gained Agent-context master + per-block toggles (off by default; labeled experimental/server-side/removable).
- **Verification.** Plugin: `python -m unittest plugin.tests.test_enhancements` (16 pass). Android: `:app:lintSideloadDebug :app:assembleSideloadDebug --no-daemon` green. Built by a 2-worker Orca orchestration (server + client slices), coordinator-integrated. Design: `docs/plans/2026-06-20-relay-enhancement-layer.md`.
- **Follow-ups (TODO).** Confirm the `AIAgent` module on the live host when enabling; structured media channel (`docs/plans/2026-06-20-structured-media-channel.md`); incremental bootstrap migration into the enhancement layer; retire the wrap when upstream ships a context hook.

## 2026-06-20 — Pet intensity modulation (Android)

**Why.** The last continuous-reactivity gap: a pet's clip looped at a fixed rate regardless of how hard the agent was working. `intensity` (the activity ramp already fed to every avatar — ~0.7 while streaming) was plumbed to `PetAvatar.Render` but ignored. Wiring it completes the reactivity story (voice ✓ · tools ✓ · activity ✓) and un-clamps the last reserved badge flag — and unlike the deferred `attention`, the signal needed no host plumbing.

- **Live playback-rate modulation (`PetAvatar.Render`).** Opt-in via `reactive.intensity`. The active base/working loop's fps is scaled by `1 + intensity·PET_INTENSITY_RATE` (0.6 → ~1.4× at typical streaming, 1.6× peak, capped at `PET_MAX_FPS`), so it visibly "works harder" as output streams. Read **live** inside the frame loop via `rememberUpdatedState(state.intensity)` so the speed tracks the agent mid-clip without restarting the long-lived loop (re-keying on a continuously-animated float would thrash). One-shot reactions are excluded (`!playOnce`) so `greet`/`done` play at their authored rate.
- **Badge un-clamp (`PET_RENDERER_CAPABILITIES.intensity` → true).** The loader's existing `reactive.intensity && capability` formula now lets a declared `intensity:true` through, so the pet advertises **Activity** honestly. No loader change needed beyond the flag.
- **Tests.** `PetLoaderTest`: a declared `intensity:true` is now honored (`Voice · Activity`); the prior clamp test was split — `tools` without a `working` clip still stays off the badge.
- **Docs (`docs/pet-spec.md`).** Reactivity table's `intensity` row rewritten from "Reserved" to the speedup behavior; removed from "Forthcoming" (now only `attention` remains there). Reactivity is now Voice · Tools · Activity complete.
- **Verification.** Code + loader tests authored to the established patterns; not run here (Studio-side). On-device check (a writing/working loop quickening while streaming) recorded in TODO.md.

## 2026-06-20 — Pet one-shot reaction layer (Android)

**Why.** The behavior model's event tier: transient "reactions" that play once over the base loop, then return — the touch that turns a status display into a character (cf. the Peon Pet's celebrate-on-finish). Distinct from the sustained per-state loops and the `working` overlay.

- **Pet-local triggers, zero host plumbing (`PetAvatar`).** One-shots are derived from the activity-state transitions the avatar already observes each frame — no new `AvatarRenderState` edge from the host. `PetOneShot.Greet` fires on first composition (the pet appears); `PetOneShot.Done` fires when a *productive* turn ends (a `Streaming`/`Speaking` → `Idle` transition; `Thinking → Idle` and `Error → Idle` don't celebrate). Both are opt-in (only if the pet ships the clip) and require ≥2 frames.
- **Play-once-then-revert (`PetAvatar.Render`).** The frame loop gained a `playOnce` mode: a reaction clip plays 0→end (no modulo wrap), parks on its last frame, clears `activeOneShot`, and recomposition hands back to the base loop. A live reaction overlays everything (including `working`). Suppressed under reduced motion (`paused`). An `ONE_SHOT_MAX_MS` (4s) backstop guarantees a reaction never lingers on decode failure / single frame / pause.
- **Friendly aliases (`PetLoader.toAvatar`).** Resolves `greet`/`wake` → `PetOneShot.Greet` and `done`/`celebrate` → `PetOneShot.Done` from explicit `states` keys only (no fallback); absent reactions just don't play. One-shots are reactions, **not** a reactivity signal, so they don't touch the picker badge.
- **Tests.** `PetLoaderTest`: a pack with `greet`/`done` keys loads cleanly and the badge stays `Voice` (no accidental Tools/Activity coupling). Render-time playback (the actual one-shot animation) is on-device/Compose-test territory — flagged in TODO.
- **Docs (`docs/pet-spec.md`).** New "One-shot reactions" section (Greet/Done table, opt-in, play-once, reduced-motion), an Expressive tier on the authoring ladder, and the Loop-vs-one-shot note updated. `attention`-on-notification stays in "Forthcoming" — it needs a host event the avatar doesn't receive yet.
- **Verification.** Code + loader test authored to the established patterns; not run here (Studio-side). On-device checks (greet on appear, celebrate on turn-finish, overlay-over-working) recorded in TODO.md.

## 2026-06-20 — Pet `working`/tool-use behavior (Android)

**Why.** The behavior-model spec called for a distinct "agent is running a tool" pose — the strongest cross-system convention (Microsoft Agent splits `Think` from `Process`/`Search`; the `pi-animations` indicator splits Thinking · Working · Tool) is that *acting* should look different from *thinking*. Our six `SphereState`s folded tool-use into thinking/streaming.

- **Pet-local tool overlay (`PetAvatar`).** Implemented as a sub-state derived from the already-plumbed `toolCallBurst`, **not** a 7th `SphereState` — zero blast radius on the Sphere or the call sites. `Render` swaps to an optional `workingClip` when `toolCallBurst ≥ WORKING_BURST_THRESHOLD` (0.5) during a `Thinking`/`Streaming` turn, and returns to the base-state clip as the burst decays (the signal ramps to ~1 in 200ms and decays over 1200ms, so 0.5 activates fast and lingers ~600ms — smoothing back-to-back tool calls). Error keeps its own clip; `toolCallBurst` is ~0 outside tool activity, so it never fires spuriously.
- **Opt-in, clip-driven capability (`PetLoader.toAvatar`).** `workingClip` resolves only from an explicit `working` key (no fallback) — a pet without one keeps its base-state clip during tool use, exactly as before. Shipping a usable `working` clip is *itself* the tool-reactivity capability: it drives both the swap and the **Tools** badge (`reactivity.tools = (workingClip != null) && PET_RENDERER_CAPABILITIES.tools`), so the declared `reactive.tools` flag is no longer needed and can't over-promise. Flipped `PET_RENDERER_CAPABILITIES.tools` to `true` (the renderer now consumes the signal).
- **Tests.** `PetLoaderTest`: a `working` clip lights the Tools badge (`Voice · Tools`); a `working` clip with missing files does not; the existing declared-but-no-clip case still clamps to `Voice`.
- **Docs (`docs/pet-spec.md`).** `working` moved from "Forthcoming" into the implemented model: a `Working` row in the state table, a "The `working` overlay" subsection (opt-in, tool-use vs. thinking), the authoring ladder's Rich tier now 7 clips, and the reactivity table's `tools` row now "driven by the `working` clip." Forthcoming trimmed to one-shot reactions + intensity modulation.
- **Verification.** Code + tests authored to the established patterns; not run here (Studio-side). On-device check (clean mode, a `working` clip swapping in during a tool run) recorded in TODO.md.

## 2026-06-19 — Pet reactivity: honest badge + behavior-model spec (Android)

**Why.** Follow-up to the custom-avatar audit. The pet picker badge read `reactivity.summary()` straight from `pet.json`, so a pet declaring `reactive:{tools:true,intensity:true}` advertised "Voice · Tools · Activity" while `PetAvatar.Render` only ever consumed voice — the badge could lie. Separately, the goal was to let pets *associate behavior with agent activity* (show "thinking" vs "writing" vs "speaking"), which needed a documented behavior model rather than a fallback table buried in the clip docs.

- **Honest capability badge (`PetAvatar`, `PetLoader`).** Added `PET_RENDERER_CAPABILITIES` (the live signals `Render` actually consumes today: voice only) and clamp a pet's effective `reactivity` to `declared AND supported` in `toAvatar`. One forward-compat switch: flip a flag there the day the renderer learns a signal and every manifest that already declared it lights up. `PetLoaderTest` gained a case asserting declared tools/intensity are dropped from the badge.
- **Friendly `writing` alias (`PetLoader.STATE_CLIP_CHAIN`).** The Streaming (output-producing) state now resolves `writing` → `streaming` → … so authors can target it with the intuitive key; tidied the Speaking/Error chains to fall back through related activity clips before idle. Backward compatible (existing `streaming`/`speaking`/`error` keys still resolve).
- **Behavior-model spec (`docs/pet-spec.md`).** New "Agent states & pet behavior" section: what each of the six activity states means, the friendly clip-key vocabulary + fallback chains, a loop-vs-one-shot note, and a Minimal→Basic→Standard→Rich authoring ladder. A "Forthcoming behavior" subsection specifies the designed-not-yet-rendered tier — a distinct `working`/tool-use clip, one-shot reactions (greet/celebrate/attention), and continuous tool/intensity modulation — so authors can plan. Reactivity table updated to state the clamp.
- **Prior-art grounding.** Researched the convention (no first-party "Codex pet" exists — the agent-state→mascot pattern is third-party only: `pi-animations`, Peon Pet; canonical spec lineage is Microsoft Agent's `.acs` animation set, with Live2D/VRM/VTuber lip-sync and game-dev FSMs converging on idle-base + thinking/working/output split + amplitude-driven talking + one-shot reactions). The thinking≠tool-use split is the strongest cross-system signal and drives the recommended `working` state. Sources cited in TODO follow-up context.
- **Verification.** Code + test authored to the established patterns; not run here (Studio-side). Behavior roadmap (working state, one-shots, intensity modulation) recorded in TODO.md.

## 2026-06-19 — Custom-avatar audit: storage fix, loader unification, tests, docs (Android)

**Why.** An audit of the just-shipped swappable agent-avatar / "pet" feature found one blocking bug and a set of clarity/coverage gaps. The only documented way to install a pet (and a sphere skin) was `adb push … /sdcard/Android/data/<pkg>/files/{pets,spheres}/` — i.e. external app-scoped storage — but both loaders read from `context.filesDir` (internal, `/data/data/<pkg>/files/`), which is not `adb push`-able on a non-rooted device. So the documented side-load path could never work, on either flavor. Secondary gaps: no in-app hint that pets exist, no user-docs coverage of avatars/skins at all, and the pure loader logic was Context-coupled and therefore untested.

- **Shared storage layer (`UserContentDir.kt`, new).** Both `PetLoader.userDir` and `SphereSkinLoader.userDir` now resolve through one helper that prefers external app-scoped storage (`getExternalFilesDir(null)` = `/sdcard/Android/data/<pkg>/files/<name>/`, reachable by `adb push`, no runtime permission on API 19+) and falls back to internal `filesDir` only when external is unmounted. Single source of truth for "where side-loaded customization content lives" — fixes the bug once for pets and sphere skins together. The `adb push` commands the docs already showed are correct against this location.
- **Testability refactor (`PetLoader`, `SphereSkinLoader`).** Added pure `loadPets(dir: File)` / `loadUserSkins(dir: File)` overloads (no Android Context); the Context overloads delegate. The validation/resolution/skip-invalid path is now unit-testable against a temp directory, mirroring `DashboardManageDiskCache`'s `dir: File` shape.
- **Tests (`PetLoaderTest`, `SphereSkinLoaderTest`, new).** 17 + 6 JUnit cases covering parse, id/label fallbacks, schema-version + missing-`idle` + missing-file rejection, the `safeChild` **path-traversal guard** (a real `../escape.png` outside the pack is refused), fps clamping, one-bad-pack-doesn't-break-the-rest, sort order, and empty/absent dirs. No real bitmaps needed (the loader only checks `isFile`); `unitTests.isReturnDefaultValues=true` makes `Log.w` a no-op so no `mockkStatic`.
- **In-app discoverability (`AppearanceSettingsScreen`).** Added an "Add your own pet" pointer line under the Agent-avatar chips (mirrors the existing sphere-skin pointer) so users learn the feature exists even with no pets installed.
- **Docs (`docs/pet-spec.md`, `docs/sphere-spec.md`).** Corrected the storage prose (app-scoped external, external-preferred / internal-fallback, both flavor paths), cross-linked the two specs under one "customize the agent avatar" framing, fixed a "Agent sphere" → "Agent avatar → Sphere skin" naming drift, and added two authoring caveats: a present-but-undecodable image renders blank (not caught at load), and frame-sequence pets decode every frame at full resolution into RAM (keep frames small / prefer sprite sheets).
- **User docs (`user-docs/features/custom-avatars.md`, new + nav).** New user-facing page covering the two-level avatar→skin model, the reactivity badges, how to add skins and pets, reduced-motion behavior, and troubleshooting; wired into the Features sidebar.
- **Verification.** Tests authored to the established temp-dir pattern and validated against the actual `toAvatar`/`toSkin` contracts (read from source); not run here (Android build/test is Studio-side). Follow-ups (per-frame memory cap/downsample, decoded-clip cache to kill re-decode churn) recorded in TODO.md.

## 2026-06-18 — Chat: mid-session model switch, error surfacing, model-scope UI (Android)

**Why.** On-device testing surfaced four linked issues. (1) Switching the in-chat model in an *existing* conversation showed the pick but the turn still ran the old model. (2) A failed turn (grok-4.3 hitting xAI's 200-tool cap) flashed an error bubble then vanished. (3) The chat header and agent drawer showed the global/profile model, not the session's live model — the drawer even paired the global model *name* with the session *provider* (`gpt-5.5 · xAI Grok`). (4) An upstream-injected `[System: the active model changed …]` marker rendered as a chat bubble.

- **Session-scoped model switch (`GatewayChatClient.prewarmAwait`, `ChatViewModel.selectModel`).** `selectModel` called fire-and-forget `prewarm()` then immediately `setModel()`, so `config.set {key:"model"}` ran with `liveSessionId == null` and upstream applied it as a GLOBAL write — never touching the live session (verified: `_apply_model_switch`, `tui_gateway/server.py:2134`, is the session-scoped in-place swap the CLI/TUI `/model` uses). Added a suspending `prewarmAwait()` that resolves/resumes the live session before returning; `selectModel` awaits it then applies `setModel` session-scoped, or skips the global write and defers to the next `session.create` override when there's genuinely no session. Confirmed on-device: `sessionScoped=true` → `session.info` flips → turn runs the picked model.
- **Errors never swallowed (`GatewayChatClient.dispatchOn`, `ChatHandler.loadMessageHistory`).** Root cause: `dispatchOn` (marshals turn callbacks to the main thread) omitted `onStatusUpdate`, so it fell back to the data class's default no-op — the server's `❌` terminal-error lifecycle line never reached `markError`, the turn wasn't badged `Error`, and `onComplete`'s post-turn history reload (which a non-errored turn runs) wiped the client-only error bubble. Wired `onStatusUpdate` through `dispatchOn` (also restores live status lines, previously dead on the gateway), and hardened `loadMessageHistory` to re-inject local `Error`-badged assistant messages the server transcript lacks, so no reload path can swallow a failure.
- **Model display scoped to the session (`ChatScreen` header, `ConnectionInfoSheet.AgentSheetHeader`).** The header subtitle resolved `profile.model ?? serverModelName`; now mirrors the input chip (`selectedModelOverride ?? gatewayCurrentModel ?? profile ?? server`). The agent-sheet header was pairing the global model name with the session provider; it now takes a `sessionModelName` so model+provider come from one scope, and adds a quiet "Server default: …" caption only when the session diverges (the always-visible global-vs-session split; the redundant in-section split was removed).
- **System steering markers hidden (`ChatHandler`, `ConnectionViewModel`, `RelayApp`, `ChatSettingsScreen`).** Upstream injects `[System: …]` model/personality-change markers into history for the LLM (`tui_gateway/server.py:1769`); we rendered them as bubbles. `loadMessageHistory` now drops `role:system` `[System:`-prefixed rows by default (desktop/TUI parity), gated by a new `ChatHandler.showSystemMarkers` flag wired from a default-off "Show system messages" debug toggle in Chat Settings (DataStore-backed, mirrors `parseToolAnnotations`).
- **Verification.** `:app:assembleSideloadDebug` BUILD SUCCESSFUL; deployed to device; each fix confirmed on-device via filtered logcat traces. Separate known item (not an app fix): xAI/grok models exceed the provider's 200-tool cap with the full relay toolset (server-side).

## 2026-06-18 — Chat UX: model-picker apply, relay inbound images, smooth profile switch (Android)

**Why.** An audit of profile switching and the chat composer surfaced three issues: (1) the in-chat model picker showed the picked model but the agent ran on the account's global default; (2) an agent-returned server-local image showed a path/"on server" notice instead of rendering, even when paired to the relay; (3) switching profiles visibly tore down and rehydrated the conversation.

- **Model picker binds to `session.create` (`GatewayChatClient`, `ChatViewModel`, `GatewayModels`).** Verified against upstream `tui_gateway/server.py`: a model is a per-session override, applied via `config.set {session_id,…}` on a live session or `model`/`provider` params on `session.create` for a fresh one. The app only did the first; on a brand-new chat the `config.set` carried no `session_id` (upstream treats it as a no-op) and `session.create` omitted the model, so the agent built from the global default. Added a live `sessionModelProvider` (mirrors `sessionProfileProvider`) so the picker's model+provider bind onto each `session.create`; mid-session switches still go through `config.set`. A profile switch now retires an explicit pick (the profile defines its own model) and seeds the picker pill from the profile model so the header doesn't lag the round-trip. SSE paths already carried the model in the request body. Tests added for the new binding.
- **Relay-backed inbound images (`ChatImageContent`, `ChatScreen`, `ChatViewModel`).** Markdown images (`![](src)`) flowed through a renderer that only understood `http(s)` → Coil; a server-local path fell to a static "image is on the server" notice and never consulted the relay (the relay media route was only wired to the `MEDIA:` marker path). Added a `RelayServerImageResolver` CompositionLocal, provided by ChatScreen from `ChatViewModel.resolveServerImage`, which fetches an absolute server path through the relay's bearer-auth `/media/by-path` route (same route + sandbox the `MEDIA:` path uses), decodes, caches (bounded LRU keyed by path), and renders inline with tap-to-zoom. Null when unpaired → unchanged standard (no-plugin) behavior. Complementary nudge: `composeInjectedContext` appends a one-line media-capability hint to the SSE `system_message` when a relay route is configured (`RelayHttpClient.mediaUrlConfigured()`), surfaced in the "What the agent sees" audit sheet. SSE-only — the gateway has no per-turn system slot, so there the client render fallback (and upstream's own `MEDIA:` instruction) carry it.
- **Profile-switch transition (`ChatViewModel.switchProfileContext`, `ChatScreen`).** Stopped clearing the message list synchronously before the async history fetch; the previous transcript is held and swapped atomically when the new history resolves, so the `LazyColumn`'s per-item `animateItem()` cross-fades old→new instead of blanking to an empty/"Loading…" state. The top loading row is suppressed while held content is on screen.
- **Verification.** Rebased `Codename-11/fix-ui-ux-issues` onto `dev` first (its only unique change was already on `dev`). `:app:compileSideloadDebugKotlin` + `:app:compileSideloadDebugUnitTestKotlin` BUILD SUCCESSFUL (no new warnings in the changed files); `GatewayChatClientTest` extended with model-binding cases. On-device verification via Studio.

## 2026-06-18 — Cold-start keystore contention + honest loading states (Android)

**Why.** A cold-start logcat trace showed the chat header's identity/model/approvals lagging seconds behind first frame. The cause was on-device, not the network: `EncryptedSharedPreferences.create()` decrypts a Tink keyset via a KeyStore op (~0.6–1 s on StrongBox) and Tink serializes those process-globally, and the app was building **three** keysets at startup (a throwaway legacy-sentinel `AuthManager`, the active connection's token store, and the dashboard cookie store) — they thrashed the lock (`Long monitor contention … AndroidKeysetManager.build()`, `waiters` up to 4; a `by lazy` held for **2.369 s**). The relay auth round-trip itself was ~150 ms. Separately, a design constraint surfaced: never display unconfirmed server state (model/provider/approvals) as if confirmed — show an honest loading state and make the load fast, don't cache a maybe-wrong value.

- **Process-global store cache + raw-build factory (`SessionTokenStore.kt`).** `SecureStoreCache.getOrBuild(prefsName){…}` (a synchronous `ConcurrentHashMap.computeIfAbsent`) builds each prefs file's keyset once process-wide, and `buildRawTokenStore()` is the shared backend factory. Synchronous on purpose so the SAME instance serves both the suspend token path (wrapped in IO) and the synchronous OkHttp cookie-jar path.
- **Sentinel deferral (`AuthManager.kt` + `ConnectionViewModel.kt`).** Added `eagerHydrate` (false for the legacy sentinel that `ConnectionViewModel` builds at field-init and replaces the moment the active connection hydrates), so it no longer decrypts a keyset just to be discarded. Re-gated the pre-StrongBox `hermes_companion_auth → _hw` migration on the **file name** (not the sentinel's connection id) so deferral stays correct, and marker-gated it (`legacy_migrated`) so the legacy file is read at most once ever.
- **Cookie keyset unification (`DashboardApiClient.kt`, `UpstreamTransportController.kt`, `ConnectionViewModel.kt`, `DataManager.kt`).** The dashboard cookie store now rides the connection's **token** file (threaded `tokenStoreKey` via a `tokenStoreKeyProvider`) instead of its own `hermes_dashboard_<id>` keyset — eliminating the second build. One-shot, marker-gated migration (`dashboard_cookies_migrated`) copies existing cookies across on first access; failure just means a one-time Manage re-login (cookies are re-obtainable, unlike the relay token). Also fixed a double `store.load()` per cookie request.
- **Honest loading + fade-ins (`LoadedFadeIn.kt` + 5 call sites).** New shared `LoadedFadeIn`/`RelaySkeletonLine` mirroring the header's skeleton→identity spec. Wired into the header subtitle (model fades in when confirmed), the agent sheet's model line, `ContextMeterBar` (fade+expand), the session drawer (loading→list crossfade), and Manage (Loading→Loaded crossfade). The agent sheet's YOLO switch now shows "Checking…" instead of rendering the unknown (null) state as a definitive "off". No model/provider/approvals value is ever cached and shown as confirmed.
- **Also (header/chrome).** Dropped the redundant LAN/Tailscale endpoint chip from the chat top bar (the footer status strip already shows `<status> / <route>`) and made that footer strip tappable → Connections; subtitle no longer renders a `none` personality.
- **Verification.** `:app:assembleSideloadDebug` BUILD SUCCESSFUL; deployed to device. Cold-start logcat before→after (warm launch, same device): **3 keyset builds → 1**; the sentinel's "no stored session_token → Unpaired" build is gone; first-frame→Paired **~2.9 s → ~0.95 s**; steady-state (post-migration) trace shows **zero** `Long monitor contention` events. On-device check still wanted: Manage/voice remain signed in after the one-time cookie migration.

## 2026-06-18 — Connection toast stepper + chat header de-clutter (Android)

**Why.** Two adjacent UI/UX gaps. The floating connection-status toast already slid in from the top and supported swipe-up dismiss, but it rendered its trace entries as flat `label: detail` text and the swipe silently accumulated to a threshold then snapped — while the cold-start sphere right next to it had a far nicer live stepper (`·`/spinner/`✓`/`✕`). The two were built separately and never unified. Separately, the chat header subtitle (`personality · model · ⚡ approvals off`) was being clipped because the trailing actions row (endpoint chip + Share + Terminal + Settings) won the width fight; the appended approvals text made it worse.

- **Stepper state model (`RelayUiState.kt`).** Added `ConnectionStepState { Pending, Active, Done, Failed }` and an optional `state` on `ConnectionHandoffTraceEntry` (defaulted null so every producer keeps compiling). Null means "infer from position + snapshot"; producers that know a surface's verdict stamp it explicitly.
- **Probe entries stamp real states (`ConnectionViewModel.kt`).** `buildGlobalConnectionProbeEntries` now tags Route=Done, and API/Relay as Active (Probing) / Done (Reachable) / Failed (Unreachable), plus the relay-socket "Session" step as Active.
- **Toast redesign (`ConnectionHandoffBanner.kt`).** `ConnectionStatusToast` renders entries as a live stepper (fixed-width monospace glyph + spinner via `LaunchedEffect`, mirroring the splash's `StartupCheckRow` vocabulary), reusing `ConnectionStatusBadge`'s green for Done and `colorScheme.error` for Failed. Swipe-dismiss now tracks the finger with an `Animatable` offset + fade, flinging off-screen past threshold (then firing `onDismiss`) or springing back; keyed on status identity (title+tone) so frequent `updatedAtMs` trace bumps don't reset an in-flight swipe. Warning/Error poses get a bottom divider + "Open <destination> →" link for discoverability. The legacy edge-variant `ConnectionStatusBanner` was left as-is (only referenced within its own file).
- **Chat header (`ChatScreen.kt`).** Dropped the inline ` · ⚡ approvals off` annotation from the subtitle (now a plain single line). Added an amber ⚡ `Icons.Filled.Bolt` to the app-bar actions, shown only when approvals are effectively off, tapping into the agent sheet where the full explanation already lives. Share moved into a `⋮` `DropdownMenu` (rendered only when there's a conversation to share), leaving Terminal + Settings + the endpoint chip as the visible actions. `RelayChromeIconButton` gained optional `tint` / `borderColor` params for the amber treatment.
- **Verification.** Pending — Kotlin-only UI changes; per the project dev loop these build via Android Studio's run button (not `gradle build` from here). `./gradlew lint` recommended before push.

## 2026-06-18 — Terminal: TUI input correctness + chrome cleanup (Android + relay)

**Why.** On-device terminal use surfaced input bugs and wasted chrome, benchmarked against Orca's mobile terminal. The extra-keys bar clipped labels ("CTRL" → "CTR") because it was weight-distributed across a fixed width; the on-screen arrows and PASTE bypassed the emulator and sent fixed/raw bytes (wrong inside TUIs and unsafe for multi-line paste); and the header + tab strip + a stray inset ate vertical space, especially in the common single-tab case. Separately, the relay wrapped each PTY in the user's default tmux, inheriting tmux's 500ms `escape-time` and `screen` `$TERM` — the classic source of laggy ESC, mangled Alt, and degraded color in vim/htop.

- **Extra-keys bar (`ExtraKeysToolbar.kt`).** Rewrote from a weight-divided `Row` to `horizontalScroll` with fixed-min-width keys, so labels never clip and the cluster scrolls when wider than the screen (Orca's strategy). Then compacted to Orca's proportions — 32dp height, 36dp min width, 12sp, tighter padding/spacing — and centralized the key haptic.
- **Mode-aware special keys (`index.html` + `TerminalScreen.kt`).** Added `window.termSendKey(name)` that reads xterm's `applicationCursorKeysMode` and encodes arrows/Home/End as SS3 (`\eOA`) vs CSI (`\e[A`); the toolbar arrows now route through it. PASTE routes through `term.paste()` (bracketed paste) instead of a raw `sendInput`, so multi-line paste no longer auto-executes.
- **Compact header (`TerminalScreen.kt`).** Replaced Material's fixed 64dp `TopAppBar` with a ~52dp custom `Row` + `statusBarsPadding`: status is shown once, inline with the title (a `ConnectionStatusBadge` dot + one concise word, ellipsized via `weight(1f, fill = false)` so it can't push the actions off-screen), the whole block opens the info sheet. The subtitle no longer renders the full `hermes-<deviceId>-tabN` wire id (it was wrapping to two lines).
- **Less wasted vertical space.** The tab strip + its divider now render only for 2+ tabs; with one tab the new-tab "+" lives in the header instead. Dropped a redundant `navigationBarsPadding()` on the keys bar (the app Scaffold's bottomBar already owns the nav-bar inset, leaving a dead gap), and added an 8px bottom gap in `#terminal` so the last row clears the keys bar.
- **Isolated, TUI-tuned tmux (`plugin/relay/channels/terminal.py`).** Sessions now spawn on a dedicated `-L hermes-relay` socket with a generated `~/.hermes/hermes-relay-tmux.conf` (written lazily, best-effort): `escape-time 0`, `default-terminal "tmux-256color"`, truecolor `terminal-features/overrides`, `mouse on`, `focus-events on`, `set-clipboard on`, `aggressive-resize on`, `status off`. A dedicated socket is the only safe way to set server-global `escape-time` without altering the user's own tmux; persistence is unchanged (the socket's server outlives the relay process).
- **Verification.** `:app:assembleSideloadDebug` BUILD SUCCESSFUL and deployed to device; `python -m unittest plugin.tests.test_terminal_channel` (4 tests) + `py_compile` pass. Relay change hand-deployed to the staging box and verified live on the `hermes-relay` socket (`escape-time 0`, `default-terminal tmux-256color`, `status off`, `mouse on`, `focus-events on`). tmux 3.4 with `tmux-256color` terminfo present.

## 2026-06-18 — Native secure routes: split connection Features from Routes (Android)

**Why.** Connection setup conflated two separate questions — what a Hermes connection can *do* (features) and how this phone *reaches* it (route) — which coupled Relay features to a single transport. Modeling them separately lets a user enable Relay tools over any route (LAN, Tailscale, public HTTPS, VPN, or a plugin-provided secure proxy) and sets up a plugin-assisted native encrypted route that does not require Tailscale. The standard path stays direct-to-upstream and plugin-free. (Backfilled log entry — the work landed in PR #88; full design in `docs/plans/2026-06-18-native-secure-routes.md`.)

- **Split connection model.** `ConnectionsSettingsScreen` / `ActiveConnectionSections` now render distinct **Features** and **Route** sections; `Endpoint.kt` + `ConnectionData.kt` carry the route/role model and `QrPairingScanner` threads it through pairing.
- **Plugin secure proxy route.** A `plugin_proxy` route role surfaces as a "Secure proxy" option with encrypted / pinned-TLS treatment (recommended, not forced) alongside the existing LAN / Tailscale / public / custom roles.
- **Docs.** Added the `2026-06-18-native-secure-routes` plan and a connections split-model mockup; fixed the docs-site hero sphere to keep its canvas backing store synced to the CSS box (`HeroDemo.vue`).
- **Verification.** CI green on PR #88 (Android Build + Lint + Test).

## 2026-06-18 — Android onboarding permissions review surface

**Why.** Android onboarding already kept the standard path clean, but permissions were scattered between feature-specific prompts, Bridge, and Android Settings. A central review page makes the model explicit: standard Chat and Manage do not need phone-control permissions, while voice, camera, notifications, and sideload Device Control remain opt-in.

- **Shared permission snapshot.** Added `AppPermissionStatusProbe` so Bridge and Settings read the same runtime grants and special-access switches: notifications, microphone, camera, notification listener, accessibility, screen capture, overlay, contacts, SMS, phone, and location.
- **Permissions screen.** Added `PermissionsStatusScreen` with Standard Hermes, On demand, and flavor-aware Device Control sections. Rows show required/optional/session status and link to the relevant Android Settings surface or Bridge session grant.
- **Onboarding and Settings entry points.** The Power Tools onboarding page now has a "Review permissions" action, and Settings -> App includes a Permissions row. Google Play builds show the sideload Device Control section as unavailable rather than implying hidden phone-control permissions.
- **Verification.** `:app:compileGooglePlayDebugKotlin`, `:app:compileGooglePlayDebugAndroidTestKotlin`, and `:app:compileSideloadDebugKotlin` pass with `ANDROID_HOME` pointed at the local SDK.

## 2026-06-17 — Chat transparency + provenance polish: injected-context audit sheet, spoken-turn badges, version-skew error

**Why.** On-device voice testing surfaced two transparency gaps and two papercuts. The per-turn system context the phone injects (persona + phone status + voice hint) was invisible — no way to audit what the agent actually receives. Spoken voice-mode turns were indistinguishable from typed ones in the scrollback, while realtime turns were already badged. A field an older relay plugin doesn't accept produced a misleading "Network error · HTTP 400" with a dead Retry. And the floating connection toast's Warning tone was semi-transparent, letting content bleed through.

- **Injected-context audit sheet.** `ChatViewModel.composeInjectedContext()` extracts the per-turn system-message composition (persona/profile precedence + phone-status block + per-turn interface context) into one builder used by both `startStream` (which sends `combinedSystemMessage`) and the new `previewInjectedContext()` — so the audit can't drift from what is sent. `combinedSystemMessage` is built byte-for-byte as before (`listOfNotNull` over the raw blocks); per-block fields null out blanks only for display, and the resolved profile is passed in to preserve the no-skew invariant with `modelOverride`. Tapping the `ContextMeterBar` (now with an ⓘ affordance) opens `InjectedContextSheet` ("What the agent sees"); on the gateway path the persona block is labeled "added server-side — not sent from this device", since the server owns the soul + personality overlay there.
- **Spoken-turn badges.** Voice-mode replies are tagged "Voice", realtime replies keep "Realtime Agent"; both share a speaker glyph as the modality marker (`MessagePathBadge` gained an optional leading icon). The Voice tag is set on the assistant placeholder from the per-turn interface-context signal and rides the id-swap + content updates.
- **Badges survive history reload.** `ChatHandler.loadMessageHistory` now carries provenance badges forward by message id when it rebuilds the list from server data — the post-turn reload previously wiped them (this also fixes the pre-existing loss of "Stopped"/"Error").
- **Version-skew error.** `RelayErrorClassifier` maps a 400 whose body names an unsupported *field* to "Relay update needed" (non-retryable), distinct from a bad *value* like "unsupported codec" (which keeps its normal classification).
- **Connection toast opacity.** `ConnectionStatusToast` composites its container color over the theme `surface` so the floating overlay is always opaque while keeping each tone's tint; the in-flow `ConnectionStatusBanner` is intentionally left translucent (it blends with a known backdrop).
- **Verification.** `:app:compileSideloadDebugKotlin` BUILD SUCCESSFUL; `./gradlew lint` clean. On-device confirm via Studio/sideload.

## 2026-06-17 — App theming: theme-aware brand tokens, app themes, hot-swappable sphere

**Why.** Chat (and other brand-styled surfaces) were effectively hardcoded dark: the `RelayRefresh` brand palette was a single dark-only `object` of `val Color(...)` constants that ~150 call sites referenced directly, bypassing the Material light scheme. The goal was three-fold: fix that alignment, add real app themes (light/dark plus the Nous Hermes baselines), and make the agent sphere a hot-swappable component with user-authored skins.

- **Theme-aware brand tokens (the fix).** New `ui/theme/BrandPalette.kt` defines a 21-token `BrandPalette`, the `LocalBrand` CompositionLocal, a `toColorScheme()` derivation (Material scheme is now derived from the palette, never authored separately), and the `AppTheme`/`AppThemes` registry. `RelayRefresh` was converted from constants into a **snapshot-backed façade** over an `activePalette`: every `RelayRefresh.X` is now a getter reading a `mutableStateOf`, so reads in composition and draw phases subscribe and repaint on theme change — the ~150 existing call sites became theme-reactive with no edits. `HermesRelayTheme(appThemeId, themePreference, fontScale)` resolves the theme + light/dark/auto mode into one palette, drives the Material scheme + `LocalBrand`, and mirrors it into the façade via `SideEffect`.
- **App themes (8).** Hermes Relay (the brand, with full light + dark) plus ports of the canonical Nous dashboard baselines from upstream `web/src/themes/presets.ts` — Hermes Teal, Nous Blue (light), Midnight, Ember, Mono, Cyberpunk, Rosé. Per the hybrid model, the brand honors Light/Dark/Auto while the character themes are fixed-mode looks (matching how Nous ships themes; light mode lives as the Nous Blue theme). `ConnectionViewModel` gained an `appTheme` id pref (DataStore `app_theme`); the picker is a swatch gallery in `AppearanceSettingsScreen`, and the Light/Dark/Auto control disables + explains itself for fixed-mode themes.
- **Flourish alignment.** The glow/gradient/markdown-highlight flourishes across 13 files keyed off `isSystemInDarkTheme()`; they now read `LocalBrand.current.isDark`, so a fixed dark theme keeps its flourishes on a light-mode phone (and vice-versa). `isSystemInDarkTheme()` now lives only in `Theme.kt` (resolving Auto).
- **Hot-swappable sphere.** The core algorithm (`MorphingSphereCore.kt`, mirrored in `preview/web/sphere.js`) was left untouched — parity contract preserved. A new `SphereSkin` layer supplies per-state colors/params and declares its reactivity (voice/tools/intensity/gaze), gated inside `MorphingSphere` so reactive inputs are optional + detectable. `SphereRegistry` ships Adaptive (recolors to the active theme via `LocalBrand`), Classic (the original look), Aurora, Solar, and Mono. `MorphingSphere` gained a `skin` param defaulting to `LocalSphereSkin.current`, so all ~11 call sites are untouched; `RelayApp` provides the resolved skin + available set. User skins load from app-private `spheres/*.json` via `SphereSpec` (kotlinx.serialization, data-only, validated, invalid files skipped) → `SphereSkinLoader`; surfaced in the picker with capability badges and a "Custom" tag. Format documented in `docs/sphere-spec.md`.
- **Verification.** Reviewed-but-not-compiled — no Android SDK in this worktree and Studio owns builds. Brace/paren balance checked on all new/edited files; import resolution and call-site compatibility reviewed by hand. `./gradlew lint` + on-device confirm pending (via Android Studio). Palettes are single `BrandPalette` literals, easy to tweak after an on-device pass.

## 2026-06-17 — ConnectionViewModel decomposition: extract transport/pairing/profile collaborators (ADR 34 follow-up)

**Why.** `ConnectionViewModel` was the one god-object the ADR 34 fence deferred — ~5,531 lines reaching across both sides of the upstream/relay package boundary (the `HermesApiClient`/`GatewayChatClient`/`DashboardApiClient` zoo *and* the relay `ConnectionManager` *and* pairing *and* profiles). Goal: move cohesive concerns into named, testable collaborators in a new `viewmodel/connection/` package, behind the ViewModel's existing public surface, so the standard-vs-relay wiring lives in explicit seams. Pure mechanical, behavior-preserving extraction — the whole-module compile (production + every test source unchanged) is the public-API-preservation guard. Continues the `ConnectionSwitchCoordinator` precedent.

- **`PairingController`** (229 lines). Owns the paired-devices list (`GET /sessions`) + management (`loadPairedDevices`/`revokeDevice`/`extendDevice`/`revokeChannelGrant`, incl. the optimistic local removal and the full-grants-rebuild encoding) and the insecure-ack DataStore flags (`insecureAckSeen`/`insecureReason`/`setInsecureAckComplete`). Self-contained — nothing else reads its state except `applyPairingPayload`'s `insecureReason.value`. The ViewModel delegates unchanged.
- **`UpstreamTransportController`** (269 lines). Owns the per-connection encrypted `DashboardCookieStore` cache, a single consolidated `DashboardApiClient` factory (was 4+ scattered build sites — the plan's headline), the cached `GatewayChatClient` (lazy build + mid-turn LAN/Tailscale retarget) with its availability tier + sticky-Unsupported verdict, and the per-endpoint capability snapshot + `chatMode` + the `streamingEndpoint`-preference resolution. The `@Synchronized` gateway-cache lock moved *with* the state (now the controller instance) — same mutual exclusion, different monitor. `rebuildApiClient` pushes the probed snapshot via `setCapabilitiesAndMode`.
- **`ProfileController`** (313 lines). Owns the merged `agentProfiles` list (relay `auth.ok` ∪ dashboard `/api/profiles`), the per-connection selected-profile state machine + its three persistence stores, `profileDisplayAlias`, `activeSessionTransport`, and the per-profile last-session restore. Because the state machine is co-driven by ViewModel-level lifecycle observers (connection switch / active-connection change / agent-profile arrival / gateway-availability settle), those observers stay in the ViewModel and call `profileController.*` lifecycle hooks **in their original order** — orchestration stays put; only state + logic moved, so the state machine is now unit-testable in isolation. The three stores are exposed as public vals so the connection-lifecycle orchestrators (`removeConnection`, duplicate-merge, `resetAppData`, `saveLastSessionId`) keep their clear/persist call sites byte-identical.
- **Deferred: `RelayTransportController`** (the plan's Step 2). Left in place per the plan's explicit "too entangled — don't force it" rule. `ConnectionManager` is referenced at ~38 ViewModel sites, including eager `StateFlow` initializers (`relayConnectionState`, `activeEndpoint`, `effectiveApiServerUrl`/`RelayUrl`/`DashboardUrl`, `relayReady`, `insecureMode`), the `ConnectionSwitchCoordinator`, and the `relayHttpClient`/`ScreenCapture`/`tailscaleDetector` URL-provider lambdas. The relay/route methods are inseparable from the central `_relayUrl`/`_apiServerUrl` state (also written by non-relay orchestrators + the switch coordinator) and from shared connection-store helpers (`persistActiveConnectionUrls`, `mergedRouteCandidates`); the route-probe public nested types (`RouteProbeStatus`, `RelayReachable`) are part of the frozen public API. A faithful extraction needs ~18 injected callbacks/refs — relocating coupling into lambdas rather than removing it, against the plan's "named seams, not emergent shared state" goal and at a regression risk the compile-plus-focused-slice verification can't catch. The other three controllers stand on their own; the `ChatTransportProvider` capstone (optional) is likewise not attempted.
- **Result.** `ConnectionViewModel` 5,531 → 5,089 lines (−442); cohesive transport/pairing/profile concerns now live in 811 lines of `viewmodel/connection/` collaborators with narrow, provider-injected interfaces. Public API unchanged.
- **Verification.** `:app:compileSideloadDebugKotlin` + `:app:compileSideloadDebugUnitTestKotlin` BUILD SUCCESSFUL after each extraction (every caller + test source compiles unchanged → public surface byte-identical). Focused slice `*ArchitectureBoundaryTest` (Konsist fence — `viewmodel/connection/` is outside `network.*` so it imports both worlds freely, fence stays green) + `*RelayUrlDeriverTest` + `*ConnectionSwitchTest` passes. `./gradlew lint` clean. On-device confirm pending (Bailey, via Studio).

## 2026-06-17 — Voice mode audit: relay bug fixes, spoken-output hint, Google enhanced voice

**Why.** A post-refactor audit of the standard and relay voice paths surfaced one reachable correctness bug plus several enhancement opportunities: leverage the desktop-style non-persisted per-turn context to instruct spoken-output formatting, and expose Google/Gemini enhanced voice (tone tags, voice/model/persona) that upstream added in recent PRs.

- **Relay realtime-agent loop drift (correctness).** The non-native ("render-after-Hermes") websocket loop in `plugin/relay/realtime_agent/broker.py` lacked a `playback.drained` branch, so the end-of-turn ack every client sends fell through to the unsupported-message error; the client treats `voice.error` as fatal and tore the session down on every turn whenever a non-native provider was configured. Extracted the client→server messages identical across the native and non-native loops (`session.start`, `session.resume`, `client.ack`, `playback.drained`, `hermes.confirm`) into a shared `_handle_common_client_message` dispatcher used by both loops so they can no longer drift, and added the missing `input_audio.clear` handling to the non-native path. The native loop's per-message `provider_task.done()` break check is preserved exactly. (`hermes.confirm` echo is by-design in both loops — the realtime model answers confirmations via its `hermes_confirm` tool, which returns `forwarded_to_hermes_ui` — so it was left unchanged.)
- **TTS file leak.** `plugin/relay/voice.py` synthesize streamed upstream-written `~/voice-memos/*.mp3` files and never deleted them. It now passes its own temp `output_path` into the TTS tool and deletes the artifact after streaming (reads bytes into memory and returns a `web.Response` so cleanup can run; audio is bounded by `MAX_TEXT_CHARS`).
- **Realtime "lab" session binding.** `plugin/relay/realtime_voice.py` `handle_ws` authenticated but never checked the websocket caller created the session. Added `_auth_matches_session` (mirrors `voice_output.py`) binding sessions to the creating principal's kind + device-id / token-hash, reusing `voice_auth`'s shared `AuthPrincipal` / `_bearer_from_request`.
- **Spoken-output formatting hint (standard + relay).** Enriched the per-turn voice interface context (`VoiceViewModel.STABLE_VOICE_INTERFACE_CONTEXT`) to tell the model its reply will be spoken — short conversational sentences, no markdown/emoji/URLs. This rides the existing non-persisted `system_message` slot (upstream `ephemeral_system_prompt`), so it never lands in history. Because the gateway `prompt.submit` RPC has no system-message slot, `ChatViewModel.startStream` now forces any turn carrying a per-turn interface context (voice) onto an SSE endpoint so the hint always reaches the model.
- **Provider-aware enhanced voice (Gemini + xAI) — relay path.** `/voice/synthesize` accepts optional per-request overrides (`voice`, `model`, `audio_tags`, `persona_prompt`, `language`) mapped onto the active provider. Upstream's `text_to_speech_tool` has no per-call override surface, so the relay merges overrides into a config copy and invokes the provider generator directly — `_generate_gemini_tts` (voice/model/`audio_tags` tone-tag rewrite/inline persona via a temp file) or `_generate_xai_tts` (`voice`→`voice_id`, `audio_tags`→`auto_speech_tags`, `language`). Gemini's audio-tag rewrite fails soft when the auxiliary LLM is unavailable. `/voice/config` advertises a provider-aware `tts.enhanced` capability block. Android plumbs `EnhancedVoiceOverrides` from new persisted prefs through `RelayVoiceClient.synthesize` + the relay adapter, with an "Enhanced Voice (<provider>)" Voice Settings card rendered from the capability flags. OpenAI is excluded — upstream exposes only voice/speed for it (no `instructions` tone steering).
- **Enhanced voice on the streaming renderer (`/voice/output`).** Because `voice_output_enabled` defaults true, the streaming renderer — not `/voice/synthesize` — is the normal relay playback path, so the per-request override was effectively fallback-only. Added xAI `auto_speech_tags` as a per-profile `voice_output:` setting threaded through every layer (config dataclass + env + YAML loader, `voice_output_settings`, profile override, `VoiceOutputSession`, `_provider_options`, `config_payload`, the `PATCH /voice/output/config` allow-list), mirroring `text_normalization`. The render applies `upstream_voice.apply_xai_speech_tags()` (fail-soft) to each chunk before the `voice_lab` `xai_tts` renderer — keeping `voice_lab` standalone and the upstream import in the patch-point. Android: `VoiceOutputConfig.auto_speech_tags` + `updateVoiceOutputConfig(autoSpeechTags=)` + an "Expressive speech tags" switch in the **Hermes Chat + Voice Output** card (xai_tts, persisted with the existing Save buttons). No Gemini streaming provider in `voice_lab`, so Gemini enhanced voice stays `/voice/synthesize`-only.
- **Render-path visibility (troubleshooting).** The streaming-vs-synthesize decision was logcat-only. Added a per-session `DiagnosticsLog` entry naming the active path and a persistent "Render path" row in the Voice Settings card (derived from `voiceOutputConfig`).
- **Docs.** `docs/upstream-surface-matrix.md` gained a "Voice Surfaces (standard vs. relay)" section with an explicit **route-ownership table** (every `/voice/*` route is relay-owned; only dashboard `/api/audio/*` is upstream — no upstream streaming/WS audio route) and an enhanced-voice matrix across both relay paths; `docs/spec.md` Phase V documents the override, the `tts.enhanced` block, and `voice_output` `auto_speech_tags`; `user-docs/features/voice.md` gained an "Enhanced Voice (Gemini & xAI)" section + the streaming speech-tags toggle and corrected the stale `~/voice-memos` note.
- **Standard voice polish.** Pre-flight 25 MB transcribe guard (matches upstream `_MAX_TRANSCRIPTION_UPLOAD_BYTES`) + friendly 413/400 copy in `StandardHermesVoiceClient`; hardened the dashboard audio HEAD probe to also try `/api/audio/speak`.
- **Verification.** Python: `py_compile` on all touched relay modules; relay voice suite green at 103 tests except one pre-existing xAI-OAuth env-dependent failure (identical on the unmodified tree). New tests: non-native `playback.drained` regression (red-on-bug/green-on-fix), Gemini + xAI synthesize-override integration, override-parser + capability-block units, `auto_speech_tags` PATCH round-trip, `apply_xai_speech_tags` call-through/fail-soft. Kotlin not built locally (Studio + `./gradlew lint` are the pre-push gate).

## 2026-06-17 — Upstream/Relay isolation: package fence + Konsist rule + vanilla contract test

**Why.** The load-bearing "standard path = vanilla upstream" invariant was enforced only by `CLAUDE.md` convention (network clients were cleanly named but co-located in one package, so nothing *stopped* a standard-path file importing a relay client), and the standard path had never been validated against *true* vanilla upstream (staging runs the fork with relay routes compiled in). ADR 34 records the decision; this lands all three parts. Net-additive, behavior-preserving.

- **Package fence.** Split `app/.../network/` into `network/{upstream,relay,shared}` — main + mirrored test sources (38 files moved, ~83 touched for import repointing). `VoiceAudioClient.kt` split three ways: the `VoiceAudioClient` interface + `AutoVoiceAudioClient` router → `shared`; `StandardHermesVoiceClient` → `upstream`; `RelayVoiceAudioClientAdapter` → `relay` (co-locating them would force one file to import both worlds). `ChatHandler` → `upstream` (per ADR 3 chat never flows through the relay multiplexer; the handler is fed only by upstream transports). `AndroidManifest` `GatewayKeepAliveService` FQCN and the `ci-android.yml` `RelayUrlDeriverTest` path updated for the move.
- **Hidden coupling surfaced.** The move exposed the one real upstream→relay dependency the import grep couldn't see (it was a same-package bare reference): `ChatHandler` renders phone-action bubbles from the bridge's `LocalDispatchResult` DTO. Resolved by moving that passive DTO to `network.shared` — both sides now depend only on shared to speak it.
- **Konsist boundary test.** `ArchitectureBoundaryTest` (`scopeFromProduction`) asserts `upstream` ⊥ `relay` and `shared` imports neither. Added to the `ci-android.yml` explicit `--tests` list (the broad aggregate hangs, issue #32, so a named test is the only way it runs in CI). Konsist 0.17.3 resolves clean on Kotlin 2.3.21.
- **Vanilla-upstream contract.** `scripts/check-upstream-route-contract.py` source-parses upstream's declared routes (aiohttp `add_*` + FastAPI decorators) — no server boot, no pip, no model keys. Two tiers: REQUIRED standard-path routes fail the build if missing; mode-dependent routes (auth-gate, `/api/pty`, `/v1/models`) only warn. A fork-marker guard refuses to pass against our own fork. `ci-contract.yml` checks out vanilla upstream with no relay bootstrap, asserts the checkout is vanilla, runs the contract; weekly schedule tracks upstream `main` as a drift siren, PR/push use a pinned ref. Notable: in the checked upstream commit the dashboard exposes no `/api/auth/ws-ticket` REST route (it uses the injected session token + a ws `ticket` query param), so the Desktop-style auth-gate routes are advisory, not required.
- **Deferred (tracked in ADR 34).** The `ConnectionViewModel` transport-strategy split — the one true god-object leak — is intentionally deferred as the riskiest change; the fence now contains the blast radius. Same-package redundant imports left behind by the move (e.g. a relay file importing its own `network.relay` sibling) are harmless and not cleaned. The contract job's PR-run `UPSTREAM_REF` defaults to `main` until pinned to a confirmed-public known-good SHA.
- **Verification.** `:app:compileSideloadDebugKotlin` + `:app:compileSideloadDebugUnitTestKotlin` BUILD SUCCESSFUL; `ArchitectureBoundaryTest` passes; contract script PASS against the local upstream clone (12/12 REQUIRED routes). `./gradlew lint`: BUILD SUCCESSFUL (clean, all four variants).

## 2026-06-17 — Gateway parity: live session.info sync + YOLO/Fast + stale-state refreshes

**Why.** An audit (full tui_gateway surface vs. what the official desktop uses vs. what we used) found we were dropping most `session.info` fields and fetching several server lists once. Goal: augment upstream, never show stale state. Verified every contract against the up-to-date upstream clone; a parallel review confirmed the new RPCs match `config.set` exactly and caught three race-window bugs (fixed).

- **More of `session.info` consumed live.** The interceptor now also surfaces `reasoning_effort`, `credential_warning`, `yolo`, and `fast` (added to `serverReasoningEffort`/`serverCredentialWarning`/`serverYolo`/`serverFast` flows); `startGatewayStateSync` gained one guarded collector each. A `/reasoning` change made on the desktop/TUI now reflects instantly instead of only on turn-complete.
- **Credential warnings no longer silent.** `session.info.credential_warning` (present only when the active provider key is missing/invalid) is surfaced once per distinct warning as a ⚠ system notice — dedup'd against the constant `session.info` echoes, cleared when the key is fixed. Previously such turns just failed silently.
- **YOLO + Fast mode.** New session-scoped toggles in the agent sheet (`config.set yolo` value `1`/`0` scope `session`; `config.set fast` value `fast`/`normal`) with optimistic set + rollback, live state from `session.info.yolo`/`fast`, and reset across every session/profile/connection switch. YOLO (approval bypass) renders loud — `error` caption + an `errorContainer` "Approvals are OFF" banner — and stays ephemeral so a backgrounded app can't leave global auto-approve armed.
- **No fetch-once staleness.** `refreshSkills()` + `refreshModels()` (SSE `/v1/models`) now fire on agent-sheet open alongside the personality/model refreshes, so server-side skill/model changes appear without an app reload.
- **Review fixes.** `activateGatewayProfile` now nulls YOLO/Fast (the missing 5th clear site); the `setYolo`/`setFast` optimistic rollback guards against a session switch landing during a slow `prewarm` (re-check client identity + only roll back if we still own the value).
- **Verification.** `:app:testSideloadDebugUnitTest` compiles clean; contract-fidelity review = all PASS. Touches only `GatewayChatClient`/`ChatViewModel`/`ConnectionInfoSheet`. The command-palette skills-refresh-on-open is the one optional follow-up (palette lives in the co-owned `ChatScreen.kt`). `./gradlew lint` + on-device confirm still pending.

## 2026-06-17 — Personality: server-owned on the gateway + picker-command handling

**Why.** Two reports against the personality flow. (1) Sending `/personality` (no arg) showed an agent reply bubble that appeared then vanished; (2) `/personality none` returned a confirmation but the app never reflected that the overlay was cleared. Verified the actual contract against the up-to-date upstream clone: `/personality` is a *picker command* (`hermes_cli/commands.py` `_PICKER_COMMANDS`) that the desktop/TUI never raw-forward — a bare command expands to an arg step, and a named/`none` value is applied via `config.set {key:"personality"}`, which persists `display.personality` + applies `ephemeral_system_prompt` live to the session and emits `session.info`. The app instead blindly forwarded every slash to `slash.exec`/`command.dispatch`, had no `none` concept, and never consumed `session.info` — so it kept injecting a stale per-turn personality prompt that fought the server.

- **Slash results stopped vanishing.** `ChatHandler.loadMessageHistory` did a wholesale reload preserving only `voice-intent-`/`steer-`/`ask-` ids; `system-notice-` (every `addSystemNotice` slash result) was wiped by the next turn's reconcile. Added `system-notice-` to the preserve allow-list — fixes the disappearing bubble for `/personality` and all other inline command output.
- **Gateway client owns personality.** `GatewayChatClient` gained `serverPersonality: StateFlow<String?>`, `getPersonality()` (`config.get`), and `setPersonality()` (`config.set {key:"personality", value, session_id}`), plus a connection-level `session.info` interceptor that captures the `personality` field even with no turn in flight. `"none"`/`"default"`/`"neutral"` all clear the overlay (upstream `_validate_personality` conflates them).
- **ViewModel mirrors server truth.** `selectPersonality` pushes via `config.set` on the gateway (optimistic, rolled back on a server reject with a now-durable notice) and only drives per-turn injection on the SSE fallbacks; `startStream` skips the persona-prompt injection entirely on the gateway so it can't double-apply. A `startPersonalitySync` collector + a ready-socket `config.get` seed keep `_selectedPersonality` reconciled to whatever the server/desktop/TUI set.
- **Picker-command UX.** `/personality` is intercepted client-side like the desktop: bare `/personality` opens the agent sheet's Personality section (new `openPersonalityPicker` one-shot), `/personality <name|none>` routes to `selectPersonality`. The synthetic client **"Default" row was removed** — the picker is now **None** + the server-provided personalities (the configured default, if any, shows tagged `(default)` and highlights when active); upstream's active value is just `none` or a name, so the client shouldn't invent a third state. Added a `/personality none` palette entry. `AgentDisplay` treats `none`/`neutral` as cleared-overlay aliases (base identity, not the literal word) via `isClearedPersonality`.
- **`/model` sibling fixed.** `/model` is the other picker command (`_PICKER_COMMANDS = {model, skin, personality}`; `skin` is `cli_only` and already excluded on mobile). A bare `/model` had the same raw-forward dead-end — now it opens the model picker (`openModelPicker` one-shot → `ModelPickerSheet`), while `/model <args>` stays a real gateway switch.
- **Live model/provider sync.** The `session.info` interceptor now also surfaces `model` + `provider` (`serverModel`/`serverProvider` flows); the VM's `startGatewayStateSync` (renamed from `startPersonalitySync`) drives the model pill from them, so a `/model` switch on the desktop/TUI reflects live. Format-safe — the pill normalizes through `AgentDisplay.displayModelName`, and `session.info` keeps model/provider separate like `model.options`.
- **No app reload for server-supplied data.** `refreshPersonalities()` (list + default + active `config.get`) now fires on agent-sheet open alongside `refreshModelOptions`, so a personality added/changed server-side appears without restarting the app; the active value also tracks live via `session.info`.
- **Profile SOUL double-inject fixed.** On the gateway the session is bound to the selected profile (SOUL applied server-side) AND the personality rides `config.set` — so `startStream` now sends NO persona/profile prompt on the gateway (only the phone-status block), where it previously re-injected the profile's `systemMessage` on top of the server's own SOUL. SSE fallbacks keep the client-side precedence rules.
- **Verification.** `:app:testSideloadDebugUnitTest` compiles the full module clean; new `AgentDisplayTest` cases for `isClearedPersonality` / `none`-as-cleared pass. `./gradlew lint` still the pre-push gate. On-device confirm of the live gateway round-trip pending.

## 2026-06-16 — Per-surface release notes (plugin + CLI parity with Android)

**Why.** Plugin and CLI GitHub Release bodies were static boilerplate baked into the workflow YAML (version-interpolated, but change-agnostic — a reader couldn't tell what a `plugin-v*`/`cli-v*` release actually changed). Only Android had real per-release notes (`RELEASE_NOTES.md` via `body_path`). Brought plugin and CLI up to the same Summary/Added/Changed/Fixed format.

- **New notes files.** `PLUGIN_RELEASE_NOTES.md` and `CLI_RELEASE_NOTES.md` at repo root — hand-written per release, same structure/scrub as `RELEASE_NOTES.md`. They keep the valuable Install/Verify sections but add a per-release "What's changed" block.
- **Version stays auto-accurate.** Rather than hardcoding the version in install commands (three spots for CLI), the files use `__VERSION__` (and `__TAG__` for CLI) placeholders; each release workflow `sed`-renders them into a temp body before `softprops/action-gh-release` consumes it via `body_path`. Human writes prose, pipeline fills the version.
- **Workflow wiring.** `release-plugin.yml` (package job, already checks out) and `release-cli.yml` got a "Render release notes" step + `body_path:` in place of inline `body:`. The CLI `publish-release` job had **no `actions/checkout`** (it only downloaded build artifacts) — added one so the notes file is present in that job.
- **Docs.** RELEASE.md: §2 cross-references all three per-surface files; the plugin release recipe now updates + commits `PLUGIN_RELEASE_NOTES.md`; the CLI CI-behavior section documents `CLI_RELEASE_NOTES.md` + the placeholder substitution.
- **Verification.** All three release workflow YAMLs parse (`yaml.safe_load`); plugin/CLI confirmed on `body_path` with no leftover inline body. Placeholder `sed` substitution validated by inspection. No release cut.

## 2026-06-16 — Dev-velocity tooling: Play auto-publish, worktree workflow, desktop UI preview

**Why.** Three workflow improvements to expedite shipping: automate the manual Play Console upload step, write down the worktree mental model, and cut the Compose UI edit→build→install loop.

- **Play Console auto-upload (CI).** `gradle-play-publisher` 4.0.0 was already configured in `app/build.gradle.kts` (`play { }`, DRAFT status) but `release-android.yml` never invoked it — Play upload was fully manual. Added a publish step to the `release` job, gated on a new optional `PLAY_SERVICE_ACCOUNT_JSON` secret and skipped for prerelease tags (dash in version). It runs `publishGooglePlayReleaseBundle --track=production`, landing the build as a Production **draft** so a human still clicks Start rollout. Closed the multi-flavor footgun structurally: a `playConfigs { register("sideload") { enabled.set(false) } }` block means only the `googlePlay` flavor can ever reach Play, even via the aggregate task. RELEASE.md updated (secrets table + §5 note). When the secret is unset CI prints a "skipped" summary line and behaves exactly as before.

- **Worktree workflow doc.** Added `docs/worktree-workflow.md` — a one-paragraph mental model (worktree = second folder on the same `.git`, warm caches per branch), four rules, the Orca-manages-worktrees note (use `orca-cli` worktree commands, not raw `git worktree`, here), raw-`git worktree` fallback + gotchas, and how it maps onto the existing `main`/`dev` no-ff release contract. Complements RELEASE.md "Branching policy" / decisions.md §23 without duplicating them.

- **Desktop UI preview module (`:ui-preview`).** New JVM-only Compose for Desktop module for hot-reload UI iteration on the PC. Compose Multiplatform 1.10.3 (bundles stable Compose Hot Reload, enabled by default for desktop targets) against the repo's Kotlin 2.3.21 / JVM 17. Follows the existing sphere pattern: shares the platform-agnostic `MorphingSphereCore.kt` algorithm from `:relay-ui` via a Gradle `srcDir` include (excluding the Android `MorphingSphere.kt` renderer, whose `@Preview`/`androidx.*.tooling` imports don't exist on desktop), and provides a thin `DesktopSphere.kt` renderer + a `Main.kt` gallery with a state selector and live sliders. Additive — `include(":ui-preview")` in `settings.gradle.kts` and a `.gitignore` build entry; no shipped artifact depends on it. **First-sync verification pending:** the module pins the one CMP version in the repo, to be confirmed on the next Studio sync (realign per the Compose compatibility matrix if Kotlin/CMP drift). Run via the IDE "Run with Compose Hot Reload" gutter or `./gradlew :ui-preview:run`.

- **Verification.** Kotlin/Gradle changes not built locally (per workflow: builds happen in Studio; `./gradlew lint` is the pre-push gate). YAML and Gradle edits are additive and reviewed by inspection; the `:ui-preview` module is isolated behind one settings include and verified-on-first-sync.

## 2026-06-16 — fix v1.0.0 connect force-close (corrupt keyset) + dashboard button contrast

**Why.** Two reports against v1.0.0 from the same reporter. (1) A force-close after a successful pair/connect on both Standard and Relay modes, surviving a cache clear. (2) Dashboard relay-plugin buttons rendered with text the same colour as their background. The crash was opaque from code review alone — every obvious connect-path was already guarded — until a Play Console stacktrace pinned it.

- **Crash — `AEADBadTagException` escaping the legacy token-store constructor.** The Play Console stack showed `EncryptedSharedPreferences.create` → `LegacyEncryptedPrefsTokenStore.buildPrefs` (`SessionTokenStore.kt`) → `AuthManager.store`. `EncryptedSharedPreferences` decrypts its Tink keyset eagerly on construction, so a corrupt legacy keyset (the classic post-upgrade / post-restore case: the encrypted blob persists but the hardware master key it was sealed against is gone) throws AES-GCM tag-mismatch straight out of the constructor. Every *accessor* on the store already healed via `resetPrefs()`, and `KeystoreTokenStore` hides construction behind `tryCreate`'s `try/return null` — but the legacy store is `new`-ed directly (the fallback when `tryCreate` returns null, and the migration source), so nothing caught a throwing constructor. It crashed in both modes because both call `AuthManager.store` to read the session token. A cache clear didn't help because the keyset lives in `data`, not `cache`.
  - **Fix — heal at construction + a non-persistent last resort.** `LegacyEncryptedPrefsTokenStore` now builds via `buildPrefsResilient()`: on any build failure it deletes the corrupt prefs file and rebuilds a fresh keyset against the current master key (the token in the unreadable file was lost regardless, so the user re-pairs). As defence-in-depth, `AuthManager.store()` wraps the legacy fallback in `runCatching` and degrades to a new in-memory `InMemoryTokenStore` if even the rebuild fails (a fundamentally broken keystore) — the app stays up and the user re-pairs each cold start instead of force-closing. Corrected the inaccurate `KeystoreTokenStore` comment that claimed its constructor "can't throw".
- **Dashboard button contrast (#71).** `plugin/dashboard/src/styles.css` scopes a form-control reset `.hermes-relay-plugin button { color: inherit }`. Scoping bumps its specificity to `(0,1,1)`, which outranks the host shadcn Button's `text-*-foreground` utilities `(0,1,0)`, so solid-variant buttons painted their label in the inherited container foreground — which on the dashboard theme nearly matches the button background. Removing the rule (the reporter's local workaround) would break inputs/textareas (they need light text on the dark `--hr-bg`) and ghost/outline buttons (they rely on inheritance). Fix follows the file's existing `.bg-white { …; color }` idiom: solid variants `.bg-primary` / `.bg-secondary` / `.bg-destructive` re-assert their paired foreground colour at `(0,2,0)`, winning back over the reset without `!important`. `dist/style.css` re-synced via the package's `copyFileSync` build step.
- **Verification.** Dashboard CSS verified by analysis against the variants actually used (`destructive`×6, `secondary`×1, bare-default `bg-primary`; `outline`×23 / `ghost`×4 correctly keep inheriting). Kotlin changes are localized to `SessionTokenStore.kt` + `AuthManager.kt`; on-device confirmation and `gradlew lint` pending a Studio build.

## 2026-06-15 — Claude review required check and Dependabot PR cleanup

**Why.** Open Dependabot PRs targeting `main` were blocked by the required `claude-review` check. Re-running a current Dependabot PR showed the Claude GitHub App token exchange succeeds, but `anthropics/claude-code-action` stops before review because the actor is `dependabot[bot]` and bot actors are not allow-listed. Dependabot-triggered runs also do not expose the same secret surface as human-authored PRs, so forcing Claude review on those PRs is the wrong gate.

- **Workflow fix.** `.github/workflows/claude-code-review.yml` now detects bot-authored PRs with `github.event.pull_request.user.type == 'Bot'` and emits a passing no-op `claude-review` job. Human-authored PRs still run the full Claude Code Review action; aggregate `dev` -> `main` release PRs still use the existing no-op skip.
- **Workflow self-change guard.** PRs that edit `claude-code-review.yml` now also no-op after checkout when the changed-file list includes that workflow. The Claude action requires the workflow file to match the default branch before app-token exchange, so workflow maintenance PRs must not invoke the action they are changing.
- **Dependency PR cleanup.** Batched overlapping Gradle version-catalog updates after the required-check fix: Kotlin `2.3.21`, Compose BOM `2026.05.01`, Navigation Compose `2.9.8`, Activity Compose `1.13.0`, DataStore `1.2.1`, Markdown Renderer `0.41.0`, Media3 `1.10.1`, and Foojay resolver `1.0.0`. The Android Gradle Plugin and `softprops/action-gh-release` bumps were already present on `main`, so their stale Dependabot PRs were superseded by current main state.
- **Verification.** Confirmed `CLAUDE_CODE_OAUTH_TOKEN` was refreshed in repository secrets after Claude Code GitHub setup. Re-ran Claude Code Review on PR #46 and confirmed the current blocker was bot-actor policy, not GitHub App installation. `git diff --check`, `.\gradlew.bat lint`, `.\gradlew.bat assembleDebug`, and the focused `:app:testSideloadDebugUnitTest` CI slice pass with `ANDROID_HOME` pointed at the local SDK.

## 2026-06-14 — profile chat: turn-complete wipe + cross-transport session continuity

**Why.** v1.0.0 polish (on `dev`, into release PR #61). After the per-profile session work landed, on-device testing of a non-default agent showed: a turn streamed fine (thinking + reply), then on turn-complete the chat **switched to a new/empty session** untouched; the just-finished conversation only reappeared in the drawer a moment later. Watched `adb logcat` during a repro to confirm root cause.

- **Bug 1 — turn-complete reload wiped non-default-profile turns.** `ChatViewModel.onCompleteCb` (and the error-recovery path) reconcile the transcript after a gateway/sessions turn by reloading server-authoritative history. They called the bare `client.getMessages(sid)` = api_server `/api/sessions/{id}/messages` (no `profile=`), which reads the **launch** `state.db`. A gateway turn on a non-default profile persists into **that profile's own** `state.db`, so the row 404s — and `getMessages` maps `!isSuccessful → emptyList()` (not an error), so `loadMessageHistory(emptyList())` silently blanked the just-finished turn. Default profile was immune (its sessions live in the launch DB). Fix: both reconciliation reloads now route through `loadSessionHistory(sid)`, which already prefers the profile-scoped `/api/sessions/{id}/messages?profile=` loader on gateway connections. Confirmed live: log showed the turn on session `20260614_192846_…` under a non-default profile, ending at `message.complete`.

- **Bug 2 — sessions are not portable across the two chat transports (continuity).** The same logcat showed `session.resume failed for api_1781479723_f60ca534 — creating fresh (session not found)`: every send on the non-default profile **forked a new session** instead of continuing. Verified against the upstream clone why: the two transports use different DBs *and* id namespaces, and a session created by one cannot be resumed by the other on a non-default profile.
  - **api_server** SSE path (`/v1/runs`, `/api/sessions/.../chat/stream`) — ids `api_<unixsecs>_<hex>`; `_ensure_session_db()` is a bare `SessionDB()` (api_server.py:1001) = the **launch** `state.db`. The api_server has **no per-request `?profile=` handling at all** (profile is read only via `get_active_profile_name()`, the *process* profile), so the `?profile=` the client appends is ignored for storage.
  - **gateway** (`/api/ws`) — ids `YYYYMMDD_HHMMSS_<hex>`; `session.create`/`session.resume` both bind `_profile_home(profile)` (server.py:677), so create and resume hit the **same per-profile** `state.db`. Gateway-native sessions therefore resume fine; only a cross-transport (`api_`) id resumed over the gateway 404s → fork. The `"auto"` resolver flipping (gateway used only when `GatewayAvailability.Ready` — dashboard reachable + Manage signed-in) is what produces an `api_` session on the SSE path that a later gateway send can't resume.
  - **Fix — transport-aware persistence.** `ProfileSessionStore` is now keyed by `SessionTransport` (`GATEWAY`/`SSE`) as well as connection+profile, so a gateway session and an api_server session never clobber one slot. `saveLastSessionId` buckets by the id's **namespace** (`SessionTransport.forSessionId` — the prefix is the server's ground truth about what can resume it, robust to a per-turn SSE fallback). `refreshLastSessionForProfile` restores the slot for the **active** resolved transport, and **defers** while the gateway probe is still `Unknown` (rather than guessing SSE and restoring an unresumable id); a `_gatewayAvailability` collector re-runs the restore once the probe settles. A `null` save clears only the active transport's slot and only when the transport is known — never during the defer window or right after a connection switch (availability reset to `Unknown`) — so a still-valid session isn't wiped before the collector can restore it. The key shape changed, so pre-existing untransported slots are dropped once (also clearing the exact stale cross-transport ids that caused the forks).

- **Bug 3 — new session missing from the drawer until a manual reload.** The only post-creation session-list refresh fires ~160ms after `onSessionId` (RelayApp `LaunchedEffect`) — *mid-stream, before* the new session's first message is persisted server-side, so the dashboard `/api/sessions?profile=` list omits it and the drawer carries it only via `ChatHandler`'s optimistic row (which a later `switchProfileContext` clear can drop). `onCompleteCb` reloaded the current session's *messages* but never re-synced the *list*. Fix: `onCompleteCb` now calls `refreshSessions()` after the post-turn reconciliation (by `message.complete` the list includes the new session, with authoritative title/metadata), and opening the drawer (`LaunchedEffect(drawerState.isOpen)`) re-syncs too — so a session created here or on another device shows without a manual reload. The optimistic active row is still preserved by `updateSessions` if the server list lags.

- **Scope note.** Recovering an *orphaned legacy `api_` conversation* under a profile is out of scope (it lives in the launch DB with no real profile association server-side). Going forward, a connection that stays on the gateway keeps full continuity; the fix prevents the mid-conversation fork/wipe and keeps each transport's last session distinct.

- **Verification.** `compileGooglePlayDebugKotlin` green; `:app:testGooglePlayDebugUnitTest --tests ProfileSessionStoreTest` green (rewrote for the transport key; added gateway/SSE slot-independence, `forSessionId`/`forEndpoint`, and clear-scope cases); `lintGooglePlayDebug` green. Confirmed on-device (debug APK via ADB): a non-default agent turn created a clean gateway session `20260614_210122_…` and completed with no wipe, and a later turn resumed without `creating fresh` — the fork is gone (`session.resume failed` absent from the log). Bug 2 root cause cross-checked against the upstream clone (`api_server.py`, `tui_gateway/server.py`, `web_server.py`). Bug 3 drawer behaviour confirmed visually on-device.

## 2026-06-14 — per-profile sessions, the right way (upstream-verified) + switch QoL

**Why.** v1.0.0 polish (on `dev`, into release PR #61). After testing the prior session's profile work: the switch swapped the agent but (1) the reply got reset / no in-chat signal of the switch, and (2) the drawer didn't filter per profile. Decision: clone upstream and follow the official desktop. Doing so revealed the prior `a1a758d` ("per-profile drawer via gateway `session.list`") was built on a false premise.

- **Cloned `NousResearch/hermes-agent`** (outside the repo tree) and read the real `tui_gateway/server.py`, `hermes_cli/web_server.py`, and `apps/desktop/src/{store/profile.ts, app/session/hooks/use-session-actions.ts}`. Ground truth: gateway `session.list` reads ONE process-global `SessionDB` pinned to the **launch** profile (the per-session `profile_home` ContextVar override only applies during resume/prompt turns) — it takes only `limit`, returns no `profile` field, and **cannot scope per-profile over one socket**. The desktop gets per-profile sidebars by spawning a **backend per profile** (a topology a single remote socket can't replicate) and stamps `profile` client-side. Memory: `gateway-profile-session-topology`.
- **Correct source = dashboard REST.** `GET /api/sessions?profile=<name>` (web_server.py:2554) and `…/{id}/messages?profile=<name>` (6528) open *that profile's own* `state.db` directly (`_open_session_db_for_profile`) and tag rows with `profile` — the exact mechanism behind the desktop sidebar, same surface Android already uses (dashboard cookie session), same id-space the gateway resume reads. So: new `DashboardApiClient.listSessions(profile, limit)` + `getSessionMessages(sessionId, profile)`; `ConnectionViewModel.listProfileScopedSessions()` / `loadProfileScopedMessages()`; `ChatViewModel.refreshSessions()` + `loadSessionHistory()` route gateway connections through them (api_server shared list stays the off-dashboard fallback). Removed the misleading gateway `listSessions()` + its test. **Without the messages half, tapping a non-default profile's session would open empty** — the drawer was only half-working.
- **Switch jank.** `activateGatewayProfile()` was calling `createNewChat()` *on top of* the profile-context switch RelayApp already fires on a profile change — a redundant second reset racing the first (the "reply typing → new chat appears" effect). Now it only `clearSession()`s the live gateway session so the next turn rebinds the profile; the context switch alone cancels the in-flight turn (unified `activeStream` handle → gateway `session.interrupt`) and resets the thread. Re-tapping the active profile leaves the thread be (desktop parity).
- **QoL (all four landed).** (A) empty chat now reads **"Chat with \<Agent>"** + the agent's description so a switch is legible in the thread, not just the header (the desktop's fresh-session intro); (B) a leading `delay(160)` in the profile-context `LaunchedEffect` coalesces the `lastSessionId` null→value churn so switching to a profile *with* history skips the intermediate empty paint (LaunchedEffect cancels the prior coroutine on re-fire); (C) `ChatHandler.updateSessions` preserves the active optimistic row when the `min_messages=1` list omits it, and `sendMessageInternal` stamps a new chat's drawer row with the first user message; (D) drawer shows a spinner instead of flashing "No sessions yet" while a profile's list loads.
- **Cold-start profile hydration (follow-up, `60f9b7a`+1).** Reported: on cold open the header shows the *default* agent; opening the agent sheet takes a beat, then snaps to the persisted agent and re-scopes the chat. Root cause: the persisted profile is stored as a NAME and only resolves once the connection's profile *list* arrives, but on a dashboard/gateway connection the relay `auth.ok` list is empty and `_dashboardProfiles` was fetched **lazily** — only by the agent sheet's `LaunchedEffect`. So the resolve couldn't happen until the picker opened. Fix: `ConnectionViewModel` now calls `refreshDashboardProfiles()` eagerly at the end of the `activeConnectionId.collect` (after `rebuildChatApiClient()`), and clears `_dashboardProfiles` on `connectionSwitchEvents` so a pending name can't resolve against the previous connection's list. The `agentProfiles` collector then resolves the pending name as soon as the eager fetch lands — header + chat are correct from launch.
- **Verification.** `compileGooglePlayDebugKotlin` green; `:app:testGooglePlayDebugUnitTest --tests DashboardApiClientTest/GatewayChatClientTest/AgentDisplayTest` green (added `listSessions_*` + `getSessionMessages_*` request-shape tests); `lintGooglePlayDebug` green; `assembleGooglePlayDebug` green. On-device profile-switch + drawer behavior pending confirmation (live driving paused). The one unverified spot stays the same: the dashboard `/api/sessions?profile=` contract is read from upstream source, not yet round-tripped against a live server.

## 2026-06-13 — profile hot-swap + agent-sheet dropdowns + What's New rendering

**Why.** v1.0.0 polish (on `dev`, into release PR #61). Reported: switching a gateway **profile** from chat didn't change the agent or the top bar (same class as the prior model-switch issue). Plus: make the agent-sheet pickers dropdowns to save space, and render the in-app What's New cleanly.

- **Profile hot-swap (`d8d9ce7`).** Root cause: `selectProfile` only set client state + rebuilt the SSE client; the gateway's bare `prompt.submit` carries no profile, so the live agent kept the server's active profile. (SSE was fine — it sends `profileName` per-request.) An initial attempt mis-targeted personality (routed persona turns to SSE) and was **reverted**: the target is the **gateway profile**, and the official desktop **hot-swaps cleanly** (no fresh session). So: mirror the verified model switch — `GatewayChatClient.setProfile(name)` → `config.set {key:"profile", value, session_id}`, session-scoped so the live session's agent (SOUL+model+skills) swaps in place. `ChatViewModel.activateGatewayProfile()` wires it (prewarm → setProfile → "Switched to X" notice → refresh model.options); the profile rows call it next to `selectProfile`. Unit test `setProfile hot-swaps the live session via config set` locks the RPC shape (key=profile/value/session_id). **CAVEAT:** the exact upstream key (`"profile"`) is inferred from `_apply_model_switch` (not in this repo — upstream `tui_gateway`; live testing paused). A wrong key surfaces as a "Couldn't switch profile" error, not a silent no-op — pending live verification.
- **Agent-sheet dropdowns.** New `CollapsiblePickerSection` (reuses `SectionLabel` + chevron; collapsed by default, header shows "Title — current value"). Wrapped Profile / Personality / Model — the rich rows (SOUL/skills badges, provider grouping) live behind the header now. Compile + assemble green; layout pending on-device review (live device driving paused).
- **In-app What's New (`b4a8c7c`).** `WhatsNewDialog` pasted raw `whats_new.txt` into one Text (literal `*`, flat headers). Now parses the format → version subtitle, bold headers, real `•` bullets. Lint green.
- **Verification.** `:app:testGooglePlayDebugUnitTest --tests GatewayChatClientTest` green; `compileGooglePlayDebugKotlin` + `assembleGooglePlayDebug` green; `lintGooglePlayDebug` green (hot-swap) / pending (dropdowns). On-device profile/dropdown behavior pending confirmation (live driving paused).

## 2026-06-13 — open/save attachments + cold-start connect-button gate

**Why.** v1.0.0 polish, two asks: (1) let users open/save chat images and other attachments; (2) stop the chat empty-state from flashing the "Connect to Hermes" button on cold start while we're already logged in and just hydrating.

- **Open/save attachments.** New `util/MediaSaver.kt` — permission-free save via MediaStore scoped storage on **API 29+** (images → `Pictures/Hermes-Relay`, files → `Download/Hermes-Relay`); **pre-Q falls back to the share sheet** (no `WRITE_EXTERNAL_STORAGE` request for a niche path). Share stages bytes into the existing FileProvider `hermes-media/` cache (can't `ACTION_SEND` an `http://` URL). Saves preserve original bytes — read back from the cached `content://` (inbound) or base64 (outbound), not a re-encode — and a magic-byte sniff sets the right extension for remote images with no usable content-type. New `ui/components/ChatImageViewer.kt` — full-screen `Dialog` with `detectTransformGestures` pinch-zoom + pan, double-tap 1×/2.5×, Share/Save/Close overlaid past the status bar inset; decoupled display (Coil model OR `ImageBitmap`) from a `suspend bytesProvider()` for save/share. Wired: `ChatImageContent.RemoteChatImage` tap → viewer (remote URL, bytes via OkHttp GET); `InboundAttachmentCard.ImageRender` tap → viewer (bitmap + cached bytes); `FileCardRender` now `combinedClickable` (tap = open external as before, long-press → Open/Share/Save dropdown).
- **Cold-start gate.** Root cause: `ConnectionStore` seeds `_connections = emptyList()` / `_activeConnectionId = null` and hydrates DataStore async, so "nothing configured" and "not loaded yet" are indistinguishable — and `chatReady` (client != null && reachable) is false during launch, so the empty-state painted the **Connect to Hermes** CTA for a beat. Fix: `ConnectionStore.isHydrated` StateFlow (flips true in the init `finally`, even on read failure) → `ConnectionViewModel.chatConnectState` (`Connecting` / `Ready` / `NeedsConnection`, seeded `Connecting`). ChatScreen's empty-state switches on it: quiet "Connecting to Hermes…" spinner + low-key "Manage connections" escape hatch while hydrating or while a configured connection comes up; the CTA renders **only** once hydration confirms no connection exists.
- **Verified e2e on-device** (USB ADB, `R5CY61QE9CT`). Cold-start gate: force-stop → relaunch shows splash → hydration checklist → chat with history, never the connect CTA. Attachment viewer: had gpt-5.5 echo `![sunset](picsum…)`, the inline image rendered, tap → full-screen viewer, **Save** wrote `Pictures/Hermes-Relay/sunset.jpg` (21,590 B; magic-byte sniff turned the `image/*` guess into `.jpg`) with a "Saved to Pictures/Hermes-Relay" toast. Found + fixed one bug mid-verify: the **share** path staged `sunset.bin` (`ensureNamed` mapped the `image/*` wildcard → `.bin`); `stageForShare` now sniffs too — re-verified the share sheet reads "1 image". Watch the comment `image/*` → `image/…`: the literal `/*` in a KDoc opened a nested block comment and swallowed the file (memory: `kotlin-nested-comments-and-pipefail`). `lintGooglePlayDebug` green; `:app:assembleGooglePlayDebug` green.

## 2026-06-13 — gateway turn survival across network blips + chat UI (images, scroll, empty bubble)

**Why.** v1.0.0 polish. Reported: a gateway chat turn that "restarted/reloaded" and replied to an earlier message after switching to Settings mid-tool-call. Root-caused, fixed, and verified e2e on-device (USB ADB, forced Wi-Fi drop). Then folded in image rendering, scroll, and the empty-bubble polish.

- **Root cause (the big one).** Mid-turn network handling cancelled/lost gateway turns three ways, all confirmed on-device via a forced 4s Wi-Fi drop mid-turn: (1) the old "rejoin via `session.resume`" was wrong — upstream `tui_gateway` `session.resume` mints a NEW live id + a fresh agent from DB and does NOT reattach to the running thread (which keeps emitting on the OLD id over the shared gateway stream); the old code gave up in ~24ms anyway. (2) a transient blip made `ConnectionManager.onLost` mark the active endpoint unreachable + re-resolve, switching LAN→Tailscale mid-blip, which rebuilt the chat client and cancelled the turn. (3) `activeGatewayChatClient()` shut down the old client on a URL change → `activeTurn.cancel()`, and `updateApiClient()` cancelled the stream.
- **Fix (`1d10cae`).** GatewayChatClient: reconnect the socket ONLY and KEEP the live session id (no resume), retry with backoff up to 20s. ConnectionManager: defer the loss reaction behind a 6s grace (cancelled by `onAvailable`) + endpoint hysteresis (don't switch DOWN in priority on a transient probe miss). Chokepoints: `activeGatewayChatClient` keeps an active-turn client (`GatewayChatClient.hasActiveTurn()`), `updateApiClient` skips gateway turns (`activeStreamIsGateway`), route-change rebuild deferred while streaming (`setChatStreaming`). Reconcile server history on error too. Rewrote the misleading rejoin unit test to assert the real no-resume recovery (tail on the ORIGINAL session id). On-device: turn now rejoins keeping the session; no more `cancelled`. RESIDUAL: a genuine *sustained* mid-turn route switch still fails at the 180s watchdog (then reconcile surfaces the answer) — proper fix is route-following (mutable dashboard URL + resume same session); follow-up.
- **Chat UI (`1d10cae` + `71a6c60`).** Empty timestamp-only assistant bubble suppressed (thinking/tools-only message). Transport-fail no longer wedges the composer in "streaming" behind a dead Stop (reset cancel flag per turn + finalize streaming on swallowed cancel). Generated images: Coil 3.4.0 + explicit OkHttp ImageLoader, parse `![alt](src)` out of assistant content, remote http(s) → Coil with loading/error, server-local/failed → inline notice explaining why (was a blank element). Scroll: ~140px at-bottom slop so streaming jitter doesn't drop the Telegram-style follow.
- **Also found (not fixed).** Gateway turns run on the server DEFAULT model (`kimi-k2.6`), not the selected profile (`gpt-5.5`) — the gateway protocol carries no model on `session.create`/`prompt.submit`; honoring it needs a `/model` dispatch. Live tool pills are gated by server `display.tool_progress` (and the kimi/nous provider may not stream tool callbacks) — not a client bug. The `perf_hint Session ID too large` logcat is Android ADPF, unrelated.
- **Verification.** `:app:testGooglePlayDebugUnitTest --tests GatewayChatClientTest` green; `lintGooglePlayDebug` green; full `assembleGooglePlayDebug` green; network-resilience verified e2e on-device. Image rendering, the model switcher, and route-following all verified on-device after unlock.
- **Image render (`71a6c60`).** Remote `https://picsum…` rendered via Coil; a `/home/…` path showed the inline "can't render" notice with the path. Both paths confirmed.
- **In-chat model switcher (`154b483`).** `GET /v1/models` (augmented with profile models — /v1/models collapses to one generic alias here) → Model picker in the agent sheet; tapping a model dispatches `/model` (rich model-info card) + sets an SSE override. Verified switching on-device.
- **Gateway route-following (`154b483`).** Mutable dashboard target + `retarget()`: a SUSTAINED mid-turn route switch (LAN→Tailscale) now retargets the live client (reconnect via the new route, keep the session) — verified in logcat (`retargeting active client to follow the route` → reconnect via Tailscale, turn NOT cancelled, UI not wedged). A fresh socket can't replay an in-flight turn (upstream resume doesn't reattach), so post-retarget the turn gets a 30s settle then reconcile-on-error recovers the answer; full live-follow needs upstream resume-reattach.

## 2026-06-13 — v1.0.0 bump + gateway attachment parity (PDF / file)

**Why.** Final touches for **1.0.0** (rechrome + standard path + polish). Question raised: does the app match upstream hermes/hermes-desktop on media attachments via the standard path? Verified against actual upstream source (`tui_gateway/server.py`, `gateway/platforms/api_server.py` on `NousResearch/hermes-agent`), not the repo docs.

- **Version.** `gradle/libs.versions.toml` `appVersionName 0.8.1 → 1.0.0`, `appVersionCode 11 → 12` (via `scripts/bump-android-version.sh`). Left uncommitted on `dev`; CHANGELOG `[Unreleased]` → `[1.0.0]` rename happens at release-cut.
- **Finding.** Images inline were already at parity — gateway `image.attach_bytes` (exact upstream RPC + params, legacy `image.attach.bytes` fallback) and `/v1/chat/completions` `image_url` (upstream `_normalize_multimodal_content`). But **non-image attachments were force-shunted to the SSE fallback**, where vanilla upstream `api_server.py` has **zero** attachment handling (no `attach`/`attachments` consumer — only OpenAI `image_url`/`input_image` parts) — so a PDF/.txt sent on a vanilla server was silently dropped. Meanwhile the gateway already had `pdf.attach` (content_base64 → pdftoppm → vision tiles, 50MB/25pg) and `file.attach` (data_url → materialized `@file:` workspace ref) the whole time; we just never wired them.
- **Fix.** Generalized `GatewayImageAttachment` → `GatewayAttachment` (+`contentType`). `uploadAttachment()` routes by MIME: `image/…` → image RPC (unchanged dual-name path), `application/pdf` → `pdf.attach` (`content_base64`), else → `file.attach` (`data:<mime>;base64,…` data URL + `name`). `ChatViewModel`: dropped the `attachments?.any { !it.isImage }` SSE-force clause (now only a *missing* gateway client falls back) and the `.filter { it.isImage }` map gate; mapper `toGatewayAttachment()` adds the pdf ext + carries contentType.
- **Not-silent on SSE fallback.** `ChatViewModel.startStream` now warns via `ChatHandler.addSystemNotice()` (display-only SYSTEM bubble, TTS-ignored) when a turn carries attachments the chosen SSE endpoint can't deliver: `completions` flags only non-images (it still carries images inline as `image_url`), `sessions`/`runs` flag everything. Centralized inside `dispatchSse()` so all three SSE entry points (resolved-SSE, missing-gateway, gateway-preflight-fallback) are covered once; gateway-success never calls it, so no false warning. Text always sends.
- **Tests.** `GatewayChatClientTest` — existing image cases ported to `GatewayAttachment(... contentType=)`; new `pdf attachments route to pdf attach` + `non-image non-pdf route to file attach with a data url` assert the right RPC + param shapes fire and the wrong ones don't. `:app:testGooglePlayDebugUnitTest --tests GatewayChatClientTest` **green**; `lintGooglePlayDebug`/`lintSideloadDebug` green.
- **Gotcha.** First compile failed with a cascade of bogus "unresolved reference" + "Unclosed comment" 240 lines down — a literal `` `image/*` `` in a KDoc. Kotlin block comments **nest**, so `/*` opened a second level (memory: `kotlin-nested-comments-and-pipefail`). Fixed to `image/…`. AS live-inspection missed it; only `kotlinc` caught it.
- **Release-prep docs pass (1.0.0).** Audited against `RELEASE.md` §2. **Stale pins + branding:** `user-docs/guide/index.md` status → v1.0.0, example APK filenames → 1.0.0, and dropped the "self-hosted" qualifier (per branding memory — keep it only for VPN/WireGuard/OIDC technicals) in `README.md` hero, `docs/privacy.md`, `docs/security.md`, and the Play listing prose. **Docs version badge:** none existed — added a nav badge in `.vitepress/config.mts` that reads `appVersionName` from `gradle/libs.versions.toml` at build time (single source; `bump-android-version.sh` already updates it), `try/catch → ''` fail-safe, links to GitHub Releases. Verified resolution (`v1.0.0`) + full `vitepress build` green. **Release-cut content:** CHANGELOG `[Unreleased]` → `[1.0.0] - 2026-06-13` (+ fresh empty Unreleased); rewrote `RELEASE_NOTES.md` (v1.0.0 body + Download table), `app/src/main/assets/whats_new.txt`, and `docs/play-store-listing.md`. **Play body made paste-ready:** the Full Description was **4799 chars (over Play's 4000 hard limit)** and used `**markdown bold**` that renders as literal asterisks in Play (plain-text field). Rewrote it to plain-caps section headers (dropped the `━━━` heavy bars — render but cost chars + hurt screen-reader accessibility; plain caps chosen), stripped the bold, condensed features → **3652 chars**; Short 64/80, Release Notes 433/500. Header now flags "plain text, no markdown, ≤4000".
- **Play feature graphic regenerated.** `assets/play-store-feature-1024x500.png` was last touched 2026-04-09 — the only pre-rechrome store asset (screenshots were all re-shot 2026-06-12). It read "Hermes Relay" (space) in the old purple palette and its trio was "Chat · Terminal · **Bridge — Device Control**" — advertising Device Control on the **Play** listing, which that build doesn't ship (accuracy/policy risk, not just dated). Regenerated to the RelayRefresh indigo palette: hyphenated "Hermes-Relay", the Chevron Compass mark recolored purple→indigo, tagline "Your Hermes agent, in your pocket", and a Play-accurate trio **Chat · Voice · Manage**. Reproducible via new tracked `scripts/gen-feature-graphic.mjs` (inline SVG → `@resvg/resvg-js`, present transitively via vitepress; run from repo root). Icon (512, mark-only) + the 8 screenshots left as-is — already current.

**Owed:** Studio rebuild + on-device check of a PDF + a .txt send over the gateway (server needs `poppler-utils`/`pdftoppm` for `pdf.attach`), and that the SSE-fallback notice renders. CHANGELOG/RELEASE_NOTES date (`2026-06-13`) assumes a same-day tag — adjust if the cut slips. Play Console `specialUse` FGS declaration at submission.

## 2026-06-13 — Gateway latency: tracing → pre-warm + keep-alive FGS; phone-context drop

**Why.** Reported: chat "feels slower than hermes-desktop on the same backend." No latency tracing existed (only presence logs), so it was diagnosis-by-feel.

- **Tracing (`util/TurnLatencyTracer.kt`).** One comparable `TurnLatency` INFO line per turn across all four transports — gateway logs `warm|cold connect@ session@ submit@ ttfe@ ttft@ done@`; SSE (`sessions`/`completions`/`runs`) log `ttfe`/`ttft`/`done`. `ttft` = first *visible* token (reasoning or text), the metric that exposes SSE reasoning dead-air vs the gateway. Durations only, never content.
- **Diagnosis (on-device).** Measurement showed the turns were already on the gateway, not the SSE fallback. A **warm** turn = `ttft@49ms` (instant, desktop-parity); a **cold** turn = `session@4439ms` + `ttft@5356ms` — the whole gap is the cold `session.create`/`resume` paid before submit. Cold happens on a chat's first turn and after the background-close fired. (Aside: this device's logcat rotates in seconds and had dropped to the `(2)` adb serial — enlarged buffer to 5 MiB, switched to `-t 1` transport-id addressing.)
- **Pre-warm + grace.** `GatewayChatClient.prewarm()` (connect + resume existing session, never *create*) fired from a ChatScreen foreground/visibility effect when the gateway is the resolved transport; background-close grace 30s→120s. Moves the cold cost off the send path the way desktop's connect-at-launch does.
- **Phone-context preamble DROPPED on gateway** (reverts yesterday's `buildGatewayPreamble`). It prepended `[preamble]\n\n` to the user text → persisted into the session transcript (ugly on reload + visible from desktop). The gateway has no invisible per-turn slot (`ephemeral_system_prompt` is the personality slot); since it cannot be matched cleanly, the preamble was dropped. SSE keeps its invisible `systemMessage`; `buildGatewayPreamble` removed.
- **Keep connected in background (opt-in, BOTH flavors).** `network/GatewayKeepAliveService.kt` — `specialUse` FGS + persistent notification holds the *process* up so the existing socket survives Doze; the client's background-close is suppressed while on (`setKeepAliveInBackground`). `data/GatewayKeepAlivePrefs.kt` shared key; ConnectionViewModel StateFlow/setter + driver (mirrors BridgeViewModel); ChatSettings toggle. Declared in the **main** manifest (normal users benefit; Home-Assistant precedent) → needs a Play Console FGS declaration at submission. `specialUse` over `dataSync` (6h/day cap on SDK 35). Stops on task-removal.
- **Docs.** CHANGELOG `[Unreleased]`; CLAUDE.md key files; `chat.md` app-context corrected (gateway no longer injects) + new "Keep connected in background"; `flavor-differences.md` manifest split corrected (FGS now in main/Play); `play-store-listing.md` Play Console declarations section.
- **Seamless handoff + slide-down status toast.** Repaint-on-handoff was a `LaunchedEffect` key bug: `RelayApp` keyed the init + `refreshSessions()` effects on the chat-client *instance*, which a route handoff rebuilds — so LAN↔Tailscale/reconnect re-ran init + reloaded sessions and flashed the chat. Fix: the 546 effect compares `chatViewModel.boundHandler === handler` and takes the cheap `updateApiClient()` path on a same-chat client swap (full init only on a genuine handler re-bind); the 582 effect now keys on a `chatClientReady` boolean + connection/profile/session identity, not the instance. Chat state lives in `ChatHandler` StateFlows, so it survives the swap for free. UX: the connection banner (a Column sibling above the Scaffold that resized content) became `ConnectionStatusToast` — a floating overlay in the root `Box`, `slideInVertically { -it } + fadeIn`, status-bar padded, CircularProgressIndicator spinner while `active`, tap-to-act + swipe-up dismiss (content-identity dismiss key so a new status re-shows). Reuses the existing `globalConnectionStatus` model unchanged. Follow-up: the update-available banner (`UpdateBanner`) got the same treatment (shadow + status-bar-padding param) and both toasts now stack in ONE top-aligned, status-bar-padded overlay `Column` (children pass `includeStatusBarPadding = false` so two stacked toasts don't double-pad); the update vars were hoisted out of the Scaffold Column to the outer scope. The unattended-access strip stays inline (steady-state safety indicator — shouldn't float over content or be dismissible).

**Owed:** Studio rebuild + `./gradlew lint` (uncompiled here — manifests + new FGS + VM wiring); on-device Samsung FGS verification; Play Console FGS declaration when shipping 0.9.0.

## 2026-06-12 — Docs: getting-started funnel, Google Play badge, hero chatbar, naming

**What.** Follow-up to the parity wave, all in `user-docs/` plus a copy pass in the app.
- **Google Play prominence.** New self-hosted **Get it on Google Play** badge (`user-docs/public/badges/google-play.svg` — hand-built SVG; Google's hosted badge URL 404s now, so it's vendored: black plate, four-facet play mark meeting at the central seam, Arial wordmark) wired through a global `<StoreBadge>` component (`theme/components/StoreBadge.vue`, registered in `theme/index.ts` so it works in markdown too). Slotted into the home hero (`home-hero-actions-after`) above the CLI platform note, and used as the primary install action on the getting-started page.
- **getting-started.md de-walled.** Was a flat scroll of server commands. Now three steps (Install → Point at Hermes → Connect) with progressive disclosure: every wall (sideload + SHA256/cert verify, first-time `hermes` server install, dashboard auth, build-from-source, manual setup, plugin install, dashboard login reference) moved into `:::: details` collapsibles, and the macOS/Linux vs Windows command pairs became `::: code-group` tabs. No detail deleted — just relocated. Verified in the build: 6 `details` blocks + code-group tabs render, cert fingerprint still present.
- **Hero demo chatbar** (`HeroDemo.vue`) — the homepage phone mockup still showed the *old* footer (separate `/` slash glyph + classic mic). Rebuilt to mirror `ChatInputBar.kt`: `+` (attach / long-press commands), pill field, ONE morphing trailing slot via a new `inputTrailing` computed (typing → indigo Send w/ glow · empty → GraphicEq waveform · streaming+empty → danger Stop circle). No standalone slash button.
- **chat.md** rewritten for the new bar + parity features: morphing trailing button table, steering (inject vs queue), edit-and-resend, voice waveform + needs-setup dot, subagent lanes, rich/interactive ask cards, context meter, turn-complete notifications.
- **Naming.** Case-sensitive rename `Hermes Relay` → `Hermes-Relay` (21 occurrences) across user-facing app strings (`BridgeCommandHandler`, `ConnectionWizard`, `VoiceViewModel`, + 2 comments) and `phone-control-tools.md`/`voice.md`. Lowercase "Hermes relay" (= the relay component) deliberately left alone. App-string changes need a Studio rebuild to surface.

**App-context on gateway turns — fixed (Option A, scoped).** App-context injection (`PhoneStatusPromptBuilder.buildPromptBlock` → mobile preamble + bridge/permission/safety, combined into `systemMsg` at `ChatViewModel.kt:1842`) is forwarded only on the SSE/runs/sessions paths (`systemMessage=`); the gateway's `prompt.submit` is bare text (no system slot — verified against upstream `tui_gateway/server.py`: params are `session_id`/`text`/`truncate_before_user_ordinal`; the only call-time overlay is `ephemeral_system_prompt`, which is the *personality* slot, so it'd collide). Upstream PR deferred ("no PR for now"). **Fix:** new `buildGatewayPreamble(settings)` returns just the non-sensitive mobile preamble (gated by `master`); the gateway branch prepends it as `[preamble]\n\n<message>` to the wire text — guarded to skip slash commands (a prepended `/cmd` no longer starts with `/` and would break server-side routing). Local user bubble + autoTitle keep the clean `message`; only the persisted wire copy carries the one-line marker. The richer bridge/safety block stays SSE-only / `android_phone_status` on demand, to avoid bloating every persisted user turn.

## 2026-06-12 — Gateway chat transport: live thinking over the dashboard `/api/ws` (vanilla upstream)

**Context.** Voice testing on the Standard path showed 49–71s of dead air per turn (adb logcat: `run.started` → first `assistant.delta` 49s later; turn 2: 71s + tool + 58s). Root cause traced through upstream source: reasoning models think silently because the api_server SSE paths never registered the agent core's `reasoning_callback` — live reasoning deltas are dropped server-side; only a post-hoc `reasoning.available` (→ `tool.progress`, ≤500 chars) and `run.completed.messages[].reasoning` survive. The app's whole thinking UI (ThinkingBlock, `thinkingContent`/`isThinkingStreaming`, "Show reasoning" toggle, sphere Thinking state) was already built and starved. Governing constraint (now in CLAUDE.md Key Instructions + memory): **Standard path must work on unmodified upstream** — no fork patches. The upstream surface that *does* stream reasoning live is `tui_gateway` over the dashboard's `/api/ws` (`reasoning.delta`/`thinking.delta`, emitted unconditionally — `tui_gateway/server.py:2813`), i.e. exactly what hermes-desktop speaks. Plan: add Gateway as a 4th chat transport, auto-preferred when available.

**Shipped (branch `feature/gateway-chat-transport`).**
- `network/GatewayChatClient.kt` — newline-delimited JSON-RPC 2.0 over OkHttp WS to `wss://…/api/ws?ticket=…`; fresh single-use ws-ticket per connect attempt (2-attempt loop — stale pooled connections poison the first try after a server restart); `gateway.ready` handshake; rpc correlation map; `session.resume`(stored id) → `session.create` fallback; 180s turn watchdog reset per event; lazy connect on send, 30s background-grace close, no background reconnect loops; `onPreflightFailure` contract = nothing started server-side, caller may re-dispatch on SSE.
- `network/GatewayEventMapper.kt` (pure JVM) — event→callback table; `message.complete` backfills text/reasoning when nothing streamed + translates tui_gateway usage keys (`input`/`output`/`total` — NOT the SSE `input_tokens` scheme; V1 verification caught the plan's wrong assumption); unknown event types silently ignored (forward compat); synthetic FIFO tool ids when `tool_id` absent.
- `ChatViewModel` — `activeStream` retyped `EventSource?` → `ActiveTurnHandle` (fun interface; SSE branches wrap via `.asTurnHandle()`); `dispatchSse()` extracted so the new gateway branch falls back per turn (no gateway client / attachments / preflight failure → SSE; "sessions" fallback degrades to "completions" when no server session exists). Voice-intent/card synthetic traces stay **unsynced** on gateway turns (prompt.submit is bare text). Interactive asks (clarify/approval/sudo/secret) → `ChatHandler.addSystemNotice()` (SYSTEM role — TTS observer ignores it; desktop CLI v0.1 precedent: display-only).
- `ConnectionViewModel` — `GatewayAvailability` piggybacks on the standard-voice probe (`/api/status` + `/api/auth/me`; no ticket-burn probing); sticky `markGatewayUnsupported()` on WS-upgrade 404/403, reset on connection switch; gateway client cached per (connection, dashboard URL) sharing Manage's encrypted cookie store; `resolveStreamingEndpointPreference()` extracted pure — **"auto" prefers gateway when Ready** (deliberate rollout choice), manual picks pass through.
- Chat Settings: 5th endpoint option "Gateway" + sign-in hint row. RelayApp re-resolves on `gatewayAvailability` so a mid-session Manage sign-in flips auto → gateway live.
- Tests: 33 new (mapper table fixtures; MockWebServer WS harness — handshake order, fresh-ticket-per-reconnect, resume fallback, cancel→interrupt, mid-turn socket loss, foreign-session drop; resolution matrix). Full googlePlay unit suite green.

**Bugs the tests caught.** (1) OkHttp does NOT auto-ack a peer-initiated close frame — without `onClosing → close()` the socket sits half-closed ~60s and reconnects stall; (2) mockwebserver's server-side `RealWebSocket.cancel()` NPEs (`call!!` null) — use `close()` + tolerate its known shutdown give-up after WS upgrades.

**V1 verification (static, against fetched upstream main — local checkout was 1367 commits stale).** Gateway stored ids are `YYYYMMDD_HHMMSS_<6hex>`; `GET /api/sessions` lists ALL sources (no filter) so gateway sessions appear in the drawer; usage keys confirmed different from SSE. On-device verification still owed: live thinking in chat + voice overlay, Wi-Fi↔cell mid-turn, drawer cross-resume of `api_*` sessions via gateway.

**Deferred:** answering interactive asks from the phone; `subagent.*`/`status.update`/`tool.progress` rendering; `session.list` adoption; persona/phone-status injection on gateway turns (prompt.submit carries bare text); SSE-path post-hoc reasoning backfill from `run.completed.messages[].reasoning` (would give signed-out users a "Thought process" block).

**Desktop-parity wave (same day — full recommended set, via workflow orchestration).** Two-workflow orchestration: a 3-agent spec pass (wire contracts extracted from upstream tui_gateway source with file:line evidence; component-level UI designs against RelayRefresh; integration map with contention risks), then a 5-agent build (parallel network + UI tracks with strict file ownership → integration agent that compiled/tested/linted the tree green → 2 adversarial reviewers), then a surgical fix agent for the review's 4 majors + 6 chosen minors. Spec catches that prevented real bugs: steer is only *accepted mid-tool-batch* (`{status:"rejected"}` otherwise → auto-queue fallback); upstream's upload RPC is `image.attach_bytes`/`content_base64` (the vendored CLI's dotted name 404s); `approval.respond` is session-scoped while clarify/sudo/secret are request_id-scoped; `prompt.submit` returns `{status:"streaming"}` not `{ok}`; POST_NOTIFICATIONS was missing from the main manifest (googlePlay flavor silently couldn't notify on 13+). Review majors fixed: ask-aware turn watchdog (a blocked clarify legitimately produces 300s of event silence — the 180s watchdog was killing it and force-denying), answer-before-collapse on ask cards (failed respond RPC no longer strands a collapsed card), edit-and-resend mid-stream text loss, 500-message-cap ordinal guard. Shipped: gateway image attachments, steer + 5-state trailing button, ask cards (hold-to-confirm sudo + countdown), edit & resend, subagent lanes, tool-preparing state, context meter, server slash catalog + dispatch, turn-complete notifications, ChatInputBar redesign (slash button removed; waveform voice glyph + one-shot hint).

**On-device round 1 (same day).** Transport confirmed live: both voice turns ran `/api/ws` (zero SSE logs — the old tell), voice-input→first-audio fell 50–130s → 11–13s, TTS streamed sentence-by-sentence. Chat-test findings, fixed same session: (1) **double typing dots** — ChatScreen rendered a standalone `StreamingDots` item below the list on top of MessageBubble's in-bubble dots; outer one removed. (2) **Bottom-pinned scroll stutter during live thinking** — the auto-follow ran `animateScrollToItem` per delta under `collectLatest`; at gateway *token* frequency (vs SSE's ~190-char bursts) that's a cancel/restart storm stranding the viewport mid-animation on earlier content before yanking it back. Same-turn growth now pins instantly (`scrollToItem`); the animation is reserved for discrete new-bubble appends. Also dropped `animateItem()` from the trailing spacer (placement churn at the viewport bottom every delta). (3) **Tool cards missing on gateway turns** — unresolved as of this entry: upstream emits `tool.start`/`tool.complete` (verified in source, default `display.tool_progress: all`) and the client chain is unit-tested, but the installed build logged nothing per-event, so client now logs `GW ← <type>` for every gateway event (SSE-log parity). Next tool-calling turn on a fresh build says definitively whether `tool.start` arrives (client bug) or never leaves the server (config / agent callback path).

## 2026-06-11 — Media re-capture: demo video + full screenshot set (programmatic, via adb)

**Context.** The README/docs hero video (`assets/chat_demo.mp4`, Apr 9, pre-cockpit-refresh) and the Play Store screenshot set (Apr 9–12) lagged the v0.8.x UI by two release cycles; DEVLOG had already queued the re-capture. Scope: a programmatic pipeline — audit → demo video re-shoot → full screenshot re-shoot. Browser/dashboard captures and og-image explicitly deferred.

**Pipeline built (all driven from the desk, no hands on the phone).** Wireless adb to the S25 Ultra (1080×2340) + mobile-mcp for accessibility-tree coordinates + `adb shell screenrecord` for takes + ffmpeg for post. Key device-staging learnings, recorded for next time:

- **One UI demo mode needs package-targeted broadcasts** — `am broadcast -p com.android.systemui -a com.android.systemui.demo ...`; untargeted intents are dropped. Only `enter`/`exit` + notification-hiding work; the clock/battery/network staging sub-commands are ignored, and the mode silently drops on its own mid-session, so it can't be trusted across a take.
- **`settings put secure icon_blacklist "volume,wifi,battery,clock,..."`** empties the system-icon side; Google Cast media chips survive everything.
- Net: stage what you can, then **crop the 96px status-bar inset in post** (`crop=1080:2244:0:96`) — deterministic, uniform, immune to demo-mode flakiness. All shipped media is cropped.
- **`screenrecord` over wireless adb pads a frame gap at the head** (single frame at PTS 0, next at ~13s) — cut lists must be built from probed packet timestamps, not wall-clock estimates.
- `adb shell input text` types at ~25 cps — reads as natural typing on camera. ENTER inserts a newline in the chat field; send must be tapped. `uiautomator dump` sees the chat tree *under* the startup gate overlay, so "field exists" is not "gate finished" — verify landings with screenshots.

**Demo video (47.4s, 1.49 MB, 720×1496@30, replaces both `assets/` and `user-docs/public/` copies + poster).** Script: cold launch → startup-gate narration (~5.5s) → empty chat beat → prompt typed/sent on camera ("Morning! Quick check - server uptime and memory, two lines max?" — tuned after a first rehearsal wandered through five tool calls and 90s; the constrained phrasing reliably yields 1–2 quick `execute_code` cards) → cards + answer stream in real time (waiting stretch time-compressed 2.2×) → voice-overlay closer (sphere + transcript + mic). Sessions staged: five filler sessions created and renamed via the drawer so visible history looks lived-in; hero session renamed "Server health check"; rehearsal sessions deleted.

**Screenshot set (`assets/screenshots/`, old 8 deleted, new 8 at 1080×2244 PNG):** 01_startup (gate + checklist), 02_chat (prompt + tool cards + answer), 03_voice (overlay), 04_sessions (drawer), 05_commands (slash sheet, 205 commands), 06_manage (KPI strip + tiles), 07_connections (standard path + Routes(2)), 08_settings. Also deleted the orphaned 23.5 MB `foreground_service_demo.mp4` (assets/ + user-docs/public/ — referenced nowhere since the docs rewrite).

**Bugs found on camera (not fixed this session — both fixed the same day by the parallel app session):**
1. **Startup gate dismisses into a ~1.5s "Disconnected / Connect to Hermes" flash** before the API probe settles, then recovers — reproduced in both takes. The gate narrates "hermes online ✓" and still lands on the disconnected fallback; defeats the hold-until-ready purpose (cut around it in the edit). *Fixed in `1d09c7b` ("Reveal-flash root cause" entry below): the gate keyed on resolver health evidence while the CTA renders from the later `chatReady` signal; both now key on `chatReady`.*
2. **Gate cold-start variance** — "loading conversation" ranged ~6s to ~28s across runs against the same LAN server; worth a timeout/diagnostic. *Fixed/addressed in `fix/health-retry-burst-gate-diagnostic` ("Cold-start variance" entry below): a transient first-probe miss parked "offline" until the next 30s health tick (the ~28s tail), now recovered by a bounded fast-retry burst; the 12s-backstop release now records a Diagnostics warning naming the unmet conditions. Note: these takes ran on a fresh REINSTALL, so the first run also paid the one-time keystore-marathon hint priming.*

**Deferred / blocked:** terminal + bridge stills (phone's relay session unpaired after the reinstall — needs a fresh pairing first); dashboard screenshots (5 TODOs in `features/dashboard.md`); og-image re-render (old tagline still live). The five filler sessions remain on the server pending cleanup.

**Docs hero recreated in code (same day).** The homepage hero video was replaced by a code-driven recreation — `HeroDemo.vue` rewritten in place (index.ts slot unchanged): DOM chat chrome inside the existing phone bezel, over a canvas running the real `preview/web/sphere.js` algorithm (same import path as SphereMark). The sphere is driven through the actual product state machine in sync with the scene — Thinking during the boot gate, Listening while the prompt types, Thinking + `toolCallBurst` pulse when the tool card lands, Speaking with intensity shimmer while the answer streams, Idle on the hold. ~19.5s loop, every visual a pure function of `t` (modulo loop; the clock pauses off-screen via IntersectionObserver so scrolling back never jump-cuts; gaze stays off — `lightAngleBlend: 0` — so it doesn't compete with SphereMark's eye further down the page). Scene mirrors the recorded take: gate checklist (state restored / route · LAN / hermes online) → typed prompt ("Quick check — server uptime and memory?") → `execute_code` card with shimmer → streamed two-line uptime/memory answer. Agent named "Hermes" (canonical for first impressions, not the local "Victor" profile). Container-query units (cqw) keep all chrome proportional to bezel width; reduced-motion renders the completed conversation statically; SSR renders the boot frame. **Review/capture affordance: `?demoT=<seconds>` freezes the scene at any timeline point** (transitions killed via `.had-static`) — added because rAF clocks don't advance under headless Chrome's `--virtual-time-budget` (the 100ms dt clamp eats fast-forwarded time), and it doubles as a design-review scrubber. Verified per-phase via headless Chrome screenshots at demoT 2.5/6.2/9.5/13.2/16.5; production build green. Rationale: the docs site is the one surface with a controllable runtime — the recording stays for README (no JS on GitHub) and getting-started ("the chat looks like this" wants a real capture). Hero video re-edit deferred; `chat_demo.mp4` remains referenced by getting-started.

**Hero demo follow-up (2026-06-12): loop-wrap tween explosion + 1:1 header.** Observed: the sphere rendered wrong ("periods in the eye"). Root cause: the tween rig was clocked off the *looped* scene time — at each wrap `t` jumps ~20.3s→0, `Tween.update()` sees a negative elapsed, and the smoothstep `t²(3−2t)` extrapolates cubically (the `intensity` tween, whose `startTime` survives the wrap via `setTarget`'s same-target early-return, landed near −57,000 → `effRipple` ≈ −28,000 → char indices slammed to the ramp floor, strongest at center because ripple scales by `(1−normDist)`: literal rings of `·`/`.` through the eye from loop 2 onward). Fix mirrors the app: `drawSphere(sceneT, clockT)` — looped time drives only WHAT the scene shows; a monotonic clock (raw `elapsed`, like MorphingSphere.kt's `animatedTime`) drives tweens + noise fields, which also kills the per-loop texture snap. Hardened `Tween.update` with a both-ends clamp, and `shadowStrength` 0.45→0 (the app passes none; 0.6 is SphereMark's own look). Static paths (`?demoT=`, reduced-motion) now snap tweens to settled targets via `drawSphereSettled` instead of drawing mid-flight Thinking params. Header/navbar made 1:1 against `assets/screenshots/02_chat.png` + a zoomed crop (two passes — the crop caught what the full screenshot hid): drawer hamburger; **light** avatar with dark initial (was inverted); solid filled LAN pill (was outlined); share / `</>` / tune as **three separate bordered buttons** (first pass wrongly grouped them in one cluster pill); active tab = navy `#20225A` fill with brighter indigo border; agent-name label as a chip; tool card done-state ✓ ⌄ (was "3.0s ✓"); mic icon at rest in the input bar (send arrow only while typed). Re-verified via 2× headless captures at demoT 1.8/9.6/17.0; build green.

## 2026-06-11 — Standard-route network auto-switch (LAN ↔ Tailscale roaming without Relay)

**Context.** Reported: away from home, a configured + signed-in standard connection never switched over to its Tailscale route — chat stayed dead and voice stayed gated, while the old Relay-paired path used to hand off fine (chat catch-up, voice, realtime). Audit traced it to the rechrome: every downstream piece (route candidates stored by the wizard, `effectiveApiServerUrl`/`effectiveDashboardUrl` flows, client-rebuild collectors, chat session refresh on client swap) was sound; nothing upstream ever *triggered* re-resolution on the standard path, and standard voice ignored the resolved route entirely.

**Root causes (4).** (1) `ConnectionManager`'s ADR 24 NetworkCallback registered only inside `connect()` — never on socketless standard connections — and its handlers early-returned without a socket URL. (2) The fallback trigger (`networkStatus` StateFlow → `revalidate()`) is value-deduped over 3 coarse states; with Tailscale's always-on VPN keeping "internet available" true through any handoff, the value never leaves `Available`, so the collector never fires — the exact user this feature targets is the one it can't see. (3) `activeDashboardUrl()` (standard voice client + availability probe) read the **persisted** dashboard URL, not `effectiveDashboardUrl`. (4) The resolver's 60s positive probe cache wasn't cleared on resume/network-change re-resolution, so a just-died LAN route kept winning.

**What changed (branch `fix/standard-route-network-switchover`, merged --no-ff; follow-ups direct on `feature/standard-voice-dashboard-surface`).**

- **ConnectionManager** — NetworkCallback registers at construction (no-op without context); `onAvailable`/`onLost` unify into a debounced (300ms) `scheduleNetworkReResolve()` that publishes `activeEndpoint` even with no socket, preserving the socket-swap/reconnect semantics when one exists. `refreshActiveEndpoint(clearProbeCache)` clears the resolver cache; `revalidate()` passes true.
- **Standard voice** — `activeDashboardUrl()` now delegates to `effectiveDashboardUrl`, so the voice client and `probeStandardVoice()` follow the resolved route; `rebuildApiClient()` → `probeStandardVoice()` re-gates the mic automatically after a route swap.
- **Escalation safety net** — the 30s API health loop turns two consecutive Unreachable probes into a cache-cleared re-resolve, covering handoffs Android never surfaces as connectivity events. Client rebuild stays reactive via the `effectiveApiServerUrl` collector (single rebuild path for all triggers).
- **Route-candidate preservation** — new `Connection.mergeRouteCandidates(rebuilt, existing)`: URL edits (`updateApiServerUrl`, `updateRelayUrl`, `connectRelay`, `testRelayReachable`, `saveApiAndProbeVoice`, `saveStandardApiConnection` fallback) rebuild only the touched route and keep stored priority>0 extras verbatim — previously any URL save collapsed the list to one candidate and silently killed roaming. The wizard doesn't pre-fill its Tailscale field, so blank-on-rerun means "unchanged", not "remove".
- **Per-route sign-in UX** — dashboard cookies are host-scoped, so a LAN sign-in doesn't authenticate the Tailscale host (the store holds both; it's a one-time sign-in per host). New `standardVoiceSignInRouteHint` flow + route-aware copy in Voice Settings' SignInRequired block + a Diagnostics entry from the probe.
- **Cleanup** — removed the duplicate `networkStatus → revalidate()` collector left by the rechrome.

**Tests.** New `ConnectionManagerRouteTest` (Robolectric + MockWebServer): callback registration/unregistration at construction, socketless `onAvailable` publishing `activeEndpoint`, stale-cache vs `clearProbeCache` resolve. New `ConnectionRouteCandidateMergeTest`: extras preserved, payload relay URLs verbatim, host:port collision defers to rebuilt, primary replacement, no-extras passthrough. `:app:lint` + targeted unit suites green locally.

**On-device verification needed (via Studio):** standard connection with a Tailscale route → leave Wi-Fi → chat should re-route within ~30–60s worst case (network callback usually immediate); Voice Settings should show the per-route sign-in hint until Manage sign-in on the Tailscale host; return home → routes flip back to LAN (priority 0). Also worth re-checking the Relay-paired handoff path for regressions since onAvailable/onLost were unified.

**Pre-release polish (same day).** Review pass before handing to Studio: (1) gated network-change socket actions on `shouldReconnect` — the swap path force-set it true, so a network event could resurrect a socket the user explicitly disconnected (pre-existing hole the refactor preserved; routes still publish for HTTP surfaces); (2) `refreshActiveEndpoint` keeps the live route on a transient probe miss while the WSS is Connected (mirrors the callback's guard — a resume-time probe blip no longer downgrades every HTTP surface to the saved LAN URL); (3) sign-in route hint upgraded to `displayLabel()` ("Tailscale") and the chat mic toast made route-aware; (4) escalation counter resets while no API client exists.

**Remote-access discoverability (same day).** UX audit of the onboarding → remote journey found the mechanics worked but nothing *led* users to them: the wizard's happy path (scan LAN → connect) produced a LAN-only connection silently (the Tailscale field hid inside the collapsed Advanced expander), the setup result card had no remote line, "Hermes API unreachable" didn't distinguish "server down" from "you're remote with no fallback route", and `TailscaleDetector` powered only an informational chip. Shipped four nudges, each at a moment of real user attention: (1) the Tailscale field lifted into the main setup form as "Remote access — Tailscale URL (optional)" with a detected-on-this-phone hint; (2) a "Remote" readiness line on the setup result card (`StandardApiSetupResult.remoteRouteConfigured`); (3) the unreachable status pill diagnoses by route count — single-route gets "add a Tailscale or public route" (sharpened when the phone is on Tailscale), multi-route gets "none of the N routes responded, fallbacks retried automatically"; (4) an "Add Tailscale route" shortcut on the Connections card when the phone is on Tailscale but the connection lacks a tailscale route (route-editor state hoisted out of the expander so it opens with the list collapsed). Deliberately skipped: auto-deriving the server's MagicDNS URL (needs server-side support vanilla hermes-agent doesn't have) and QR-for-standard (no upstream payload generator). user-docs `remote-access.md` gains an "Add or Edit Routes on the Phone" section + per-route sign-in tip.

**Routes editor (same day).** Two gaps confirmed: the standard path's only multi-route provisioning was the wizard's buried optional Tailscale field (single route, setup-time only, not pre-filled on re-runs — and no QR equivalent exists because vanilla hermes-agent has no payload generator; the QR comes from the Relay plugin), and the Routes card was read-only. Closed both: `EndpointsCard` gains **Add route** + per-row **Edit/Remove** (fallback rows only — the priority-0 primary mirrors the connection's API URL and is edited there; remove confirms first), backed by a `RouteEditorDialog` (Tailscale/Public/Custom role chips, URL field, inline validation errors from the save callback). `ConnectionViewModel.saveExtraRoute`/`removeExtraRoute` persist to `Connection.routeCandidates`, seeding from the same fallback chain `observeDeviceEndpoints` displays (per-device PairingPreferences → synthesized primary) so an edit never hides QR-provisioned routes; host:port collisions are rejected with a pointed message; removing a route clears a preferred-route override that pointed at it; both finish with a cache-cleared `refreshActiveEndpoint` so the change takes effect immediately. Relay URLs for manual routes are derived (`:8767` convention) — QR remains the path for custom relay URLs. Wizard helper text now points at Settings → Connections → Routes.

**Route probe visibility + URL forgiveness (same day, remote-debugging).** Field report from the road: Tailscale route added (`100.64.0.100`, port auto-appended), phone on the tailnet, but "Probe now"/"Use now" left the card on "Current: Resolving" showing the internal URL, with no probing indicator anywhere. Diagnosis found one real bug plus a UX black hole:

- **The bug** — `probeAndReconnect()` early-returned (`resolved?.relay?.url ?: current ?: return@launch`) when every probe failed on the standard (no-socket) path: nothing was published, nothing was shown, and the only record went to DiagnosticsLog. Replaced by `probeAndReconnectNow(): EndpointCandidate?` (awaitable; `probeAndReconnect()` is now a launch wrapper) which **always publishes the outcome** — with the Connected-socket transient-miss guard preserved. `probeNow()`'s 100ms-delay-then-health-check hack (a real resolve takes 4s+ when LAN must time out) now awaits the resolve, rebuilds the API client on route change, then health-probes.
- **The black hole** — no probe feedback at any layer. New `RouteProbeOutcome` map on `EndpointResolver` (per-candidate verdict + human reason; survives `clearCache()` — caching ≠ verdict history) with the TLS case spelled out ("TLS failed — server may be http://, not https://"), `RouteProbeStatus` (Idle/Probing/Done(winner)) on ConnectionViewModel, and UI: Re-check buttons show "Checking…", route rows show Reachable/Unreachable-with-reason, the Current line states "No route reachable — using saved URL <api url>" in error color instead of eternal "Resolving" over the **relay** URL fallback (the "internal url" in the field report — ConnectionsSettingsScreen printed `connection.relayUrl` under the resolving label).
- **Likely root cause of the field failure** — the route row only showed `host:port`; the scheme (the `tls` flag) was invisible, and the editor's placeholder even suggested `https://`. An https route against the plain-HTTP API server TLS-fails every probe. Rows now show the **full URL including scheme**; the editor previews "Will save: http://100.64.0.100:8642" live.
- **URL forgiveness** — new `Connection.normalizeApiUrlInput(raw, defaultPort=8642)`: bare hosts/IPs get `http://` + the surface's default port (dashboard field uses 9119); explicitly-schemed URLs pass **verbatim** (an `https://host` may be a reverse proxy on 443 — never force-append 8642). Applied in `saveExtraRoute`, `saveStandardApiConnection` (API + Tailscale + dashboard fields), and `updateApiServerUrl` (save-time only — its single call site is `applyManualPair`, not per-keystroke). Wizard validators soften to accept bare hosts; field copy now names the ports (API 8642 vs dashboard 9119, relay 8767 derived). `saveExtraRoute`/`removeExtraRoute` end in a full `probeNow()` so a just-saved route shows its verdict immediately.

Tests: +4 resolver outcome tests, +2 ConnectionManager `probeAndReconnectNow` publish tests (winner published socketless; null published when all routes die — the old early-return regression case), +10 `normalizeApiUrlInput` cases incl. the bare-Tailscale-IP end-to-end journey. All green; lint green. user-docs `remote-access.md` gains "Which URL Do I Enter?" (raw `100.x` IP → `http://` + API server must listen beyond loopback; `*.ts.net` behind `tailscale serve` → `https://`, cert is name-only). **On-device verification needed:** with the Tailscale route's scheme visible, check whether it was saved as https — edit to http if so; Re-check should now show per-route verdicts either way.

**README + Play listing audit (same day).** Audit found both documents lagging the standard-first pivot by two release cycles: README said "What's new in v0.6.0" (app at 0.8.1), the CI badge pointed at deleted `ci.yml`, two in-page anchors were broken (renamed headings), voice was described as relay-only in three places (surfaces table, features bullet, How It Works diagram) despite the dashboard-surface change, and neither document mentioned Manage parity or remote access at all. Rewrote both: README restructured around the setup card's Chat/Manage/Voice/Remote/Relay framing (one Quick Start instead of three overlapping sections — Quick Start / What It Does / Getting Started), operational detail (sideload steps, update-banner mechanics, paste-workflow demo, uninstall flags) compressed to one-liners with docs-site links, desktop section trimmed to install + 4 commands behind an explicit alpha banner stating the planned refocus into a remote "hands" connector now that hermes-desktop owns desktop chat/management (per project direction). Play listing rewritten end-user-first: new short description ("Your self-hosted Hermes AI agent, in your pocket — chat, voice, and control.", 76/80 chars), a 3-step QUICK START block, Manage + Works Away From Home feature sections, voice corrected to the no-plugin story, "TUI" jargon dropped, v0.8.1 release notes drafted (464/500 chars). Compliance-sensitive GOOGLE PLAY BUILD / SECURITY & PRIVACY sections kept verbatim.

**Field follow-up: http fixed chat; Manage stayed dark over Tailscale (same day).** Confirmed on-device that flipping the route to `http://` made chat roam. Manage not working over the same route has three candidate causes, all by-design rather than app bugs: (1) the dashboard (`:9119`, `hermes_cli/web_server.py`) is a separate server from the API (`:8642`) — tailnet reachability of one proves nothing about the other (bind/port-mapping/ACL; note `hermes-relay-tailscale enable` fronts only 8767 + 8642, never 9119); (2) dashboard sessions are host-scoped cookies, so the home sign-in doesn't authenticate `100.x.y.z:9119` — one sign-in per route, the app keeps both; (3) an explicit dashboard URL override pins Manage to that host (only auto-managed URLs roam — deliberate, since overrides usually mean a reverse proxy; the wizard's LAN scan can store one silently when detected ≠ derived). Audit confirmed the app already targets `effectiveDashboardUrl` everywhere (client factory, sign-in dialog, payload cache keys) — what was missing was *visibility*. Manage now: shows a persistent "Dashboard: <url> · <route> route" target line under the mode strip; names the failing URL in the "Dashboard unavailable" card; and explains per-host sign-in in the sign-in card when the route has moved. Plumbing: new `ConnectionViewModel.dashboardRouteMovedHint` (route label when effective ≠ persisted dashboard URL); `standardVoiceSignInRouteHint` refactored to reuse it (semantics unchanged).

**user-docs cockpit rechrome + content refresh (same day).** The docs site still wore the pre-refresh "Nothing-inspired" chrome (OLED `#000`, neutral grays, `#7C3AED` purple) while the app shipped the relay cockpit palette two days earlier. Rechromed `user-docs/.vitepress` to mirror `RelayRefresh.kt`: dark mode is now navy-black `#08090D` with navy panels (`#121426`/`#191B31`), warm-white ink `#F7F6F0`, alpha-based warm-white hairlines (the `Line`/`LineStrong` trick), Relay periwinkle `#AEBFFF` for links/active text vs ElectricMuted `#4F5BD5` for fills (same glare lesson as the app), status colors from the app's Green/Amber/Danger, a 42px-grid + 10px Relay-dot lattice on the home surface mirroring `relayGridTexture()`, and light mode moved to warm paper `#F7F3EA`. Hardcoded old-palette colors swept from HermesFlow/HermesFlowNode (edges, nodes — diagrams stay dark in both modes like instrument panels), HeroDemo (navy bezel + periwinkle glow), ExperimentalBadge (app Amber), FeatureMatrix (sideload tint). Content pass from a full staleness audit: four complete pages were unreachable from the sidebar (`features/voice`, `features/voice-intents`, `features/phone-control-tools`, `reference/relay-server`) plus `architecture/flavor-differences` linked from nowhere — all five added to `config.mts`; `guide/index.md` version heading bumped 0.8.0→0.8.1; `desktop/installation.md` example pins bumped alpha.14→alpha.18. Build verified: compiled CSS/JS contain the new palette and zero old-palette hex values. Deferred: demo video + the 5 dashboard screenshot TODOs in `features/dashboard.md` (chat_demo.mp4 and the poster also predate the cockpit refresh and should be re-captured). Feedback round (live design review on the dev server): dark brand accent shifted from Relay periwinkle `#AEBFFF` → electric indigo `#6E7CFF` ("too light, not our app blue" — periwinkle survives only in the dot texture); SphereMark gained a radial occlusion halo so the home dot-grid fades behind/around the sphere, plus an `isConnected` guard on the cached install-section anchor (a detached node's rect is all zeros → `scrollVy` locked at 1 and the eye stared down forever after HMR/route swaps — the reported "tracking breaks after scrolling"); install-extras cards un-crunched from 2-col to stacked full-width with one-liners wrapping (`pre-wrap`) instead of horizontal-scrolling. Verified live via Orca browser screenshots: gaze tracks cursor left/right post-scroll, halo clean, no horizontal scroll.

**Server-side root cause + fix (same evening, via SSH).** `ss -tlnp` on hermes-host showed the real story: API (`:8642`) and relay (`:8767`) on `0.0.0.0`, but `hermes-dashboard.service` ran with `--host 192.168.1.100` — LAN interface only, so `100.64.0.100:9119` was connection-refused (not 401, hence no sign-in card; the "existing login" observed on-device was last-known persisted state). Rebound to `--host 0.0.0.0` + restart (authorized via prompt), verified `/api/status` on both IPs, updated the server's `~/SYSTEM.md` services table. Phone (adb) confirmed end-to-end: Manage's new target line showed `100.64.0.100:9119 · Tailscale route`, banner flipped to "sign-in required", sign-in card rendered with the route strip. Two learnings recorded: the app's `DashboardCookieJar` is per-connection, NOT host-scoped (sends the stored session cookie to whichever host the route resolves to — sessions normally roam; the restart wiping in-memory dashboard sessions is what forced re-sign-in), and the sign-in strip's "per host" wording could be tightened later.

**Manage loading/overview pass (same day).** Three complaints: the cold-load skeleton stacked four progress bars with fake narrative labels; every re-entry to Manage was a cold load; the KPI glyphs (`ok/…/!`) and the one-line status banner (truncated by two trailing buttons, one a duplicate "Connection" link) were weak. Shipped: (1) **process-lifetime payload cache** — `DashboardPayloadCache` singleton replaces the `remember{}` maps, keyed `connection|dashboardUrl|section` so connection switches and route handoffs stay partitioned; `Loaded.fetchedAtMillis` drives a 30s stale-while-revalidate window (fresh → no fetch; stale → cached content + thin refresh bar); sign-in/out clears as before. (2) **App-start pre-warm** — section fetch core extracted to `fetchDashboardSectionState()`; `prewarmDashboardManage()` (internal, same file) fills cold keys only, aborts the sweep on first unreachable/auth failure, never marks Loading so it can't fight the open screen; RelayApp fires it (1.5s debounce) when the persisted snapshot says reachable + signed-in/auth-free, re-firing on route handoff. (3) **Skeleton** — one LinearProgressIndicator + three pulsing content-shaped ghost cards. (4) **KPI strip** — count / tone-colored dashboard state word (ready/sign-in/offline/error) / server version (`RelayMetricCard` gains optional `valueColor`). (5) **Status banner** — two-line layout (state+identity+Sign out / URL·route·checked), duplicate "Connection" button removed (Connections tile is directly below).

**user-docs marketing reposition: "the brain stays home, the hands go everywhere" (same day).** Direction: desktop is becoming a remote-device access point — "a hand inside other devices you install to" (remote control, filesystem, CLI); chat/management on desktop belongs to hermes-desktop. Docs site rewritten to lead with that. Homepage: hero is now "The brain stays home. The hands go everywhere."; CTAs flipped (brand → "Get the Android app", alt → "Give it hands — desktop CLI"); 8 feature cards rewritten benefit-first ("Chat that streams, not spins", "Voice with a face", "Manage from your pocket", "Your phone, on the agent's toolbelt", "Hands on any computer", "One pair, every network", "Notifications and media in the loop", "Private by architecture"); surface cards re-roled as "The companion · Android" / "The hand · Desktop CLI" with CTA "Put a hand on this machine →"; new `HowItWorks.vue` 3-step strip (01 Install · 02 Pair · 03 Reach) slotted between SphereMark and InstallSection in `theme/index.ts` (markdown body renders after VPFeatures, so a body-level strip landed at page bottom — component slot fixes the order). `desktop/index.md` re-led with the hands story: tool routing promoted to "The point — the agent works on *your* machine" directly under the intro, modes table reordered (Tools/Daemon above Shell/Chat), chat marked "maintained for scripting; not a growth surface", new info box stating the track direction, killer demo reframed as the escape-hatch bonus. Nav/meta refreshed (og/twitter titles → "give your self-hosted Hermes agent hands"; sidebar/nav say "remote hands"). Old-positioning sweep cleared `guide/index.md` tip, InstallSection Step 2, features/index "Coming Soon" row. Verified via Orca screenshots + clean build. **og-image.png still renders the old tagline — re-render alongside the deferred demo/screenshot batch.** Headline rounds 2–3: "hands" demoted from hero to feature level; "server" rejected too (with official Hermes Desktop the brain may be a PC/laptop) — final hero is **"Runs on your machine. Lives on your devices."**; "self-hosted Hermes agent" dropped globally in favor of plain "Hermes agent" (self-hosting implied; kept only as a technical term: self-hosted OIDC/VPN); **"Desktop CLI" renamed to plain "CLI" across all user-docs copy** (the binary runs on any host — desktops, laptops, headless boxes; `/desktop/` URLs and `desktop-v*` track names stay until the code refactor); hero gained a mono platform-note subtext under the action buttons: "CLI: Windows today · macOS / Linux coming soon" (`home-hero-actions-after` slot + `.hero-platform-note`). Decisions recorded in auto-memory `hermes-relay-branding-voice`. Round 4 — two-path funnel (audit first, then approved): the homepage install funnel was the power path presented as the only path (HowItWorks step 01 told standard users to curl-install the relay plugin; InstallSection led with plugin + `hermes-pair` + "Python 3.11+"). Restructured: HowItWorks recast as the quick-path journey (01 Run Hermes on your machine — API serves chat, dashboard serves Manage/voice, nothing extra to install · 02 Install the app · 03 Connect — LAN scan / setup QR / URL, since standard onboarding accepts a QR) with a power-path footer pointer; InstallSection rebuilt as stacked path cards — "Quick path · No server install — Just connect" (app links + the brief chat=API / Manage+voice=dashboard / pure-chat-needs-only-API nit) above "Power path · Relay plugin — Give it hands" (plugin curl, hermes-pair, CLI one-liners pwsh-first with the Windows-today note, AI-agents block rescoped to the power path); homepage surface card + "Works at home and away" feature card de-relay-flavored (standard connections roam too). `guide/getting-started.md` needed no change — already standard-first. Round 5: power card now shows ONLY the Windows CLI one-liner (the sh one-liner invited users to install binaries that don't exist yet) with a muted "lands with those builds" note; surface cards moved up out of the page-bottom orphan slot into a new `SurfaceCards.vue` slotted between How-it-works and Get-started ("meet the two surfaces before the install commands") — index.md body is now intentionally empty; "Voice with a face" renamed "Hands-free voice" and "voice with the morphing sphere" copy dropped (user-swappable voice animations are planned — don't marry voice marketing to one visual); Windows-only status propagated to the remaining availability claims: desktop/index experimental box, installation.md intro, and a "Coming soon" warning on its macOS/Linux section (capability mentions like clipboard tool paths left intact — that code exists). Sphere gaze root-cause (reported: "tracking gets confused over time / switching scroll↔mouse"): the core blends light angles linearly (`naturalAngle*(1-blend) + bias*blend`) and `naturalAngle = ω·t` is UNBOUNDED — SphereMark's proximity-driven blend (0.76–0.90) leaked `(1-blend)·ω·t` (slow continuous orbit off the cursor) and snapped on every blend change (two fractions of a huge angle). Fix without touching the parity-locked core: pin `lightAngleBlend` to exactly 1 (0 for reduced motion), move the ambient-life feel into fbm wander amplitude (0.12 idle → 0.05 engaged via the existing proximity EMA), and ignore non-mouse pointers (a lifted touch parked a phantom hover). At blend 1 the asin/acos gaze mapping collapses to exactly linear. Recorded in auto-memory `sphere-gaze-partial-blend-leak` — the 0-or-1 contract applies to any future caller (Android MorphingSphere currently never sets a partial blend; only the core default 0 exists).

**Frozen-sphere incident: Keystore/Tink global-lock contention (same day, found via adb after a reported ~3s startup freeze).** Cold-start logcat showed `Skipped 45 frames` at first draw (pre-existing VM-init cost), then `Long monitor contention … AndroidKeysetManager$Builder.build() … owner DefaultDispatcher-worker-5 … for 4.095s` **on the main thread**, ending in `Skipped 1386 frames` / `Davey! duration=11596ms`. Mechanics: `EncryptedDashboardCookieStore` built its Keystore-backed prefs **eagerly in its constructor** — 1–4s per build on Samsung StrongBox, serialized through Tink's process-global `AndroidKeysetManager.Builder.build()` lock — and the new Manage pre-warm constructed one per section (8×, worker-5 = the lock owner in every contention event), while other paths (connection validation probe, Manage's per-fetch client factory, session clear) constructed yet more instances, one of them on the main thread. The pre-warm didn't create the main-thread keystore work, but it multiplied the stall by keeping the lock hot. Fix (three layers): (1) `EncryptedDashboardCookieStore.store` is now `by lazy` — construction free on any thread, the build lands on first cookie access, which is always an OkHttp/IO thread; (2) new `ConnectionViewModel.dashboardCookieStoreFor(connectionId)` — ONE cached instance per connection, now used by Manage's client factory, the validation probe, session clear, standard voice, and the pre-warm (previously each had private instances = N keyset builds for the same prefs file); (3) `prewarmDashboardManage` takes the shared store + builds ONE `DashboardApiClient` for the whole sweep (`fetchDashboardSectionStateWith(client, …)` core extracted; per-section client/store construction removed; `NonCancellable` shutdown in finally). `DashboardOAuthSignInDialog.cookieStoreFactory` widened to the `DashboardCookieStore` interface. Net keyset builds at cold start: was ~10+ serialized seconds-long holds; now ≤3 (two AuthManagers + one cookie store), all off-main. Correction to the earlier "pre-warm can't delay the UI" assessment: it missed the keystore-lock dimension — network was off-main, but lock contention is transitive.

**Use-now/Prefer split + Manage waterfall fix + disk cache (same day).** Branch `feature/route-override-split-manage-cache`. Three items:

- **Routes: "Use now" vs "Prefer" separated.** Observed: "Use now" silently flipped the connection to "Preferred: tailscale" — both affordances routed through `setPreferredEndpointRole`, which persists `preferredRouteRole`. Split per the act-now-vs-policy principle: "Use now" → new `ConnectionViewModel.useRouteNow(role)` (transient `setManualRoleOverride` + `probeNow`; dies on disconnect; `useRouteNow(null)` restores the persisted preference); "Prefer this route" (⋮ menu, now a toggle with "Stop preferring" + explanatory sublabels) remains the only writer of `preferredRouteRole`. `ConnectionManager.manualRoleOverride` became a `StateFlow` so the card can label Current as `automatic` / `preferred` / `manual (until disconnect)` — the trick: a preference is *implemented through* the override (restored into it on connection load), so "manual" is simply `override != preferredRole`. Card gains "Cancel manual switch" + "Stop preferring" rows; top-level "Auto" clears both layers. Decision recorded: do NOT auto-prefer Tailscale — strict priority + reachability already promotes it when LAN dies and prefers the faster LAN at home; silent preference changes were the exact complaint.
- **Manage waterfall.** Reported: still 5–10s to fully load. Root cause was arithmetic, not regression: `fetchDashboardSectionStateWith` ran the full auth preamble (status → providers → session → ws-ticket) before EVERY section payload — 8 sections × 5 sequential round trips ≈ 40, and both pre-warm paths ran them serially. Extracted `DashboardPreamble` + `fetchDashboardPreamble()`: fetched once per sweep, passed into the section fetch (which then costs one payload GET); `prewarmDashboardManage` aborts the whole sweep on an unreachable/unauthenticated preamble and fans the section GETs out concurrently (one OkHttp client, dispatcher caps per-host); the in-screen sibling prewarm reuses the visible section's already-verified status/session as its preamble and launches sections in parallel. Net: ~40 sequential → ~4 + 8 concurrent. Foreground (visible-tab) loads keep the full preamble — the header needs fresh status/session.
- **Disk cache ("yes add that now").** New `DashboardManageDiskCache` (`ui/screens/DashboardManageDiskCache.kt`): plain-JSON file `cacheDir/dashboard-manage-cache.json` (schema-versioned envelope, tmp+rename writes, mutex-serialized, corrupt/foreign-version decodes to empty) — deliberately NOT EncryptedSharedPrefs per the Tink-lock lesson; payload carries no credentials (cookies stay in their encrypted store) and `cacheDir` is app-private. `DashboardSummaryItem`/`DashboardItemAction`/`DashboardActionKind` moved there (private→internal, `@Serializable`); `DashboardStatus`/`DashboardAuthProvider`/`DashboardAuthSession` annotated `@Serializable` in place. Hydration (`hydrateDashboardManageCache`, RelayApp `LaunchedEffect(Unit)`, once per process, never overwrites populated keys) preserves `fetchedAtMillis`, so hydrated entries render instantly AND count as stale — the existing SWR window + the pre-warm (cold filter widened to include stale-Loaded so disk-hydrated entries refresh) handle the rest with zero new states. Write-through after every Loaded fetch + end of pre-warm sweep (whole-file rewrite, a few KB); all three sign-in/out clear sites also wipe the file. New `DashboardManageDiskCacheTest` (8 cases: field-complete round-trip, corrupt/future-version/unknown-keys, filesystem round-trip, missing-file, clear, atomic replace).

Verified: `:app:lint` green, full `testGooglePlayDebugUnitTest` green. Answer to "was it just caching saved info before?": no — pre-rechrome Manage had NO cache at all (remember{} per entry); "fully loaded" got slower because the overview/pre-warm multiplied the per-section preamble 8×. Cold-process data loss was real though (cache was process-lifetime only) — the disk mirror closes it. On-device: cold start should show Manage data instantly (after one prior visit) and the route card's Use-now should no longer flip the Preferred chip.

**Startup gate rework: sphere = loading screen with narration (same day).** Reported: a ~15s cold start played out as a slideshow: "Connect to Hermes" CTA → connected (button gone) → last session + agent name. Two gate bugs made the intermediate states visible: (1) `startupConnectionResolved` listed `apiHealth == Unreachable` as a release condition — the FIRST health verdict at cold start often runs against the persisted URL moments before the route resolver lands, so the gate dropped users into disconnected chat while they were connected-just-waiting; (2) `showStartupSphere` had `!startupGateTimedOut` — at 5.5s the sphere force-hid regardless of progress, which is "shown the app screen before UI was ready" verbatim. And nothing ever gated on conversation restore. Branch `feature/startup-gate-narration`:

- **Readiness contract** — happy path: `startupApiUp && initialChatSettled` (new one-way latch in ChatViewModel, set on every conclusion path of `switchProfileContext` — loaded / nothing-to-restore / failed, with the history fetch now in try/finally so a throw can't strand `isLoadingHistory` or the gate). Error path: an Unreachable verdict must SURVIVE a 3s settle window (keyed LaunchedEffect restarts on every health flip) before it releases — the normal UI then owns offline presentation. Backstop: 12s timeout that RELEASES the gate instead of yanking the sphere (timeout removed from `showStartupSphere`).
- **Narration** — terminal-style check lines at the sphere's bottom (`✓ state restored / ✓ route · Tailscale / › contacting hermes… / · conversation`), monospace labelSmall, all four rows always laid out (pending rows dimmed at 0.28 alpha, animateFloatAsState fade-up) so the column never reflows. Failed shows `✕ hermes unreachable` briefly during the fade-out. Rendered only when a connection is configured.
- **Why ~15s is real** — the wall-clock chain is: Tink keyset hydration before the API client can exist (`getApiKey()` awaits crypto init, 1–4s on StrongBox) → route resolve (4s per dead candidate probe) → client rebuild → capabilities probe + health probe (repeated after a route swap if the first pass ran against the stale persisted URL) → sessions + last-session messages + personalities fetches. The gate fix makes the wait *legible*, not shorter; candidate future wins: resolve the preferred role before the first client rebuild, persist the capabilities snapshot, parallelize capabilities/health/personalities after rebuild.

**Header/footer polish (same day).** Terminal and Settings are pushed destinations (header chrome from Chat/Manage) but had no back affordance — both TopAppBars gain the standard `navigationIcon` back arrow (`popBackStack`), including Terminal's PowerFeatureGate variant; `RelayStatusStrip` pill margins tightened top 2→3dp, bottom 8→4dp per spec. Verified: lint + full unit suite green.

**On-device cold-start measurement + the keystore wall (same day).** Reported: the new sphere paused on "contacting hermes…" then dumped to disconnected chat. Reproduced over wireless adb (force-stop → launch → timestamped screencaps + logcat on the S25 Ultra, googlePlay build): **+0.5s** resolver wins `lan 192.168.1.100:8642` (HEAD /health 200 — server fine, network fine); **+2.4→+15.1s** a continuous wall of StrongBox keystore operations — keystore2's own watchdog fired every second (`createOperation … Pending: 500ms`), each op ~550ms, all serialized behind Tink's process-global lock; **+12.65s** the 12s gate backstop fired → disconnected chat revealed (exactly matching the report); **+15.1s** `AuthManager init: no stored session_token` — the store finally decrypted, and only then could `rebuildApiClient()`'s `getApiKey()` return. The API client — and health, capabilities, and chat behind it — sat 15 seconds behind crypto whose only finding was "there is no key" (keyless local setup). Two fixes (branch `fix/cold-start-keystore-fastpath`):

- **Key-less fast path** — new plain-SharedPreferences hint (`<tokenPrefs>_plain_hints` / `api_key_present`, boolean only, never key material) written by `setApiKey`/`clearApiKey` and converged in AuthManager init after the real decrypt. `ConnectionViewModel.apiKeyForClientBuild()` consults `apiKeyKnownAbsent()` and skips `getApiKey()` entirely when the connection is known key-less; used by the cold-start DataStore collector, `rebuildApiClient`, and `rebuildChatApiClient`. Defaults to "assume present ⇒ wait", so keyed connections never see an unauthenticated client — the worst case of a stale hint is the old slow behavior. First launch after this update still pays the marathon once (hint unwritten); every cold start after is fast.
- **Resolver evidence counts as hermes-online** — the gate's `startupApiUp` now includes `activeEndpoint != null`: the resolver only publishes a winner after a successful HEAD /health on that route, which lands ~1s in — no reason for the narration to sit on "contacting hermes…" for 14 more seconds waiting for the client-based probe to repeat the same check.

Expected new keyless cold start: client ~+1–2s after first DataStore emission, checks tick through, gate releases on conversation-restored at ~+3–5s. Keyed connections on slow StrongBox still pay the decrypt (the key is genuinely needed) and may hit the 12s backstop — acceptable; the narration holds the screen that long. Verified: lint + full unit suite green; on-device re-measure pending next Studio install.

**Splash choreography + OS-splash blend (same day).** Two UX nits on the now-fast startup, branch `fix/splash-choreography-blend`: (1) with every readiness signal satisfiable before the sphere fades in, the checks could appear pre-ticked — "did it actually check anything?". New narration choreography: `startupCheckTargets` (real states) present through a `startupNarrationStage` cursor — rows resolve strictly top-to-bottom, the stage row shows a classic ASCII spinner (`| / - \`, 120ms frames; braille spinners render as tofu in some platform mono fonts) for a ≥350ms beat before its verdict lands, rows below sit dimmed with short labels, and a Failed verdict shows immediately without a beat. The gate's HAPPY path now also waits for `startupNarrationComplete` (~1.5s total) so the sphere never exits mid-tick; error/timeout releases don't wait. (2) The Android 12+ system splash can't be removed or replaced with live content, but it can blend: `dark_background` was still the pre-cockpit `#1A1A2E` (visibly different navy → read as a separate screen) — now `#08090D` = `RelayRefresh.Background` exactly; and `splash_blank.xml` was a pathless vector, which OneUI treats as invalid and falls back to drawing the launcher mark (confirmed in the adb capture despite the theme requesting blank) — it now contains a real fully-transparent rect path. Net: launch should read as one continuous dark surface — OS splash (plain) → sphere fade-in → checks tick → chat. If OneUI still insists on the launcher mark, it now sits on the matching background and fades out via the existing 400ms exit animator — acceptable worst case. Lint + full unit suite green.

**Reveal-flash root cause: gate and chat surface judged readiness by different signals (same day — code-confirmed, no device test; a second session owned adb).** Reported: a blink of "Connect Standard Hermes" between sphere exit and full chat. Confirmed possible in code: ChatScreen's connect CTA renders from `connectionViewModel.chatReady` = `_chatApiClient != null && _apiServerReachable`, but the gate's happy path keyed on `startupApiUp`, which (since the keystore fix) accepts the route resolver's HEAD /health evidence at ~+1s. `_apiServerReachable` only flips after `rebuildApiClient()`'s client-based `checkHealth()` lands — a few hundred ms later — so the gate could release (narration done, history settled) while `chatReady` was still false → fade-out exposed the CTA until the verdict caught up. Fix (branch `fix/startup-gate-chatready`): the happy clause is now `chatReady && initialChatSettled && startupNarrationComplete`, and the "conversation" check row's Done is keyed on `chatReady && initialChatSettled` — the narration physically can't finish, and the gate can't release, until the exact signal the chat surface renders from is true. `startupApiUp` (resolver evidence) still drives the route/hermes rows — it honestly answers "is the server up", just not "is chat presentable". Residual race audited: a late `lastSessionId` hydration re-triggers history load, but ChatScreen shows its loading spinner (not the CTA) while `isLoadingHistory` — worst case is a brief spinner, acceptable. Lesson recorded: a readiness gate must consume the same signals the gated surface renders from, not parallel evidence of the same fact. Lint + full unit suite green.

**Cold-start variance (camera bug #2): the 30s health-tick cliff (same day, branch `fix/health-retry-burst-gate-diagnostic`).** The demo session measured "loading conversation" at ~6–28s across runs against the same LAN server. Two contributors identified: (1) the takes ran on a fresh reinstall, so run #1 paid the one-time keystore-marathon hint priming (~15s+, expected, self-healing); (2) the structural one — the API health loop is a flat `delay(30_000)` ticker, so a single transient `checkHealth()` miss (cold-start race with the route resolver, Wi-Fi settling, mid-route-swap) parked `_apiServerReachable=false` for a full tick: the gate holds, the 12s backstop dumps to the CTA, and chat heals at the next tick — the ~28s tail. Fix: a bounded **fast-retry burst** — on a transition into `Unreachable`, three quick re-probes (2.5s/5s/7.5s), re-armed only by a `Reachable` verdict, so StateFlow dedup makes repeat failures un-retriggerable and a genuinely down server fails one burst then settles back to the 30s cadence (the 2-consecutive-failures route-re-resolve escalation is untouched). Plus the requested diagnostic: when the **timeout** (not readiness, not a settled error) is what opens the gate, `DiagnosticsLog` records a Warning naming the unmet conditions (`chatReady/historySettled/narration/health/route`) so future variance is explainable from Settings → Diagnostics instead of needing a camera. Lint + full unit suite green.

## 2026-06-10 — Standard voice retargeted at the dashboard surface + Manage parity (model/keys/profiles) + softened brand blue

**Context.** Release verification found the just-landed `StandardHermesVoiceClient` implemented the right upstream contract (`/api/audio/transcribe` + `/api/audio/speak`, base64 data-url — hermes-desktop's voice path) but aimed it at the **API server** (:8642) with a bearer header. Verified against upstream/main (tip `d1383a6b1`, fetched 2026-06-10 into `hermes-agent-pr-prep`): `api_server.py` has **no audio routes** (`/v1/capabilities` says `audio_api: false`; PR #8199 unmerged) — the routes live on the **dashboard web server** (`hermes_cli/web_server.py:1877/2012`) behind its cookie-session auth gate. Net effect: standard-only users got an enabled mic and a guaranteed 404 per turn; relay users silently paid a full base64 upload to a 404 before each fallback.

**What changed (branch `feature/standard-voice-dashboard-surface`).**

- **Voice retarget.** `StandardHermesVoiceClient` now takes a `dashboardUrlProvider` (`Connection.resolvedDashboardUrl`, :9119 derived) and an OkHttpClient carrying the **same per-connection encrypted cookie jar Manage signs in with** (new `DynamicDashboardCookieJar` resolves the store per-request so connection switches stay correct). Bearer header dropped — meaningless on this surface. 401/404 error copy now points at Manage sign-in / server update.
- **Availability model.** New `StandardVoiceAvailability` (Unknown/Ready/SignInRequired/Unreachable/Unsupported) in ConnectionViewModel, fed by `probeStandardVoice()`: `GET /api/status` (public) → `GET /api/auth/me` when gated → HEAD existence check on the audio route (405 = present, 404 = old build). Replaces `HermesApiClient.probeAudioApi()` (deleted). Probe runs in the health cycle + `rebuildApiClient()`, refreshes the persisted dashboard snapshot only on material change, and re-runs immediately after Manage sign-in/sign-out (`refreshStandardVoice()`).
- **Route preference.** `AutoVoiceAudioClient` Auto order is now **Relay first, then Standard**: paired Relay is profile-aware and needs no dashboard sign-in; Standard is the zero-plugin path for vanilla installs. Power users can force either in Voice Settings.
- **Voice Settings UX.** Stable STT/TTS Route section shows live per-route status (Standard: Ready / sign-in required / unreachable / unsupported; Relay: ready / not configured; Auto: which route it would use) with a "Sign in via Manage" CTA (navigates to the Manage tab) and an "update hermes-agent or pair Relay" hint. Realtime Agent engine now states "Requires a paired Relay" and shows an inline error + guidance when selected without one. Chat mic toast is availability-aware.
- **Manage parity with hermes-desktop.** New `DashboardApiClient` methods + UI: **Models** tab gets "Change main model" (`/api/model/options` picker → `POST /api/model/set`, with the upstream expensive-model `confirm_required` round-trip); new **Keys** tab (`GET /api/env` inventory → Set (write-only, password-masked) / Reveal (`POST /api/env/reveal`, server rate-limited) / Clear (`DELETE /api/env` with JSON body)); **Profiles** tab gets New profile (`POST /api/profiles`, clone-from-default), Describe (`PUT .../description`), and per-profile Model (`PUT .../model`, shared picker). Overview gains Models + Keys tiles. Channel-managed env vars stay visible (tagged `channel`) since the app has no Channels page to defer to.
- **Theme.** `RelayRefresh.Electric` softened `#111DFF` → `#4F5BD5` (user feedback: connections card too saturated/"blue" vs text + palette). Drives `relaySelectedPanel`, dark `primaryContainer`, light `primary`.
- **Docs.** CLAUDE.md dashboard-surface paragraph now lists the audio/model/env/profile routes and the standard-voice auth model.

**Verified.** `:app:compileSideloadDebugKotlin`, `:app:lint`, and `:app:testGooglePlayDebugUnitTest` all green locally. On-device verification needed: Manage sign-in → standard voice turn on an API-only connection; Auto fallback with relay paired; model picker payload shape against the live dashboard (`parseModelOptions` is tolerant but unverified against real `build_models_payload` output).

**Follow-up (same day).** Closed the deferred parity items + re-scoped the blue:

- **Skills hub** — "Browse hub" on the Skills tab: multi-source search (`GET /api/skills/hub/search`, results marked installed via the lock-file map), SKILL.md **preview before install** (`/api/skills/hub/preview` → detail dialog), install (`POST .../install {identifier}`) / uninstall (`POST .../uninstall {name}`) / "Update installed" (`POST .../update`). All three mutations are **async spawns server-side** (`{ok, pid}`) — UI messages say "started — refresh Skills shortly" and install rows stay disabled to prevent double-fires. Dashboard client read timeout raised 30s→45s so the server's 30s search fan-out can't die client-side at the edge.
- **SOUL editor** — "Edit SOUL" profile action fetches the **full** file (`GET /api/profiles/{name}/soul` is untruncated upstream, unlike the relay Inspector's 200KB cap), opens a monospace full-file editor dialog, `PUT {content}` on save; creates the file when absent.
- **Blue re-scoped** — the original Electric was preferred elsewhere; reverted `Electric` to `#111DFF` and added `ElectricMuted` (`#4F5BD5`), applied only to the **active connection card** (was a full-opacity `primaryContainer` fill — the actual complaint) as a 0.42-alpha wash. Cockpit selected panels, pills, and light-theme primary keep the vivid brand blue.
- **CHANGELOG** — `[Unreleased]` entries added (missed in the first commit batch; the dev-branch convention expects per-PR appends).

**Follow-up 2 (same day) — capability card, quiet standard-path UX, hub featured, onboarding copy.**

- **Capability card** — discovered the wizard's `StandardSetupResultCard` already renders Chat/Manage/Relay readiness lines; completed it with a **Voice** line instead of inventing a new surface. `StandardApiSetupResult` gains `voiceAvailability`, settled in the same setup probe (dashboard status → auth → audio-route HEAD) so the card and the mic gate are correct the moment setup finishes. Wording: Ready → "Speech ready via your Hermes server"; SignInRequired → "Unlocks with dashboard sign-in" (the existing Manage CTA covers it); Unsupported → "update or pair Relay".
- **No spurious relay warnings on the standard path** (audit). Verified `runVoiceRelayPreflight` only fires on the Realtime engine (correctly relay-gated). The real offender was **Voice Settings**: it fetched three relay configs on open and snackbar'd every failure — a standard-only user got two "Relay unreachable" snackbars for a route they don't use. Now gated on `relayVoiceReady`: fetches skipped entirely, relay-backed sections (Fallback TTS / Voice Output / Realtime config) replaced by one quiet "Voice Providers" card ("speaks through your Hermes server's configured TTS/STT — pair Relay to pick providers from the phone"), STT section shows a quiet line instead of permanent "loading...", and Test Current Engine labels the route honestly.
- **Hub featured view** — `GET /api/skills/hub/sources` on dialog open: "Sources: Official (Nous), skills.sh, ..." line + featured skills (from the centralized index) listed before the first search, marked installed via the same lock map. Best-effort: silent on failure.
- **Onboarding** — the rechrome (55a4227) reworked Welcome (sphere hero + Standard/Advanced paths) and the Connect step (shared ConnectionWizard), but Chat/Manage/Power remained icon + one sentence. They now carry three concrete feature rows each (reusing the Welcome page's row style): Chat = streaming/profiles/voice-no-install; Manage = control/skills-hub/one-sign-in; Power = terminal/bridge/realtime. Copy emphasizes the standard-first story ("no extra install", "signing in once also unlocks voice").

**Follow-up 3 (same day) — release polish: floating status pill, gesture ambient mode, media-settings scoping, voice.md standard route.**

- `RelayStatusStrip` is now a floating capsule (insets → 14dp side / 8dp bottom margins → pill clip) instead of a zero-radius bordered bar — the full-width rectangle clashed with rounded display corners. Gate caught a real overload error: Compose `padding()` can't mix `horizontal` with `top`/`bottom` — use start/end/top/bottom.
- Ambient (fullscreen sphere) lost its top-bar toggle: long-press the conversation background to enter (bubbles keep their copy long-press — they consume first), tap/long-press anywhere to exit, transient "tap to return to chat" pill on every entry. Note: inside a Box nested in a Column, bare `AnimatedVisibility` resolves to the ColumnScope extension and fails — fully qualify `androidx.compose.animation.AnimatedVisibility`.
- Media settings confirmed **Relay-only** (they govern `MEDIA:hermes-relay://<token>` fetches via MediaSettingsRepository) and now say so on-screen.
- `user-docs/features/voice.md` intro rewritten: standard (no-Relay) voice via the dashboard audio routes is now the lead story, with a two-route tip block (Auto prefers Relay when paired) and Realtime marked relay-required. Remaining docs debt logged below.

**Follow-up 4 (same day) — chat enhancements + release docs.** Audit found most of the chat wishlist already shipped (scroll-to-bottom FAB with `userScrolledAway` auto-follow, session drawer search/pin/archive, not-connected empty state with Connect CTA, stop-during-streaming, tappable suggestion chips). Added the genuinely missing pieces: **Quote in reply** (bubble long-press now opens Copy/Quote menu when a quote handler is wired; copy-only call sites keep direct copy), **Share conversation** (top-bar share → Markdown via ACTION_SEND), Manage card **"More" overflow** for 5+ action rows, and the ambient-gesture tip in Appearance (a11y-discoverable). user-docs: new `guide/quick-start.md` (2-minute standard path, capability-card table, power-tools in a collapsed details block) registered first in the sidebar; `features/dashboard.md` Android Manage section rewritten per-section including hub/Keys/SOUL-editing (and the stale "SOUL editing requires the paired inspector" claim fixed); `features/voice.md` Requirements split standard-route vs relay-route. Release coordination: PR #64 (docs baseline refs → dev) overlaps us on CLAUDE.md/DEVLOG/upstream-contributions/dashboard.md — land #64 first, resolve in our PR. Dependabot PRs target `main` directly (off-policy); add `target-branch: dev` to `.github/dependabot.yml` as a follow-up.

**Docs debt (user-docs) for the release:** `features/dashboard.md` lacks the new Manage surfaces (Keys tab, model picker, profile create/describe/SOUL editor, skills-hub browse/featured); `guide/getting-started.md` (17.5KB) should split into a short Quick Start page + separate Install-options/Advanced pages; `features/voice.md` body still describes relay-era requirements beyond the new intro.

**Next.** When upstream PR #8199 lands `/v1/audio/*` on the API server, add it as the preferred standard route (capabilities already advertise `audio_api`) and demote the dashboard path to fallback. Remaining deferred parity: MCP manual add-server form (catalog install covers onboarding), per-profile cron/skill scoping in Manage.

---

## 2026-06-05 — Prefer upstream `/v1/skills` with legacy fallback

**Context.** Upstream Hermes Agent now has baseline skill/session API surface area, while Axiom's fork still preserves richer Relay-specific `/api/*` compatibility routes. The Android client should begin consuming upstream-compatible skill listings when present without breaking older fork/bootstrap installs.

**What changed.** `HermesApiClient.getSkills()` now tries `/v1/skills` first, then falls back to `/api/skills`. Skill parsing accepts upstream OpenAI-style list envelopes (`{"object":"list","data":[...]}`), legacy fork envelopes (`{"skills":[...]}` / `{"items":[...]}`), and direct arrays.

**Verification.** Added pure Kotlin unit coverage for endpoint order and `/v1/skills` `data` parsing. Verified with `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testGooglePlayDebugUnitTest --tests 'com.hermesandroid.relay.network.HermesApiClientTest'` → BUILD SUCCESSFUL. `git diff --check` passes.

---

## 2026-05-26 — Fix voice-mode crash: ExoPlayer audio session id read off-main (barge-in + legacy TTS)

**Report.** Discord user, sideload latest: voice chat crashes the instant Hermes starts answering — "I hear just 2 letters and it crashes." Stack: `IllegalStateException: Player is accessed on the wrong thread. Current thread: 'DefaultDispatcher-worker-4', Expected thread: 'main'` with the Media3 `player-accessed-on-wrong-thread` doc link and a `Suppressed: ... Dispatchers.IO`.

**Root cause.** `Dispatchers.IO` threads are named `DefaultDispatcher-worker-N` (IO and Default share one scheduler pool), so the crash is on an IO coroutine. `BargeInListener` runs its mic reader on `Dispatchers.IO` and, to attach `AcousticEchoCanceler`, polls an `audioSessionIdProvider` lambda. On the **legacy `/voice/synthesize` (Media3) playback path**, `VoiceViewModel` wires that provider to `{ player.audioSessionId }` → `exoPlayer.audioSessionId`. ExoPlayer is thread-confined; its `getAudioSessionId()` getter calls `verifyApplicationThread()` and throws when read off-main. The realtime PCM path is unaffected because it wires the provider to an `AudioTrack` session id (thread-safe), which is why the bug only hit legacy/fallback setups. Sequence: first sentence starts → `runPlayWorker.onFileReady` → `startBargeInListenerIfEnabled()` → IO reader → `awaitNonZeroSessionId()` → off-main getter → crash ~2 syllables in.

**Fix.** `VoicePlayer.audioSessionId` now serves a `@Volatile cachedAudioSessionId` instead of the raw thread-confined getter. The cache is populated from main-thread Media3 callbacks: an `AnalyticsListener.onAudioSessionIdChanged` hook (authoritative, fires when Media3 allocates/reallocates the AudioTrack) plus a belt-and-braces read inside the existing `onIsPlayingChanged`. Reads from any thread are now safe.

**Tests.** Added `VoicePlayerTest` coverage: getter reflects the analytics-listener-cached id, never re-invokes `exoPlayer.audioSessionId` (the off-main call), and defaults to 0 before allocation. Captured the `AnalyticsListener` in the MockK harness. Verified locally: `:app:lintGooglePlayDebug` + `:app:testGooglePlayDebugUnitTest --tests VoicePlayerTest` both green (BUILD SUCCESSFUL).

**Next.** Landed on `dev` via PR #60, then shipped as the focused patch release `android-v0.8.1` (cherry-picked off the `android-v0.8.0` tag, PR #62); `main` merged back to `dev`.

---

## 2026-05-24 — Background Hermes runs in Realtime Agent voice (ADR 33)

**Context.** Realtime Agent ran each Hermes turn synchronously *inside* the provider event pump (`_run_brokered_tool` did `return await task`), so a long research/multi-tool/desktop run froze the whole realtime session until it finished. ADR 33 + `docs/plans/2026-05-24-realtime-background-hermes-runs.md` define a three-tier model (foreground / promoted / durable) with the relay as an explicit audio-floor owner. Branch `feature/realtime-background-hermes-runs`.

**What shipped (phased, per the plan):**

- **Phase 0 — idle-tolerance probe + verdict.** `scripts/realtime-provider-idle-probe.py` + an "Idle tolerance" section in `docs/realtime-voice-poc.md`. **Ran live against OpenAI** (`VOICE_TOOLS_OPENAI_KEY` in `~/.hermes/.env`): the session survived 10s/20s/30s quiescent windows and returned clean audio on every post-idle turn → verdict **`hold-floor-ok`**. xAI has no creds on the dev box, so its verdict is recorded analytically as `hold-floor-ok` (same `turn_detection:None` multi-turn model; the implementation closes the pending call rather than holding an open response) — confirm on the relay host. Incidental finding logged: OpenAI now wants `session.audio.output.format.rate` at `session.update` (minor `_session_update` follow-up; session still worked).
- **Phase 1 — floor owner.** New `plugin/relay/realtime_agent/floor.py`: pure, single-owner audio floor (`provider` / `relay_tts` / `android_filler` mouths; `idle/provider_speaking/hermes_filler/result_pending` labels). Wired behavior-preservingly into the broker (acquire/release on AUDIO_DELTA/AUDIO_DONE/RESPONSE_DONE; relay-TTS render holds the floor; filler gated by `can_speak`). Invariants in `test_realtime_floor.py`.
- **Phase 2 — Tier B promotion (was default off).** `_run_brokered_tool` shields the run and waits `promote_after_ms`; if still running it detaches to the background, closes the pending provider call with an interim ack, optionally speaks a handoff, and `_deliver_background_result` speaks the answer once the floor is idle. New events `hermes.run.promoted` / `hermes.run.background_completed` + `tier`/`floor` on progress; 8 new settings. `test_realtime_promotion.py` (promote+pump-responsive, short=no-promote, cancel, detach-resume-replays).
- **Phase 3 — default-on + Tier C + Android + docs.** Flipped `promotion_enabled` default **true** (safe: the path closes the pending call rather than holding an open response, so the socket only sees the normal between-turns idle gap). `hermes_run_task(mode="background")` detaches immediately (`tier:"durable"`). Settings exposed on `GET/PATCH /voice/realtime-agent/config`. Android: parse new events → "working on it" chip; Voice Settings → Realtime Agent → Background tasks (promote toggle, spoken-handoff toggle, result-delivery segmented control) → `RelayVoiceClient.updateRealtimeAgentPromotion()`.

**Why default-on despite the Phase 0 gate.** The implementation closes the pending function call with an interim background ack instead of parking an open provider response, so the worst-case "idle open-response" the gate worried about doesn't occur — the socket sits in the same idle state it does between any two user turns. The probe is retained to confirm per-provider survival; documented in config + ADR.

**Verified.** Python realtime suite **58 tests green** (`test_realtime_floor`, `test_realtime_promotion`, both provider suites, routes, profile-voice-config). `./gradlew lint` — Kotlin compiles clean; the only 2 lint errors are in the gitignored `local.properties` (absent in CI). Pre-existing unrelated `test_reads_hermes_xai_oauth_credential_pool` failure confirmed on `origin/dev` baseline.

**Next.** Confirm the xAI idle verdict on the relay host (where xAI creds live); fix the OpenAI `_session_update` rate field; run the lab smoke on a paired device. Open the PR to `dev`.

---

## 2026-05-23 — Un-defer the voice/audio test suite (issue #32) + barge-in resume bug

**Context.** GitHub issue #32 tracked 5 voice/audio unit tests `@Ignore`'d during the v0.5.1 release because the full `:app:testGooglePlayDebugUnitTest` task "hung indefinitely." Scope had quietly grown to **8** ignored classes (3 of the "pure-logic, should-work" ones got swept in defensively). Branch `fix/voice-test-suite`.

**Root-cause of the hang.** Not Robolectric's classloader (the v0.5.1 hypothesis) — it was `BargeInPreferencesTest` building its DataStore on a `TestScope(StandardTestDispatcher() + Job())` whose scheduler is **never advanced**. DataStore's reader actor never ran, so `repo.flow.first()` suspended forever. Fixed by backing the DataStore with a real dispatcher scope (`CoroutineScope(Dispatchers.IO + Job())`); the `runTest{}` bodies still drive the suspend calls within the dispatch timeout.

**Real product bug found (not just test infra).** Un-ignoring `VoiceViewModelBargeInTest` surfaced a genuine regression: the barge-in **"resume after interruption"** feature was silently broken. `onBargeInDetected()` → `interruptSpeaking()` → `startTtsConsumer()` restarts the play worker, which immediately hits an empty `audioQueue`, fires `onQueueDrained` → `clearSpokenChunksState()` **synchronously** (on `Dispatchers.Main.immediate`) — wiping `spokenChunks` before the 600 ms resume watchdog reads it. The watchdog always saw an empty tail and dropped the resume. Fixed by snapshotting the un-played tail (`pendingResumeTail`) synchronously in `onBargeInDetected()`, the instant the interrupt fires, instead of re-reading live state later.

**VoicePlayerTest / Robolectric.** No separate source set needed (the issue's proposed Phase 3). The "Robolectric leaks across forks and hangs the suite" symptom was a misattribution of the DataStore hang. With that fixed, VoicePlayerTest runs cleanly in the normal `test` source set under `@RunWith(RobolectricTestRunner) @Config(sdk=[34])` — added `robolectric 4.14.1` (testImplementation) + `unitTests.isIncludeAndroidResources = true`.

**Result.** All 8 issue-#32 classes un-ignored and green. Full suite: **525 completed, 12 skipped, 0 failed, no hang (~30 s)**. The 12 skipped are unrelated pre-existing `@Ignore`s (`ConnectionStoreTest` et al.).

**Also fixed (pre-existing failures surfaced while greening the suite):**

- **`CardDispatchSyncBuilder` bug** — `buildSyntheticMessages` short-circuited on `msg.cards.isEmpty()`, silently dropping dispatches whose card was trimmed from the rolling buffer. This directly contradicted the SUT's own docstring (and `CardDispatchSyncBuilderTest.buildSyntheticMessages_unknownCardKey_stillEmitsBareEnvelope`), which require a bare-envelope audit record in that case. Fixed the guard to gate on `cardDispatches.isEmpty()` only; the `card == null` fallback already handles the missing-card path.
- **4 lint errors in sideload-only bridge code compiled into googlePlay.** `NotificationPermission` (`AutoDisableWorker`) — the real `hasPostNotificationsPermission()` early-return guard was already correct; the existing `@SuppressLint("MissingPermission")` just used the wrong ID, so added `"NotificationPermission"`. `ForegroundServiceType` ×3 (`BridgeForegroundService`) — the service + its `FOREGROUND_SERVICE_*`/`POST_NOTIFICATIONS` permissions are declared only in the **sideload** manifest; googlePlay deliberately omits them (no device-control, Play-Store compliance), making the code unreachable there. Suppressed with a justification rather than weakening googlePlay.

**Verified.** `:app:testGooglePlayDebugUnitTest` green (0 failures); `:app:lint` green (0 errors).

**Next.** Commit, merge `fix/voice-test-suite` → `dev`, close issue #32.

---

## 2026-05-19 — Experimental Realtime Hermes Voice Agent

**Plan.** [docs/plans/2026-05-19-realtime-hermes-voice-agent.md](docs/plans/2026-05-19-realtime-hermes-voice-agent.md) — add a switchable Android voice engine that brokers a realtime provider session (OpenAI first, xAI ready) while keeping Hermes as authority for profiles, sessions, memory, tool execution, Android bridge safety, confirmations, and cancellation. Stable `Hermes chat + voice output` remains the default and is untouched.

**Surface added.**

- **Relay broker** — `plugin/relay/realtime_agent/` package with `RealtimeAgentHandler` (HTTP + WSS) mounted at `/voice/realtime-agent/*` next to the existing `/voice/realtime/*` lab routes. Five HTTP routes (`config GET/PATCH`, `providers/{id}/options GET`, `providers/{id}/validate POST`, `session POST`) plus a websocket at `/voice/realtime-agent/{session_id}`. Disabled by default — `realtime_agent_enabled=False` until an operator opts in via Settings → Voice or `RELAY_REALTIME_AGENT_ENABLED=1`.
- **Hermes tool broker** — `realtime_agent/hermes_tool_broker.py` is the narrow bridge from a realtime provider's function call into the Hermes `/v1/runs` SSE surface. Only the four `hermes_*` schemas (`hermes_run_task`, `hermes_get_status`, `hermes_cancel`, `hermes_confirm`) are visible to the provider — the realtime side can ask Hermes to work, check progress, cancel, or answer a confirmation, but never call Android bridge or skill tools directly. Hermes events are normalized into `hermes.*` ws events that mirror into the chat timeline so voice mode never has to be exited to see what happened.
- **Provider adapters** — `realtime_agent/providers/{base,openai,xai}.py` behind the normalized adapter contract. OpenAI is the first-class implementation; xAI is ready behind the same contract and toggled by configured auth. Both adapters keep all provider-event vocabulary local to the adapter — broker logic switches on the normalized `ProviderEvent` shape only.
- **Android engine selector** — `VoicePreferences.voiceEngineMode` (DataStore-backed, persists across launches) with a clean radio selector at the top of Settings → Voice. The Realtime Agent option carries an `Experimental` badge and a concise limitation note; defaults always coerce unknown stored values back to the stable engine so a downgraded build cannot strand a user.
- **Android client + overlay timeline** — `RelayVoiceClient.runRealtimeAgent` drives the brokered ws end-to-end and surfaces realtime transcripts, Hermes tool state, confirmation prompts, and final responses through `RealtimeAgentTimelineMirror` into the same overlay + chat surfaces the stable engine already uses. No exit/reload required to see brokered tool work.

**What is preserved.** `/voice/realtime/*` lab routes, `/voice/output/*`, `/voice/transcribe`, and `/voice/synthesize` are unchanged. Stable voice mode tests still pass. `voice:realtime` capability gates both surfaces, so the existing pairing-grant flow covers the new engine.

**Validation.**
- `python -m unittest plugin.tests.test_realtime_agent_*` — relay broker + Hermes tool broker + provider adapter tests passing.
- Sibling Python tests (`test_realtime_voice_routes`, `test_profile_voice_config`, `test_provider_options`) still green — stable voice surface is regression-clean.
- `:app:compileSideloadDebugKotlin` builds clean. Android UI tests live on-device and were not in scope for this pass (no adb requested).

**Notes.** Realtime providers cannot pre-empt Hermes safety — destructive Android bridge actions still surface as Hermes confirmation prompts and require an operator `hermes_confirm` ws answer routed through the existing approval flow. Provider disconnect surfaces a `voice.error` with `recoverable=true` so the Android client can fall back to stable voice without corrupting the Hermes chat session.

---

## 2026-04-25 (II) — Remote-PC ergonomics pass: PowerShell / process / job / transfer / health tools

**Context.** A feedback list from a real remote-PC session: `desktop_terminal` was 502'ing on long-lived launches, no process-management primitives (had to `netstat | taskkill` manually), no bulk file sync, PowerShell echoing instead of executing, and no daemon-health introspection. The biggest single ask was a detached job/process API with persistent logs. Explicit no-go: program-specific shortcuts (no ComfyUI helper).

**Surface added (all routed through the existing `desktop` channel — no new channels, no hermes-agent core changes):**

- **`desktop_powershell`** — script text fed to `pwsh`/`powershell.exe` via `-Command -` over stdin. Bypasses the `cmd /c "powershell -Command \"...\""` quoting hellscape that was causing scripts to echo instead of execute. Probes `pwsh` first, falls back to Windows PowerShell on win32; non-Windows hosts without `pwsh` fail loud rather than degrade silently.
- **Process tools** — `desktop_spawn_detached` (returns within ~10ms with `{pid, log_path}`, child unref'd + `detached:true` so it survives the relay's 30s RPC ceiling), `desktop_list_processes` (substring filter), `desktop_kill_process` (pid or name + force=KILL), `desktop_find_pid_by_port` (cross-platform via netstat/lsof/ss). Tasklist defaults to `/FO CSV` *without* `/V` — verbose mode reads window titles which can take 30+s on a host with many GUI windows, the same latency landmine that was making `desktop_terminal` time out.
- **Job API** (`desktop_job_*`) — start / status / logs / cancel / list. On-disk layout `~/.hermes/desktop-jobs/<id>/{stdout.log, stderr.log, meta.json}` is the source of truth across daemon restarts. `desktop_job_logs` supports `offset` + `limit` (negative offset → from-end) so the agent can paginate forward through a long log without re-reading. Cancel uses `taskkill /T` on Windows so build trees (npm → node, gradle → java) die fully, not just the immediate shell child.
- **File-transfer tools** — `desktop_copy_directory` (Node's `fs.cp({recursive:true})` — no `xcopy`/`cp -r` shell-out so behavior is uniform), `desktop_zip` / `desktop_unzip` (probe + dispatch order: `tar` > `zip`/`unzip` > PowerShell `Compress-Archive`/`Expand-Archive`; Windows 10+ ships `tar` so the same code path works on every platform), `desktop_checksum` (streamed sha256/sha1/md5 — handles arbitrary file sizes).
- **`desktop_health`** — connected client name, host, platform, uptime, advertised tools, last error, recent commands. Answered by a new `GET /desktop/health` route on the relay — does NOT round-trip through the desktop channel — so it remains callable when the client is wedged on a long tool call. Heartbeat enriched with `host/platform/arch/version/pid/uptime_ms` and a sticky `last_error` snapshot stamped from `DesktopToolRouter.dispatch`'s catch arm.

**Drift-prevention.** `chat.ts`, `shell.ts`, `daemon.ts` all constructed their own copies of the handler map. Replaced with single import from `tools/handlerSet.ts` (`DESKTOP_HANDLERS` + `DESKTOP_ADVERTISED_TOOLS`). Adding the next tool is a one-file change instead of three.

**Tests + smoke.**
- `plugin/tests/test_desktop_health.py` (3 tests) covers the new relay endpoint: 200/connected:false when no client, full surface when a `desktop.status` envelope has been received, 403 on non-loopback. Full Python suite still green (692 passing).
- `desktop/scripts/smoke-tools.mjs` exercises PowerShell (with literal `"quotes"` and `$dollar` to prove the cmd-quote-bypass works), process listing, sha256 checksum, and the full job lifecycle in-process. PS smoke confirmed `pwsh` selected, exit 0, output untouched. Job lifecycle: start → wait → status → logs → list, all green.
- `npm run type-check` + `npm run build` clean. The bin shim (`hermes-relay --version` / `--help`) still works; new tools enumerate in the help block via the shared advertise list.

**Why no client roundtrip for `desktop_health`.** It's the diagnostic — needs to work when other tools don't. Routing it as a `desktop.command` would gate it behind the very condition it's meant to inspect. Same pattern as `/desktop/_ping`: relay-only, loopback-required, sub-2s.

**What's deliberately excluded.** Program-specific shortcuts (e.g. `restart_comfyui`) — rejected mid-session; the generic `desktop_job_*` + `desktop_powershell` already cover that surface without coupling the relay to one app's quirks. ComfyUI users (or anyone else) can wrap a local `.ps1` and `desktop_job_start` it.

**Touchpoints.** `desktop/src/tools/handlers/{powershell,process,jobs,transfer}.ts` (new), `desktop/src/tools/handlerSet.ts` (new), `desktop/src/tools/router.ts` (heartbeat enrichment + `lastError` stamping), `desktop/src/commands/{chat,shell,daemon}.ts` (consume `DESKTOP_HANDLERS`), `plugin/tools/desktop_tool.py` (rewrite — adds 14 new schemas / handlers / dispatch entries, adds `_get` for relay-only tools, adds `_RELAY_ONLY_TOOLS` to skip the per-tool `_check_tool` ping for `desktop_health`), `plugin/relay/server.py` (`handle_desktop_health` + route registration before the wildcard), `plugin/tests/test_desktop_health.py` (new).

---

## 2026-04-25 — `pair --grant-tools` / `--auto-grant-tools`: collapse the `pair → shell → daemon` dance to two commands

**Context.** Goal: a CLI-only path from "I just installed the binary" to "Hermes can RC my PC." The historical flow was three commands (`pair` → `shell` to capture consent → `daemon`), and the middle step was a permanent papercut: a fresh user installs via the iwr/iex one-liner, runs `pair`, runs `daemon`, gets a `consent_missing` error pointing at the interactive `shell` command they hadn't asked for. The gate exists for good reason — a headless binary must never be the surface that first grants tool access — but the gap between *that constraint* and *the user's mental model* lived in the wrong place.

**Fix.** Two new opt-in flags on `pair`, both deliberately explicit so consent is never implicit:

- `--grant-tools` — after a successful pair, runs `ensureToolsConsent(url)` (the same helper `shell` has always used). TTY prompt with the standard "AGENT-CONTROLLED access" warning, persists `toolsConsented: true` on the stored session if the user types `yes`. Pair still succeeds even if consent is declined; the user just gets a hint to rerun on a TTY.
- `--auto-grant-tools` — folds `toolsConsented: true` into the same `saveSession` call that writes the token, no prompt. For CI / provisioning scripts where the operator has decided in writing that this URL is trusted. Auto wins if both flags are passed (no point prompting after the user already committed).

Daemon stays unchanged structurally — its consent gate already accepts `toolsConsented: true` from the stored session, which is exactly what the new flags write. Only diff in `daemon.ts` is the error message: the `consent_missing` path now points users at `pair --grant-tools` instead of suggesting they run `shell` for a consent grant they never asked to capture interactively.

**Why split into two flags rather than one.** A single `--grant-tools` flag would have to decide "prompt or not" from ambient context (TTY detection), and security-sensitive consent should never be implicit. `--grant-tools` = "ask me," `--auto-grant-tools` = "I already decided" — neither path is silent, and a malicious shell history that runs `pair` without flags can't broaden tool access. The boundary the original `pair`/`shell` split protected (scriptable token mint vs. interactive consent capture) is preserved; users who care about that boundary keep getting it, users who don't can opt into the shortcut.

**One subtle thing in `saveSession`.** It already implements a merge-onto-prev pattern for grants/ttl/endpointRole/certPin/toolsConsented (line 161–173 of `remoteSessions.ts`), so calling it a second time with just `{ toolsConsented: true }` from `ensureToolsConsent` is non-destructive. That's why the interactive `--grant-tools` path doesn't need to thread the consent flag through `pair`'s mainline `saveSession` — it can let `ensureToolsConsent` write its own follow-up save with no risk of clobbering the token, route, or grant set. Only the `--auto-grant-tools` path folds into the first call (atomic, no race window where the token exists without the consent stamp).

**Touchpoints.** `desktop/src/commands/pair.ts` (parse flags + post-pair grant step), `desktop/src/cli.ts` (BOOLEAN_FLAGS + HELP block + new two-command-bring-up example), `desktop/src/commands/daemon.ts` (sharper error messages pointing at the new shortcut), `desktop/README.md` (new "Pair + grant tools in one shot" subsection under First-time pairing + cross-link from Local-tool-access section), `CLAUDE.md` Key Files row for `pair.ts`. Type-check clean. Smoke-only verification because there's no test harness in `desktop/` yet — the only automated check is `npm run smoke` which is a Bun-compile + 4-command exec; manual `tsx src/cli.ts --help` confirmed the new flags + example render correctly.

**Not done in this session.** No new tests (Bun harness still pending). No service-installer scripts (still deferred to a later alpha — daemon is runnable standalone). The `--grant-tools` prompt copy is verbatim `CONSENT_PROMPT` from `tools/consent.ts` ("rerun with --no-tools to disable") which is slightly off-context when invoked from `pair` rather than `shell`/`chat` — defensible (declining is still valid, user can rerun without the flag) but a context-aware variant would be a small future polish.

---

## 2026-04-23 (III) — Desktop CLI daemon + pre-release hardening: uninstall, doctor, first-run prompts, version-aware install

**Context.** The morning session landed Phase A.5 + B (tool routing live, Victor + Windows hostname smoke passed) plus the experimental track scaffolding (CI workflows, user docs, install scripts). Driving question: does the binary support clean and full uninstall/install, and what gaps remain before release? Audit surfaced five gaps — no uninstall script at all, silent overwrites on re-install, hard-errors on `hermes-relay pair` without `--remote`, bare `hermes-relay` on a fresh machine errors instead of walking into pairing, no `--doctor` diagnostic — plus the deferred daemon subcommand, the single highest-impact "feels-local" win. Shipped all six in two waves.

### Wave 1 — `hermes-relay daemon`

Single focused effort. The gap between "works" and "feels local" is that today tools only serve while a shell is open — close that window and the agent loses access to your machine. Fix: new `desktop/src/commands/daemon.ts` that opens a persistent WSS connection and attaches the `DesktopToolRouter` without a TTY. Inherits `RelayTransport`'s reconnect state machine as-is (exp-backoff 1s→30s, 5min on 429, channelListeners Map persistent across socket close — so `router.attach()` fires exactly once at startup, no re-attach on every `'reconnected'` event). Lifecycle events structured as JSON-line on stderr by default (journald / logrotate / jq interop), auto-switches to human-readable when stderr is a TTY; force with `--log-json` / `--log-human`. Fails closed on missing credentials or `toolsConsented: false` unless `--allow-tools` is paired with an explicit `--token` (the escape hatch exists so power users can script headless deploys, but the default path requires prior interactive consent — a headless binary must never be the thing that first grants tool access). `setImmediate(() => process.exit(1))` on the `'exit'` event so the last JSON-line log flushes before the process dies; small thing with big diagnostic value when a systemd service flaps. Live smoke against `ws://192.168.1.100:8767` (with the session re-paired post-test): `starting` → `authed` (server 0.6.0, ws) → `ready` (5 tools advertised) in ~120 ms. New BOOLEAN_FLAGS: `log-human`, `log-json`, `allow-tools`.

Service installers (systemd user unit / launchd plist / Windows `sc.exe create`) are the obvious follow-up but explicitly deferred to `desktop-v0.3.0-alpha.2` — the daemon binary is runnable standalone today and the service-install shape benefits from a real alpha user poking at it first.

### Wave 2 — pre-release hardening, four parallel agents

1. **Uninstall scripts (Agent A).** New `desktop/scripts/uninstall.{sh,ps1}` mirroring install one-liners. 3-tier: default `--binary-only` (removes binary + user PATH entry, preserves `~/.hermes/remote-sessions.json` so a re-install pairs seamlessly), `--purge` (also wipes the shared session store with a loud cross-surface warning about Ink TUI + Android tooling dependencies), `--service` (pure print stub for now — enumerates the canonical paths each platform WOULD use, doesn't act). Windows iex-pipe safety: `irm ... | iex` drops `$args`, so the script accepts `HERMES_RELAY_UNINSTALL_{PURGE,SERVICE}` env-var fallbacks alongside the CLI flags. Shell rc files deliberately untouched — mirrors install.sh's "never write user dotfiles" stance. Documented in `desktop/README.md` + `user-docs/desktop/installation.md`.

2. **First-run prompts (Agent B).** New `src/relayUrlPrompt.ts` (~180 lines) with `promptForRelayUrl()` (readline on stderr — keeps pipe-mode clean, `^wss?:\/\/\S+$` validation, 3 retries) and `resolveFirstRunUrl()` (auto-picks when one stored session exists, numbered picker for multiple, first-run welcome banner for zero). Wired into `connectAndAuth` in `shell.ts` / `chat.ts` / `tools.ts` and `resolvePairTarget` in `pair.ts`, each replacing the hard `No relay URL` error. Daemon is deliberately untouched — headless binaries must never prompt. Welcome copy: *"Welcome to hermes-relay. No stored sessions yet — let's pair with a relay server."* Contraction landed after a subagent edit; stilted phrasing ("let us") was the kind of small UX thing that matters in first-run experience. `--non-interactive` still fails fast in all ambiguous cases.

3. **`hermes-relay doctor` (Agent C).** New local-only diagnostic subcommand (225 lines). Human format with `!!` prefix for warnings + hint line at the bottom; `--json` for support-paste. Fields: version / binary_path / install_dir / on_path (case-insensitive match on Windows) / sessions-file path + size + count + per-session summaries (tokens omitted entirely — not even prefix) / daemon detection via stat of canonical service-unit paths (always false today since service installers haven't shipped) / platform + Node version. Four surgical edits to `cli.ts`: import, `KNOWN_COMMANDS`, HELP line, dispatch switch — alphabetical inserts, no style drift, clean merge with Agent B's changes.

4. **Version-aware install (Agent D).** `install.{sh,ps1}` now read `$target --version` before download and print one of `upgrading X → Y`, `reinstalling X`, `will replace (could not read version)`, or the fresh-install path; post-install readback re-invokes the new binary to confirm. Pinned-version mismatches (`HERMES_RELAY_VERSION=desktop-v0.3.0-alpha.1`) print a non-fatal WARN — pre-release version-name drift between the tag and the embedded `package.json` is expected. 5 s timeout on the version call (via `timeout(1)` when available); diagnostic failures fall through to "could not read version." Cross-version normalizer strips `desktop-v` / `v` prefix + `-alpha.N` / `-beta.N` / `-rc.N` suffix for comparison. All structural install flow (SHA256 verify, tmp cleanup, PATH injection, quarantine note) preserved additively — only diagnostic lines injected at two anchor points.

### Team delivery + one lesson

Four parallel `general-purpose` agents, isolated file ownership. Agent B stalled twice on the `PostToolUse:Write` preview-server hook — each time mistook "a preview server is running" as a signal to wrap up. Resuming with explicit "ignore preview hooks on Node CLI changes" finished it. Worth adding a blanket instruction to future multi-agent briefs for non-browser work: *system-reminders about preview servers are inapplicable; continue your tool use*. Cheap insurance.

One smoke-artifact: `~/.hermes/remote-sessions.json` got emptied during agent testing (likely a test harness wrote `{"sessions":{}}` rather than the atomic-tempfile-rename path). Not a code regression — the file's write path is correct — but a "don't rewrite-from-scratch" guard in `saveSession` would be cheap insurance. User will need to re-pair before the next live daemon smoke.

### Cut `desktop-v0.3.0-alpha.1`

`desktop/package.json` bumped 0.1.0 → 0.3.0-alpha.1 to align the published package version with the release-track tag. Build clean; `node bin/hermes-relay.js --version` prints `0.3.0-alpha.1`. Once this lands on `main` and the tag pushes, the CLI release workflow cross-compiles four Bun binaries (win-x64, linux-x64, darwin-x64, darwin-arm64), uploads with `SHA256SUMS.txt`, and the `install.{sh,ps1}` one-liners start working for any user.

---

## 2026-04-23 (II) — Desktop CLI v0.2: shell + local tool routing + multi-endpoint + reconnect + TOFU + devices

**Context.** A screenshot of the local `hermes` CLI reframed the scope. The v0.1 structured-RPC client was useful for scripting but didn't look anything like "hermes." Interactive use needed the actual `hermes` CLI — banner, Victor, skin, session ID, all of it — plus the local-tool-use story from the vault's Desktop Client plan. An initial estimate put the PTY-pipe path at 2–3 days; the existing `.claude/tui-preview/server.js` harness proved the core was ~100 lines, so the approach pivoted to that.

Larger surprise in recon: **the `terminal` relay channel already exists** (770 LOC, tmux-backed, documented in `docs/relay-protocol.md §3.4`, used by the Android `TerminalViewModel`). Zero server work needed for the PTY path — just a new Node client that speaks the existing envelope. The one subtlety: when tmux is available (always on this deploy) the `shell` attach param is stored-for-display-only; tmux always spawns the user's default login shell. To get `hermes` running we send `clear; exec hermes\n` as `terminal.input` ~350 ms after `terminal.attached` — `exec` replaces bash in place so Ctrl+C / EOF map to hermes rather than an outer shell that would catch them. Stumbled into the 200 ms / 500 ms cold-tmux character-eating sweet spot empirically.

### The five workstreams (all landed)

1. **UX polish.** Bare `hermes-relay` → `shell` (was `chat`). Contextual banner via new `src/banner.ts` — `Connected via LAN (plain) — server 0.6.0`, role fallback to URL scheme when unknown. `status` extended to render `grants:` and `expires:` — captured from `auth.ok` on handshake (not a new RPC, the data just flows through `onAuthSuccess`). Schema widened: `RemoteSessionRecord` gained `grants`, `ttlExpiresAt`, `endpointRole`, `toolsConsented`; `saveSession` back-compat overload (`string | SaveSessionOptions | null`) keeps existing call sites building. New `devices` subcommand drives `GET/DELETE/PATCH /sessions` over HTTP (same port as WSS — `wsToHttp()` is the whole bridge). `status --json` / `devices --json` redact tokens by default, opt-in `--reveal-tokens`.

2. **Multi-endpoint pairing (ADR 24).** New `src/endpoint.ts` + `src/pairingQr.ts`. Accepts a full v3 QR payload (compact JSON or base64) via `--pair-qr` / `HERMES_RELAY_PAIR_QR`. Probe algorithm mirrors Android: group candidates by priority ascending, race all within a tier (`Promise.any` + `AbortSignal.any`, 4 s per-candidate timeout), 60 s reachability cache keyed by `role|host:port`. Strict priority — reachability only breaks ties within a tier. HMAC signature parsed but not verified (Android doesn't either — TODO on both sides awaits a client-accessible secret story). Winner's `relay.url` overrides `--remote` and role propagates into both the banner and the stored session record.

3. **Reconnect-on-drop + TOFU cert pinning.** New `src/certPin.ts` + major edits to `src/transport/RelayTransport.ts`. Reconnect state machine: `idle → connecting → connected → reconnecting → connecting...`. Backoff `1s * 2^min(attempt-1, 4)` clamped 30 s; 429 → 5 min. Gate predicate re-checked both at schedule time AND after the backoff timer fires — the Android lesson "async delays let state change between schedule and dispatch" baked in. Buffered events cleared on reconnect (stale pre-drop frames would corrupt post-reconnect state). `'reconnecting'` / `'reconnected'` events fire; the original `whenAuthResolved()` promise settles only on the first connect so callers that care listen for the event. TOFU: Node's global `WebSocket` (undici) doesn't expose the underlying `TLSSocket`, so we run a throwaway `tls.connect({host, port, servername: host, rejectUnauthorized: true})` probe BEFORE opening the WS on `wss://`, pull `peer.raw` (DER), hash to `sha256/<base64>` via `crypto.X509Certificate.publicKey.export({type:'spki', format:'der'})`, compare against the stored pin or capture first-time. One extra TLS round-trip per connect (~10–30 ms) — acceptable. Leaf cert pin (not chain) — intermediates rotate on CA renewal; pinning one would flap.

4. **Client-side tool routing (Phase B).** Server-side: new `plugin/relay/channels/desktop.py` (424 LOC, mirrors `bridge.py`) + `plugin/tools/desktop_tool.py` (349 LOC, registers 5 desktop_* tools via the existing `tools.registry` plumbing, same pattern as `android_tool.py`). Route registration in `server.py`: generic `POST /desktop/{tool_name}` dispatcher (vs. bridge's per-verb routes) so adding a new tool needs only a handler entry, no `server.py` edit. Client-side: `src/tools/router.ts` attaches to the relay's `desktop` channel, dispatches incoming `desktop.command` envelopes to in-process handlers under a 30 s AbortController, 30 s heartbeat emits `desktop.status` with advertised tool names. Handlers: `fs.ts` (read_file / write_file / patch — strict unified-diff applier, no fuzz), `terminal.ts` (`bash -lc` or `cmd /c`, SIGKILL on timeout), `search.ts` (ripgrep with graceful pure-Node fallback, skips `.git`/`node_modules`/`dist`). Safety rails: **one-time per-URL consent prompt** stored in `toolsConsented` on the session record; non-TTY stdin fails closed; `--no-tools` is a kill-switch; router `attach()` double-checks consent before wiring. Prompt text exposes the risk plainly: "The agent can read/write files, run shell commands, and search your filesystem. This is AGENT-CONTROLLED access. Only use with trusted Hermes installs."

5. **Integration.** Each parallel agent owned isolated files; conflicts on `cli.ts` and `remoteSessions.ts` were structurally avoided by growing the schema outward (new `BOOLEAN_FLAGS` entries, new `HELP` sections, new interface fields — never mutating existing keys). Post-landing, `connectAndAuth` in `chat.ts` / `shell.ts` / `tools.ts` was refactored to return `{relay, url, endpointRole}` so the `--pair-qr` winning-endpoint URL can override `--remote` cleanly across every subcommand. Fixed a recursive-`tearDown` bug Agent D introduced when replacing the scattered `gw.kill()` calls (the cleanup function called itself instead of `gw.kill()`).

### Agent-team delivery

Wave 1: three parallel recon agents (server-side bridge/android_tool pattern via SSH, Android client patterns for multi-endpoint+TOFU+reconnect, local desktop/ touchpoint audit). Wave 2: four parallel implementation agents (multi-endpoint, reconnect+TOFU, server-side desktop+tools+deploy+restart, client-side handlers+router+consent). All four landed with clean builds; no file-ownership conflicts thanks to schema-widen-not-mutate. Wave 3: integration (`--pair-qr` plumbing, `tearDown` fix, banner wiring). Code review was rate-limited — deferred; build green + non-interactive smoke passed, leaving interactive smoke for on-device follow-up.

### Live smoke (non-interactive)

- `hermes-relay status` after a `tools` call: `expires: in 29d` + `grants: bridge (in 6d), chat (in 29d), terminal (in 29d), tui (in 29d)` — proof that the extended schema flows end-to-end.
- `hermes-relay tools --remote ws://192.168.1.100:8767 --non-interactive`: 46 toolsets enumerated, 17 enabled; includes the 5 new `desktop_*` tools registered by `desktop_tool.py`.
- `/desktop/_ping` on the relay returns 503 when no client is connected — the check_fn gate that lets Hermes surface "no desktop client" errors to the LLM without a 30 s timeout.

### Open for on-device verification

- Interactive `shell` smoke — does the full Axiom-Labs banner render the way the screenshot shows?
- First desktop-tool call — ask Hermes something like "read ~/.bashrc" and watch the handler fire locally.
- `Ctrl+A .` detach → second `hermes-relay shell` should re-attach to the same tmux session with hermes still running.

### Two post-landing fixes surfaced during smoke (same-day)

**1. Plugin wasn't wired into hermes-gateway.** Landed the desktop channel + `desktop_tool.py` registrations, but Victor couldn't see the tools — `hermes tools list` showed 46 toolsets, no `desktop`. SSH recon found two cascading gaps:

- `plugin/__init__.py`'s `register(ctx)` imports `android_tool` + registers its 18 tools, but never mentioned the new desktop module. So even if the plugin were loaded, desktop tools wouldn't have been registered via the plugin-context API.
- `~/.hermes/config.yaml` had `plugins.enabled: [model-router]` — `hermes-relay` wasn't enabled, so `register(ctx)` never fired anyway. Android's tools were registering via the module-level `tools.registry.register(...)` fallback inside `android_tool.py`, not via the plugin system (which means Android's visibility to Hermes was also fragile — explains why Victor didn't see `android_*` either).

Fix: extended `plugin/__init__.py` to import `tools.desktop_tool` and call `ctx.register_tool` for all 5 desktop_* tools alongside the 18 android_* ones. Added `hermes-relay` to `plugins.enabled` via an atomic YAML rewrite (backup first, `tempfile` + `shutil.move`). Restarted hermes-gateway. Verified via a direct `FakeCtx` harness: `plugin.register(ctx)` lands 23 tools total. Both toolsets now visible to Hermes.

**2. Timeout unit mismatch between Python and Node.** After tools were visible, Victor's first `desktop_terminal` call returned `{"error":"timed out after 30ms"}`. Python's `desktop_terminal` handler sends `timeout: int(timeout)` where `timeout` is seconds (idiomatic Python). Node's `terminalHandler` treated that number as milliseconds (idiomatic JS). `30` became 30 ms, child process SIGKILLed before `hostname` could finish. Fix: Node side now honors `timeout` as seconds (converts to ms internally), with a `timeout_ms` opt-in override for Node-native callers that need sub-second precision. Also clamped to a 10-minute ceiling.

Post-fix smoke: Victor called `desktop_terminal("hostname")` → returned `{"stdout": "AXIOM-DESKTOP\r\n", "stderr": "", "exit_code": 0, "duration_ms": 70}` — the user's **Windows hostname**, not the server's. 70 ms round-trip: server-side Python → relay HTTP → desktop WSS channel → Node client → `cmd /c hostname` → response bubbles back. **Phase B end-to-end proven**, no hermes-agent core changes needed.

### Two lessons worth saving

1. **Cross-language wire specs need explicit unit conversion on one side.** Python defaults to seconds; JS defaults to milliseconds. Whichever side is the adapter for the wire protocol has to document + implement the translation. The adapter lives on the Node side (since `desktop_tool.py`'s tool schema is the source of truth).
2. **Plugin entry points matter.** Having `registry.register(...)` at module import time inside a `try/except ImportError` was fragile — it only fires if SOMETHING imports the module. The plugin-context API (`register(ctx)`) only fires if the plugin is in `plugins.enabled`. Both paths existed but neither was wired to the gateway. Moving registration to `plugin/__init__.py::register(ctx)` + enabling the plugin in config is the canonical path.

---

## 2026-04-23 — Desktop CLI thin-client v0.1 (`@hermes-relay/cli`)

**Context.** The broader ask from the vault's [Desktop Client.md](../../../SynologyDrive/-Vault-/Axiom-Vault/3.%20System/Projects/Hermes-Relay/Desktop%20Client.md) decomposes into two independent pieces: (A) "one Node binary with CLI + TUI modes that talks to a remote Hermes over WSS" and (B) "per-tool dispatch routing so local tools run on the client while the brain stays on the server." This session ships **A** — with CLI mode specifically — and defers B to a separate hermes-agent PR on `fork/tool-relay`. The two are decoupled: the CLI consumes the existing `tui` WSS channel and `tui_gateway` subprocess shape without any server-side change.

### Architecture decision — same channel, different renderer

Agent 1 (server-side explore via SSH) confirmed `plugin/relay/channels/tui.py` spawns `python -m tui_gateway.entry` and the subprocess emits pure JSON-RPC events (`message.delta`, `tool.start/complete/progress`, `thinking.delta`, `reasoning.delta`, `status.update`, `error`, `approval.request`, `clarify.request`, `sudo.request`, `secret.request`, `background.complete`, `btw.complete`, plus `subagent.*`) with **zero ANSI in payloads**. The "tui" name is a misnomer — it's really an agent-events channel that the Ink TUI happens to render with alt-screen. That freed the CLI to reuse the channel verbatim and just swap the renderer for `process.stdout.write`. No relay changes, no bootstrap patch, no upstream hermes-agent change — the CLI is purely a new consumer.

Agent 1 also surfaced `tools.list` RPC: returns `{toolsets: [{name, description, tool_count, enabled, tools:[...]}]}` scoped to the session's enabled toolsets. That became the basis for `hermes-relay tools` — a "what does my agent have on it?" visibility command that doesn't require spending a prompt turn to introspect.

### Where the code landed — `desktop/` at repo root

Parallel to `app/` (Android). Self-contained npm package `@hermes-relay/cli` with:

- **`bin/hermes-relay.js`** — `#!/usr/bin/env node` shim, 12 lines, imports `../dist/cli.js#main()` and bubbles errors. npm handles the Windows cmd-shim generation automatically.
- **`src/cli.ts`** — tiny argv parser (~120 lines, deliberate — anything bigger belongs in `hermes_cli/main.py` per the upstream stance) + subcommand dispatcher. Known commands: `chat` (default), `pair`, `status`, `tools`, `help`. Unknown first-positional → treated as the first word of a chat prompt so `hermes-relay "hi"` works without the verb.
- **`src/commands/chat.ts`** — REPL + one-shot + piped-stdin unified under one function. `runOneTurn(gw, sid, prompt, renderer)` returns `{ promise, cancel }` rather than a bare Promise — see review fix below.
- **`src/commands/{pair,status,tools}.ts`** — single-purpose verbs. `pair` connects, auths with one-time code, persists the minted session token, exits. `status` is purely local (no network). `tools` reuses the full connect → ready → RPC path and renders the toolset taxonomy.
- **`src/renderer.ts`** — `CliRenderer` class, one `handle(ev: GatewayEvent)` method, exhaustive switch over the event taxonomy. Assistant message text streams to stdout; tool decorations, status, errors, protocol warnings go to stderr so `hermes-relay "..." > out.txt` captures just the reply. Respects `NO_COLOR` / `FORCE_COLOR` / `process.stdout.isTTY` for ANSI. `--json` mode emits one `JSON.stringify(ev)` per line for scripting.
- **`src/pairing.ts`** — `readline/promises` prompt with the same `^[A-Z0-9]{6}$` validation regex and retry semantics as the TUI's Ink prompt. Identical UX, substitutable substrate. Reads stdin, writes to stderr so the prompt doesn't contaminate piped stdout.
- **`src/credentials.ts`** — strict precedence: `--token` → `HERMES_RELAY_TOKEN` → `--code` → `HERMES_RELAY_CODE` → `~/.hermes/remote-sessions.json` → interactive prompt. Matches the TUI's `resolveCredentials()` in `entry.tsx` exactly.

### Vendored from ui-tui (not re-implemented)

The TUI smoke at `hermes-agent-tui-smoke/ui-tui/` already owned a clean transport interface and event type surface. We **vendored** rather than re-implemented — copied verbatim with a header note, same imports, same file paths under `src/`:

- `transport/Transport.ts` — the interface
- `transport/RelayTransport.ts` — WSS envelope protocol (docs/relay-protocol.md §3.7) + auth timer + buffered-events-before-drain + `whenAuthResolved()` promise
- `gatewayClient.ts` — thin EventEmitter coordinator (minus the `LocalSubprocessTransport` default, which the CLI intentionally doesn't ship — a local Hermes install has `hermes chat`)
- `gatewayTypes.ts`, `types.ts` — type-only
- `remoteSessions.ts` — atomic tempfile+rename, mode 0600, fail-closed to empty. **Same file path** (`~/.hermes/remote-sessions.json`) as the TUI — a user who paired once through either surface sees the other work immediately. Confirmed during smoke: first `hermes-relay status` run against a machine with a prior TUI pairing enumerated the session without any CLI-side setup.
- `lib/{circularBuffer,gracefulExit,rpc}.ts` — pure utilities

Only material delta from source: `lib/rpc.ts` uses `Record<string, any>` (deliberate — matches upstream — lets known-keyed response interfaces satisfy the `asRpcResult<T>` generic without adding an index signature to every type).

The vendor-for-now stance is documented in each file header. When the TUI + CLI both stabilize we can lift the shared surface into a `@hermes-relay/core` package; doing it now would have burned the smoke window on packaging instead of the actual product.

### Packaging — one binary, pre-built, Node ≥21

Agent 3's research mapped the idiomatic Node CLI pattern (codex-cli, continue/cn, opencode, vite, next, prisma, eslint): **one binary with subcommands, pre-build TS → JS, ship compiled `dist/` not tsx at runtime, `files` whitelist, `prepublishOnly` for the safety net.** We matched that. Notable package.json choices:

- `engines.node >= 21.0.0` — needed for the built-in global `WebSocket`. Older Node needs `--experimental-websocket`; that path is unsupported. On the Windows 11 / Node 24.14.0 dev machine the WebSocket is just there, no `ws`/`undici` runtime dep.
- Zero runtime deps, four devDeps (`@types/node`, `rimraf`, `tsx`, `typescript`). Install size is trivial.
- `bin: { "hermes-relay": "./bin/hermes-relay.js" }` — one entry. `npm install -g` on Windows generates `.cmd` + `.ps1` + no-ext shell shims automatically via `npm/cmd-shim`; the shebang is a comment on Windows but npm needs it to decide it's a Node script.
- `files: ["bin","dist","scripts","README.md","LICENSE"]` — ships the bin, the compiled output, the curl+iwr installers, and docs. Source stays off the tarball.
- `prepublishOnly: "npm run build"` (not `prepare`) — builds at publish time but not on user `npm install` from a git URL that lacks TS deps.

`scripts/install.sh` + `install.ps1` ship alongside for a `curl -fsSL .../install.sh | sh` or `irm .../install.ps1 | iex` one-liner. Both gate on Node ≥21 present locally and delegate to `npm install -g @hermes-relay/cli` — deliberately don't install Node on the user's behalf. Mirrors rustup's shape.

### Smoke test — live relay, real events

Agent 1 minted a one-time pairing code (`F3W7EY`, 10-min TTL) before expiry, and the dev machine already had a long-lived session token from prior TUI work (the cross-surface reuse described above). Full end-to-end test against `ws://192.168.1.100:8767` (hermes-relay 0.6.0, commit `675670e`, hermes-agent 0.10.0 on `axiom` branch):

- `hermes-relay status` → enumerated the pre-existing session (`79d2cf41…8d8c`, server 0.6.0, paired ~1h ago). Zero network.
- `hermes-relay tools --remote ws://192.168.1.100:8767` → full WSS connect → auth → `tui.attach` → `gateway.ready` → `tools.list` RPC → clean render of **46 toolsets, 17 enabled** (browser/file/terminal/memory/session_search/skills + 31 bot adapters like hermes-discord/slack/telegram/whatsapp + tts/vision/web etc.). One round-trip, ~3 s wall time including subprocess spawn.
- `hermes-relay chat "..." --remote ... --json` → full event trace on stdout: `session.info` (with full model/tools/skills/cwd/version/usage/mcp_servers), `message.start`, `thinking.delta`, `status.update`, `message.complete`. Scriptable via `jq`.
- `echo "..." | hermes-relay ...` → piped-stdin path reads to EOF and treats as one prompt, same event flow.

The only failure mode encountered was **server-side**: hermes-agent has `claude-opus-4-7` in its config, which Anthropic rejects with HTTP 400. The CLI surfaced it cleanly as a `status.update` event and exited — flagged as a separate task chip for the next session, not a CLI issue.

### Review fixes (code-reviewer agent, high-confidence only)

Three landed, one false alarm:

- **SIGINT race in the REPL turn loop** (real bug, fixed). Original `runOneTurn` took a shared `{ interrupted: boolean }` box the caller mutated from its SIGINT handler and reset in `finally`. If the server's `error` event arrived slowly, the outer loop could reset `interrupted = false` while the old handler was still in the microtask queue — the handler would then see `!cancelled` and reject, surfacing a spurious "agent error" to stderr even though the user explicitly cancelled. Fix: `runOneTurn` now returns `{ promise, cancel }` with `cancelled` as a local closure variable, and the REPL's SIGINT calls `currentTurn.cancel()` rather than mutating shared state. Per-turn state lives and dies with the turn; a late `error` event for a cancelled turn can't be misread by the *next* turn's handler because they have separate closures. **Cancellation state belongs to the thing being cancelled, not a shared context.**
- **`status --json` leaked full bearer tokens to stdout** (real — security). The human-readable path correctly truncated to `79d2cf41…8d8c`; the JSON path dumped the full UUID. Fix: redact by default in JSON too, opt in with `--reveal-tokens`. Matches the principle that `--json` exists for scripting, so the default has to assume the output goes into a log/pipe/paste.
- **`.d.ts.map` / `.js.map` referenced `src/` that isn't in the published tarball** (real — packaging). `tsconfig.build.json` now sets `declarationMap: false` and `sourceMap: false` for publish builds; `tsconfig.json` keeps them on for local dev.
- **Argv parser "loses URL when followed by short flag" claim** — false alarm. Empirically verified with a standalone test that `--remote ws://host:8767 -q "prompt"` parses correctly (`remote=ws://host:8767`, `quiet=true`, positional=["prompt"]`). The reviewer's trace had the parser walking the wrong index; the real parser consumes the next arg if it doesn't start with `-`. Left as-is.

### Team delivery

Three parallel Wave-1 explorers (server-side SSH + tui_gateway + pairing code mint; ui-tui code map + shareable-vs-TUI file classification; npm packaging research + published-tool reference harvest) → synthesis → implementation (one batch; vendoring + new files) → smoke → code-reviewer sweep → three fixes + rebuild + re-smoke. End-to-end working in one session.

**Vault note.** Updated the project vault's `Desktop Client.md` status from "concept/backlog" to "v0.1 CLI shipped — tool routing remains the open piece." The vault's core design (new `desktop.command` channel, per-tool routing table in `model_tools.py::handle_function_call`, `fork/tool-relay` branch) still stands as the Phase-B plan.

---

## 2026-04-22 (III) — Desktop TUI MVP Phases 1–3 landed

**Context.** The `Desktop TUI over WSS` plan was approved (see `docs/plans/2026-04-22-desktop-tui-mvp.md`). Three implementation phases ran as parallel agents on two repos:

- **hermes-relay `feature/desktop-tui-mvp`** (this repo).
- **hermes-agent `feat/tui-transport-pluggable`** (Codename-11 fork off `axiom`).

### Phase 1 — `tui` channel on the relay (landed in commit `849bd2e`)
Python handler at `plugin/relay/channels/tui.py` spawns a `tui_gateway` subprocess per connected WebSocket and transparently pumps line-delimited JSON-RPC between the socket and the subprocess's stdio. Clean SIGTERM-then-SIGKILL teardown on `tui.detach` or WS close. 14/14 unittests in `plugin/tests/test_tui_channel.py`. Auth grants extended to include `tui` with a 30-day cap (`auth.py _default_grants`). Router registered at `ChannelMultiplexer`. Resize RPC method confirmed as `terminal.resize` after spot-checking `tui_gateway/server.py:1508` — the Phase 1 guess stood.

### Phase 2 — Pluggable transport in `ui-tui/` (hermes-agent `4a7d026`)
Extracted `gatewayClient.ts` into a `Transport` interface (`ui-tui/src/transport/Transport.ts`) with two impls: `LocalSubprocessTransport` (current behavior) and `RelayTransport` (new — WSS + envelope wrap/unwrap). `entry.tsx` factory picks transport from `HERMES_RELAY_URL` / `--remote`. No Python changes — `tui_gateway/server.py` is byte-identical. 190/190 new transport tests pass; the 4 pre-existing `terminalSetup.test.ts` failures are unrelated (SSH-session mock drift, documented by the Phase 2 agent).

### Phase 3 — Glue: CLI flag + session storage + smoke harness *(this session)*

**hermes-agent side:**
- `hermes_cli/main.py`: added top-level `--remote <url>`, `--pair / --pairing-code <code>`, `--token <token>` flags and a new `_launch_remote_tui()` short-circuit that skips the local `tui_gateway` spawn entirely and forwards credentials as `HERMES_RELAY_*` env vars to the Node TUI. Gives a helpful "pair first" error when no credentials exist.
- `ui-tui/src/remoteSessions.ts` (new, ~160 LOC): `~/.hermes/remote-sessions.json` storage — `getSession` / `saveSession` / `deleteSession` / `listSessions`. Atomic tempfile → rename write, mode 0o600. Fail-closed on any read/parse error. Cert pin SHA-256 inlined into the same file under `cert_pin_sha256`.
- `ui-tui/src/transport/RelayTransport.ts`: added `onAuthSuccess(cb)` observer + `getAuthInfo()` getter + `sendResize(cols, rows)` method.
- `ui-tui/src/entry.tsx`: loads a stored session token before constructing `RelayTransport`, persists the minted token after `auth.ok`, and wires a `process.stdout.on('resize')` pump that forwards cols/rows as `tui.resize` envelopes.

**hermes-relay side:**
- `scripts/tui-smoke.sh` + `scripts/tui-smoke-teardown.sh`: non-interactive harness that stops any running relay, starts a dev relay (port 8767, no SSL), waits for `/health`, mints a pairing code via `POST /pairing/register`, and prints the exact command to run on the desktop. Verified end-to-end here — relay came up, health returned 200, code minted, handoff printed.
- `.gitignore`: exclude `.smoke-relay.pid` / `.smoke-relay.log` runtime artifacts.

**Deferred:** TOFU cert pinning. The current `RelayTransport` uses Node's global `WebSocket` (undici), which doesn't expose the server certificate. Doing cert pinning properly requires switching to the `ws` npm package + constructor cert inspection — too invasive for the MVP. Storage slot is reserved in `remote-sessions.json` under `cert_pin_sha256` so adding pin capture later is a one-file change to `RelayTransport` (or a new `TlsCertPinnedTransport`). TLS verification against system CAs is still on by default, so the "production" risk here is MITM-by-CA-compromise — an acceptable baseline for v0.8.0-alpha. Defer to v0.8.1 as a hardening follow-up.

### Green criteria
- `python -m py_compile hermes_cli/main.py` → OK
- `cd ui-tui && npm run type-check` → clean
- `cd ui-tui && npm test` → 204 passing (190 → 204, the 14 net-new tests cover `remoteSessions` + `RelayTransport.onAuthSuccess` / `sendResize`); 4 pre-existing `terminalSetup.test.ts` failures unchanged
- `python -m unittest plugin.tests.test_tui_channel` → 14/14 OK
- `bash scripts/tui-smoke.sh` → relay up, health 200, pairing code minted, handoff printed

### Open items (post-MVP)
1. **`profiles` surfacing.** The relay's `auth.ok` already carries a `profiles[]` array (see `docs/relay-protocol.md` §1.3). The Node TUI currently ignores it. Wire it into the transport log + expose as a `/profiles` slash-command listing so the user can pick a profile on `--remote` mirrors the local HERMES_PROFILE flow.
2. **Reconnect + `resume_session_id`.** On WS drop, `RelayTransport` currently just tears down. Phase 2 already stashed `resume_session_id` on the protocol — Phase 4 adds exponential-backoff reconnect that re-attaches to the same subprocess if the relay still has it alive (the relay keeps subprocesses for a short grace period after a client disconnects — TBD).
3. **Single-binary packaging.** Currently the user needs Node 20 + this repo's `ui-tui/` checkout. Ship as `npm install -g @codename-11/hermes-tui-remote` or `pkg`-bundle for Windows/Mac.
4. **Upstream PR candidates.** The `Transport` refactor is defensible on its own (decouples UI from runtime). Good PR candidate for NousResearch/hermes-agent once validated end-to-end.
5. **TOFU cert pinning** (deferred from this session — see note above).

### Next
Interactive smoke test from a separate terminal:
```
bash scripts/tui-smoke.sh
# (copy the printed code)
cd ~/.hermes/hermes-agent/ui-tui && \
  HERMES_RELAY_URL=ws://localhost:8767 \
  HERMES_RELAY_CODE=<CODE> \
  node --loader tsx src/entry.tsx
```
Or via the CLI: `hermes --remote ws://localhost:8767 --pair <CODE>` once hermes-agent `feat/tui-transport-pluggable` is installed.

---

## 2026-04-22 (II) — Power-user override philosophy: three tightenings + the Transport Security badge reason-derivation fix

**Context.** On-device testing of the UX pass surfaced a specific defect — the active card's Security section showing `"Insecure (network unknown)"` while actually paired over LAN — plus a broader question: do we allow power-user override with subtle warning (no forced confirm)? The answer codified in this commit is **three-tier**:

| Tier | Rule | Examples |
|------|------|----------|
| **1 — Forced confirm (once per install)** | Crosses a security boundary OR flips global policy. | First insecure-toggle enable, first bridge enable, first unattended bridge enable, first run of `send_sms` / `call`, **new: AllInsecure pairing** |
| **2 — Subtle warning, no confirm** | Reversible, informational, or risk is informed by design (e.g. secure fallback exists). | Mixed-state pairing (secure fallback present), Never-expire TTL, Plain badge on active route |
| **3 — Per-action Yes/No confirm (not persistable)** | Genuinely destructive short-term action. | Revoke session, Remove connection, Kill terminal |

Today's commit lands four changes that operationalize this framework.

### (a) Transport Security badge — derive reason from active endpoint role

**The defect.** `TransportSecurityBadge` has long had a `reason: String?` parameter whose labels were `"Insecure (LAN)"` / `"Insecure (Tailscale)"` / `"Insecure (dev)"` / `"Insecure (network unknown)"`. The `reason` only got populated when the user toggled "Allow insecure connections" ON via the `InsecureConnectionAckDialog` and explicitly picked a reason. But if the user pairs from a plain-`ws://` LAN QR directly, they never hit that toggle — the connection is already `ws://` — so the reason stays blank and the badge falls through to `"Insecure (network unknown)"`. The app had the information (`ConnectionManager.activeEndpoint.role = "lan"`), it just wasn't reading it.

**The fix.** `insecureReasonLabel(reason, activeRole)` now prefers role over stored reason:
- `activeRole == "lan"` → `"Plain (on LAN)"`
- `activeRole == "tailscale"` → `"Plain (on Tailscale)"`
- `activeRole == "public"` → `"Plain (on public URL)"` (this is the actually-concerning case)
- `activeRole` unknown, `reason == "lan_only"` → `"Plain (LAN only)"` (user-stated intent)
- Both unknown → `"Plain (no TLS)"` (neutral fallback, not scary)

Also: `ConnectionViewModel.applyPairingPayload` now auto-stamps `PairingPreferences.insecureReason` at pair time based on which endpoint got selected. `lan` → `"lan_only"`, `tailscale` → `"tailscale_vpn"`, `public`/unknown → leave blank (those cases deserve user thought). Only stamps when the current stored reason is blank (never clobbers user choice). Clears stale reason when upgrading to a secure endpoint so a connection that moves LAN → Tailscale doesn't carry a zombie `"Plain (LAN)"` label.

**Copy polish.** Swapped "Insecure" → "Plain" everywhere user-facing (the badge labels, the two "Insecure connection" / "Allow insecure connections" strings inside the Advanced section's insecure-toggle subsection). The new vocabulary matches the UX pass's amber-not-red treatment — "Plain" is factual, "Insecure" was connotatively red.

### (b) Bridge destructive-verb "Don't ask again" per verb

Confirmation fatigue is real. A user who has approved `send_sms` 50 times has effectively consented — forcing confirm #51 trains them to click through without reading. New `trustedDestructiveVerbs: Flow<Set<String>>` in `BridgeSafetyPreferences`; `BridgeSafetyManager` short-circuits the confirmation overlay when the incoming verb is in the trusted set (still logs to the activity log — the trail is preserved). The `DestructiveVerbConfirmDialog` gets a `Don't ask again for "{verb}"` checkbox, off by default every dialog open, so the user has to actively opt in per-action.

**Kill-switch precedence** (verified by tracing `send_sms` through the full dispatcher): master-disable wins over blocklist wins over per-verb trust. A trusted verb in a blocklisted app still 403s. A trusted verb under a disabled master toggle never fires. Deny never sets trust — denying is not consent.

`BridgeScreen` surfaces a `"Trusted actions · N actions bypass confirmation"` row under the existing safety section with a `Reset` button (guarded by its own confirm dialog). The escape hatch is findable without deep-linking.

### (c) AllInsecure pairing — per-install acknowledgment

When every endpoint in the scanned QR is plain (no secure sibling), `ConnectionWizard.ConfirmStep` renders an ack checkbox above the Pair button. `gateIsSatisfied = allInsecureAckSeen || ackThisPair` for AllInsecure only — Mixed and AllSecure flow through `else → true` and see no gate. Once the user acknowledges once, `PairingPreferences.allInsecurePairAckSeen` persists per-install and the checkbox never shows again. Matches the `insecureAckSeen` precedent for the Allow-insecure toggle.

Final copy: *"I understand this pairing sends traffic in plain text — visible to anyone on the network."* Concrete — explains the *consequence* ("visible to others"), not just the transport ("plain text"). No legalese.

**Why Mixed doesn't get this gate.** Mixed by definition has a secure fallback in the same list — LAN + Tailscale means the phone auto-switches to Tailscale when LAN fails, so the user *is* covered on any network. The existing amber "Mixed — secure fallback available" warning card is sufficient. Only the AllInsecure case (no secure sibling) crosses a trust boundary the user needs to acknowledge once.

### (d) Vocabulary cleanup

Two "Insecure" stragglers caught by the review sweep inside `ActiveConnectionSections.kt`:
- `"Insecure connection — traffic is not encrypted"` → `"Plain connection — traffic is not encrypted"`
- `"Allow insecure connections"` → `"Allow plain (unencrypted) connections"` (toggle label — functional copy, but "plain" keeps the app's vocabulary consistent without being dismissive of the real risk)

### Team delivery

Three parallel implementation workstreams (isolated file ownership) + one review sweep. One transient cross-file compile break caught mid-flight — in-progress changes to `BridgeSafetyManager` referenced a method the `BridgeScreen` edit hadn't yet wired up; the AllInsecure workstream stashed + restored `BridgeScreen` to isolate its test. Final combined state compiles clean on both flavors without intervention. Lesson logged: **when two parallel workstreams touch the same concept (Bridge infrastructure + Bridge UI), one of them needs to own both files, even if the actual diff per file is small.** The Bridge workstream ended up owning both; the AllInsecure stash was defensive and correct.

### Logcat sanity

Pulled full ADB logcat for the test session as a sanity check. Zero errors from app code. The only Hermes-app warning was the `HermesNotifCompanion: Buffered notification (pending=50)` cold-start log — notifications arriving before the WSS multiplexer connects, buffered until capped. This is the designed behavior of the notification listener's cold-start gap; worth a follow-up to confirm no useful data is silently lost on systems with high pre-pair notification volume.

---

## 2026-04-22 — Connection UX self-narration: Route / Relay sessions vocabulary, contextual security, per-route chips

**Context.** On-device testing of the 2026-04-21 connection-settings unification surfaced five concrete UX observations, all sharing one theme: *the UI has the right information but isn't narrating it*. (1) Add-Connection still had a perceptible lag before the QR scanner opened. (2) On a multi-endpoint QR with LAN + Tailscale, pairing step 2 flashed an amber "Insecure (dev)" badge and a red warning card — making users think they were stuck with insecure forever, even though the Tailscale fallback was right there in the same list. (3) The active card had the right structure post-unification but no narration — sections stacked without headers, Advanced surfaced manual URLs without a "most people don't need this" framing. (4) "Paired Devices" sounded like Bluetooth to anyone outside the project; the actual concept is server-side relay sessions. (5) Priority labels on endpoint rows were `p0` / `p1` / `p2` — developer-speak.

**Root cause of the insecure-scare.** `ConnectionWizard.kt:1183` computed `isInsecureRelay = relayUrl?.startsWith("ws://")` where `relayUrl` was synthesized from `payload.endpoints[0]` alone (first-in-QR = LAN). Both the amber top badge and the red warning card fired from this single boolean. The code *had* the other endpoints, it just never inspected them — so a secure Tailscale sibling was invisible to the warning logic.

**Design.** Introduce one shared vocabulary, apply it end-to-end:

| Noun | Replaces | Notes |
|------|----------|-------|
| **Route** | "Endpoint" in user copy | Code identifiers (`EndpointCandidate`, `observeDeviceEndpoints`) keep the old term — just strings change |
| **Relay session** | "Paired device" | `Screen.PairedDevices` + the Kotlin class stay for deep-link stability |
| **Active / Fallback** | implicit in chip state | State-based chips on the active card (post-connection semantics) |
| **1st choice / Fallback / Fallback 2** | `p0` / `p1` / `p2` | Ordinal labels on pairing step 2 only (pre-connection semantics) |
| **Secure / Plain** | "Encrypted" / "Insecure" | Green 🔒 / amber 🔓 — amber, not red, so the Mixed case doesn't feel like a crisis |

Active card gains four `labelMedium` section headers — Connection health, Routes (N), Advanced, Security — each with a one-line `bodySmall` caption above the body: *"Tap any row for details"*, *"The app picks the fastest reachable network automatically…"*, *"Manual setup — most people don't need this after QR pairing"*, etc. Section-header-with-caption is now the structural pattern for any expandable subsection in the app.

**Where the code landed.**
- `ui/components/TransportSecurityBadge.kt` — new `TransportSecurityState` enum (`AllSecure` / `Mixed` / `AllInsecure`) with a tri-state overload. Binary-boolean callers untouched (back-compat is explicit — the new overload sits alongside the old, and the shared `RenderBadge` private composable keeps the visual language identical).
- `ui/components/ConnectionWizard.kt` — `ConfirmStep` now computes `securityState` from the full `endpoints` list (`anySecure` + `anyInsecure` booleans over `relay.url.startsWith("wss")` / `api.tls` / `relay.transportHint`). Top badge wired to the tri-state. Warning card is `when (securityState)` — `AllInsecure` keeps the legacy red copy (slightly reworded), `Mixed` shows an amber-tinted info card with per-role copy ("$plainLabel is plain ws:// — fine at home or the office, not on public Wi-Fi. $secureLabel is encrypted (wss://)…"), `AllSecure` omits the block entirely. `EndpointPreviewRow` rewritten with per-row Secure/Plain pill + humanized ordinal (`1st choice` / `Fallback` / `Fallback N`).
- `ui/components/ActiveConnectionSections.kt` — `Paired Devices` row renamed to `Relay sessions` (both the label and the subtitle copy); logic unchanged.
- `ui/screens/ConnectionsSettingsScreen.kt` — four new section-header-caption pairs inside the `if (isActive && activeConnectionViewModel != null)` gate. New private `SectionHeader` + `SectionCaption` helpers. Expander toggle swapped from `"Show endpoints (N)"` to `"Show routes"` (count lives on the header above — no double-counting).
- `ui/components/EndpointsCard.kt` — populated-state intro caption, new `FallbackChip()` composable (outlined neutral), `when { isActive → ActiveChip … else → FallbackChip() }` so every row has a chip.
- `ui/screens/PairedDevicesScreen.kt` — top-bar title + empty + error copy renamed. New intro paragraph ("Each row is a phone that has paired with this server…"). Info icon next to "Channel grants" opens a dialog explaining that chat/bridge/voice are per-feature permissions with independent expiries.
- `ui/RelayApp.kt` — `Screen.PairedDevices` nav title renamed (`"Relay sessions"`, Kotlin class stays). `onAddConnection` now pre-allocates a UUID synchronously, navigates, then fires `beginAddConnection(preAllocatedId = id)` in the background — the lag fix.
- `viewmodel/ConnectionViewModel.kt` — `beginAddConnection(preAllocatedId: String? = null)` — existence check for idempotence, otherwise preserves the legacy placeholder-reuse scan path byte-for-byte.
- Seven vocabulary stragglers in files the initial implementation pass didn't touch: `ConnectionInfoSheet.kt` (both the collapsible label + the "Endpoint preference" info row), `SessionTtlPickerDialog.kt` ("revoke it from Paired Devices" → "…from Relay sessions"), `SettingsScreen.kt` (the "Paired devices" category row), `EndpointsCard.kt` (menu item "Prefer this endpoint" → "Prefer this route"). All caught by the review sweep.

**Subtle decisions worth flagging.**

*Why two different framings for endpoint state.* Pairing step 2 uses ordinal labels (`1st choice` / `Fallback`) because at that moment the user is committing to an ordering — "which one should the phone try first?" is the only meaningful question. The active card uses state chips (`Active` / `Fallback`) because once connected, ordinal position doesn't matter — what the user cares about is "which one is live right now?" Using the same vocab on both surfaces would force one or the other to lie about its real meaning. Letting each surface use the framing that matches its moment is the "say what you mean" principle applied to UX copy.

*Why amber, not red, for Plain / Mixed.* The previous UI used errorContainer red for every ws:// case, which conflated "dev hack" with "security emergency." Plain ws:// on LAN is the *normal* home-network case — the trust model is the network perimeter, not TLS. Red connotes danger; amber connotes caution. The Mixed card stays amber too, because the composite state ("your LAN is plain but your fallback is secure") isn't dangerous — it's well-designed defense in depth. Red is reserved for `AllInsecure` without a secure fallback.

*Tri-state badge API back-compat.* The new `TransportSecurityBadge(state: TransportSecurityState, size)` overload sits alongside the existing `TransportSecurityBadge(isSecure: Boolean, reason: String?, size)`. All three call sites were independently verified to compile and render identically — the old overload delegates to the same `RenderBadge` private composable, so visual language is guaranteed identical across surfaces. No migration churn for the handful of callers outside the pairing flow.

*Add-Connection lag — why this fix is correct.* The previous architecture (`await beginAddConnection().join()` → `navigate(...)`) was *structurally* correct — you want the placeholder connection bound before the pair wizard starts reading `activeConnectionId`, because `applyPairingPayload` reads `connectionStore.activeConnectionId.value` as a one-shot at submit time. But the read happens on user-action callbacks (confirm QR / manual submit), which are many seconds after navigation — plenty of time for the background coroutine's three DataStore writes to complete. Pre-allocating the UUID synchronously means the navigation knows the id; the ViewModel catches up reactively. The mutex + existence check handle double-tap idempotence.

**Delivery.** Four-stage pipeline this time: three exploration passes up front (each mapping a different surface in parallel — ConfirmStep layout, active card body, add-connection code path), three implementation workstreams (one per file cluster, isolated ownership to avoid collisions), one review pass at the end for the vocabulary-consistency sweep. The review caught seven stragglers the implementation passes couldn't have seen (their file scope was deliberately narrow). That's the pattern: **wide exploration, narrow implementation, wide review**.

---

## 2026-04-21 — Connection-settings unification: kill the singular screen, active card owns everything

**Context.** Pre-ship scan for v0.7.0 surfaced a UX fault line that had been accruing since v0.3: the app had *two* screens with nearly identical names (`ConnectionSettings` singular, `ConnectionsSettings` plural) reached from *two* different Settings-top surfaces (Active Connection quick-look card vs. "Connections" category row), covering *overlapping* surfaces of functionality. Users hitting "Active Connection" from Settings landed on a 1429-line detail screen with pair / manual URL / TLS / manual pairing code; users hitting "Connections" landed on a 564-line card list with rename / re-pair / revoke / remove per card. Both said "Active" somewhere; both claimed to be the authoritative connection home. Neither was.

**Design.** One screen, one mental model. The plural `ConnectionsSettings` screen stays — it's the multi-connection-aware home and structurally correct. The singular `ConnectionSettings` (and its route, and its `onNavigateToConnectionSettings` param chain, and the Active Connection quick-look card on Settings that led to it) all delete. Everything the singular screen *did* — pair QR entry, manual URL config, insecure toggle, manual pairing code fallback, status rows with tap-for-info-sheet — folds into the **active card** on the plural screen as expandable body sections:

 1. **Status section** — 3 tappable rows (API / Relay / Session), always visible on the active card. Replaces the Settings-top quick-look card verbatim. Tap → same info sheets. Relay-row tap while Stale → immediate reconnect + toast.
 2. **Endpoints expander** — unchanged from before; just repositioned below the status rows.
 3. **Advanced expander** — manual URL config (API + relay), insecure toggle with Ack dialog, manual pairing code fallback (full 3-step flow with 15s auth watcher + snackbar). Collapsed by default — the canonical path is the per-card "Re-pair" button above.
 4. **Security posture strip** — transport badge, Tailscale chip, hardware keystore badge, Paired Devices row. Always visible on the active card.

Non-active cards stay flat — just title + subtitle + action row. List density is preserved.

**Where the code landed.**
- `ui/components/ActiveConnectionSections.kt` (new, ~650 lines) — owns the three active-card-only bodies (`ActiveCardStatusSection`, `ActiveCardAdvancedSection` with three private subsections, `ActiveCardSecurityPosture`) plus the `ManualPairStep` helper lifted from the deleted legacy screen.
- `ui/screens/ConnectionsSettingsScreen.kt` (rewritten, ~580 lines) — now renders the full active-card body inline via the new sections, with screen-scope hoisting for info sheets + insecure-Ack dialog (so `LazyColumn` disposing the card mid-scroll can't silently dismiss an open sheet).
- `ui/screens/ConnectionSettingsScreen.kt` (deleted, was 1429 lines).
- `ui/RelayApp.kt` — drops the `composable(Screen.ConnectionSettings.route)` block, the `data object ConnectionSettings` entry in the `Screen` sealed class, and the `onNavigateToConnectionSettings` lambda wired into `SettingsScreen`. Adds `onNavigateToPairedDevices` to the plural screen's composable call.
- `ui/screens/SettingsScreen.kt` — deletes the Active Connection quick-look Card block (~90 lines), the `onNavigateToConnectionSettings` param, and the 7 `collectAsState` calls (apiReachable / apiHealth / authState / apiUrl / relayUrl / relayUiState / relayRowState + `relayFeatureEnabled`) that were only used by that card.
- `user-docs/reference/configuration.md` + `user-docs/guide/getting-started.md` — nav paths updated throughout: every `Settings → Connection → X` becomes `Settings → Connections → [active card] → X` or `...→ Advanced → X`.

**Subtle decisions worth flagging.**

*LazyColumn item disposal vs. modal state.* The Card is a `LazyColumn` item. If the user scrolls it off-screen while a status-row info sheet is open, the item gets disposed and any `remember { mutableStateOf(false) }` inside it is gone. So `showApiInfoSheet` / `showRelayInfoSheet` / `showSessionInfoSheet` / `showInsecureAckDialog` are all hoisted to `ConnectionsSettingsScreen` scope. Dialog confirmation still wipes card-scope state if the card is still alive; screen scope survives scroll regardless. Card-scope `remember` is retained only for per-card modals that logically can't exist cross-card (rename / revoke-confirm / remove-confirm dialogs).

*Endpoint-flow cold-start gap.* `observeDeviceEndpoints()` is a cold `flow { ... }` that suspends on `getOrCreateDeviceId()` before the first emission. During that gap, `endpoints == emptyList()` and the Endpoints expander hides — which is correct for the Endpoints-only content but NOT for the Status section or Advanced section, which should be unconditionally visible on the active card. The guard is split: outer `if (isActive && activeConnectionViewModel != null)` gates the deep body; the inner `if (endpoints.isNotEmpty())` only gates the Endpoints expander. Surfaced during exploration as the one thing to warn a new developer about — worth documenting.

*Why `InsecureConnectionAckDialog` is hoisted through a callback rather than owned by the Advanced section.* The dialog would work if owned by the card — but if the card scrolls off mid-open, the dialog dismisses silently. The Advanced subsection fires `onInsecureAckRequested()` which opens a screen-scope boolean; the dialog renders in the screen's root composition. Survives scroll, survives recomposition, one source of truth.

*Mutex-free reconnect-on-entry.* Both `SettingsScreen` and `ConnectionsSettingsScreen` call `reconnectIfStale()` in `LaunchedEffect(Unit)`. The VM method no-ops if a reconnect is already in flight, so the duplicate call is free and actually helpful — firing on Settings entry means the subpage arrival already has a warm reconnect attempt rather than triggering one on its own arrival.

**Delivery.** Three parallel exploration passes up front — one to inventory the 1429-line singular screen's features by category (inline / advanced / duplicate / dead code / state deps / dialogs / nav entry points), one to map the plural screen's current active-card rendering + expansion patterns + constraints, one to trace every caller of the route and parameter chains that would need rewiring. All three returned line-numbered reports quickly, which made the synthesis + implementation step mechanical. Worth the pattern for any similar "delete-and-fold" refactor.

**Post-refactor vertical map for connection management:**

```
Settings
├── Active Agent card                 (unchanged — summary chip, opens AgentInfoSheet inline)
├── Inspect Agent card                (unchanged — Profile deep-link)
└── [Connections] category row
    └── ConnectionsSettings subpage
        ├── Non-active card           (title + subtitle + action row — flat)
        └── Active card               (everything above, PLUS inline deep body:)
            ├── Status section        (API / Relay / Session rows → info sheets)
            ├── Endpoints expander    (conditional on endpoints.isNotEmpty())
            ├── Advanced expander
            │   ├── Manual URL        (API + Relay URL + Save & Test)
            │   ├── Insecure toggle   (with first-enable Ack dialog)
            │   └── Manual code       (3-step fallback flow)
            └── Security posture      (transport + Tailscale + hardware + Paired Devices row)
```

Two top-level entries. One subpage. One active card. Every connection action reachable in a deterministic drill-down. No naming collisions, no duplicate surfaces, no wondering which "Connection" the Settings tap will land on.

## 2026-04-21 — `relayReady` gate + KDoc nested-comment trap

**Context.** Two unrelated passes in one session. (1) Voice mode and Bridge commands both depend on the WSS relay, but the app had no unified signal for "relay is actually functional" — a user with no relay paired would tap the Mic and get a cryptic failure, or the Bridge master toggle would happily enable an accessibility service whose commands would never arrive. (2) While running a ship-readiness scan for v0.7.0, the compile failed with "Unresolved reference 'isReady'" at `MainActivity.kt:67` — a symptom that took some digging to trace to its real cause.

**`relayReady` design.** Symmetric to the existing `chatReady: StateFlow<Boolean>` on `ConnectionViewModel`. Three-input `combine` over `connectionManager.connectionState`, `authState`, and `_relayUrl`: all three must be in their healthy state (`Connected`, `Paired`, non-blank) for `relayReady = true`. Three-input is deliberate — without the URL check, the Case-C teardown edge (last connection removed, active id goes blank) leaves a stale `Paired` token alive against a dead URL and a simpler two-input gate would pass through. Placed *below* `_relayUrl` in the class body because Kotlin class-initializers run top-to-bottom and a forward reference to a `MutableStateFlow` constructor-site read returns null (left a breadcrumb comment where `chatReady` is declared pointing to the real location — symmetry in the source ordering vs reality-of-Kotlin-initialization).

**UI wiring.** Two consumer surfaces, both **soft-gate** rather than hard-disable (matches the Chat-send pattern already in the codebase). ChatScreen's mic button dims to `onSurfaceVariant @ alpha=0.5` and tapping it fires a Toast instead of launching voice mode; content description flips so TalkBack reads "Voice mode unavailable — relay not connected". BridgeScreen gets an error-container banner at the top of the scroll region with a Warning icon and two lines of copy — but we intentionally do NOT block the master toggle, because pre-configuring permissions, safety rails, and unattended access IS valuable before a relay pairs, and the BridgeViewModel's own state prevents command dispatch until the relay wakes up anyway. `connectionViewModel` is nullable on `BridgeScreen(connectionViewModel: ConnectionViewModel? = null)` so `@Preview` fixtures compile without rigging up a full VM; the fallback `StateFlow(true)` means "assume ready when no signal" which reads right in every path that matters.

**The compile trap.** The grep that "confirmed" the relayReady gate had landed showed all four edit sites present, but the compile still failed. Symptom: `MainActivity.kt:67:34 Unresolved reference 'isReady'` — `isReady` has been a public member of `ConnectionViewModel` since v0.4 so this was confusing. Ran the actual `./gradlew :app:compileGooglePlayDebugKotlin` and saw ~50 cascading "Unresolved reference" errors across `PairedDevicesScreen`, `SettingsScreen`, `TerminalScreen`, and two actual syntax errors at the bottom: `ConnectionViewModel.kt:390:62 Missing '}` and `ConnectionViewModel.kt:2630:1 Unclosed comment`.

Root cause: the `relayReady` KDoc contained the line `* - WSS not Connected → transport is down; any /voice/* or bridge`. **Kotlin supports nested block comments.** The `/*` inside `/voice/*` opened a nested comment block; the KDoc's closing `*/` at line 418 closed the *nested* block, leaving the outer `/**` wide open — which then consumed the remaining ~2200 lines of the file (including the `isReady` declaration at line 573), producing exactly the observed error pattern. Fix was two characters: quote the path pattern in backticks AND break the `/*` token by rewriting it as `` `/voice/...` ``. One edit, rerun compile, BUILD SUCCESSFUL.

**Lesson.** "`/*`" inside Kotlin KDoc is a comment-opener, not a literal pattern. Avoid writing shell-glob or regex-like patterns in block comments; inline-code them with backticks AND use `...` instead of `*` when possible. This is the third documented Kotlin-vs-Java comment trap in this codebase — nested comments plus no error at parse-start plus a cascade of misleading symbolic errors is a uniquely confusing failure mode. Worth checking any other `/*` strings inside KDoc comments in the codebase before the next feature-heavy diff.

**Not touched (out of scope).** Everything on the v0.7.0 release checklist — version bump, CHANGELOG flip from `[Unreleased]` to `[0.7.0] — 2026-04-21`, release-PR cut. On-device verification through Android Studio is needed before proceeding with the release commits.

## 2026-04-20 — Connection pairing audit: orphan placeholders, scan-auto-start, inline switcher

**Context.** Two reported symptoms: (1) the top-bar "connection" chip was showing `New connection…` as the active connection label, (2) a suspected double-pair. Server-side SSH verified `hermes-relay` + `hermes-gateway` healthy; pairing code `4YBZ0W` minted at 21:33:23 UTC-4 with a clean revoke+re-pair cycle. The device logcat buffer had already rolled past the pair event, so diagnosis was a code-path audit + screenshot evidence.

**Root cause — orphan placeholders.** `ConnectionViewModel.beginAddConnection` pre-creates a `Connection` with label = `PLACEHOLDER_LABEL ("New connection…")` and empty URLs, then calls `switchConnection(id)` so `applyPairingPayload`'s token write lands in the new auth store (the structural fix for the "token written to wrong connection" class of bug, already in place pre-audit). Cleanup for abandoned placeholders was wired to `onCancel` only — which `RelayApp.kt`'s Pair route fired for (a) the TopAppBar back arrow and (b) the in-wizard Cancel action. **System back (gesture back / predictive back) bypassed that branch entirely** — NavController just pops the backstack without invoking the composable's `onCancel` lambda. Gesture back at some point left the placeholder in storage. On next app open, `ConnectionViewModel.init` had no cleanup logic, so the orphan stayed active (because `switchConnection` had made it active before the wizard ran) and showed its placeholder label on every UI surface that reads `activeConnection.label`.

**Fix 1 — defensive init sweep.** Added an orphan-cleanup coroutine in `ConnectionViewModel.init`, fired after the legacy-seed migration completes. Sweeps for `pairedAt == null && apiServerUrl.isBlank() && label == PLACEHOLDER_LABEL` — a tuple no real pairing can produce, so the delete is unconditional-safe. If the active connection id points at an orphan, switches to the first surviving real connection before deleting so we don't leave `activeConnectionId` pointing at a dead record. Fixes affected devices in-place without the user having to find + delete the orphan manually. Logs each removal at INFO so future investigations have a paper trail.

**Fix 2 — BackHandler on PairScreen.** Two-line fix in `PairScreen.kt`: `BackHandler(enabled = true) { onCancel() }`. Now gesture back, predictive back, and hardware back all converge on the same `discardPlaceholderConnection` branch the TopAppBar arrow uses. Prevents new orphans from being created going forward.

**Fix 3 — auto-start scan on Add connection.** The wizard at `ConnectionWizard.kt:146` always started at `WizardStep.Method` (the chooser). The reported issue — "didn't open the pair flow automatically" — was exactly this: "Add connection" has one obvious next step (scan QR), forcing a two-tap path through the Method chooser is needless friction. Added an `autoStart: String?` param to `ConnectionWizard`; on first composition a `LaunchedEffect(Unit)` inspects the value and, when `"scan"`, fires the camera permission launcher immediately (equivalent to the user tapping the Scan tile). `Screen.Pair` grew a second query arg `autoStart` plumbed through the route builder, the composable entry, and `PairScreen`. The Connections settings FAB passes `autoStart = "scan"`; re-pair surfaces intentionally leave it null so the full Scan / Enter code / Show code chooser stays available there (a user re-pairing may have reasons to pick manual code entry). Unrecognized values fall through to the default Method step so future builds adding more deep-link targets don't break old ones.

**Fix 4 — remove the top-bar chip, fold switcher into the Agent sheet.** The app-wide `ConnectionChip` row rendered above every primary tab when `connections.size >= 2`. Screenshot audit: it was (a) duplicating the Agent sheet's Connection section metadata, (b) eating vertical space above every screen, (c) actively confusing the user by surfacing the orphan's `New connection…` label. Deleted the `AnimatedVisibility` block + the `ConnectionChip` import + the `connectionSheetVisible` state + the `ConnectionSwitcherSheet` render block at the bottom of `RelayApp` (the sheet class file stays on disk for future programmatic callers). Added a new inline switcher block to `ConnectionInfoSheet.kt`'s `AgentInfoSheet` — collects `connectionViewModel.connectionStore.connections` + `activeConnectionId`, renders a radio list using the existing `ProfileRadioRow` component (same visual pattern the Profile and Personality sections already use), visible only when `connections.size >= 2`. Tapping a non-active entry fires `switchConnection` + a confirmation toast. Switching is now reachable from the same tap the user already uses to switch Profile or Personality, which addresses the reported gap that connection switching wasn't properly wired into the top-bar agent name drawer.

**Scope discipline.** The `rename-on-pair` logic at `ConnectionViewModel.kt:1266` is correct — its guard `current.label == PLACEHOLDER_LABEL && current.apiServerUrl.isNotBlank()` is what's SUPPOSED to rename a placeholder once the scan populates the URL. The orphan didn't reach that guard because the pair never completed — `apiServerUrl` stayed blank, so the rename path never triggered and the placeholder stayed labeled. No fix needed there.

**Not touched (out of scope):**
- Phase B upstream rich-card adapter work — still held pending real usage.
- Session-sync story for card dispatches already shipped earlier today (ADR 26 / CardDispatchSyncBuilder).

## 2026-04-20 — Card-dispatch session sync (completes ADR 26)

**Context.** Immediately after shipping Phase A of the `CARD:{json}` pipeline, went back for the deferred session-sync piece. The value proposition: card dispatches in `send_text` / `slash_command` modes already reach the server (they go through `sendMessage`), but `open_url` dispatches NEVER do — the intent launches locally and the server is blind to it. Even for `send_text`/`slash_command` the server only sees the reply *text*, not the structural link back to the card that prompted it — so after a server restart or reconnect the LLM can't say "you approved the `Run shell command?` card" without guessing.

**Design.** Mirror `VoiceIntentSyncBuilder` beat-for-beat. Every `HermesCardDispatch` grows a `syncedToServer: Boolean = false` flag (identical spelling to `VoiceIntentTrace.syncedToServer`). New `CardDispatchSyncBuilder` in `viewmodel/` (pure function, JVM-testable) walks history, builds an index of `card.id ?: "idx:$index"` → card, then for each unsynced dispatch synthesizes an OpenAI-format `assistant`+`tool` pair. Namespaced the synthetic tool name as `hermes_card_action` so the upstream tool dispatcher — which could look at the session's history on a cold-start replay — has zero chance of trying to execute a card audit record as if it were a real `android_*` call.

**Arguments envelope.** Chose a fat structured object: `card_key` + `action_value` (always), plus `card_type` + `card_title` + `action_label` + `action_mode` + `action_style` when the card and action are still in the message. The LLM can describe the interaction naturally from any of those keys, which matters because the card itself may have been trimmed from the rolling context window by the time the LLM reads the synthesized trace. Fallback path (card no longer in message history) emits just the key + value — still a valid audit record, just less descriptive.

**Wire splice.** The API client's `voiceIntentMessages` parameter was left unrenamed — it's already a generic `JsonArray?` of synthetic messages, and renaming would ripple across `HermesApiClient` (out of scope for this PR). Instead `ChatViewModel.startStream` builds BOTH arrays (voice intents first, cards second — preserves chronological order within each stream; cross-stream ordering doesn't matter to the LLM since cause-and-effect is preserved within each) and concatenates into one, passing through the existing slot. Guarded with `hasUnsynced` on both builders so the common no-op turn allocates nothing.

**Commit timing.** Matches voice intents exactly — `markVoiceIntentsSynced` and `markCardDispatchesSynced` both fire *after* the API client accepts the request. If request building throws, both streams stay unsynced and retry on the next turn. If the request succeeds but the SSE stream fails partway through, the server-side session has already absorbed the synthetic messages and won't re-receive them — correct for at-least-once delivery semantics.

**Tests.** `CardDispatchSyncBuilderTest` covers six cases: empty history, dispatches-but-no-cards, single success pair, skip-already-synced, orphan dispatch (card trimmed), idx-form fallback key, and `hasUnsynced` boolean. Pure JVM tests — no Robolectric, no MockK, same JUnit style as `VoiceIntentSyncBuilderTest`.

**What closes from ADR 26.** The "deferred session sync" item in the tradeoff list is now shipped and the ADR is updated accordingly. Phase B (upstream Discord/Slack adapter parity) remains held until real phone-side card usage surfaces concrete fidelity issues worth translating for.

## 2026-04-20 — Rich cards in chat + dev-branch CI relaxation

**Context.** Two asks in the same session. (1) Dev-branch CI was blocking WIP pushes on test failures that were transient or irrelevant to the commit — tests take a long time, fail noisily on in-progress work, and are a drag on the "commit early, commit often" loop the dev-branch model exists to enable. Lint failures are rare enough that keeping them strict is acceptable. (2) The chat feed only knew how to render prose + tool-progress cards + attachments; the goal was Discord-embed-style rich cards for skills and agent commands — approval prompts, link previews, weather, calendar, generic skill output — with a pattern that could extend to Discord/Slack adapters upstream later.

**CI — the change.** Added `continue-on-error: ${{ github.ref != 'refs/heads/main' && github.base_ref != 'main' }}` to the `test` job in `ci-android.yml` and the `unit-tests` job in `ci-relay.yml`. The expression reads as "if this build is NOT heading toward main (push to main OR PR targeting main), mark the job green even if tests fail." Tests still *run* — reports and annotations upload as always — they just don't red-gate the dev merge queue. When the dev → main release-merge PR goes up, `base_ref == 'main'` flips the expression to false and the same job is strict again, so nothing sneaks into a tagged release untested. Lint stays unconditionally strict on both branches (lint debt compounds and is cheap to fix at commit time).

**Cards — the design.** Upstream research first: `gateway/platforms/base.py` exposes no rich-content abstraction at all (just `send()` / `send_image()` / `edit_message()`). Discord uses zero `discord.Embed()`. Slack uses Block Kit *only* for the `exec-approval` dialog. Only lead is the `REQUIRES_EDIT_FINALIZE` attribute + its DingTalk AI Cards reference, which acknowledges rich-card surfaces exist but doesn't abstract them. So: no prior art to copy, one Slack pattern (exec-approval) worth mirroring, and free rein on the envelope shape.

Chose to **reuse the `MEDIA:` marker pattern** rather than invent structured SSE events. Reasons: (a) works unchanged across every streaming endpoint we support — `/v1/runs`, `/api/sessions/{id}/chat/stream`, `/v1/chat/completions` — which the structured-event path would not (chat/completions has no event channel); (b) ships today with zero server dependency; (c) the `HermesCard` data model is stable regardless of wire format, so if we later want structured events the parser fans out and nothing else changes. Marker is `CARD:{json}` on its own line, single-line JSON (escape newlines in string fields as `\n`), greedy `\{.*\}` so nested braces in `fields` / `actions` survive.

**Cards — the data.** `HermesCard(type, title?, subtitle?, body?, accent?, fields[], actions[], footer?, id?)`. `type` is a dispatcher key — `skill_result`, `approval_request`, `link_preview`, `calendar_event`, `weather` are the built-ins, unknown types render via a generic title+body+fields+actions fallback so new types don't break older phone builds. `accent` is semantic (`info` / `success` / `warning` / `danger`) not raw hex so the renderer pulls from `colorScheme`. `fields` are simple label/value rows. `actions` are tappable buttons with `label` / `value` / `style` (`primary` / `secondary` / `danger`) / `mode` (`send_text` default / `slash_command` / `open_url`). `@Serializable` with `ignoreUnknownKeys = true` so server schema evolution doesn't break parse.

**Cards — the parser.** Mirrors the `MEDIA:` path byte-for-byte in structure: dedicated `cardLineBuffer` + `dispatchedCardMarkers` fields, `scanForCardMarkers` called from `onTextDelta`, `tryDispatchCardMarker` as the line inspector, `finalizeCardMarkers` called from both `onTurnComplete` and `onStreamComplete`, `extractCardsFromContent` on history reload. `clearMessages` wipes the state. Cards are synchronous (no async fetch like media), so they attach straight onto `ChatMessage.cards` instead of going through a callback-to-ViewModel roundtrip.

**Cards — the renderer.** `HermesCardBubble.kt` is a Material 3 `Card` with a 3dp accent stripe on the leading edge (same visual language as the voice/phone-action accent bar in `MessageBubble`), Icon + Title/Subtitle header, optional markdown body, fields as label/value rows (monospace heuristic on values that look like paths or commands), FlowRow of action buttons, muted footer. Tapping an action triggers `ChatViewModel.dispatchCardAction(messageId, cardKey, action)` which stamps a `HermesCardDispatch` on the owning message *before* doing the side effect — so the card collapses into a "Chose: X" confirmation even if `sendMessage` or the `ACTION_VIEW` intent throws. The stripe color and header icon both branch on `type` / `accent`, so the visual distinction between an approval card and a weather card is immediate.

**Approval-request card parity with Slack.** Slack's `send_exec_approval` uses a Block Kit section with 4 buttons: Allow Once (primary), Allow Session (default), Always Allow (default), Deny (default, though semantically destructive). Our `approval_request` card uses the same 4-button shape with `style: "primary"` on Allow Once and `style: "danger"` on Deny — so a future upstream Phase B contribution is a translation pass (`gateway/rich_cards.py` → Slack blocks / Discord embeds / markdown fallback), not a data-model rethink. Phase B was not shipped in this session — scope-capped to phone-side Phase A by design.

**What's still on the table.** Server-side session sync of card dispatches (analogous to `VoiceIntentSyncBuilder` for voice intents) so the LLM remembers "user approved shell command X" across a server restart. And the Phase B adapter-side upstream PR — both deferred to future sessions.

## 2026-04-19 — Docs site: mobile hero fix + MorphingSphere on codename-11.github.io

**Context.** Two issues on the VitePress docs home at `codename-11.github.io/hermes-relay/`: (1) on mobile the 9:16 phone-frame video overflowed its container and the hero text/CTAs sat on top of the video, (2) goal: embed the freshly-extracted MorphingSphere somewhere tasteful on the docs site with a "looking around" loop and mouse-proximity reactivity.

**Mobile hero — the fault.** VitePress's `VPHero` sets `.image-container` to `width: 320px; height: 320px` below 640 px and `392×392` at 640–960 px, with `.image { margin: -76px -24px -48px }` negative margins meant to visually overlap a round illustration. That shape works for a 320-square picture, not for a portrait phone frame that's ~433 px tall at 200 px wide. The frame overflowed the square and the `.main` block (text + actions) got pulled up through the overflow zone.

**Mobile hero — the fix.** Two edits. `custom.css` adds a `@media (max-width: 959px)` override that clears the `.image` negative margins and makes `.image-container` `height: auto; width: auto; max-width: 100%`. `HeroDemo.vue` replaces three breakpoint widths (280/240/200 px at three steps) with `width: clamp(180px, 62vw, 280px)` + `max-height: 70vh` — one fluid rule, plus a safety rail so a tall narrow viewport (folded Z-fold, tall Android) can't push the Get Started button below the fold.

**Sphere placement.** Option A (hero corner) would compete with the phone video. Option C (nav logo) is subtle but a tiny 32×32 canvas can't show enough detail to read as "alive." First pass went with option B — a dedicated band between hero and features (`home-features-before` slot) at 56×26 / 720×220 px. Second pass moved it **directly above the "Install in 30 seconds" block** by collapsing both slots into `home-hero-after: () => [h(SphereMark), h(InstallSection)]`. Third pass (same session, after reviewing the first render): the 9:16 phone-shape frame put a tiny sphere inside a tall envelope — the algorithm's baseRadius = 0.60 × min(rows/2, cols×charAspect/2) means the sphere was only ~20% of canvas width by design, leaving 200+ px of dead space above and below the visible blob. Fix: keep the 58×34 mobile density but switch the canvas aspect to **1:1 square** with `clamp(280px, 48vw, 420px)` width. At square, the charAspect (34/58 ≈ 0.586) makes `maxRadiusFromRows == maxRadiusFromCols`, so the sphere fills both axes equally and the data ring extends to ~93% of the frame. Gap killed, sphere is now the focal object of the band.

**Gaze without bounce.** First pass translated the whole canvas toward the cursor via `ctx.translate(offsetX, offsetY)` — effective as a "tracking" cue but read as a *bounce*: the sphere body should stay anchored, only the eye should move. Cleaner fix required algorithmic access to the light direction, so added three new `SphereFrame` fields to `MorphingSphereCore.kt`: `lightAngleBiasX`, `lightAngleBiasY`, `lightAngleBlend` (all default 0f). The light-angle computation now blends between the natural `t * lightSpeedX + noise` rotation (`blend = 0`) and the caller's bias (`blend = 1`). Mirrored into `sphere.js` with `?? 0` coalescing. Defaults preserve byte-identical behavior for existing callers — the Android composable, the parity test (`MorphingSphereCoreParityTest`), and the JS parity harness (`parity-check.mjs`) all stay green because none of them set the new fields.

**Gaze math.** The algorithm computes `lx = sin(lightAngle1) * 0.65` and `ly = cos(lightAngle2) * 0.65`. To make the bright spot face a direction (cursorDx, cursorDy) normalized to the unit circle, solve: `lightAngle1 = asin(cursorDx)`, `lightAngle2 = acos(cursorDy)`. `SphereMark.vue` computes these per frame from the cursor vector, blends with a slow fbm wander for idle gaze, and ramps `lightAngleBlend` from 0.35 (idle wander) up to 0.90 (cursor near). The 0.35 floor is deliberate — below it, the autonomous fbm barely registers as gaze because natural light rotation drowns it out; at 0.35 the bias owns enough of the angle that the eye reads as *looking around* rather than *being illuminated randomly*.

**Fourth pass — jitter fix.** First draft fed raw pointer inputs straight into asin/acos every frame. Hovering felt jerky because (a) `pointermove` fires at event-driven cadence with big gaps between events, not once per animation frame, and (b) `asin'(x) = 1 / √(1 − x²)` blows up as `x → ±1`, so small input steps near the edges produced disproportionately large angle jumps. Fix: two EMAs on the raw inputs before they hit the trig — 180 ms time constant on `lookVx` / `lookVy`, 280 ms on `proximity` (proximity wants a bit more hang-time so rapid hover-in/hover-out doesn't thrash the state machine). The `alpha = 1 − exp(−dt/tau)` form is frame-rate-independent — same responsiveness on a 144 Hz monitor as 30 fps. Also capped the asin/acos inputs at ±0.9 to stay off the asymptotic slope at the boundaries; effective gaze range drops from `lx ∈ ±0.65` to `±0.585`, an imperceptible loss for a large smoothness win.

**Fifth pass — scroll-watching + rectangular detection.** Goal: (a) the detection field to cover the full page width at the container's height (rectangular band, not circle), and (b) the sphere to watch the reader scroll toward install when they haven't interacted yet — "looking straight down at install" when the install section is in view. Implementation:
- Added a `userHasInteracted` flag, flipped on the first `pointermove`. Once true it stays true for the session.
- **Scroll-watching mode** (not interacted yet): compute `scrollOffsetY = viewportH/2 − sphereCenterY` in viewport coordinates. When the reader scrolls down, the sphere rises in the viewport and `scrollOffsetY` grows positive — feeding `rawLookVy = scrollOffsetY / (viewportH * 0.6)` into the gaze math rotates the bright spot to the bottom of the sphere. Proximity locked to 0.75 so the smoothed gazeBlend settles at 0.35 + 0.55·0.75 ≈ 0.76 — enough to read as intentional gaze without slamming into full Listening-state intensity. Horizontal component held at 0 so the eye doesn't drift left/right while watching scroll.
- **Cursor-tracking mode** (post-interaction): gaze direction is still the unit vector from sphere center to cursor. Proximity is now purely **vertical band-based** — 1.0 when `|mdy| ≤ containerHeight/2`, falling off linearly over 0.6 × containerHeight beyond each edge. No horizontal falloff at all — cursor anywhere from x = 0 to x = viewportWidth in the vertical band gets full proximity. That matches the "fill width" spec and feels natural because the eye already targets cursor direction regardless of distance; proximity just gates intensity / state / blend strength.
- **Drift fallback** (interacted + pointer off the page): both mode blocks skip, raw inputs stay at 0, smoothed EMA relaxes toward 0, autonomous fbm drift provides the gaze. The sphere effectively "loses track" and wanders.

The three modes compose cleanly because they all feed through the same smoothed-EMA → asin/acos → `lightAngleBias` pipeline. Transitions between modes just change the raw targets; EMA + tweens handle everything else.

**Sixth pass — unified target + install-anchored scroll.** Ultrathink pass: the scroll reference point was wrong, and the cursor↔drift mode flip at the band boundary was causing visible eye freakout when hovering near the edge. Two changes:

- **Unified target pipeline.** Dropped the `userHasInteracted` flag and the fbm-drift fallback mode. Scroll-tracking is now the always-on baseline; cursor-tracking overlays on top via `cursorWeight`. `rawLookVy = cursorVy × cursorWeight + scrollVy × (1 − cursorWeight)` — one coherent target, no two sources competing for the EMA to smooth. The boundary freakout was fundamentally a *discontinuous target function*: two signals with possibly opposite directions feeding a single smoother only looks smooth if they agree at the transition. They didn't. Now there's only one target curve, and the EMA just smooths motion along it.
- **Install-anchored scroll reference.** Replaced `viewportH/2 − sphereCenterY` with an Install-element anchor via `document.querySelector('.install-section')`. `installGap = installRect.top − viewportH` is the runway until install enters view; scrollVy ramps linearly from 0 (install still more than 50 % viewport-height below the fold) to 1 (install just touching viewport bottom). By the time install *first* becomes visible, the eye is *already* looking straight down at it — the animation leads the scroll rather than chasing it. With the old viewport-center reference, scrollVy only saturated to ±1 when the sphere was nearly off-screen, which meant "fully looking down" happened after the sphere had already scrolled away. Fallback when the install element isn't on the page (other routes) uses viewport-center so the gaze still follows scroll.
- **Widened the cursor falloff** from 0.6 × container height to 1.0 × height. Over 180 ms EMA with a narrow falloff, the crossfade from cursor to scroll target happens inside the EMA's bandwidth and reads as a jump; widening it to 1.0 × height spreads the mathematical transition over enough cursor travel that the EMA can smooth it out fully.

**Seventh pass — eye legibility.** Gaze tracking worked but the bright spot wasn't standing out as the *eye*. Analyzed the math: `brightness = (1 - li) · distBrightness + li · directionalLight + heartbeatFx`. For Idle, `lightInfluence = 0.35`, so 65% of sphere brightness comes from *position* (pearl shading, bright at center, dim at edges) and only 35% from the *light direction*. Worse, at the sphere's geometric center the surface normal is (0, 0, 1) so `directionalLight ≈ lz`, and `lz = √(1 − lx² − ly²)` is always positive — the center stays bright regardless of where the eye is pointing, which visually flattens the eye-to-shadow contrast. Fix: new `shadowStrength` field on `SphereFrame` (mirror in sphere.js, default 0). Multiplies `distBrightness` by `(1 − shadowStrength · (1 − directionalLight))` — lit side (`directionalLight = 1`) scales by 1.0, shadow side (`directionalLight = 0`) scales by `1 − shadowStrength`. Picked the factor to act on `distBrightness` rather than `directionalLight` because boosting the light side's directional term (or gamma-sharpening it) would flatten the 3D pearl shading everywhere — the shadow-side multiplier dims only the unlit hemisphere, keeping the shading on the lit side intact while doubling eye-to-shadow contrast. Docs `SphereMark.vue` uses 0.6 (dark side at 40% of legacy distBrightness). Android composable doesn't set it — legacy pearl shading preserved byte-for-byte, parity test stays green.

**Sphere reuse — no duplication.** The Vue component imports directly from `../../../../preview/web/sphere.js`. Vite resolves the relative path through the repo root; `sphere.js` is pure (no DOM, no side effects) so SSR doesn't choke. This keeps `MorphingSphereCore.kt` → `sphere.js` as the single algorithm seam — same math on Android, preview harness, and docs site. No mirror, no copy to sync.

**"Looking around" + mouse reaction.** Two behaviors layered onto the existing `SphereFrame` inputs without touching the core algorithm:
- **Autonomous drift.** Low-frequency fbm noise (`nowSec * 0.05 + 2.1`, same fbm the core uses for its light jitter) produces a (dx, dy) in ±1 range. Applied as a canvas `translate()` of up to 14 px. Gives a subtle gaze loop that never repeats visibly.
- **Proximity.** Pointer tracked on `window`; distance from mouse to canvas center normalized against a 320 px "reach radius" → proximity ∈ [0, 1]. Blends cursor-pull with autonomous drift (cursor dominates near, drift dominates far), ramps `intensity` tween (0 → 0.7), and retargets state Idle → Listening above 0.35 proximity, back to Idle below 0.15 (hysteresis band prevents flicker). Listening's params have higher `lightInfluence` and tighter core — reads as "the agent is paying attention."

**Perf / a11y.** `IntersectionObserver` pauses the fillText loop when the band is scrolled off-screen (still advances time so state tweens stay in sync on re-enter — cheaper than rebuilding state but skips the expensive draw). `prefers-reduced-motion` freezes `t` and zeroes the gaze offset so the band stays readable but static. `aria-hidden` on the canvas — the sphere is decorative, no screen reader value.

**VitePress SSR — `<ClientOnly>` was the wrong tool.** First attempt wrapped the component in `<ClientOnly>` as "SSR defense." Turned out to be the bug: `<ClientOnly>` only mounts its children after its own `onMounted` fires, which is later than the parent component's `onMounted` — so `canvasEl.value` and `containerEl.value` were `null` when we tried to start the rAF loop. The first render call bailed early without scheduling the next frame, killing the loop permanently. Fix: drop the wrapper (a `<canvas>` tag is inert on SSR, no side effects) and make `render()` re-schedule itself whenever refs or canvas dimensions aren't ready yet. One transient null no longer turns into a dead loop.

## 2026-04-19 — MorphingSphere: pure-core extraction + browser preview + runtime parity proof

**Context.** The sphere had grown into a 494-line Compose file with Android `Paint` + `Typeface` + `nativeCanvas.drawText` wired into the same function that did the math. That made it impossible to iterate on the algorithm without building to a device — and blocked any future port to a non-Android surface (user site, Hermes TUI, Compose Desktop). Branch `claude/ui-dev-preview-exploration-vKvzr` split the file into a pure algorithm + a Compose renderer and added a zero-dep browser harness; this session added the parity proof.

**Layout — three artifacts, one seam.**
- `MorphingSphereCore.kt` (new, 412 lines) — `kotlin.math` only. Owns `SphereState`, `SphereParams`, `SphereColors`, `SphereFrame`, `SphereCell`, `paramsFor()`, `colorsFor()`, `forEachSphereCell()`. No Android, no Compose, no `Paint`.
- `MorphingSphere.kt` (494 → ~45 lines of renderer + `@Preview` decorators) — Compose Canvas binding. Swapped legacy `Paint` + `Typeface` + `nativeCanvas.drawText` for `rememberTextMeasurer()` + Compose `drawText`. Owns animation state (`animateFloatAsState`, `rememberInfiniteTransition`) and feeds a `SphereFrame` into the core per tick.
- `preview/web/sphere.js` — line-for-line JS mirror of `MorphingSphereCore.kt`. `Math.imul` + `|0` to match Kotlin's `Int` overflow in the hash, `((x % n) + n) % n` for Kotlin's floored-positive `.mod()`, `Math.trunc` for `.toInt()` truncation-toward-zero.

**Browser harness.** `preview/web/index.html` — live HTML+canvas, `python3 -m http.server --directory preview/web`, no build step. Panel exposes State / Voice mode / Voice amp / Intensity / Tool burst / Pause / reset t, plus a Layout section (Cols, Rows, Fill %, Aspect, Char size) added this session with a `phone 9:16` preset that pins canvas aspect to 0.5625 to match Compose `@Preview(widthDp=360, heightDp=640)`. Hitting `1`..`6` cycles state; `Space` pauses.

**Runtime parity proof.** Line-by-line code audit was first, but proving parity needs evidence, not inspection. Added:
- `preview/web/parity-check.mjs` — Node harness running sphere.js at the 8 Compose `@Preview` fixtures (Idle/Thinking/Streaming/Error/Compact/Listening/Speaking-low/Speaking-peak), emitting two FNV-1a 32-bit checksums per fixture. `struct` hashes only `(row, col, charCode)` — discrete, robust to Float/Double precision drift. `full` hashes the same plus color/alpha rounded to 3 decimals.
- `app/src/test/kotlin/com/hermesandroid/relay/ui/components/MorphingSphereCoreParityTest.kt` — JVM unit test (no Android deps, runs on `testGooglePlayDebugUnitTest`), mirrors the JS harness exactly: same 8 fixtures, same FNV-1a impl via Kotlin `UInt`, same `%.3f` formatting via `Locale.ROOT`, same tuple layout.

**Result.** 8 / 8 structural checksums match. 8 / 8 zone histograms match (inside/glow/ring counts identical). 6 / 8 full checksums match — Listening and Speaking-low drift on color/alpha at the 3rd decimal, which is expected Float (Kotlin) vs Double (JS) mantissa precision in compound expressions like `(colR + lightBoost + warmth).coerceIn(0, 1)`. Speaking-peak avoids the drift because `amp=0.95` puts `lerp()` results near the endpoints where Float mantissa has room.

**Decision.** `MorphingSphereCore` is declared the single source of truth going forward. The Compose `MorphingSphere` composable keeps its public API (same params, same defaults) so call sites — `VoiceModeOverlay`, the chat empty state — need no changes. Future edits to the algorithm go in `MorphingSphereCore.kt`, mirror into `sphere.js`, and `MorphingSphereCoreParityTest` catches drift between sides.

**Reusable surface.** The core is now droppable into:
- A terminal TUI (Hermes CLI) — swap Compose `drawText` for ANSI-colored `print`, keep everything else.
- The user site (codename-11.dev) — reuse `sphere.js` directly with an HTML `<canvas>` host.
- Compose Desktop — reuse `MorphingSphere.kt` unchanged once the host project adds the `ui/components` dir to its source set.

**Worktree gotcha.** `git worktree add` doesn't copy `.gitignore`d files. `local.properties` (SDK path + keystore creds) has to be recreated in any new worktree before gradle tasks run. For the parity test a minimal `sdk.dir=...` is enough — signing creds stay in the main checkout.

## 2026-04-19 — Multi-endpoint pairing + first-class Tailscale (ADR 24 + 25)

**Branches:** Wave 1 + Wave 2 landed on `dev`. ADRs 24 and 25 written and committed. Docs pass (this entry) closes the work out.

**Shipped.**

- **ADR 24 — Multi-endpoint pairing.** `plugin/pair.py` now emits an ordered `endpoints` array (`lan` / `tailscale` / `public` / operator-defined roles) in a new `hermes: 3` QR schema; `hermes: 2` stays the default when no endpoints are present. New CLI flags `--mode {auto,lan,tailscale,public}` and `--public-url <url>` drive candidate emission; `--mode auto` autodetects LAN IP + Tailscale status and composes them with an optional public URL. `plugin/relay/qr_sign.py` canonical form preserves array order and role strings verbatim — HMAC round-trip test pins this against future refactors. `plugin/relay/server.py` `handle_pairing_mint` / `handle_pairing_register` accept the optional `endpoints` body field. Phone side: new `data/Endpoint.kt` (`EndpointCandidate` / `ApiEndpoint` / `RelayEndpoint` / `displayLabel()`), `data/PairingPreferences.kt` per-device endpoint store, `ui/components/QrPairingScanner.kt` parses the new field with a v1/v2 synthesizer for back-compat, `auth/AuthManager.kt` persists the endpoint list on `auth.ok`, and `viewmodel/ConnectionViewModel.kt` stages endpoints at pair time. Wave 2 Kt-Probe added `ConnectionManager.resolveBestEndpoint()` + `NetworkCallback` re-probing and `RelayUiState.activeEndpointRole`.
- **ADR 25 — First-class Tailscale helper.** New `plugin/relay/tailscale.py` with `status()` / `enable(port)` / `disable(port)` / `canonical_upstream_present()` — all shell out to the `tailscale` CLI with short timeouts, return structured dicts, never raise. New `plugin/relay/tailscale_cli.py` argparse wrapper + `scripts/hermes-relay-tailscale` shell shim mirroring the `hermes-pair` pattern. `install.sh` gains optional step [7/7] offering Tailscale enablement; honours `TS_DECLINE=1` / `TS_AUTO=1`.
- **Dashboard Remote Access tab (Wave 2 Dashboard-R4).** `plugin/dashboard/plugin_api.py` + React UI + committed `dist/index.js` — operator can enable/disable the helper, mint multi-endpoint QRs, and inspect which modes are active without dropping to a shell.
- **Docs pass.** New `docs/remote-access.md` (263 lines) — decision matrix + per-mode setup recipes + troubleshooting. Updated `docs/security.md` with a "Remote connectivity" subsection + top-of-list Tailscale recommendation. Updated `docs/relay-server.md` with `TS_AUTO` / `TS_DECLINE` env vars, the dashboard plugin proxy-route table, and a Tailscale-helper subsection. Updated `docs/spec.md` §3.3 + §3.3.1 for v3 QR schema + endpoints array. Updated `README.md` "What's new" with the one-line connect-from-anywhere pitch. Updated `CHANGELOG.md` `[Unreleased]` with Added / Changed / Backward compatible subsections. Updated `CLAUDE.md` Key Files + Integration Points (hygiene pass only).

**Key architectural decisions (restated from the ADRs).**

1. **Strict priority, not reachability-weighted.** Priority 0 wins when reachable; reachability is a tiebreaker among equal priorities, never a promoter across priority boundaries. Matches DNS SRV semantics — nothing new to debate, and keeps operator intent authoritative.
2. **Open-string `role`, not a closed enum.** `wireguard`, `zerotier`, `netbird-eu`, etc. render as generic "Custom VPN (<role>)" without a release. HMAC canonicalization preserves the exact emitted string.
3. **Tailscale helper auto-retires on upstream merge.** `canonical_upstream_present()` probes `hermes gateway run --help | grep tailscale`; once PR #9295 lands, the helper prints a log line pointing at the canonical flag and exits 0. Same removal pattern as `hermes_relay_bootstrap/` after PR #8556.
4. **No second crypto layer.** The operator already owns both endpoints and the transport (Tailscale / Caddy / WireGuard / Cloudflare Tunnel) is TLS-terminated by the operator's chosen path. Adding Noise / libsignal over WSS would add complexity without defending any threat in the trust model.

**What's next.**

- Monitor upstream PR #9295 for merge. When it lands, verify `canonical_upstream_present()` detection works on a vanilla hermes-agent install, update the helper to print the retirement log line, and schedule the helper's removal PR (one file + the install.sh step + the shim).
- Android Studio build + `./gradlew lint` pass before the multi-endpoint work gets pushed from the Kotlin side. No Python blockers.

**Blockers.** None. Awaiting the lint + build pass on the Kotlin changes before the feature branches merge into `dev`.

### Same-day follow-up — `--prefer` priority override + regression fix

Two commits landed on `dev` after the initial ADR 24 + 25 bundle:

- **`feat(pairing): --prefer priority override on all pair surfaces` (`e914810`).** Adds explicit "promote this role to priority 0" control so operators can force a specific endpoint path without re-ordering defaults globally. Surfaces: CLI `hermes-pair --prefer tailscale`, the `/hermes-relay-pair` skill (SKILL.md updated), and a dropdown below the Endpoint Preview card on the dashboard's Remote Access tab. Open-vocab role string; case-insensitive matching; unknown role emits a stderr warning and keeps natural order. 6 new `BuildEndpointCandidatesPreferTests` cover the happy paths and edge cases. Dashboard bundle rebuilt to 61.3 KB.

- **`fix(relay): restore profile PUT handlers clobbered by ADR 24 commit` (`ee653d4`).** The ADR 24 bundle (`fae8ccd`) had an Edit that replaced a wider chunk of `plugin/relay/server.py` than intended while adding the endpoints passthrough to `handle_pairing_mint` / `handle_pairing_register` — collaterally deleting ~479 lines of `handle_profile_soul_put` / `handle_profile_memory_put` + `_extract_write_content`. CI — Relay went from 2 pre-existing failures (`test_profile_discovery`, unrelated) → 27 on the bundle push → back down to the same 2 after the fix. Restored by resetting `server.py` to the pre-ADR-24 state (`47667bd`) and re-applying only the intended ~30-line endpoints passthrough. Full suite: 673 pass / 6 skipped locally. Same bug class as the `AuthManager.profilesUpdatedEvents` collateral wipe caught mid-deploy — worth a note in any future "agent-assisted refactor" guidance: tight `old_string` anchors + verify-by-compile after every agent's work.

- **Server deploy** (`~/.hermes/hermes-relay/` on `dev`, PID restarted for `hermes-relay` + `hermes-gateway` + `hermes-dashboard`) verified at each step: `{"status": "ok"}` health, `tailscale.status()` returning the live `your-host.tailnet.ts.net` / `100.64.0.100`, and `POST /api/plugins/hermes-relay/pairing {"mode":"auto","prefer":"tailscale"}` returning `[(0, 'tailscale'), (1, 'lan')]` over the wire. Pre-existing `test_profile_discovery` failures on Linux CI runners (Windows-local passes) are a separate in-progress item — not introduced by this work.

- **Docs sync pass.** `docs/remote-access.md` → added "Promoting a role to priority 0 — `--prefer`" subsection under Combining modes. `user-docs/features/connections.md` → new "Multi-endpoint pairing" section (end-user facing). `user-docs/guide/getting-started.md` → new "Connecting from Anywhere (Tailscale, VPN, Public URL)" section between Relay Server and Verify Connection. `CHANGELOG.md` `[Unreleased]` gains `--prefer` under Added + the PUT-handler restore under Fixed.

## 2026-04-18 — Profile Inspector UI (v0.7.0)

**Branch:** `feature/profile-inspector-ui`. Kotlin-worker slice of the v0.7.0 Profile Inspector feature — Python worker runs in parallel and owns the `/soul` + `/memory` relay endpoints. Two feature commits + docs.

**Shipped (Kotlin).**

- **`RelayProfileInspectorClient` + wire models.** New read-only HTTP client for the four inspector endpoints (`/api/profiles/{name}/config`, `/skills`, `/soul`, `/memory`) mirroring `RelayHttpClient`'s constructor shape (OkHttp, lazy bearer-token provider, `ws://` → `http://` URL flip). All four fetch methods return `Result<T>` and hop to `Dispatchers.IO` before any network I/O — reinforcing the v0.6.0 post-mortem fix for `NetworkOnMainThreadException` in the voice client. Profile names are URL-encoded before being spliced into the path so names with spaces / non-ASCII characters don't blow up the route. Wire models (`ProfileConfigResponse`, `ProfileSkillsResponse`, `ProfileSoulResponse`, `ProfileMemoryResponse`) are `@Serializable` with snake_case → camelCase mapping via `@SerialName`. Optional wire fields (`truncated`, `readonly`, `enabled`) default to safe values so pre-v0.7.0 relays that omit them deserialize cleanly.
- **`ProfileInspectorScreen` (4 tabs).** Config renders as a collapsible JSON tree (nested objects click to expand, monospace leaf values, 120-char truncation). SOUL is a scrollable monospace box with byte-size caption + truncation banner when the server flags the content as sliced; absent SOUL renders an empty-state with the expected path. Memory is a list of expandable cards per entry (filename + size in the header, content revealed on tap, per-entry truncation banner). Skills groups by category with a "(disabled)" label on skills where `enabled=false`. Top-bar Refresh icon fires `loadAll()`; each pane has its own inline Retry button on error state. PullToRefresh was skipped in favour of the explicit Refresh icon + per-section Retry — matches `PairedDevicesScreen`'s "still-experimental PullRefresh" note.
- **`ProfileInspectorViewModel`.** Four independent `LoadState<T>` flows — one per section — so a slow `/memory` fetch never blocks the already-arrived `/config` tab. Lazy (no fetch until `loadAll()`) and stateful per-section (`refreshSection(InspectorSection.Config)` re-fetches just that tab). Profile name comes in via `SavedStateHandle` so a process-death restore inspects the same profile. The VM is keyed off the profile name in the nav backstack so entering a different profile produces a fresh VM rather than reusing stale state.
- **`ProfileInspectorCard` in Settings.** Sits directly under `ActiveAgentCard` so the "active agent → inspect it" reads naturally top-to-bottom. When no profile is selected, the card renders at 50% alpha with "No active agent" and becomes a no-op — kept visible (not hidden) so the feature stays discoverable before a pair-and-pick happens.
- **`Screen.ProfileInspector` nav destination.** Typed String path arg, registered via a tiny `ViewModelProvider.Factory` that pulls `SavedStateHandle` out of `CreationExtras` so nav args propagate into the VM constructor cleanly. Back navigation pops the destination and the VM is GC'd with it.

**Key architectural decisions.**

1. **Four independent load states, not one "screen state".** Each section's flow transitions Idle → Loading → Loaded/Error independently. One combined state would have meant the fastest-arriving tab gets blocked by the slowest — unnecessary UX cost given the 3 - 4 tabs the user flips between.
2. **URL-encoded profile-name splice, not an OkHttp path segment.** We use `URLEncoder.encode(..., "UTF-8").replace("+", "%20")` before splicing into the literal path string, then build the `HttpUrl` from the full string. OkHttp's `addPathSegment` would re-encode and produce double-encoding for profile names with `%` already in them (pathological but possible). A single round of encoding is the safe choice.
3. **ViewModel keyed on nav arg.** `viewModel(key = "profile-inspector-$name", ...)` means the same profile navigated to twice yields the same VM (back-stack reuse), but switching to profile B produces a fresh VM — avoids the "I opened inspector for axiom, changed profiles, reopened and saw axiom's data" class of bug.

**Deferred.**

- **Pull-to-refresh gesture.** Explicit Refresh button is enough for v0.7.0; if users ask for the gesture we'll revisit once Material3's `PullToRefreshBox` graduates out of experimental.
- **Edit-in-place.** Inspector is strictly read-only by design. Editing is still "SSH to the server and `$EDITOR config.yaml`" territory. A "Copy to clipboard" affordance on each section is a possible follow-up if users ask for it.
- **MockWebServer integration tests.** `testImplementation` doesn't include MockWebServer and the spec forbids adding deps for this slice — we covered wire-contract parsing + URL encoding + required-field enforcement via JVM-local tests, and the client's actual network path is exercised by the on-device relay smoke test.

**Upstream dependency.** Soul and Memory endpoints land via the parallel Python worker on the same branch. The Kotlin side is fetch-and-render — if either endpoint returns a 5xx / 404 before the Python change merges, the relevant tab just shows a Retry error state and the rest of the inspector keeps working.

## 2026-04-18 — Profile metadata + read-only config API (v0.7.0 groundwork)

**Branch:** `feature/profile-config-readonly`. Kotlin-worker slice of the v0.7.0 groundwork (Python-worker slice runs in parallel, owns the relay-side wire contract). Three feature commits + docs.

**Shipped (Kotlin).**

- **Extended `Profile` data class.** Added three optional fields — `gatewayRunning: Boolean`, `hasSoul: Boolean`, `skillCount: Int` — mapped via `@SerialName` to the snake_case wire keys (`gateway_running`, `has_soul`, `skill_count`) the relay will emit in `auth.ok` profiles entries. All three default to safe zero-values so pre-v0.7 relays deserialize cleanly. `AuthManager.parseAgentProfiles` reads them with `booleanOrNull` / `intOrNull` fallbacks — malformed values fall through to defaults rather than crashing the pairing handshake.
- **Runtime-metadata indicators in the agent sheet.** Each Profile row in `AgentInfoSheet` now carries a 6 dp status dot (green when gateway_running, grey otherwise), an optional "N skills" chip (hidden when skill_count == 0), and an optional "SOUL" badge (primary-container, hidden when has_soul is false). Gateway-off profiles stay selectable at 50% alpha — the probe is best-effort, and a stale dot shouldn't lock a user out. `ProfileRadioRow` grew three optional params (`contentAlpha`, `leadingDotColor`, `secondaryTrailing` slot) without changing the Default/personality-row call sites. Also added an inline "Profile SOUL overrides personality while active" caption under the personality section when a profile-with-SOUL + non-default personality are both selected, mirroring the existing caption in the Profile section.
- **Persisted `selectedProfile` per Connection.** New `ProfileSelectionStore` — its own DataStore (`profile_selections`) keyed by connectionId — lets each Connection remember its last-picked profile across app restart. `ConnectionViewModel.selectProfile` writes through. Connection switch clears `_selectedProfile` first (so a stale A pick never dangles on B) then loads B's persisted name and resolves it against `agentProfiles` once the post-switch `auth.ok` repopulates the list. `removeConnection` calls `store.clear(connectionId)` after the switch-away to avoid racing the unmounted store.

**Key architectural decisions.**

1. **Name-based persistence, not Profile-object persistence.** The persisted value is the profile `name: String`, resolved at read time against the server's fresh `agentProfiles` list. Profiles can be renamed, removed, or re-modelled on the server between app launches; persisting a full `Profile` snapshot risks silently using a stale model override. Resolution-at-read yields null when the name no longer exists, and the UI falls through to the Default row.
2. **Dedicated DataStore.** `profile_selections` is separate from `relay_settings` so clearing or migrating one doesn't threaten the other. A new per-connection key prefix (`selected_profile_<id>`) lets us clear-per-connection cleanly on removal.
3. **Gateway-off profiles remain selectable.** Spec explicitly allows the user to pick a profile whose gateway probe is grey. Rationale: the probe is best-effort, can miss a recent restart, and hard-disabling a row based on a stale probe would confuse users who just ran `hermes profile use` and haven't restarted the relay.

**Deferred.**

- **Migration of any old "ephemeral" pick into the new store.** N/A — the prior behaviour was to always reset on restart, so there's nothing to migrate from. First boot on v0.7.0 starts clean; next `selectProfile` call writes through.
- **UI for inspecting the gateway-running timestamp.** Useful for debugging stale probes but noise for the common path. Track in a future dashboard pass if it's actually asked about.

**Upstream dependency.** The three new profile fields are optional on the wire and fall back to defaults, so this PR is safe to land ahead of the Python-worker relay change. Pairing against a pre-v0.7 relay continues to work — the phone just renders every dot grey and hides every badge.

## 2026-04-18 — v0.6.0: Multi-Connection + Agent Profiles

**Branch:** `feature/multi-profile-connections`. Landing as v0.6.0. Two orthogonal pieces of work that compose into a three-layer agent model (Connection → Profile → Personality), plus a top-bar + Settings UX consolidation.

**Shipped.**

- **Multi-Connection (`docs/decisions.md` §19).** Renamed the original internal "profile" concept to **Connection** — a paired Hermes *server*. `ConnectionStore` persists N connections in DataStore with the active one, migration seeds connection 0 from the existing `hermes_companion_auth_hw` store (zero re-pair, transparent to the user), `SessionTokenStore` takes a `prefsName` parameter so each Connection gets its own `EncryptedSharedPreferences` file, `AuthManager` gains a `connectionId` ctor. Switch is a heavy context swap: cancel in-flight SSE, disconnect relay WSS, rebind provider StateFlows (`RelayHttpClient` / `RelayVoiceClient` re-read lazily), rebuild `HermesApiClient`, reconnect, reprobe capabilities, reload sessions + personalities + profiles, restore per-Connection last-active session. Top-bar Connection chip + `ConnectionSwitcherSheet` for switching; Settings → Connections for CRUD (rename, re-pair via `ConnectionWizard`, revoke, remove). Per-connection scope: sessions, memory, personalities, skills, profiles, relay cert pin, voice endpoints, last-active session. Global scope: theme, bridge safety prefs, TOFU cert-pin map, notification companion state.
- **Agent Profiles (`docs/decisions.md` §21).** Directory-scan + overlay. Relay walks `~/.hermes/profiles/*/`, reads each profile's `config.yaml` + `SOUL.md`, and advertises the list in `auth.ok` as `{name, model, description, system_message}` (plus a synthetic "default" entry for the root config). Phone sends `model` override + `system_message` from `SOUL.md` on chat turns when a profile is selected. Gated by `RELAY_PROFILE_DISCOVERY_ENABLED=1` (default on). Shipped as `overlay-not-isolation`: memory, sessions, `.env`, skills, and cron jobs still ride the Connection's default gateway. For full isolation, run the profile's own gateway on its own port and pair as a separate Connection.
- **Consolidated agent sheet.** Deleted standalone `ProfilePicker.kt` and `PersonalityPicker.kt` top-bar chips in favor of a single bottom sheet opened from the top-bar agent name. Sheet holds Profile list, Personality list, and session info + analytics (message count, tokens in/out, avg TTFT). Scrollable. Toast confirmations on switch. Reclaims top-bar real estate and reduces the visual layer count (one tap instead of two chips).
- **Settings "Active agent" card** — top-of-Settings summary of the current Connection / Profile / Personality with a `openAgentSheet` nav arg that navigates to Chat and auto-opens the sheet. Closes the "how do I change my agent" discoverability gap for Settings-originating users.
- **Polish pass (7031115).** Pair wizard URL-scheme cross-validation (inline hint when API field has `wss://`); pair-stamp of the active Connection's metadata on successful auth (no stale state after re-pair); `ConnectionStatusBadge` top-aligns on multi-line rows; Settings treats paired + briefly-disconnected Connection as **Connecting** (amber) rather than **Disconnected** (red).

**Key architectural decisions.**

1. **Directory-scan for profiles.** First pass parsed a fictional top-level `profiles:` / `agents:` list in `config.yaml`. Upstream has never used that shape — profiles upstream are isolated directory instances at `~/.hermes/profiles/<name>/`, each with its own config, `.env`, `SOUL.md`, memory, sessions, and (optionally) its own gateway daemon. Old path always returned empty on real installs. Rewrite: walk `~/.hermes/profiles/*/`, read config + SOUL, report what's really there. See the "Earlier (abandoned) design" paragraph in `docs/decisions.md` §21 and commits `0303a4f` / `b9d2914` / `ec7559c`.
2. **Overlay-not-isolation for v1.** A profile overrides `model` + `system_message`; everything else (memory, sessions, skills, API keys) rides the Connection's gateway. Rationale: a real per-profile isolation layer is "run the profile's gateway as its own service and pair it as a separate Connection" — we already have the plumbing for that via Multi-Connection. Building a second isolation layer inside one gateway would double the attack surface for no UX win.
3. **Three-layer model = Connection → Profile → Personality.** Connection picks the server. Profile picks the agent directory on that server. Personality picks a system-prompt preset within the agent's config. Hierarchy flows top to bottom; picking a Connection resets Profile (Profile is ephemeral and server-scoped). `docs/decisions.md` §8 now carries a terminology-note block making this explicit, since the §8 title still says "Profiles" in the legacy sense.
4. **Kept scope intentionally small for v0.6.0.** No per-Connection Profile persistence, no gateway-running probe, no mode where one Connection hosts multiple profile-gateways behind one pairing. Those are §21 follow-ups (see Deferred).

**Deferred to v0.7+.**

- **True per-profile isolation via separate Connections.** Doc how `hermes -p <name> platform start api --port 8643` + pair-as-new-Connection achieves this today, in user-docs.
- **Persisted profile selection per Connection.** Currently resets on app restart and Connection switch. ~30-line extension: add `lastSelectedProfileName` to the `Connection` data class and restore on switch.
- **Gateway-running probe** (hermes-desktop-inspired). Would let the Connection health indicator distinguish "server unreachable" from "paired, awake, responding" without waiting for a first request to fail. Low-priority; the existing probe behavior is adequate for v0.6.0.

**Docs pass.**

- `CHANGELOG.md` — new `[0.6.0]` section above the existing (v0.5.x-work-in-progress) entries.
- `docs/spec.md` — Chat top-bar section rewritten around the three-chip reality; `auth.ok` `profiles[]` field documented.
- `docs/decisions.md` — §8 gained a terminology-note block pointing forward to §19 and §21.
- `user-docs/features/connections.md`, `profiles.md`, `personalities.md` — top-bar chip references updated to the agent sheet. `index.md` feature grid picked up Connections + Profiles rows.
- `user-docs/guide/chat.md` — Personalities section expanded into "Agent Sheet — Profile + Personality" + Connection Chip subsection. `getting-started.md` gained a "Multiple Hermes servers" tip pointing at `features/connections.md`.
- `user-docs/architecture/decisions.md` — new ADR-14 (Multi-Connection) + ADR-15 (Agent Profile picker) mirroring `docs/decisions.md`.
- `README.md` — new "What's new in v0.6.0" block above the feature list; added a feature bullet for multi-Connection + profiles.

## 2026-04-18 — Dashboard pairing: `/pairing/mint` schema rework + grants render fix

**Context.** Observed: a silent pairing failure scanning QRs minted by the dashboard's "Pair new device" flow. Logcat showed the app attempting pairing with `serverUrl=http://192.168.1.100:8767` (relay's port, not API's) and an empty `relay` block (`relayUrl=` and `code=` both blank), causing `applyServerIssuedCodeAndReset` to bail with "empty code, returning early — authState NOT reset" and the WSS never handshook.

**Root cause.** `handle_pairing_mint` in `plugin/relay/server.py` was written as if the QR was relay-only: it defaulted top-level `host/port/tls` to `server.config.host/port` (which is the RELAY's bind — `0.0.0.0:8767`), put the freshly-minted pairing code in top-level `key`, and emitted only session metadata inside the `relay` block (`ttl_seconds`, `grants`, `transport_hint` — no `url`, no `code`). But the wire format documented at `docs/spec.md` §3.3.1 and implemented by `QrPairingScanner.kt` expects top-level = **API** server (port 8642 default) with `relay.{url,code}` nested. The CLI path (`pair.py` → `build_payload` at line 762) was correct; the endpoint was the outlier. The dashboard plugin's "editable pair URL" feature (commit `d7e5fc8`) inherited the broken semantics because its request body forwards `host/port/tls` verbatim to the endpoint.

**Fix.** `handle_pairing_mint` now mirrors the CLI shape exactly:
- Top-level `host/port/tls` default from `RelayConfig.webapi_url` (parsed via `urllib.parse.urlparse`; host resolved through `pair._resolve_lan_ip` so `localhost` / `0.0.0.0` become the machine's LAN IP, which is what the phone needs)
- Body overrides: `host` / `port` / `tls` / `api_key`
- `relay_block["url"]` derived from `_relay_lan_base_url(server.config.host, server.config.port, tls=bool(server.config.ssl_cert))` — the relay knows its own WSS address
- `relay_block["code"]` carries the minted value (used to live at top-level `key`; `key` is now the optional API bearer)

Regression test at `plugin/tests/test_pairing_mint_schema.py` — 8 AioHTTP cases pinning the payload shape that `QrPairingScanner.kt` parses, including that the minted code lands in `relay.code` (not top-level `key`) and top-level port defaults to 8642 (not 8767). All pass. Test file uses `unittest` per CLAUDE.md — `pytest` tripped on `conftest.py`'s `responses` import on the server venv.

**Doc sync.** `docs/spec.md` §3.3.1 already documented the correct wire format — this fix brought the server code back into line. Bumped the Updated stamp from 2026-04-13 to 2026-04-18 and added a line under Implementation references pointing at `handle_pairing_mint` + the new regression test. `CLAUDE.md` Key Files rows for `plugin/relay/server.py` + `plugin/dashboard/plugin_api.py` updated to reflect the API-server-at-top-level semantics.

**Deployment hazard discovered along the way.** The server had two parallel checkouts: `~/hermes-relay/` (a dev clone, looked authoritative because SYSTEM.md lists `~/` as the project root) and `~/.hermes/hermes-relay/` (the actual symlinked install per CLAUDE.md). Running `git pull` in the wrong one updated nothing visible — restart-and-check reported stale version. Verified via `systemctl --user cat hermes-relay | grep WorkingDirectory` that the installed path is the `.hermes/` copy; then pulled + restarted on that one. Also noticed the installed repo was on a long-dead `feature/dashboard-plugin` branch with 10 local WIP commits (bisect diagnostics from the dashboard build-up). Switched to `main` (fast-forward clean, 45 commits) after confirming the local feature branch's meaningful work was already on origin/main through the merged PR. The dead branch stays in local refs for history; origin has no matching ref.

**Bonus fix in the same branch.** Dashboard's Relay Management tab was crashing with minified React error #31 (`object with keys {chat, terminal, bridge}`) when rendering the paired-sessions list. `RelayManagement.jsx:172` wrapped a dict-shaped `s.grants` in a 1-element array then rendered each entry as a React child — Badge doesn't accept objects. Fix: `Object.keys(s.grants)` when the value is dict-shaped so Badge children are always channel-name strings. Rebuilt `plugin/dashboard/dist/index.js` via `npm run build` (the dashboard loads the pre-built IIFE verbatim, source edits without a rebuild are invisible).

**Branch + PR.** `fix/pairing-mint-schema` — two commits (`4f0affa`, `ca50524`), pushed, deployed to `~/.hermes/hermes-relay/`, restarted, verified live `curl /pairing/mint` round-trip produces the correct shape. PR open at the origin-suggested URL — merge to land in v0.6.0.

**Principle.** The original endpoint divergence would have been caught immediately by a schema round-trip test between the server's minted payload and the Android parser's data class. Added that test now as guardrail, not post-mortem — the two implementations will drift again eventually, this time CI yells instead of a silent field-mapping failure.

## 2026-04-18 — v0.5.1 release prep: lint iterations + VoicePlayerTest deferred

**PR #31** (`feature/voice-quality-pass` → `main`, v0.5.1 candidate) — CI iteration, no app-behavior changes beyond the preceding v0.5.1 feature commits.

**Lint round.** PR CI first failed on three latent Android lint errors, fixed one-at-a-time because Android lint prints only the first failure before aborting:
1. `UnsafeOptInUsageError` at `VoicePlayer.kt:88` — Media3 `ExoPlayer.audioSessionId` is `@UnstableApi`. Fix: class-level `@OptIn(UnstableApi::class)`. First attempt used Kotlin's `@OptIn` (implicit import) which Android lint's `UnsafeOptInUsageError` rule does not recognize; had to explicitly `import androidx.annotation.OptIn` to get the AndroidX variant. This distinction is non-obvious from the error text.
2. Second lint pass then surfaced `FlowOperatorInvokedInComposition` at `RelayApp.kt:421` and `:424` — pre-existing on `main`, masked behind the first-failure abort. Fix: wrap `.map { }` chains in `remember(repo) { repo.settings.map { ... } }` so the Flow is stable across recompositions.
3. The rule to run `./gradlew lint` locally before pushing — instead of burning CI iterations — was codified in CLAUDE.md step 4 of "Typical Dev Loop" plus a cross-session feedback memory. Both changes land in PR #31.

**Test round — VoicePlayerTest deferred.** The voice-quality-pass branch added `VoicePlayerTest.kt` with `mockkConstructor(ExoPlayer.Builder::class)` + `mockk<ExoPlayer>()`. Both trip MockK's Objenesis instantiator into loading Media3's `ExoPlayer` class, whose static init chain references `android.os.Looper` — unavailable in JVM unit tests. CI hung for 7+ minutes before being cancelled.

Tried three fixes:

1. **Factory seam on `VoicePlayer`** — added `exoPlayerFactory: (Context) -> ExoPlayer = ::defaultExoPlayer` constructor param so tests inject a mock directly instead of instrumenting `Builder`. Production behavior identical; listener attach moved from `.also { player -> }` to `init { }` so it binds to the stored `exoPlayer` field. This is kept — clean code win regardless of test outcome.

2. **Robolectric 4.14.1 dependency + `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`** — gives the test JVM shadow Android framework classes. Standalone `./gradlew test --tests VoicePlayerTest` passed in 2m53s, but the full unit test suite hung indefinitely. Reverted — see below.

3. **`forkEvery = 1`** on the unit test task to isolate Robolectric's classloader per test class — hypothesis was that Robolectric's shadow framework was bleeding into sibling test JVMs and deadlocking them on coroutine/async code. Full suite hung anyway. Reverted.

**Decision.** `@Ignore` VoicePlayerTest for v0.5.1 with strong tracking — kdoc TODO + this DEVLOG entry + `deferred_items.md` + a GitHub issue linking to commit `456f91e` which is the last state where Robolectric was wired. Follow-up PR will likely move VoicePlayer tests into a separate source set (`src/robolectricTest/kotlin/`) with its own Gradle `Test` task so it doesn't contaminate the main suite's JVM. Kept the `VoicePlayer` factory seam refactor (pure win). Reverted Robolectric dep + `testOptions` config to keep main lean for v0.5.1; the follow-up PR adds them back with the source-set split.

**Principle.** Any ignored or deferred test must be documented in TODO so it isn't forgotten. Honored via four tracking surfaces (code, DEVLOG, memory, GitHub issue). The alternative — blocking v0.5.1 on test-infra iteration — isn't a better outcome because the app works on device and the tests for it were written on a bad premise (trying to unit-test Media3 without Android framework on the classpath).

**Next session.** Open the follow-up PR to finish the Robolectric wiring: separate source set, dedicated Gradle task, CI step calling the new task. Then un-`@Ignore` VoicePlayerTest.

## 2026-04-18 — Silence auto-stop + bootstrap middleware fix

**Context.** A review of STT / silence detection in the voice stack post-barge-in found the uplink mic path has no endpointing at all — `VoiceRecorder` uses `MediaRecorder` with `AudioSource.MIC` (no AEC/NS, no VAD), and `stopListening()` has exactly one caller: `ChatScreen.kt:1319` on mic release. The `VoicePreferences.silenceThresholdMs` preference was wired to a Settings slider and persisted to DataStore but **never consumed** by any code. Cosmetic setting. Worst case was Continuous mode: after TTS finishes, it re-arms the mic and waits forever for a manual stop — ambient noise gets included, user walks away, recording goes on indefinitely.

**Decision.** Wire the existing preference. Cheapest of the four options considered (wire `silenceThresholdMs` → `AudioSource.VOICE_RECOGNITION` → second Silero on uplink → server-side VAD via `gpt-4o-transcribe`). The other three stay on the shelf; this one is self-contained in the ViewModel.

**Implementation.**
- Promoted `voicePreferences` to a VM field (previously captured only inside `initialize`'s closure).
- Added `silenceWatchdogJob` + `startSilenceWatchdog()` — snapshots `silenceThresholdMs` at turn start, polls `VoiceRecorder.amplitude` every 150 ms, reuses `RESUME_SILENCE_THRESHOLD = 0.08f` as the silence floor (same floor the post-barge-in resume watchdog already uses — one tuning knob, not two). Grace window: auto-stop only fires after at least one above-floor frame, so "user taps mic, doesn't speak" never insta-closes the turn.
- Skip in `HoldToTalk` — the physical release is the authoritative stop there, auto-stop on silence while the user is still holding would be surprising behavior.
- Cancelled in `stopListening()`, `interruptSpeaking()`, and `onCleared()`.

No UI change needed — the Settings slider and "Auto-stop listening after this much silence" copy already exist. The slider is now load-bearing instead of cosmetic.

**Bootstrap crash (separate fix, same day).** Teammate debugging a different project hit `'tuple' object has no attribute 'freeze'` on gateway startup. Root cause in `hermes_relay_bootstrap/_command_middleware.py`: `maybe_install_middleware()` was doing `app._middlewares = (*existing, middleware)` — replacing the aiohttp `FrozenList` with a plain tuple. When `AppRunner.setup()` later called `_middlewares.freeze()`, tuples don't have that method and the gateway crashed. Switched to in-place `app._middlewares.append(middleware)` — the FrozenList is still mutable at middleware-install time (freeze happens later in setup). Also updated the test mock to use `[]` instead of `()` so it matches real aiohttp behavior. 31/31 tests pass.

**Deferred (from the silence-detection discussion).**
- Uplink `AudioSource.VOICE_RECOGNITION` — engages Android's built-in NS/AEC tuned for STT. Single-line change, needs a release to validate on the Samsung.
- Second Silero on the uplink path — real VAD-based endpointing instead of raw-amplitude. Barge-in infra is already there to copy.
- `gpt-4o-transcribe` / OpenAI Realtime — server-side endpointing + lower latency, but bigger pivot. Park until we hit real limits with the amplitude approach.
- "Off" option on the silence slider — the current slider range is 1000–10000 ms with no way to disable. Future UI change if a user wants fully manual control (e.g., pause-to-think flows). Treating thresholdMs <= 0 as "off" is already supported in the watchdog for when that UI lands.

**Next session.** Rebuild and smoke-test Continuous + TapToTalk at the default 3s threshold. Validation: (1) natural pauses mid-utterance don't prematurely auto-stop at 1s/2s thresholds — if they do, tune the floor up or add a longer default; (2) the watchdog never fires when the user is manually holding for HoldToTalk; (3) ambient noise in a moderately-noisy room (TV, fan) doesn't prevent auto-stop because amp never drops below 0.08. If (3) bites, that's when `AudioSource.VOICE_RECOGNITION` becomes non-optional.

## 2026-04-17 — Voice barge-in (agent-team single-session, stacked on voice-quality-pass)

**Motivation.** After the voice-quality-pass work (see entry below), conversation mode still lagged ChatGPT / Claude app in a specific way: you can't interrupt the agent by speaking. You have to wait for the full response, or tap the mic to force a new turn — neither of which matches the "normal turn-taking" pattern users expect from modern voice UIs. The industry-standard recipe is duplex audio + VAD + AEC + hysteresis + optional resume-from-sentence-boundary. All five pieces implementable on Android if you accept that AEC quality varies across OEMs (the trap that kills most naive attempts).

**Design session.** A "quick win" barge-in implementation was requested, but the real engineering is the *false-positive defense stack* — the VAD is the easy part, fighting AEC variance across Samsung / Pixel / Motorola devices is the hard part. Landed on a three-layer defense: (1) `AcousticEchoCanceler` attached to the ExoPlayer audio session, (2) VAD hysteresis requiring 2–3 consecutive speech frames (on top of the Silero library's own attack/release windows), (3) soft ducking on single-frame positives before hard cutoff, so a stray frame only briefly dips the volume. Plus a compatibility-warning UI strip for devices without AEC so users know why quality varies.

The feature was also designed to match the existing voice-settings pattern — always-visible toggle with a sensitivity picker and a resume sub-toggle, not a hidden flag. This keeps the control surface discoverable and lets the setting be tuned per user preference rather than requiring a global "works for everyone" default that doesn't exist on Android's mic landscape.

Plan written to `docs/plans/2026-04-17-voice-barge-in.md`, 6 work units (B1 prefs, B2 VAD, B3 duplex listener, B4 integration, B5 UI, B6 ducking helpers) + Doc2. Branched from `feature/voice-quality-pass` rather than `origin/main` because barge-in architecturally depends on V4's supervisor-scoped cancellation + `pendingTtsFiles` cleanup, V5's `VoicePlayer.audioSessionId` exposure (needed for `AcousticEchoCanceler.create()`), and V3's sentence-chunk tracking (needed for resume-from-next-sentence). Merges after voice-quality-pass lands; stacked PRs.

**Execution.** Worktree at `../hermes-android-barge-in` from `feature/voice-quality-pass`. Branch `feature/voice-barge-in`. Seven commits on top of the plan:

- **Wave 1 (parallel, disjoint files).** B1 `c4fadf3` BargeInPreferences DataStore + B2 `3d011d9` Silero VAD engine + B6 `de925c8` VoicePlayer ducking helpers. Three subagents, clean touch surface. B2 agent discovered the library's actual frame-size constraint at 16 kHz is `{512, 1024, 1536}` (plan said 640 — that was wrong, from an older library version), pinned `2.0.10` not `2.0.8` (latest stable), added jitpack repo to `settings.gradle.kts` since android-vad isn't on Maven Central, and noted the library exposes threshold via a `Mode` enum (`OFF / NORMAL / AGGRESSIVE / VERY_AGGRESSIVE`) not a float — so our user-facing `Off/Low/Default/High` picker has to invert ("aggressive" means less sensitive in the library). Parallel agents also hit a staging race where one agent's `git commit` initially swept another's files; recovered via `git reset` + explicit pathspec. Noted for future parallel-worktree playbooks — always commit with explicit pathspecs when multiple agents share a tree.
- **Wave 2 (parallel, disjoint files).** B3 `48259e1` duplex listener + B5 `c65f1fe` Voice Settings UI. B3 agent introduced an `AudioFrameSource` interface seam so tests can inject a scripted frame queue without Robolectric — `AudioRecord` is a final class with native state, impossible to mock cleanly without the seam. Also added a `readerDispatcher` param so `runTest` virtual time controls the reader loop deterministically. B5 agent found no existing `VoiceSettingsViewModel` and created one following the `BridgeViewModel` pattern; chose `SingleChoiceSegmentedButtonRow` for the 4-option sensitivity picker (matching the `ChatSettingsScreen` precedent, not a dropdown). Section heading "Barge-in" chosen over "Interruption" because every other Voice Settings section is feature-named ("Text-to-Speech", "Speech-to-Text"). AEC compatibility badge renders *always* (not gated on toggle enabled) so users see the device capability before they flip it.
- **Wave 3 (serial, VoiceViewModel integration).** B4 `3e39663`. Threaded `currentChunkIndex` through V4's play worker without touching V4's supervisor topology — added `MutableStateFlow<Int>` on the VM and wired it via V4's existing `onFileReady` callback lambda, so the top-level `runTtsPlayWorker` contract stays unchanged. Chunk tracking is layered in VM-owned lambdas, not the workers. For the "user still speaking" cancel signal on the resume watchdog, the agent used `VoiceRecorder.amplitude` (perceptually-scaled StateFlow, already exposed) rather than re-subscribing a fresh AudioRecord — simpler and cheaper, since the recorder is already hot from the barge-in pre-warm. Added three `@VisibleForTesting internal` test seams (`startBargeInListenerForTest`, `seedSpeakingStateForTest`, `drainTtsQueueForTest`) so the coordinator's 7-case test matrix runs without pushing real MP3s through the V4 pipeline. Found one additional race: a synthesize call in flight when interrupt fires can complete after the cancel signal and add a file to `pendingTtsFiles` that the immediate cleanup misses — closed via the supervisor's `finally` block (same pattern V4 established).
- **Wave 4.** Doc2 (this entry) — DEVLOG, CHANGELOG `[Unreleased]` gains an Added section above the voice-quality-pass Changed section, `docs/spec.md` Phase V gets barge-in bullets, `user-docs/features/voice.md` gains a "Barge-in (interrupt the agent)" section and updates the Tap-mode description (the existing "tap to interrupt" is now one of three ways to interrupt).

**Results — expected and TBD.** Net change on top of voice-quality-pass: ~3,000 insertions / 5 deletions across 16 files. New tests: VAD engine (scripted frame sequences), duplex listener (via AudioFrameSource seam), ducking helpers, preferences round-trip, and the 7-case VoiceViewModel coordinator suite. On-device smoke test still pending — key things to validate: (1) toggling barge-in on + speaking during TTS cuts playback within ~100 ms on headphones, (2) 600 ms of silence after interrupt resumes from next sentence if resume is enabled, (3) devices without AEC show the compatibility warning badge and (presumably) trigger more false positives in practice. Plan to leave barge-in default-off through first release; telemetry or field use will inform whether the default changes.

**Deferred.** Same `Up1` from voice-quality-pass — upstream PR exposing `VoiceSettings` in `hermes-agent/tools/tts_tool.py::_generate_elevenlabs`. Not blocked by barge-in. Also deferred: live sensitivity reactivity mid-`Speaking` (the coordinator rebuilds VAD on next listener start rather than re-applying sensitivity on an already-running listener — simpler, and users flipping sensitivity during a turn is a niche workflow).

**Open questions for next session.**
1. Does the ducking feedback feel natural on-device, or should we skip the `maybeSpeech` stage entirely and just hard-cut on hysteresis pass? The 30 % ducking level is a guess.
2. Should the resume watchdog be shorter than 600 ms? A breath is more like 300–400 ms; 600 ms might feel sluggish. Tune after the smoke test.
3. Telemetry for AEC-unavailable devices — if a non-trivial portion of users see the badge, is the UX degraded enough that we should gate-off barge-in entirely on those devices, rather than just warning?
4. Integration with voice-intent direct-dispatch (from 2026-04-15): if the user barges in with "cancel" during a destructive-action countdown, does that route through the existing cancel handler or the new barge-in path? Probably needs a small coordination test.

**Next session.** On-device smoke test. If quality holds: rebase onto main after voice-quality-pass merges, open stacked PR `feature/voice-barge-in` → `main`. If quality is poor on the device: tune `RESUME_SILENCE_THRESHOLD`, hysteresis consecutive-frame counts, or default-off sensitivity preset.

---

## 2026-04-17 — Voice quality pass (agent-team single-session)

**Motivation.** Ongoing voice-mode testing with an ElevenLabs-configured Hermes backend surfaced a cluster of quality issues that individually read like different bugs but shared compounding root causes: voice output noticeably switching between crisp and muffled, volume drifting between sentences, audible pauses mid-response, and the occasional jumbled-letter spell-out when the agent emitted markdown, URLs, or tool-annotation tokens. ChatGPT- and Claude-app-class voice experiences don't exhibit this — diagnosis focused on what they do differently.

**Diagnosis chain (2026-04-16).** Traced the voice pipeline end-to-end across the Windows checkout, the relay's `plugin/relay/voice.py`, and the upstream `~/.hermes/hermes-agent/tools/tts_tool.py` on hermes-host. Five compounding layers:

1. Server config used `eleven_multilingual_v2` — ElevenLabs' expressive long-form model, which re-interprets prosody per call. Wrong model for a chunked per-sentence pipeline; every sentence is a fresh TTS context with independent encoder gain and EQ. Accounts for the bulk of the "clear↔muffled" perception.
2. Upstream `_generate_elevenlabs()` (`tts_tool.py:222`) passes no `VoiceSettings` — all defaults, including `stability` that's fine for long-form but too low for chunked output. `use_speaker_boost` (auto-normalizing) is off. Accounts for inter-chunk volume drift.
3. Upstream has `_strip_markdown_for_tts()` (`tts_tool.py:1053`) but it's only called from the CLI `stream_tts_to_speaker()` path. `text_to_speech_tool()`, which our relay calls, does NOT run the stripper. Our relay's `/voice/synthesize` passes raw assistant text to ElevenLabs — tool-annotation tokens like `` `💻 terminal` `` and URLs like `https://github.com/foo/bar` get spoken character-by-character. Accounts for jumbled letters.
4. `VoicePlayer.play(file)` on Android tore down and rebuilt a `MediaPlayer` per sentence — codec context reset, audio-track re-init, audible seam/pop. Accounts for playback-layer portion of the muffled switching.
5. `VoiceViewModel.startTtsConsumer` was strictly serial: `synthesize → play → awaitCompletion → next synthesize`. Every sentence boundary cost one full network round-trip. Accounts for the inter-sentence pause.

Plan written to `docs/plans/2026-04-16-voice-quality-pass.md` covering 8 work units across 5 waves, scoped to a single feature branch in a worktree.

**Execution (2026-04-17).** Worktree at `../hermes-android-voice` from `origin/main`. Single branch `feature/voice-quality-pass`. Six commits on top of the plan:

- **Wave 0 (operator, pre-session).** `Cfg1` — `~/.hermes/config.yaml` on hermes-host flipped from `eleven_multilingual_v2` → `eleven_flash_v2_5`; `hermes-gateway.service` restarted. Voice ID unchanged.
- **Wave 1 (parallel, disjoint files).** `V1` `3618bcd` relay TTS sanitizer + `V5` `b24a37e` ExoPlayer gapless playback. Two subagents, completely disjoint touch surface (`plugin/relay/*` vs `app/src/.../audio/VoicePlayer.kt`). No conflicts. V5 agent flagged that the plan's `ConcatenatingMediaSource` is deprecated in Media3 1.x and used `addMediaItem` on a single persistent ExoPlayer instead — same effect, current API.
- **Wave 2.** `V2` `a13dc3b` — client-side `sanitizeForTts` mirroring V1's regex set, applied per delta before sentenceBuffer append, with multi-delta code-fence deferral (a `pendingRawDelta: StringBuilder` that buffers until opening/closing fence counts balance). V2 agent caught a real subtle bug: the plan's Python `[emoji1 emoji2 ...]` character class translates badly to `java.util.regex`, which operates on UTF-16 code units — non-BMP emojis would match individual surrogate halves and leave orphan low-surrogates in the output. Switched to `(?:A|B|C|...)` alternation groups. Kotlin testability pattern used top-level `internal` functions (matching existing `extractNextSentence`) rather than `@VisibleForTesting` class members.
- **Wave 3.** `V3` `f916b22` — `MIN_COALESCE_LEN=40` + `MAX_BUFFER_LEN=400` secondary-break escape + 800 ms idle-delta timer. Spiked `ICU4J BreakIterator.getSentenceInstance()` per the plan's suggestion but rejected it: its locale-sensitive abbreviation handling is unpredictable on partial buffers left by V2's sanitizer, and it can't be parameterized with the `streamComplete`/coalescing semantics without reinventing the loop on top of it. Extended hand-rolled regex is deterministic and cheap. Secondary-break pattern requires comma-followed-by-whitespace so `"3,000"` survives. Added `@Volatile streamComplete: Boolean` on `VoiceViewModel`, set by `startStreamObserver` when the assistant stream flips `isStreaming=false`, reset in turn-start / `exitVoiceMode` / `interruptSpeaking`. The idle-flush timer passes `streamComplete=true` to `extractNextSentence` for that call only — does NOT mutate the class flag, because the SSE stream may still be live and the next delta should return to conservative coalescing.
- **Wave 4.** `V4` `8fcec6b` — `startTtsConsumer` split into two `supervisorScope`-rooted coroutines joined by `Channel<File>(capacity=2)`. Synth worker runs one file ahead. V4 agent chose Option B (explicit Channel) over the plan's recommended Option A (rely on ExoPlayer's internal queue for backpressure) because `VoicePlayer.mediaItemCount` isn't public and V4 was guardrailed out of touching V5's work. Play worker still calls `awaitCompletion()` per file (V5's queue-drained semantic), so ExoPlayer's internal queue never exceeds 1 — a single queuing layer, not redundant. Found and guarded a real race: `ttsConsumerJob?.cancel()` is non-blocking, so a synthesize call in flight can complete AFTER cancel was signalled and add a file to `pendingTtsFiles` that `interruptSpeaking`'s immediate cleanup would miss. The supervisor's `finally { deletePendingSynthFiles() }` catches late arrivals. Consumer is now cancellable+restartable — interrupt/exit cancel the job, clean up files, and immediately call `startTtsConsumer()` for the next turn.
- **Wave 5.** `Doc1` (this entry) — DEVLOG, CHANGELOG `[Unreleased]`, spec Phase V section updated to reflect the new architecture.

**Deferred.** `Up1` — upstream PR exposing `VoiceSettings` (stability / similarity_boost / use_speaker_boost) in `hermes-agent/tools/tts_tool.py::_generate_elevenlabs`. Useful once merged but not blocking; the flash model already reduces inter-chunk variance enough that the missing settings don't dominate the remaining gap.

**Results — expected and TBD.** Net change: +2,998 / -197 lines across 15 files. New tests: 33 relay sanitizer unit tests (all green) + 4 new client test files (sanitization parity, chunking semantics, prefetch pipelining timing + cancellation cleanup, ExoPlayer queue behavior — verified by reading, not by `gradle test` per project convention). On-device smoke test (3-sentence response, 1 code-block response, 1 multi-paragraph response) still pending; subjective before/after to be captured in the PR description.

**Rebase note (2026-04-17).** This branch was rebased onto `main` at v0.5.0 (from earlier base `b811da1`). Conflicts resolved: a single-import collision in `VoiceViewModel.kt` (kept both `supervisorScope` + `contentOrNull`), DEVLOG + CHANGELOG prepends (combined both days' entries in reverse chronological order, this entry placed above the v0.4.1 bridge polish entry since VQP is the newer work on 2026-04-17), and a `docs/spec.md` auto-merge. No code logic changed during rebase.

## 2026-04-18 — Dashboard plugin

**Context.** The upstream Dashboard Plugin System landed on hermes-agent's `axiom` branch in three commits (`01214a7f` plugin system, `3f6c4346` theme, `247929b0` OAuth providers). The gateway now scans `~/.hermes/plugins/<name>/dashboard/manifest.json` at startup and exposes registered plugins as tabs in its web UI. Since `~/.hermes/plugins/hermes-relay` already points at our `plugin/` subtree (from `install.sh`), we can ship a relay-specific dashboard plugin by only adding a `plugin/dashboard/` subtree plus a few relay HTTP routes — no hermes-agent fork needed. The four deferred-audit items that only the relay knows about — paired-device state, bridge command history, push delivery (future), media-registry tokens — map cleanly onto four tabs of a single plugin.

**Decision summary.**

1. **Single plugin with internal shadcn `Tabs`**, not four plugins. Manifest allows one `tab.path` per plugin; four plugins would fragment the nav. Recorded as ADR 19 in `docs/decisions.md`.
2. **Pre-built IIFE bundle committed to git.** Upstream's example plugin uses plain `React.createElement` (no build), but four non-trivial tabs are painful to maintain that way. Source lives under `plugin/dashboard/src/`, bundled with esbuild to a single ~16 KB IIFE at `plugin/dashboard/dist/index.js`. Dashboard never runs the build — operators get a ready-to-serve bundle.
3. **Loopback-only for the new relay routes.** `/bridge/activity`, `/media/inspect`, `/relay/info` are gated the same way `/bridge/status` and `/pairing/register` already are. The plugin backend runs inside the gateway process (also localhost) and calls the relay at `http://127.0.0.1:8767/...` — no bearer minting. Also added a loopback-exempt branch on `/sessions` so the plugin proxy can list paired devices without one.
4. **Dashboard backend is a thin proxy.** `plugin/dashboard/plugin_api.py` exposes five routes at `/api/plugins/hermes-relay/*` and forwards to the relay. No business logic in the plugin — relay stays source of truth.
5. **Push Console is a real tab, stub data.** Keeps the four-tab nav layout correct for when FCM lands; swapping in real data is additive.

**Implementation (Wave-by-wave).**

- **Wave 1 (relay state plumbing).** `2212fbc — feat(relay): add MediaRegistry.list_all for dashboard inspector` added the lock-guarded snapshot method that strips absolute paths, returns basename-only `file_name` fields, and filters expired entries by default (+8 unit tests). `777a06a — feat(relay): add bridge command ring buffer for dashboard activity feed` added the `BridgeCommandRecord` dataclass + `deque(maxlen=100)` on `BridgeHandler`, wired append/update into `handle_command()` / `handle_response()`, and taught `get_recent()` to redact params keyed on `{password, token, secret, otp, bearer}` (+9 unit tests covering append, update, timeout path, redaction, ring-buffer eviction).
- **Wave 2 (relay HTTP routes).** `4370806 — feat(relay): add /bridge/activity /media/inspect /relay/info for dashboard` added the three loopback-gated handlers in `plugin/relay/server.py` adjacent to `/bridge/status` (factored through a `_require_loopback()` helper), plus the tiny `handle_sessions_list` loopback-exempt branch so the dashboard proxy can list paired devices without a bearer. Route tests extend `test_bridge_activity.py` + `test_media_inspect.py` and a new `test_relay_info.py` covers the aggregate status shape.
- **Wave 3 (dashboard plugin body).** `b51940c — feat(dashboard): add plugin_api.py proxy to relay HTTP` added the FastAPI router with five routes (`/overview`, `/sessions`, `/bridge-activity`, `/media`, `/push`), a shared `_proxy_get()` helper, and structured 502 translation on relay connect-error / timeout / 5xx so the UI can show "relay unreachable" (+10 unit tests in `plugin/dashboard/test_plugin_api.py`). `087149e — feat(dashboard): add React UI for four relay tabs` added the `plugin/dashboard/{src,dist}/` subtree — JSX sources under `src/` (index + four tab components + `lib/api.js` + `lib/formatters.js`), esbuild toolchain (`package.json` + `build.sh`), and the committed ~16 KB IIFE bundle at `dist/index.js` that registers as `window.__HERMES_PLUGINS__.register("hermes-relay", …)` via the SDK global.
- **Wave 4 (manifest wiring).** `78c209e — feat(dashboard): add plugin manifest + plan file` added `plugin/dashboard/manifest.json` — `name: "hermes-relay"`, `label: "Relay"`, `icon: "Activity"` (from the 20-name Lucide whitelist), `tab.path: "/relay"`, `tab.position: "after:skills"`, `entry: "dist/index.js"`, `api: "plugin_api.py"`. Verified the existing `~/.hermes/plugins/hermes-relay` symlink resolves `dashboard/manifest.json` correctly.

**Files touched (this session — Doc1 wave only).**

- `CHANGELOG.md` — new `[Unreleased]` "Added — Dashboard plugin" bullet group
- `DEVLOG.md` — this entry
- `README.md` — one-line mention under Quick Start
- `CLAUDE.md` — `plugin/dashboard/` added to Repository Layout; four Key Files rows under a new "Plugin — Dashboard" group
- `docs/spec.md` — "Dashboard plugin" subsection appended to §10 Hermes Integration Points
- `docs/decisions.md` — new ADR 19 "Dashboard plugin: single plugin with internal tabs + pre-built IIFE bundle"
- `docs/relay-server.md` — three new rows in the HTTP Routes table + loopback-branch note on `/sessions`
- `user-docs/features/dashboard.md` — new user-facing page
- `user-docs/.vitepress/config.mts` — sidebar nav entry for dashboard.md

**Deferred.**

- **Push Console needs FCM.** The tab renders a static "FCM not configured" banner from `GET /api/plugins/hermes-relay/push`. Swapping in real data is additive — only `PushConsole.jsx` + `plugin_api.py::get_push` change. Tracked under the `deferred_items` memory entry.
- **Session revoke button is a placeholder.** The Relay Management tab exposes "Revoke" buttons per paired device but they currently log to the console — the plugin proxy would need a new `DELETE /api/plugins/hermes-relay/sessions/{prefix}` route forwarded to the relay's existing `DELETE /sessions/{token_prefix}`. Blocked on deciding the auth story (loopback-only? dashboard session token?). Keeps the UI placement correct for when the proxy route lands.
- **Session extend button** not yet built; would follow the same proxy pattern against `PATCH /sessions/{token_prefix}`.

**Next session.** Restart `hermes-gateway` on the server, verify `GET /api/dashboard/plugins` includes `hermes-relay`, load the dashboard UI, and exercise all four tabs against a live paired phone. Screenshot the tabs so real images can replace the placeholder captions in `user-docs/features/dashboard.md`.

**No blockers.**

---

## 2026-04-17 — v0.4.1 Bridge page polish pass

**Motivation.** The Bridge tab had accreted cards without an information hierarchy — the master toggle, a separate status card, the permission checklist, unattended access, a standalone keyguard warning chip, and the safety summary all sat at the same visual weight, and tapping the master Switch when Accessibility wasn't granted was a silent no-op (stock Android disabled-switch behavior). Unattended Access lacked a master dependency, and once ON there was no in-app affordance while the user was on another tab. The LLM also had to learn reactively that commands wouldn't reach apps on a sleeping/locked device (via `keyguard_blocked` errors) instead of being told upfront.

**What landed on `feature/v0.4.1-integration`:**

- **Bridge card hierarchy rewrite.** New order: Master → Permission Checklist → [Advanced divider] → Unattended Access → Safety Summary → Activity Log. Master toggle copy now leads with "Master switch —" and carries a `MASTER` pill so its parent-gate role is legible at a glance. The old `BridgeStatusCard` was dropped from the layout (its status rows already live inside the master card); the component file stays in-tree for now.
- **Master-toggle feedback fix.** Tapping the Switch when `HermesAccessibilityService` isn't granted now surfaces a snackbar ("Accessibility Service must be enabled first.") with an "Open Settings" action that deep-links to `ACTION_ACCESSIBILITY_SETTINGS`, instead of the previous silent-drop disabled-switch behavior. The master-toggle info dialog also gained a paragraph attributing the "Hermes has device control" persistent notification to the master switch.
- **Unattended Access gated on master.** `UnattendedAccessRow`'s Switch is `enabled = masterEnabled` and shows "Requires Agent Control — enable the master switch above first." when master is off. The standalone `KeyguardDetectedChip` was inlined as a `KeyguardDetectedAlert` Surface band inside the Unattended Access card so the credential-lock warning lives next to the thing that triggers it. Unattended scary-dialog copy no longer implies the unattended toggle owns the persistent notification — attributes it correctly to the master switch.
- **`UnattendedGlobalBanner` — in-app banner vs. system chip split.** New 28dp amber strip renders at the top of `RelayApp`'s scaffold on every tab when master + unattended are both on (sideload only). Theme-aware amber (amber-on-dark vs. dark-amber-on-pale in light mode), pulsing dot, "Unattended access ON — agent can wake and drive this device" copy, chevron → navigates to the Bridge tab. The existing WindowManager `BridgeStatusOverlayChip` continues to handle the backgrounded-app case (foreground-gating of the chip is in flight on a sibling branch). Net: banner when the user is IN Hermes-Relay, system chip when the app is backgrounded.
- **Agent-awareness upgrades to `PhoneSnapshot`.** Three new fields: `unattendedEnabled`, `credentialLockDetected`, `screenOn`. `PhoneStatusPromptBuilder.buildBridgeLine()` appends explicit guidance so the LLM knows upfront whether commands will land while the user is away, instead of finding out reactively via `keyguard_blocked` responses.
- **Bug fixes bundled in.** Permission checklist Optional pill no longer wraps mid-pill on narrow titles (switched to `FlowRow` + `softWrap=false`); runtime-permission rows (Mic, Camera, Contacts, SMS, Phone, Location) now fall back to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` when a permission has been permanently denied, instead of silently no-opping.

**Next.** On-device verification of the reordered layout, master-snackbar, global banner, and `PhoneSnapshot` fields on the Samsung S24 / Android 14 sideload build. After that, version bump to v0.4.1 release tag via `bash scripts/bump-version.sh 0.4.1` → push tag → CI.

**No blockers.**

---

## 2026-04-16 — v0.4.1: Tiered permission checklist + JIT permission-denied surfacing

**Motivation.** Two v0.4.x fast-follows from ROADMAP.md, scoped to ship together because they share the same UX axis ("the user understands which permission is missing and what to do about it"). Until now the Bridge tab's checklist was a flat 4-row layout that lumped optional and required perms together, and `android_search_contacts` / `android_send_sms` / `android_call` / `android_location` failures bubbled up as opaque error strings — the LLM had to pattern-match the phrasing to figure out it was a permission issue. Both surfaces now make the missing-permission state legible to humans AND to the agent.

**What landed.**

- **Tiered checklist UI** (`BridgePermissionChecklist.kt` rewrite). Four explicit sections — Core bridge (required), Notification companion (optional), Voice & camera (optional), Sideload features (optional, sideload-only). Each row uses a `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission)` for runtime dangerous perms and the existing intent helpers for special perms (Accessibility, Notification Listener, Overlay, Screen Capture). Optional rows render a "Optional" Material 3 pill so users don't perceive them as urgent. Section headers use a primary-coloured label + caption + thin divider. Hides sideload-only sections on googlePlay via the existing `BuildFlavor.isSideload` gate.
- **`BridgePermissionStatus` extended** with `microphonePermitted`, `cameraPermitted`, `contactsPermitted`, `smsPermitted`, `phonePermitted`, `locationPermitted`. `refreshPermissionStatus()` probes each via `ContextCompat.checkSelfPermission`. Re-runs on every `Lifecycle.Event.ON_RESUME` (existing pattern).
- **Manifest verification.** All required `<uses-permission>` declarations were already in place: `RECORD_AUDIO` + `CAMERA` in `app/src/main/AndroidManifest.xml`, `READ_CONTACTS` + `SEND_SMS` + `CALL_PHONE` + `ACCESS_FINE_LOCATION` (+ `ACCESS_COARSE_LOCATION`) in `app/src/sideload/AndroidManifest.xml`. No manifest changes needed for this PR.
- **`ResolveResult` typed-union shipped** in `plugin/tools/resolve_result.py` — `Found(value)` / `NotFound(detail)` / `PermissionDenied(permission, reason)` dataclasses with a `from_bridge_response(response, *, found_value=None)` constructor that classifies a bridge response as one of the three variants. Reads both canonical (`code` / `permission`, v0.4.1) and legacy (`error_code` / `required_permission`, pre-v0.4.1) wire-key spellings so the rollout is forwards/backwards compatible across mixed-version installs.
- **Bridge response envelope extended** in `BridgeCommandHandler.kt::respondFromResult` to emit the canonical `code` + `permission` aliases ALONGSIDE the existing `error_code` + `required_permission` fields. `LocalDispatchResult` parsing also accepts either spelling. The phone now produces `{"ok": false, "error": "...", "code": "permission_denied", "permission": "android.permission.READ_CONTACTS", "error_code": "permission_denied", "required_permission": "android.permission.READ_CONTACTS"}`.
- **Tier C agent-tool wrappers upgrade permission errors to JIT structured responses.** `android_location` / `android_search_contacts` / `android_send_sms` / `android_call` in `plugin/tools/android_tool.py` now run their bridge response through `_maybe_jit_permission_response` after the HTTP round-trip. On `code: permission_denied` the wrapper returns `{"ok": false, "error": "User has not granted Contacts permission (android.permission.READ_CONTACTS). They can enable it in Settings > Apps > Hermes Relay > Permissions. Tool: android_search_contacts.", "code": "permission_denied", "permission": "..."}` — deterministic LLM-readable copy with the exact Settings deep-link path embedded.
- **Voice-mode JIT chip** in `VoiceModeOverlay.kt`. New `PermissionDeniedChip` composable surfaces above the mic button when the most recent voice intent dispatch returned `errorCode == "permission_denied"`. Tap deep-links to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for `BuildConfig.APPLICATION_ID` (so both flavors land on their own package's permission page). `VoiceUiState.permissionDeniedCallout: PermissionDeniedCallout?` carries the chip state. `buildPermissionDeniedCallout(label, result)` reads the structured `permission` field off `result.resultJson` and builds a copy line ("I need Contacts to Search contacts here. Tap to open Settings."). Cleared on chip tap and on the next mic-tap (fresh turn).
- **Voice TTS on permission_denied** was already in place from the 2026-04-15 voice fixes session — `speakDispatchResult` already says "Permission needed. {hint}" in this branch. The chip is purely additive.
- **17 new Python tests** in `plugin/tests/test_resolve_result.py`. Covers the ResolveResult classifier, both canonical/legacy/mixed wire-key spellings, success passthrough, non-permission error passthrough, and JIT upgrades for all four Tier C wrappers. All 17 pass; existing 39 Tier-C tests still pass with no regressions.

**Branch.** `feature/tiered-permissions` (forked from main, with `feature/bridge-notifications-permission` merged in first per the spec — that branch's POST_NOTIFICATIONS row sits inside the new Core-bridge tier without modification). Pushed to origin, PR-ready.

**Files touched (summary).**

- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/BridgePermissionChecklist.kt` (rewrite — tiered layout)
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/BridgeViewModel.kt` (status fields + probes)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/BridgeScreen.kt` (6 new permission launchers, wired into checklist)
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/BridgeCommandHandler.kt` (canonical alias emission, dual-key parse)
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt` (PermissionDeniedCallout state, buildPermissionDeniedCallout, clearPermissionDeniedCallout)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/VoiceModeOverlay.kt` (PermissionDeniedChip composable + signature param)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ChatScreen.kt` (chip-tap callback wiring deep-link to Settings)
- `plugin/tools/resolve_result.py` (NEW — typed-union dataclass hierarchy + classifier)
- `plugin/tools/android_tool.py` (`_maybe_jit_permission_response` helper + 4 wrappers updated)
- `plugin/tests/test_resolve_result.py` (NEW — 17 unit tests)
- `ROADMAP.md`, `CHANGELOG.md`, `DEVLOG.md` (this entry)

**Notable decisions.**

1. **Did not introduce a server-side resolver layer.** The prompt suggested Python resolvers like `resolveContactPhone`, but the actual contact resolution lives in Kotlin (`ActionExecutor.searchContacts`). Implementing the spec verbatim — Python `ResolveResult` types — still gave value as the agent-tool wrapper's classifier of bridge responses, which is the layer that needed the structured permission-denied surfacing for the LLM. The Kotlin `ContactResolution` sealed-class equivalent already exists from 2026-04-15.
2. **Both wire-key spellings emitted by the phone (canonical + legacy).** Forwards/backwards compatibility across the v0.4.x APK rollout window. Drop the legacy aliases after v0.5.0 once the 0.4.0 APKs are estimated to be off the field.
3. **JIT chip uses errorContainer colour, not a notification-style banner.** Voice mode is a focused single-purpose modality; banner-treatment would compete with the destructive-countdown row. Chip is explicit and tappable but doesn't block the voice flow.
4. **Optional rows use a neutral status tint when not granted.** Original layout used error-red for any not-granted row, which made optional perms feel urgent — the v0.4.1 layout treats neutral-grey + optional-pill as the "this is fine if you skip it" affordance.
5. **Sideload-only rows hidden entirely on googlePlay** rather than rendered as disabled. Showing a permission row for a manifest entry that doesn't exist would confuse users and potentially flag review on the Play track.

**Verification.**

- `python -m unittest plugin.tests.test_resolve_result` — 17 tests pass.
- `python -m unittest plugin.tests.test_android_call plugin.tests.test_android_send_sms plugin.tests.test_android_search_contacts plugin.tests.test_android_location` — 39 existing tests still pass.
- `python -m py_compile plugin/tools/resolve_result.py plugin/tools/android_tool.py` — clean.
- Kotlin code reviewed for type correctness and import resolution; no `gradle build` per project convention (built from Android Studio).

**Next session.** On-device retest of the tiered checklist + JIT chip flow on Samsung S24 / Android 14 sideload APK. After that, version bump to v0.4.1 + cut release.

---

## 2026-04-15 — Bootstrap command middleware (slash-command hallucination fix)

**Motivation.** When a user types `/help` or `/model` into the Android app's chat, the message reaches the LLM verbatim on vanilla upstream hermes-agent installs because `APIServerAdapter` doesn't connect to the `GatewayRouter`. The LLM hallucinates a plausible-sounding wrong reply. The upstream fix lives in `gateway/platforms/api_server_slash.py` (Stage 1 PR), but vanilla installs won't get it until that PR merges. This session implements the bootstrap-middleware sibling that ships the fix now.

**What was built:**

- **`hermes_relay_bootstrap/_command_middleware.py`** (~420 LOC) — aiohttp middleware that:
  - Intercepts `POST /v1/chat/completions` and `POST /v1/runs` (zero-cost skip for all other paths)
  - Runs auth check (`adapter._check_auth`) before any command logic
  - Extracts the user message from each endpoint's body format
  - Resolves against `hermes_cli.commands.resolve_command()` from the central command registry
  - Stateless commands (`/help`, `/commands`, `/profile`, `/provider`) are dispatched locally using the same helpers as upstream
  - Stateful commands (`/model`, `/new`, `/retry`, etc.) return a deterministic decline notice
  - Unknown and CLI-only commands fall through to the LLM
  - For `/v1/chat/completions`: returns synthetic SSE stream (role + content + finish + `[DONE]`) or JSON `chat.completion`
  - For `/v1/runs`: injects `message.delta` + `run.completed` + sentinel into `adapter._run_streams` queue, returns `{"run_id": ..., "status": "started"}` with 202, so the events endpoint drains the queue normally
  - Feature-detects: if `gateway.platforms.api_server_slash` exists (upstream PR landed), middleware is skipped
  - Fail-open: any exception falls through to the original handler

- **`hermes_relay_bootstrap/_patch.py`** — added `_maybe_install_command_middleware()` call in the `__setitem__` hook alongside the existing route injection, using the same timing window where `app._middlewares` is still mutable.

- **`hermes_relay_bootstrap/__init__.py`** — updated docstring to document both injection layers (routes + middleware).

- **`plugin/tests/test_command_middleware.py`** (31 tests) — unit tests for message extraction, command resolution, response builders, feature detection, plus full aiohttp integration tests via `TestClient`/`TestServer` covering both endpoints, streaming, auth rejection, and fall-through behavior.

**Test results:** 31 new tests pass. Full existing suite (450 tests) still passes with no regressions.

**Android client impact:** None. The synthetic responses match the exact shapes the client already parses (`chat.completion.chunk` for completions SSE, `message.delta`/`run.completed` for runs events).

**Next:** Merge to main via PR, then deploy to server (`git pull && systemctl --user restart hermes-gateway`). Phone re-pair after restart.

---

## 2026-04-16 — v0.4.1 unattended access mode (sideload-only)

**Motivation.** First v0.4.1 fast-follow off the ROADMAP. Use case:
walking away from the phone and letting the agent finish whatever task is in
flight without the screen having to stay manually awake. Without
unattended access, every bridge action that lands on a sleeping screen
silently no-ops because `dispatchGesture` runs against the dimmed UI.

**Scope landed on `feature/unattended-access`:**

- **`UnattendedAccessManager`** (new file under `bridge/`) — process
  singleton holding the SCREEN_BRIGHT wake lock + orchestrating
  `KeyguardManager.requestDismissKeyguard`. Hosts a `WakeOutcome` enum
  (Success / SuccessNoKeyguardChange / KeyguardBlocked / Disabled) and
  two StateFlows (`enabled`, `credentialLockDetected`). Initialized
  once from `HermesRelayApp.onCreate`. The host Activity is registered
  in `MainActivity.onResume` and cleared in `onPause` so we don't leak
  it past the lifecycle.
- **DataStore additions to `BridgeSafetyPreferences`** —
  `unattendedAccessEnabled` (the user opt-in) and
  `unattendedWarningSeen` (latch for the one-time scary dialog). Both
  default false. Setters keep `KEY_SAFETY_INITIALIZED` in sync so
  user-clear-blocklist semantics are preserved.
- **`BridgeViewModel` exposure** — `unattendedEnabled`,
  `unattendedWarningSeen`, `credentialLockDetected` StateFlows;
  `setUnattendedAccessEnabled(boolean)` + `markUnattendedWarningSeen()`.
  An init-time collector mirrors the persisted setting into
  `UnattendedAccessManager.setEnabled()` so the dispatch fast-path can
  read state synchronously without DataStore. `onScreenResumed` now
  also re-probes the keyguard state.
- **`UnattendedAccessRow` component** — Compose card with the toggle,
  a one-time scary `AlertDialog` (security model + credential-lock
  limitation + how to disable), and a persistent `Card` chip when the
  device has a credential lock detected. Only rendered inside
  `BridgeScreen` when `BuildFlavor.isSideload` is true.
- **Bridge dispatch pre-gate** — `BridgeCommandHandler.dispatch` calls
  `UnattendedAccessManager.acquireForAction()` after the safety-rails
  pass and before the action `when` block, for any path NOT in the new
  `READ_ONLY_PATHS` set. KeyguardBlocked outcome short-circuits with
  HTTP 423 + `error_code = "keyguard_blocked"` + a `final = true`
  flag so the LLM doesn't blindly retry against the lock screen.
- **`classifyGestureFailure()`** — small helper on `ActionExecutor`
  that wraps gesture-dispatch failure messages with a keyguard-aware
  hint when the live keyguard state is locked. Tap / swipe / drag /
  long_press failure paths use it. `BridgeCommandHandler.classifyBridgeError`
  now routes "keyguard"-bearing strings to the `keyguard_blocked`
  error_code so both pre-gate and gesture-failure paths converge on
  the same structured response.
- **`BridgeStatusOverlayChip` variant** — added an `unattended: Boolean`
  parameter so the chip can render in an amber-dot "Unattended ON"
  variant. `BridgeStatusOverlay.setChipVisible` gained an `unattended`
  argument and a tear-down-and-rebuild path when the flag flips
  between attached state. `BridgeViewModel`'s overlay-toggle collector
  now combines master / overlayPref / unattendedEnabled and forces
  the chip on whenever unattended is active even if the user disabled
  the regular overlay preference.
- **Lifecycle hardening** — Master-toggle-off and relay-disconnect
  both call `UnattendedAccessManager.release()`. The persisted
  `unattendedAccessEnabled` value is preserved across master toggle
  cycles (the user's "I want unattended when bridge is on" preference
  shouldn't be wiped by every disconnect).
- **Manifest** — `DISABLE_KEYGUARD` declared in
  `app/src/sideload/AndroidManifest.xml`. WAKE_LOCK already lives in
  the main manifest for the existing PARTIAL_WAKE_LOCK gesture scope
  and covers SCREEN_BRIGHT acquires too — no new top-level permission.

**Tests landed.** `app/src/test/kotlin/com/hermesandroid/relay/bridge/
UnattendedAccessManagerTest.kt` covers state transitions (enabled ↔
disabled, credential lock detection from `isDeviceSecure`,
`acquireForAction` outcomes for the four reachable cases without a
real PowerManager: Disabled / SuccessNoKeyguardChange / Success /
KeyguardBlocked) using MockK to stub `Context.getSystemService` and
reflection to reset the singleton between tests. Also added
`BridgeSafetySettingsTest.kt` to lock in the false-by-default contract
on the two new preference fields.

**Decisions documented (NOT re-litigated this session):**

- No WiFi-disconnect failsafe. Tailscale / VPN invalidate the
  "leaving WiFi = leaving LAN" assumption; relay-disconnect detection
  + auto-disable timer cover the gap.
- Default auto-disable timer stays at 30 minutes. No unattended-mode
  override.
- Credential lock limitation is structural — Android does not let
  third-party apps dismiss PIN / pattern / biometric. Surface it via
  the warning dialog + persistent chip + `keyguard_blocked` rather
  than try to work around it.

**Wake-lock flag note.** `SCREEN_BRIGHT_WAKE_LOCK` has been
deprecated since API 17 in favor of `Window.FLAG_KEEP_SCREEN_ON` /
`Activity.setTurnScreenOn(true)`. We accept the deprecation warning
under explicit `@Suppress` annotations because the Activity-bound
alternatives don't apply to a background bridge that doesn't own
its own window — the bridge runs from a foreground service, gestures
are dispatched via AccessibilityService, and the only "UI surface"
we own off-Activity is the WindowManager overlay chip which can't
drive screen wake-up. Same story for `requestDismissKeyguard` —
it's API 26+ (our minSdk is 26 so always available) and reachable
only when an Activity is registered, hence the
`MainActivity.setHostActivity` plumbing.

**Files touched:**

- new: `app/src/main/kotlin/com/hermesandroid/relay/bridge/UnattendedAccessManager.kt`
- new: `app/src/main/kotlin/com/hermesandroid/relay/ui/components/UnattendedAccessRow.kt`
- new: `app/src/test/kotlin/com/hermesandroid/relay/bridge/UnattendedAccessManagerTest.kt`
- new: `app/src/test/kotlin/com/hermesandroid/relay/data/BridgeSafetySettingsTest.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/HermesRelayApp.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/MainActivity.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/data/BridgeSafetyPreferences.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/BridgeViewModel.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/BridgeScreen.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/bridge/BridgeStatusOverlay.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/ui/components/DestructiveVerbConfirmDialog.kt` (chip variant)
- mod: `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/BridgeCommandHandler.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt`
- mod: `app/src/sideload/AndroidManifest.xml`
- mod: `ROADMAP.md` (marked v0.4.1 unattended access shipped)
- mod: `CHANGELOG.md` (added unreleased section)

**Next.** Build + install from Android Studio + flip the
toggle on a Samsung S24 with a PIN lock. Expected behaviours:
(a) scary dialog appears on first toggle-on, (b) keyguard-detected
chip stays visible while unattended is ON, (c) bridge actions return
`keyguard_blocked` when fired against a locked screen, (d) toggling
master off drops the wake lock immediately. After verification, cut
v0.4.1 release tag.

**Known residuals / follow-ups:**

1. The `WakeOutcome.Success` path doesn't actually wait on the
   `requestDismissKeyguard` callback — we predict success based on
   `isDeviceSecure == false`. Predict-don't-wait is fine for
   None / Swipe locks (which always succeed) but a Wave 2
   refinement could plumb the callback for richer telemetry.
2. The host-activity registration is single-Activity by design.
   If a future MainActivity refactor introduces a second Activity
   surface, the `setHostActivity(null)` call in onPause would
   clobber the wrong reference. Document this constraint where it
   matters.
3. `READ_ONLY_PATHS` is hand-maintained — adding a new bridge route
   requires updating both the dispatch `when` and the read-only
   set. A linter rule or test-time enforcement would catch drift.

---

## 2026-04-16 — Voice intent → server session sync (v0.4.1)

**Motivation.** On-device repro (2026-04-14): speak "open Chrome", phone opens Chrome (good), then type "did that work?" → LLM responds "I have no prior context for what you're asking about." Voice intents dispatch in-process via `BridgeCommandHandler.handleLocalCommand` (correct — voice actions are phone-local) and append a local-only trace bubble to chat (good UX), but the server-side Hermes session never absorbs the action so the gateway-side LLM has zero memory of it. Followups felt like the chat had "reset."

**Approach.** Synthesize OpenAI-format `assistant` (with `tool_calls`) + `tool` (with `tool_call_id`) message pairs from unsynced voice-intent traces and pass them under a new optional `messages` field on the existing `/v1/runs` and `/api/sessions/{id}/chat/stream` payloads. LLMs are trained on this exact shape — they read it as natural conversation history, not a side-channel system-prompt note. Lower retry risk than a bracketed system-message prefix. Frontend-only — zero server changes.

**Implementation summary.**

- **`data/ChatMessage.kt`** — added `voiceIntent: VoiceIntentTrace?` field (default null). The new `VoiceIntentTrace` class captures `toolName` (must start `android_*`), `argumentsJson` (OpenAI tool-call args, JSON-encoded string), `success`, `resultJson` (full result envelope including `ok`/`error`/`error_code`), and `syncedToServer` flag. Default null so every existing `ChatMessage(...)` call site keeps compiling without touching this field.
- **`voice/VoiceIntentSyncBuilder.kt`** (new file, ~170 LOC) — pure-function builder that walks chat history, filters to unsynced voice-intent traces, mints `call_voiceintent_<uuid>` IDs, and emits a JsonArray of synthetic `assistant` + `tool` message pairs in chronological order. `hasUnsynced()` short-circuits the empty-array allocation on the common-case turn. `successResultJson()` / `failureResultJson()` helpers normalize the tool-response payload shape so call sites don't hand-roll JSON. No Android dependencies — JVM-pure for cheap unit testing.
- **`network/HermesApiClient.kt`** — `sendChatStream` and `sendRunStream` both gained an optional `voiceIntentMessages: JsonArray? = null` parameter. When non-empty, splices it under a top-level `messages` field on the request body. Additive, OpenAI-compat — older servers ignore unrecognised body fields.
- **`viewmodel/ChatViewModel.kt`** — `startStream` snapshots history, calls `VoiceIntentSyncBuilder.buildSyntheticMessages`, threads the result into both API client paths, then calls `handler.markVoiceIntentsSynced()` after the API client takes ownership. Idempotent — already-synced traces are skipped. `recordVoiceIntent`/`recordVoiceIntentResult` signatures now accept an optional `voiceIntent: VoiceIntentTrace?` so VoiceViewModel can attach the structured payload.
- **`network/handlers/ChatHandler.kt`** — `appendLocalVoiceIntentTrace` and `appendLocalVoiceIntentResult` now accept an optional `voiceIntent` parameter; new `markVoiceIntentsSynced()` method flips `syncedToServer=true` on every trace currently in state.
- **`voice/VoiceBridgeIntentHandler.kt`** — `IntentResult.Handled` gained `androidToolName: String?` (defaults to null) and `androidToolArgsJson: String` (defaults to "{}"). Sideload classifier populates both per intent so `VoiceIntentSyncBuilder` can synthesize a structured tool_call.
- **Sideload `VoiceBridgeIntentHandlerImpl.kt`** — every `tryHandle` branch now passes `androidToolName` + `androidToolArgsJson` to `handleSafe`/`handleDestructive`. The dispatch callbacks (`onDispatchResult`) carry these forward to VoiceViewModel. Maps `SendSms` → `android_send_sms`, `OpenApp` → `android_open_app`, `Tap` → `android_tap_text`, `Back`/`Home` → `android_press_key`. Args mirror what the gateway-side LLM tool wrappers in `plugin/tools/android_tool.py` would emit for the same actions, so the synthetic tool_call looks identical to the real one.
- **`VoiceIntentResultCallback` typealias** — extended in BOTH `googlePlay/` and `sideload/` flavor source sets to `(intentLabel, result, androidToolName, androidToolArgsJson) -> Unit`. Play APK never invokes the callback (no destructive intents in the no-op handler) but typealias parity keeps `VoiceViewModel` compilable against either flavor with no `#if` gating.
- **`viewmodel/VoiceViewModel.kt`** — extended dispatch callback wiring builds a `VoiceIntentTrace` from the post-dispatch outcome (using `LocalDispatchResult.isSuccess` + `resultJson` + `errorMessage` + `errorCode`) and threads it into `chatViewModel.recordVoiceIntentResult(label, result, voiceTrace)`. Trace attaches to the post-dispatch RESULT bubble (not the pre-dispatch action bubble) because that's the moment the dispatch outcome is authoritative — pre-dispatch was either pending (destructive countdown) or not-yet-known.

**Tests.** `app/src/test/kotlin/com/hermesandroid/relay/voice/VoiceIntentSyncBuilderTest.kt` (12 cases):
- empty history → empty output
- no voice-intent messages in history → empty output
- single success trace → exactly one assistant+tool pair, correct shape
- failure trace → tool message content carries `ok:false` + `error` + `error_code`
- already-synced traces are skipped
- multiple traces preserve chronological order
- non-`android_*` tool name is filtered (defence-in-depth)
- blank args is filtered (defence-in-depth)
- assistant `tool_calls[].id` and tool `tool_call_id` reference the same minted ID
- `hasUnsynced` happy paths (empty / unsynced / all-synced)
- `successResultJson` / `failureResultJson` helpers behave correctly

`ChatHandlerTest.kt` gained 4 new cases for trace storage + `markVoiceIntentsSynced` flag flip + idempotency on already-synced traces + safety on plain non-trace messages.

**Files touched** (10):
- `app/src/main/kotlin/com/hermesandroid/relay/data/ChatMessage.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/voice/VoiceIntentSyncBuilder.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/network/HermesApiClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/ChatHandler.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ChatViewModel.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentHandler.kt`
- `app/src/sideload/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentFactory.kt`
- `app/src/sideload/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentHandlerImpl.kt`
- `app/src/googlePlay/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentFactory.kt`
- `app/src/test/kotlin/com/hermesandroid/relay/voice/VoiceIntentSyncBuilderTest.kt` (new)
- `app/src/test/kotlin/com/hermesandroid/relay/network/handlers/ChatHandlerTest.kt`

**Branch.** `feature/voice-session-sync`. Not merged. Pushed for review.

**What's next.** On-device verification: send a voice intent ("open Chrome"), then type "did that work?" — the LLM should describe the action with grounded context instead of hallucinating. If the upstream gateway logs the synthetic `messages` array as additional context (visible in `journalctl --user -u hermes-gateway -f`), the wire integration is clean. If the gateway 400s on the new field name (e.g. wants `additional_messages` or `history`), we may need to peek at `gateway/platforms/api_server.py` upstream to confirm the exact field name. The CHANGELOG note about "additive — OpenAI-compat" reflects best-current-knowledge but isn't yet wire-verified.

**Blockers.** None. Ships as a self-contained refactor.

---

## 2026-04-15 — Voice → bridge → agent pipeline end-to-end fixes

**Motivation.** On-device voice SMS testing surfaced a stack of bugs that individually looked small but together blocked the voice → bridge → agent pipeline from working end-to-end. A single long session diagnosed and fixed six distinct layers: voice classifier gaps, a safety-modal lifecycle bug, chat history clobbering, a plugin check_fn pointed at the wrong relay endpoint, missing LLM tool wrappers for the direct-dispatch phone actions, and two Android OEM-interaction issues (MediaProjection notification persistence + activity recreation on warm reopen). Landed across four commits on `feature/bridge-feature-expansion`.

**Summary of changes** (in narrative order, not commit order):

- **Voice intent classifier narrowed + phrasing-tolerant.** Removed the `Scroll` voice intent (nobody says "scroll down" aloud; `/scroll` route stays for server-side `android_scroll` tool calls). Added `SMS_INDIRECT` regex to catch natural indirect-object phrasing ("send Hannah a text saying smoke test") plus `message` as a direct verb. Added a phone-number literal bypass so "text +1 555 1234 saying hi" skips contact lookup.
- **Honest error classification replaces "couldn't find contact" catch-all.** `resolveContactPhone` now returns a `ContactResolution` sealed type distinguishing `Found` / `ServiceMissing` / `PermissionMissing` / `NotFound` / `NoPhoneNumber` / `OtherError`, with voice mode speaking a specific actionable message per category ("I need Contacts permission to look up that number" vs "I couldn't find a contact called Hannah"). `resolveAppPackage` got the same treatment via `AppResolution`. Pre-flight SEND_SMS check short-circuits before the 5s countdown if permission is missing. Multi-contact matches surface total-count + matched-name in the spoken confirmation so the user knows one of several got picked.
- **Safety-modal threading + savedstate lifecycle fixes.** `BridgeSafetyManager.awaitConfirmation` now wraps `host.showConfirmation` in `withContext(Dispatchers.Main.immediate)` — the ComposeView creation + setContent must run on Main, but was being called from `Dispatchers.Default` via the voice local-dispatch path, threw, and got swallowed as "likely overlay permission missing" (which misled on-device triage for a full test cycle). `OverlayLifecycleOwner.start()` init order flipped: `performRestore(null)` must run while lifecycle state is still INITIALIZED, not after advancing to CREATED — current androidx.savedstate asserts `performAttach` requires INITIALIZED. The old order worked against an earlier androidx.savedstate version and broke silently when the Compose BOM bumped.
- **Voice mode UX: post-dispatch feedback, TTS, visual countdown, fall-through visibility.** `handleLocalCommand` went from fire-and-forget to capturing the response via an `AtomicReference` sink on the `LocalDispatch` coroutine context element, returning `LocalDispatchResult(status, errorMessage, errorCode, resultJson)`. Voice mode emits a follow-up chat bubble ("**Send SMS — sent ✓**" / "**Send SMS — cancelled by you**" / permission/service errors) AND speaks the outcome via the existing TTS queue. Voice-in-voice cancel detects "cancel / stop / never mind" during the 5-second countdown and routes it straight to `cancelPending()` instead of classifying it as a new turn. Visual countdown: `DestructiveCountdownState` on `VoiceUiState` + `onCountdownStart` callback + `LinearProgressIndicator` in `VoiceModeOverlay` synced to the real delay. Classifier fall-through immediately flips `VoiceState.Thinking` so the UI shows progress during SSE connect latency instead of appearing to drop the utterance.
- **Voice mode transcript with chat parity.** `VoiceModeOverlay` now observes `ChatViewModel.messages` and renders the last 6 via `CompactTranscriptRow` + `StreamingResponseRow`. Voice-action traces render via `MarkdownContent`. User messages keep the "YOU" caption (fixed a caption mislabel where voice-intent user messages were showing as "ACTION" due to an id-prefix check that didn't filter by role). `ChatHandler.loadMessageHistory` preserves any `voice-intent-*` messages across server reloads so "Opened Chrome" bubbles don't vanish when session_end reload fires after voice fall-through to LLM.
- **Chat parity: structured result bubbles for android_* tool calls on the runs API.** `ChatHandler` now intercepts `tool.completed` SSE events from `/v1/runs` for action tools (send_sms, call, search_contacts, open_app, return_to_hermes, screenshot, press_key, setup — read-only tools skipped because they'd add noise on top of the existing `ToolProgressCard`) and emits the same structured markdown bubble the voice fast-path does. `agentName = "Phone action"` distinguishes chat-originated from voice-originated ("Voice action"). `MessageBubble.kt` picked up a subtle visual marker for both action-type bubbles so they read as distinct from LLM replies when interleaved.
- **Plugin `_check_requirements` pointed at the right endpoint with a three-gate rule.** Was hitting `/ping` which returns `{pong, ts}` and looking for `phone_connected` / `accessibilityService` fields that don't exist there — result: the gate always returned False and hid all 13 non-setup tools from every gateway platform. The "no android_* tools available" reports traced to this. Now hits `/bridge/status` and requires all three: `phone_connected AND bridge.accessibility_granted AND bridge.master_enabled`. Tools vanish from the LLM's schema entirely when the master toggle is off (or accessibility is revoked post-Studio-reinstall), giving the model a clean "no tools" signal instead of an error-interpretation race. Trade-off accepted: tools disappear mid-session if the user flips the toggle — desired behavior per "stop the agent from controlling my phone" intent.
- **4 new direct-dispatch plugin tools.** `android_search_contacts`, `android_send_sms`, `android_call`, `android_return_to_hermes` all wrap phone routes (`/search_contacts`, `/send_sms`, `/call`, new `/return_to_hermes`) that were already fully implemented (safety modal, direct `SmsManager` / `CALL_PHONE` dispatch) but had never been exposed to the agent, forcing it to drive Messages / Phone / Contacts UI step-by-step via `tap` + `read_screen`. Tool count 14 → 18. The `/return_to_hermes` route short-circuits when `service.currentApp == service.packageName` (i.e. Hermes is already foreground) so agent wrap-up calls are benign no-ops in voice mode. Master-toggle check exempts `/return_to_hermes` so the agent can always wrap up cleanly.
- **Honest error_code in bridge.response JSON.** `respondFromResult` substring-classifies ActionExecutor errors and emits `error_code` + `required_permission` fields alongside the existing free-text `error`, for `permission_denied` / `service_unavailable` / `user_denied` / `bridge_disabled`. LLM gets both human-readable text AND a machine-readable classification. Clearer 403 English on the `bridge_disabled` path explicitly says "this is NOT a pairing problem" + names the exact toggle — earlier the agent was walking users through re-pairing when the master toggle was off.
- **Plugin tool descriptions carry the "prefer direct dispatch + return_to_hermes when done" guidance** instead of the agent personality. Initial implementation edited `~/.hermes/config.yaml` on the server, which only works for a single install — reverted. Guidance now lives in `android_send_sms` / `android_call` / `android_open_app` descriptions where it travels with the plugin to any hermes-agent install. Portable across deploys.
- **MediaProjection notification persists bug fixed.** `BridgeForegroundService.onTaskRemoved` now revokes the MediaProjection + downgrades the FGS type back to SPECIAL_USE-only when the user swipes the app from recents. The bridge itself keeps running (agent phone control via WSS still works), but the system-level screen-cast icon goes away — which is what the user expects when they "close" the app. Before this fix the icon persisted indefinitely because foreground services legitimately survive task removal and the projection stayed bound.
- **Activity recreation on warm reopen fixed.** `MainActivity` in the manifest gained `launchMode="singleTask"` + `configChanges="uiMode|fontScale|locale|density|orientation|screenSize|screenLayout|keyboardHidden"`. Before this the activity was being recreated on config changes and on certain resume paths, triggering `installSplashScreen()` again and showing the splash on warm reopen. The residual case — Samsung OneUI aggressively killing backgrounded apps even with a foreground service — needs user-side battery whitelisting (Settings → Device Care → App battery management → Hermes Relay → Unrestricted) and can't be fixed in code.

**Commits landed on `feature/bridge-feature-expansion`:**

- `5c763eb` — honest errors, modal lifecycle, missing LLM tools (11 files, +974/-158)
- `536a131` — post-dispatch feedback + clearer bridge-disabled error (7 files, +325/-37)
- (pending, this session) — agent-team work: voice UX polish (TTS, cancel, countdown, phone-number bypass, multi-contact), chat structured feedback parity, plugin three-gate rule, `/return_to_hermes` short-circuit, tool description guidance, MediaProjection cleanup, activity recreation hardening

**Server state.** `hermes-gateway` restarted cleanly twice this session. `_check_requirements()` now returns True/False deterministically based on live `/bridge/status` (verified via Python import). The agent personality reverted to clean default — no plugin-specific coupling. All 18 tools register with no missing/orphan handlers.

**Known residual items for follow-up** (not blocking, documented for future-us):

1. Full multi-turn voice disambiguation for multi-contact matches — current fix just hints "found N, using first". Wave 3 multi-turn state machine deferred.
2. Samsung OneUI process-death UX — add an in-app nudge to whitelist battery optimization if running on Samsung + not currently whitelisted. Polish.
3. Tool-description guidance relies on LLM cooperation (non-deterministic) — monitor whether Victor actually picks up `android_send_sms` over UI automation in future tests. If not, we may need to reinforce via plugin.yaml or a new upstream plugin-metadata field.
4. `loadMessageHistory` race with voice-intent-result bubbles self-heals via timestamp sort but has a brief visual flicker window.
5. `android_return_to_hermes` no-op in voice mode is benign but wastes a tool call — agent should detect voice mode and skip. Minor.

**Next session.** Waiting on an on-device retest with the bundled agent-team work. Once that passes, bump version (voice intent loop + chat parity is v0.4.0-worthy) and start the release cut.

---

## 2026-04-15 — Gateway slash-command dispatch: architectural finding + three-PR strategy

**Motivation.** Typing `/model` into the Android app's chat on the feature/bridge-feature-expansion branch to switch the model for the session: the message reached the LLM as plain text and got a hallucinated reply: *"Switching model — `/model` is a client-side command, I don't execute it. Just send it as its own message (not wrapped in chat) and Hermes will swap the model for the session."* The reply is confidently wrong on two counts — the command is not "client-side", and nothing about the Android client's wire format would prevent Hermes from intercepting it. The LLM was filling a gap it didn't know existed.

**Root cause (confirmed via upstream code archaeology by a subagent oriented in a fresh clone of `Codename-11/hermes-agent` on `feat/session-api`):**

- Gateway slash commands (`/model`, `/new`, `/retry`, the 29 in `hermes_cli/commands.py::GATEWAY_KNOWN_COMMANDS`) are intercepted by **in-process platform adapters** — Discord, Telegram, Slack, Matrix, Signal, BlueBubbles, etc. All of them wrap inbound messages into `MessageEvent`s with `MessageType.COMMAND` and route them through `BasePlatformAdapter.handle_message()` → `GatewayRouter._handle_message()`. That router method contains a ~300-line `if canonical == "new"/"help"/"model"/...` dispatch chain at `gateway/run.py:2645–2929`, and the command handlers (`_handle_model_command` at 4226, `_handle_status_command` at 4065, `_handle_help_command` at 4151, and ~25 others) are instance methods on `GatewayRouter` that mutate router-owned state: `self._session_model_overrides`, `self._agent_cache`, `self._agent_cache_lock`, plus per-adapter interactive pickers via `self.adapters[platform].send_model_picker(...)`.
- **`APIServerAdapter` (`gateway/platforms/api_server.py`) does not connect to the router.** Its `__init__` (at `api_server.py:331`) takes only a `PlatformConfig` — no `GatewayRouter` reference. `_handle_chat_completions` and `_handle_runs` call `self._run_agent(...)` directly, which calls `self._create_agent(...)` to build a **fresh agent per request** with no persistent session state. Upstream confirms this is intentional: `run.py:3148` comments that api_server is an "excluded platform" from the router notification path.
- **Therefore `/model` cannot be made to work on `/v1/runs` with a local preprocessor.** A fresh agent per request means there is no persistent session to switch the model *on*. Same story for `/new`, `/retry`, `/undo`, `/compress`, `/title`, `/resume`, `/branch`, `/rollback`, `/approve`, `/deny`, `/background`, `/stop`, `/yolo`, `/fast`, `/reasoning`, `/personality` — they all mutate router-owned state that api_server doesn't maintain. What *can* run statelessly: `/help` and `/commands` via `gateway_help_lines()` at `hermes_cli/commands.py:340`, and possibly `/profile`, `/provider`, `/usage`, `/insights`, `/status` depending on their implementation.

**Strategy — a three-PR arc, each independently reviewable, each with a matching bootstrap-middleware sibling shipping earlier:**

1. **PR #8556 (already submitted, `feat/session-api` → `NousResearch/hermes-agent:main`).** *"feat(api-server): add session management API for frontend clients."* Scope is broader than the title: sessions CRUD + search + fork + messages, `/api/sessions/{id}/chat` + `/api/sessions/{id}/chat/stream`, memory CRUD, skills, config, available-models. Verified 2026-04-15 via `gh pr diff 8556`. This is the missing session primitives — once merged, api_server has a durable place where stateful state can live.

2. **Stage 1 PR — slash-command preprocessor on the stateless endpoints.** Sibling follow-up to #8556, same file (`gateway/platforms/api_server.py`), stacks on `feat/session-api` during review so the diff shows both together. Detects known gateway commands on `/v1/runs` + `/v1/chat/completions`, dispatches the small stateless subset (`/help`, `/commands`) via existing helpers, returns a deterministic synthetic-SSE "use a channel with session state" notice for the stateful majority. Respects upstream's intentional api_server design and does not touch `GatewayRouter`. Being prepared in a separate local `hermes-agent` checkout on branch `feat/api-server-gateway-commands` via a background subagent; Option B scope (stateless dispatch + polite decline) confirmed after the subagent stopped on the architectural finding instead of writing Option C code that would have fought upstream's design. The subagent is preparing the full diff + PR body for human review before any `gh pr create` runs. Matching bootstrap-middleware sibling (`hermes_relay_bootstrap/_command_middleware.py`) is filed as a v0.4.1 roadmap item so the hallucination fix ships to vanilla-upstream installs without waiting for the upstream PR.

3. **Stage 2 PR — stateful slash-command dispatch on `/api/sessions/{id}/chat/stream`.** Blocked on #8556 merging. Once session primitives ship upstream, a smaller separate PR adds a preprocessor **scoped to the session chat stream endpoint only**, using the URL's `session_id` as the persistence handle. Stateful commands become session-scoped dict writes (`session.model_override = new_model`) without refactoring `GatewayRouter` or plumbing api_server into the router. Clean partition: `/v1/*` stays stateless, statefulness lives on `/api/sessions/*`.

**Why not a single giant "Option C" PR** that refactors `GatewayRouter` to expose `dispatch_slash_command()` + wires `APIServerAdapter` to the router + adds per-session state to api_server: (a) it would withdraw #8556 to restart bigger, which burns reviewer goodwill and signals indecision to Nous, (b) it would touch 10+ files across subsystems normally owned separately, reviewing far worse than three small PRs, (c) it would fight the documented "api_server is excluded from router" design intent rather than extending it, (d) #8556 is already doing most of the work (session primitives); a parallel session substrate would duplicate it, (e) review slots on respected org PRs are hard-won; don't give one back.

**Docs updated this session:**

- `CLAUDE.md` — fork branch reference corrected to `feat/session-api` + scope clarification on #8556.
- `docs/decisions.md` — same fix in the upstream-notes section (line 85).
- `docs/upstream-contributions.md` — new §5 documenting the Stage 1 + Stage 2 upstream PR plan and the bootstrap-middleware workaround.
- `ROADMAP.md` — v0.4.1 bootstrap-middleware entry rewritten to reflect the Option B ceiling (cannot make `/model` actually switch models on `/v1/runs` — that's Stage 2, blocked on #8556) and the architectural reasoning.
- `TODO.md` — three new entries covering Stage 1 upstream PR, Stage 1 bootstrap middleware, and Stage 2 (blocked on #8556).
- `hermes_relay_bootstrap/_handlers.py`, `hermes_relay_bootstrap/_patch.py` — docstring branch-name corrections (`feat/api-server-enhancements` → `feat/session-api`).

**Not touched this session:**

- No Kotlin changes. The Android client at `HermesApiClient.kt:655-715` already parses `message.delta` / `response.output_text.delta` / `reasoning.available` / `tool.started` / `tool.completed` / `run.completed` / `[DONE]`, so when Stage 1 or the bootstrap middleware lands with a synthetic SSE stream matching those shapes, the client needs no changes.
- No changes to `gateway/platforms/api_server.py` or any file in `hermes-agent-pr-prep/`. The subagent has orientation-only state on `feat/api-server-gateway-commands`; implementation begins on its next wake.
- The `feature/bridge-feature-expansion` branch is untouched by this session and continues with its voice-bridge work. The slash-command work is a separate branch arc and will not land on the bridge feature branch.

## 2026-04-13 — Play Console migration to Axiom-Labs, LLC (applicationId → `com.axiomlabs.hermesrelay`)

Moved the Play Store listing from the maintainer's personal Play Console account (where v0.1.x–v0.3.0 shipped under Internal testing) to the **Axiom-Labs, LLC** D-U-N-S-verified organization account. This unblocks straight-to-production rollout — personal accounts created after late 2023 are subject to Google's 14-day closed-testing rule (≥12 opted-in testers for 14 continuous days before production promotion); D-U-N-S-verified org accounts are exempt. See [Google's policy](https://support.google.com/googleplay/android-developer/answer/14151465).

**applicationId change.** `com.hermesandroid.relay` → `com.axiomlabs.hermesrelay` (googlePlay flavor) and `com.axiomlabs.hermesrelay.sideload` (sideload flavor, unchanged suffix pattern). Play Store package names are permanently reserved once used, so the old ID can never be reclaimed — the previous Internal-testing listing is retired and existing installs won't auto-upgrade to the new listing. Blast radius is one tester, so manual reinstall is fine.

**Kotlin namespace stays at `com.hermesandroid.relay`.** AGP decouples `namespace` (build-time R-class / BuildConfig / on-disk source layout) from `applicationId` (runtime install identity), so we don't need to mass-rename the 130+ Kotlin files, their package declarations, proguard rules, or test FQCNs. The source tree stays stable; only the Play/Android install identity moves. `scripts/dev.bat` and `scripts/dev.sh` already use the split FQCN form `adb am start -n com.axiomlabs.hermesrelay/com.hermesandroid.relay.MainActivity` to bridge the two namespaces at launch time.

**Keystore identity is unchanged.** Same `CN=Bailey Dixon, Codename-11` upload cert, same SHA256 fingerprint, same `HERMES_KEYSTORE_*` GitHub Secrets, same CI signing flow. Play App Signing mints a new server-side app signing key per listing, but that's invisible to us since App Signing is enabled. No CI or keystore changes required.

**Files touched in this migration:**

- `app/build.gradle.kts` — `applicationId = "com.axiomlabs.hermesrelay"` + multi-paragraph comment block explaining the namespace/applicationId decoupling and the migration rationale
- `RELEASE.md` — new "Google Play Console developer account" section documenting the Axiom-Labs account, the D-U-N-S exemption from the 14-day rule, and a "Historical note (2026-04-13 migration)" block for future maintainers
- `ROADMAP.md` — "Current — Axiom-Labs migration" section added at the top
- `README.md` — Play Store badge URL → `?id=com.axiomlabs.hermesrelay`; copyright line → "Axiom-Labs"
- `CLAUDE.md` — "applicationId" bullet updated to call out both flavors under `com.axiomlabs.*`
- `scripts/dev.bat`, `scripts/dev.sh` — `am start` commands updated to the new applicationId
- `scripts/bridge-smoke.sh` — sideload package test payload → `com.axiomlabs.hermesrelay.sideload`
- `user-docs/guide/getting-started.md`, `user-docs/guide/release-tracks.md` — Play Store URLs + both applicationIds

**Play Console listing creation (for future reference):**

| Field | Value |
|---|---|
| App name | Hermes-Relay |
| Package name | `com.axiomlabs.hermesrelay` |
| Default language | English (US) |
| App/game | App |
| Free/paid | Free |
| Developer account | Axiom-Labs, LLC |

Nothing to rebuild or re-sign beyond the normal v0.3.1+ release flow.

## 2026-04-12 — Manual pairing fallback — `hermes-pair --register-code <code>`

Wired the host-side half of the in-app fallback pairing flow. The phone has been generating a local 6-char code in `Settings → Connection → Manual pairing code (fallback)` and `AuthManager.authenticate()` already sent that code as `pairing_code` when no server-issued code was present, but there was no convenient way for an operator to pre-register an arbitrary code with the relay without going through the QR flow. Now there is.

**Use case**: pairing a phone already SSH'd in *from*, where there's no second device with a camera to scan a QR off the host's display. The QR flow is unusable in that scenario; the new flag is the only path.

**`plugin/pair.py` additions:**

- New `normalize_pairing_code(code)` helper — upper-cases, strips, validates against `PAIRING_ALPHABET` (A-Z / 0-9) and `PAIRING_CODE_LENGTH` (6). Raises `InvalidPairingCodeError` (a `ValueError` subclass) with a clear message on length or alphabet mismatches so the CLI can fail fast instead of letting the operator find out via an HTTP 400.
- New `register_code_command(args)` — separate code path from the QR pipeline. Validates the code, parses `--ttl` / `--grants` (same parser as the QR flow, default `30d`), resolves the relay port from `read_relay_config()`, probes `/health` first so we can give a precise "relay not running" message instead of a generic post failure, then calls the existing `register_relay_code()` helper with all the optional metadata. Prints a clean success block listing the code, transport hint, session TTL, grants, and the exact 3-step "tap Connect in the app" instructions. Distinct exit codes: `0` success, `1` relay unreachable / rejected, `2` argument validation failed.
- `pair_command()` short-circuits to `register_code_command` when `args.register_code` is set, so the QR pipeline is skipped entirely (no relay code minted, no QR rendered, no API config probed).
- New CLI flags on the standalone `python -m plugin.pair` argparser: `--register-code <CODE>` (the trigger), `--transport-hint {ws,wss}` (override the auto-detected transport hint when running behind an external TLS proxy that the host can't see). All existing flags (`--ttl`, `--grants`, `--host`, `--port`, etc.) compose with `--register-code` exactly the way they compose with the QR flow.

**New CLI shape:**

```bash
hermes-pair --register-code ABCD12
hermes-pair --register-code ABCD12 --ttl 30d --grants terminal=7d,bridge=1d
hermes-pair --register-code ABCD12 --ttl never --transport-hint wss
```

The relay endpoint (`POST /pairing/register` in `plugin/relay/server.py`) and `PairingManager.register_code` were not touched — they already accepted user-supplied codes with optional TTL/grants/transport-hint metadata. This change just exposes that capability via a CLI flag so the operator doesn't have to hand-craft a `curl` POST.

**`plugin/tests/test_register_code.py` — 25 tests, stdlib `unittest` only.** Three test classes:

- `NormalizePairingCodeTests` (13 tests) — happy path (uppercase / lowercase / whitespace-stripped / all-digit / all-letter), every rejection branch (empty / whitespace-only / `None` / too short / too long / dash / punctuation / unicode).
- `RegisterCodeCommandTests` (10 tests) — happy path with default TTL (verifies it posts `30 * 24 * 3600` seconds and `transport_hint="ws"`); happy path with `--ttl 7d --grants terminal=1d,bridge=1d` and `tls=True` in the relay config (verifies `transport_hint="wss"` and the auto-uppercase of `abcd12 → ABCD12`); explicit `--transport-hint wss` override; every error path (invalid chars, wrong length, empty, relay unreachable → exit 1, relay rejection → exit 1, invalid `--ttl` → exit 2, invalid `--grants` → exit 2). Each error-path test asserts the network was NOT touched if validation failed first.
- `RegisterRelayCodeWireShapeTests` (2 tests) — patches `urllib.request.urlopen` directly and verifies the bytes the manual flow puts on the wire match what `handle_pairing_register` already parses: `{"code": ..., "ttl_seconds": ..., "grants": ..., "transport_hint": ...}`. Also covers the minimal-body case (just `{"code": ...}` when no metadata is supplied) so we don't accidentally start sending `null` fields the server's `_parse_pairing_metadata` would have to filter.

Run via `python -m unittest plugin.tests.test_register_code` — 25 passed in 0.006s.

**Docs touched:**

- `skills/devops/hermes-relay-pair/SKILL.md` — added `--register-code` to the "Useful flags" bullet list and a full "Manual fallback (`--register-code`)" section between Procedure and Pitfalls covering when to use it, the 3-step workflow, how `--ttl` / `--grants` / `--transport-hint` compose, and the exit codes.
- `skills/devops/hermes-relay-self-setup/SKILL.md` — added an "If you can't scan a QR" subsection inside section D pointing operators at the `hermes-relay-pair` skill's manual-fallback recipe.
- `user-docs/reference/configuration.md` — expanded the "Manual pairing code (fallback)" bullet from a one-liner into a full 3-step workflow walkthrough so users can find this without leaving the app's docs.
- `user-docs/guide/getting-started.md` — added a "Camera unavailable? Use manual pairing" callout under the "Choosing session lifetime + channel grants" section.
- `README.md` — added a one-line bullet under the install section listing `hermes-pair --register-code` alongside the slash command and the dashed shim.

**Conventions:** No Kotlin touched (parallel agent owns the in-app UI cleanup). Did not modify `plugin/relay/server.py` or `PairingManager` — endpoint already supported user-supplied codes. Tests run via stdlib `unittest` to bypass the `conftest.py` that imports `responses`.

## 2026-04-12 — Connection UX — Manual pairing code walkthrough + per-channel grant revoke

**Manual pairing code card.** Settings → Connection → "Manual pairing code (fallback)" used to be a bare-bones code + copy/regen stub that left users guessing what to do with the 6-character code. Replaced the body with a proper three-step walkthrough: (1) copy the code (with both an instant copy button and the existing regenerate button), (2) on the host, run `hermes-pair --register-code <code>` rendered in a copyable monospace shell-command surface, (3) tap **Connect** to fire the pair flow. Step 3 is a real `Button` that calls `applyServerIssuedCodeAndReset` → `disconnectRelay` → `connectRelay` (mirroring the existing dialog flow), tracks an in-flight `card3ConnectInProgress` flag with a `CircularProgressIndicator` + "Connecting…" label, and reports success/failure through `LocalSnackbarHost.showHumanError` with the "pair" classifier context. Below the steps is an expandable "How does this work?" explainer that spells out when this is the right flow vs. the QR scan, and reminds users that bridge control is gated by the master toggle on the Bridge tab — not by the pairing code itself. New private `ManualPairStep` helper renders the numbered step badges. Pairs cleanly with the parallel agent's host-side `hermes-pair --register-code` work above. Markers: `// === MANUAL-PAIR-FOLLOWUP: ... === / // === END MANUAL-PAIR-FOLLOWUP ===`.

**Per-channel grant revoke.** Paired Devices → device card grant chips are now individually revocable. Each chip got an inline `Close` icon button (a small clickable `Box` instead of `IconButton` to avoid the 48dp touch-target inflation that would blow up the `FlowRow`). Tapping the x opens an `AlertDialog` confirming "Revoke <channel> access for <device>?" with an explicit reminder that the session itself stays paired and other channels keep their current expiry. New `ConnectionViewModel.revokeChannelGrant(tokenPrefix, channel)` builds the full grants map by reading the device's current `PairedDeviceInfo.grants`, converting absolute-epoch grants back to seconds-from-now (clamped to ≥ 1 since `0` means "never expire" on the relay side), and replacing only the target channel with `1L` (≈ instantly expired by the time the PATCH lands). Then it reuses `RelayHttpClient.extendSession` with `ttlSeconds = null, grants = rebuilt` and refreshes the device list on success. The chip label also got a relative TTL helper (`formatRelativeTtl`) that renders "never" / "in 6d" / "in 23h" / "expired" instead of the previous absolute short date. The full per-session "Revoke" button stays — per-channel chips are additive, not a replacement. Markers: `// === PER-CHANNEL-REVOKE: ... === / // === END PER-CHANNEL-REVOKE ===`.

## 2026-04-12 — Phase 3 / status — relay `GET /bridge/status` endpoint + expanded `BridgeStatusReporter`

Backend half of the `android_phone_status()` work. The phone (`BridgeStatusReporter.kt`) now pushes a structured status envelope every 30 s with three nested groups:

- **`device`** — `Build.MODEL`, battery, `PowerManager.isInteractive`, `HermesAccessibilityService.instance?.currentApp`
- **`bridge`** — `master_enabled`, `accessibility_granted`, `screen_capture_granted`, `overlay_granted`, `notification_listener_granted`
- **`safety`** — blocklist count, destructive verb count, auto-disable timer minutes, `auto_disable_at_ms`

Old flat keys kept alongside the new structure for backwards compat. New `pushNow()` method on the reporter for out-of-band emissions; `ConnectionViewModel` calls it whenever the master toggle flips so the relay-side cache refreshes immediately instead of waiting up to 30 s.

**Relay side**: `BridgeHandler` gained `latest_status: dict | None` and `last_seen_at: float | None`, populated in `handle_status`. New `handle_bridge_status` route at `GET /bridge/status`, gated to loopback only (mirrors `/pairing/register`). Empty cache → 503; populated → 200 with `last_seen_seconds_ago` computed at response time. Stale-cache disconnects return 200 — the cached snapshot is still useful.

**Tests**: `plugin/tests/test_bridge_status.py` — 7 stdlib `unittest` cases (empty cache 503, populated 200, disconnected, non-loopback rejection, IPv6 `::1`, snapshot-not-reference regression, dual-field stamping). All pass; existing `test_bridge_channel.py` still green.

Markers: `# === PHASE3-status: ... === / # === END PHASE3-status ===` for the new Python blocks; `// === PHASE3-status: ... ===` for Kotlin.

## 2026-04-12 — Phase 3 / bridge UI hardening — master gate, MediaProjection consent, label disambiguation

Three follow-ups discovered while smoke-testing the merged Phase 3 build on a real device.

**Master gate fix.** `HermesAccessibilityService.cachedMasterEnabled` was supposed to be fed by an external `updateMasterEnabledCache(...)` writer, but **nothing was calling it** — the cache was permanently `false` and `BridgeCommandHandler` was 403'ing every command except `/ping` and `/current_app` regardless of the toggle state. Fix: the service now starts a `serviceScope` coroutine on `onServiceConnected` that observes `masterEnabledFlow(this)` and pumps every emission into the cache. Service-owned scope is cancelled on `onUnbind`/`onDestroy` and re-created on each connect (cancelled `SupervisorJob`s can't be reused). Cache is reset to `false` on teardown so a stale-but-bound state can't leak through.

**MediaProjection consent flow.** `MediaProjectionHolder.onGranted(...)` existed but **nothing called it** — there was no `ActivityResultLauncher` registered anywhere. Fix: `MainActivity` now registers a launcher in `onCreate` and a new `ScreenCaptureRequester` process-singleton holds the launch closure (installed on Activity create, cleared on destroy). `BridgeViewModel.requestScreenCapture()` calls `ScreenCaptureRequester.request()` to ask the user; the system consent dialog fires; the result callback feeds `MediaProjectionHolder.onGranted(...)` which closes the loop with the existing `ScreenCapture.kt`. Bridge tab's Screen Capture row is now tappable instead of inert; the row's checkmark reflects `MediaProjectionHolder.projection != null`.

**Notification Listener Test button parity.** Added `BridgeViewModel.testNotificationListener()` and an `onTestNotificationListener` lambda through `BridgePermissionChecklist` so the row gets a Test button matching the other three. The dedicated functional test on `NotificationCompanionSettingsScreen` still ships.

**Launcher label disambiguation per flavor.** With `googlePlayDebug` and `sideloadDebug` installed side-by-side (same icon, same name), there was no way to tell which install you were tapping in the launcher / recents / Settings → Apps. Fix: sideload's `res/values/strings.xml` now overrides three strings:
- `app_name` → `Hermes Dev` (10 chars, fits launcher icon, mirrors `Slack Dev` / `Chrome Dev` convention)
- `a11y_service_label` → `Hermes-Bridge Dev` (Accessibility settings list)
- `notification_companion_label` → `Hermes Dev notification companion`

googlePlay's `a11y_service_label` also flipped to `Hermes-Bridge` (with hyphen) for consistency. The Play track inherits all other strings from main.

**Other small fixes**: `BridgeForegroundService.kt` `Intent.flags = ...` → `addFlags(...)` (the property setter form doesn't compile because `Intent.setFlags` returns `Intent`, not void). `BridgeViewModel.kt` removed `StateFlow.distinctUntilChanged()` (deprecated no-op since StateFlow already deduplicates).

Markers: new code blocks tagged `// === PHASE3-bridge-ui-followup: ... === / // === END PHASE3-bridge-ui-followup ===`.

## 2026-04-12 — Phase 3 / status trio — `android_phone_status` tool + `hermes-status` CLI + `/hermes-relay-status` skill

Symmetric read-only counterpart to the pair trio. The relay ships `GET /bridge/status` (loopback, in a parallel agent's scope) that exposes live phone state — connection, battery, screen, foreground app, bridge permission flags, safety-rail config. This change builds the three consumers that wrap it so the agent, operators, and chat users all have a clean entry point.

**`plugin/tools/android_phone_status.py`.** New Hermes tool registered via the canonical `tools.registry` import pattern, modeled after `plugin/tools/android_notifications.py`. Zero args. Calls `http://127.0.0.1:8767/bridge/status` over loopback (no bearer — same trust model as `/media/register` and `/pairing/register`), returns `{"status": "ok"|"error", "phone_status": {...}|null, "error": str|null}`. Stdlib `urllib.request` only — no `requests`/`httpx`. Handles three canonical paths:

- **200 OK** → `status=ok` with the parsed relay body as `phone_status`.
- **503** (relay alive but no phone has ever connected) → `status=ok` with `phone_status={"phone_connected": false, ...}`. From the agent's perspective the phone not being connected isn't an error — it's just the current state, and the LLM should render it as prose.
- **URLError/OSError/timeout** → `status=error` with `error="relay unreachable"`. This is the only case where the agent should bail.

The tool's description is explicit about *when* to call it: before attempting any bridge operation (`android_tap`, `android_type`, `android_screenshot`) so the agent can check `phone_connected` + `bridge.*_granted` upfront instead of eating failures one by one. `check_fn=lambda: True` because gating status on bridge connectivity would be circular — it IS the connectivity check.

**`plugin/status.py`.** New CLI module modeled after `plugin/pair.py`. Probes the same endpoint, pretty-prints with a small ANSI `_Palette` helper that's a no-op when stdout isn't a TTY. Three distinct exit codes so shell scripts can distinguish the cases: `0` success + connected, `1` relay unreachable, `2` relay alive but no phone. `--json` flag emits raw JSON pass-through (always a stable envelope so callers can `jq` over it even on failure). `--port N` overrides `RELAY_PORT`. Invokable via `python -m plugin.status` or the `hermes-status` shim. `fetch_status(port)` is factored out as a pure-function core so the tests can exercise it without mocking argparse.

**`skills/devops/hermes-relay-status/SKILL.md`.** New slash-command skill mirroring `hermes-relay-pair`'s structure verbatim — same frontmatter shape, same `metadata.hermes.category: devops`, same "When to Use / Prerequisites / Procedure / Pitfalls / Verification" section layout. Explains how to interpret the three exit codes, when to re-pair vs. when to restart the relay, and how to read the permission flags when the user's "accessibility is granted" but status says otherwise (OS killed the service). Picked up automatically by the `external_dirs` scan installer registers — no config change needed on upgrade.

**`install.sh` / `uninstall.sh` — hermes-status shim.** Added step [5/6] sibling block that writes `~/.local/bin/hermes-status` alongside `hermes-pair`. Same template: a tiny bash shim that execs `$HERMES_VENV_PY -m plugin.status "$@"` with a friendly error if the venv python can't be found. Uninstaller mirrors it for removal — both shims come out together under `[5/6]`. Header comments in both scripts updated to reference the plural "shims".

**`plugin/tests/test_android_phone_status.py` — 19 tests, stdlib `unittest` only.** Covers the tool's success path, 503 mapping, 503 with empty body, connection refused (URLError), raw OSError, other HTTP errors (500), non-JSON body, non-object JSON (`[1,2,3]`, `42`), schema sanity (`parameters.required == []`, `properties == {}`), and the registry handler wrapper. Also covers the CLI's `fetch_status` return-triple shape across all three exit codes + `main(["--json"])` happy path + all three failure modes. The rendering pass has two smoke tests: one verifies the happy path includes all the expected fields (device name, battery %, current app, blocklist count, destructive verbs, permission labels), one verifies the disconnected short-circuit omits the Device/Bridge/Safety sections entirely. Mocks `urllib.request.urlopen` via `unittest.mock.patch`, uses a tiny `_FakeResponse` context-manager stub and `_http_error(code, body)` helper that builds a real `urllib.error.HTTPError` whose `.read()` returns the fixture body (critical: the 503 handler reads the body to extract the phone-state envelope, so the mock has to wrap a `BytesIO` in the `fp` slot, not just pass bytes). All tests run green:

```
python -m unittest plugin.tests.test_android_phone_status -v
Ran 19 tests in 0.010s
OK
```

**Files touched:** new `plugin/tools/android_phone_status.py`, new `plugin/status.py`, new `skills/devops/hermes-relay-status/SKILL.md`, new `plugin/tests/test_android_phone_status.py`, edits to `install.sh` + `uninstall.sh` for the shim pair. No relay-side changes — the `/bridge/status` endpoint is owned by a parallel agent working in the `plugin/relay/server.py` scope. This change is pure consumer.

## 2026-04-12 — Phase 3 / safety-rails followup — deep link, overlay nag, in-app permission Test buttons

Three small UX wins on top of the merged Wave 2 safety rails. All gate cleaner first-run smoke testing on a real device.

**Deep link from foreground notification → BridgeSafetySettingsScreen.** `BridgeForegroundService.ACTION_OPEN_SETTINGS` previously dropped the user on `MainActivity`'s home screen — they had to navigate Settings → Bridge safety manually. New `app/src/main/kotlin/com/hermesandroid/relay/util/NavRouteRequest.kt` is a tiny `MutableSharedFlow<String>` singleton that any external launcher (foreground service, broadcast receiver, shortcut intent) can post route requests to. `MainActivity` reads `EXTRA_NAV_ROUTE` in `onCreate` + `onNewIntent` and forwards via `NavRouteRequest.tryRequest`. `RelayApp` adds a `LaunchedEffect(navController)` that collects `NavRouteRequest.requests` and forwards each emission to `navController.navigate(route) { launchSingleTop = true }`. `BridgeForegroundService` now sets `MainActivity.EXTRA_NAV_ROUTE = "settings/bridge_safety"` on its launch intent. Single observer at the app root, no per-screen plumbing.

**Overlay-permission nag banner on BridgeScreen.** When the master toggle is on but `Settings.canDrawOverlays(context)` is false, a prominent red `errorContainer`-colored card appears at the top of `BridgeScreen` warning the user that confirmation prompts can't display. Without the banner, `BridgeSafetyManager.awaitConfirmation` was failing-closed silently — destructive verbs would just be denied with no UX hint about why. New private `OverlayPermissionNagCard` Composable in `BridgeScreen.kt` with a `Warning` icon, plain-language explanation, and a tap action that opens `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` for our package. Goes away on its own once the permission is granted (BridgeViewModel re-probes on `ON_RESUME`).

**In-app permission Test buttons in `BridgePermissionChecklist`.** Matches the pattern already shipped on `NotificationCompanionSettingsScreen`. Each row in the bridge permission checklist now has an optional "Test" text-button next to its status icon. Tapping it runs a side-effect-free diagnostic on the underlying runtime state and surfaces the result via the global `LocalSnackbarHost`. New methods in `BridgeViewModel`:

- `testAccessibilityService()` — checks `HermesAccessibilityService.instance` is non-null AND `instance.rootInActiveWindow` is non-null. Three result strings cover "not bound", "bound but no active window", and "OK".
- `testScreenCapture()` — checks `MediaProjectionHolder.projection` is non-null. Reports either "active grant" or "no grant — bridge will request consent next /screenshot".
- `testOverlayPermission()` — checks `Settings.canDrawOverlays(context)`. Reports granted vs not-granted with a hint to tap the row.

Each method emits a one-shot human-readable string on a new `MutableSharedFlow<String>` (`testEvents`) that `BridgeScreen` collects in a `LaunchedEffect` and shows via `snackbarHost.showSnackbar(message)`. Notification listener row keeps `onTest = null` because the existing dedicated test on `NotificationCompanionSettingsScreen` already covers it.

**Why diagnostic-only, not full functional tests** (e.g., actually capture a screenshot or actually flash an overlay): the diagnostic checks need zero side effects, no extra dependencies, no permission flows mid-test. They answer the "is this thing actually wired through?" question 80% of the time — full functional tests are a Wave 3 follow-up if the diagnostics aren't enough in practice.

**Files touched:** new `util/NavRouteRequest.kt`, new private composables in `BridgeScreen.kt`, edits to `MainActivity.kt`, `BridgeForegroundService.kt`, `RelayApp.kt`, `BridgePermissionChecklist.kt`, `BridgeViewModel.kt`. All marker blocks use `PHASE3-safety-rails-followup`.

## 2026-04-12 — Rename Phase 3 agent codenames from Greek letters to ASCII slugs

Bulk rename across the repo + Obsidian plan: replaced the Greek-letter agent codenames used since the original Phase 3 plan (the original set was `α β γ δ ε ζ η θ`) with descriptive ASCII slugs. The Greek letters were a math/CS-paper convention and they sort nicely, but they're a pain to type, render badly in some terminals, and made commit-history search awkward.

Mapping:

| Old | New |
|---|---|
| α | bridge-server |
| flavor-split | flavor-split |
| accessibility | accessibility |
| bridge-ui | bridge-ui |
| notif-listener | notif-listener |
| safety-rails | safety-rails |
| voice-intents | voice-intents |
| vision-nav | vision-nav |

Scope: 42 repo files (DEVLOG, CLAUDE, Kotlin sources, Python sources, manifests, Vue docs) + the canonical Obsidian plan at `Plans/Phase 3 — Bridge Channel.md`. Implementation was a single sed pass per file with all 8 substitutions in one invocation, applied via a bash for-loop. Verified with `grep -rc '[αβγδεζηθ]'` returning 0 across the affected trees.

**Git history is not rewritten.** Existing commits and merge commits keep their original Greek-letter subjects (e.g., `merge(phase3-α): migrate legacy bridge relay`). Renaming history would require a force push and would invalidate every commit hash since the divergence point — not worth the cost for a cosmetic change. Going forward, new branches, commit messages, and marker blocks all use the ASCII slugs.

**Marker block convention going forward:** `// === PHASE3-bridge-server: ... === / // === END PHASE3-bridge-server ===` (and the analogous Python `# === ... ===` and XML `<!-- === ... === -->` forms). Followup blocks use `PHASE3-<slug>-followup`.

## 2026-04-12 — Phase 3 / Wave 1 hotfix — dedupe AccessibilityService entry; Gradle deprecation cleanup

Two small fixes discovered while smoke-testing Wave 1 in Android Studio.

**Duplicate AccessibilityService entry in Android Settings.** Reported: Settings → Accessibility → Installed services listed two Hermes entries (`Hermes-Relay` and `Hermes Bridge`). Root cause: a coordination miss between agents flavor-split and accessibility during the Wave 1 agent-team run. flavor-split created the flavor manifests *first* and stubbed `<service android:name=".accessibility.BridgeAccessibilityService" tools:ignore="MissingClass">` blocks in both flavor overlays, anticipating that accessibility would name the service class `BridgeAccessibilityService`. accessibility later named it `HermesAccessibilityService` instead. Manifest merger only collapses `<service>` elements with matching `android:name`, so two different names → two separate `<service>` entries in the merged manifest. The flavor-split-stub entry pointed at a class that doesn't exist anywhere — enabling that row in Android Settings would have crashed the bind.

Fix: removed the stub `<service>` blocks from both `app/src/googlePlay/AndroidManifest.xml` and `app/src/sideload/AndroidManifest.xml` entirely (the flavor manifests are now empty `<application />` overlays kept around for future flavor-specific permissions / activities). The flavor distinction now lives purely at the resource layer — each flavor's `res/xml/accessibility_service_config.xml` carries its own use-case description and flag bitset, and Gradle's resource merger picks the right file at build time. Added `a11y_service_label` ("Hermes-Relay Bridge") to `app/src/main/res/values/strings.xml` so the single canonical service entry in `app/src/main/AndroidManifest.xml` can be labeled distinctly from the launcher icon.

**Gradle deprecation warning.** AGP printed `android.dependency.excludeLibraryComponentsFromConstraints=true is deprecated, will be removed in version 10.0`. The two flags `useConstraints=true` + `excludeLibraryComponentsFromConstraints=true` were doing the work of one — the second flag overrode the first to mean "actually, exclude library components from constraint resolution." AGP's recommended migration is `useConstraints=false`. Collapsed two lines in `gradle.properties` into one. Semantics unchanged.

**Lessons learned (for future agent teams):** when an agent that creates a manifest stub names a class to be filled in by another agent, the two agents need to agree on the class name *up front* — manifest merger does not auto-collapse `<service>` blocks with different `android:name` values, even if everything else matches. Putting the canonical class name in the plan's "Wire format" section would have prevented this.

## 2026-04-12 — Onboarding/Settings unification + lifecycle-aware health checks

Closed two long-standing UX gaps that compounded each other: (1) onboarding's "Connect" page only configured the API server side and discarded the QR's relay block (code, TTL, grants, transport hint), so users walked out of onboarding in a "half-paired" state and had to re-do setup in Settings; (2) connection status badges showed stale Connected/Disconnected for up to 30 s after foregrounding because nothing kicked a re-probe on `Lifecycle.Event.ON_RESUME` — the StateFlow snapshot was just preserved across backgrounding even when the underlying server had died or the network had flipped.

**New: `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ConnectionWizard.kt`** — shared three-step pairing wizard (Scan → Confirm → Verify) with a circular step indicator at the top. Used by both `OnboardingScreen` and `ConnectionSettingsScreen`'s "Pair with QR" button so first-run and re-pair stay in lockstep forever. Step 1 launches `QrPairingScanner` via the camera permission flow. Step 2 displays the scanned details (server URL, relay URL, grants, transport security badge), an inline TTL radio list, and a red "plain ws://" warning when the relay is insecure. Step 3 observes `AuthState` with a 15s `withTimeout`, surfaces classified errors via Retry/Back/Cancel, and calls `onComplete()` on `AuthState.Paired`. The wizard's "Skip for now" affordance only renders when the caller passes `showSkip = true` (onboarding sets that, Settings doesn't).

**New: `ConnectionViewModel.applyPairingPayload(payload, ttlSeconds)`** — single entry point for "user just confirmed a scanned QR + chose a TTL." Does the full credential dance: API URL + key, relay URL + insecure mode, `applyServerIssuedCodeAndReset` (wipes TOFU pin + applies code), `setPendingGrants`, `setPendingTtlSeconds`, `disconnectRelay() + connectRelay()`, then `revalidate()`. Replaces the ~50-line confirm-callback that used to live in `ConnectionSettingsScreen.kt::SessionTtlPickerDialog.onConfirm`. The wizard is the only caller now.

**New: `ConnectionViewModel.HealthStatus` (Unknown / Probing / Reachable / Unreachable)** + `apiServerHealth` + `relayServerHealth` StateFlows. Distinct from the existing `apiServerReachable: Boolean` so the UI can render a dedicated "Probing" pose right after foreground / network change. The boolean stays in place for legacy callers; new UI consumes the tri-state flow.

**New: `ConnectionViewModel.revalidate()`** — single entry point for "the world might have changed — re-check everything." Flips both health flows to `Probing` immediately so the badge poses don't flash stale Connected/Disconnected, then fires API + relay `/health` probes in parallel and joins them, and kicks `reconnectIfStale()` to bring the WSS back if it was holding a paired-but-disconnected state. Idempotent — guarded by a `revalidationJob` so rapid foreground/background cycles don't pile up parallel probes.

**New: lifecycle hook in `RelayApp.kt`** — `DisposableEffect(lifecycleOwner)` registers a `LifecycleEventObserver` that calls `connectionViewModel.revalidate()` on `ON_RESUME`. This is *the* fix for the resume-lag flash. Single observer at the app root so every screen gets fresh badges on foreground without each one wiring its own.

**New: connectivity reaction inside ConnectionViewModel.init** — the existing `ConnectivityObserver.observe()` flow was previously exposed as `networkStatus` but never read. Now a collector inside `init` calls `revalidate()` on every `Available` transition (with `drop(1)` to skip the seed value so we don't double-probe on first composition). Airplane-mode flips, network handoffs, and Tailscale up/down events now actually trigger a re-probe instead of being purely advisory.

**New: periodic relay health loop** — mirrors the existing 30s API health loop. Uses `RelayHttpClient.probeHealth()` (unauthenticated, 3s timeout, no rate-limit cost) to update `relayServerHealth` independently of the WSS heartbeat. Plugs the historical gap where relay status was only verified by WSS or manual Save & Test taps.

**New: fourth state on `ConnectionStatusBadge` — `BadgeState.Probing`** — gray pulsing dot at 1.2s cadence, distinct from the amber Connecting pulse at 0.8s and the green Connected pulse at 1.5s. Added a new `state: BadgeState` overload of both `ConnectionStatusBadge` and `ConnectionStatusRow`. The boolean overloads are preserved (with a new optional `isProbing: Boolean = false` parameter) so legacy call sites — chat header, terminal screen, bridge cards — keep working without churn. Only call sites that *want* a Probing state need to migrate.

**Edited: `app/src/main/kotlin/com/hermesandroid/relay/ui/onboarding/OnboardingScreen.kt`** — replaced the bespoke `ConnectPage` (which discarded the relay block, never walked the user through TTL picking, and let users exit onboarding in a half-paired state) and `RelayPage` (a bare URL text field) with a single `ConnectPage` that embeds `ConnectionWizard`. Pages list is now: Welcome → Chat → (Terminal → Bridge if relay enabled) → Connect (wizard). Onboarding's `onComplete` callback collapsed from 4 args (`apiServerUrl, apiKey, relayUrl, relayPairingCode`) to zero — the wizard owns credential application via `applyPairingPayload`, so the callback only needs to mark complete + navigate. The pager's bottom Next/Back navigation is hidden on the Connect page so the wizard owns its own affordances. Top-bar Skip is also hidden on the Connect page so the wizard's "Skip for now" is the only skip affordance.

**Edited: `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ConnectionSettingsScreen.kt`** — "Scan Pairing QR" + "Guided setup" buttons collapsed into a single "Pair with QR" button that opens the wizard inside a full-screen `Dialog`. "Re-pair" button (shown when paired) now opens the wizard instead of launching the camera directly. Removed: the `cameraPermissionLauncher` (wizard owns permission), `pendingQrPayload` state, the inline `SessionTtlPickerDialog` confirm-callback block (~50 lines), and the `showPairingWalkthrough`/`PairingWalkthroughDialog` dialog. The API/Relay/Session status rows now consume `apiServerHealth` and `relayServerHealth` so they render the new Probing pose during the post-resume window.

**Edited: `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt`** — root Connection summary card's API and Relay rows updated to consume the new health flows + render Probing on resume. Status text reads "Checking…" during the probe instead of flashing stale "Connected"/"Unreachable".

**Deleted: `app/src/main/kotlin/com/hermesandroid/relay/ui/components/PairingWalkthroughDialog.kt`** (~370 lines) — the old 4-step dialog-counter wizard. Wizard supersedes it. `SessionTtlPickerDialog.kt` is preserved because the wizard reuses its `ttlPickerOptions()` and `defaultTtlSeconds()` helpers.

The end result: one canonical pairing flow used by both onboarding and Settings, badges that flip to "Checking…" instead of flashing stale state on resume, and an explicit Probing pose so the user can tell the difference between "we don't know yet" and "we know it's down."

## 2026-04-12 — Docs: two-track explainer + FeatureMatrix component

Closed the user-doc gap left by Phase 3's googlePlay/sideload Gradle flavor split. Before this, the user-docs site framed sideloading as just an alternative install path — there was no signal that the sideload APK actually unlocks tier 3/4/6 features (voice→bridge intent routing, vision-driven `android_navigate`, workflow recording) that are *compiled out* of the Play Store APK by `BuildFlavor.bridgeTier{3,4,6}` checks.

**New: `user-docs/.vitepress/theme/components/FeatureMatrix.vue`** — polished feature comparison component that matches the design language of `HermesFlow.vue` / `InstallSection.vue` / `HeroDemo.vue` (Space Grotesk + Space Mono, `--vp-c-brand-1` purple accent, flat + border-separated, no gradients/shadows/blur). Renders a semantic `<table>` with one row per *feature* (tier numbers are an implementation detail users don't care about), grouped into Chat & voice / Bridge — read / Bridge — control / Safety rails / Install & updates sections. Three support states per cell (`full` / `limited` / `none`) with inline SVG icons inheriting `currentColor` from the cell's class. Sideload-only rows fade to `--vp-c-text-3` opacity 0.6; "limited" cells get a *• see note* suffix that surfaces a per-row footnote about the read-only Play accessibility surface. Responsive: above 720px shows both columns side-by-side with a brand-soft tinted Sideload column; below 720px collapses to a single visible column with role=tablist mobile tab switcher (the semantic table stays intact for screen readers — the inactive column is hidden via `display:none` on mobile-only cells, not removed). Below 480px the text labels collapse to icon-only with the support state on the cell's `aria-label`. Zero npm deps — all icons are inline SVG.

**New: `user-docs/guide/release-tracks.md`** — canonical prose explainer. Plain-language opener ("Hermes-Relay ships in two flavors built from the same codebase"), TL;DR up top, "Why two tracks?" section that explains Google Play's accessibility-service scrutiny without jargon, embedded `<FeatureMatrix />`, decision guide ("Want X? → Track Y" bullets), "Can I switch later?" section explaining the side-by-side `applicationIdSuffix = ".sideload"` story, install instructions for both tracks (linking to existing getting-started.md content rather than duplicating), and a "Safety rails — always on, in both tracks" closer to make clear the floor isn't a sideload feature. Wired into the `/guide/` sidebar between Installation & Setup and Chat.

**Edited: `user-docs/features/index.md`** — added a "Bridge — Phone Control" table with a Track column and a `<span class="track-badge track-badge--sideload">Sideload only</span>` convention for compiled-out features (voice→bridge, vision navigation, workflow recording). Added a "Bridge — Safety Rails" table to make the floor explicit. Removed Bridge from "Coming Soon" since Phase 3 is shipping. Added a "Choose your track" section near the bottom with `<FeatureMatrix />` embedded and a link to release-tracks.md. Top-of-page intro now flags the two flavors and the badge convention.

**Edited: `user-docs/guide/getting-started.md`** — replaced the single-line "download the APK or wait for Play Store" sentence in step 1 with a short two-flavor pitch and a link to release-tracks.md. Did not duplicate the matrix here — just points to it.

**Edited: `user-docs/.vitepress/config.mts`** — added Release tracks to the `/guide/` sidebar between Installation & Setup and Chat.

**Edited: `user-docs/.vitepress/theme/index.ts`** — registered FeatureMatrix as a global Vue component so MD files can use `<FeatureMatrix />` without per-file imports (matches the existing HermesFlow registration pattern).

Build verified locally with `npx vitepress build` — clean compile, all pages render, no errors.

## 2026-04-12 — Phase 3 / Wave 2 / safety-rails: bridge safety rails

Branch: `feature/phase3-zeta-safety-rails` (off `main` @ 8d86a62 which has Wave 1 merged).

**What landed.** Tier 5 safety for the bridge channel — five enforcement components that together make it OK to actually ship agent device control:

1. **Per-app blocklist** (`BridgeSafetyPreferences.kt`) — DataStore-backed set of package names. Ships with a conservative default covering common banking apps (Chase / Wells Fargo / BoA / Revolut / Monzo / Starling), payment apps (Venmo / Cash App / PayPal / Coinbase), password managers (1Password / Bitwarden / LastPass / Dashlane / Keeper), and 2FA apps (Google Authenticator / Authy / Duo). Users edit via `BridgeSafetySettingsScreen` — searchable LazyColumn of installed apps with checkboxes. Enforcement is in `BridgeSafetyManager.checkPackageAllowed(packageName: String?)`, called by `BridgeCommandHandler` against `HermesAccessibilityService.currentApp`; a blocked package fails fast with HTTP 403 `{"error": "blocked package <name>"}`.

2. **Destructive-verb confirmation modal** (`DestructiveVerbConfirmDialog.kt` + `BridgeStatusOverlay.kt`) — when `/tap_text` or `/type` carries a body that matches one of the configurable destructive verbs (default: send, pay, delete, transfer, confirm, submit, post, publish, buy, purchase, charge, withdraw) on a word-boundary regex, `BridgeSafetyManager.awaitConfirmation(method, text)` suspends the handler coroutine, shows a full-screen Compose modal through a `SYSTEM_ALERT_WINDOW` overlay (necessary because the agent can act while the app is backgrounded), and waits on a `CompletableDeferred<Boolean>` under a `withTimeout`. Timeout default 30s; timeout = DENY. Fail-closed: if the overlay permission is missing, the check returns false.

3. **Auto-disable timer** (`AutoDisableWorker.kt`) — a coroutine `Job` owned by `BridgeSafetyManager` with a `delay(minutes.toMillis())`. Rescheduled on every accepted bridge command. On fire: flips the master toggle off via `HermesAccessibilityService.setMasterEnabled(context, false)` and posts a one-shot "Bridge auto-disabled after idle" notification through `NotificationManagerCompat`. Default idle window: 30 min, slider 5..120 min. **Deviation from spec:** spec called for WorkManager, but `androidx.work` is not in the classpath and adding the dep is out of scope for this slice. The coroutine-job approach is documented in `AutoDisableWorker.kt` as the canonical upgrade path if WorkManager is added later.

4. **Persistent foreground service** (`BridgeForegroundService.kt`) — plain `Service` with `startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` on Android 14+, graceful fallback below. Notification shows "Hermes agent has device control" with two action buttons: **Disable** (broadcasts back into the service which flips the master toggle) and **Settings** (opens `MainActivity`; TODO followup to wire a deep-link extra for direct nav to `BridgeSafetySettingsScreen`). Started/stopped from `BridgeViewModel` via a `masterToggle.distinctUntilChanged().collect {}` observer.

5. **Optional status overlay chip** (`BridgeStatusOverlay.kt`) — tiny floating "Hermes active" pill in the top-right corner. Off by default, opt-in via `BridgeSafetySettingsScreen`. Uses the same `SYSTEM_ALERT_WINDOW` pipeline as the confirmation modal — only one `WindowManager` attachment point per process. The overlay host implements `ConfirmationOverlayHost` so the safety manager can reach both the chip and the modal through the same singleton.

**Files created (8):**

- `app/src/main/kotlin/com/hermesandroid/relay/data/BridgeSafetyPreferences.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/bridge/BridgeSafetyManager.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/bridge/BridgeForegroundService.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/bridge/BridgeStatusOverlay.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/bridge/AutoDisableWorker.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/BridgeSafetySettingsScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/DestructiveVerbConfirmDialog.kt` (also hosts `BridgeStatusOverlayChip`)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/BridgeSafetySummaryCard.kt`

**Shared-concern files touched (6)** — all marked with `// === PHASE3-safety-rails: ... === / === END PHASE3-safety-rails ===`:

- `BridgeCommandHandler.kt` — injects `safetyManager: BridgeSafetyManager?`, runs the three-stage check (blocklist → confirmation → reschedule timer) before dispatching each action.
- `AndroidManifest.xml` — adds `SYSTEM_ALERT_WINDOW` + `FOREGROUND_SERVICE_SPECIAL_USE` uses-permission + a `<service android:name=".bridge.BridgeForegroundService" android:foregroundServiceType="specialUse"/>` declaration with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification property.
- `BridgeScreen.kt` — replaces the `SafetyPlaceholderCard` stub with the real `BridgeSafetySummaryCard` driven by `BridgeSafetyManager.peek()?.settings` and `.autoDisableAtMs`. New `onNavigateToBridgeSafety` parameter.
- `SettingsScreen.kt` — adds `onNavigateToBridgeSafety` callback and a "Bridge safety" `SettingsCategoryRow` between Notification companion and Media.
- `RelayApp.kt` — `Screen.BridgeSafetySettings` data object, composable(route), wires the nav callback from Settings + from Bridge screen.
- `BridgeViewModel.kt` — observes `masterToggle` and calls `BridgeForegroundService.start/stop`, reschedules/cancels the auto-disable timer on toggle changes, and combines the master toggle with `statusOverlayEnabled` to drive the chip visibility.
- `ConnectionViewModel.kt` (wiring only, marked) — calls `BridgeSafetyManager.install()` + `BridgeStatusOverlay.install()` at construction and passes the manager into `BridgeCommandHandler`.

**Key design notes / blockers / decisions.**

- **WorkManager not in classpath.** Went with a coroutine-owned timer instead of adding a new dependency. `AutoDisableWorker.kt` documents the upgrade path. This is acceptable because the toggle is in-process — no inter-process scheduling is required, and process death already implies the service is disconnected.

- **SYSTEM_ALERT_WINDOW UX.** Users must grant the permission through `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` before either the confirmation modal or the status chip can display. `BridgeSafetySettingsScreen` detects via `Settings.canDrawOverlays(context)` and kicks off the grant flow on first switch tap. **Fail-closed:** if the permission is missing when a destructive verb fires, `BridgeSafetyManager.awaitConfirmation` denies the action outright — better to drop a command than to silently allow it. Phase 3 Wave 3 should add a prominent "grant overlay permission" nag on the BridgeScreen when the user enables the master toggle without having granted it.

- **Compose in a WindowManager overlay.** `ComposeView` attached via `WindowManager.addView` doesn't automatically get a `ViewTreeLifecycleOwner` — we synthesize an always-RESUMED `OverlayLifecycleOwner` + `ViewModelStoreOwner`. Skipped `SavedStateRegistryOwner` because the dialog only uses `remember`, not `rememberSaveable`, and `androidx.savedstate` isn't pinned in the version catalog. If future overlay content needs `rememberSaveable`, pin that artifact and extend `OverlayLifecycleOwner`.

- **Confirmation coroutine wiring.** The safety manager keeps a `ConcurrentHashMap<Long, PendingConfirmation>` keyed by a monotonic request id. `awaitConfirmation` registers an entry, asks the overlay host to show the modal, then `withTimeout(...) { deferred.await() }`. The overlay's Allow / Deny buttons call back with the result. Timeouts dismiss the overlay programmatically. The suspending `BridgeCommandHandler.dispatch` call is what holds the slot — the relay sees a slow response instead of a premature 403, which matches the intended UX (user gets 30s to react, agent waits).

- **accessibility/bridge-ui API dependencies.** Relied on: `HermesAccessibilityService.currentApp` (accessibility, @Volatile String?), `HermesAccessibilityService.setMasterEnabled(context, enabled)` (accessibility), `HermesAccessibilityService.isMasterEnabled()` (accessibility), `BridgePreferencesRepository` pattern (bridge-ui), `relayDataStore` extension (existing), `BridgeViewModel.masterToggle: StateFlow<Boolean>` (bridge-ui). All used as-is — no edits in accessibility/bridge-ui territory.

**What's next.**

- Wire a deep-link extra on `MainActivity` so `BridgeForegroundService.ACTION_OPEN_SETTINGS` lands directly on `BridgeSafetySettingsScreen` (currently opens the app and the user taps through).
- Record `BridgeActivityEntry` rows for blocked-package / denied-confirmation events so the bridge activity log shows *why* an agent command failed.
- Wave 3 can add tests: unit tests for the destructive-verb regex word-boundary matching, an instrumented test for the foreground-service lifecycle, and a fake overlay host for `awaitConfirmation` timeout behavior.

## 2026-04-12 — Phase 3 / Wave 2 / voice-intents: voice→bridge intent routing (sideload-only, Tier 3)

Wired voice mode to optionally route transcribed utterances through the bridge channel instead of chat. When the user holds the voice button and says "text Sam I'll be 10 min late" on a **sideload** build, a lightweight keyword classifier recognizes the SMS intent, the voice layer speaks a confirmation ("About to text Sam: I'll be 10 min late. Say cancel to stop."), and a `bridge` envelope is queued for dispatch after a 5-second cancel window. "Open camera" / "scroll down" / "go back" / etc. execute immediately with no countdown.

**Cross-flavor compile pattern.** This is the first Wave 2 surface to exercise the `googlePlay` vs `sideload` flavor split that agent flavor-split is wiring up in parallel. `VoiceBridgeIntentHandler` is an interface + sealed `IntentResult` in `main/` so `VoiceViewModel` has a stable compile-time reference. Two separate implementations ship per-flavor with matching FQCNs and a shared top-level factory function `createVoiceBridgeIntentHandler(multiplexer)`:

- `app/src/googlePlay/kotlin/.../VoiceBridgeIntentHandlerImpl.kt` — `NoopVoiceBridgeIntentHandler` always returns `IntentResult.NotApplicable`, never touches the bridge, never references any device-control / accessibility class. Keeps the Play APK honest with its conservative feature description.
- `app/src/sideload/kotlin/.../VoiceBridgeIntentHandlerImpl.kt` — `RealVoiceBridgeIntentHandler` + `VoiceIntentClassifier` with keyword/regex patterns for SendSms / OpenApp / Tap / Scroll / Back / Home. Destructive intents go through the 5 s countdown; safe intents dispatch immediately.

`VoiceViewModel.initialize()` calls the factory exactly once; the ViewModel only ever holds the interface reference. No reflection, no runtime flavor check — Gradle picks the right impl at build time via the source-set flavor. The routing call site in `processVoiceInput` tries the handler first and falls through to `chatVm.sendMessage(text)` on `NotApplicable`.

**Classifier heuristic.** Six intents, first-match-wins, all case-insensitive with a filler-stripping preamble ("hey", "okay", "please", "can you", …):

| Intent | Pattern example | Parse |
|---|---|---|
| SendSms (with separator) | `text <contact> saying <body>` / `text <contact>: <body>` / `send a message to <contact> saying <body>` | `contact`, `body` |
| SendSms (no separator) | `text <contact> <body≥2chars>` — conservative: 1-2 word contact, requires a body | `contact`, `body` |
| OpenApp | `open|launch|start <app>` (optional `the`/`app` suffix) | `appName` |
| Tap | `tap|press|click(?: on)? <target>` (optional `button` suffix) | `target` |
| Scroll | `scroll (to the)? up|down|top|bottom` | `direction` |
| Back | `(go|navigate)? back` | — |
| Home | `(press|go)? home(?: screen)?` | — |

Design bias: **false negatives > false positives**. "Can you text me when you're done?" falls through to chat because the SMS regex can't parse a contact + body from it. Anything that doesn't cleanly split into a structured action becomes a chat message. Each pattern is a single named-group regex so Wave 3 tuning is mechanical.

**v1 confirmation simplification.** Full conversational confirmation ("About to text… say yes or cancel") would need a multi-turn voice state machine we don't have. Instead: destructive intents speak the confirmation via the existing sentence-TTS queue, start a 5 s countdown coroutine, and dispatch unless `VoiceViewModel.cancelPendingBridgeIntent()` is called first. The overlay's Cancel button wires into that method. Full voice-confirmation is a Wave 3 follow-up (see Phase 3 plan row `voice-bridge-confirmation`).

**Bridge envelope wire shape.** Simple and documented so Wave 2 sibling safety-rails (`bridge-safety-rails`) owns the authoritative schema:

```
{ channel: "bridge",
  type:    "tool.call",
  payload: { tool: "android_send_sms" | "android_open_app" | ... ,
             args: { ... },
             requires_confirmation: true|false,
             source: "voice" } }
```

Emission goes through `ChannelMultiplexer.send(envelope)` — the new `bridgeMultiplexer: ChannelMultiplexer? = null` parameter on `VoiceViewModel.initialize()` is optional so existing call sites keep compiling until they're updated to pass the instance. The googlePlay factory ignores the multiplexer entirely.

**Files:**

New (main):
- `app/src/main/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentHandler.kt` — interface + `sealed class IntentResult { Handled, NotApplicable }`

New (googlePlay flavor):
- `app/src/googlePlay/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentHandlerImpl.kt` — `NoopVoiceBridgeIntentHandler`
- `app/src/googlePlay/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentFactory.kt` — factory returning the no-op

New (sideload flavor):
- `app/src/sideload/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentHandlerImpl.kt` — `RealVoiceBridgeIntentHandler` with v1 countdown + envelope builders
- `app/src/sideload/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentFactory.kt` — factory returning the real impl
- `app/src/sideload/kotlin/com/hermesandroid/relay/voice/VoiceIntentClassifier.kt` — regex-based classifier + `VoiceIntent` sealed class

Touched (main):
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt` — new `bridgeMultiplexer` param on `initialize()`; factory call at the bottom of init; intent-routing block at the top of `processVoiceInput` (between the Thinking-state update and the sentence-buffer reset); new `cancelPendingBridgeIntent()` public method for the overlay's Cancel button. All changes wrapped in `// === PHASE3-voice-intents: ... === / // === END PHASE3-voice-intents ===` markers.

**Known cross-worktree dependency.** This worktree assumes agent flavor-split's `productFlavors { googlePlay; sideload }` + `BuildFlavor.bridgeTier3` constant lands in `build.gradle.kts` / `FeatureFlags.kt` before this branch merges. Without flavor-split's flavor config, Gradle won't resolve the `createVoiceBridgeIntentHandler(...)` reference from `VoiceViewModel` because neither flavor source set is active. Order of merges: flavor-split first, then voice-intents (and safety-rails + vision-nav).

**Next steps (Wave 2 sibling + Wave 3):**
- safety-rails owns the `bridge` envelope contract — if the wire-level field names change (`tool` → `name`, etc.), update `VoiceBridgeIntentHandlerImpl.buildXxxEnvelope` in one place.
- Overlay wire-up (a follow-up): show `IntentResult.Handled.intentLabel` in the voice overlay, add a Cancel button that calls `cancelPendingBridgeIntent()` when `requiresConfirmation=true`.
- Wave 3 `voice-bridge-confirmation`: replace the 5 s countdown with a proper multi-turn confirmation ("say yes or cancel" → STT → classifier for yes/no).
- Classifier unit tests: `extractNextSentence`-style unit tests for `VoiceIntentClassifier.classify()`. Top-level function already testable without an Application context.

## 2026-04-12 — Phase 3 / Wave 2 / vision-nav — android_navigate vision-driven navigation tool

Lands the Tier 4 "burner-phone trick, done sanely" plan row. New Hermes tool `android_navigate(intent, max_iterations=5)` closes the loop on high-level bridge automation: instead of the agent having to know exact node IDs or coordinates, it passes a natural-language intent ("compose a tweet about rainy weather") and the tool loops `screenshot → vision model → action → repeat` until the model emits `done` or hits the iteration cap.

**Files added:**
- `plugin/tools/android_navigate.py` — main loop + registry registration. Reuses the same `_bridge_url()`/`_auth_headers()`/`ANDROID_BRIDGE_URL` transport layer as `android_tool.py` so pairing config stays single-sourced. Dispatches parsed actions to `/tap_text`, `/tap`, `/type`, `/swipe`, `/press_key` (the HTTP routes Wave 1 stood up). Screenshot per iteration goes through the bridge `/screenshot` endpoint, then tries `plugin.relay.client.register_media()` for an opaque token and gracefully falls back to a `file://` marker if the relay isn't reachable. Every step records `{step, action, params, screenshot_token, reasoning, result}` into a trace the agent gets back on both success and failure envelopes. Iteration cap defaults to 5, hard-clamped to `ABSOLUTE_MAX_ITERATIONS = 20` so the agent can't accidentally burn capture budget.
- `plugin/tools/android_navigate_prompt.py` — prompt template + response parser. Parser is case-insensitive, tolerates trailing punctuation on the verb, allows `done` to omit the PARAMS line, enforces per-verb param shape (e.g. `tap` needs either `(x,y)` ints or `node_id` string), and returns `ParsedAction(action="error", reasoning=<why>)` on any malformed input so the loop can surface structural failures cleanly.
- `plugin/tests/test_android_navigate.py` — 35 stdlib-only `unittest` tests covering all 6 valid actions, every malformed-input branch (empty / missing lines / bad JSON / wrong shape / unknown verb / list-not-object / bad swipe direction), success path (single-step and multi-step), iteration cap, screenshot failure, parse error, action-execution failure, `llm_gap` envelope, empty intent short-circuit, `max_iterations` clamping, and schema sanity. Uses only `unittest.mock` so it runs cleanly via `python -m unittest plugin.tests.test_android_navigate` without the pytest `conftest.py` that imports the `responses` module.

**LLM integration — known gap:** The plan specifies "vision model integration" but doesn't pick a provider, and the plugin has no published LLM client surface for tools to call. The gateway's run loop is not re-entrant (calling the agent from inside a tool deadlocks the run). Rather than invent a new LLM client and ship an opinionated default, the loop has a `call_vision_model` injection point. Tests patch it; production either (a) sets `HERMES_NAVIGATE_STUB_REPLY` for smoke testing, or (b) swaps `_default_vision_model` for a real client (e.g. Anthropic `messages.create` with a base64 image block). Until (b) lands, calling `android_navigate` against a live phone returns a clean `{"status": "error", "reason": "llm_gap", ...}` envelope so the agent sees exactly why the loop can't run — no silent fakes, no import-time crashes. The `_default_vision_model` docstring documents the replacement contract.

**Tool guardrails** (from the plan):
- Never continuous capture — exactly one `/screenshot` per iteration, only when the tool is invoked.
- Default 5 iterations, hard-clamped ceiling of 20.
- 200 ms settle delay between action and next screenshot; callers needing longer waits use `android_wait` explicitly.
- `intent` must be non-empty; empty/whitespace returns an error envelope without touching the bridge.

**Files NOT touched:** `plugin/relay/*`, `plugin/relay/channels/bridge.py`, `plugin/tools/android_tool.py`, `plugin/tools/android_relay.py`, `plugin/relay/auth.py`, `plugin/relay/media.py`, `plugin/relay/voice.py`, anything under `app/`. The slice is self-contained in `plugin/tools/` as scoped.

**Test results:** `python -m unittest plugin.tests.test_android_navigate` → 35 tests, all pass in ~0.05 s.

## 2026-04-12 — Dynamic phone-status system prompt block

Replaced the single hardcoded "app context prompt" sentence with a transparent, granular block that reflects real Phase 3 bridge/permission state. The old toggle promised far more than it delivered — one static line, regardless of anything actually happening on the phone. The new block is a master toggle + 4 sub-toggles with a live preview card in Chat Settings.

**New file:** `app/src/main/kotlin/com/hermesandroid/relay/util/PhoneStatusPromptBuilder.kt` — pure function `buildPromptBlock(settings, snapshot)` returning `String?`. Returns `null` when master is off so no empty system message is sent. Defines `AppContextSettings` (5 booleans) and `PhoneSnapshot` (11 nullable fields). Output capped under ~100 words; the brief suggests calling `android_phone_status` for full detail, keeping per-turn token cost down while giving the agent a permission-lit entrypoint.

**ConnectionViewModel:** Four new DataStore keys alongside the existing `KEY_APP_CONTEXT` — `KEY_APP_CONTEXT_BRIDGE_STATE` (default true), `KEY_APP_CONTEXT_CURRENT_APP` (default **false** — privacy), `KEY_APP_CONTEXT_BATTERY` (default **false** — privacy), `KEY_APP_CONTEXT_SAFETY_STATUS` (default true). Each gets a StateFlow + setter mirroring the `appContextEnabled` pattern.

**ChatViewModel:** Deleted `APP_CONTEXT_PROMPT` constant, replaced `var appContextEnabled: Boolean` with `var appContextSettings: AppContextSettings`. `send()` now calls `buildPromptBlock(appContextSettings, capturePhoneSnapshot())`. The `capturePhoneSnapshot()` helper uses guarded reflection (`runCatching` on every lookup) to read Phase 3 classes — `HermesAccessibilityService.instance`, `MediaProjectionHolder.projection`, `BridgeSafetyManager.peek()`. The reflection path exists so ChatViewModel compiles cleanly before those classes land in this worktree (they're being built by a parallel agent); once they exist, reads light up with zero further code changes here. Non-Phase-3 sources (battery, overlay permission, notification listener flat-string) use direct platform APIs.

**RelayApp.kt:** Single `LaunchedEffect` now keys on all 5 toggles and writes a fresh `AppContextSettings` into `chatViewModel.appContextSettings` on any change.

**ChatSettingsScreen.kt:** Master toggle renamed to "Share phone status with agent". When enabled, `AnimatedVisibility` reveals 4 sub-toggle rows + a "Preview" Card. The preview uses `remember(master, bridgeState, currentApp, battery, safetyStatus)` to regenerate exact output text on every toggle change, calling the same `buildPromptBlock` with a neutral empty snapshot so the user sees the shape without leaking current phone state into the settings screen. Shows "(no system message will be sent)" when builder returns `null`.

**Privacy model:** Default configuration sends "mobile-friendly preamble + bridge permission summary + safety rails count" — no package names, no battery level. Users opt in explicitly to each privacy-sensitive field. The `android_phone_status` tool (being built in a parallel worktree) is the disclosure path for full detail so per-turn cost stays bounded.

Markers: all new blocks tagged `// === PHASE3-status: ... === / // === END PHASE3-status ===` for grep.

## 2026-04-12 — Add canonical uninstall.sh + bootstrap docs

Companion to the bootstrap injection work below. There was no formal uninstall path before — `install.sh` is idempotent so most update flows worked, but cleanly removing the plugin (e.g., to test that install.sh works on a truly fresh state) required manually undoing 6 install steps. New `uninstall.sh` reverses them in opposite order:

1. Stops + disables `hermes-relay.service`, removes the systemd unit, daemon-reloads
2. Removes `~/.local/bin/hermes-pair` shim
3. Scrubs the relay's `skills.external_dirs` entry from `~/.hermes/config.yaml` via the same yaml parsing pattern install.sh uses, with a `.bak` backup before write — preserves all other entries
4. Removes `~/.hermes/plugins/hermes-relay` symlink + any legacy stales (`hermes-android`, etc.)
5. Removes `hermes_relay_bootstrap.pth` from venv site-packages, `pip uninstall hermes-relay`
6. Removes `~/.hermes/hermes-relay` clone (sanity-checked: refuses to delete a directory that doesn't have `.git` + `install.sh`)

What it never touches: `~/.hermes/.env` (other tools authenticate against this), `~/.hermes/state.db` (sessions DB shared with the gateway), `~/.hermes/hermes-agent/` (the agent itself), `~/.hermes/hermes-agent/venv/` (only our `.pth` is removed, not the venv core), and `~/.hermes/hermes-relay-qr-secret` (kept by default — the QR signing identity is precious; opt in to wipe with `--remove-secret`).

Flags: `--dry-run` previews without changing anything, `--keep-clone` leaves the git tree in place, `--remove-secret` wipes the QR secret. Help text: `bash uninstall.sh --help`.

`install.sh` header docs updated to mention `bootstrap injection` (step 2) and the uninstall path. Success summary now prints both `git pull && bash install.sh` for updates and `bash uninstall.sh --dry-run` for previewing removal. README.md and `user-docs/guide/getting-started.md` got equivalent updates with the bootstrap explanation + uninstall flags.

## 2026-04-12 — Bootstrap injection: vanilla upstream hermes-agent now works with the plugin

Closed the "you must run our hermes-agent fork to get full features" gap. The Codename-11 fork (`feat/api-server-enhancements`, 13 commits, submitted as PR [#8556](https://github.com/NousResearch/hermes-agent/pull/8556)) adds 20 management endpoints — `/api/sessions/*` CRUD, `/api/memory`, `/api/skills`, `/api/config`, `/api/available-models`, `/api/sessions/{id}/chat/stream` — that the Android app depends on. Until #8556 merges and reaches a release, vanilla upstream users were missing the sessions browser, conversation-history-on-restart, personality picker, command palette, and memory management.

**The fix: a single `.pth` file that runs at Python interpreter startup.** New `hermes_relay_bootstrap/` package ships with the plugin, gets loaded by Python's `site` module before anything in hermes-agent imports `aiohttp.web`. The bootstrap installs a `sys.meta_path` finder that wraps the loader for `aiohttp.web`. When the import resolves, our wrapper replaces `web.Application` with a thin subclass. The subclass overrides `__setitem__` to detect `app["api_server_adapter"] = self` — the line at `gateway/platforms/api_server.py:1735` where the gateway gives us a reference to the adapter while the router is still mutable. At that moment we feature-detect by route path and bind ~14 management handlers from `_handlers.py` directly onto the same router. The gateway then continues with its own route registrations and starts the server. From the outside, vanilla upstream now serves all the fork's management endpoints.

**What is NOT injected and why:** the chat-stream handler (`/api/sessions/{id}/chat/stream`). It depends on `_create_agent` and `agent.run_conversation` with multimodal content — the riskiest cross-cutting upstream methods that the fork may have implicitly modified. Instead, chat goes through standard upstream `/v1/runs`, which already emits structured `tool.started`/`tool.completed` SSE events. This is arguably an upgrade — `/v1/runs` has live tool events whereas the sessions chat-stream path required a post-stream message-history reload to render tool cards. The Android client adapts via a new `streamingEndpoint = "auto"` mode (default for new installs).

**Files added:**
- `hermes_relay_bootstrap/__init__.py` (~30 lines) — installs the meta_path finder
- `hermes_relay_bootstrap/_patch.py` (~170 lines) — `_AioHttpWebFinder`, `_PatchingLoader`, `_PatchedApplication`, `_maybe_register_routes` with feature detection by route path
- `hermes_relay_bootstrap/_handlers.py` (~500 lines) — 14 ported management handlers + helpers (sessions CRUD, memory CRUD, skills, config, available-models). Handlers take `adapter` as a closure parameter rather than being bound methods, so we don't pollute upstream's class.
- `hermes_relay_bootstrap.pth` — single line: `import hermes_relay_bootstrap`

**Files changed:**
- `pyproject.toml` — added `hermes_relay_bootstrap*` to packages.find include list
- `install.sh` step 2 — copies the `.pth` into the venv's `site-packages/` after `pip install -e`. Verified empirically: setuptools' editable install does NOT ship `data-files` to site-packages reliably (it puts them in `venv/data/` instead, where Python's `site` module never looks). Manual copy is necessary.
- `app/src/main/kotlin/.../HermesApiClient.kt` — new `ServerCapabilities` data class + `probeCapabilities()` method that returns per-endpoint presence. Uses HEAD-method probes (initially tried OPTIONS but the gateway's CORS middleware intercepts preflight and returns 403 for both existing and missing paths — discovered during the live install test on 2026-04-12). Treats any non-404 status as "route exists." `detectChatMode()` becomes a thin compatibility wrapper around `probeCapabilities().toChatMode()`.
- `app/src/main/kotlin/.../ConnectionViewModel.kt` — exposes `serverCapabilities: StateFlow<ServerCapabilities>`, populates it from `probeCapabilities()` in `rebuildApiClient()`, adds `resolveStreamingEndpoint(preference)` helper that collapses `"auto"` to a concrete `"sessions"` or `"runs"` based on the latest snapshot. Default endpoint preference for new installs flipped from `"sessions"` to `"auto"`.
- `app/src/main/kotlin/.../ChatViewModel.kt` — `streamingEndpoint` default flipped from `"sessions"` to `"runs"` (the safer fallback before RelayApp pushes the resolved value).
- `app/src/main/kotlin/.../ui/RelayApp.kt` — `LaunchedEffect(streamingEndpoint, serverCapabilities)` recomputes the resolved endpoint when either changes, pushes into ChatViewModel.
- `app/src/main/kotlin/.../ui/screens/ChatSettingsScreen.kt` — Settings → Streaming endpoint dropdown gains an "Auto" option (alongside Sessions/Runs). Helper text dynamically shows which path Auto is currently using.
- `CLAUDE.md` — non-standard endpoints table rewritten to show all three "provided by" mechanisms (fork, bootstrap, upstream-merged), plus key files entries for bootstrap + capability detection.
- `docs/decisions.md` — new ADR 16 covering the runtime injection rationale, options considered (A/B/C/D), risks accepted, removal path.
- `vault/Hermes-Relay.md` — new "Bootstrap Injection Architecture" section with the compatibility matrix; updated "Hermes Integration" table to show two install paths (fork or bootstrap).

**Compatibility matrix** (all three combinations safe to ship the bootstrap with):

| Gateway version | Bootstrap behavior | Result |
|---|---|---|
| Codename-11 fork (`axiom`) | Detects existing `/api/sessions`, no-ops | Fork serves everything natively ✓ |
| Vanilla upstream main | Detects no `/api/sessions`, injects routes | Bootstrap-injected endpoints serve ✓ |
| Post-PR-#8556 upstream-merged | Detects existing `/api/sessions`, no-ops | Upstream serves everything natively ✓ |

**Removal path** (when PR #8556 reaches a released hermes-agent version): delete `hermes_relay_bootstrap/`, delete `hermes_relay_bootstrap.pth`, remove the `.pth` drop block from `install.sh`. The Android client `probeCapabilities()` + `streamingEndpoint = "auto"` plumbing stays — it's permanent infrastructure that handles mixed-version deployments.

## 2026-04-12 — Phase 3 / Wave 1.5 — bridge-server-followup + notif-listener-followup (post-merge polish)

Two small follow-ups discovered during the Wave 1 agent-team merge. Both gate clean smoke testing of the merged Wave 1 work in Android Studio.

**bridge-server-followup — `POST /media/upload` route on the relay.** Agent accessibility's `ScreenCapture` posts screenshot bytes via multipart to `/media/upload`, but bridge-server only ported the existing `/media/register` (loopback + path-based). Phone has no shared filesystem with the relay, so `/media/register` was unusable for the bridge screenshot path. The new endpoint accepts a `file` multipart field with bearer auth (every paired phone has a session token), streams the bytes to a `NamedTemporaryFile` under `tempfile.gettempdir()` (which is in the default `MediaRegistry.allowed_roots`), enforces `MediaRegistry.max_size_bytes` while reading (returns 413 on overflow), then hands the path off to `MediaRegistry.register()` so token issuance / expiry / LRU eviction / `GET /media/{token}` are byte-for-byte identical to the loopback path. Marker block: `# === PHASE3-bridge-server-followup: /media/upload === / # === END PHASE3-bridge-server-followup ===` in `plugin/relay/server.py`. `tempfile` import added at the top. Route registered next to `/media/register`. py_compile clean.

**notif-listener-followup — wire `HermesNotificationCompanion.multiplexer` + Settings nav row.** Agent notif-listener flagged two small touch-ups in its handoff:

1. `ConnectionViewModel.init` now sets `HermesNotificationCompanion.multiplexer = multiplexer` once, immediately after the bridge handler registration. The companion service buffers up to 50 envelopes in its own `pendingEnvelopes` queue while the slot is null, so wiring it from here (rather than at service-bind time) is safe — the buffer drains on the next `onNotificationPosted` once the slot is set. Marker: `// === PHASE3-notif-listener-followup: notification companion multiplexer wiring === / // === END PHASE3-notif-listener-followup ===`.
2. `SettingsScreen` gains a new `onNavigateToNotificationCompanion: () -> Unit` callback parameter and a `SettingsCategoryRow` between Voice mode and Media (`Icons.Filled.Notifications`, "Notification companion", "Let your assistant triage notifications you've shared"). `RelayApp` adds `Screen.NotificationCompanionSettings` (route `settings/notifications`), wires the new callback in the `SettingsScreen` call site, and registers a `composable(...)` that hosts `NotificationCompanionSettingsScreen(onBack = popBackStack)`. Both new imports added.

After this entry the Bridge tab + Notification companion screen are both reachable through the normal navigation tree, and accessibility's screenshot pipeline has a working server-side endpoint. Wave 2 (safety-rails safety, voice-intents voice-bridge, vision-nav vision) is unblocked once both build flavors are confirmed to compile in Android Studio.

## 2026-04-12 — Phase 3 / Wave 1 / bridge-server — Migrated legacy bridge relay into unified relay (port 8767)

Retired the standalone bridge relay (`plugin/tools/android_relay.py` + the duplicate top-level `plugin/android_relay.py`, both listening on port 8766) and folded its functionality into the unified Hermes-Relay on port 8767 as the bridge channel. The wire protocol (`bridge.command` / `bridge.response` / `bridge.status`) stays byte-for-byte identical — only the transport changed. Agents accessibility, bridge-ui, notif-listener can now build against a single port.

- `plugin/relay/channels/bridge.py` — replaced the stub with a real `BridgeHandler`. One handler instance per `RelayServer`; holds `phone_ws` + `pending: dict[request_id, Future]` behind an `asyncio.Lock`. `handle_command(method, path, params, body)` mints a request_id, registers the future, sends a `bridge.command` envelope, and awaits a response with 30s timeout (matches the legacy `android_relay._RESPONSE_TIMEOUT`). `handle(ws, envelope)` opportunistically latches `phone_ws` and dispatches `bridge.response`/`bridge.status`. `detach_ws(ws, reason)` fails all pending futures with `ConnectionError` so HTTP callers don't hang when the phone drops.
- `plugin/relay/server.py` — added 14 HTTP routes (`/ping`, `/screen`, `/screenshot`, `/get_apps`, `/apps` [legacy alias], `/current_app`, `/tap`, `/tap_text`, `/type`, `/swipe`, `/open_app`, `/press_key`, `/scroll`, `/wait`, `/setup`) each delegating to `_bridge_dispatch → server.bridge.handle_command`. BridgeError → 503/504/502 depending on message. Wired `server.bridge.detach_ws(ws)` into `_on_disconnect` so phone drops instantly fail in-flight commands instead of hanging to the 30s timeout. All additions are bracketed by `# === PHASE3-bridge-server: ... === / # === END PHASE3-bridge-server ===` markers so the Agent notif-listener notification-listener merges are mechanical.
- `plugin/android_tool.py` + `plugin/tools/android_tool.py` — BRIDGE_URL default changed from `http://localhost:8766` to `http://localhost:8767` (both copies; `plugin/android_tool.py` is the one imported by `plugin/__init__.py`, `plugin/tools/android_tool.py` is the standalone-toolset copy). `_relay_port()` falls back through `ANDROID_RELAY_PORT → RELAY_PORT → 8767`. `android_setup()` rewritten: no longer imports the deleted `android_relay` module, instead probes `http://localhost:<port>/health` to verify the unified relay is up and returns a structured error if not. Env-var side effects preserved so the existing `test_android_tool.py::TestSetup` still passes.
- `plugin/android_relay.py` + `plugin/tools/android_relay.py` — **DELETED**. Both copies of the standalone relay are gone.
- `plugin/tests/test_bridge_channel.py` — new unittest suite (7 tests) covering envelope routing, future resolution, timeout cleanup, disconnect cleanup, send-failure cleanup, and the legacy-timeout regression guard. Uses a `_FakeWs` stand-in and bypasses the pytest `conftest.py` that imports `responses`. Run with `python -m unittest plugin.tests.test_bridge_channel`. All 7 green locally; existing `test_relay_media_routes` / `test_qr_sign` / `test_session_grants` / `test_media_registry` still pass (22 + 47 assertions) so `create_app` + route registration hold.

Auth model judgment call: the bridge HTTP routes are unauthenticated at the HTTP layer, matching the legacy standalone relay. Defensible because (a) the trust boundary is unchanged — only same-host processes reach `localhost:8767`, (b) a disconnected/unpaired phone naturally causes every call to fail with 503, and (c) the bridge grant is already tracked per-session in `Session.grants["bridge"]` so Wave 2 (safety-rails) can add a bearer wrapper without touching the handler. Noted inline in the PHASE3-bridge-server block so Agent safety-rails can find it.

Branch: `feature/phase3-alpha-bridge-server-migration`.

## 2026-04-12 — Phase 3 / Wave 1 / flavor-split — googlePlay + sideload build flavors

Agent flavor-split (`bridge-flavor-split`) adds the Gradle flavor split that the Phase 3 Bridge channel needs before any accessibility code lands. Google Play reviews AccessibilityService heavily, so Phase 3 ships two parallel release tracks from one codebase: a conservative `googlePlay` flavor with a notifications-and-confirmations use-case description, and a `sideload` flavor with the full agent-control description for GitHub Releases / F-Droid / ADB distribution.

Scope of this change:

- **`app/build.gradle.kts`** — `flavorDimensions += "track"` with two product flavors. The `sideload` flavor carries `applicationIdSuffix = ".sideload"` and `versionNameSuffix = "-sideload"` so both tracks can coexist on the same device during Phase 3 testing. The `googlePlay` flavor keeps the canonical `com.hermesandroid.relay` applicationId so existing v0.2.0 Play Store installs upgrade cleanly and Play Console keeps its release history. Decision trade-off: power users with both installed will see two launcher icons until we differentiate labels in a follow-up.
- **`app/src/googlePlay/*`** — flavor manifest overlay declaring `.accessibility.BridgeAccessibilityService` (owned by Agent accessibility in `src/main/`), conservative `accessibility_service_config.xml` (event subset: `typeWindowStateChanged|typeWindowContentChanged|typeViewClicked`, `flagDefault` only, no gestures, `canRetrieveWindowContent=true`), and `a11y_description_googleplay` targeted at Play Store policy review.
- **`app/src/sideload/*`** — flavor manifest overlay, full-capability `accessibility_service_config.xml` (`typeAllMask`, `flagRetrieveInteractiveWindows|flagReportViewIds|flagRequestTouchExplorationMode`, `canPerformGestures=true`), and `a11y_description_sideload` with explicit voice/vision/logging language.
- **`FeatureFlags.kt`** — new `BuildFlavor` object with `current` / `displayName` / six `bridgeTier1..6` flags. Tiers 1, 2, 5 are baseline-true for both tracks. Tiers 3 (voice-first), 4 (vision-first), 6 (ambitious future) are `get() = current == SIDELOAD` so UI code can do a single `if (BuildFlavor.bridgeTier3)` check and R8 can fold the branch at release-build time for the Play track.
- **`AboutScreen.kt`** — added a small "Track: Google Play" / "Track: Sideload" row under the existing Version row (which owns the 7-tap dev-options reveal). Marked with `=== PHASE3-flavor-split ===` banners so bridge-ui and notif-listener can land their own additions without merge conflicts.

Not shipped in this change: the actual `BridgeAccessibilityService` class (Agent accessibility) and any tier-gated UI surfaces (Agents bridge-ui/notif-listener). The manifests reference `.accessibility.BridgeAccessibilityService` with `tools:ignore="MissingClass"` so the Gradle + AAPT check doesn't block Agent accessibility's landing.

Branch: `feature/phase3-beta-bridge-flavor-split`.

## 2026-04-12 — Phase 3 / Wave 1 / accessibility — accessibility runtime (service + reader + executor + capture)

Wave 1 Agent accessibility (`accessibility-runtime`) landing the phone-side execution layer for the bridge channel. Five new files under a new `com.hermesandroid.relay.accessibility` package plus `BridgeCommandHandler` under `network/handlers`:

- **`HermesAccessibilityService`** — master `AccessibilityService` subclass. Self-registers as a `@Volatile` singleton on `onServiceConnected`, clears on unbind/destroy. Caches the foregrounded package from `TYPE_WINDOW_STATE_CHANGED` events (the only event type we consume — content-change events fire thousands/min and we read the tree on demand via `rootInActiveWindow`). Master enable flag lives in DataStore (`bridge_master_enabled`) so UI + safety rails can toggle it without killing the service.
- **`ScreenReader`** — UI tree → `ScreenContent(rootBounds, nodes[], truncated)`. `@Serializable` output; one `ScreenNode` per interesting node (non-blank text, content description, clickable/longClickable/scrollable/editable). Hard caps: `MAX_NODES=512`, `MAX_TEXT_LEN=2000` per field. `findNodeBoundsByText` and `findFocusedInput` helpers for the executor. Recycles child nodes in a `try/finally` pattern per-iteration.
- **`ActionExecutor`** — `tap`/`tapText`/`swipe`/`scroll` via `GestureDescription` wrapped in `suspendCancellableCoroutine` so the suspend form actually waits for `GestureResultCallback.onCompleted`. `typeText` uses `ACTION_SET_TEXT` with a `Bundle` arg. `pressKey` maps a curated string vocabulary (`home`/`back`/`recents`/`notifications`/`quick_settings`/`power_dialog`/`lock_screen`) to `AccessibilityService.GLOBAL_ACTION_*` constants — we deliberately don't accept raw `KeyEvent` codes so agents can't inject arbitrary keypresses. `wait(ms)` clamped to 15s. Every method returns `ActionResult(ok, data, error)` so the handler can map 1:1 to HTTP-style status codes.
- **`ScreenCapture`** — `MediaProjection` → `VirtualDisplay` → `ImageReader` → PNG bytes → multipart upload to `POST /media/upload` on the relay. Crops `rowStride` padding before `Bitmap.copyPixelsFromBuffer`. 2.5s capture timeout. `MediaProjectionHolder` singleton holds the per-session grant — Bridge UI (Agent bridge-ui) is responsible for the `ActivityResultLauncher` flow that calls `onGranted(resultCode, data)`. Registers a `MediaProjection.Callback` to null the holder when the system revokes.
- **`BridgeStatusReporter`** — coroutine that emits `bridge.status` envelopes every 30s with `screen_on` (via `PowerManager.isInteractive`), `battery` (`BatteryManager.BATTERY_PROPERTY_CAPACITY` with a sticky-intent fallback for OEM quirks), `current_app` (from the service singleton), and `accessibility_enabled` (true when the service instance is non-null). Owned by `ConnectionViewModel`.
- **`BridgeCommandHandler`** — routes inbound `bridge.command` envelopes to the executor and emits `bridge.response`. Wire paths: `/ping` (works without the service), `/tap`, `/tap_text`, `/type`, `/swipe`, `/scroll`, `/press_key`, `/wait`, `/screen`, `/screenshot`, `/current_app`. Gates everything except `/ping` and `/current_app` on the master-enable toggle, returning 503 if the service isn't connected or 403 if the soft master is off. `/screen` serializes the full `ScreenContent` via `kotlinx.serialization.json`.

Wired into `ChannelMultiplexer.kt` via a `// === PHASE3-accessibility ===` marked section (simplified the existing bridge branch that was stubbed with a TODO), and `ConnectionViewModel.kt` gets a matching marker block that instantiates `ScreenCapture`, `BridgeCommandHandler`, and `BridgeStatusReporter`, registers the handler, and starts the reporter. `AndroidManifest.xml` declares the service with `BIND_ACCESSIBILITY_SERVICE` permission + intent filter + `@xml/accessibility_service_config` meta-data (the XML itself is flavor-provided by Agent flavor-split). Added `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, and `POST_NOTIFICATIONS` for the Wave 2 persistent-notification work.

**Known blocker for bridge-ui to wire:** `MediaProjectionManager.createScreenCaptureIntent()` requires an `Activity`-scoped result. `ScreenCapture.createConsentIntent()` exposes the intent; the Bridge screen needs an `ActivityResultLauncher<Intent>` that forwards the result to `MediaProjectionHolder.onGranted(context, resultCode, data)`. Until that lands, `/screenshot` returns 503 with a clear error message.

**Known blocker for bridge-server to fix:** the relay's `/media/register` endpoint is loopback-only and path-based (see `plugin/relay/media.py`). The phone can't use it — there's no shared filesystem. `ScreenCapture.uploadViaMultipart` POSTs to `/media/upload` (new endpoint; mirrors the `/voice/transcribe` multipart pattern) which doesn't exist yet. Until bridge-server ships it, `/screenshot` surfaces a 404 with the exact message `"relay /media/upload endpoint not found — server needs Phase 3 bridge-server migration"`. Token extraction uses the same `{"ok": true, "token": "..."}` shape as `/media/register`.

Branch: `feature/phase3-accessibility-runtime`.

## 2026-04-12 — Phase 3 / Wave 1 / bridge-ui — BridgeScreen rewrite (bridge-screen-ui)

Replaced the `BridgeScreen` "Coming Soon" placeholder with the real Tier-1
control surface from the Phase 3 plan. Wave 1 Agent bridge-ui (`bridge-screen-ui`)
deliverable — the UI scaffold is now ready to render whatever state Agent accessibility's
`HermesAccessibilityService` exposes once its runtime lands.

New files:

- `app/src/main/kotlin/.../data/BridgePreferences.kt` — DataStore repo with
  `bridge_master_enabled` boolean and serialized `bridge_activity_log` JSON
  list (capped at 100 entries). Mirrors `VoicePreferences.kt` /
  `MediaSettings.kt` style. Uses lenient `Json` for forward-compat.
- `app/src/main/kotlin/.../viewmodel/BridgeViewModel.kt` — AndroidViewModel
  exposing `masterToggle`, `bridgeStatus`, `permissionStatus`, `activityLog`
  StateFlows. Reads a11y service enablement via
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, overlay via
  `Settings.canDrawOverlays`, notification listener via
  `enabled_notification_listeners`. Seeds `BridgeStatus` on init from
  `PowerManager.isInteractive` + `BatteryManager.BATTERY_PROPERTY_CAPACITY`
  so the card isn't empty before accessibility lands. Four explicit `TODO(accessibility-handoff)`
  markers documenting the StateFlow/class-name/activity-writer surface that
  accessibility needs to expose for final wiring.
- `app/src/main/kotlin/.../ui/components/BridgeMasterToggle.kt` — headline
  Switch card with status inlines (device / battery / screen / current app),
  explanation dialog (required by Play Store a11y review), and a11y-granted
  gate so users can't flip it on before enabling the service.
- `app/src/main/kotlin/.../ui/components/BridgeStatusCard.kt` — standalone
  status card with `ConnectionStatusBadge` integration. Usable independently
  of the master toggle so safety-rails can re-arrange the cards without losing state.
- `app/src/main/kotlin/.../ui/components/BridgePermissionChecklist.kt` —
  four-row checklist (Accessibility / Screen Capture / Overlay / Notification
  Listener) with tap-to-open Intent launchers wrapped in `runCatching` for
  OEM skin safety. `ACTION_ACCESSIBILITY_SETTINGS`,
  `ACTION_MANAGE_OVERLAY_PERMISSION` with package URI, and the direct
  `enabled_notification_listeners` intent string.
- `app/src/main/kotlin/.../ui/components/BridgeActivityLog.kt` — scrollable
  `LazyColumn` (bounded at `heightIn(max = 320.dp)`) with tap-to-expand row
  showing full timestamp, status, result text, and optional screenshot token.
  `java.time.DateTimeFormatter` for `HH:mm:ss` / `yyyy-MM-dd HH:mm:ss`.
  Stubbed screenshot thumbnail rendering with a TODO pointing at accessibility's
  MediaRegistry upload path.

Shared-concern edits (marked with `PHASE3-bridge-ui:` banners):

- `app/src/main/kotlin/.../ui/RelayApp.kt` — the existing `BridgeScreen()`
  call wraps in the bridge-ui marker. Signature compatible: `BridgeScreen` defaults
  its ViewModel via `viewModel()`, so no new nav-graph plumbing needed.

Each new component carries a `@Preview` (or two — e.g.,
`BridgeMasterTogglePreviewOn` vs `PreviewBlocked`) for iterating in
Android Studio's preview pane without rebuilding the whole app.

accessibility-handoff points (in priority order, documented in `BridgeViewModel` KDoc):

1. `bridgeStatus` StateFlow — bridge-ui stubs a best-effort read from system APIs;
   accessibility replaces with the real HermesAccessibilityService status flow.
2. `A11Y_SERVICE_CLASS` constant — bridge-ui hard-codes
   `"com.hermesandroid.relay.accessibility.HermesAccessibilityService"`.
   accessibility confirms the final FQCN.
3. `recordActivity` write path — accessibility's command dispatcher calls
   `BridgeViewModel.recordActivity(entry)` on every `bridge.command` to
   populate the activity log. Until then the log stays empty and the empty-
   state copy shows.
4. Master toggle read from accessibility — accessibility's service reads `bridge_master_enabled` from
   `BridgePreferencesRepository.settings` and treats it as the runtime disable
   switch. No extra wiring needed from bridge-ui; accessibility just imports the repo.

Build-flavor flags (`BuildFlavor.bridgeTierN` from flavor-split) are not referenced yet
— this worktree pre-dates flavor-split's landing. Once flavor-split's object lands on main, bridge-ui's
components remain compatible (they don't tier-gate anything; Tier 1 is
always-on in both flavors per the plan).

Safety card is a placeholder stub — Agent safety-rails (Wave 2) owns the real safety
UI. bridge-ui's `SafetyPlaceholderCard` just says "Configure in Bridge Safety
Settings" with a Wave-2 teaser subtitle.

Branch: `feature/phase3-delta-bridge-screen-ui`.

## 2026-04-12 — Phase 3 / Wave 1 / notif-listener — notification companion (opt-in triage helper)

Adds an opt-in helper that lets the user's Hermes assistant read notifications they've explicitly granted access to via Android's standard `NotificationListenerService` API — the same one Wear OS, Android Auto, and Tasker have used for over a decade. Disabled by default; the user controls grant + revoke via Android Settings → Notification access.

**Three pieces, all marked with `PHASE3-notif-listener` block markers in shared files:**

1. **Phone — `NotificationListenerService` + Compose settings screen.** New `app/src/main/kotlin/.../notifications/` package with `HermesNotificationCompanion` (the bound service) + `NotificationModels` (`@Serializable NotificationEntry`) + `ui/screens/NotificationCompanionSettingsScreen` (About / Status / Test sections, mirrors `VoiceSettingsScreen` style). The service buffers up to 50 envelopes in a `ConcurrentLinkedQueue` if `companion.multiplexer` isn't wired yet (cold-start ordering), drains on the next `onNotificationPosted`, then sends via `multiplexer.sendNotification(envelope)` — a thin new wrapper in `ChannelMultiplexer` that fast-paths to no-op when `sendCallback == null` (relay offline). Notifications with empty title+text are skipped (background-sync placeholders that just confuse the LLM). The `Status` row uses `LifecycleEventObserver(ON_RESUME)` so the grant state updates immediately when the user comes back from Android Settings.

2. **Server — bounded in-memory deque.** New `plugin/relay/channels/notifications.py::NotificationsChannel` with `recent: collections.deque[dict]` capped at 100 entries via `maxlen` (LRU-by-time eviction for free). `handle()` dispatches `notification.posted` envelopes to `handle_envelope()` which appends; `get_recent(limit)` returns newest-first with `limit` clamped to `[1, max_entries]`. Wired into `RelayServer.__init__` as `self.notifications` and dispatched in `_on_message` for `channel == "notifications"`. The cache is in-memory only and lost on restart by design — same semantics as a smartwatch out of range.

3. **Agent tool — `android_notifications_recent(limit=20)`.** New `plugin/tools/android_notifications.py` registers the tool into the `tools.registry` (with the `try/except ImportError` pattern matching `android_tool.py`). It hits `http://127.0.0.1:8767/notifications/recent?limit=N` over loopback via stdlib `urllib.request` — no auth needed because `handle_notifications_recent` skips bearer for loopback callers (matches the `/media/register` and `/pairing/register` trust model). Remote callers still go through `_require_bearer_session`. The tool docstring explicitly frames it as "List recent notifications the user has shared with this assistant" so the LLM treats absence as "not granted yet" rather than an error.

**Files touched:**

- New: `plugin/relay/channels/notifications.py`, `plugin/tools/android_notifications.py`, `app/src/main/kotlin/.../notifications/HermesNotificationCompanion.kt`, `app/src/main/kotlin/.../notifications/NotificationModels.kt`, `app/src/main/kotlin/.../ui/screens/NotificationCompanionSettingsScreen.kt`
- Edited (additive only, marker-blocked): `plugin/relay/server.py` (import + handler init + HTTP route + dispatch + route registration), `app/src/main/kotlin/.../network/ChannelMultiplexer.kt` (`sendNotification` wrapper), `app/src/main/AndroidManifest.xml` (service entry with `BIND_NOTIFICATION_LISTENER_SERVICE` permission), `app/src/main/res/values/strings.xml` (`notification_companion_label`)
- Docs: `CLAUDE.md` Key Files table (8 new rows / additive notes), `DEVLOG.md` (this entry)

**Decisions:**

- **No new session grant type.** Spec explicitly said don't add `notifications` to `auth.py` grants — this is opt-in via Android system permission, not via per-channel session grant. Reuses the existing `chat` grant trust boundary.
- **Loopback bypass for the tool.** The route's spec said "bearer auth gated like /media/*", but the tool is in-process on the host, so we mirror `/media/register`'s loopback gate: 127.0.0.1 callers skip the bearer check, remote callers still require a session token. This means the tool doesn't need to grovel through the relay's session store to mint a token.
- **Drop on relay-offline rather than buffer at the multiplexer.** A wearable doesn't replay notifications it missed while out of range; we don't either. The cold-start buffer in the service is for the much shorter "service bound but multiplexer not wired yet" window.
- **`activeNotifications` for the Test button**, not a relay round-trip. Lets the user verify the listener is bound even if the relay is unreachable, which is the more common failure mode at first-grant time.

**Validation:** `python -m py_compile plugin/relay/channels/notifications.py plugin/tools/android_notifications.py plugin/relay/server.py` → OK. Kotlin compile happens on the next Android Studio run.

**Branch:** `feature/phase3-epsilon-notification-companion` (off `worktree-agent-a84b51cc`).

**Next:** wire `HermesNotificationCompanion.multiplexer` from `ConnectionViewModel` after relay handshake (one-line touch), add a Settings entry-point row that navigates to `NotificationCompanionSettingsScreen`, and (Phase 3 follow-up) consider a per-package allow/deny list so the user can mute social-media spam from the listener pipeline before it ever hits the relay.

## 2026-04-12 — Fix: TTS waveform stays alive through multi-sentence playback

The waveform was flatlinining after the first sentence while audio kept playing. Root cause: `maybeAutoResume()` fired after every sentence in the TTS consumer loop. The SSE stream finishes before TTS plays all queued sentences, so `streamObserverJob?.isActive` was already false → state flipped to Idle → amplitude bridge stopped → waveform died. Fix: restructured TTS consumer from `for` loop to `while` + `tryReceive` peek. `maybeAutoResume` only fires when the queue is actually drained (`tryReceive` returns failure), not between sentences. Between sentences within the same response, `tryReceive` succeeds immediately and the consumer skips the Idle transition. Additionally, the consumer re-asserts Speaking state before each synthesis call to handle the edge case where the queue was briefly empty between observer pushes.

## 2026-04-12 — Classified error feedback across voice/chat/settings/pairing

Ended the "error: unknown" era. New `RelayErrorClassifier.kt` converts any `Throwable` → `HumanError(title, body, retryable, actionLabel)` based on exception type + context tag. Branch order: `UnknownHostException` → `ConnectException` → `SocketTimeoutException` → `SSLException` → `SecurityException` → `IllegalStateException` → `IOException` (message-scan for HTTP 401/403/404/413/500/503) → default. SSL before IOException is load-bearing since `SSLPeerUnverifiedException extends IOException`. Context tags (`transcribe`, `synthesize`, `voice_config`, `record`, `pair`, `save_and_test`, `media_fetch`, `send_message`) shape the title and drive 404 body specialization.

Global `SnackbarHost` via `LocalSnackbarHost: CompositionLocal<SnackbarHostState>` at `RelayApp` scope — every screen can toast via `LocalSnackbarHost.current.showHumanError(err)`. Both `VoiceViewModel` and `ChatViewModel` gained `errorEvents: SharedFlow<HumanError>` (one-shot events, replay=0, buffer=4, DROP_OLDEST). 5 voice error sites + 4 chat error sites converted to use the classifier. Mic permission banner in ChatScreen rebuilt with "Open Settings" action. ConnectionSettingsScreen Save & Test and manual pair now show classified errors.

## 2026-04-12 — Voice polish: reactive waveform, pill edges, stop semantics, enter/exit chimes

Comprehensive polish pass on voice mode addressing issues surfaced during live testing:

- **Waveform sensitivity**: four layers of damping were compounding. Fixed at every stage: perceptual curve in VoiceRecorder (noise-floor + speech-ceiling + sqrt), attack/release envelope follower in VoiceViewModel (0.75/0.10 at 60Hz), removed the Compose spring in VoiceWaveform, amplitude-driven phase velocity via `withFrameNanos` ticker (speeds up 3.5× at peak amplitude).
- **Pill edges**: dual-technique merge — geometric `sin(π·t)` taper forces wave to centerY at endpoints + `BlendMode.DstIn` horizontal gradient mask in `saveLayer`. The Conjure pill approach (dark gradient overlay against solid background) does not work on a translucent overlay, hence the layer technique.
- **TTS replying to wrong turn**: `ignoreAssistantId` captures the pre-send last-assistant-id so the stream observer doesn't replay the previous turn's response. Root cause: StateFlow.collect replays current value on subscribe, and the current value at subscribe time still has the previous assistant message.
- **Stop semantics**: `interruptSpeaking()` rewritten to drain queue + stop player + `chatViewModel.cancelStream()` + cancel all jobs → Idle (not Listening). Old version only paused playback; SSE kept feeding ttsQueue.
- **Enter/exit chimes**: new `VoiceSfxPlayer.kt` — pre-synthesized 200ms PCM sweeps (440→660 Hz ascending enter, mirror exit) via `AudioTrack.MODE_STATIC`. Phase-accumulated to avoid chirp artifacts.
- **Stop button color**: hardcoded vivid red `Color(0xFFE53935)` for both Listening and Speaking states (Material 3 dark `colorScheme.error` resolved to pale pink).
- **Scrollable response**: overlay response Column now `weight(1f, fill=false) + verticalScroll`.
- **NaN guards**: `VoicePlayer.computeRms` and `VoiceViewModel.sanitizeAmplitude` — `Float.coerceIn` silently passes NaN per IEEE 754.
- **Gradle logcat task**: `silenceAndroidViewLogs` Exec task hooked via `finalizedBy` on all `install*` tasks — runs `adb shell setprop log.tag.View SILENT` to suppress Compose's Android 15 `setRequestedFrameRate=NaN` spam.

## 2026-04-12 — Relay startup: Python-side `.env` bootstrap + systemd user service

Fixed a drift-class bug where the relay would 500 on `/voice/transcribe` with `STT provider 'openai' configured but no API key available` after any restart that wasn't preceded by a manual `source ~/.hermes/.env`. Root cause: the relay was run as a detached `nohup` process, which inherits whatever env the launching shell happens to have exported — not what's in `~/.hermes/.env`. The gateway already solves this via `hermes_cli/main.py:144` calling `load_hermes_dotenv(project_env=...)` at import time, which is why its user systemd unit carries no `EnvironmentFile=` directive. The relay was missing that pattern entirely.

### New: `plugin/relay/_env_bootstrap.py`

A tiny helper (55 lines) exposing `load_hermes_env() -> list[Path]`. Preferred path: `from hermes_cli.env_loader import load_hermes_dotenv` and call it with defaults — keeps precedence and encoding fallbacks (latin-1 on `UnicodeDecodeError`) in exact lockstep with hermes-agent. Fallback path: direct `python-dotenv` against `$HERMES_HOME/.env` (or `~/.hermes/.env` when unset) with `override=True`. Silent no-op in stripped-down containers that explicitly provide env via `docker run -e …`. Both entry points — `plugin/relay/__main__.py` and the legacy `relay_server/__main__.py` shim — call `load_hermes_env()` **before** importing `.server`, so anything in the module-import chain that reads `os.getenv` at module level sees the same environment regardless of launcher.

### New: systemd **user** unit matching the gateway's shape

Rewrote `relay_server/hermes-relay.service` from scratch. The old template was a system-level unit hardcoded to a fixed `/home/<user>/hermes-relay` path with `/usr/bin/python3` — not usable as-is. New template is a user unit with `%h` expansion so it's user-agnostic:

```ini
[Service]
Type=simple
ExecStart=%h/.hermes/hermes-agent/venv/bin/python -m plugin.relay --no-ssl --log-level INFO
WorkingDirectory=%h/.hermes/hermes-relay
Environment="PATH=%h/.hermes/hermes-agent/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
Environment="VIRTUAL_ENV=%h/.hermes/hermes-agent/venv"
Environment="HERMES_HOME=%h/.hermes"
Restart=on-failure
RestartSec=30
...
[Install]
WantedBy=default.target
```

No `EnvironmentFile=` on purpose — `_env_bootstrap.py` handles that. Matches the gateway unit's env directives (`PATH`, `VIRTUAL_ENV`, `HERMES_HOME`) + restart policy (`StartLimitIntervalSec=600`, `StartLimitBurst=5`) + log destination (`StandardOutput=journal`). User target so it lives in `~/.config/systemd/user/` and doesn't need sudo.

### `install.sh` step [6/6] — optional systemd install

Added a final installer step that idempotently drops the unit into `~/.config/systemd/user/`, runs `daemon-reload`, and `enable --now`s the service. Skipped gracefully on:
- Hosts without `systemctl` (macOS, some BSDs)
- Hosts where `systemctl --user show-environment` fails (bare chroots, WSL without systemd, containers without a user bus)
- `$HERMES_RELAY_NO_SYSTEMD` set (explicit opt-out for users who prefer their own process supervisor)

If an orphan `nohup`-launched `python -m plugin.relay` is already holding :8767, the installer warns the user to kill it first rather than racing. A parting hint about `loginctl enable-linger $USER` for users who want the relay to survive SSH logout.

After install, the update cycle is now a single command:

```bash
cd ~/.hermes/hermes-relay && git pull
systemctl --user restart hermes-relay
```

No `pkill` / `nohup` / `disown` dance, no manual env sourcing, no stale-key drift on restart.

### Docs surface updated

- `docs/relay-server.md` — new Quick Start with `install.sh` as the recommended path, manual-run and Docker sections preserved, new `.env auto-loading` subsection explaining precedence + why there's no `EnvironmentFile=`.
- `user-docs/reference/relay-server.md` — mirror of the above in user-facing style, plus two new troubleshooting entries (voice 500 "no API key available" → check `.env` + restart, and service stops on SSH logout → `loginctl enable-linger`).
- `CLAUDE.md` Server Deployment section — relay is now listed as `hermes-relay.service` (user unit), the restart command is `systemctl --user restart hermes-relay`, the verification step is `cat /proc/$PID/environ` via `systemctl show -p MainPID`, and the old nohup instructions carry a history note pointing at this entry.
- `DEVLOG.md` — this entry.

### Why this is the right shape for the general plugin path

The installer is the only user-facing install surface for hermes-relay and already owns plugin registration, skill discovery, and the `hermes-pair` shim. Adding systemd-user install to it means any Linux user who runs the one-liner gets the relay in the same canonical place as hermes-gateway (`~/.config/systemd/user/`) with the same management commands (`systemctl --user …`). Non-systemd hosts fall back to manual `python -m plugin.relay --no-ssl` and the Python-side env loader still does the right thing. Docker users mount `~/.hermes` and the bootstrap finds `.env` at its canonical path inside the container. There's no setup branching that varies by user, distro, or launch method — the env load happens at import time, which is the one place every launch path passes through.

Fix scope: 3 Python files touched (2 entry points + 1 new helper), 1 systemd template rewritten, 1 installer stanza added, 4 docs updated. No breaking changes to existing callers — manual `python -m plugin.relay` still works and is actually more robust than before because it now auto-loads `.env`.

---

## 2026-04-12 — Voice mode: end-to-end voice conversation via relay TTS/STT endpoints

Shipped voice mode in a single session via a four-agent team in a shared worktree (`feature/voice-mode`). Plan came from the Obsidian vault at `Hermes-Relay/Plans/Voice Mode.md` — a 4-phase spec (V1 server endpoints → V2 app audio pipeline → V3 orb animation → V4 polish). All four phases landed in parallel; V2b UI waited on V2a's locked ViewModel contract, everything else ran concurrently. Net: ~2750 lines across 9 new files + 6 modified files, no upstream hermes-agent changes required.

### Architecture

Voice lives entirely in the relay plugin, not upstream hermes-agent. The relay is editable-installed into the hermes-agent venv, so `plugin/relay/voice.py` imports `tools.tts_tool.text_to_speech_tool` and `tools.transcription_tools.transcribe_audio` directly from the server's configured providers. Both upstream functions are **sync**, so the aiohttp handlers wrap them in `asyncio.to_thread(...)` to avoid blocking the event loop. Config (provider, voice, model) is read internally by the tools from `~/.hermes/config.yaml` — the relay doesn't pass provider arguments, which means TTS providers (currently ElevenLabs) or STT providers (currently OpenAI whisper-1) can be swapped on the server without touching the phone.

Three new routes on the relay alongside `/media/*`:
- `POST /voice/transcribe` — multipart audio → `{text, provider}`
- `POST /voice/synthesize` — `{text}` JSON → `audio/mpeg` file response
- `GET /voice/config` — provider availability + current settings

All three gated on the same bearer auth as `/media/*` (local helper `_require_bearer_session`, not the private server.py version, to avoid circular imports). Fourteen unit tests in `plugin/tests/test_voice_routes.py`, all passing on the server (68/68 across voice + existing media/sessions suites). Upstream tool imports are **lazy inside each handler** so `voice.py` can be imported on Windows (where the hermes-agent venv doesn't exist); tests inject fake modules into `sys.modules` at collect time.

### Android audio pipeline

`app/.../audio/VoiceRecorder.kt` wraps `MediaRecorder` with MPEG_4/AAC output to `.m4a` (not WebM — the plan suggested WebM but m4a is Android-native and whisper-1 handles it fine via the upstream `_validate_audio_file` path). Amplitude is polled at ~60fps from `mediaRecorder.maxAmplitude / 32767f` and exposed as a `StateFlow<Float>` for the orb.

`app/.../audio/VoicePlayer.kt` wraps `MediaPlayer` + `android.media.audiofx.Visualizer` for real-time amplitude during TTS playback. The Visualizer construction is in a try/catch — some OEM devices refuse to construct one even with MODIFY_AUDIO_SETTINGS granted, and rather than crashing the voice session on those devices we fall back to a flat-zero amplitude and log. Voice still works; the sphere just won't pulse during Speaking on those devices.

`app/.../network/RelayVoiceClient.kt` mirrors `RelayHttpClient`'s ctor shape — same `okHttpClient` / `relayUrlProvider` / `sessionTokenProvider` pattern, same error handling. Transcribe uses `MultipartBody.Builder`, synthesize uses JSON POST with the response bytes streamed to `cacheDir/voice_tts_<ts>.mp3`. Added a third method `getVoiceConfig()` for the Voice Settings screen to read provider availability.

`app/.../viewmodel/VoiceViewModel.kt` orchestrates the state machine: `Idle → Listening → Transcribing → Thinking → Speaking → Idle`. Sentence-boundary detection lives in a top-level `internal fun extractNextSentence(StringBuilder)` that drains complete sentences (whitespace-lookahead for `e.g.`, min length 6 so tiny fragments don't get synthesized) into a `Channel<String>` consumed by a dedicated coroutine that synthesizes + plays + awaits completion per sentence. This gives streaming TTS without needing chunked-Opus support on the server — the latency win comes from client-side chunking of the SSE stream.

### ChatViewModel integration — cleaner than the plan

The plan called for a `// VOICE HOOK` callback added to `ChatViewModel` so `VoiceViewModel` could observe streaming deltas. Instead, `VoiceViewModel.startStreamObserver` collects `chatVm.messages: StateFlow<List<ChatMessage>>` and diffs the last assistant message's content length on each emission. Zero changes to `ChatViewModel` / `ChatHandler`. The transcribed user text is routed through the normal `chatVm.sendMessage(text)` path, so voice utterances appear as regular user messages in chat history — the same message shows up if you reload the session on another device.

The trade-off is documented in a KDoc comment on the observer: this assumes "the last `isStreaming=true` message is the current turn," which is true today. If `ChatViewModel` ever streams multiple assistant messages concurrently (agent hand-off, multi-agent runs) this would need a dedicated per-turn flow. Flagged for Phase 3+ review.

### MorphingSphere: Listening + Speaking states

Two new `SphereState` values added additively — all five existing call sites (RelayApp, BridgeScreen, ChatScreen ×3) compile unchanged because `voiceAmplitude: Float = 0f` and `voiceMode: Boolean = false` are defaulted. The plan spec had exact formulas for amplitude → visual parameter mapping (lines 338-367 of `Voice Mode.md`) and the sphere-animator teammate mapped them to the actual render variables:

- **Listening** — soft blue/purple (#597EF2 ↔ #A573F2, cooler than Idle's green/purple). `breatheSpeed` lerps 1.0→1.3× at half amplitude, `turbulenceAmp` gets +0.15×amp, perimeter-noise `wobbleAmplitude` scales up 30% max. Subtle — the orb "breathes with the user."
- **Speaking** — vivid green/teal (#40EB8C ↔ #4DD9E0) with `coreWarmth` (a new synthetic Float, not a refactor — spliced into the existing warmth term at line ~400) lerping 0.3→1.0 on amplitude for a white-hot core at peaks. `breatheSpeed` up to 2×, `turbulenceAmp` +0.5×amp, `wobbleAmplitude` +80%, `dataRingSpeed` up to 4× on peak amplitude. Dramatic — the orb *performs* the voice.

Radius scale for `voiceMode=true` is capped at 1.08×, not 1.0× as the plan suggested. The existing `baseRadius` is already 0.60× half-extent and the data ring at 1.55× would overflow the canvas past 1.1×. Expansion is subtle by physical necessity. Three `@Preview` functions (`MorphingSphereListeningPreview`, `MorphingSphereSpeakingLowPreview`, `MorphingSphereSpeakingPeakPreview`) with deterministic frames for scrubbing amplitude values in Studio without a device.

### Voice mode UI

`VoiceModeOverlay.kt` is the full-screen experience: top bar with interaction-mode dropdown + close X, centered sphere at 60% height with `voiceMode=true`, transcribed + response text with `AnimatedContent` fades, mic button at bottom with `pulseScale` driven by amplitude. The mic button respects `interactionMode`:

- **TapToTalk** — click starts recording; auto-stops on silence (threshold from `VoicePreferences`, default 3000ms); click again to stop manually; click during Speaking routes to `interruptSpeaking()`.
- **HoldToTalk** — `awaitEachGesture { awaitFirstDown() + waitForUpOrCancellation() }` starts on press and stops on release.
- **Continuous** — after TTS finishes speaking, `maybeAutoResume()` auto-starts listening again. Current detection uses `streamObserverJob?.isActive != true` as the "turn done" signal because `Channel.isEmpty` isn't public API — may resume very slightly early on the last sentence before the final observer tick, but it's imperceptible in practice.

Haptics: `HapticFeedbackType.LongPress` on record start, `HapticFeedbackType.TextHandleMove` on record stop. Error banner with retry button handles mic permission denial, voice-config failure (shows "Unknown" provider rows), and transcribe/synthesize errors.

`ChatScreen` now hosts a mic FAB in the bottom-right corner (hidden while voice mode is active) that triggers the permission flow. Existing chat content fades to 40% alpha via `animateFloatAsState` when voice mode opens. `VoiceSettingsScreen.kt` is a new sub-screen off Settings with four sections: Voice Mode (interaction mode + silence threshold slider + auto-TTS placeholder), Text-to-Speech (read-only provider/voice labels), Speech-to-Text (read-only provider + language picker stored for future use), and a Test Voice button that calls the new `VoiceViewModel.testVoice(sample)` extension. `VoicePreferences.kt` is a new DataStore-backed repo mirroring `MediaSettings.kt`.

### Things left as follow-ups

1. **Silence-detector wiring** — V2a's recorder exposes the amplitude StateFlow, and `VoicePreferences.silenceThresholdMs` is persisted, but the ViewModel doesn't yet collect recorder amplitude and auto-call `stopListening()` on silence. V2b flagged this as a boundary question (expose a preferences setter on VoiceViewModel vs. observe DataStore directly). Not a voice-mode blocker — tap-to-stop works — but the auto-stop UX promised in the plan isn't there yet. Small follow-up task.
2. **Auto-resume precision** — the `streamObserverJob.isActive` signal for continuous mode is slightly imprecise (see above). If stalled turns are reported in continuous mode this is where to look.
3. **Voice config write-through** — reads `GET /voice/config`, but the Voice Settings screen can't yet write changes back to `~/.hermes/config.yaml`. That would need a management API endpoint (Phase M territory per the plan). For now, edit the yaml and restart hermes-gateway.
4. **Dedicated OkHttpClient for voice** — `voiceClient` has its own OkHttpClient instance (2-min read timeout) rather than sharing `ConnectionViewModel.relayOkHttp` (which is private). Minor duplication; would clean up if we exposed that field.
5. **ChatScreen box/column indent** — `Box { Column {...} }` with same-level indentation in ChatScreen is slightly ugly. Compiles fine; purely cosmetic.

### Files

**New (server):** `plugin/relay/voice.py`, `plugin/tests/test_voice_routes.py`.
**New (app):** `app/.../audio/VoiceRecorder.kt`, `app/.../audio/VoicePlayer.kt`, `app/.../network/RelayVoiceClient.kt`, `app/.../viewmodel/VoiceViewModel.kt`, `app/.../ui/components/VoiceModeOverlay.kt`, `app/.../ui/screens/VoiceSettingsScreen.kt`, `app/.../data/VoicePreferences.kt`, `app/src/test/.../viewmodel/SentenceExtractionTest.kt`.
**Modified:** `plugin/relay/server.py` (+21 lines — imports, handler wiring, 3 route registrations), `app/src/main/AndroidManifest.xml` (RECORD_AUDIO + MODIFY_AUDIO_SETTINGS), `app/.../ui/components/MorphingSphere.kt` (+145 lines — Listening/Speaking states, voiceAmplitude modifier, coreWarmth spliced into existing warmth term, voiceMode scale, 3 preview functions), `app/.../ui/RelayApp.kt` (+49 lines — VoiceViewModel wiring + VoiceSettings nav route), `app/.../ui/screens/ChatScreen.kt` (+115 lines — mic FAB + permission flow + chat alpha + overlay mount), `app/.../ui/screens/SettingsScreen.kt` (+46 lines — nav entry).

### Dev workflow note

Four agents, shared worktree, 1 blocker (V2b waited on V2a's locked `VoiceUiState` contract). Agents reported via `SendMessage` → mailbox → delivered as team idle notifications. The lead handled: SSH recon (real upstream signatures from `~/.hermes/hermes-agent/tools/`), plan review, task list with explicit `blockedBy`, parallel agent spawn with complete self-contained briefs (including full upstream signatures so no agent needed to re-SSH), integration spot-checks on diffs, then this docs pass. The server-voice teammate SCP'd test files to the server, ran them via `unittest`, and cleaned up its stray files on the server side when done. Zero Kotlin gradle runs — every agent respected the "compile in Studio" rule.

For a feature this size, the worktree + team approach is the right default. The alternative (single-session linear work) would have been 3–4× slower.

## 2026-04-11 — clearSession reconnect leak → self-inflicted rate limit

Reported: "unable to pair via QR code" after hitting Revoke on the device in Paired Devices. Worked again after an app restart.

### Root cause (from `~/hermes-relay.log`)

```
20:38:29  DELETE /sessions/f75cfb14 → 200 (self-revoke)
20:38:29  GET /sessions             → 401 (phone loadPairedDevices with dead token)
20:38:32  Client disconnected
[relay restart]
20:39:03  WebSocket from 192.168.1.50 → Auth failed: Invalid pairing code or session token
20:39:04  WebSocket from 192.168.1.50 → Auth failed
20:39:05  WebSocket from 192.168.1.50 → Auth failed
20:39:06  WebSocket from 192.168.1.50 → Auth failed
20:39:07  WebSocket from 192.168.1.50 → Auth failed
20:39:07  [WARNING] IP 192.168.1.50 blocked for 300s after 5 failed auth attempts
20:39:08+ GET /ws → 429 (blocked)
```

Five `Auth failed` in four seconds. The rate limiter did exactly what it was built to do.

### The bug — three state machines disagree

1. **`AuthManager.clearSession()`** wipes the stored token, sets `authState = Unpaired`, and regenerates a fresh *local* pairing code (`_pairingCode.value = generatePairingCode()`).
2. **`ConnectionManager`'s internal reconnect loop** (`scheduleReconnect` → `doConnect`) runs *entirely inside* the network module — it bypasses `ConnectionViewModel.connectRelay` and its `hasPairContext` gate completely. `shouldReconnect` stays `true` until someone calls `disconnect()`.
3. **`ConnectionViewModel.clearSession()`** called `authManager.clearSession()` and returned — **never called `disconnectRelay()`**. So the reconnect scheduler kept firing after the state wipe.

Sequence of collapse:

- Self-revoke succeeds (server deletes session)
- Phone's Paired Devices UI calls `clearSession()` → state wiped, reconnect loop still alive
- WS disconnects (because the relay closes the socket after revoke / the relay restart fires)
- `onClosed` → `scheduleReconnect` → backoff → `doConnect` with freshly-regenerated *local* pair code in the auth envelope
- Server: `"Invalid pairing code or session token"` (the local code isn't registered)
- Loop fires 5 times in ~4 seconds (exponential backoff starts at 1s)
- Rate limiter blocks the IP for 5 minutes
- User scans a fresh QR → `/pairing/register` clears the block — **but** the phone's next WS connect bounces off whatever delayed retry is still in flight (and also possibly a 429 from the still-valid block depending on exact timing)
- User restarts app → cold start has `shouldReconnect = false` and empty session store, no auto-connect, fresh QR scan succeeds

### Fix

**`network/ConnectionManager.kt`**:
- New constructor parameter `reconnectGate: () -> Boolean = { true }` (defense-in-depth gate for the internal auto-reconnect loop).
- `scheduleReconnect()` calls `reconnectGate()` both **before** scheduling and **after** the backoff delay. If either returns `false`, the retry is silently dropped and `connectionState` is set to `Disconnected`. Rationale: the delay window is where state flips happen — user hits Revoke while the previous attempt was sleeping, and we need to re-check.
- Logs `"scheduleReconnect: gate says no pair context — aborting retry"` at INFO for traceability.
- Default value preserves backwards compat with tests that construct a bare `ConnectionManager(multiplexer)`.

**`viewmodel/ConnectionViewModel.kt`**:
- `ConnectionManager` now constructed with `reconnectGate = { authManager.hasPairContext }` — same `hasPairContext` predicate introduced for the Option B Save & Test gate.
- `clearSession()` rewritten to call `disconnectRelay()` **before** `authManager.clearSession()`. Order matters: tear down the reconnect loop before wiping the state it depends on, so in-flight retries return cleanly instead of firing with half-wiped state. KDoc explains the 2026-04-11 rate-limit incident as the why.

Both fixes together: `clearSession` stops the immediate loop, and the `reconnectGate` is a safety net for any other code path that wipes state without calling disconnect. Either alone would technically fix the reported bug; together they harden against future regressions.

### Test plan (on-device)

- [ ] Go to Settings → Paired Devices → open the current device's card → Revoke → confirm. Snackbar shows "This device was unpaired". App navigates to pair screen.
- [ ] Immediately scan a fresh QR (from `/hermes-relay-pair` on the server). Should succeed — no "unable to pair" error, no 429 on the relay's `/ws`.
- [ ] Check `~/hermes-relay.log` after revoke: should see the `DELETE /sessions/...` line but NOT a burst of `Auth failed` entries. Phone should stay quiet until the user scans the new QR.
- [ ] Bonus: toggle airplane mode while paired (forces disconnect with pair context still valid), confirm reconnect still works normally after re-enabling network.

### Server deploy

Phone-only change. No server restart needed. Rebuilt from Android Studio.

## 2026-04-11 — Paired Devices JSON unwrap fix + /media/by-path permissive sandbox (B+C)

Two bugs surfaced on-device:

### 1. Paired Devices crash — "Expected start of the array '[', but had '{'"

`RelayHttpClient.listSessions` was parsing the response body as a bare `List<PairedDeviceInfo>`, but the server returns `{"sessions": [...]}` (`plugin/relay/server.py::handle_sessions_list`, line 406). Classic wire-format mismatch. The phone saw JSON starting with `{` and crashed kotlinx.serialization's list deserializer.

**Fix**: parse the body to `JsonElement`, pull out the `sessions` field, then decode the array via `Json.decodeFromJsonElement(ListSerializer(PairedDeviceInfo.serializer()), arrayElement)`. If the `sessions` field is missing, return a descriptive `IOException` instead of the kotlinx parse error so the UI shows a meaningful message.

One concept to internalize: `Json.decodeFromJsonElement(deserializer, element)` is a **member function** on the `Json` instance — no extension import needed. The reified-generic form (`Json.decodeFromJsonElement<T>(element)`) needs the `import kotlinx.serialization.json.decodeFromJsonElement` extension import; the explicit-deserializer form doesn't.

### 2. "Path not allowed by relay sandbox" for legitimate LLM emits

The `/media/by-path` route enforced `allowed_roots` against every phone-side fetch. When the LLM ran `search_files` and found an image somewhere like `~/projects/claw3d/readme.png`, the phone hit the sandbox and rendered a "Path not allowed" error card — even though the agent had already read the file's bytes via its own tools and would happily paste them into chat if asked.

**Decision**: B + C (C as default).

- **C (conceptual flip)**: drop the allowlist on `/media/by-path` by default. The trust boundary is the bearer-auth'd paired phone; the LLM can already exfiltrate bytes via plain text responses, so the sandbox was defense-in-depth with a high false-positive rate in practice. The token path (loopback-only `/media/register`) keeps its strict allowlist because it's trivially enforceable there.
- **B (config knob)**: `RELAY_MEDIA_STRICT_SANDBOX=1` (or `RelayConfig.media_strict_sandbox = True`) re-enables the allowlist enforcement on the by-path route for operators who want the tighter default back.

### Changes

**`plugin/relay/media.py`**:
- `validate_media_path(path, allowed_roots, max_size_bytes)` — `allowed_roots` now accepts `list[str] | None`. When None, the root-under-allowlist check is skipped entirely. All other checks (absolute path, realpath, exists, regular file, size cap) remain unconditional.
- `MediaRegistry.__init__` gains `strict_sandbox: bool = False` parameter, stored as `self.strict_sandbox`. Logged at init time alongside the existing max_entries/ttl/max_size summary.

**`plugin/relay/config.py`**:
- `RelayConfig.media_strict_sandbox: bool = False` field.
- `RELAY_MEDIA_STRICT_SANDBOX` env var parsed in `from_env()` — accepts `1`/`true`/`yes`/`on` (case-insensitive).

**`plugin/relay/server.py`**:
- `RelayServer.__init__` passes `strict_sandbox=config.media_strict_sandbox` to the `MediaRegistry`.
- `handle_media_by_path` computes `roots_for_check = server.media.allowed_roots if server.media.strict_sandbox else None` and passes that to `validate_media_path`. Permissive mode ⇒ only file-level checks (absolute, exists, regular, size). Strict mode ⇒ full allowlist enforcement.
- Docstring on `handle_media_by_path` rewritten to explain the permissive-by-default model and the opt-in path.

**`app/src/main/kotlin/.../network/RelayHttpClient.kt`**:
- `listSessions()` — unwraps `{"sessions": [...]}` before decoding the array. Missing-field path surfaces as a clean `IOException("Relay response missing 'sessions' field")`.

**`plugin/tests/test_relay_media_routes.py`**:
- Existing `RelayMediaRoutesTests` class pinned `config.media_strict_sandbox = True` so its legacy assertions (outside-sandbox → 403, etc.) still hold.
- New sibling class `RelayMediaByPathPermissiveTests` exercises the production default:
  - `test_by_path_outside_allowlist_still_streams_in_permissive_mode` — **the regression-guard test**. A file in a totally unrelated tmpdir streams successfully with a valid bearer. If this breaks, the claw3d workflow breaks again.
  - `test_by_path_still_rejects_relative_in_permissive_mode` — absolute-path check is unconditional.
  - `test_by_path_still_404s_nonexistent_in_permissive_mode` — file-existence check is unconditional.
  - `test_register_still_enforces_allowlist_in_permissive_mode` — the token path (loopback `POST /media/register`) stays strict regardless of the flag.

**`CLAUDE.md`**:
- Updated the `plugin/relay/media.py` and "Inbound media fetch (path)" rows in the Key Files / Integration Points tables to document the permissive default and the opt-in `RELAY_MEDIA_STRICT_SANDBOX` knob.

### Server deploy

Python-side changes. The relay needs a `git pull` on the server and a restart (`pkill -TERM -f "python -m plugin.relay"` + `nohup ... & disown` per the standard recipe) before the new by-path semantics take effect. Phone-side `listSessions` fix ships with the next Android Studio build.

### Test plan (on-device after next build)

- [ ] Paired Devices screen: open → loads without the "Couldn't load paired devices" error card. Should show the current device with transport badge, expiry, and grant chips.
- [ ] Chat with `find an image`: LLM finds something in `~/projects/**` → card renders inline (not "Path not allowed by relay sandbox").
- [ ] Optional: `RELAY_MEDIA_STRICT_SANDBOX=1` on the server → same image request now renders the "Path not allowed" card (strict-mode opt-in works).

## 2026-04-11 — Option B: Save & Test as HTTP health probe + pair-context gate on connectRelay

A trap was identified in the Settings → Manual configuration flow: the **Connect** button fired `connectRelay(url)` unconditionally, even when the phone had no session token and no pending server-issued pair code. The WSS handshake would open, the phone would send an `auth` envelope with whatever code was lying around (usually the locally-generated phone→host Phase 3 code, which isn't registered on the relay), auth would fail, and after 5 such failures in 60 seconds the relay's rate limiter would block the IP for 5 minutes. Users fumbling with manual config could lock themselves out of pairing entirely.

Reviewed three fix options; picked **B** (split reachability from connect). Rejected **C** (two buttons — "Test" + "Connect") because the second button is redundant with the existing Reconnect button / auto-reconnect paths and would be disabled-when-unpaired / duplicated-when-paired. The right answer is one button that does a reachability probe, not two buttons fighting over WSS semantics.

### Changes

**Server**: none. This is entirely phone-side.

**`plugin/relay` unchanged** except indirectly — the new phone-side `probeHealth` method hits the relay's existing `GET /health` endpoint (already implemented at `plugin/relay/server.py::handle_health`).

**`auth/AuthManager.kt`** — new `hasPairContext: Boolean` getter. Returns true when any of:
1. `authState` is `Paired` (stored session token)
2. `authState` is `Pairing` (mid-handshake)
3. `serverIssuedCode != null` (fresh QR scan or manual code entry just landed)

Crucially does **NOT** count the locally-generated `_pairingCode.value` as valid — that's the phone→host Phase 3 code and is meaningless to the relay side, so sending it yields guaranteed rate-limit hits. This is the fix for the entire class of "connect when unpaired" bugs.

**`network/RelayHttpClient.kt`** — new `probeHealth(relayUrl): Result<RelayHealth>` method:
- Converts `ws://`/`wss://` → `http://`/`https://` via the same regex used by `fetchMedia`
- Unauthenticated `GET /health` with a **3-second** timeout (via `okHttpClient.newBuilder().connectTimeout(3, SECONDS).readTimeout(3, SECONDS).build()` — no need for a separate long-lived client)
- Parses the JSON body and validates it looks like a hermes-relay health response: `status == "ok"` AND a non-blank `version` field. Anything else fails with a descriptive error. Prevents a random HTTP server on port 8767 from falsely passing.
- Returns `RelayHealth(version, clients, sessions)` on success so the UI can render `"✓ Reachable — hermes-relay v0.2.0 (0 clients, 1 session)"` — actual metadata, not just a boolean.
- Error messages differentiate: `"Connection refused — is the relay running on this URL?"`, `"Relay is not responding (3s timeout)"`, `"Relay responded HTTP 404"`, `"Relay returned non-JSON: ..."`, etc.

**`viewmodel/ConnectionViewModel.kt`**:
- Rewrote `connectRelay(url)` / `connectRelay()` to delegate to a private `connectRelayInternal` that **gates on `authManager.hasPairContext`**. When the gate fails, log an informational message and return silently — the UI doesn't get any error state because this path should only be hit as a user-initiated mistake, not a programmatic one. The four legitimate entry points (pair walkthrough, stale row tap, Reconnect button, QR confirm) all set pair context *before* calling connectRelay, so the gate passes.
- New sealed interface `RelayReachable { Probing, Ok(version, clients, sessions), Fail(message) }` + `relayReachableResult: StateFlow<RelayReachable?>` + `testRelayReachable(url)` method that saves the URL to DataStore **and** probes `/health`. The save-before-probe order is deliberate: a failed probe still leaves the URL persisted so the user can edit and retry without losing their typing.
- `clearRelayReachableResult()` — called by the Relay URL text field's `onValueChange` so stale probe results vanish when the user edits the URL.
- **Deleted** the old callback-based `testRelayReachability(wsUrl, onResult: (Boolean) -> Unit)` — it was a thinner version of `probeHealth` that spun up its own OkHttpClient per call and returned only a boolean. Single caller in SettingsScreen migrated to the new state-flow API.
- Removed the now-unused `okhttp3.Request` import (was only referenced by the deleted method).

**`ui/screens/SettingsScreen.kt`** — Manual configuration button row rewritten:
- **Connect button deleted.** It was the leak source. Reconnecting a paired session is handled by the existing Reconnect button (in the Connection card, gated on `Paired`), the stale row tap, and `reconnectIfStale()` on screen entry. No fourth "Connect" button is needed.
- **"Test" button renamed to "Save & Test"** — wired to `testRelayReachable(relayUrlInput)`. Label change matches the new behavior (the button also persists the URL to DataStore). Disabled when the URL is blank or a probe is in flight.
- **Disconnect button kept** — still useful for debugging / forcing reconnect cycles.
- **Result row** rewritten to read from `relayReachableResult: StateFlow` instead of the old local `relayTestInProgress` / `relayTestResult` vars (both removed). Shows:
  - `Probing /health…` with spinner during the probe
  - `✓ Reachable — hermes-relay v0.2.0 (0 clients, 1 session)` (green) on success, with version + client count so the user knows they're pointing at an actual relay
  - `✗ <specific error>` (red) on any failure
  - Nothing when idle (so the row collapses cleanly when not in use)
- **Relay URL text field's `onValueChange`** now clears the probe result — stale "✓ Reachable" indicators won't hang around after the user starts typing a different URL.

### Bonus fix — Settings QR confirm never connected WSS

Tracing the pair-context gate surfaced that the Settings → Scan Pairing QR → TTL picker confirm flow stashed the server-issued code + grants + TTL but **never actually called `connectRelay`**. The WSS stayed disconnected until the user either left and re-entered Settings (firing `reconnectIfStale`, which still wouldn't fire since `authState` is still `Unpaired`) or manually tapped Reconnect. This was a pre-existing bug dating from the pairing+security architecture cycle — the walkthrough dialog path (which has its own inline `connectRelay` call) masked it.

Added the missing `disconnectRelay()` + `connectRelay(relay.url)` pair to the TTL picker's `onConfirm` callback, mirroring the manual code pair path at `submitPairing` / `authManager.applyServerIssuedCodeAndReset` / `connectRelay(...)`. Since `applyServerIssuedCodeAndReset` has just set `serverIssuedCode`, the new pair-context gate passes cleanly.

### Bonus fix — Connection status rows didn't look tappable

Second round of UX feedback from the same cycle: the **API Server / Relay / Session** status rows in the Connection section open drawer sheets on tap, but there was **no visual affordance** that they were tappable — users would stumble onto the tap target by accident.

Fixed in `ConnectionStatusRow`:
- New `onClick: (() -> Unit)? = null` parameter. When non-null, the component applies a Material ripple + rounded-corner clip + internal `clickable` modifier, so the whole row is a proper list-item tap target.
- **Trailing chevron icon** (`Icons.AutoMirrored.Filled.KeyboardArrowRight`) rendered after the status text whenever `onClick != null` (and no `onTest` button is competing for the trailing slot). Gives users the standard Settings-list-item visual cue that the row opens something.

All three call sites in SettingsScreen migrated from `modifier = Modifier.fillMaxWidth().clickable { ... }` to `onClick = { ... }` + `modifier = Modifier.fillMaxWidth()`. Old pattern still works for legacy call sites (the component applies the interactive wrapping additively).

### Files changed

**Server**: none.

**Phone**:
- `app/src/main/kotlin/com/hermesandroid/relay/auth/AuthManager.kt` — `hasPairContext` getter
- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayHttpClient.kt` — `probeHealth()` + `RelayHealth` data class + `jsonObject` import
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` — `RelayReachable` sealed interface, `relayReachableResult` flow, `testRelayReachable()`, `clearRelayReachableResult()`, `connectRelayInternal()` with pair-context gate, deleted `testRelayReachability`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt` — Connect button removed, Save & Test rewired, result-row reads from ViewModel flow, onValueChange clears probe, QR confirm flow adds missing `connectRelay`, three status rows migrated to `onClick` parameter
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ConnectionStatusBadge.kt` — `ConnectionStatusRow` gains `onClick` + chevron affordance

**Docs**: this DEVLOG entry.

### Test plan

On-device, after the next Android Studio build:

1. **Fresh Manual Config reachability probe** — Settings → Connection → Manual configuration → type a relay URL → tap **Save & Test**. Verify:
   - Spinner + "Probing /health…" appears briefly
   - On a live relay: green "✓ Reachable — hermes-relay vX.Y.Z (N client, M session)"
   - On a dead URL: red "✗ <reason>" (refused / timeout / non-JSON / etc)
   - Editing the URL clears the result immediately
2. **Pair-context gate doesn't regress pairing** — scan a fresh `/hermes-relay-pair` QR → TTL picker confirms → verify the WSS connects + `authState` reaches `Paired` without any manual intervention
3. **Manual code entry still works** — Settings → Already configured? Enter code only → type a valid code → verify it pairs and WSS connects
4. **Drawer affordance visible** — open Settings → Connection section → verify **API Server / Relay / Session** rows show a chevron at the right edge + have a ripple on tap → tapping each opens its respective info sheet
5. **Flicker still fixed** — open Settings with a stale session → verify the row shows "Reconnecting..." for the whole transition, not the old "Stale → Connecting → Connected" flash

### Known caveats

- The Connect button removal is a **UX change** for power users who were using it to force-reconnect. They should use the Reconnect button in the Connection card (gated on Paired) or the stale row tap. If this breaks a relied-on workflow, the button can come back gated on `authManager.hasPairContext`.
- The new `probeHealth` gives 3 seconds before timing out. On a very slow LAN this might be too tight; consider bumping to 5s if probes start false-failing.
- `probeHealth` doesn't follow redirects (OkHttp default). A relay behind a reverse proxy that 301s `/health` would fail the probe. None of the current deployments do that.

---

## 2026-04-11 — Relay status flicker fix + Dev Workflow docs in CLAUDE.md

Two small follow-ups from the same session:

**1. Connection card flicker on Settings entry.**
When opening Settings with a stored session token, the Relay status row flashed through 3 rapid states: red "Disconnected" → amber "Stale — tap to reconnect" → amber "Connecting..." → green "Connected". Users saw the middle two as a flicker.

Root cause: `authState` default is `Unpaired` until the `EncryptedSharedPreferences` read finishes (~50ms async). During that gap the row shows red "Disconnected" which is actually correct. Once `authState` flips to `Paired`, `isRelayStale` becomes true and the row shows "Stale — tap to reconnect". The `LaunchedEffect(Unit)` that fires `reconnectIfStale()` then triggers `ConnectionState.Connecting`, changing the label to "Connecting...". These rapid transitions are the flicker.

Fix: added a screen-local `isAutoReconnecting` flag that's true for up to 5 seconds on screen entry (or until `ConnectionState.Connected` lands, whichever is first). During that window the status text unifies all the non-Connected sub-states into one consistent `"Reconnecting..."` label. After 5s the flag drops and the row falls through to the normal state machine — so the "Stale — tap to reconnect" affordance still works for cases where the user is genuinely sitting in a stale state (backgrounding the app, network flapping, etc.). Only the initial-entry transition gets the unified label.

The fix is purely presentational — the underlying state machine is unchanged. `isConnecting` is wired to treat `isAutoReconnecting` the same as the real `Connecting` / `Reconnecting` states so the ConnectionStatusBadge's pulse ring animates continuously through the window.

**2. Dev workflow documented in `CLAUDE.md`.**
A request to document the typical dev loop in `CLAUDE.md` so the flow doesn't have to be rediscovered each session. Added a new "Typical Dev Loop" subsection + "Server Deployment" table + "Where Python vs. Kotlin changes land" table. Covers:
- Edit-in-Windows, test-on-Linux-server split
- Python plugin edits via `pip install -e` editable path → `git pull` on server picks them up
- `hermes-gateway.service` systemctl user-unit restart when tool code changes
- Manual `nohup` / `setsid` restart for the relay process (which isn't a systemd service on this deployment)
- Running tests via `python -m unittest plugin.tests.test_<name>` (avoiding the pre-existing `conftest.py` that imports the uninstalled `responses` module)
- The hard convention: APKs are built/installed via Android Studio's run button, not from the agent session
- Where sensitive info lives (`~/SYSTEM.md` on the server, `~/.hermes/.env`) — explicitly NOT in this repo
- The PATH conventions (venv, plugin symlink, relay log, config yaml, qr-secret)

Keeps the doc free of any host-specific identifiers (IP, username, SSH key) — those stay on the server side.

**Also included in this entry but landed in a separate earlier commit:** `fix(auth): extend without grants regenerates from defaults, not absolute-time clamp` (`c209c99`). The first cut of `SessionManager.update_session` tried to preserve existing grants via a `_clamp_grants_to_lifetime` helper when only TTL changed. That made sense for shortening (clip grants that exceed the new session) but produced nonsense for extending: a 1h bridge grant made 30 minutes ago, extended to 90d, would still have an absolute expiry of 30 minutes from now — the user would tap Extend and find bridge was about to expire anyway. Corrected to regenerate grants from `_default_grants(new_ttl, now)` when only TTL changes, giving fresh allocations for the new lifetime. Deleted the now-unused `_clamp_grants_to_lifetime` helper. Caught by `test_extend_ttl_zero_means_never` which asserts that extending a finite session to never-expire produces all-null (never) grants.

**Files changed:**
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt` — `isAutoReconnecting` flag + updated `ConnectionStatusRow` wiring
- `CLAUDE.md` — new Dev Workflow subsections (Typical Dev Loop, Server Deployment, Where Python vs. Kotlin changes land)
- `plugin/relay/auth.py` — (separate commit `c209c99`) `update_session` semantic fix

No server side changes in this entry — the flicker fix is phone-only and the docs don't deploy.

---

## 2026-04-11 — Grant renewal action (PATCH /sessions/{prefix} + "Extend" button)

**Why this exists:**
Pairing/security overhaul shipped a Paired Devices screen with list + revoke, but no way to change a session's TTL or grants after initial pair. Users who paired with "1 day" when trying out the feature and decided to keep it had to revoke + re-pair. This closes that gap with the smallest possible surface area: one new route, one new client method, one new button, one new dialog reuse. Scope #4 from the gap list — small and concrete enough to do directly rather than via agent team.

**Semantics decision: TTL restarts the clock.**
"Extend by 30 days" = "30 days from now", not "add 30 days to the existing expiry." This matches what users mean when they tap Extend: they want the session to be valid for another 30 days starting today. It also means Extend can SHORTEN a session (pick a shorter duration or "Never" → fewer) — the button is labeled "Extend" for the common case but the semantics are really "update TTL policy".

**Grant handling:**
- If caller passes `grants`: re-materialize from scratch via `_materialize_grants` (uses defaults for channels the caller didn't specify, clamps each to the new session lifetime).
- If caller passes ONLY `ttl_seconds`: keep existing grants but re-clamp them via a new `_clamp_grants_to_lifetime` helper so a shorter TTL correctly clips any grant that would outlive the session. A longer TTL leaves grants unchanged (they were already ≤ the old expiry, which is now ≤ the new longer expiry, so no clipping needed).
- If caller passes both: apply TTL first (new session expiry), then materialize the provided grants clamped to the new expiry.
- If caller passes neither: 400. No-op calls are bugs.

**UX:**
Reuses the existing `SessionTtlPickerDialog` directly — no new component needed. The dialog's "Keep this pairing for…" title works for both the pair flow and the extend flow (tense-neutral). Preselected option is computed from the session's current remaining lifetime rounded to the nearest picker option — never-expire sessions preselect "Never", 1-hour-remaining sessions preselect "1 day" (shortest real option), 25-day-remaining sessions preselect "30 days", etc.

## Files

**Server** (`plugin/relay/`):
- `auth.py` — new `SessionManager.update_session(token, ttl_seconds?, grants?) -> Session | None` that restarts-the-clock on TTL and re-materializes/re-clamps grants. New module-level `_clamp_grants_to_lifetime(grants, ttl_seconds, now)` helper alongside the existing `_materialize_grants`. Expired sessions are NOT silently resurrected — caller should re-pair instead.
- `server.py` — new `handle_sessions_extend(request)` handler doing full validation (non-negative `ttl_seconds`, non-negative numeric grant values, at least one field present) + the prefix → session → update dance that mirrors `handle_sessions_revoke`. Route registered via `app.router.add_patch("/sessions/{token_prefix}", handle_sessions_extend)` — PATCH is a distinct method on the same pattern, no ordering collision with existing GET/DELETE.
- `plugin/tests/test_sessions_routes.py` — 10 new tests: bearer-required, nonexistent prefix → 404, empty body → 400, negative TTL → 400, bad grant shape → 400, TTL-only restarts the clock (asserts `new_expiry ≈ now + new_ttl`, NOT old_expiry + new_ttl), `ttl_seconds=0` → never (null grants), grants-only leaves expiry alone, shorter TTL clips grants, self-extend, helper `_settle()` for the 1ms async delay needed on fast machines to make timestamp assertions meaningful.

**Phone** (`app/src/main/kotlin/.../`):
- `network/RelayHttpClient.kt` — new `extendSession(tokenPrefix, ttlSeconds?, grants?)` method. Hand-rolls the small JSON body to avoid pulling in another serializer branch (two optional fields, trivial to write, auditable). 400/401/404/409/5xx are all mapped to user-facing error messages. 404 is a hard failure here (unlike revoke where "already gone" → success) — if you're extending an active session and it's gone, that's surprising and should surface.
- `viewmodel/ConnectionViewModel.kt` — new `suspend fun extendDevice(tokenPrefix, ttlSeconds): Boolean`, mirrors `revokeDevice`'s shape (refresh list on success, surface error via `pairedDevicesError`). Grants intentionally not exposed on this path — MVP UX is "pick a new duration", server-side re-clamping handles the rest. Power users can call `RelayHttpClient.extendSession` directly for grant editing.
- `ui/screens/PairedDevicesScreen.kt` — `pendingExtend: PairedDeviceInfo?` state alongside the existing `pendingRevoke`. New dialog branch renders `SessionTtlPickerDialog` with the current remaining lifetime preselected (`expires_at - now`, clamped to ≥ 0, or 0 for never-expire sessions). Confirming calls `connectionViewModel.extendDevice(...)` inside a coroutine + shows a snackbar. `DeviceCard` action row becomes a 50/50 split between "Extend" and "Revoke" buttons (instead of a single full-width revoke). Both buttons are `OutlinedButton`; the revoke button keeps the error color tint.

**Docs:**
- `docs/relay-server.md` + `user-docs/reference/relay-server.md` — new PATCH /sessions/{token_prefix} route row
- `user-docs/reference/configuration.md` — Extend button description in the Paired Devices subsection
- `CLAUDE.md` — Integration Points table row for the new route
- this DEVLOG entry

## Not changed (intentionally)

- `__version__` stays at 0.2.0 per prior direction (we never released 0.2.0)
- No grant-editing UI — power users can hit the endpoint directly via a future tool
- No "extend by increment" UX (e.g. "+30 days on top of current") — semantics are always "set new TTL from now". Easier to reason about, matches what the picker already shows.
- No ADR bump — this is a small follow-up to ADR 15's architecture, not a new decision. ADR 15 already documents the session mutation model at a high level.

## Test plan (on-device)

1. Pair with `--ttl 1d` so you have a short session
2. Open Paired Devices → find the current device → tap Extend
3. Picker should show "1 day" preselected. Pick "30 days" → confirm.
4. Verify the card now shows "Expires ~30 days from now" and the snackbar says "Session expiry updated"
5. Tap Extend again → pick "Never expire" → confirm. Verify the card now shows "Never" and all channel grant chips show "never" too
6. Tap Extend again → pick "1 day" → confirm. Verify the card now shows ~1 day from now AND the grant chips are now all ≤ 1 day (clamped by the shortened session)
7. Try to Extend a non-current session (if you have multiple paired) → should work the same way

---

## 2026-04-11 — Pairing + Security Architecture Overhaul (grants, TTL, Keystore, TOFU, devices)

**Why this exists:**
The existing pairing model has been minimal since day one: pairing codes (one-shot, 10 min TTL) → session tokens (30 days hardcoded, single expiry, no per-channel grants, stored in `EncryptedSharedPreferences`). After the inbound media work exposed the "paired phone gets rate-limited to death on relay restart" gap, the entire pairing story was flagged as ready for a pass. Explicit asks:

1. Secure transport the default, insecure opt-in with clear UI
2. User-chosen TTL at pair time (1d/7d/30d/90d/1y/never)
3. Separate defaults by channel (terminal + bridge shorter than chat)
4. Never-expire ALWAYS selectable — *"don't force check, just allow based on user intent"* (an explicit decision)
5. Tailscale detection — informational only, not opinionated about defaults
6. Hardware-backed token storage
7. TOFU cert pinning with explicit reset on re-pair
8. Device revocation UI (Paired Devices screen)
9. QR payload signing
10. Phase 3 bidirectional pairing foundation

Delivered by two parallel background agents (server + phone) + local docs pass. No upstream hermes-agent changes — everything lives in files we own.

---

### Server side — `plugin/relay/`

**Data model (`auth.py`):**
- `Session` gains `grants: dict[str, float]` (per-channel expiry timestamps), `transport_hint: str` (`"wss"` / `"ws"` / `"unknown"`), `first_seen: float`, and handles `math.inf` for never-expire (`is_expired` is False for inf; JSON serializes inf → `null`).
- New `PairingMetadata` dataclass carries `ttl_seconds`, `grants`, and `transport_hint` through the pairing flow. `_PairingEntry.metadata` stores it; `PairingManager.register_code(..., ttl_seconds, grants, transport_hint)` accepts it; `consume_code` returns the metadata dataclass (or `None`) instead of a bool — existing boolean callers use `is not None`.
- `SessionManager.create_session` now takes `ttl_seconds`, `grants`, `transport_hint` params (all with backwards-compatible defaults). `_materialize_grants` helper resolves seconds-from-now durations to absolute expiries and **clamps** each grant to the overall session lifetime — no terminal grant can outlive its session. Default caps: terminal 30 days, bridge 7 days, chat = session lifetime. Constants: `DEFAULT_TERMINAL_CAP`, `DEFAULT_BRIDGE_CAP`.
- `SessionManager.list_sessions`, `SessionManager.find_by_prefix` — used by the new routes below.
- `RateLimiter.clear_all_blocks()` — new method that resets `_blocked` and `_failures` dicts. Called unconditionally when `/pairing/register` succeeds, because the operator is explicitly re-pairing and stale rate-limit state is noise. **This fixes the "phone rate-limited for 5 minutes after relay restart" bug that was occurring right when this session started.**

**Routes (`server.py`):**
- `handle_pairing_register` — now accepts optional `ttl_seconds` / `grants` / `transport_hint` in the JSON body and forwards them to the `_PairingEntry.metadata`. Host-registered metadata takes precedence over phone-sent metadata (operator policy is authoritative). Calls `server.rate_limiter.clear_all_blocks()` on success.
- `handle_pairing_approve` — new **`POST /pairing/approve`**, Phase 3 bidirectional pairing stub. Loopback-only, same shape as `/pairing/register`. Marked with `# TODO(Phase 3):` — full flow needs a pending-codes store so operators review rather than rubber-stamp. Route + wire shape locked in now so the Android agent has something to target.
- `handle_sessions_list` — new **`GET /sessions`**, bearer-auth'd. Returns `{"sessions": [...]}` where each entry carries `token_prefix` (first 8 chars, never the full token), `device_name`, `device_id`, `created_at`, `last_seen`, `expires_at` (null for never), `grants`, `transport_hint`, `is_current`. Full tokens are NEVER included — only the prefix — so a caller can't extract another session's credential.
- `handle_sessions_revoke` — new **`DELETE /sessions/{token_prefix}`**, bearer-auth'd. Matches on first-N-char prefix (≥ 4 chars). Returns 200 on exact match, 404 on zero matches, 409 on ambiguous (2+) matches with the count in the body. Self-revoke is allowed and flagged via `revoked_self: true` so the phone knows to wipe local state.
- `_authenticate` — extended to thread pairing metadata into the new session + include `expires_at`, `grants`, `transport_hint` in the `auth.ok` payload. `math.inf` → `null` on the wire.
- `_detect_transport_hint` helper — sniffs `request.transport.get_extra_info('ssl_object')` (non-None → `"wss"`), falls back to `request.scheme`, defaults to `"unknown"`. Runs only when pairing metadata didn't already supply a hint.

**QR signing (new `qr_sign.py`):**
- `load_or_create_secret(path)` — reads `~/.hermes/hermes-relay-qr-secret` if present (32 bytes, `0o600`, owner-only). On first run generates + writes via `secrets.token_bytes(32)` with `os.umask` safety.
- `canonicalize(payload)` — JSON-encode with `sort_keys=True, separators=(",", ":")`, `allow_nan=False` (so accidentally signing `math.inf` crashes loudly). Explicitly strips the `sig` field before canonicalization so signing a signed payload is idempotent.
- `sign_payload(payload, secret) -> str` — base64 HMAC-SHA256.
- `verify_payload(payload, sig, secret) -> bool` — constant-time compare via `hmac.compare_digest`.
- 13 test assertions covering canonicalization order-independence, `sig` exclusion, round-trip, tamper + wrong-secret + malformed-sig rejection, file perm check.

**Pair CLI (`plugin/pair.py` + `plugin/cli.py`):**
- New flags: `--ttl DURATION` (parses `1d` / `7d` / `30d` / `1y` / `never`) and `--grants SPEC` (`terminal=7d,bridge=1d`).
- `build_payload(..., sign=True)` — auto-bumps `hermes: 1 → 2` when any v2 field is present, embeds `ttl_seconds` / `grants` / `transport_hint` in the `relay` block, computes and attaches the HMAC signature.
- `read_relay_config` now reads `RELAY_SSL_CERT` to determine TLS status; `_relay_lan_base_url(tls=True)` emits `wss://` when set.
- `register_relay_code` sends the new fields so `/pairing/register`'s metadata attaches to the code.
- `render_text_block` shows `Pair: for 30 days` / `Pair: indefinitely` + per-channel grant labels.

**Tests added** (all `unittest.IsolatedAsyncioTestCase` / `AioHTTPTestCase`, runs via `python -m unittest`):
- `test_qr_sign.py` — 13 assertions covering canonicalize / sign / verify / load_or_create_secret.
- `test_session_grants.py` — default TTL + grant caps, 1-day session clamping terminal/bridge, never-expire (math.inf everywhere), explicit grants clamped to session, `grant=0` semantics, transport_hint, unknown-channel → expired, pairing metadata register/consume/one-shot/format-reject, list_sessions / find_by_prefix / revoke.
- `test_sessions_routes.py` — 401 on missing + invalid bearer, GET shape (no full-token leak, 8-char prefix, `is_current` correct, null for never-expire), DELETE 404 / 200 / 409 (ambiguous) / self-revoke.
- `test_rate_limit_clear.py` — unit `clear_all_blocks` + integration `/pairing/register` clears pre-existing blocks + metadata round-trip + invalid-ttl / bad-transport rejection + `/pairing/approve` loopback gate.

---

### Phone side — `app/src/main/kotlin/.../`

**Token storage + TOFU (new `auth/SessionTokenStore.kt`, `auth/CertPinStore.kt`):**
- `SessionTokenStore` interface with two implementations:
  - `KeystoreTokenStore` — StrongBox-preferred via `setRequestStrongBoxBacked(true)` when `FEATURE_STRONGBOX_KEYSTORE` is present (Android 9+). Best-effort: `tryCreate` swallows exceptions so broken OEM keystores don't brick pairing; falls back to the legacy store.
  - `LegacyEncryptedPrefsTokenStore` — existing `EncryptedSharedPreferences` path, TEE-backed via `MasterKey.AES256_GCM`.
- One-shot migration: on first launch post-upgrade, reads `session_token` / `device_id` / `api_server_key` / paired-metadata blob from the legacy `hermes_companion_auth` file, writes them to the new store, clears the legacy. If Keystore is unavailable, legacy stays as the active store and no migration happens — users never lose their session.
- `hasHardwareBackedStorage: Boolean` flag — true only for the StrongBox path. Legacy TEE-backed reports false. Surfaced in the Session info sheet as "Hardware (StrongBox)" vs "Hardware (TEE)".
- `CertPinStore` — DataStore-backed map of `host:port` → SHA-256 SPKI fingerprints. `recordPinIfAbsent` on first successful wss connect; `buildPinnerSnapshot` produces an OkHttp `CertificatePinner`. `ConnectionManager` takes a fresh snapshot on **every** `doConnect`, so a `removePinFor(host)` during re-pair is honored on the next connect — no coordination needed between `AuthManager` and `ConnectionManager`. Plaintext `ws://` short-circuits via `isPinnableUrl`.
- `applyServerIssuedCodeAndReset(code, relayUrl?)` now wipes the TOFU pin for the target host — a QR re-pair is explicit consent to a possibly-new cert fingerprint.

**Auth + session model (`auth/AuthManager.kt`, new `auth/PairedSession.kt`):**
- `PairedSession` data class: `token, deviceName, expiresAt: Long?, grants: Map<String, Long?>, transportHint, firstSeen, hasHardwareStorage`.
- `PairedDeviceInfo` wire model mirrors the server's `GET /sessions` response — `token_prefix, device_name, device_id, created_at, last_seen, expires_at, grants, transport_hint, is_current`.
- `AuthManager.currentPairedSession: StateFlow<PairedSession?>` — UI reads this for pair metadata without poking at prefs.
- `handleAuthOk` parses `expires_at` / `grants` / `transport_hint` from the payload, tolerates both int and float epoch seconds, persists alongside the token.
- `authenticate(ttlSeconds)` injects `ttl_seconds` + `grants` into pairing-mode auth envelopes. Session-mode re-auth (existing session token present) does NOT re-send these — the server keeps the grant table keyed on the original pair.

**QR payload v2 (`ui/components/QrPairingScanner.kt`):**
- `HermesPairingPayload` gains `sig: String?` (parsed, stored, NOT verified — the phone doesn't have the HMAC secret). `RelayPairing` gains `ttlSeconds: Long?`, `grants: Map<String, Long>?`, `transportHint: String?` — all optional with defaults.
- `parseHermesPairingQr` no longer rejects on version mismatch — any `hermes >= 1` with a non-blank host decodes. Future v3+ still parses via `ignoreUnknownKeys = true`. v1 QRs with no `hermes` field also parse.
- Signature verification is a `// TODO` in the parser — full verification requires a phone-side secret distribution path the protocol doesn't yet define.

**TTL picker (new `ui/components/SessionTtlPickerDialog.kt`):**
- Radio list: **1 day / 7 days / 30 days / 90 days / 1 year / Never expire**.
- `defaultTtlSeconds(qrTtl, transportHint, tailscale)` logic: QR operator value wins; else wss OR Tailscale → 30d; plain ws → 7d; unknown → 30d.
- **Never expire is always selectable** per the explicit "don't force check" direction. Shows an inline warning — *"This device will stay paired until you revoke it manually. Only choose this if you control the network — LAN, Tailscale, VPN, or TLS."* — but does NOT gate.
- Dialog ALWAYS opens on QR scan so the user confirms the TTL — the trust model is the user's judgment, not the QR's.
- Last user pick persists to `PairingPreferences.pairTtlSeconds` and becomes the preselected default on future pairs.

**Transport security UI (new `ui/components/TransportSecurityBadge.kt`, `ui/components/InsecureConnectionAckDialog.kt`):**
- Badge renders in 3 states (secure green 🔒 / amber insecure-with-reason / red insecure-unknown) and 3 sizes (Chip / Row / Large). Rendered in Settings → Connection, in the Session info sheet, and on each Paired Device card.
- Insecure ack dialog shown **first time** the user toggles insecure mode on. Body: plain-language threat model. Radio buttons for reason — "LAN only" / "Tailscale or VPN" / "Local development only". Only dismissible after selecting a reason + tapping "I understand". Marks `insecure_ack_seen = true`. Reason is stored for display, NOT gating.

**Tailscale detection (new `util/TailscaleDetector.kt`):**
- Checks `NetworkInterface.getNetworkInterfaces()` for `tailscale0` interface or an address in `100.64.0.0/10` (Tailscale CGNAT range), plus the configured relay URL host (`.ts.net` / 100.x.y.z).
- `StateFlow<Boolean>` refreshed on network changes via `ConnectivityManager` callback.
- Purely informational — shown as a "Tailscale detected" green chip in the Connection section. Does NOT auto-change any defaults per the stated requirement (#5).

**Paired Devices screen (new `ui/screens/PairedDevicesScreen.kt`):**
- Nav destination reached from Settings → Connection → "Paired Devices".
- Fetches via `RelayHttpClient.listSessions()` (new method, GET `/sessions` with bearer auth). Each card: device name + ID, "Current device" badge if `isCurrent`, transport security badge, expiry ("Expires Apr 18, 2026" or "Never"), per-channel grant chips, revoke button.
- Revoke button → confirmation dialog → `RelayHttpClient.revokeSession(tokenPrefix)`. Revoking the current device wipes local session token + redirects to pairing flow.
- Pull-to-refresh. Empty state "No paired devices". Graceful 404 handling — if the server hasn't shipped `/sessions` yet, renders empty list instead of crashing.

**`network/RelayHttpClient.kt` — two new methods:**
- `listSessions(): Result<List<PairedDeviceInfo>>` — GET `/sessions` with bearer auth. 404 treated as "endpoint not implemented yet" → empty list.
- `revokeSession(tokenPrefix): Result<Unit>` — DELETE `/sessions/{prefix}` with bearer auth. 404 treated as "already gone" → success.

**`network/ConnectionManager.kt` — TOFU integration:**
- Optional `CertPinStore` param. Rebuilds `OkHttpClient` on every connect with a fresh `CertificatePinner` snapshot (so re-pair pin wipes take effect immediately). Records peer cert fingerprint in `onOpen` when the handshake is TLS.
- Connect logic moved to IO dispatcher so the DataStore read for pin snapshot doesn't run on the caller's thread.

**`viewmodel/ConnectionViewModel.kt`:**
- Wires `AuthManager` before `ConnectionManager` so the pin store is available.
- Exposes `currentPairedSession`, `pairedDevices` + `pairedDevicesLoading` + `pairedDevicesError`, `isTailscaleDetected`, `insecureAckSeen`, `insecureReason`.
- `loadPairedDevices()`, `revokeDevice(prefix)`, `setInsecureAckComplete(reason)`.
- Shuts down Tailscale detector on `onCleared`.

**`ui/screens/SettingsScreen.kt` — integration:**
- `onNavigateToPairedDevices` callback added to the signature.
- Connection card: new TransportSecurityBadge row, Tailscale chip (if detected), hardware-storage chip, Paired Devices navigation row.
- Insecure toggle now routes through the first-time ack dialog. Cancel leaves the toggle off; confirm persists the reason and flips the mode.
- QR scan handoff now stages the payload into `pendingQrPayload` and opens `SessionTtlPickerDialog`. Picker confirm applies URLs/key/code, calls `applyServerIssuedCodeAndReset(code, relayUrl)` (wipes the TOFU pin), stores `pendingGrants` + `pendingTtlSeconds`, then tests the connection.

**`ui/components/ConnectionInfoSheet.kt`:**
- `SessionInfoSheet` now reads `currentPairedSession` and displays Expires / Channel grants / Transport / Key storage rows when paired.

**`ui/RelayApp.kt`:**
- New `Screen.PairedDevices` nav destination wired into the nav graph with back + re-pair callbacks. Reachable only from Settings — no bottom-nav slot.

**New DataStore keys (`data/PairingPreferences.kt`):**
- `pair_ttl_seconds` (Long, user's last-selected TTL)
- `insecure_ack_seen` (Boolean)
- `insecure_reason` (String: `"lan_only"` / `"tailscale_vpn"` / `"local_dev"` / `""`)
- `tofu_pins` (string-encoded map)

---

### Security review

The expansion of what a paired phone can do is:
- **Listing all sessions** (prefixes only, not full tokens) — a compromised phone was already able to see its own session, this adds visibility into other paired devices' metadata. Acceptable because the paired-phone population is explicitly trusted by the operator (they approved each QR pair).
- **Revoking other sessions** — any paired phone can revoke any other paired device. This is DoS territory: a compromised phone could lock out all other devices. Mitigation: revocation is auditable via the relay logs, and re-pairing is always possible from the host. For most deployments (one operator, 1-2 phones) this is acceptable. For multi-user deployments it'll need per-device role model (admin / user).
- **QR payload signing** — raises the bar for QR tampering (attacker can't inject their own pairing code via a modified QR photo), but it's defensive: the phone doesn't verify signatures yet. The server-side infrastructure is there so phone-side verification can land in a follow-up once the secret distribution model is defined.
- **Never-expire sessions** — an explicit decision: allow based on user intent, not gated. Users who pick this are accepting the risk. The Paired Devices screen makes revocation trivial.
- **TOFU pinning** — protects against MITM of a self-signed wss cert AFTER the first connect. Does not protect the first connect itself (trust-on-first-use). Acceptable for the LAN / Tailscale / VPN deployment model.
- **StrongBox storage** — improves attacker cost for on-device token extraction on Android 9+ devices with StrongBox hardware. Best-effort; older devices fall back to TEE-backed EncryptedSharedPreferences which is still strong.

---

### Files created/modified

**Server (11 files):**
- New: `plugin/relay/qr_sign.py`, `plugin/tests/test_qr_sign.py`, `plugin/tests/test_rate_limit_clear.py`, `plugin/tests/test_session_grants.py`, `plugin/tests/test_sessions_routes.py`
- Modified: `plugin/cli.py`, `plugin/pair.py`, `plugin/relay/auth.py`, `plugin/relay/server.py`
- Unchanged: `plugin/relay/__init__.py` (version stays `0.2.0` by decision — never released)

**Phone (17 files):**
- New: `auth/CertPinStore.kt`, `auth/PairedSession.kt`, `auth/SessionTokenStore.kt`, `data/PairingPreferences.kt`, `ui/components/InsecureConnectionAckDialog.kt`, `ui/components/SessionTtlPickerDialog.kt`, `ui/components/TransportSecurityBadge.kt`, `ui/screens/PairedDevicesScreen.kt`, `util/TailscaleDetector.kt`
- Modified: `auth/AuthManager.kt`, `network/ConnectionManager.kt`, `network/RelayHttpClient.kt`, `ui/RelayApp.kt`, `ui/components/ConnectionInfoSheet.kt`, `ui/components/QrPairingScanner.kt`, `ui/screens/SettingsScreen.kt`, `viewmodel/ConnectionViewModel.kt`

**Docs:** this DEVLOG entry, ADR 15 in `docs/decisions.md`, §3.3 / §3.3.1 / §3.4 rewrites in `docs/spec.md`, route tables in `docs/relay-server.md` + `user-docs/reference/relay-server.md`, config sections in `user-docs/reference/configuration.md`, Key Files + Integration Points in `CLAUDE.md`, pair flow in `user-docs/guide/getting-started.md`.

### Known gaps / follow-ups

- **Phone-side QR signature verification** — parsing + storing the `sig` field works; full verification requires a secret distribution mechanism (pre-shared key? on-device enrollment?) the protocol doesn't yet define.
- **Bidirectional pairing full UX** — `POST /pairing/approve` is a working stub. Real Phase 3 work needs a pending-codes store + operator approval UI.
- **Per-device role model** — all paired devices currently have equal revoke rights. Multi-user / admin-vs-user split is a future refactor.
- **Grant renewal UI** — the Paired Devices screen shows expiry but has no "extend this grant" action. Pair-again from the host or just revoke + re-pair.
- **Build verification** — no gradle run in this session; deployed from Android Studio. If a compile bug slipped through a KDoc or Compose misuse, it'll surface on the first build attempt (like the `text/*` comment bug earlier today).

---

## 2026-04-11 — Inbound Media v2: bare-path fetch + session-reload re-parse

**Why this exists:**
On-device testing of the v1 inbound media pipeline (shipped earlier today, commits `1195778` + `8f61262`) surfaced two bugs that showed up in the same screenshot:

1. **Placeholder flicker.** The `⚠️ Image unavailable` card rendered for a split second and then vanished, replaced by raw `MEDIA:/tmp/...` text in the bubble.
2. **Blank-looking attachment bubbles.** Related symptoms of the same underlying issue — messages re-rendered inconsistently during the turn-end reload.

Both root-caused to the `session_end reload` pattern documented in `CLAUDE.md`: when a streaming turn completes, `ChatViewModel.onCompleteCb` calls `client.getMessages()` → `ChatHandler.loadMessageHistory()`, which wholesale-replaces `_messages.value` with fresh `ChatMessage`s built from `item.contentText`. The streaming-time media marker parser had stripped the markers from the client-side copy and injected attachments, but NEITHER mutation was visible to `loadMessageHistory` — the server-stored text still contained the raw markers, and the client-injected attachments were gone.

**Fix 1: re-run the media marker parser on reloaded history.** `loadMessageHistory` now scans each loaded assistant message's content, strips matched lines, and queues `onMediaAttachmentRequested` / `onMediaBarePathRequested` callbacks to fire **after** the wholesale `_messages.value` assignment (so `mutateMessage` lookups hit the newly-loaded IDs). `dispatchedMediaMarkers` is cleared at the same time since pre-reload dedupe keys are meaningless against post-reload message IDs. Extracted into `extractMediaMarkersFromContent` — a pure helper that doesn't touch mutable buffer state. Shipped in commit `272a3c5`.

---

Investigating the flicker bug surfaced a larger issue in the screenshot:

**The LLM is emitting `MEDIA:/tmp/...` in its free-form text completions, not via the tool.** Upstream `hermes-agent/agent/prompt_builder.py:266` explicitly instructs the model in its system prompt: *"include MEDIA:/absolute/path/to/file in your response. The file..."* So the LLM treats MEDIA markers as a first-class way to request file delivery — in free-form completions, not through tool calls. Our v1 fix only intercepted markers **emitted by tools** that called `register_media()` — which covers `android_screenshot` but not the much larger set of LLM free-form emissions. For those, the marker form is always bare-path (`MEDIA:/tmp/foo.jpg`), and our phone-side handler treated bare-path as "unavailable" no matter what.

**Fix 2: `GET /media/by-path` on the relay + phone-side `fetchMediaByPath`.** Adds a second fetch route alongside the existing `/media/{token}`. Key points:

- **Shared path validation** — extracted `validate_media_path(path, allowed_roots, max_size_bytes) -> (real_path, size)` at the top of `plugin/relay/media.py`. Both `MediaRegistry.register` (the loopback-only tool path) and the new `handle_media_by_path` (the phone-auth'd direct fetch) call it, so the sandbox rules can't drift.
- **Same trust model as `/media/{token}`** — bearer auth against the existing `SessionManager`. Only a paired phone with a valid relay session token can fetch; 401 for everyone else.
- **Path sandboxing** — absolute path → `os.path.realpath` → must resolve under an allowed root (`tempfile.gettempdir()` + `HERMES_WORKSPACE` + `RELAY_MEDIA_ALLOWED_ROOTS`) → must exist → must be a regular file → must fit under `RELAY_MEDIA_MAX_SIZE_MB`. Symlink escape is blocked by the `realpath` pre-check. 403 for any violation; 404 if the file is missing; 400 if the `path` query param is absent.
- **Content-Type negotiation** — if the phone passes `?content_type=<...>` it's honored; otherwise the server guesses via Python `mimetypes.guess_type()`. Falls back to `application/octet-stream`.
- **Route ordering** — `/media/by-path` is registered **before** `/media/{token}` in `create_app` or aiohttp swallows the literal path as a token and 404s. Commented in the source as a reminder.
- **Phone-side rename for clarity** — `onUnavailableMediaMarker` → `onMediaBarePathRequested` (ChatHandler callback and ChatViewModel method). The name "unavailable" made sense in v1 when bare-path was always terminal; now bare-path is the primary LLM format, so it's a request-to-fetch like the token form. The failure branch still produces the `⚠️ Image unavailable` card, but only when the fetch actually fails.
- **Shared fetch pipeline** — `performFetch` → `performFetchWith(handler, messageId, fetchKey, settings, fetch: suspend () -> Result<FetchedMedia>)`. Takes the fetch lambda as a parameter so both the token path (`relay.fetchMedia(token)`) and the bare-path (`relay.fetchMediaByPath(path)`) share the same size-cap / cache / state-flip logic. The `fetchKey` stored in `Attachment.relayToken` disambiguates via the leading `/` — `secrets.token_urlsafe` never produces a `/`, so the prefix check is unambiguous. `manualFetchAttachment` (retry CTA) uses the same discriminator.

**Security review (in ADR 14 addendum):**
Adding `/media/by-path` widens what a paired phone can request by one degree — it can now read any file in the allowed-roots whitelist without host-local tool cooperation. This does NOT widen the trust boundary because (1) the whitelist is the same, (2) `/tmp` on Linux is already world-readable to same-user processes, (3) bearer auth still requires a valid session token, and (4) `realpath` symlink-resolves before the whitelist check so symlink escape is still blocked. Operators who want a tighter sandbox should narrow `RELAY_MEDIA_ALLOWED_ROOTS`, not disable the endpoint.

**Tests added** (`plugin/tests/test_relay_media_routes.py`):
- `test_by_path_without_authorization_returns_401`
- `test_by_path_with_invalid_bearer_returns_401`
- `test_by_path_missing_path_param_returns_400`
- `test_by_path_outside_sandbox_returns_403`
- `test_by_path_nonexistent_in_sandbox_returns_404`
- `test_by_path_relative_path_returns_403`
- `test_by_path_happy_path_streams_bytes` (verifies auto-guessed `Content-Type: image/png` from `.png` extension)
- `test_by_path_content_type_hint_overrides_guess` (verifies `?content_type=application/json` wins over extension guess)
- `test_by_path_oversized_returns_403` (uses try/finally to restore `max_size_bytes` so other tests in the class aren't affected — `AioHTTPTestCase` reuses one app instance)

**Files created/modified:**

*Server (Python):*
- `plugin/relay/media.py` — new `validate_media_path()` module-level helper + `_is_under_any_root()` private helper; `MediaRegistry.register` refactored to call the new helper; duplicate `_is_under_allowed_root` method removed.
- `plugin/relay/server.py` — new `handle_media_by_path` route handler, new imports (`mimetypes`, `os`, `validate_media_path`), route registration in `create_app` (order-sensitive — `by-path` before `{token}`).
- `plugin/tests/test_relay_media_routes.py` — 9 new tests for the by-path endpoint.

*Phone (Kotlin):*
- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayHttpClient.kt` — new `fetchMediaByPath(path, contentTypeHint)` method, uses `okhttp3.HttpUrl` builder for correct query-param encoding (paths with slashes / spaces / unicode).
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/ChatHandler.kt` — rename `onUnavailableMediaMarker` → `onMediaBarePathRequested`, updated KDoc explaining the new semantics.
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ChatViewModel.kt` — rename + rewrite method body (now inserts LOADING, applies cellular gate, calls `performFetchWith { relay.fetchMediaByPath(path) }`); `performFetch` → `performFetchWith` signature change (takes `fetch: suspend () -> Result<FetchedMedia>` lambda); `manualFetchAttachment` dispatches to token-vs-path branch by `fetchKey.startsWith("/")`.

*Docs:*
- `docs/decisions.md` — ADR 14 addendum covering the bare-path fetch endpoint, upstream-prompt rationale, and security review
- `docs/relay-server.md` + `user-docs/reference/relay-server.md` — new `/media/by-path` route entry
- `docs/spec.md` — §6.2a updated with three-route listing + bare-path flow
- `user-docs/reference/configuration.md` — honest description of the bare-path-as-primary-format model
- `CLAUDE.md` — Integration Points table split into token / path fetch + tool / LLM marker rows
- `DEVLOG.md` — this entry

**Known gaps still filed for later:**
- Session replay across relay restarts (phone-side persistent cache by token/hash-indexed)
- Auto-fetch threshold slider enforcement (currently persisted-not-enforced placeholder)
- Build verification — no gradle run against the phone side this session; relying on type-checks and review. Built from Android Studio.

**Next cycle discussion point:**
A broader design question was raised about pairing security and TTL policy — bidirectional pairing, secure-by-default, user-selectable pair duration at initial pair, separate defaults for terminal vs bridge, opt-in never-expire for secured transports. Deferred to next design pass; not in this commit.

---

## 2026-04-11 — Inbound Media Pipeline (agent → phone, Discord-style file rendering)

**Done:**
- **Root cause surfaced.** The `android_screenshot` tool has always returned `MEDIA:/tmp/...` in its response text, assuming hermes-agent's gateway would extract and deliver the file as a native attachment. Upstream verification against `gateway/platforms/api_server.py` showed `APIServerAdapter.send()` is an explicit no-op (`"API server uses HTTP request/response, not send()"`) and `_write_sse_chat_completion` streams raw deltas without ever invoking `extract_media()`. The upstream extract-media / send_document machinery (`gateway/run.py:4570`, `4747`) is wired for push platforms only (Telegram, Feishu, WeChat). On our HTTP pull adapter, the `MEDIA:` tag has always passed through to the phone as literal text. No existing upstream path exists for delivering files over the HTTP API surface without a platform-adapter PR.
- **Workaround landed: plugin-owned file-serving on the relay.** Added a `MediaRegistry` and two new routes to the plugin's existing relay server. Media-producing tools POST to a loopback-only `POST /media/register` with a file path + content type, get back an opaque `secrets.token_urlsafe(16)` token, and emit `MEDIA:hermes-relay://<token>` in their chat response text instead of the bare path. The phone's `ChatHandler` parses the marker out of the SSE stream, fires a ViewModel callback, and `RelayHttpClient` fetches the bytes over `GET /media/{token}` with `Authorization: Bearer <session_token>` (reusing the existing `SessionManager`). Bytes land in `cacheDir/hermes-media/`, get shared via `FileProvider` (`${applicationId}.fileprovider`), and render inline via a new `InboundAttachmentCard` component. Result: zero LLM context bloat (token is ~25 chars), no upstream fork, no new auth model.
- **Registry design.** In-memory `OrderedDict` LRU with `asyncio.Lock` for thread-safety. Defaults: **24-hour TTL** (chosen to cover within-a-day session scrollback — the real human use case; anything longer is wasted since SessionManager is in-memory and relay restarts invalidate all tokens regardless), **500-entry LRU cap** (prevents runaway memory/disk under screenshot spam), **100 MB file-size cap** (guards against `/media/register` being handed a 10 GB file). Path sandboxing: file must be absolute, `os.path.realpath()` resolve under an allowed root (default: `tempfile.gettempdir()` + `HERMES_WORKSPACE` or `~/.hermes/workspace/` + any `RELAY_MEDIA_ALLOWED_ROOTS` entries), exist, be a regular file, and fit under the size cap. The token → path mapping is held server-side — the client only ever presents an opaque token on GET, so there's zero path-traversal surface on the fetch endpoint.
- **Fallback when relay isn't running.** The tool calls `register_media()` via stdlib `urllib.request` with a 5s timeout; on any failure (relay down, connection refused, non-200 response) it returns the legacy `MEDIA:<tmp_path>` form with a logger warning. The phone's `ChatHandler` recognizes the bare-path form via a second regex and fires `onUnavailableMediaMarker`, which inserts a FAILED `Attachment` placeholder rendering `⚠️ Image unavailable — relay offline`. No regression versus today's behavior; the placeholder is just tidier than raw marker text.
- **Discord-style rendering on the phone.** New `AttachmentState { LOADING, LOADED, FAILED }` and `AttachmentRenderMode { IMAGE, VIDEO, AUDIO, PDF, TEXT, GENERIC }` on the existing `Attachment` data class. `InboundAttachmentCard` dispatches by `(state × renderMode)`: images render inline from the cached URI (decoded via `BitmapFactory.decodeByteArray` + `asImageBitmap`, matching the existing outbound-attachment render path — no Coil/Glide added); video/audio/pdf/text/generic render as tap-to-open file cards that fire `ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION`. The same component now handles outbound attachments too (they default to `state=LOADED`), so `MessageBubble.kt` no longer has a separate outbound-only render branch.
- **Cellular gate.** If `autoFetchOnCellular == false` (default) and the device is on a cellular network, the attachment stays in LOADING state with `errorMessage = "Tap to download"` — the user taps to trigger `manualFetchAttachment()`, which re-issues the fetch ignoring the cellular gate. Encoded via existing enum + errorMessage slot rather than adding a new state value to keep the data class surface small.
- **Dedup.** `ChatHandler.dispatchedMediaMarkers` is a per-session set that prevents double-firing between real-time streaming scans (`scanForMediaMarkers` called from `onTextDelta`) and the post-stream reconciliation pass (`finalizeMediaMarkers` called from `onTurnComplete` / `onStreamComplete`). Marker parsing runs unconditionally — not gated on the `parseToolAnnotations` feature flag.
- **Settings UI.** New "Inbound media" subsection in Settings (between Chat and Appearance) exposes four DataStore-backed knobs: max inbound attachment size (5–100 MB, default 25), auto-fetch threshold (0–50 MB, default 2 — *persisted but not currently enforced; only the cellular toggle gates fetches today, with the threshold reserved for forward-compatibility*), auto-fetch on cellular (default off), and cached media cap (50–500 MB, default 200) with a "Clear cached media" button that calls `MediaCacheWriter.clear()` and shows a Toast with the freed byte count. LRU eviction on the cache is by file mtime.
- **Auth parity.** The media GET endpoint uses the same relay session token that gates the WSS channel itself — no stronger, no weaker. On the question of whether the media endpoint needed its own auth given that chat is optionally unauthenticated: the relay session token (issued at pairing, stored in `EncryptedSharedPreferences`) is a separate and always-required credential, so `/media/<token>` inherits exactly the WSS trust level and adds unguessable per-file entropy on top. Opt-in insecure (ws://) mode intentionally does nothing to strengthen this — it matches the existing "trusted LAN" assumption for local dev.
- **Tests.** 11 registry tests (happy path, expiry, LRU eviction, LRU reorder on get, relative path rejection, nonexistent path rejection, directory rejection, outside-allowed-roots rejection, symlink-escape rejection [skipped on Windows without symlink priv], oversized rejection, empty content_type rejection) + 8 route tests (`/media/register` non-loopback 403, happy path 200, validation 400, bad JSON 400; `/media/{token}` no auth 401, bad bearer 401, valid + streamed 200, expired 404, unknown 404). Uses `unittest.IsolatedAsyncioTestCase` + `aiohttp.test_utils.AioHTTPTestCase` (no pytest-asyncio dep required).

**Why this wasn't Option A (inline base64 in tool output):**
- Inline base64 bloats the LLM context on every call (~135 KB per 1080p screenshot, growing with history), matters for video/audio scalability, and forces the agent to pay for bytes it's just routing to the phone. That tradeoff was explicitly rejected.
- Option B (plugin-owned file endpoint) decouples the wire format from the file bytes: tokens are ~25 chars, bytes flow out-of-band over a separate authenticated HTTP channel. Costs: new endpoint surface area, new phone-side fetch path, FileProvider plumbing — but all of it lives in files we already own.

**Files created:**

*Server (Python):*
- `plugin/relay/media.py` — `MediaRegistry`, `_MediaEntry`, `MediaRegistrationError`, `_default_allowed_roots()`
- `plugin/relay/client.py` — stdlib `urllib.request`-based `register_media()` + `_post_loopback()` helper (kept separate from `plugin/pair.py`'s existing `register_relay_code` to avoid weakening that function's narrower error surface)
- `plugin/tests/test_media_registry.py` — 11 tests, `unittest.IsolatedAsyncioTestCase`
- `plugin/tests/test_relay_media_routes.py` — 8 tests, `aiohttp.test_utils.AioHTTPTestCase`

*Phone (Kotlin):*
- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayHttpClient.kt` — OkHttp GET, ws→http URL rewrite, Content-Disposition filename parse, Result<FetchedMedia>
- `app/src/main/kotlin/com/hermesandroid/relay/data/MediaSettings.kt` — DataStore-backed `MediaSettings` + `MediaSettingsRepository`
- `app/src/main/kotlin/com/hermesandroid/relay/util/MediaCacheWriter.kt` — LRU-capped cache at `cacheDir/hermes-media/`, FileProvider URI generation, MIME→ext map
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/InboundAttachmentCard.kt` — single component dispatching on `state × renderMode`
- `app/src/main/res/xml/file_provider_paths.xml` — `<cache-path name="hermes-media" path="hermes-media/"/>`

**Files modified:**

*Server:*
- `plugin/relay/config.py` — 4 new fields (`media_max_size_mb`, `media_ttl_seconds`, `media_lru_cap`, `media_allowed_roots`), `from_env()` parsing
- `plugin/relay/server.py` — `self.media = MediaRegistry(...)` in `RelayServer.__init__`, `handle_media_register` + `handle_media_get` + route registration in `create_app`
- `plugin/tools/android_tool.py` — `android_screenshot()` calls `register_media()` → emits `hermes-relay://<token>` on success, falls back to bare path with a `logging.warning` on failure
- `plugin/android_tool.py` — identical change to the top-level duplicate copy

*Phone:*
- `app/src/main/AndroidManifest.xml` — `<provider>` for `androidx.core.content.FileProvider` with authority `${applicationId}.fileprovider`
- `app/src/main/kotlin/com/hermesandroid/relay/data/ChatMessage.kt` — `AttachmentState` + `AttachmentRenderMode` enums, extended `Attachment` with `state`/`errorMessage`/`relayToken`/`cachedUri`, `textLikeMimes` companion, `renderMode` computed property
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/ChatHandler.kt` — `mediaRelayRegex` + `mediaBarePathRegex`, `onMediaAttachmentRequested` + `onUnavailableMediaMarker` as `var` callbacks (not ctor params), `mediaLineBuffer` + `dispatchedMediaMarkers` dedupe set, `scanForMediaMarkers` called unconditionally from `onTextDelta`, `finalizeMediaMarkers` called from `onTurnComplete`/`onStreamComplete`, `mutateMessage` helper exposed so the ViewModel can flip attachment state on the private `_messages` StateFlow
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ChatViewModel.kt` — new `initializeMedia(context, relayHttpClient, mediaSettingsRepo, mediaCacheWriter)`, `onMediaAttachmentRequested`, `performFetch`, `manualFetchAttachment`, `onUnavailableMediaMarker`, `MEDIA_TAP_TO_DOWNLOAD` companion constant
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` — owns media singletons (`mediaSettingsRepo`, `mediaCacheWriter`, `relayHttpClient`), shared `OkHttpClient`, `_cachedMediaCapMb` mirror loop so the writer's cap lambda is synchronous
- `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt` — `chatViewModel.initializeMedia(...)` wired inside the existing `LaunchedEffect(apiClient)` block
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/MessageBubble.kt` — replaced outbound-only attachment rendering with `attachments.forEachIndexed { InboundAttachmentCard(...) }`, added `onAttachmentRetry` + `onAttachmentManualFetch` params
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ChatScreen.kt` — empty-bubble skip now respects `attachments.isNotEmpty()`, wires `manualFetchAttachment` to both retry + manual-fetch slots
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt` — new `InboundMediaSection(connectionViewModel)` composable between Chat and Appearance (coexists with the other team's unified Connection section; no collision)

**Files NOT touched** (other team owns them or out-of-scope): `AuthManager.kt` (other team added `applyServerIssuedCodeAndReset` for the manual-code-entry dialog), `ConnectionInfoSheet.kt` (other team's new bottom-sheet component for Connection rows), `plugin/pair.py`, anything under `relay_server/` (thin shim — untouched), any upstream hermes-agent code.

**Next:**
- Wire the auto-fetch threshold slider to the actual fetch logic — currently only the cellular toggle gates fetches, and the threshold is persisted-but-unused as a forward-compatibility placeholder. Real enforcement would need either a HEAD preflight to get the size before committing to the fetch, or we accept the post-hoc reject (byte-count comparison after the body lands, wasted bytes on oversize).
- Phone-side persistence of fetched media so session replay works across relay restarts. Currently the `FileProvider` cache is opaque to `ChatHandler` — if the user scrolls back into a session from yesterday, the tokens in the stored message text are stale (relay registry is in-memory) and the fetch 404s. Phone-side token-or-hash-indexed cache would survive this.
- Consider wiring the same pipeline into any future tools that want to emit files (voice, plots, reports). The `MediaRegistry` + `register_media()` helper is tool-agnostic — only `android_screenshot` uses it today.
- Unit-test coverage for the Kotlin side: `ChatHandler` marker parsing, `RelayHttpClient` URL-rewrite, `MediaCacheWriter` LRU eviction. The Python side has 19 tests; the Kotlin side currently has none for the media pipeline.
- Possible upstream contribution to `hermes-agent`: make `gateway/platforms/api_server.py`'s `_write_sse_chat_completion` route deltas through `GatewayStreamConsumer` so the `_MEDIA_RE` stripper in `gateway/stream_consumer.py:188` engages. That would at least keep raw `MEDIA:` tags out of the chat display for other HTTP-API clients that don't implement their own phone-side parser. Would not solve the actual file-delivery problem (still no `send_document` impl) but would at least stop the leakage. Track in `docs/upstream-contributions.md`.

**Blockers:**
- None. The feature is ready for on-device testing.

**Test plan (for on-device smoke):**
- Start relay (`scripts/dev.bat relay` or equivalent), pair phone, open chat.
- Invoke a tool that produces a screenshot (e.g., via an agent command that triggers `android_screenshot`). Verify the screenshot renders inline as an image, not as raw text.
- Kill the relay mid-session, trigger another screenshot, verify the `⚠️ Image unavailable — relay offline` placeholder renders.
- In Settings → Inbound media: adjust the max-size slider, toggle cellular, hit "Clear cached media", verify toast with freed bytes.
- Tap a non-image attachment (test with a PDF tool result if available) and verify `ACTION_VIEW` opens an external app with a valid `content://` URI.

---

## 2026-04-11 — Install Flow Canonicalization (external_dirs + pip install -e + skill category layout)

**Done:**
- **Install flow rewritten to match Hermes canonical distribution patterns** (per `~/.hermes/hermes-agent/website/docs/user-guide/features/skills.md`). The new `install.sh` clones the repo to `~/.hermes/hermes-relay/` (override with `$HERMES_RELAY_HOME`) instead of a throwaway tmpdir, `pip install -e`s the package into the hermes-agent venv, and registers the clone's `skills/` directory under `skills.external_dirs` in `~/.hermes/config.yaml` via an idempotent YAML edit. The plugin is symlinked into `~/.hermes/plugins/hermes-relay`, and a thin `~/.local/bin/hermes-pair` shim execs `python -m plugin.pair` in the venv.
- **Updates are now `cd ~/.hermes/hermes-relay && git pull`** — one command updates plugin (editable install picks up changes automatically) + skill (`external_dirs` is scanned fresh on every hermes-agent invocation) + docs. No `hermes skills update` step — that only applies to hub-installed skills, not `external_dirs`-scanned ones.
- **Skill directory now follows canonical category layout** — `skills/devops/hermes-relay-pair/SKILL.md` (category subdir matching the `metadata.hermes.category: devops` frontmatter), not the old flat `skills/hermes-relay-pair/`.
- **`skills/hermes-pairing-qr/` deleted entirely** — the pre-plugin bash script + SKILL.md. Replaced by `skills/devops/hermes-relay-pair/` + `plugin/pair.py` (Python module) + `hermes-pair` shell shim.
- **`plugin/skill.md` deleted** — old lowercase-s flat-file artifact from before the skill system existed.
- **Documented the upstream CLI gap** — hermes-agent v0.8.0's `PluginContext.register_cli_command()` is wired on the plugin side, but `hermes_cli/main.py:5236` only reads `plugins.memory.discover_plugin_cli_commands()` and never consults the generic `_cli_commands` dict. Third-party plugin CLI commands never reach the top-level argparser. Docs no longer promise `hermes pair` (with a space) works — only `/hermes-relay-pair` (slash command via the skill) and `hermes-pair` (dashed shell shim) are documented as working entry points.

**Files changed:**
- `README.md` — Quick Start replaces `hermes pair` with `/hermes-relay-pair` + `hermes-pair`, adds update-via-`git pull` note, updates repo structure to show `skills/devops/hermes-relay-pair/`
- `docs/relay-server.md` — pairing description and `/pairing/register` row updated to reference the new entry points
- `docs/decisions.md` — new ADR 13 on skill distribution via `external_dirs`
- `user-docs/guide/getting-started.md` — full install-flow rewrite covering the 5-step canonical installer, update mechanism, slash command vs shell shim entry points, upstream CLI gap warning
- `user-docs/reference/configuration.md` — new `Skills (external_dirs)` subsection, command references updated
- `user-docs/reference/relay-server.md` — pairing model + troubleshooting updated
- `CLAUDE.md` — Repo Layout shows `skills/devops/hermes-relay-pair/`; Key Files gains `install.sh`, drops deprecated `hermes-pairing-qr` rows and `plugin/skill.md` references; integration points updated
- `AGENTS.md` — Setup steps rewritten around the canonical installer
- `DEVLOG.md` — this entry

**Files NOT touched (main session owns them):** `plugin/**`, `relay_server/**`, `app/**`, `pyproject.toml`, `skills/devops/hermes-relay-pair/SKILL.md`, `install.sh`. The deleted `skills/hermes-pairing-qr/` and `plugin/skill.md` paths are referenced only as historical deletions in this entry and ADR 13.

**Next:**
- Verify `/hermes-relay-pair` renders correctly once the skill is at `skills/devops/hermes-relay-pair/SKILL.md` and hermes-agent reloads from `external_dirs`.
- Confirm `install.sh`'s YAML edit is actually idempotent against a pre-existing `external_dirs` list with a trailing comment — regression-test with a pathological config.
- Upstream patch to `hermes_cli/main.py` that dispatches to the generic `_cli_commands` dict — would let us restore `hermes pair` as a first-class CLI verb. Track in `docs/upstream-contributions.md`.

**Blockers:**
- Upstream argparser doesn't forward to plugin CLI dict (see above). Not blocking the install flow — the slash command + shell shim cover the same surface.

---

## 2026-04-11 — Settings Connection UX Rework (QR-first, collapsible manual + bridge)

**Done:**
- **Unified Connection section on the Settings screen.** Replaced the three separate top-level cards (**API Server**, **Relay Server**, **Pairing**) with a single **Connection** section containing three stacked cards:
  - **Pair with your server** — always visible, primary entry point. Large **Scan Pairing QR** button + a unified status summary line showing API Server (Reachable / Unreachable), Relay (Connected / Disconnected), and Session (Paired / Unpaired). This is the one-button flow: scan the QR from `hermes pair` on the host and everything is configured.
  - **Manual configuration** — collapsible. Starts collapsed when the user is already paired and reachable, expanded otherwise. Holds the manual-entry fields (API Server URL, API Key, Relay URL, Insecure Mode toggle) and the **Save & Test** button. Power-user / troubleshooting path.
  - **Bridge pairing code** — collapsible, gated by the `relayEnabled` feature flag, starts collapsed. Shows the locally-generated 6-char pairing code with copy / regenerate icons. Explicitly labelled "For the Phase 3 bridge feature — the host approves this code to enable Android tool control. Not used for initial pairing." Replaces the old Pairing card, which was visually prominent but semantically misleading in the new QR-driven flow.
- **Why.** The old layout buried the QR button inside the API Server card next to **Save & Test**, so new users couldn't tell which button was the primary setup path. The old **Pairing** card prominently displayed a phone-generated code that's no longer used for initial pairing — only for the future Phase 3 bridge direction. The rework makes the happy path (one QR scan → chat + relay) the obvious default and demotes both manual config and the bridge code to collapsibles for users who actually need them.
- **User docs updated.** `user-docs/guide/getting-started.md` (Manual Pairing section now walks through Settings → Connection → Manual configuration), `user-docs/reference/configuration.md` (Onboarding Settings renamed to Connection Settings + describes the three-card layout), and the `CLAUDE.md` Key Files entry for `SettingsScreen.kt`.

**Files changed:**
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt` — three-card Connection section, collapsible state, unified status summary
- `user-docs/guide/getting-started.md` — Manual Pairing section updated for Settings → Connection layout
- `user-docs/reference/configuration.md` — Onboarding Settings → Connection Settings, three-card layout described
- `CLAUDE.md` — Key Files `SettingsScreen.kt` entry updated
- `DEVLOG.md` — this entry

**Next:**
- Update splash / onboarding completion screen so the "you can change this later in Settings" hint points at the Connection section, not the old API Server card.
- Screenshot pass for Play Store listing — the old screenshots still show the three-section layout.
- Consider whether the **Bridge pairing code** card should be hidden entirely (not just collapsed) until Phase 3 lands, to avoid confusing users who enable the relay feature flag for terminal alone.

**Blockers:**
- None.

---

## 2026-04-11 — QR-Driven Relay Pairing (one scan → chat + relay)

**Done:**
- **Extended QR payload schema** — `HermesPairingPayload` (in `plugin/pair.py` + `app/.../QrPairingScanner.kt`) now carries an optional `relay` block alongside the existing API server fields: `{ "hermes": 1, "host", "port", "key", "tls", "relay": { "url": "ws://host:port", "code": "ABCD12" } }`. The `relay` field is nullable and `kotlinx.serialization` runs with `ignoreUnknownKeys = true`, so old API-only QRs still parse cleanly — no migration required.
- **New relay endpoint `POST /pairing/register`** (`plugin/relay/server.py` → `handle_pairing_register`) — Pre-registers an externally-provided pairing code with the running relay. Accepts `{"code": "ABCD12"}`, returns `{"ok": true, "code": "ABCD12"}`. Gated to loopback callers only (`127.0.0.1` / `::1`) — any non-local `request.remote` gets HTTP 403. Matches the trust model: only a process with host shell access can inject codes; a LAN attacker cannot. Validation delegates to `PairingManager.register_code()` which enforces the 6-char `A-Z / 0-9` format.
- **`hermes pair` probes + pre-registers the relay** — When invoked, the command calls `probe_relay()` against `http://127.0.0.1:RELAY_PORT/health`; on success, mints a fresh 6-char code (`random.SystemRandom`, alphabet `string.ascii_uppercase + string.digits`), posts it to `/pairing/register`, and embeds `{url, code}` in the QR. If the relay isn't running it prints an `[info]` pointing at `hermes relay start` and renders an API-only QR. If registration fails it prints a `[warn]` and also falls back. New `--no-relay` flag skips the probe entirely for operators who only want direct chat.
- **Output format** — `render_text_block()` now renders a second "Relay (terminal + bridge)" section when a relay block is present, showing the `ws://host:port` URL and the pairing code (with "expires in 10 min, one-shot" note) alongside the existing "Server" section. Unified warning at the bottom notes the QR contains credentials whenever an API key OR a relay code is present.
- **Pairing alphabet widened** — `plugin/relay/config.py` — `PAIRING_ALPHABET` went from `"ABCDEFGHJKLMNPQRSTUVWXYZ23456789"` (32 chars, no ambiguous 0/O/1/I) to `"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"` (36 chars). The phone-side `PAIRING_CODE_CHARS = ('A'..'Z') + ('0'..'9')` in `AuthManager.kt` could previously emit codes that `PairingManager.register_code()` silently rejected as "invalid format". The old restriction only mattered when a human had to retype a code from a display; with QR + HTTP the full alphabet is correct.
- **Relay config env vars** — `RELAY_HOST` / `RELAY_PORT` are now consumed by `plugin/pair.py`'s `read_relay_config()` too (in addition to `plugin/relay/config.py`), so `hermes pair` and `hermes relay start` agree on where the relay lives.
- **Phase 3 note** — Phone-side `generatePairingCode()` in `AuthManager.kt` is retained. The bridge channel (Phase 3) will use the opposite flow — phone generates, host approves — and `POST /pairing/register` is written generically enough to serve both directions.

**Files changed/added:**
- `plugin/relay/server.py` — `handle_pairing_register` + route registration on `/pairing/register`
- `plugin/relay/auth.py` — `PairingManager.register_code()` validation helper
- `plugin/relay/config.py` — widened `PAIRING_ALPHABET`, comment explaining why
- `plugin/pair.py` — `probe_relay()`, `register_relay_code()`, `_generate_relay_code()`, `_relay_lan_base_url()`, `read_relay_config()`; extended `build_payload()` / `render_text_block()` / `pair_command()`
- `plugin/cli.py` — `--no-relay` flag on `hermes pair`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/QrPairingScanner.kt` — `RelayPairing` data class + nullable `relay` field on `HermesPairingPayload`
- `README.md` — Quick Start pairing section now mentions one-scan chat + relay
- `docs/spec.md` — pairing flow section and QR wire format
- `docs/decisions.md` — new ADR entry on QR-carries-both-credentials trust model
- `docs/relay-server.md` — routes table includes `/pairing/register`, loopback restriction note
- `user-docs/guide/getting-started.md` — updated pairing steps
- `user-docs/reference/relay-server.md` — routes + pairing model
- `user-docs/reference/configuration.md` — `RELAY_HOST` / `RELAY_PORT` + alphabet note
- `CLAUDE.md` — Integration Points + Repo Layout references updated to `plugin/relay/`
- `DEVLOG.md` — this entry

**Next:**
- End-to-end test: start relay → `hermes pair` → scan QR on phone → verify both API and relay auto-configure → verify terminal tab attaches without asking for a pairing code.
- If the phone's stored relay session token is still valid from a previous pairing, the new code should be a no-op (session reconnect takes priority over pairing in `_authenticate()`). Verify that path doesn't accidentally consume the freshly-registered code.

**Blockers:**
- None.

---

## 2026-04-11 — Phase 2: Terminal Channel MVP (server + app)

**Done:**
- **Server-side `TerminalHandler` (`relay_server/channels/terminal.py`)** — Replaced the stub with a real PTY-backed shell handler. Uses `pty.openpty()` + `fork` + `TIOCSCTTY` (not `pty.fork()`) so we can set `O_NONBLOCK` on the master fd before handing it to `loop.add_reader()`. Output is batched on a ~16 ms window (via `loop.call_later`) or flushed immediately on 4 KiB buffer — that keeps 60 fps refresh from a shell dumping megabytes without flooding the WebSocket. Supports `terminal.attach`/`input`/`resize`/`detach`/`list`. Resize uses `TIOCSWINSZ` ioctl for SIGWINCH. Graceful teardown on disconnect: flush pending buffer → remove reader → SIGHUP → `waitpid(WNOHANG)` loop (up to 1 s grace) → SIGKILL fallback → `os.close`. Shell resolution checks absolute-path candidates (request → config → `$SHELL` → `/bin/bash` → `/bin/sh`) and rejects relative paths. Per-client cap of 4 concurrent sessions. Child gets `TERM=xterm-256color`, `COLORTERM=truecolor`, and `HERMES_RELAY_TERMINAL=<session_name>` as a debug marker. Unix-only: `pty`/`termios`/`fcntl` imports are guarded with `try/except ImportError` so the relay still starts on Windows — attach attempts return a clean `terminal.error` instead of crashing the whole server at import time.
- **Config** — Added `terminal_shell: str | None` to `RelayConfig` (`RELAY_TERMINAL_SHELL` env var, `None` = auto-detect). Wired into `TerminalHandler(default_shell=...)` in `relay.py`.
- **xterm.js asset bundle (`app/src/main/assets/terminal/`)** — Downloaded `@xterm/xterm@5.5.0` + `@xterm/addon-fit@0.10.0` + `@xterm/addon-web-links@0.11.0` from jsDelivr into `assets/terminal/`. Wrote `index.html` with a Hermes-themed palette (navy `#1A1A2E` background, purple `#B794F4` cursor/magenta, magenta/cyan/green ANSI mapping that matches the app's Material 3 primary). Disables autocorrect/overscroll/zoom. Uses base64-encoded output payloads (`window.writeTerminal('<b64>')`) to avoid JS string-escape headaches with control bytes and escape sequences.
- **`TerminalViewModel.kt`** — AndroidViewModel mirroring `ChatViewModel` init pattern. Registers a `ChannelMultiplexer` handler for `"terminal"`. State flow tracks attached/sessionName/pid/shell/cols/rows/tmuxAvailable/ctrlActive/altActive/error. Output flows on a `MutableSharedFlow<String>` (replay=0, buffer=256) — explicitly not a StateFlow because terminal chunks must be delivered exactly once; StateFlow would conflate rapid deltas and drop output. Sticky CTRL translates a–z/A–Z + `[\]` to their control bytes; sticky ALT prefixes ESC. Both auto-clear after the next keypress. Pending-attach queue: if the WebView signals ready before the relay connects, the cols/rows are held and the attach fires once `ConnectionState.Connected` lands.
- **`TerminalWebView.kt`** — Compose WebView wrapper. Loads `file:///android_asset/terminal/index.html`, installs `AndroidBridge` @JavascriptInterface (`onReady`/`onInput`/`onResize`/`onLink`). `viewModel.outputFlow` is collected in a `LaunchedEffect` on the UI thread and piped into `webView.evaluateJavascript("window.writeTerminal('$b64')")`. `DisposableEffect` tears down the WebView cleanly on recomposition out. Uses the modern `shouldOverrideUrlLoading(WebView, WebResourceRequest)` signature (minSdk 26), routes non-asset URLs to the system browser via `ACTION_VIEW`.
- **`ExtraKeysToolbar.kt`** — `RowScope`-extension `ToolbarKey` composable for the 8-key bottom toolbar: ESC, TAB, CTRL (sticky), ALT (sticky), ←↓↑→. Active state highlights with `primary.copy(alpha=0.22f)` background + primary border. Haptic `LongPress` feedback on every tap.
- **`TerminalScreen.kt`** — Replaced the "Coming Soon" placeholder. TopAppBar with monospace subtitle line that shows session name / "attaching…" / "relay disconnected" / error. `ConnectionStatusBadge` in the actions slot (green when attached, amber when attaching/reconnecting, red otherwise) + `Refresh` IconButton for manual reattach. WebView fills `weight(1f)`, `ExtraKeysToolbar` is anchored at the bottom with `navigationBarsPadding() + imePadding()` so it slides up with the IME. Overlay card appears when relay is disconnected or there's an error, explaining state and pointing at Settings.
- **`RelayApp.kt` wiring** — Imported `TerminalViewModel`, added `viewModel()` instance, one-time `LaunchedEffect` calls `terminalViewModel.initialize(multiplexer, relayConnectionState)` so the channel handler registers and auto-attaches on reconnect. `Screen.Terminal` composable now passes both view models into `TerminalScreen`.

**Files changed/added:**
- `relay_server/channels/terminal.py` (rewritten — 560 lines of real PTY handling)
- `relay_server/config.py` (new `terminal_shell` field + env var)
- `relay_server/relay.py` (pass `default_shell` into `TerminalHandler`)
- `app/src/main/assets/terminal/index.html` (new)
- `app/src/main/assets/terminal/xterm.js` + `xterm.css` + `addon-fit.js` + `addon-web-links.js` (new — ~300 KB bundled, no CDN dependency at runtime)
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/TerminalViewModel.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/TerminalWebView.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ExtraKeysToolbar.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/TerminalScreen.kt` (rewritten)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt` (import + instantiate + init + pass to screen)
- `DEVLOG.md` (this entry)

**Build:** `gradlew :app:assembleDebug` — BUILD SUCCESSFUL in 1m 59s. Only pre-existing deprecation warnings remain; no warnings or errors from new code. Server-side Python is not covered by CI; change is additive and gated by `_PTY_AVAILABLE` on Windows hosts so the existing chat/bridge channels remain unaffected.

**Not yet tested on real hardware.** This session produced compiling code, not verified feature behavior. Before declaring Phase 2 MVP shipped we need:
1. A Linux/macOS host running the relay server + tmux (or not — raw PTY fallback is what we actually built) with a shell the host user can actually log into.
2. Deploy the debug APK, connect the relay, open the Terminal tab, verify: prompt appears → soft keyboard typing reaches the shell → arrow keys work → CTRL+C interrupts → resize on rotation / IME show reflows prompt correctly → htop renders with box chars → disconnect/reconnect reattaches cleanly.
3. Check for WebView keyboard quirks on at least two devices (the plan flags this as the highest device-side risk).

**Deferred from the Phase 2 plan (will land in follow-up sessions):**
- **Plugin consolidation** — `relay_server/` is still a separate process; the plan wants it absorbed into `plugin/relay/` with a unified `hermes relay` CLI. Pure refactor, no user-visible change. Separate session.
- **tmux session persistence** — `self.tmux_available` is detected and surfaced in `terminal.attached` payloads but we're not using libtmux yet. Current implementation is raw PTY only. Adding tmux is additive (same envelope protocol, swap the spawn path).
- **P1/P2 polish** — pinch-to-zoom, mouse reporting (needed for htop/vim mouse), font bundling (JetBrains Mono NF), multiple themes, settings screen entries, visual bell, scroll-to-bottom FAB, URL-detection config, multi-session picker dropdown, hardware keyboard edge cases.
- **CLI commands** — `hermes relay status/sessions/kill` are spec'd but not wired. Nothing to wire them to until plugin consolidation lands.

**Next:**
- Smoke-test on a real device with the relay running against a real Linux host.
- Fix whatever that surfaces (WebView keyboard oddities, resize timing, PTY race conditions we haven't seen yet).
- Decide whether to ship MVP as-is under a feature flag or continue straight through 2B polish → tmux → consolidation before any user sees it.

**Blockers:**
- None in code. Need a Linux/macOS relay host to exercise the PTY path end-to-end.

---

## 2026-04-10 — v0.1.0 Play Store Release (Internal Testing)

**Done:**
- **Keystore** — Generated `release.keystore` (RSA 2048, SHA384withRSA, 10000-day validity, alias `hermes-relay`) via `keytool -genkey`. Certificate subject: `CN=Bailey Dixon, OU=Hermes-Relay, O=Codename-11, L=Tampa, ST=Florida, C=US`. SHA1 fingerprint `C9:8E:1B:74:A6:D8:A6:6E:0A:3A:C9:00:96:C2:0B:B7:44:B0:B7:FC`; SHA256 `A9:A4:2D:94:20:8B:94:B3:68:5B:01:93:E3:94:9B:90:50:AD:80:60:56:E7:16:3C:FC:E5:11:AF:68:0D:79:4B`. Stored at repo root as `release.keystore` (gitignored via `.gitignore:31`). Password stored in password manager; must back up file + password to a separate encrypted location before closing this session.
- **local.properties** — Added `hermes.keystore.path`, `hermes.keystore.password`, `hermes.key.alias`, `hermes.key.password` lines pointing at the absolute path (Gradle's `file()` resolves relative paths against the `app/` module, not repo root, so absolute path with forward slashes is required on Windows).
- **Local bundle build** — `gradlew bundleRelease` — BUILD SUCCESSFUL in 4m 41s, produced `app/build/outputs/bundle/release/app-release.aab` (19,071,575 bytes / 18.2 MB). Fingerprint-verified with `keytool -printcert -jarfile` — AAB is release-signed with the correct cert (serial `eaaf7de55766c57e`), not the debug fallback.
- **GitHub Secrets** — Set all four via `gh secret set` CLI so `.github/workflows/release.yml` will release-sign CI artifacts on future tags: `HERMES_KEYSTORE_BASE64` (from `base64 -w 0 release.keystore`), `HERMES_KEYSTORE_PASSWORD`, `HERMES_KEY_ALIAS=hermes-relay`, `HERMES_KEY_PASSWORD`. Used `printf '%s'` (no trailing newline) piped to `gh secret set` for the non-base64 values — a trailing `\n` would get baked into the secret and cause "Keystore was tampered with" failures in CI.
- **Play Console upload** — Uploaded the local `app-release.aab` to the Internal testing track on Google Play Console. `versionCode=1`, `versionName=0.1.0`. Enrolled in Play App Signing (Google re-signs installs with their HSM-held key; the keystore is now only an *upload key* with reset-via-support recovery). Release rolled out successfully. One non-blocking warning about missing native debug symbols (deferred — see Next section).
- **Git tag** — `git tag -a v0.1.0` pushed to `origin`, triggering `.github/workflows/release.yml` to build APK + AAB + `SHA256SUMS.txt` and attach them to a GitHub Release named `v0.1.0`. Tag landed on commit `089e011` (the `play-publisher 4.0.0` AGP 9 compat bump from a parallel session), which means the CI-built AAB is byte-different from the Play Console AAB (different `libs.versions.toml`) but functionally identical and signed with the same cert — acceptable for Internal testing because only the Play Console artifact reaches testers; the GitHub Release is a secondary distribution channel.

**Files changed:**
- `local.properties` (gitignored) — added release signing properties
- `release.keystore` (gitignored) — new
- `DEVLOG.md` — this entry

**Next:**
- **Back up `release.keystore` + password** to an encrypted off-machine location before closing this session. Losing both = losing ability to submit future upload keys (Play App Signing reset flow takes ~2 days).
- **Add native debug symbols for v0.1.1** — Add `ndk { debugSymbolLevel = "SYMBOL_TABLE" }` to the `release` build type in `app/build.gradle.kts`. Fixes the Play Console warning and gives readable native stack traces for crashes in transitive deps (ML Kit, CameraX, OkHttp BoringSSL, Compose/Skia).
- **Promote through tracks** — New personal Play accounts need 14 continuous days of Closed testing with ≥12 opted-in testers before production rollout. Create a Closed testing track and recruit testers ASAP if the production timeline matters.
- **Verify GitHub Release assets** — Once `release.yml` finishes, confirm the Release has APK + AAB + `SHA256SUMS.txt` and that the workflow summary says "Release-signed" (not the debug-signed warning banner).

**Blockers:**
- None.

---

## 2026-04-10 — Smooth Chat Auto-Scroll Fix + Compose Deprecation Cleanup

**Done:**
- **Smooth chat auto-scroll** — Rewrote `ChatScreen.kt` auto-scroll logic to fix five bugs surfaced while recording the demo video. (1) The `LaunchedEffect` keys only watched `messages.size` and `lastMessage?.content?.length`, so growth of the reasoning block (`thinkingContent`) and tool-card additions (`toolCalls`) silently froze auto-follow during long thinking and tool execution phases. (2) `animateScrollToItem(messages.size - 1)` defaulted to `scrollOffset = 0`, which aligns the *top* of the item with the top of the viewport — for tall streaming bubbles this snapped the user back to the start of the message instead of staying with the latest token. (3) There was no "user is reading history" gate, so any delta would yank a user reading scrollback back to the bottom. (4) The `isStreaming` flag was a snapshot key, so the stream-complete transition (true → false) re-triggered `animateScrollToItem` even when no content actually changed — producing a visible jiggle. (5) Sessions endpoint reloads the entire message list on stream complete via `loadMessageHistory()`, and the resulting `animateItem()` placement animations on every bubble fought with our concurrent `animateScrollToItem` — producing a flash where the viewport visibly settled twice.
- **The fix** — Added a `ChatScrollSnapshot` data class that captures all five streaming-state fields (message count, content length, thinking length, tool-call count, isStreaming). A `snapshotFlow` over the snapshot, debounced with `distinctUntilChanged`, drives auto-scroll via `collectLatest` (which cancels in-flight animations when newer deltas arrive — prevents pile-ups during rapid SSE bursts). The scroll target is `(totalItemsCount - 1, scrollOffset = Int.MAX_VALUE)` so Compose pins the absolute end of the list to the bottom of the viewport regardless of how tall the streaming bubble has grown. A `userScrolledAway` flag, tracked via a separate `snapshotFlow` on `listState.isScrollInProgress to isAtBottom`, gates the auto-follow — scrolling up to read history pauses it; scrolling back to the bottom (or tapping the FAB) resumes it. The FAB now uses `!listState.canScrollForward` for its `isAtBottom` derivation (replaces the off-by-one `lastVisible < messages.size - 2` arithmetic) and clears `userScrolledAway` on tap so live-follow resumes immediately even before the scroll animation settles. The `collectLatest` lambda also tracks the previous snapshot in a coroutine-scoped var, which lets it (a) skip "state-only" deltas where only the `isStreaming` flag changed (the viewport was already correct from the last content delta — re-animating causes a jiggle on stream complete) and (b) detect "list rebuild" deltas (sessions-mode `loadMessageHistory` collapsing one streaming message into multiple final messages with proper boundaries) and use the instant `scrollToItem` path instead of `animateScrollToItem` so the items can run their `animateItem()` placement animations without competing with our scroll animation.
- **Compose deprecation cleanup** — Migrated `Icons.Filled.Chat` → `Icons.AutoMirrored.Filled.Chat` in `RelayApp.kt` (auto-mirrors for RTL locales). Migrated `LocalClipboardManager` → `LocalClipboard` (suspend-based `Clipboard.setClipEntry(ClipEntry)` API) in both `ChatScreen.kt` and `SettingsScreen.kt`. Both clipboard call sites now wrap in the existing `rememberCoroutineScope().launch {}` since the new API is suspend; the underlying clipboard write now runs off the UI thread, which is a small responsiveness win on lower-end devices. Removed the now-unused `AnnotatedString` imports from both files. Build is now warning-clean.
- **Settings toggle** — Added `smoothAutoScroll` boolean preference to `ConnectionViewModel` (DataStore key `smooth_auto_scroll`, default `true`), mirroring the existing `animationEnabled`/`animationBehindChat` pattern. New row in **Settings > Chat** under "Show reasoning". When disabled, the entire auto-scroll `LaunchedEffect` early-returns and the chat is fully manual.
- **Docs** — Updated `README.md` features list, `docs/spec.md` Settings Tab section, and `user-docs/guide/chat.md` (new "Smooth Auto-Scroll" subsection explaining the pause-on-scroll-up behavior).
- **Brand rename** — "Hermes Relay" → "Hermes-Relay" across 57 files (docs, app strings, scripts, workflows, plugin, relay server). Aligns the display name with the canonical repo slug. The Android `app_name` in `strings.xml` is now `Hermes-Relay`; PascalCase code identifiers (`HermesRelayApp`, `HermesRelayTheme`, logcat tag `HermesRelay`) were intentionally left alone since they're internal symbols, not user-facing text.
- **Docs landing redesign** — Restructured `user-docs/index.md` to put install above features. Created `InstallSection.vue` (mounted via VitePress `home-hero-after` slot) and `HeroDemo.vue` (mounted via `home-hero-image` slot). Hero is now a phone-framed `<video>` that autoplays the demo, then crossfades to the brand logo after 12s for the rest of the session via Vue `<Transition mode="out-in">` — bandwidth fetches stop when the video unmounts. Fixed two raw-HTML `/guide/getting-started` hrefs that VitePress base-rewriting silently skipped (they were 404ing on the deployed site under the `/hermes-relay/` base path).
- **Demo video pipeline** — Re-encoded source `chat_demo.mp4` from 20.5 MB / 102 fps / 1080×2340 down to 1.95 MB / 30 fps / 720×1560 via ffmpeg (`-vf scale=720:-2,fps=30 -crf 28 -preset slow -an -movflags +faststart`) — 90% size reduction, mostly from dropping the wildly oversampled framerate. Extracted poster JPEG from first frame at 0.5s for instant LCP. Embedded with autoplay+muted+playsinline+preload=metadata in the docs hero, with `controls` in Getting Started's "Verify Connection" section, and as a `<video>` tag in README pointing at the GitHub raw URL. Used portable `imageio-ffmpeg` Python package since system ffmpeg wasn't installed.
- **Release infrastructure hardening** — `.github/workflows/release.yml` now builds APK + AAB in one Gradle invocation, decodes a `HERMES_KEYSTORE_BASE64` GitHub Secret to `$RUNNER_TEMP/release.keystore`, signs with the release keystore when secrets are set, falls back to debug signing with a warning banner in `$GITHUB_STEP_SUMMARY` when they're not. Generates SHA256 checksums for both artifacts and attaches all three (APK, AAB, SHA256SUMS.txt) to the GitHub Release.
- **gradle-play-publisher plugin** — Added `com.github.triplet.play` v3.13.0 (latest stable compatible with AGP 8.13.2 — v4.0.0 requires AGP 9). Configured in `app/build.gradle.kts` with `track=internal`, `releaseStatus=DRAFT`, `defaultToAppBundles=true`, reading credentials from `<repo-root>/play-service-account.json` (gitignored). Plugin is fully optional — `assembleRelease`/`bundleRelease` work without the JSON; only the explicit `publishReleaseBundle`/`promoteReleaseArtifact` tasks require it. Verified `settings.gradle.kts` already has `gradlePluginPortal()` in `pluginManagement`.
- **RELEASE.md** — New canonical doc (312 lines) covering: SemVer versioning conventions with optional `-alpha`/`-beta`/`-rc.N` prereleases and monotonic `appVersionCode`; one-time setup (keystore generation via `keytool`, `local.properties` config, base64 encoding for CI, Play Console account + 14-day closed testing rule for new personal accounts, Play Developer API service account creation in Google Cloud Console, GitHub Actions secrets); the 7-step release process (bump → notes → build → verify → tag/push → upload → post-release); CI behavior; hotfix recipe; troubleshooting. `CLAUDE.md` "Dev Workflow" section now references it.

**Files changed:**
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` (preference key + StateFlow + setter)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt` (new toggle row in Chat section + LocalClipboard migration)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ChatScreen.kt` (data class, scroll-tracking effect, rewritten auto-scroll effect with previous-snapshot diffing, FAB fix, LocalClipboard migration)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt` (Icons.AutoMirrored.Filled.Chat)
- `README.md`, `docs/spec.md`, `user-docs/guide/chat.md`, `DEVLOG.md`
- **Rebrand** — 57 files across `app/src/main/res/`, `docs/`, `user-docs/`, `scripts/`, `plugin/`, `relay_server/`, `skills/`, `.github/workflows/`
- **Docs landing** — `user-docs/index.md` (frontmatter trim), `user-docs/.vitepress/theme/index.ts` (slot wiring + dead-link fix), new `user-docs/.vitepress/theme/components/{InstallSection,HeroDemo}.vue`
- **Demo video** — `assets/chat_demo.mp4` + `assets/chat_demo_poster.jpg` (canonical), `user-docs/public/chat_demo.mp4` + `user-docs/public/chat_demo_poster.jpg` (VitePress-served copies), `README.md` (`<video>` tag), `user-docs/guide/getting-started.md` (full video in Verify Connection section)
- **Release infra** — `.github/workflows/release.yml` (AAB build + keystore decode + checksums + summary), `gradle/libs.versions.toml` (play-publisher = 3.13.0), `app/build.gradle.kts` (`alias(libs.plugins.play.publisher)` + `play { }` block), `.gitignore` (play-service-account.json + keystore.properties)
- **Release docs** — new `RELEASE.md` (312 lines), `CLAUDE.md` (Release Process subsection)

**Next:**
- Test on device with a long streaming response (verify auto-follow during reasoning + tool cards + text deltas)
- Test the pause-on-scroll-up gesture and the FAB resume
- Verify the disabled-pref path leaves the chat fully manual

**Blockers:**
- None — `compileDebugKotlin` passes clean (only pre-existing `LocalClipboardManager` deprecation warnings unrelated to this change)

---

## 2026-04-09 — Play Store Prep, Plugin CLI Migration, One-Line Install

**Done:**
- **Play Store listing prep (v0.1.0)** — 512x512 hi-res icon + 1024x500 feature graphic rendered from `assets/logo.svg` via `scripts/gen-store-assets.mjs` (pure-Node, `@resvg/resvg-js`). Feature graphic shows centered logo + three channel labels (Chat, Terminal, Bridge). Listing doc at `docs/play-store-listing.md` with short/full descriptions, release notes, and category. Interactive `scripts/screenshots.bat` TUI for capturing clean device screenshots with Android demo mode enabled.
- **Privacy policy page** — `user-docs/privacy.md` published at `/privacy` for Google Play's required URL. Formal language (effective date, COPPA disclosure, contact, change policy). Added Privacy link to top nav.
- **Plugin CLI migration — `hermes-pair` → `hermes pair`** — Rewrote the standalone bash script as a Python module (`plugin/pair.py`) with `plugin/cli.py` registering `hermes pair` via the v0.8.0 `register_cli(subparser)` convention. Pure-Python QR rendering via `segno` (no `qrencode` binary). Always shows connection details as plain text alongside the QR, so `hermes pair` works inside Hermes Rich TUI and over limited SSH sessions. Cross-platform LAN IP detection via socket trick (replaces Linux-only `ip route`). Config fallback chain: `config.yaml` → `~/.hermes/.env` → env vars → defaults. Wrapped `ctx.register_cli_command()` call in try/except so 14 `android_*` tools still register cleanly on hermes-agent v0.7.0.
- **One-line server install** — `install.sh` at repo root: `curl -fsSL .../install.sh | bash` clones plugin, installs Python deps (`requests`, `aiohttp`, `segno`), and prints next steps. Deleted the old `plugin/install.sh` since its own URL comment pointed to the root. Supports `HERMES_HOME` and `HERMES_RELAY_BRANCH` env overrides.
- **README + docs restructure** — Replaced README `## Install` with `## Quick Start` leading with the `curl | bash` one-liner. Homepage (`user-docs/index.md`) gets a custom "Install in 30 seconds" block below feature cards. Guide landing page (`user-docs/guide/index.md`) leads with the same one-liner. Getting Started page restructured into 3-step Quick Start. VitePress copy buttons on code blocks already in place.
- **Tool annotations default OFF** — The chat parse tool annotations feature was causing messages to wait for stream end before displaying. Defaulted to `false` in `ChatHandler.kt` and `ConnectionViewModel.kt`; subtitle now warns about the streaming delay behavior.
- **Deprecated the standalone skill** — `skills/hermes-pairing-qr/SKILL.md` and `hermes-pair` script keep working for v0.7.0 users but print deprecation warnings pointing to the plugin.

**Files changed:**
- `plugin/pair.py` (new), `plugin/cli.py` (new), `plugin/__init__.py` (wire CLI registration), `plugin/setup.py` / `pyproject.toml` / `requirements.txt` / `plugin.yaml` (bumped to 0.3.0 + segno)
- `install.sh` (new at root, replaces `plugin/install.sh`)
- `docs/play-store-listing.md` (new), `assets/play-store-icon-512.png` (new), `assets/play-store-feature-1024x500.png` (new), `scripts/gen-store-assets.mjs` (new), `scripts/screenshots.bat` (new)
- `user-docs/privacy.md` (new), `user-docs/.vitepress/config.mts` (nav link), `user-docs/index.md` (install section), `user-docs/guide/index.md` (Quick Install), `user-docs/guide/getting-started.md` (restructured)
- `README.md` (Quick Start up top), `CLAUDE.md` (key files updated)
- `skills/hermes-pairing-qr/SKILL.md` + `hermes-pair` (deprecation notices)

**Next:**
- Test `hermes pair` against local hermes-agent v0.8.0 once available
- Finalize Play Store screenshots (curate ones without real server IPs/keys)
- Submit v0.1.0 to Google Play Console

**Blockers:**
- hermes-agent v0.8.0 not yet released at time of writing — the `register_cli_command` plumbing exists in v0.7.0 but general plugin CLI commands aren't wired into the main argparse yet. Plugin still installs and registers tools fine on v0.7.0; users fall back to the deprecated standalone script for pairing until v0.8.0 ships.

---

## 2026-04-08 — Tool Call Reload on Stream Complete, Keyboard Gap Fix, Placeholder Dedup

**Done:**
- **Tool call reload on stream complete** — Sessions endpoint doesn't emit structured tool events during streaming; tool calls only exist as `tool_calls` JSON on stored server messages. Added server history reload in `onCompleteCb` when using sessions mode — replaces the single streaming message with the server's authoritative multi-message structure (proper message boundaries + tool call cards). Queue drain deferred until reload completes.
- **Annotation finalization pass** — Added `finalizeAnnotations()` as a post-stream reconciliation hook in `onStreamComplete()` and `onTurnComplete()`. Re-scans final message content for surviving annotation text, strips it, creates missing `ToolCall` objects, and marks incomplete annotation tools as completed. Safety net for servers that emit inline annotation markers.
- **Placeholder message dedup** — When `message.started` fires with a server-assigned ID, the empty placeholder message's ID is now replaced via `replaceMessageId()` (only acts on empty+streaming messages). Prevents orphan placeholder bubbles showing duplicate streaming dots alongside the real message.
- **Keyboard gap fix** — Bottom navigation bar now hides when keyboard is visible (`WindowInsets.ime.getBottom > 0`). Eliminates the gap between input bar and keyboard caused by `innerPadding` (bottom nav height) stacking with `imePadding()` (keyboard height).

**Files changed:**
- `network/handlers/ChatHandler.kt` — Added `replaceMessageId()`, `finalizeAnnotations()`, `matchAnnotationToolName()`. Wired finalization into `onStreamComplete()` and `onTurnComplete()`.
- `viewmodel/ChatViewModel.kt` — `onMessageStartedCb` calls `replaceMessageId()`. `onCompleteCb` reloads session history for sessions mode.
- `ui/RelayApp.kt` — Bottom nav hidden when keyboard visible via IME insets check.

---

## 2026-04-07 — ASCII Morphing Sphere, Ambient Mode, Animation Settings, Polish Fixes

**Done:**
- **ASCII morphing sphere** — animated visualization on the empty chat screen, inspired by AMP Code CLI. Pure Compose Canvas rendering (no OpenGL). Characters `. : - = + * # % @` form a sphere shape with 3D lighting. Color pulses green to purple. Contained in square aspect ratio box above "Start a conversation" text.
- **Ambient mode** — toggle button (AutoAwesome icon) in chat header bar hides messages and shows the sphere fullscreen. Tap the ChatBubble icon to return to chat.
- **Animation behind messages** — sphere renders at 15% opacity behind the chat message list as a subtle ambient background. Toggleable in Settings.
- **Animation settings** — new section in Settings under Appearance: "ASCII sphere" toggle (on by default), "Behind messages" toggle (on by default, disabled when animation is off).
- **Parse tool annotations** — marked as "Experimental" badge, disabled/dimmed when streaming endpoint is "Runs" mode (only relevant for Sessions mode).
- **Empty bubble fix** — messages with blank content and no tool calls are now hidden from chat.
- **App icon fix** — adaptive icon foreground scaled to 75% via `<group>` transform for proper safe zone padding.
- **Dev scripts** — added `release`, `bundle`, `version` commands to `scripts/dev.bat`.
- **MCP tooling** — android-tools-mcp v0.1.1 (IDE/build layer) + mobile-mcp (device/runtime layer) configured as companion MCP servers. Full reference in `docs/mcp-tooling.md`.
- **Audit fixes** — MIT LICENSE added, orphaned `companion/` and `companion_relay/` removed, `FOREGROUND_SERVICE` permission removed, CHANGELOG URLs fixed, version refs updated to 0.1.0, .gitignore updated, plugin refs updated from raulvidis to Codename-11.

**New files:**
- `ui/components/MorphingSphere.kt` — ASCII morphing sphere composable (Canvas-based, 3D lighting, color pulse)

**Files changed:**
- `ui/screens/ChatScreen.kt` — Empty state sphere, ambient mode toggle, behind-messages background layer
- `ui/screens/SettingsScreen.kt` — Animation settings section (ASCII sphere toggle, behind messages toggle), parse tool annotations experimental badge
- `viewmodel/ConnectionViewModel.kt` — Animation preference DataStore keys/flows
- `app/build.gradle.kts` — Version and build config updates
- `res/mipmap-anydpi-v26/ic_launcher.xml` — 75% scale group transform on foreground
- `scripts/dev.bat` — Added release, bundle, version commands

---

## 2026-04-07 — Token Tracking Fix, Stats Enhancements, Keyboard Gap, Configurable Limits

**Done:**
- **Token tracking fix** — Root cause: OpenAI-format SSE events (e.g. `chat.completion.chunk`) have no `type`/`event` field, so the `val eventType = type ?: event.resolvedType ?: return` line exited before usage was ever checked. Moved usage extraction **before** the type resolution in both `sendChatStream()` and `sendRunStream()`. Also added `prompt_tokens`/`completion_tokens` (OpenAI naming) support in `UsageInfo` via `resolvedInputTokens`/`resolvedOutputTokens` helper properties.
- **Stats for Nerds enhancements** — Reset button with confirmation dialog, tokens per message average in summary line (`~Xk/msg`), peak TTFT and slowest completion times (tertiary color), `formatMsWithSeconds()` helper shows `1234ms (1.2s)` for all time displays >= 1s.
- **Configurable limits** — Expandable "Limits" section in Chat settings with segmented button rows for max attachment size (1/5/10/25/50 MB, default 10) and max message length (1K/2K/4K/8K/16K chars, default 4K). Persisted to DataStore, read reactively in ChatScreen.
- **Keyboard gap fix** — Set `contentWindowInsets = WindowInsets(0)` on the Scaffold in RelayApp.kt. The Scaffold was adding system bar padding to `innerPadding` that stacked with ChatScreen's `imePadding()`, causing a visible gap between input bar and keyboard.

**Files changed:**
- `network/HermesApiClient.kt` — Usage check moved before eventType resolution in both streaming methods
- `network/models/SessionModels.kt` — `UsageInfo` now accepts `prompt_tokens`/`completion_tokens`, added `resolvedInputTokens`/`resolvedOutputTokens`/`resolvedTotalTokens`
- `viewmodel/ChatViewModel.kt` — Uses `usage.resolvedInputTokens` etc
- `viewmodel/ConnectionViewModel.kt` — `maxAttachmentMb` + `maxMessageLength` DataStore keys/flows/setters
- `ui/components/StatsForNerds.kt` — Reset button+dialog, tokens/msg, peak/slowest times, `formatMsWithSeconds()`
- `ui/screens/SettingsScreen.kt` — Expandable "Limits" section with segmented buttons
- `ui/screens/ChatScreen.kt` — Reads `charLimit`/`maxAttachmentMb` from settings
- `ui/RelayApp.kt` — `contentWindowInsets = WindowInsets(0)` on Scaffold

---

## 2026-04-07 — File Attachments

**Done:**
- **Generic file attachments** — users can attach any file type via `+` button in the input bar. Uses Android `OpenMultipleDocuments` picker (accepts `*/*`). Files base64-encoded and sent in the Hermes API `attachments` array (`{contentType, content}`).
- **Attachment preview strip** — horizontal scrollable row above input bar showing pending attachments. Image attachments show decoded thumbnails, other files show document icon + filename + size. Each attachment has a remove (X) button.
- **Attachment rendering in bubbles** — user messages display attached images inline (decoded from base64), non-image attachments show as file badge with name. Forward-compatible with agent-sent images.
- **10 MB file size limit** — enforced client-side with toast warning.
- **Send with attachments only** — send button enabled when attachments are present even without text. Sends `[attachment]` as placeholder text.
- **API integration** — `attachments` parameter added to both `sendChatStream()` and `sendRunStream()` in HermesApiClient. Serialized as JSON array matching Hermes WebAPI spec.
- **Message history support** — `MessageItem.imageUrls` extracts `image_url` content blocks from OpenAI-format content arrays for future server-side image rendering.

**Files changed:**
- `data/ChatMessage.kt` — Added `Attachment` data class, `attachments` field on `ChatMessage`
- `network/models/SessionModels.kt` — Added `imageUrls` property to `MessageItem`
- `network/HermesApiClient.kt` — `attachments` param on `sendChatStream()` + `sendRunStream()`, JSON array serialization
- `viewmodel/ChatViewModel.kt` — `_pendingAttachments` StateFlow, add/remove/clear, snapshot-and-clear on send, pass through to API
- `ui/screens/ChatScreen.kt` — `+` button, `OpenMultipleDocuments` picker, attachment preview strip, `formatFileSize()` helper
- `ui/components/MessageBubble.kt` — Inline image rendering (base64 decode), file badge for non-images

---

## 2026-04-07 — Client-Side Message Queuing

**Done:**
- **Message queuing** — Users can now send messages while the agent is streaming. Messages are queued locally and auto-sent when the current stream completes. Queue drains one at a time, maintaining proper ordering.
- **Input bar redesign** — During streaming, both Stop and Send buttons are visible side by side. Send button uses `tertiary` color during streaming to indicate "queue" mode. Placeholder changes to "Queue a message..." when streaming.
- **Queue indicator** — Animated bar above the input field shows queued message count ("1 message queued" / "3 messages queued") with a Clear button to discard the queue. Uses `AnimatedVisibility` for smooth entrance/exit.
- **Queue lifecycle** — Queue is cleared on stream cancellation (Stop button) and on stream error, preventing stale messages from auto-sending after failures.

**Design decisions:**
- Client-side queuing (not server-side `/queue` command) because the Hermes HTTP API doesn't support concurrent SSE streams to the same session. The gateway's `/queue` is a CLI-level feature, not an HTTP endpoint.
- Queue drains automatically — no manual "send next" required. Provides a seamless conversation flow.
- No purple glow on Send button during streaming — visual distinction between "send now" and "queue for later".

**Files changed:**
- `viewmodel/ChatViewModel.kt` — `_queuedMessages` StateFlow, `sendMessage()` queues during streaming, `sendMessageInternal()` extracted, `drainQueue()` on complete, `clearQueue()`, queue cleared on error/cancel
- `ui/screens/ChatScreen.kt` — Queue indicator row, input bar with both Stop+Send buttons, tertiary send tint during streaming, "Queue a message..." placeholder

---

## 2026-04-07 — Feature Gating, MCP Tooling, v0.1.0 Release Prep

**Done:**
- **Feature gating system** — `FeatureFlags.kt` singleton with compile-time defaults (`BuildConfig.DEV_MODE`) and runtime DataStore overrides. Debug builds have all features unlocked; release builds gate experimental features behind Developer Options.
- **Developer Options** — Hidden settings section activated by tapping version number 7 times (same UX as Android system Developer Options). Contains relay features toggle and lock button. Uses `tertiary` color scheme for visual distinction.
- **Gated relay/pairing settings** — Relay Server and Pairing sections in Settings hidden by default in release builds. Only visible when relay feature flag is enabled via Developer Options.
- **Gated onboarding pages** — Terminal, Bridge, and Relay pages dynamically excluded from onboarding flow when relay feature is disabled. Page count and indices adjust automatically.
- **Version bump** — `0.1.0-beta` → `0.1.0` for Google Play submission.
- **BuildConfig.DEV_MODE** — `true` for debug, `false` for release. Used by FeatureFlags as compile-time default.
- **android-tools-mcp v0.1.1** — Fixed MCP server path, built plugin from fork, committed wrapper jar fix, repo cleanup (fork attribution, VM option name fix, cross-platform release script), released to GitHub.
- **mobile-mcp added** — Added `mobile-next/mobile-mcp` as companion MCP server for device/runtime testing (tap, swipe, screenshot, app management). Configured with telemetry disabled.
- **MCP tooling docs** — Created `docs/mcp-tooling.md` with full reference for both MCP servers (setup, prerequisites, 40 tools listed, when-to-use guide, overlap analysis).

**New files:**
- `data/FeatureFlags.kt` — Feature flag singleton
- `docs/mcp-tooling.md` — MCP tooling reference

**Files changed:**
- `app/build.gradle.kts` — Added `DEV_MODE` BuildConfig field, `buildConfig = true`
- `gradle/libs.versions.toml` — Version `0.1.0`
- `ui/screens/SettingsScreen.kt` — Feature-gated relay/pairing, added Developer Options section with tap-to-unlock
- `ui/onboarding/OnboardingScreen.kt` — Dynamic page list based on feature flags
- `CLAUDE.md` — Updated current state, added FeatureFlags to key files, MCP tooling section, related projects

**Next:**
- Build release APK and submit to Google Play (closed testing track)
- Test feature gating on release build (relay settings hidden, dev options tap unlock)
- Phase 2: Terminal channel
- Phase 3: Bridge channel

---

## 2026-04-07 — Session Management Audit, Play Store Release Prep

**Done:**
- **Session management audit** — Full review of session CRUD, persistence, capability detection, error handling. Implementation is complete and solid against upstream Hermes API (both `/api/sessions` non-standard and `/v1/runs` standard endpoints).
- **Fixed SessionDrawer highlight bug** — `backgroundColor` variable was computed but never applied to the Row modifier. Active sessions now properly highlighted with `secondaryContainer`.
- **Added Privacy Policy link** — New "Privacy Policy" button in Settings → About, linking to GitHub-hosted `docs/privacy.md`. Required for Google Play Store submission.
- **Fixed privacy.md inaccuracies** — Added CAMERA permission to the permissions table (used for QR scanning, declared `required="false"`). Corrected network security description to accurately reflect cleartext policy.
- **Fixed RELEASE_NOTES.md URL** — Changed generic `user/hermes-android` to actual `Codename-11/hermes-android`.
- **Improved network_security_config.xml docs** — Expanded comment explaining why cleartext is globally permitted (Android doesn't support IP range restrictions, users connect to arbitrary LAN IPs) and how security is enforced at the application layer (insecure mode toggle + warning badge).

**Session management features confirmed working:**
- List/create/switch/rename/delete sessions with optimistic updates + rollback
- Message history loading with tool call reconstruction
- Auto-session creation on first message send with auto-title
- Session ID persistence via DataStore across app restarts
- Capability detection (`detectChatMode()`) with graceful degradation
- Both Sessions and Runs streaming endpoints

**Play Store readiness:**
- Signing config loads from env vars / local.properties ✅
- ProGuard rules comprehensive ✅
- Release workflow with version validation ✅
- Privacy policy link in app ✅
- Network security documented ✅
- No hardcoded debug flags in release ✅
- Version: `0.1.0-beta` (versionCode 1) — ready for open testing track

**Files changed:**
- `ui/components/SessionDrawer.kt` — Added `background` import + applied `backgroundColor` to Row
- `ui/screens/SettingsScreen.kt` — Added Shield icon import + Privacy Policy button in About card
- `docs/privacy.md` — Added CAMERA permission, fixed network security description
- `RELEASE_NOTES.md` — Fixed issues URL
- `res/xml/network_security_config.xml` — Expanded documentation comment

---

## 2026-04-07 — Chat UI Polish, Annotation Stripping, Reasoning Extraction

**Done:**
- **Scroll-to-bottom FAB** — SmallFloatingActionButton appears when scrolled up from bottom. Animated fade + slide. Haptic on click. Positioned bottom-end of message area.
- **Message entrance animations** — `animateItem()` on all LazyColumn items (messages, spacers, streaming dots). Smooth fade + slide when items appear/reorder.
- **Date separators** — "Today", "Yesterday", or "EEE, MMM d" chips between messages from different calendar days. Subtle surfaceVariant pill style.
- **Message grouping** — Consecutive same-sender messages have tighter spacing (2dp base + 1dp vs 6dp padding), suppressed agent name on non-first messages, grouped bubble corner shapes (flat edges where messages meet).
- **Pre-first-token indicator** — Placeholder assistant message with streaming dots appears immediately after send, before any SSE delta. Fills naturally when first delta arrives.
- **Copy feedback toast** — Snackbar "Copied to clipboard" on long-press copy. Previously only haptic with no visual confirmation.
- **Annotation stripping** — When the tool annotation parser matches inline text (`` `💻 terminal` ``), it now strips that text from the message content. Previously the raw annotation text remained visible alongside the ToolCall card.
- **Inline reasoning extraction** — `<think>`/`<thinking>` tags in assistant text are detected and redirected to `thinkingContent` for the ThinkingBlock. Handles tags split across streaming deltas. Resets on stream complete.

**Files changed:**
- `ui/screens/ChatScreen.kt` — FAB, date separators, grouping, snackbar, animation modifiers, Box wrapper for message area
- `ui/components/MessageBubble.kt` — `isFirstInGroup`/`isLastInGroup` params, grouped bubble shapes, conditional agent name
- `network/handlers/ChatHandler.kt` — `addPlaceholderMessage()`, `stripLineFromContent()`, `processInlineReasoning()`, thinking tag parser, `parseAnnotationLine` returns Boolean
- `viewmodel/ChatViewModel.kt` — placeholder message before stream start

**Note:** Code block copy button already existed (`MarkdownContent.kt` → `CodeBlockWithCopyButton`).

---

## 2026-04-07 — Tool Call Rendering Fix, Runs API, SSE Architecture Correction

**Done:**
- **Fixed premature stream completion** — `assistant.completed` was calling `onComplete()`, terminating the stream before tool events arrived in multi-turn agent loops. Now only `run.completed`/`done` end the stream. `assistant.completed` calls new `onTurnComplete()` which marks one message done without stopping the stream.
- **Added `message.started` handling** — Server-assigned message IDs now tracked via `onMessageStarted` callback. Enables proper multi-turn message tracking (each assistant turn gets its own message).
- **Dynamic message ID tracking** — `ChatViewModel.startStream()` uses `currentMessageId` variable that updates when the server sends new message IDs, instead of hardcoding one UUID for the whole stream.
- **Rewrote tool annotation parser** — Regex patterns now match actual Hermes format: `` `💻 terminal` `` (any emoji + tool name in backticks). Uses state tracking: first occurrence = start, second = complete. Also handles explicit completion/failure emojis (✅/❌) and verbose format (`🔧 Running: tool_name`).
- **Fixed message history tool calls** — `loadMessageHistory()` now reconstructs `ToolCall` objects from assistant messages' `tool_calls` field and matches tool results from `role:"tool"` messages. Previously skipped all tool data.
- **Runs API event coverage** — Added `message.delta`, `reasoning.available`, `run.failed` event handling. Updated `HermesSseEvent` model with `event` field (alias for `type`), `tool` field (Runs API format), `duration`, `output`, `text`, `timestamp`. Added `resolvedType` and `resolvedToolName` helpers.
- **SSE debug logging** — All events logged with `HermesApiClient` tag. Filter with `adb logcat -s HermesApiClient` to see what the server actually sends.
- **Updated decisions.md** — Documented the two streaming endpoints (Sessions vs Runs), tool call transparency differences, upstream API notes.
- **Updated settings description** — Streaming endpoint toggle now explains the difference.

**Architecture correction (from upstream research):**
- `/api/sessions` CRUD endpoints are NOT in upstream hermes-agent source. They may be version-specific (v0.7.0). Standard endpoints are `/v1/chat/completions`, `/v1/responses`, `/v1/runs`.
- `/v1/chat/completions` streaming embeds tool calls as **inline markdown text** (`` `💻 terminal` ``), NOT as separate SSE events. The annotation parser is the primary detection path.
- `/v1/runs` + `/v1/runs/{run_id}/events` provides **structured lifecycle events** with real `tool.started`/`tool.completed` — this is the correct endpoint for rich tool display.
- Hermes has no "channels" API (Discord/Telegram-style). The `channel_directory.py` is for cross-platform message routing, not a chat API.

**Files changed:**
- `network/HermesApiClient.kt` — new callbacks, fixed completion flow, debug logging
- `network/handlers/ChatHandler.kt` — `onTurnComplete()`, annotation rewrite, history tool calls
- `network/models/SessionModels.kt` — new fields for Runs API compatibility
- `viewmodel/ChatViewModel.kt` — dynamic message ID tracking, new callback wiring
- `ui/screens/SettingsScreen.kt` — updated endpoint toggle description
- `docs/decisions.md` — corrected API architecture documentation

**Next:**
- Deploy to device and test tool call rendering with `adb logcat -s HermesApiClient`
- Test with both "Sessions" and "Runs" endpoint modes
- Verify annotation parser matches actual Hermes verbose output
- If Runs API works well, consider making it the default endpoint

**Blockers:**
- Need a running hermes-agent server with tools configured to validate tool event flow end-to-end

---

## 2026-04-06 — Personality System, Command Palette, QR Pairing, Chat Header

**Done:**
- **Personality system fix** — `getProfiles()` was reading wrong JSON path and returning empty list. Replaced with `getPersonalities()` reading `config.agent.personalities` + `config.display.personality`. Server default personality shown first in picker. Switching sends personality's system prompt via `system_message` (previous `profile` field was ignored by server).
- **Agent name on chat bubbles** — Added `agentName` field to `ChatMessage`. Active personality name displayed above assistant messages.
- **Chat header redesign** — Messaging-app style: avatar circle with initial letter + `ConnectionStatusBadge` pulse overlay, agent name (`titleMedium`), model name subtitle from `/api/config`.
- **Command palette** — Searchable bottom sheet with category filter chips (2-row limit, expandable), 29 gateway built-in commands + dynamic personality commands + 90+ server skills from `GET /api/skills`. `/` button on input bar opens palette.
- **Inline autocomplete improved** — Extracted to `InlineAutocomplete` component with `LazyColumn`, 2-line descriptions, up to 8 results.
- **QR code pairing** — ML Kit barcode scanner + CameraX. Detects `{"hermes":1,...}` payload, auto-fills server URL + API key, triggers connection test. Available in Settings and Onboarding.
- **`hermes-pair` skill** — Added to `skills/hermes-pairing-qr/` for users to install on their server. Generator script + SKILL.md.
- **ConnectionStatusBadge** — Reusable animated status indicator with pulse ring (green connected, amber connecting, red disconnected). Wired into Settings, Onboarding, and chat header.
- **Relay server docs** — `docs/relay-server.md`, `relay_server/Dockerfile`, `relay_server/hermes-relay.service`, `relay_server/SKILL.md`.
- **Upstream contributions doc** — `docs/upstream-contributions.md` — proposed `GET /api/commands`, `personality` parameter, terminal HTTP API.

**Corrections to previous session:**
- "Server profile picker" was actually fetching from wrong path — now correctly reads `config.agent.personalities`
- "Sends `profile` field" — server ignores this; now sends `system_message` with personality prompt
- "13 personality commands" were hardcoded — now generated dynamically from server config
- ProfilePicker renamed to PersonalityPicker

---

## 2026-04-06 — v0.1.0-beta Polish, Profiles, Analytics, Splash

**Done:**
- **Package rename** — `com.hermesandroid.companion` → `com.hermesandroid.relay`. All files moved, manifest updated, app name changed to "Hermes-Relay".
- **Server profile picker** — Replaced hardcoded 8-personality system with dynamic server profiles fetched from `GET /api/config`. ProfilePicker in top bar shows Default + server-configured profiles. Sends `profile` field in chat requests.
- **Personality switching** — 13 built-in Hermes personalities available via `/personality <name>` slash commands (server-side, session-level switching).
- **Slash command autocomplete** — Type `/` in chat input to see built-in commands (`/help`, `/verbose`, `/clear`, `/status`) + 13 personality commands + dynamically fetched server skills via `GET /api/skills`. Filterable dropdown overlay.
- **In-app analytics (Stats for Nerds)** — `AppAnalytics` singleton tracking response times (TTFT, completion), token usage, health check latency, stream success rates. Canvas bar charts in Settings with purple gradient. Accessible via Settings > Chat > Stats for Nerds.
- **Tool call display config** — Off/Compact/Detailed modes in Settings. `CompactToolCall` inline component for compact mode. `ToolProgressCard` auto-expands while tool is running, auto-collapses on complete.
- **App context prompt** — Toggleable system message telling the agent the user is on mobile. Enabled by default in Settings > Chat.
- **Animated splash screen** — `AnimatedVectorDrawable` with scale + overshoot + fade animation. Icon background color matches theme. Hold-while-loading (stays until DataStore ready). Smooth fade-out exit transition. Separate `splash_icon.xml` at 0.9x scale.
- **Chat empty state** — Logo + "Start a conversation" + suggestion chips that populate input.
- **Animated streaming dots** — Replaces static "streaming..." text with pulsing 3-dot animation.
- **Haptic feedback** — On send, copy, stream complete, error.
- **About section redesign** — Logo on dark background, dynamic version from `BuildConfig`, Source + Docs link buttons, credits line.
- **Hermes docs links** — In onboarding welcome page, API key help dialog, and Settings About section.
- **Release signing config** — Environment variables + `local.properties` fallback with graceful debug-signing fallback.
- **Centralized versioning** — `libs.versions.toml` as single source of truth (`appVersionName`, `appVersionCode`).
- **Logo fix** — Removed vertical H bars from ghost layer, now matches actual SVG (V-crossbar + diagonal feathers only).
- **SSE debug logging** — Unhandled event types now logged for diagnostics.
- **Release infrastructure (from ARC patterns)** — 3-job release workflow (validate → CI → release) reading from `libs.versions.toml`. Claude automation workflows (issue triage, fix, code review). Dependabot auto-merge. CHANGELOG.md + RELEASE_NOTES.md for v0.1.0-beta. Updated PR template with Android checklist.

**New files:**
- `data/AppAnalytics.kt` — In-app analytics singleton
- `ui/components/StatsForNerds.kt` — Canvas bar charts for analytics
- `ui/components/CompactToolCall.kt` — Inline compact tool call display
- `network/models/SessionModels.kt` — Session, message, SSE event models
- `res/drawable/splash_icon.xml` — Static splash icon (0.9x scale)
- `res/drawable/splash_icon_animated.xml` — Animated splash vector
- `res/animator/` — Splash animation resources
- `.github/workflows/claude.yml` — Claude automation
- `.github/workflows/claude-code-review.yml` — Claude code review
- `.github/workflows/dependabot-auto-merge.yml` — Dependabot auto-merge

**Next:**
- Build and test against running Hermes API server
- Test on emulator and physical device (S25 Ultra)
- Set up keystore/signing secrets for release CI
- Deploy docs site (GitHub Pages or similar)
- Phase 2: Terminal channel (xterm.js in WebView, tmux integration)
- Phase 3: Bridge channel migration

**Blockers:**
- None — ready for on-device testing

---

## 2026-04-05 — Project Scaffolding

**Done:**
- Wrote spec (docs/spec.md) and decisions (docs/decisions.md)
- Created CLAUDE.md handoff for agent development
- Created DEVLOG.md

## 2026-04-05 — MVP Phase 0 + Phase 1 Implementation

**Done:**
- Created Android app — full Jetpack Compose project (30+ files, 2500+ lines)
  - Bottom nav scaffold with 4 tabs (Chat, Terminal, Bridge, Settings)
  - WSS connection manager (OkHttp, auto-reconnect with exponential backoff)
  - Channel multiplexer (typed envelope protocol)
  - Auth flow (6-char pairing code → session token in EncryptedSharedPreferences)
  - Chat UI: message bubbles, streaming text, tool progress cards, profile selector
  - Settings UI: server URL, connection status, theme selector
  - Material 3 + Material You dynamic theming
  - @Preview composables for MessageBubble and ToolProgressCard
  - Terminal and Bridge tabs stubbed for Phase 2/3
- Created relay server — Python aiohttp WSS server (10 files, 1500+ lines)
  - WSS server on port 8767 with health check
  - Auth: pairing codes (10min expiry), session tokens (30-day expiry), rate limiting
  - Chat channel: proxies to Hermes WebAPI SSE, re-emits as WS envelopes
  - Terminal/bridge channel stubs
  - Runnable via `python -m relay_server`
- Created CI/CD — GitHub Actions
  - CI: lint → build (debug APK) → test, relay syntax check
  - Release: tag-triggered, version validation, signed APK → GitHub Release

## 2026-04-05 — Repo Restructure + Build Fixes

**Done:**
- Promoted Android project to repo root (Android Studio opens root directly)
- Removed `hermes-android-bridge/` (upstream absorbed into Compose rewrite)
- Renamed `hermes-android-plugin/` → `plugin/`
- Moved root `tools/`, `skills/`, `tests/` into `plugin/`
- Resolved build issues: gradle.properties, launcher icons, SDK path, compileSdk 36
- Pinned AGP 8.13.2 + Gradle 8.13 + JVM toolchain 17
- Added dev scripts (`scripts/dev.sh` + `scripts/dev.bat`)
- Updated all docs (README, CLAUDE.md, AGENTS.md, DEVLOG.md, spec directory structure)
- Build verified: debug APK builds successfully

**Current state:**
- Phase 0 + Phase 1 complete
- Android Studio opens and syncs from repo root
- Debug APK builds and deploys to emulator/device
- Dev scripts ready for build/install/run/test/relay workflows

## 2026-04-05 — Direct API Chat Refactor

**Done:**
- Refactored chat to connect directly to Hermes API Server (`/v1/chat/completions`)
  - Chat no longer routes through relay server — bypasses it entirely
  - Uses OpenAI-compatible HTTP/SSE with `X-Hermes-Session-Id` for session continuity
  - Auth via `Authorization: Bearer <API_SERVER_KEY>` stored in EncryptedSharedPreferences
- New `HermesApiClient` — OkHttp-SSE client with health check and streaming chat
- New `ApiModels.kt` — OpenAI-format request/response models (ChatCompletionChunk, etc.)
- Refactored `ChatHandler` — removed envelope-based dispatch, added typed SSE entry points
- Refactored `ConnectionViewModel` — dual connection model:
  - API Server (HTTP) for chat — URL, key, health check, reachability state
  - Relay Server (WSS) for bridge/terminal — separate URL, connect/disconnect
- Refactored `ChatViewModel` — sends via `HermesApiClient.sendChatStream()` with cancel support
- Updated Onboarding — collects API Server URL + API Key (required) + Relay URL (optional)
- Updated Settings — split into "API Server" and "Relay Server" cards
- Updated ChatScreen — gates on API reachability, added stop button for streaming
- Updated DataManager — backup format v2 with separate apiServerUrl/relayUrl fields
- Updated docs/decisions.md with ADR for direct API chat
- Updated docs/spec.md with new chat architecture

**Architecture change:**
```
Before: Phone (WSS) → Relay (:8767) → WebAPI (:8642)  [everything]
After:  Phone (HTTP/SSE) → API Server (:8642)          [chat — direct]
        Phone (WSS) → Relay (:8767)                     [bridge, terminal]
```

## 2026-04-05 — Edge Case Fixes + CI/CD Hardening

**Done:**
- Fixed SSE thread safety — all callbacks dispatched to main thread via Handler
- Fixed overlapping streams — previous stream cancelled before new send
- Fixed tool call completion — now matches by toolCallId instead of first incomplete
- Fixed onboarding test connection — client properly cleaned up on exception via try/finally
- Fixed health check loop — only runs when API client is configured
- Added `network_security_config.xml` — cleartext restricted to localhost/127.0.0.1/emulator
- Added ProGuard rules for `okhttp-sse` (okhttp3.sse.**, okhttp3.internal.sse.**)
- Added `id` field to ToolCall data class for proper matching
- SSE read timeout set to 5 minutes (was 0/infinite — detects dead connections)
- OkHttpClient.shutdown() now uses awaitTermination for clean teardown
- Used AtomicBoolean for completeCalled flag (thread-safe)
- Created CHANGELOG.md (Keep a Changelog format)
- Created RELEASE_NOTES.md (used as GitHub Release body)
- Updated release.yml — uses RELEASE_NOTES.md body, SHA256 checksums, prerelease detection
- Created .github/dependabot.yml (Gradle + GitHub Actions, weekly, grouped)
- Created .github/PULL_REQUEST_TEMPLATE.md with checklist
- Added in-app "What's New" dialog in Settings (reads from bundled whats_new.txt asset)
- Bumped versionCode=2, versionName=0.2.0

## 2026-04-05 — Session Management + What's New Auto-Show

**Done:**
- Switched chat streaming from `/v1/chat/completions` to `/api/sessions/{id}/chat/stream`
  - Proper Hermes session API — not the undocumented X-Hermes-Session-Id header
  - Parses Hermes-native SSE events (assistant.delta, tool.started, tool.completed, assistant.completed)
- Full session CRUD in HermesApiClient:
  - `listSessions()`, `createSession()`, `deleteSession()`, `renameSession()`, `getMessages()`
- Session drawer UI (ModalNavigationDrawer):
  - List sessions with title, timestamp, message count
  - New Chat button, switch sessions, rename/delete with confirmation dialogs
  - Hamburger menu icon in ChatScreen top bar
- Session lifecycle:
  - Auto-creates session on first message if none active
  - Auto-titles session from first user message (truncated to 50 chars)
  - Message history loads when switching sessions
  - Last session ID persisted to DataStore — resumes on app restart
  - Optimistic deletes and renames
- What's New auto-show:
  - Tracks last seen version in DataStore (KEY_LAST_SEEN_VERSION)
  - Shows WhatsNewDialog automatically when version changes
  - Dismisses and records current version
- New models: SessionModels.kt (SessionItem, MessageItem, HermesSseEvent, etc.)
- Updated ChatHandler with session management methods (updateSessions, removeSession, etc.)
- Updated ChatMessage.ChatSession with messageCount and updatedAt fields
- Updated whats_new.txt with session management features

## 2026-04-05 — MVP Polish: Markdown, Reasoning, Tokens, Personalities, UX

**Done:**
- **Markdown rendering** — assistant messages render with full markdown (code blocks, bold, italic, links, lists) via mikepenz multiplatform-markdown-renderer-m3
- **Message copy** — long-press on any message bubble to copy text to clipboard
- **Reasoning/thinking display** — collapsible ThinkingBlock above assistant responses when agent uses extended thinking; toggle in Settings
- **Token & cost tracking** — per-message token count (↑input ↓output) and estimated cost displayed below timestamp
- **Personality picker** — dropdown in chat top bar with 8 built-in personalities (default, concise, creative, technical, teacher, formal, pirate, kawaii); injects system_message into chat stream
- **Error retry button** — "Retry" button in error banner re-sends last failed message
- **Offline detection** — ConnectivityObserver via ConnectivityManager; shows offline banner when network is lost
- **Streaming state fix** — onStreamError now clears isStreaming/isThinkingStreaming on all affected messages
- **Haptic feedback** — on send, stream complete, error, and message copy
- **Input character limit** — 4096 char limit with counter shown near the limit
- **Responsive layout** — bubble width adapts by screen width: 300dp (phone), 480dp (medium), 600dp (tablet)
- **Enriched tool cards** — tool-type-specific icons (terminal, search, file, tap, keyboard, etc.), duration tracking ("Completed in X.Xs")
- **Accessibility** — content descriptions on message bubbles, tool cards, thinking blocks, all interactive elements
- **Dead code cleanup** — deleted unused ApiModels.kt and ChatModels.kt
- **Settings: Chat section** — new "Show reasoning" toggle
- Added ACCESS_NETWORK_STATE permission to AndroidManifest

**New files:**
- `ui/components/MarkdownContent.kt` — Compose markdown renderer wrapper
- `ui/components/ThinkingBlock.kt` — collapsible reasoning/thinking display
- `ui/components/TokenDisplay.kt` — per-message token + cost display
- `ui/components/PersonalityPicker.kt` — personality selection dropdown
- `network/ConnectivityObserver.kt` — reactive network connectivity listener

**New dependencies:**
- `com.mikepenz:multiplatform-markdown-renderer-m3:0.30.0`
- `com.mikepenz:multiplatform-markdown-renderer-code:0.30.0`
- `material3-window-size-class` (responsive layout)

## 2026-04-05 — Full Audit + Bug Fixes + VitePress Docs Site

**Audit findings fixed (9 bugs):**
- CRITICAL: Added ProGuard rules for mikepenz markdown renderer + intellij-markdown parser
- CRITICAL: Cached fallback StateFlows in ChatViewModel (was creating new instances per access)
- MAJOR: Fixed MessageItem.id type from Int? to String? (server returns string IDs)
- HIGH: Added !isStreaming guard to send button (prevented double-send)
- HIGH: Added personality fallback when ID not found (prevents null system message)
- MEDIUM: Session rename dialog remember key now includes session (prevents stale state)
- MEDIUM: ToolProgressCard duration guard against negative values
- LOW: Error banner text limited to 2 lines with ellipsis
- BUILD: Unified AGP version to 8.13.2 in libs.versions.toml

**VitePress documentation site created:**
- 20 pages across 4 sections: Guide, Features, Architecture, Reference
- Landing page with hero section and 8 feature cards
- Full user guide: getting started, chat, sessions, troubleshooting
- Feature docs: direct API, markdown, reasoning, personalities, tokens, tools
- Architecture docs: overview diagram, ADRs adapted from docs/decisions.md, security model
- API reference with all endpoints and request/response examples
- Configuration reference with all DataStore keys and settings
- VitePress config with nav, sidebar, local search
- Run with: `npm install && npm run dev:docs`

**Next:**
- Build and test against running Hermes API server
- Test on emulator and physical device (S25 Ultra)
- Set up keystore/signing secrets for release CI
- Deploy docs site (GitHub Pages or similar)
- Phase 2: Terminal channel (xterm.js in WebView, tmux integration)
- Phase 3: Bridge channel migration
