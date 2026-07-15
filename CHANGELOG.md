# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

- **Relay follows Hermes' sticky active profile.** The advertised Server default identity, model, SOUL, and profile API metadata now come from the profile selected by Hermes' `active_profile` marker instead of always describing the root profile.
- **Running Android chats survive session switching.** On the upstream Gateway path, opening another chat, profile, draft, or Thread now detaches the visible stream without interrupting Hermes. Each running session keeps its own durable UI checkpoint, reconnects the shared event socket across route loss, and reattaches through `session.activate`/`session.resume` when selected again. SSE fallback remains intentionally single-stream and cancels on navigation.

## [0.4.0-alpha.2] - 2026-07-13

### Added

- **Desktop chat can use Relay typed streaming over WSS.** The opt-in `--relay-chat` mode sends `chat.send`, renders typed `stream.event` v1 assistant/tool/artifact/memory/skill/error lifecycles, de-duplicates reconnect events, and preserves the existing gateway chat path as the default.
- **Pending computer-use grants are manageable from the CLI.** `hermes-relay grants` lists and interactively approves or rejects local grant-bridge requests, with explicit `approve`, `reject`, and JSON forms for scripts.
- **Desktop use has a durable CLI control plane.** `hermes-relay computer-use` persists enablement, reports daemon and grant state, and cancels active task-scoped grants through the local daemon bridge.

### Changed

- **The optional Windows systray is a native context menu for the CLI.** The WebView dashboard, embedded terminals, overlays, chat, sessions, plugins, voice, and settings windows were removed. The sub-megabyte tray now invokes the single installed CLI for TUI, pairing, daemon control, grants, audit, and logs.
- **Systray daemon controls are state- and privilege-aware.** The menu cross-checks PID liveness, identifies User versus Administrator daemons, disables invalid lifecycle actions, shows pending-grant counts and version metadata, toggles sign-in startup, and requests UAC only for an explicit elevated daemon start or restart.
- **Systray desktop-use controls preserve safety across restart and elevation.** The menu enables or disables the persistent capability, displays active grant mode and expiry, raises a native pending-approval alert, supports immediate cancellation, and warns while Administrator input authority is active.
- **CLI and tray releases use one synchronized version contract.** A single npm lifecycle keeps package, compiled CLI, Cargo, and installer metadata aligned; local verification and tag CI reject drift, off-main release tags, and untested CLI changes before publishing.

### Fixed

- **Compiled CLI diagnostics report the physical executable.** `hermes-relay doctor` no longer mistakes Bun's virtual embedded path for the installed binary, so PATH and install-directory checks describe the executable that actually launched.

## [1.4.4] - 2026-07-12

### Added

- **Android adds AI-assisted Spanish.** A repeatable translation harness and freshness checks keep catalogs structurally complete while tracking fluent review separately.
- **Diagnostics exposes the Relay contract.** A manual refresh reports the installed plugin version, protocol version, capability count, profile enablement state, and last-check time; shared issue reports include sanitized Android and device metadata.
- **What’s New links to complete release history.** The polished modal now provides direct access to every bundled version, with large-text screenshot coverage.

### Fixed

- **Profile operations stay inside the selected Hermes profile.** Session list, history, rename, delete, and in-flight recovery no longer fall through to the default database after a scoped failure; optimistic writes roll back and repeated recovery failures stop cleanly.

## [1.4.3] - 2026-07-11

### Added

- **Language switching is available inside the app.** Settings → Appearance now offers System default, English, and Simplified Chinese, stays synchronized with Android's per-app language setting, and persists the choice on Android 12 and lower.

### Fixed

- **Release builds reject unsupported collection APIs.** CI now scans Kotlin sources and final minified APK bytecode for Java 21 list endpoint calls that can crash on Android versions before API 35.

## [1.4.2] - 2026-07-11

### Added

- **Android now supports Simplified Chinese.** Chat, Manage, Voice, connection setup, settings, diagnostics, notifications, accessibility labels, and both product flavors follow the device language, with Android per-app language discovery on supported versions.
- **Localization is contributor-ready.** CI enforces resource, plural, and format-argument parity; translated README and VitePress entry points establish a repeatable path for adding languages without duplicating fast-moving technical references.

### Fixed

- **Connection scan and queued-message counts use proper plurals.** Count formatting no longer depends on English-only suffix arguments and cannot fail when a locale needs a different plural structure.

## [1.4.1] - 2026-07-11

### Added

- **Background work is visible in Standard Chat.** A live process strip opens a mobile process sheet with running or recent state, output, elapsed time, Stop, and Dismiss controls. It remains compatible with older Hermes servers that do not expose process details.
- **Background work has a clearer Chat home.** Realtime work appears as a titled task card with working, waiting, delivery, and completion states, queued work, and an expandable tool timeline.
- **Multi-image messages open as galleries.** Adjacent images render in a compact grid and open at the selected image in a swipeable viewer while preserving sensitive-media reveal and original-file actions.
- **Voice gains commands and presets.** Spoken commands can stop speech, cancel background work, pause or resume listening, repeat a result, or start Standard voice chat. Hands-free, Low latency, Careful tools, and Quiet presets tune existing interaction settings.

### Changed

- **Streaming Chat content stays steadier and more readable.** Settled prose and headings adopt final Markdown styling during generation, wide tables scroll with readable columns, the thinking indicator respects system motion and TalkBack settings, and the jump-to-bottom control counts unread messages.
- **Offline Demo mode no longer starts Voice.** The mic action now explains locally that a Hermes connection is required.

### Fixed

- **An in-flight Chat turn survives reopening the app.** Session-backed replies restore partial text, live reasoning, lifecycle status, tool/subagent cards, background-task state, and unanswered approval or clarification cards. Current Hermes gateways reattach to the same running turn; older or finished sessions reconcile from history without duplicating the prompt or losing the final answer.
- **Realtime Agent delivery is protected.** Hermes results use exact provider speech where supported, delivery validation, generation-safe confirmation, and a single relay-TTS fallback if the provider closes or rejects delivery. Voice commands no longer leave synthetic cancellation turns or mute a later background answer.
- **Standard Chat receives background-process completions automatically.** When Hermes completes detached work and starts a follow-up turn on the originating Gateway session, Android shows the unsolicited assistant stream in the open conversation and reconciles history after a cold reconnect. The synthetic process prompt is rendered as a compact process notice rather than a user-authored message.

## [1.4.0] - 2026-07-09

### Added

- **Android model pickers can refresh the server catalog.** Chat's model sheet and Manage's main/profile model dialogs now expose upstream's explicit **Refresh Models** action, so dynamic/custom provider model lists can be reloaded on demand without making every picker open probe providers.
- **Server-backed session cleanup plumbing.** The dashboard client now supports single-session export, the upstream `/api/sessions/prune` route with a mandatory dry-run preview before destructive apply, plus soft archive/restore helpers and an `archived` session-list filter for the Manage surface.
- **Notification triggers MVP.** Settings → Notifications now has explicit opt-in proactive rules for the Notification companion: match by app package plus optional title/text filters, post a safe local "Ask Hermes?" prompt, show the latest trigger activity, and pause everything instantly with a kill switch.
- **Android bridge: multi-device targeting.** The relay can keep multiple Android bridge clients connected at once, route commands by `device` selector (`phone`, `pixel`, `fold`, `boox`, `note`, `notemax`, `tablet`, or device ID), expose `/bridge/devices` and `/bridge/select-active`, and advertise an optional `device` argument on the `android_*` tool schemas.
- **Voice: a second long request gets queued, not refused.** Ask for another long task while one is already running in the background and it's now queued (up to three) and starts automatically when the current one finishes — with a short spoken transition. The task card shows "+N queued", and cancelling the current task clears the queue.
- **Voice: background answers start speaking sooner and can never be silently lost.** The spoken summary now streams as it's generated (it used to be held until fully complete — a noticeable dead gap, then the whole answer at once). Delivery is verified two ways: the summary must actually reflect the answer's content (not just avoid known filler phrases), and if no spoken delivery lands within 30 seconds the answer is posted as text instead of vanishing.
- **Voice: tap the finished-task card to hear the answer again.** After a background task's card settles to "finished," tapping it replays the delivered answer. The card also now shows in the compact voice view (it previously existed only in the full-screen layout), a "Drafting the answer…" status appears as the reply is being composed, and leaving voice mode with a task still running leaves a note in chat so the work stays visible.
- **Voice: quick questions answered while a background task runs.** Realtime voice used to refuse *any* second request while a long task ran in the background — even a two-second lookup. A quick second ask is now answered inline on a side session (within the same few-second window that decides backgrounding); anything that turns out to be long still gets the "a task is already running" answer, and the running task is never disturbed.
- **Voice: the background-task card no longer vanishes mid-answer.** The card used to disappear the instant the spoken answer started (exactly when the waveform returned), reading as the task being lost. It now settles to a "Background task finished." state, lingers for a few seconds while the answer plays, then dismisses itself — and its ✕ during that settled state just dismisses the card instead of sending a cancel.
- **Voice: the "Thinking" pill no longer spins forever.** The server streams its drafting text as an internal pseudo-tool that never reports completion, and the app rendered it as a live tool pill — which then ran indefinitely in both chat and the voice overlay. Internal tool events no longer become pills (their text still feeds the thinking trace).
- **Voice: background-task answers can't be lost to a stray cancel.** Tapping cancel/stop after a background task had already finished used to mark the finished run "cancelled" — losing the answer that was about to be spoken. Cancel now only cancels a run that's actually still running; stopping the current speech works as before.
- **Voice: no more spoken run IDs or phantom queue state.** The realtime voice model no longer reads 32-character run IDs aloud after starting a background task (identifiers stay out of everything it's asked to speak), no longer claims a request was queued unless the relay accepted it, and a completed task's answer is spoken directly — deferral filler like "one moment while I look that up" in place of a finished result now triggers the fallback that speaks the real answer.
- **Voice: finished-task answers keep the realtime voice.** A completed background task's answer is now spoken by the same realtime voice you've been talking to — read word for word from the authoritative Hermes answer — instead of switching to the standard TTS voice mid-conversation. The answer always lands: if the realtime model goes off-script or the provider connection drops, standard TTS speaks it, and if you start talking mid-delivery it's posted as text instead of interrupting you. The "When the answer is ready" setting keeps its four modes (Exact / Summary / Notify / Show), now explained behind an info icon in Voice Settings.
- **Voice: realtime models refreshed.** OpenAI realtime now defaults to `gpt-realtime-2.1` (with the cheaper `gpt-realtime-2.1-mini` selectable), the versioned `grok-voice-think-fast-1.0` pin is available alongside xAI's `grok-voice-latest` alias, and session logs record which model the provider *actually* served — so provider-side alias moves no longer happen invisibly.
- **Voice: session logs clean up after themselves.** Realtime voice session logs are swept after 14 days by default (`realtime_voice.run_retention_days`, 0 disables), and the per-response TTS audio capture is now opt-in debug tooling (`debug_audio_tap`) instead of an always-on multi-MB tap.
- **Voice: one-command delivery health report.** `python -m plugin.relay.realtime_agent.report` summarizes recent voice deliveries — how many were spoken by the realtime voice vs fell back to TTS or text, and why — for quick health checks after live testing.

### Changed

- **Bootstrap compatibility layer slimmed to true gaps.** The optional compatibility hook no longer injects session CRUD/messages or the legacy skills list — current Hermes serves those natively; it now covers only surfaces with no native replacement yet (session search, memory, legacy skill detail/toggle, config, available-models, and the slash-command middleware). Older pre-session-API Hermes builds degrade to the standard completions/runs chat paths.
- **Dependency floor: aiohttp ≥ 3.14.1.** Raised from 3.9 across plugin requirements and package metadata to the patched line covering the 2026 aiohttp security advisories.

### Fixed

- **Realtime voice recovers after background route loss.** A recorded turn now waits for a relay-confirmed resumed socket, retains unacknowledged follow-up PCM for replay, and reports transport rejection instead of sitting on a dead persistent connection. Resume handshakes are coalesced, and the relay requires a valid resume claim before replacing the active phone socket, so a slower stale connection cannot detach background-result delivery. Long-lived sessions start their bounded retry window when the route actually drops instead of at voice-mode entry, and a bare socket open cannot reset it. Late callbacks from a retired session are ignored. Exiting voice mode clears its detached reconnect and confirmation state before another session opens; rejected or unacknowledged cancels no longer leave an undismissable background-task pill. Provider transcription no longer impersonates active microphone capture, Stop settles the local turn even when the route is gone, and provisional `Listening...` / `Still working...` rows cannot remain stuck in chat.
- **xAI exact background answers bypass model deferral.** Non-structured **Exact** deliveries now use xAI's provider-native forced speech event, preserving the selected realtime voice and normal assistant history while speaking the authoritative Hermes answer without asking the model to follow a read-verbatim prompt. Structured results and summary modes still use natural model summarization, and the validator plus standard-TTS fallback remain as safety nets.
- **Background voice handoffs no longer repeat themselves.** If the realtime provider already spoke an acknowledgement before calling Hermes, promotion keeps that first line and suppresses the redundant "running in the background" follow-up; silent tool calls still receive the configured spoken handoff. Provider protocols that report both response creation and output-item creation now also produce one client `response.started` event instead of two.
- **Realtime voice model and voice picks now apply to the next session.** Voice Settings persists the selected Realtime Agent model and voice per connection/profile and sends both when opening a session, so choosing a pinned model immediately controls the next session instead of requiring **Save realtime agent** to rewrite the relay config. The active voice UI reflects the override, changing it retires any prewarmed session, and the choice survives an app restart.
- **Fresh realtime sessions emit one ready event.** Android's required `session.start` acknowledgement no longer causes the relay to send a second `voice.session.ready`, avoiding duplicate event IDs and duplicate session-ready telemetry on every new voice conversation.
- **Relay media can no longer serve credential files.** `/media/by-path` now always blocks paths that resolve into credential or system locations (`~/.hermes/.env`, `auth.json`, `config.yaml`, OAuth/MCP token stores, `pairing/`, `~/.ssh`, and similar) even in the default permissive mode — mirroring upstream Hermes' media-delivery hardening — so a prompt-injected `MEDIA:` marker can't deliver live secrets to a paired phone. Symlinks are resolved before the check, and the relay's own QR-signing secret and session-token store are covered too.
- **Long agent turns no longer die or duplicate at the transport.** Gateway chat (Android and the desktop CLI) now gives `prompt.submit` up to 30 minutes to acknowledge — matching upstream desktop and the server's own turn ceiling — instead of short generic RPC timeouts that could falsely fall back to SSE (duplicating the turn on Android) or kill a legitimately long deep-reasoning turn. Turn liveness is governed by idle-progress watchdogs (no events at all for a stretch), never a hard cap while output is still streaming.
- **Manage → Models keeps providers that still need keys.** Newer Hermes hides unconfigured providers from the model catalog unless a management UI opts in; Android Manage now opts in and keeps rendering greyed provider rows with their key-setup guidance on both old and new servers. In-chat model picking is unchanged (configured providers only).
- **Phone-local context actually reaches the server on fallback chat paths.** The sessions/runs streaming payloads carried voice-intent traces, card dispatches, and attachments in fields the server never reads — silently dropping them. That context now rides channels the server actually consumes (a per-turn context digest, real history fields where they exist, inline images on the completions path), and any attachment with no supported channel is reported instead of silently discarded.
- **Relay plugin works under the native `hermes plugins install` path.** The plugin's runtime imports assumed the repo's editable layout, so upstream's native installer (which loads plugins under its own package namespace) broke `hermes relay start` and `hermes pair` with `ModuleNotFoundError: No module named 'plugin'`. All runtime imports are now package-relative, the dashboard module boots correctly when the upstream web server loads it standalone, and `hermes relay doctor` now exercises the real import chain so this class of breakage can't pass doctor again. (#165)
- **Installer handles modern venv layouts.** `install.sh` now autodetects the classic venv, uv-managed `.venv`, and containerized layouts — and everything it generates (the systemd unit and all four command shims) points at the interpreter it actually detected instead of a hardcoded classic path. On immutable container images it steers to the native install path with a clear message instead of dying mid-run. (#165)
- **Doctor catches dashboard URLs pointed at the wrong Hermes surface.** `hermes relay doctor` now distinguishes the dashboard/Manage surface from an API-server/headless backend URL and tells operators to use `hermes dashboard` when a configured dashboard URL is actually pointing at `hermes serve` / the API server.
- **Doctor and installer catch stale duplicate plugin copies.** The gateway plugin loader picks a discovered plugin by manifest name, so a second directory declaring `name: hermes-relay` (a leftover backup copy or a stray extra install) could win and make the gateway load stale code — silently ignoring every later deploy. `hermes relay doctor` now warns when more than one directory under the plugins dir declares the same plugin name, and `install.sh` removes any such duplicate so only the canonical plugin symlink remains.
- **Crash-safety on Android 14 and earlier.** Built against SDK 35, Kotlin's `removeFirst()`/`removeLast()` resolve to the new Java `List` methods that don't exist below Android 15, crashing older devices. All such calls in the app are now `removeAt(...)`, and Tink (pulled in by encrypted storage) is pinned ahead of the transitive version whose `HybridConfig` tripped the same Google Play pre-launch check.
- **No crash when a relay address is malformed.** A corrupt or hand-edited pairing address with an invalid host could crash the app the moment it opened the relay connection (the connection is built on a background thread, so the error escaped uncaught). A bad relay address is now handled as a normal connection failure — shown as disconnected with a "re-pair to refresh" note — instead of crashing. The same guard now also covers the relay's media, session, and voice HTTP calls. (relay half of #131)
- **Voice: cleaner error recovery.** A failed or timed-out voice turn no longer shows the same error twice (the top overlay banner and a duplicate bottom banner) and can now be **dismissed**, not just retried — so a stuck error state can't block the screen.
- **Voice: fallback-spoken answers no longer play into a frozen overlay.** When an answer is delivered by the standard TTS fallback (or replayed from the finished-task card), the voice screen now shows the waveform and the answer text while it speaks — previously it sat on "Thinking" with no visuals even though audio was playing.
- **Voice: a quiet realtime session no longer dies with a raw provider error.** xAI ends a realtime conversation after 900 seconds of inactivity, and no keepalive traffic resets that timer — so a voice session left open through a long background task (or simply left open) died with a raw provider error. That provider timeout is now treated as routine expiry: the session ends cleanly with no error banner, and your next voice turn transparently opens a fresh provider conversation that picks up from the same durable Hermes chat session.
- **No crash when a malformed server address reaches a chat send.** The three streaming chat paths built their HTTP request before any error handling, so a corrupt or hand-edited API URL could throw instead of failing the turn gracefully. They now surface "Invalid server address — edit the connection's API URL or re-pair" through the normal in-chat error channel (closes the remaining #131 crash-class gap).
- **Demo mode: typing a message now gets an honest reply.** Sending a message in the offline demo used to do nothing (the composer silently ignored it, reading as broken). The demo now echoes your message and answers with a short notice explaining it's an offline sample, pointing at the Connect action to chat for real.
- **Voice: realtime conversations reliably reach your chat history.** Turns the realtime voice model answers directly (without calling Hermes) are folded into the chat session on your next message — but on the default gateway connection that hand-off could be deferred indefinitely, so the agent never learned what was said in voice. The turn that carries them now routes so the sync actually lands. Synced voice turns also render cleanly when a chat reloads: a quiet "Realtime Agent" chip instead of a raw provenance footnote, and no more duplicated voice exchange after the sync.

## [1.3.0] - 2026-07-06

### Added

- **Voice settings: edit your server's voice engine.** Voice settings now has a **Server voice config** section that reads and writes the host's text-to-speech and speech-to-text settings — provider, voice, model, language, and per-provider options — over the dashboard, the same config the official desktop app edits. It includes an **ElevenLabs voice picker** that lists the voices available on your server's ElevenLabs key (and tells you when no key is set). Works on the no-plugin (Standard) path; sign in to Manage to use it.
- **Desktop CLI: `hermes-relay audit`.** Shows what the remote agent has actually run on this machine through the desktop tools — tool, status, and a short detail per call — read from a local log, no network or auth. Answers "what did the agent just do?" at a glance.
- **Desktop CLI: `hermes-relay relay`.** Inspect the relay server itself: `relay info` (version, uptime, sessions — on the relay host), `relay security` (runtime auth toggles), `relay context` (audit the system-prompt context the relay injects into the agent, which works from a remote machine with your session), and `relay queue` (list — or `--clear` / `--cancel <id>` — the messages your agent queued for an offline phone; on the relay host).
- **Desktop CLI: background daemon.** `hermes-relay daemon start` runs the headless tool router in the background (no console window, survives closing the terminal), with `daemon stop` and `daemon status` to manage it. `daemon status` reports state, uptime, relay, and advertised-tool count; bare `daemon` still runs in the foreground. Logs go to `~/.hermes/daemon.log`.
- **Desktop CLI: per-command help.** Every subcommand now answers `--help`, and `devices`/`sessions`/`plugins`/`voice`/`relay` print their own usage (sub-commands, flags, examples) instead of a terse "unknown sub-verb".
- **Desktop CLI: startup banner.** A slim "Hermes Relay" wordmark shows atop `--help`, the first-run welcome, and the chat REPL — and `hermes-relay logo` prints it on demand. Suppressed for piped/`--json`/`--no-color` output.
- **Animated "thinking" indicator.** While a reply streams, the in-bubble working indicator can now be a small dot-matrix animation instead of the three dots. Pick a motion (Wave, Pulse, Bounce, Sparkle) and a color (match-text or a brand accent) in Chat settings, with a live preview. It follows light/dark and your app theme, and goes static when animations are turned off.
- **Proactive messages from the agent to your phone.** Your Hermes agent can reach out to the paired phone on its own — via `send_message target=phone` or a cron `deliver=phone`. Messages surface as a system notification, collect in a dedicated Hermes inbox, and can be injected into the active chat to continue the conversation (selected per message). Off by default and gated on pairing: nothing is pushed unless you enable it on the server (`PHONE_ENABLED`) and opt in on the phone ("Let Hermes message me"). Delivered over the existing relay connection through the upstream platform-plugin API (no fork).
- **Reply to your agent's messages (two-way).** A proactive message is now a conversation, not a one-way ping: reply straight from the notification (inline Reply) or from the Hermes inbox, and your answer goes back to the agent and continues the same thread. The phone behaves like any other Hermes messaging platform — the reply arrives as an inbound message the agent processes and answers. Rides the same paired relay connection; no extra setup beyond the proactive opt-in above. If your phone is offline when the agent answers, the message is queued and delivered when you reconnect — not lost.
- **Pick your font.** A Font picker in Appearance sets the app-wide typeface — **Inter** (the new default), **Nunito**, or your **system** font — each previewed in its own face and applied instantly across the app, no restart. Code and timestamps stay monospaced. (Bundled faces are SIL OFL.)
- **Quick Controls in Settings.** A Quick Controls card at the top of Settings groups the switches you flip most often — **Persistent connection** and **Turn-complete alerts** — so they're one tap from the Settings root instead of buried in a sub-screen.
- **Connections: a cleaner list and a tabbed detail.** Settings → Connections is now a scannable list — each server shows an **Active** badge and an at-a-glance capability summary (API · Dashboard · Voice · Relay) — and tapping a server opens a focused detail screen with **Overview**, **Routes**, **Advanced**, and **Security** tabs. Rename / re-pair / revoke / remove moved into the detail's **⋮** menu, and **relay sessions** (review and revoke the phones paired with that server) get a clear home under Security.
- **Keep connected through deep sleep (sideload).** When **Persistent connection** is on, Settings offers a one-tap "Allow unrestricted battery" prompt so the connection survives Android's deep-sleep (Doze) — without it, the OS pauses background networking after the screen's been off a while even with a foreground service. (Sideload only; Google Play restricts this permission.)

### Changed

- **Reporting a diagnostic now files the right kind of issue.** The Report button on a diagnostics entry used to turn routine log lines into "[Bug]" GitHub issues with an empty template. Now informational entries first ask "what were you expecting to happen?" and file as a "[Diagnostic]" question, error entries keep the direct bug flow, and every report carries the connection mode you were actually on instead of a placeholder line. (#155, #154, #146)
- **Simpler release downloads.** Each Android release on GitHub now attaches just two files — the tap-to-install sideload APK and the Play Store upload bundle — plus checksums, with the release notes leading with the one file most people want. The extra "parity/testing" artifacts are gone from the release page (still reproducible from the tag via CI). (#144)
- **Clearer, snappier voice capture and playback.** Voice now engages the device's echo-cancellation and noise-suppression while recording (matching the desktop's microphone setup), and requests audio focus before the first reply so the opening words aren't clipped on a cold start. Listening timing also matches the official desktop: auto-stop ~1.25s after you stop speaking (was 3s), give up after 12s with no speech, and cap a turn at 60s.
- **Refreshed chat look.** Message bubbles are wider and denser, each assistant turn shows a small Hermes avatar to its left (once per group), and code blocks are richer — a language label, a copy button, and a clearer inset so fenced code and inline `code` no longer blend into the bubble.
- **Desktop CLI: visual + ergonomics refresh.** A single color theme across the CLI, aligned tables for `devices`/`sessions`, status dots for on/off states, and progress spinners for slow operations (the multi-endpoint pairing probe and the gateway connect) so nothing looks hung. Errors now suggest the fix (e.g. re-pair on auth failure).
- **Desktop CLI: smoother pairing.** The multi-endpoint probe shows per-endpoint progress and latency; a near-expiry session warns before it fails and prints the exact re-pair command; and a bare `ws://host` (no port) defaults to `:8767`.
- **Desktop CLI: voice + consent transparency.** `voice` now surfaces enhanced-voice capabilities (Gemini tone tags / persona, xAI speech tags); the desktop-tool consent prompt is clear that it persists per relay and points at `hermes-relay audit`; and computer-use's observe → grant → act flow is documented in `--help`.
- **Persistent connection (was "keep chat connected").** The background keep-alive and its notification are reframed from a "chat connection" to your overall connection to Hermes — it holds the app's connection open in the background so messages and live features stay responsive, and for relay-paired setups also keeps device control and notification mirroring reachable. The toggle moved out of Chat settings into the new top-level Quick Controls card.
- **Chat is the home; simpler top-level navigation.** The Chat / Manage / Bridge mode strip is gone — Chat is now full-height, and Manage and Bridge are reached from Settings (Settings → Hermes management / Bridge), each with a back arrow to Chat. Terminal and Settings remain quick icons in the chat top bar.
- **Gentler reconnects when your server is unreachable.** After the server has been unreachable for a while, the app stops retrying every ~15 seconds and drops to a slower poll — easier on the battery — and still reconnects immediately the moment the network changes or the server comes back.
- **Connection status stays out of your way.** Connection feedback now sits exactly where it matters and never covers the nav or shifts the screen. Your **agent's** connection shows in the header subtitle under the agent name — it reads *Reconnecting…* / *Connecting…* / *Disconnected* and crossfades back to the model when it recovers, the same place messaging apps put it. The **relay** link (bridge / terminal / voice) shows only as a small amber *Reconnecting…* cue in the bottom status strip, since it doesn't block chat. Returning to the app from the background is now fully silent instead of flashing a misleading "connection changed" for the same connection re-handshaking.
- **Realtime voice: quieter progress.** The periodic spoken status updates during a long task ("Using cronjob…") are now off by default — the agent speaks at the milestones that matter (task started in background, finished, or failed) and the visual progress chip covers the in-between. A server setting brings the timed narration back if you prefer it.
- **Realtime voice: a live background-task chip.** The "working on it" chip in voice mode now actually shows what's happening: the current step ("Running command"), how many steps have finished, and a running timer — with a pulse so you can tell it's alive. It also reads the connection honestly ("Reconnecting — your task is still running" during a blip, "Done — delivering the answer…" while the reply queues up), and a ✕ on the chip cancels the task outright.
- **Realtime voice: snappier long-task handoffs and first turns.** When a clearly long-running tool starts (cron, desktop, browser work), the agent hands the task to the background right away instead of waiting out the full grace period — and the voice session now warms up when you open voice mode, so the first turn skips the connection setup it used to pay.

### Removed

- **Two voice controls that did nothing.** The disabled "Auto-TTS" toggle and the "STT language" picker under "Coming soon" in Voice settings are gone: the official desktop doesn't read every typed message aloud, and speech-to-text language is a server-side setting now editable in the new Server voice config section.

### Fixed

- **Realtime voice: you can keep talking while a background task runs.** Progress updates from a background task were flipping the voice UI back into "Thinking" with a Stop button on every tick, so the mic never came back until the task finished. Progress now feeds only the task chip; the conversation stays open the whole time.
- **Realtime voice: leaving voice mode no longer cancels a running task.** Exiting (or tapping Stop to interrupt speech) used to kill an in-flight background task and could overwrite its already-delivered answer with "Cancelled." in the chat. Exit now detaches — the task keeps running and the result arrives on your next session or as a notification — and a delivered answer always keeps its text (a Stopped badge marks a genuine cancel). The chip's ✕ remains the one deliberate way to cancel.
- **Long answers are no longer lost when the connection drops mid-turn.** On slow local models (or skills that delegate long background work), the phone could drop the stream mid-turn — the server finishes and saves the answer, but the chat sat on "Still working…" forever. The app now detects the dropped stream and quietly re-checks the conversation until the finished answer arrives, then completes the turn normally (with the usual done-notification if you've backgrounded the app). Switching chats or sending something new cancels the wait. (#166)
- **Onboarding slides fit every screen.** Intro slide text could run past the bottom of the screen with no way to scroll on short displays or large font sizes. Slides now scroll when needed and compact their artwork on short viewports, so no setup guidance is unreachable. (#145)
- **Docs: fixed stale setup labels and broken links.** The setup guide referenced a "Vanilla Hermes" button the app hasn't shown since v1.2.2 (it's labeled "Hermes"), several deep links into the getting-started page were dead, and the README under-counted the available phone tools. (docs site)
- **Back button on Manage and Bridge now works.** The back arrow on the Manage ("Hermes management") and Bridge screens did nothing — it tried to jump to Chat in a way that silently no-op'd. Back now reliably returns to the screen you opened it from.
- **Dropped relay connections from a status-report race.** The phone's periodic device-status report could occasionally be sent to the relay *before* the connection had finished authenticating, which made the relay reject the whole connection and forced a reconnect. The app now holds every message until the connection is authenticated, so the handshake always completes first.
- **Fewer needless connection re-checks when switching apps.** Returning to the app after a quick glance at another app no longer triggers a full connection re-probe (and the brief "checking…" flash) when the connection was already healthy — it only re-checks after a longer absence or if something actually looks off.
- **No more scary "server isn't accepting connections" pop-up on first load.** A bare bottom message could flash on cold start while the app was still establishing its first connection (the background session-list load failing before the server was reachable). That state is now shown only by the themed connection banner at the top — the redundant pop-up is suppressed for cold-start/reconnect bootstrapping, while real failures while you're using the app still surface normally.
- **Reconnect loop on remote (Tailscale) connections.** Connecting from off your home network could make chat loop — repeatedly reconnecting before it finally settled — because a brief route-probe miss flipped the active route back to the (unreachable) home address and rebuilt the chat connection against it. The app now keeps the last working route through a transient miss, tolerates a slow first handshake on remote links, and absorbs VPN-interface churn, so a remote connection settles quickly instead of thrashing.
- **Realtime voice: background tasks survive a brief disconnect.** Asking the voice agent to run a longer task in the background no longer loses the result to a momentary network drop — the server keeps the run alive across the reconnect and delivers the answer once you're back, and a task that runs too long is now stopped cleanly instead of hanging silently.
- **Realtime voice: the spoken answer is no longer dropped when a background task finishes.** When the agent completed a longer background task, a harmless internal provider notice was being treated as a fatal error and closed the voice session right as the reply was about to be spoken (surfacing an "xAI realtime error" toast with Retry). Those transient notices no longer end the turn, so the answer is actually spoken.
- **Realtime voice: the answer waits for you instead of playing to a dead connection.** If a background task finishes while your phone is disconnected, the spoken summary is now held and delivered when the voice session reconnects — and the phone keeps retrying that reconnect for several minutes instead of giving up after one attempt. If the voice session is gone for good, the result arrives as a notification instead (the full answer is always in the chat).
- **Realtime voice: asking for a second task while one is running no longer breaks the first.** The agent now tells you the earlier task is still in progress (wait, check status, or cancel) instead of silently losing its result.

## [1.2.6] - 2026-06-27

### Added

- **Session drawer refresh.** A refresh button in the session drawer re-pulls the chat list on demand, so a title the server generates a moment after a turn shows up without waiting for the next reload.

### Changed

- **Calmer connection status.** Transient connection status — reconnecting, checking, LAN↔Tailscale handoffs — now renders as a thin banner at the top that takes its own space (the screen slides down) instead of a card floating over the chat. The floating alert is reserved for persistent errors. Frequent confirmations (copied, profiles updated, profile/personality switches) moved to the same top banner instead of the bottom pop-up.

### Fixed

- **Chats stuck showing "Untitled".** The session drawer no longer overwrites a chat's first-message preview with a blank title when the server hasn't auto-named it yet (and the SSE path never does), so chats stop reading "Untitled"; titles also reconcile once the turn settles. (#133)
- **Rename on a non-default agent profile.** Renaming a chat while a non-default profile is active now persists to that profile's own store instead of the shared one — matching the earlier session-delete fix.

## [1.2.5] - 2026-06-27

### Added

- **Demo mode.** A "Try the demo" option on the setup / Connect screen — and on the empty chat screen if you skip setup — opens an offline preview of the real Chat UI: a sample conversation with Markdown, a tool-progress card, and a rich card, with zero setup and zero network (works in airplane mode). A persistent "Demo mode — sample data, not connected" banner offers a one-tap Connect that opens the real setup wizard; other tabs show a friendly "connect your Hermes server" empty state. Lets a first-run user — or a Play reviewer with no server — see what the app does before connecting.

### Fixed

- **Crash when a non-address is entered as a server URL.** Typing or pasting non-URL text (for example a label, or a line copied from the docs) into the API server or Dashboard URL field could force-close the app on the Manage / sign-in screen: the value was handed to the networking layer as a host, which rejected it with an uncaught error on the main thread. The setup fields now reject anything that isn't a valid host or `http(s)://` URL with an inline error, and the dashboard and voice request paths treat a malformed address as "unreachable" instead of ever crashing. (#131, #132)

## [1.2.4] - 2026-06-25

### Added

- **Connection security indicator.** The chat status chip, the connection card, and the route picker now show at a glance whether your connection is encrypted — 🔒 **Encrypted · TLS**, 🛡️ **Encrypted · Tailscale** (both secure), 🛡️ **Mixed routes**, or ⚠️ **Not encrypted** — and tapping it opens a per-transport breakdown (chat, API, relay tools). A Tailscale/WireGuard route is now correctly shown as encrypted rather than implied insecure. Adds a new "Is my connection secure?" docs page explaining the difference between TLS and overlay (WireGuard) encryption.

### Fixed

- **Crash when a dashboard connection drops mid-check.** A transient network blip on the dashboard session check (e.g. a pooled connection aborting or timing out over Tailscale) could close the app: the check returned a result type but re-threw the network error instead of reporting it, and it surfaced on the main thread. The check now reports the failure cleanly, and the connection probe degrades gracefully instead of ever crashing. (#129)

## [1.2.3] - 2026-06-23

### Fixed

- **Crash on connect over TLS / Tailscale.** Connecting to a server over an encrypted link (Tailscale Serve or public HTTPS) could hard-close the app with `NetworkOnMainThreadException`. Tearing down an HTTP client closed live SSL sockets on the main thread, and a TLS socket close performs a network write — which Android forbids on the main thread. Client shutdown now always closes sockets off the main thread, so connecting over a secured link no longer crashes. (#118, #124; likely the v1.1.0 / Tailscale crash in #70)

## [1.2.2] - 2026-06-22

### Added

- **Diagnostics: status timeline.** Diagnostics now opens full-screen and leads with a top-to-bottom list of subsystem health checks — network, API server, chat transport, pairing, relay, and voice — each with a clear pass / warning / fail state and, when something's wrong, the reason why; tap a failing check for full detail. The recent-activity log stays below it.

### Changed

- **Connections wording simplified.** The default connection is now just "Hermes" (previously "Vanilla" / "Standard Hermes"), and the optional power features are labelled "Relay" / "Relay plugin", across the connection setup, switcher, voice, and permissions screens.
- **Clean chat mode shows more text.** The distraction-free chat view gives its text a noticeably taller, scrollable area instead of capping it near a third of the screen.

### Fixed

- **Deleting a session on a non-default profile now sticks.** Removing a chat while a non-default agent profile was active could leave it on the server, so it reappeared after the list refreshed; the delete is now scoped to the active profile.
- **Session drawer opens on the right profile from a cold start.** When launching with a non-default profile selected, the session list could briefly show the default profile's chats and then snap to the correct ones; it now waits for the profile to resolve and loads the right list directly.

## [1.2.1] - 2026-06-21

### Added

- **Profile lock.** Settings → Profile lock pins the app to a single agent profile and hides the rest from the pickers; the lock screen stays the one place that lists every profile, with a clear notice if the locked profile isn't on the current server.
- **In-app What's New & changelog.** A new Settings entry shows the current and past release notes any time — not just the post-update popup.
- **Diagnostics: tap for detail + report.** Logged errors now carry clean titles and open a detail view with Copy / Share / Create-GitHub-issue (the same flow as crash reports); classified errors across voice, chat, and connection are captured centrally.
- **Update-available nudge.** A dismissable in-app banner when a newer version is live — Google Play In-App Update on Play installs, GitHub Releases on sideload. Per-version dismissal, throttled, never nags.

### Changed

- **Crash reports can be shared without GitHub.** The crash dialog now has a **Share** action alongside Copy and Report, handing the full report to the system share sheet (email, chat apps, notes, Drive). This covers users without a GitHub account and sideload installs that Play vitals never sees. Every outbound path stays user-initiated — nothing is sent automatically.

### Fixed

- **Voice override applies in Auto mode.** A chosen per-profile/enhanced voice now takes effect when the engine is on Auto with the relay paired — previously only "Relay" mode applied it. Per-profile voice settings are also namespaced by connection.
- **Realtime voice "Stop" stops immediately.** Tapping Stop while the agent is speaking now halts realtime playback at once; over-chatty spoken status is throttled; and long background tasks no longer time out the turn (relay keeps the session alive while the task runs).
- **Realtime Agent: brokered Hermes turns no longer fail (relay).** When the Realtime Agent reached back to Hermes for context or tool work, a session-namespace mismatch could make the API Server reject the turn with `session_not_found`. The relay now mints or reuses a valid API Server session and retries once, and reads the API Server's current nested create-session response. Provider-native turns are unaffected.
- **Hold-to-talk no longer releases on accidental drift.** The mic button holds until the finger genuinely lifts, instead of cancelling when it drifts off the button.
- **Voice overlay is readable.** The voice dropdown panel and its status bubbles are opaque (no bleed-through), and the Focus/Overlay/Exit labels no longer wrap to two lines; invalid engine/route combinations are no longer selectable.
- **Connection status overlay clears faster.** Resolved (error/warning) connection toasts auto-dismiss within ~5s instead of lingering.

## [1.2.0] - 2026-06-20

### Added

- **Sensitive-media classification (relay).** The relay teaches the agent — server-side, via a removable system-prompt block — to mark private/NSFW media so the phone blurs it per your setting. **On by default for relay installs** (installing the relay is itself the opt-in); reversible from the "Agent context" toggle in the Relay dashboard, or `RELAY_AGENT_CONTEXT_ENABLED=0`. The exact injected instruction is visible in the chat "What the agent sees" sheet under "Relay context (server-side)". No on-device or relay-side classifier — sensitivity stays model-emitted. Vanilla upstream (no plugin) is unaffected. See `docs/plans/2026-06-20-relay-enhancement-layer.md`.
- **Transport path is visible (chat).** The chat status strip now shows which streaming path is actually in use — ⚡ Gateway (live thinking), 📡 Sessions, Completions, or Runs — instead of a generic "api online", and Chat Settings adds a basic→best tier ladder explaining the active path and its fallback.
- **Injected-context audit (chat).** Tap the context-usage meter in chat to open a "What the agent sees" sheet showing the exact extra context prepended to your next turn — persona/profile, phone status, and any per-turn (voice) hint. On the gateway path it notes the persona is applied server-side, so the audit is honest about what the phone does and doesn't send.
- **Spoken-turn badges (chat).** Voice-mode replies now carry a "Voice" chip and realtime replies a "Realtime Agent" chip — both with a speaker glyph — so spoken turns are distinguishable from typed ones in the scrollback.
- **App themes.** A new theme picker in Settings → Appearance ships eight looks: the signature Hermes Relay brand (with full light/dark) plus ports of the Nous Hermes baselines — Hermes Teal, Nous Blue (light), Midnight, Ember, Mono, Cyberpunk, and Rosé. The whole app — brand chrome, accents, and chat background — follows the chosen theme. Light/Dark/Auto applies to themes that ship both modes; fixed-mode themes show their own complete look.
- **Hot-swappable agent sphere.** The orb is now a pluggable "skin": an Adaptive skin that recolors to match your theme, built-in Classic / Aurora / Solar / Mono looks, and support for **user-authored skins** loaded from a small JSON spec. Each skin declares which live signals it reacts to (voice, tool bursts, activity), shown as capability badges in the picker. See `docs/sphere-spec.md`.
- **Connections separate features from routes (Android).** Connection settings now distinguish what a connection can *do* (a **Features** section) from how this phone *reaches* Hermes (a **Route** section), so you can enable Relay features over whichever transport you prefer. A plugin-provided **Secure proxy** route is surfaced alongside LAN, Tailscale, public, and custom routes. The standard direct-to-upstream path is unchanged and still needs no plugin. See `docs/plans/2026-06-18-native-secure-routes.md`.
- **Enhanced voice control (Gemini & xAI).** When the relay uses a Gemini or xAI voice provider, Voice Settings can now steer it: pick a Gemini voice and model and turn on expressive tone tags (with optional natural-language voice direction), or set an xAI voice with expressive speech tags. Expressive tags also apply to xAI on the streaming voice-output renderer. Standard (no-plugin) voice stays configured server-side.
- **Voice render-path visibility.** Voice Settings shows which path is rendering speech (streaming vs. basic), and Diagnostics records it each session, making voice issues easier to troubleshoot.
- **Agent pets — a living, swappable avatar.** The orb can be replaced with an animated "pet" that reacts to what the agent is doing: idle / thinking / writing / speaking / listening states, a distinct **working** pose during tool calls, one-shot **greet** / **celebrate** reactions, and a loop that quickens as output streams. Add or remove pets right in Settings → Appearance (no `adb` needed), with a live state preview, a playback-speed slider, and optional frame auto-stabilization; capability badges (Voice · Tools · Activity) show honestly what each pet actually reacts to. Pets are pure data — an AI authoring kit and a JSON schema let you generate one from sprite art. See `docs/pet-spec.md` and the custom-avatars guide.
- **Per-profile agent icon + single-image avatars.** Each agent profile can wear its own small icon beside its name (client-side, never sent to Hermes), shown in chat, the agent sheet, the top bar, and Settings. Importing an avatar now also accepts a single image (auto-wrapped as a one-frame pet) — no animated pack required.
- **In-app crash reporting.** If the app ever force-closes, the next launch shows a clean dialog with the stack trace — **Copy** it, or **Report** to open a pre-filled GitHub issue from the bug template. The report persists until you acknowledge it, and the handler re-raises so the OS still records the crash in Play vitals.
- **Clean text-flow mode (chat).** A distraction-free chat layout where your sent text slides up into a continuous flow, paired with the swappable-avatar/pet system.
- **Permissions review screen.** A central page makes the permission model explicit — standard Chat and Manage need no phone-control permissions, while voice, camera, notifications, and sideload Device Control stay opt-in — reading the same live grants Bridge does.
- **In-app attachment previews + richer capture.** Attachments preview inline before sending, sensitive media is blurred per your setting, and the capture flow is richer.

### Changed

- **Much faster cold start.** The app was building several hardware-keystore-encrypted stores at launch, which serialize on a process-global lock and stalled the chat header (model, personality, approvals) for seconds. It now builds a single keyset and the dashboard cookies share it, cutting measured time-to-connected from ~2.9 s to ~1 s after first frame, with the keystore lock contention gone. Existing sign-ins are migrated automatically on first launch.
- **Honest loading, never stale, never hidden.** Model, personality, and approvals now show a brief "checking…" state and fade in once the server confirms them, instead of popping in or showing a possibly-wrong value. Standard upstream controls (Model, YOLO, Fast, reasoning effort) are no longer hidden while loading or when unavailable — they always appear: a live control when ready, "checking…" while a value loads, or a cleanly disabled control with the reason (e.g. "available over the gateway transport") when this connection can't use them. The chat composer's reasoning-effort chip now shows alongside the model chip instead of lagging seconds behind the gateway check, and picker lists (models, personalities) show a brief, bounded "loading…" cue. The same fade-in is applied to the context meter, session drawer, and Manage panels.
- **Tidier chat header.** The LAN/Tailscale chip was dropped from the top bar (the bottom status strip already shows the route, and is now tappable to open Connections), and a `none` personality is no longer shown — leaving more room for the model name.
- **Connection toast reads like the cold-start screen.** The floating connection status toast now shows a live checklist — Route / API / Relay each with a spinner, ✓, or ✕ as the checks land — instead of flat text, matching the splash screen's stepper. Swiping it up now tracks your finger (slide + fade) rather than snapping, and connection problems get an explicit "Open Connections →" link at the bottom so the path to the detailed view is obvious.
- **Tidier chat header.** The "approvals off" warning moved out of the agent subtitle into a single amber ⚡ icon in the top bar (tap for the full explanation in the agent sheet), and Share folded into a ⋮ overflow menu — so the personality · model subtitle no longer gets clipped by the trailing action icons.
- **Voice replies are formatted for listening.** In voice mode the assistant is now guided to answer in short, conversational sentences without markdown, emoji, or raw URLs — without changing what is stored in chat history.
- **Leaner terminal screen (Android).** The extra-keys bar scrolls horizontally with compact, fully-legible keys (no more clipped "CTRL"), the header is a single compact row showing one inline connection-status dot plus state, and the tab strip is hidden for single-tab sessions — the new-tab "+" moves into the header — reclaiming vertical space for the terminal.
- **Relay terminals run on an isolated, TUI-tuned tmux.** Sessions now use a dedicated tmux server/socket with its own config — instant ESC (`escape-time 0`), truecolor `$TERM`, mouse and focus events on, and no status bar — so editors and full-screen tools behave correctly, without touching the user's personal tmux.
- **"Standard" is now "Vanilla Hermes" throughout.** The user-facing name for the no-plugin upstream path is now **Vanilla Hermes**, so it's clear the default path runs on a plain Hermes agent.
- **QR pairing degrades gracefully on unusual cameras.** On foldables and devices where the camera can't initialize, the scanner now shows a "camera unavailable — pair manually" card instead of force-closing.
- **Image & attachment viewers rotate to landscape.** The full-screen image / attachment viewers can rotate to landscape even though the rest of the app stays portrait-locked.

### Fixed

- **Clearer error when a feature needs a newer relay.** Toggling a setting an older relay plugin doesn't recognize (e.g. xAI expressive speech tags) now shows "Relay update needed" instead of a generic HTTP 400 with a dead Retry button. Genuine input errors are unaffected.
- **Connection status toast is no longer see-through.** The floating connection-lost/switching toast renders fully opaque so content behind it no longer bleeds through and hurts legibility.
- **Provenance badges survive the post-turn history reload.** "Voice", "Realtime Agent", "Stopped", and "Error" chips are now preserved when the conversation reloads after a turn, instead of silently vanishing.
- **Chat and Manage no longer stay dark in Light mode.** Brand-styled surfaces bypassed the theme and were effectively hardcoded dark; they now follow the selected theme and light/dark mode, and the glow/border flourishes key off the active theme rather than the system setting.
- **Realtime voice no longer drops the conversation mid-session with some providers.** A normal end-of-turn signal was being rejected on certain voice providers, ending the session every turn.
- **Relay voice synthesis no longer leaves temporary audio files behind** on the server.
- **Clearer voice errors and an oversize-recording guard.** Standard voice now rejects an over-long recording before uploading it and shows a helpful message for audio the server can't read, instead of a generic HTTP error.
- **Terminal paste no longer auto-runs multi-line text.** The key-bar PASTE now uses bracketed paste, so multi-line content lands intact in shells and editors instead of executing line by line.
- **Terminal on-screen arrows behave inside TUIs.** Arrow/Home/End keys follow the running app's cursor-key mode (application vs. normal), so they work correctly in vim, less, and fzf.
- **Terminal footer spacing.** A small gap now keeps the last terminal row clear of the key bar (it could previously look like the footer overlapped it), and a redundant navigation-bar inset that left empty space below the keys was removed.
- **In-chat model picker now actually applies on a new chat.** Picking a model and provider in the chat composer (e.g. Grok 4.3 via your xAI subscription) is bound to the new conversation, so the agent runs on the picked model instead of silently falling back to the account's global default. Switching profiles retires an explicit pick so the profile's own model takes over, and the picker label updates immediately instead of lagging a round-trip.
- **Server-generated images render in chat when paired to the relay.** An assistant image that points at a server-side file path is now fetched through the relay's media route and shown inline (tap to zoom), instead of degrading to an "image is on the server" notice. On the SSE chat path the agent is also told it can surface images and files by path when a relay route is configured (visible in the chat "What the agent sees" sheet). Standard (no-plugin) connections are unchanged.
- **Smoother profile switching.** Switching profiles no longer blanks the conversation to an empty/"Loading…" state before the new history loads; the previous transcript is held and cross-fades to the new one.
- **In-chat model switch now applies mid-conversation, not just on new chats.** Picking a model in an already-started chat switches the live session in place — the same path the desktop/TUI `/model` uses — instead of racing into a global-default write, so the turn runs the model you picked.
- **Server-side turn errors always surface.** A failed turn (e.g. a provider rejecting the request) now stays on screen as an error bubble with the message, instead of appearing for a moment and then vanishing when the conversation reconciled after the turn.
- **The model shown in chat matches the live session.** The chat header and the agent detail sheet now show the model the current session is actually running (reflecting a mid-session switch) rather than the profile/global default, and the agent sheet no longer pairs the global default model name with the session's provider — it now also names the host's "Server default" when the session runs something different.
- **Server steering markers no longer appear as chat bubbles.** The "[System: the active model/personality changed]" notes the server injects into history for the agent's benefit are hidden from the transcript by default (matching the desktop/TUI); a new "Show system messages" debug toggle in Chat Settings can reveal them.
- **Per-reply token counts (and other per-message details) survive the post-turn reload.** The input/output token subtext, provenance badges, tapped-card state, and voice/realtime sync traces are now preserved when the conversation reconciles against the server after a turn — previously a normal reply lost its token line once the turn finished (the error bubble kept it only because errored turns skip that reload). The reloader now preserves client-only message details by default instead of dropping any it doesn't re-derive from the server.
- **PDF viewer no longer crashes when the document closes mid-render.** A PDF preview that was torn down during a layout pass could read a closed renderer and throw `IllegalStateException: Document already closed`; the renderer is now guarded so it returns nothing instead of crashing.
- **No crash opening a chat with a server-local image.** Rendering a relay-fetched image could throw `ClassCastException: kotlin.Result cannot be cast to byte[]` because a `suspend` function returned `kotlin.Result` (which collides with the coroutine machinery's own wrapper); a purpose-built result type fixes it.
- **Side-loaded avatars and sphere skins are reachable again.** Both loaders read internal storage while the docs (correctly) pointed `adb push` at external app-scoped storage, so a side-loaded pet or skin never appeared. Both now resolve through one external-preferred location, so the documented install path works.
- **Reopened chats paint the session's real model** (not the profile/global default), the model-picker "Server default" caption shows the true default rather than the active override, and a chat's media badge shows only when paired — with the underlying server-image fetch-failure reason surfaced when a fetch fails.

## [1.1.0] - 2026-06-16

### Added

- **Automated Play Console upload on release.** When a `PLAY_SERVICE_ACCOUNT_JSON` secret is configured, pushing a stable `android-v*` tag uploads the `googlePlay` App Bundle to the Production track as a draft (a human still starts the rollout). Prereleases are skipped, and the `sideload` flavor is structurally blocked from ever publishing to Play. Without the secret, the release builds publish to GitHub Releases exactly as before.
- **Desktop UI preview harness (`:ui-preview`).** A non-shipped Compose for Desktop module renders presentational composables in a window on the PC with Compose Hot Reload, for fast UI iteration without a device build/install loop. It reuses the shared sphere algorithm as its single source of truth.
- **Plugin: guided env-key setup.** The relay plugin declares its optional voice-provider keys (`XAI_API_KEY`, `OPENAI_API_KEY`, `ELEVENLABS_API_KEY`) in its manifest, so `hermes plugins install` prompts for them (masked, with a "get yours" link) instead of hand-editing `.env`. The standard no-plugin path needs none.
- **Plugin: native install path.** Tools-only setups can install via `hermes plugins install Codename-11/hermes-relay/plugin`; the full relay still uses the curl `install.sh`.
- **`/relay` slash commands.** `relay status · devices · pair` usable mid-conversation from any platform (CLI / Discord / TUI).
- **Dashboard relay-status widget.** A `Relay · connected / offline / unpaired` badge in the dashboard header, visible on every page.
- **Session-start relay health check.** A minimal, fully-guarded `on_session_start` hook records relay reachability without slowing the gateway.

### Changed

- **Release names normalized by surface.** Future GitHub Releases are named `Hermes-Relay-Android`, `Hermes-Relay-Plugin`, and `Hermes-Relay-CLI`, with future tags on `android-v*`, `plugin-v*`, and `cli-v*`. The CLI installer and updater still understand historical `desktop-v*` prereleases during the migration.
- **Per-surface release notes.** Plugin and CLI GitHub Releases now use hand-written `PLUGIN_RELEASE_NOTES.md` / `CLI_RELEASE_NOTES.md` files (Summary + Added/Changed/Fixed + Install/Verify) — the same format as Android's `RELEASE_NOTES.md` — instead of static boilerplate baked into the workflow. The release workflows substitute the version into the install commands automatically.
- **Settings screen overhaul (Android).** Status pills are now exception-only — they appear only when a surface needs attention and stay quiet when healthy. The Power tools section shows a single state-aware **Plugin active / required / offline** badge instead of an identical "Relay paired" chip on every card. Connections moved to the top (above the Hermes section), Diagnostics + Developer options moved into the App section, the status chips were restyled to match the app's translucent-bordered language, and the brand blue was deepened.

### Fixed

- **Force-close on connect when the stored credential keyset was corrupt.** A corrupt encrypted token store (which can happen after an app upgrade or device restore) threw during construction and crashed the app right after a successful pair, on both standard and relay connections. The token store now heals a corrupt keyset on the spot, and credential storage degrades to a re-pair instead of crashing if the device keystore is unusable.
- **Dashboard plugin: unreadable button labels.** Solid buttons in the relay dashboard panel inherited the container text colour, which matched their background. Solid button variants now keep their proper contrast colour.
- **Installer failed on uv-managed Hermes hosts.** `install.sh` assumed `pip` lived in the hermes-agent virtualenv, but environments created by `uv` (the upstream default) ship no `pip` module, so the editable install aborted at step 2. The installer now bootstraps `pip` via `ensurepip`, or falls back to `uv pip`, so the plugin installs cleanly on uv-managed cores.
- **Chat settings (Android).** The streaming-endpoint picker no longer wraps "Gateway"/"Sessions" onto a second line, and the system-prompt preview now reflects the enabled context toggles (foreground app, battery, safety rails) with representative placeholder values instead of looking inert.
- **Dashboard plugin: buttons rendered as blank boxes.** The host dashboard's Nous design-system `Button`/`Badge` use boolean variant flags (`outlined`/`ghost`/`invert`) and a `tone` prop — not the shadcn-style `variant` prop the plugin passed — so every button collapsed to a solid near-white fill with an invisible label. The plugin now translates its props to the design-system contract via an adapter, and drops a label-hiding CSS reset.

## [1.0.0] - 2026-06-14

### Added

- **Relay plugin diagnostics and install guidance.** `hermes relay doctor` now reports standard upstream API/dashboard reachability, Relay loopback state, dashboard plugin presence, plugin-manager layout, and whether the legacy bootstrap monkeypatch is installed. The plugin manifest now advertises its Android and desktop tools, and `after-install.md` gives the upstream plugin manager a first-run handoff.

- **Plugin-owned compatibility hook lifecycle.** `hermes relay compat status/install/remove` now owns the optional `hermes_relay_bootstrap.pth` startup hook, so the monkeypatch can be inspected, added, or removed without rerunning the legacy installer. The standard v1.0.0 path does not require this hook.

- **Legacy cleanup alignment.** The legacy installer now installs the optional `.pth` hook through the plugin compat lifecycle, and the uninstaller removes every shell shim it creates (`hermes-pair`, `hermes-status`, `hermes-relay`, `hermes-relay-update`, `hermes-relay-tailscale`) while delegating hook cleanup to `hermes relay compat remove` when available.

- **Gateway chat transport with live thinking.** Chat can ride the upstream dashboard `/api/ws` (the `tui_gateway` surface the official hermes-desktop client speaks) — the only vanilla-upstream path that streams reasoning *live*, so the Thinking block and sphere light up during generation. "Auto" prefers it when the dashboard is reachable and Manage is signed in, and falls back to the SSE endpoints per turn.

- **Gateway desktop parity.** Native image/PDF/file attachments (with an in-chat notice when a turn falls back to a transport that can't carry files), mid-turn **steering**, **edit & resend**, interactive **approval / clarify / sudo / secret** cards, live **subagent lanes**, a **context-window meter**, server **slash commands** in autocomplete, and **turn-complete notifications** when the app is backgrounded.

- **Gateway warm-start + Keep connected in background.** Pre-warming the gateway on foreground moves the cold session-setup cost off the send path. An opt-in foreground-service toggle (both flavors; `specialUse`, off by default) holds the socket open in the background so a long-backgrounded conversation resumes instantly.

- **Switch agent profiles from chat.** Pick a different agent — model, SOUL, personality, and skills — per conversation. The selection is **ephemeral** (bound to the session like the official desktop; it never changes the server's default agent for other clients). The session drawer scopes to the active profile and loads that profile's history, and the right agent is restored on cold start. The Manage tab's server-wide **Activate Profile** action now confirms first.

- **Manage parity with the desktop dashboard.** Change models from the full provider catalog, manage provider keys (write-only, masked, reveal), create/edit profiles and SOUL.md, and browse/install/update skills. Manage data is cached to disk for an instant cold launch.

- **Open & save chat images and attachments.** Tap an image for a full-screen viewer (pinch-zoom, double-tap, Share/Save); non-image attachments gain an Open/Share/Save menu. Saves land in `Pictures`/`Download/Hermes-Relay` with no permission on Android 10+, preserving the original bytes.

- **Persistent Realtime Agent voice + background runs (ADR 33).** The realtime engine keeps one session across turns (follow-ups retain context); a long Hermes run is promoted to a tracked background task and spoken when ready, so the conversation stays responsive.

- **Redesigned chat input bar.** A Telegram-clean pill field with one trailing button that morphs between Send / Voice / Stop / Steer / Queue; the slash button is gone (typing `/` still opens autocomplete).

- **Routes card reachability verdicts** ("Reachable", or the specific failure reason) and per-turn **latency tracing** (`TurnLatency`, durations only) for diagnosing transport speed.

### Changed

- **Relay plugin/server version aligned to v1.0.0.** The Python package, plugin manifest, dashboard manifest, and relay runtime now use the same `1.0.0` line as the stable Android release so a retagged source checkout describes one product version.

- **The standard (no-plugin) path is first-class.** Chat, Manage, and voice all work against an unmodified upstream Hermes agent; standard voice rides the dashboard audio surface (`/api/audio/*`) with the Manage sign-in, and relay-paired voice is the profile-aware fallback. The relay plugin is now purely additive.

- **Seamless connection UX.** LAN↔Tailscale handoffs and reconnects no longer reload the chat; connection and update status are now in-theme slide-down toasts over the content instead of banners that pushed the UI around.

- **Editable, roaming routes.** Add/edit/remove routes in Settings → Connections; bare-host URLs default their scheme and port (and preview what will be saved); remote-access (Tailscale) is surfaced in the main setup flow with a "Remote" readiness line.

- **Faster Manage.** A shared auth preamble plus concurrent payloads cut a full load from ~40 round trips to ~12; a process-lifetime cache and startup pre-warm render the last-seen data instantly, and Manage now names which dashboard URL it's talking to.

- **Faster, calmer cold start.** Key-less connections skip the multi-second keystore decrypt; the startup sphere is now the actual loading screen with narrated check lines, and the OS splash blends into it.

- **Docs + branding.** The docs site was rechromed to the app theme and repositioned around the two-path story; the README and Play listing were refreshed standard-first; product-name copy normalized to **Hermes-Relay**.

- **Quality-of-life.** Quote-in-reply, share-conversation-as-Markdown, ambient mode as a long-press gesture, a floating status pill, decluttered Manage cards, back buttons on pushed screens, and a softer active-connection card.

### Fixed

- **No "Connect to Hermes" flash on cold start.** The empty-state now distinguishes "still hydrating from disk" from "nothing configured" (`ConnectionStore.isHydrated` → `chatConnectState`), showing a quiet "Connecting to Hermes…" spinner until ready and the connect CTA only once hydration confirms no connection exists.

- **In-app What's New renders cleanly** — parsed into a version subtitle, bold section headers, and real bullets instead of raw text with literal `*`.

- **App-start UI freeze from Keystore lock contention.** The encrypted cookie store built its StrongBox-backed prefs eagerly in its constructor (1–4 s under a process-global lock) from several code paths at once; it now builds lazily on an I/O thread and is shared per connection.

- **Standard connections now follow LAN↔Tailscale changes**, standard voice follows the resolved route (not the persisted URL), and a stale probe cache can no longer pin a dead route after a handoff or resume.

- **Editing a URL no longer wipes fallback routes** (edits merge with stored extras instead of rebuilding from the edited URL alone); **"Re-check" / "Use now" no longer fail silently** (the probe always publishes its outcome and per-route failure reasons); and a network change can no longer resurrect a deliberately disconnected relay socket.

## [0.8.1] - 2026-05-26

### Fixed

- **Voice mode crash with barge-in on legacy TTS playback.** When barge-in was enabled and the relay served audio over the legacy `/voice/synthesize` (Media3) path, the first agent sentence played for ~2 syllables and then the app crashed with `IllegalStateException: Player is accessed on the wrong thread`. The barge-in listener's `Dispatchers.IO` reader was reading `ExoPlayer.getAudioSessionId()` (a thread-confined accessor) to attach the echo canceller. `VoicePlayer.audioSessionId` now serves a `@Volatile` cache populated from main-thread Media3 callbacks, so it is safe to read from any thread.

## [0.8.0] - 2026-05-23

### Added

- **Provider-native Realtime Agent voice.** Android can opt into a Realtime Agent voice engine where Android streams mic PCM to the relay, xAI or OpenAI owns realtime speech recognition and speech generation, and Hermes remains the governed authority for tools, memory, profiles, confirmations, current-data checks, side effects, and durable transcript context.

- **Hermes-brokered realtime tool timeline.** Realtime Agent turns now mirror transcript, assistant speech, Hermes task state, concise tool-status rows, confirmation state, path badges, and compact result provenance into chat/voice UI without dumping raw tool output aloud.

- **Connection diagnostics and activity logs.** Settings now includes a Diagnostics surface with sanitized recent API, relay, session, endpoint, and voice activity. API / Relay / Session detail drawers also tail the relevant recent activity so hung or unreachable relays are visible without ADB first.

- **Realtime and Voice Settings active-engine layout.** Voice Settings now separates **Voice Engine** from global voice controls, shows only the selected engine's provider card, keeps fallback TTS visible as a global safety-net card, and provides **Test Current Engine**: stable voice plays the saved Voice Output sample, while Realtime Agent opens a provider-native `/voice/realtime-agent/*` test session and plays streamed realtime audio.

- **Voice Lab text and mic demos.** The realtime voice test screen now offers two clearly separated demos: a **Text demo** that plays raw provider TTS, and a **Mic demo** that exercises the full agent path — real speech recognition, Hermes brokering, and a spoken reply — with tap-to-record / tap-to-stop capture. A `scripts/realtime-voice-lab-smoke.ps1` smoke script accompanies the lab.

- **Realtime playback diagnostics.** Playback now records a time-to-first-audio metric, logs requested-vs-actual AudioTrack buffer sizes, runs a first-frame watchdog, and cross-checks playback drain drift so cold-start and underrun regressions surface in the Diagnostics log instead of as silent dead air.

### Changed

- **Google Play Bridge Core split.** The Google Play Android track keeps relay pairing, chat, profiles, voice, terminal/TUI, media, notification companion, relay sessions, diagnostics, and status while removing AccessibilityService-backed Device Control declarations and permissions. Sideload remains the track for screen reading, gestures, screenshots, SMS/calls, contacts/location, overlays, wake locks, and unattended control.

- **Release lanes now use explicit product tags and names.** Future Android releases use `android-v*`, plugin/Python releases use `server-v*`, and CLI releases continue on `desktop-v*`. GitHub Release names now publish as `Hermes-Relay-Android vX.Y.Z`, `Hermes-Relay-Plugin vX.Y.Z`, and `Hermes-Relay-CLI vX.Y.Z`; the old relay-named server scripts remain compatibility shims.

- **Realtime voice instructions are provider-neutral.** Realtime providers receive active interface context, local date/time, provider/model/voice/profile metadata, and guidance to ask Hermes for current facts, research, device/desktop state, project context, precise/versioned data, and any requested checks instead of guessing from model knowledge.

- **Play/user docs now match the actual artifact.** Release-track docs, feature matrix, getting-started copy, privacy/security references, and Play listing copy now say Google Play has no AccessibilityService, screen reading, gestures, screenshots, or phone-control utility permissions.

### Fixed

- **Silent / choppy first-turn realtime voice playback.** The AudioTrack deep-buffer cold-start was parking the playback head at zero so the first turn dropped or stuttered. The streaming buffer was shrunk from 4000ms to 700ms, the low-latency prebuffer threshold retuned, and a preroll force-start removed, giving reliable low-latency playback from the first frame. Confirmed on-device.

- **Voice Lab waveform now tracks the playback cursor.** The waveform is driven by `RealtimePcmPlayer.playbackAmplitude()` at the playback position instead of socket-arrival time, so the visual matches what is actually being heard.

- **Realtime Hermes calls no longer depend on the phone's saved Hermes API key.** Provider-native Hermes tool calls are brokered by the relay with its server-side Hermes credential, so a phone can be paired for realtime voice without exposing or misusing its saved API bearer.

- **Hung relay voice turns fail visibly.** Voice turns run relay health preflight and shorter realtime/session timeouts so Settings and Voice mode surface unreachable relay state instead of sitting indefinitely on Thinking.

- **OpenAI realtime is no longer treated as render-after-Hermes fallback.** `openai_realtime` is registered as a native Realtime Agent provider path alongside xAI, with provider-native audio events normalized through the same broker contract.

- **Local release signing no longer falls back to debug when `local.properties` uses a repo-root relative keystore path.** The Android Gradle signing config now resolves relative keystore paths from the repo root, matching the documented `release.keystore` setup.

## [0.7.0] - 2026-05-19

### Added

- **Profile-aware Hermes sessions and voice settings.** Android now treats Hermes profiles as first-class connection state: profile selection resolves against the active server, profile-specific chat sessions are persisted separately, default/Victor display is normalized, and per-profile voice provider/model/voice settings can be read and saved through server-owned endpoints without depending on Hermes config mutations.

- **Realtime voice playground and provider lab.** The relay now includes standalone OpenAI/xAI/ElevenLabs-oriented voice lab tooling, provider adapters, provider option discovery routes, realtime playground routes, and generated WAV/JSONL artifact ignores for iterative voice quality testing outside production Hermes routes.

- **Streaming voice output routes.** server-owned `/voice/output/*`, realtime playground, profile voice config, and provider option endpoints support provider-neutral TTS rendering, dynamic voice/model option surfaces, and profile-scoped voice configuration for Android.

- **Experimental Android realtime voice overlay.** Android adds a richer voice overlay with tap-to-talk, continuous mode controls, optional system overlay mode, compact mode, realtime waveform visualization, playback controls, and an experimental badge around barge-in instead of treating all voice as experimental.

- **Experimental realtime Hermes voice-agent plan.** `docs/plans/2026-05-19-realtime-hermes-voice-agent.md` records the next architecture step: provider-native realtime speech with Hermes-brokered profiles, sessions, tools, confirmations, and transcript mirroring. The stable Hermes chat + voice-output path remains the default.

- **Desktop tray pairing and consent flow.** The desktop surface gained Tauri tray pairing, QR/consent affordances, sidecar preparation, and computer-action approval polish so desktop and Android pairing flows are closer to parity.

- **Shared relay/Quest scaffolding.** Experimental `relay-core`, `relay-ui`, and Quest prototype modules were added for shared pairing, terminal, transport, voice, and morphing-sphere work without changing the Android phone app's default route.

- **Desktop Chat tab with first-run route setup.** The Tauri tray dashboard now has a Chat tab inspired by the Hermes Desktop chat-first flow. It streams through the saved paired relay when `~/.hermes/remote-sessions.json` has an active session, or through a direct Hermes gateway/API URL when relay pairing is not available. The tab supports stop, retry, new chat, clear, current-session transcript history, and a setup panel that offers relay pairing or direct WebAPI configuration without saving the optional API key.

- **First-class desktop TUI tab.** The Tauri tray dashboard now gives the embedded xterm/PTY Hermes session its own sidebar tab instead of nesting it under Terminal / CLI. Terminal remains the external launcher, shim-state, and copyable-command surface, while plugin embeds route into the same TUI tab.

- **Desktop surface plugins.** The desktop CLI and Tauri tray now register built-in terminal surface plugins, starting with Herm (`herm-tui`) from `liftaris/herm`. Users can inspect plugin status, install or update Herm, launch a fresh dashboard, resume with `herm -c`, or embed the plugin in the tray's xterm/PTY surface with `bunx`/`npx` fallback when the `herm` binary is not installed.

- **Relay server release track.** Relay server and Python package releases now use `relay-v*` tags, validate relay-owned version metadata, build wheel/sdist artifacts, generate checksums, and publish through `.github/workflows/release-relay.yml`. This lets Relay server fixes ship independently from Android app `versionCode` bumps and desktop CLI alphas.

- **Dashboard plugin CI.** `.github/workflows/ci-dashboard.yml` builds the dashboard plugin, runs the dashboard API tests, and verifies the plugin-owned QR modal CSS markers are present in the built bundle.

- **Upstream integration sync reference.** `docs/upstream-integration-sync.md` now tracks which Hermes-Relay surfaces use upstream-supported extension points, which pieces are server-owned compatibility layers, and what has to be checked before changing relay, Android, desktop, dashboard, bootstrap, or user-doc surfaces.

- **Relay version sync verifier.** `scripts/check-relay-version-sync.py` validates the relay package version against plugin metadata and dashboard metadata so release and dashboard surfaces cannot silently drift.

### Changed

- **Stable voice is now the main Android voice path.** Voice mode defaults to Hermes chat streaming plus relay-managed voice output, with realtime-provider work kept as a standalone lab/testbench and future experimental mode instead of replacing Hermes session/tool authority.

- **Realtime voice output uses balanced coalescing.** Normal assistant speech is batched into more natural chunks while tool/status speech stays immediate, reducing provider render resets and tone/volume variation during voice replies.

- **Voice settings are profile-scoped and option-aware.** Android can fetch provider/model/voice options from relay endpoints, show profile context in voice settings, save voice choices per Hermes profile, and expose advanced manual entry when provider metadata is incomplete.

- **Voice UI state is synchronized with chat state.** Voice mode now reuses more of the chat session/profile state, preserves live transcript and tool timeline visibility, and improves overlay exit/minimize behavior for hands-free use.

- **Release versioning is split by surface.** Android app releases remain on `v*` and use `gradle/libs.versions.toml`; Relay releases use `relay-v*` and keep `pyproject.toml`, `plugin/relay/__init__.py`, `plugin/plugin.yaml`, and dashboard plugin metadata in lockstep; desktop remains on `desktop-v*` and `desktop/package.json`. `scripts/bump-version.sh` is now a backward-compatible Android alias, with new explicit `scripts/bump-android-version.sh` and `scripts/bump-relay-version.sh` helpers.

- **Upstream voice imports are isolated.** Relay voice routes now call upstream Hermes STT/TTS helpers through `plugin.relay.upstream_voice`, keeping private upstream voice helper imports in one adapter module until Hermes exposes a stable HTTP voice API.

- **CI paths and release actions tightened.** Relay CI now watches Relay-owned paths instead of all `plugin/**`, validates Relay version metadata during syntax checks, uses explicit timeouts, and runs the focused route/auth/session test slice instead of broad test discovery. Release workflows now use `softprops/action-gh-release@v3`.

### Fixed

- **Profile switching no longer silently falls back to the wrong local API host.** Profile API URL resolution now handles per-profile Hermes API servers, default/Victor compatibility, and relay-managed profile metadata so selecting a profile does not try to create sessions against `localhost` from the phone.

- **Non-default profile names remain visible in chat.** Agent display metadata is normalized so selected profile names persist above finalized assistant messages instead of disappearing back to the default label after stream completion.

- **Voice waveform and playback state are better aligned to real audio.** The output waveform waits for audio playback, handles processing separately, and avoids returning to the microphone too early at the end of an assistant response.

- **Continuous voice mode no longer starts a session just because auto mode is enabled.** Auto/continuous remains a preference, while explicit voice start/stop controls decide when a voice session is active.

- **Android voice mode no longer 403s when paired over plain-LAN `ws://` with a Hermes API key saved.** Symptom: tap the mic in Voice mode → red banner *"Voice access expired — extend or re-pair with voice grants"* even though the Connections card shows API Server / Relay / Session all green. Root cause: `RelayVoiceClient` preferred the saved Hermes API key over the paired Relay session token; the relay's `_request_is_secure_enough_for_api_bearer` correctly rejects API-bearer auth on `/voice/*` over plaintext outside loopback/Tailscale, returning a generic 403 that the client flattened to "expired." Fix: invert bearer precedence so paired devices use the session token first (no transport guard — it's the credential the QR/pair handshake already established), with the API key as fallback for chat+voice-only installs that never paired. `describeHttpError` now also reads the server's text/plain response body when present so future 403s show the relay's actual reason instead of a one-size-fits-all string.

## [0.6.1] - 2026-05-06

### Added

- **Android bridge media sharing and MMS handoff.** New `android_share_media` and `android_send_mms` tools expose full file/attachment support through the relay media registry and Android `FileProvider` `content://` grants. Host-local paths are registered with `/media/register`, phones fetch bytes with their paired relay session, and the sideload app opens Android's native share or MMS compose UI after on-device confirmation. Relay HTTP now includes `/share_media` and `/send_mms`, and docs spell out that direct `android_send_sms` remains text-only `{to, body}`.

- **Relay voice endpoints accept Hermes API bearer tokens.** `/voice/config`, `/voice/transcribe`, and `/voice/synthesize` now accept either a Relay session token with explicit `voice:*` grants or the existing Hermes API bearer token. API bearer validation is voice-only, uses the configured Hermes API server's protected `/v1/models` endpoint with a short positive cache, and rejects non-loopback plaintext by default unless a trusted HTTPS proxy header or the explicit dev escape hatch is configured. Existing Relay sessions are backfilled with voice grants so paired phones do not need to re-pair.

- **Relay CLI can toggle plain-LAN API-key voice auth without restart.** `hermes relay insecure-api-key status|on|off` calls the running relay's loopback-only `/relay/security` endpoint and flips the runtime `allow_insecure_api_bearer` flag immediately. This keeps HTTPS as the default for API-key voice auth while making Android phone LAN smoke tests possible without exporting env vars or restarting the service.

- **Desktop CLI alpha.14 — `Ctrl+A ?` chord re-displays the chord-help banner.** The attach-time banner scrolls off as soon as anything writes to the terminal, so users mid-session forgot the verb list and had to detach + re-attach (or guess). New `Ctrl+A ?` (and `Ctrl+A h` synonym) reprints the banner to stderr without leaving the session. Banner text refactored into a single `CHORD_HELP` constant so the attach-time print, the `?` chord, and the unknown-chord hint can't drift out of sync. Unknown-chord hint now also lists `?` as one of the known verbs.

- **Desktop CLI alpha.13 — `Ctrl+A v` chord in `hermes-relay shell` for in-session paste.** Reported gap: *"...we have to exit hermes-relay shell to run `hermes-relay paste`. Can we leverage a tmux hook?"* Tmux runs on the Linux server with no path back to the Windows clipboard, so server-side hooks can't help — but the existing client-side chord state machine (`Ctrl+A .` detach, `Ctrl+A k` kill, `Ctrl+A Ctrl+A` literal) is the right place. Added `Ctrl+A v`: client reads its own clipboard image (same `captureClipboardImage()` path as the `/paste` REPL command), POSTs to `/clipboard/inbox` via the new shared `stageClipboardImageToInbox(url, token)` helper exported from `commands/paste.ts`, then types `/paste\r` into the PTY so the upstream Hermes TUI consumes it in the same flow the user would have typed by hand. Status line goes to stderr so it doesn't pollute the PTY stream: `[shell] pasted 1920×1080 (245 KB) → /paste`. Reentrancy guard prevents double-stage on a fast double-press. Banner help and chord doc-comment updated to list the new verb.

### Fixed

- **Android bridge tool/route contract drift.** The active plugin import now uses `plugin.tools.android_tool` as the single source of truth, while top-level `plugin/android_tool.py` remains as a compatibility shim. The relay now registers `/return_to_hermes`, matching the documented and phone-side command, and bridge status gating checks `/bridge/status` so tools are hidden unless a phone is actually connected.

- **Android CI/release gate no longer hangs on the broad Gradle test aggregate.** The Android CI and `v*` release workflows now run the stable sideload pairing/connection regression slice with explicit timeouts while the deferred full JVM test-suite cleanup remains tracked separately.

- **Android connection/profile state no longer leaks across switches.** Connection switches now clear the outgoing profile object immediately, load the destination connection's saved profile name only after that connection is active, and resolve it against the destination server's current profile list. The default local relay URL is now `ws://localhost:8767`, and auto-managed relay URLs are derived from the active API URL before reconnecting.

- **Desktop CLI alpha.12 — install scripts truncated the prerelease suffix in the "upgrading X → Y" line.** A user saw `existing install detected: 0.3.0-alpha.9 — upgrading to 0.3.` (literally truncated mid-token). Root cause: `normalize_pinned_version` (bash) and `Get-NormalizedPin` (PowerShell) stripped everything after the first `-`, including `-alpha.N`. Comment claimed this was "for comparison against the bare semver the binary reports" — but since alpha.4, the binary's `--version` reports the FULL semver (via the embedded `gen:version` constant), so the strip is no longer defensive, just lossy. Removed the suffix-strip from both normalizers; both now produce `0.3.0-alpha.11` from `desktop-v0.3.0-alpha.11`. The equality compare at line 138 still works because both sides include the prerelease tail.

- **Desktop CLI alpha.11 — `hermes-relay update` (and the install one-liners) saw the wrong "latest" release.** On alpha.9, `hermes-relay update --check` expected to see alpha.10 but reported "Up to date." Root cause: GitHub's `/repos/.../releases` API returns rows ordered by the release object's `created_at`, NOT by SemVer of the tag — and `created_at` shifts whenever the row is touched (re-tag, manual edit, asset replacement). When alpha.9's release row got touched after alpha.10 was tagged, the API listed alpha.9 first and all three of our resolvers blindly took `[0]`. Fix: pick the SemVer-max from all desktop-v* tags explicitly. (1) `desktop/src/updater.ts` — `desktop.reduce((max, r) => compareVersions(r.tag_name, max.tag_name) > 0 ? r : max)`. (2) `desktop/scripts/install.sh` — `sort -V | tail -1` (zero new deps; bash + sort is sufficient). (3) `desktop/scripts/install.ps1` — custom `Sort-Object` comparator that packs (Major, Minor, Patch, PrereleaseRank, PrereleaseNum) into a zero-padded sortable string with alpha=1, beta=2, rc=3, stable=999. Live-verified against the real API: all three now return `desktop-v0.3.0-alpha.10` instead of `alpha.9`.

- **Desktop CLI alpha.10 — `hermes-relay paste` always returned "No image on clipboard" on Windows even when an image was present.** Root cause: the PowerShell invocation in `captureClipboardWindows` (`src/chatAttach.ts`) was missing the `-STA` flag. `powershell.exe -Command` defaults to MTA (Multi-Threaded Apartment), and `[System.Windows.Forms.Clipboard]::GetImage()` only returns a valid image from STA threads — from MTA it silently returns null, indistinguishable from "no image present." Also affects the `chat` REPL's `/paste` command which routes through the same Windows code path. Fix: added `-STA` to the powershell args list (now `['-NoProfile', '-NonInteractive', '-STA', '-Command', ps]`). Live verification: empty clipboard returns null; a cyan 100×80 PNG placed via `[System.Windows.Forms.Clipboard]::SetImage` returns the expected 305-byte capture with correct dimensions. Affects `desktop-v0.3.0-alpha.7` through `desktop-v0.3.0-alpha.9`.

### Changed

- **Android voice no longer requires Relay pairing when a Hermes API key is saved.** The phone now resolves voice auth from the saved Hermes API key first, then falls back to the paired Relay session for `/voice/config`, `/voice/transcribe`, and `/voice/synthesize`. Chat+voice-only setups can use manual/API-key configuration without the full pairing-code flow; bridge, terminal, media, clipboard, profile writes, and Android-control routes remain paired-session-only.

- **Relay grant labels are now human-readable in Android and dashboard management UI.** Relay session grant chips still preserve the server keys internally, but user-facing lists now sort the known grant set and render labels such as `Voice STT` / `Voice TTS` instead of raw `voice:stt` / `voice:tts`. Privacy and configuration docs now reflect that Voice mode uses runtime microphone permission and split voice grants.

- **Desktop CLI alpha.8 — `/screenshot` is multi-monitor aware by default.** The alpha.6/alpha.7 `screenshotHandler` / `captureScreenshot` captured only the primary display on Windows and treated `display` as a number-only param. alpha.8 changes the default to `-1` (all monitors stitched) and accepts string aliases so both the agent tool call and the `/screenshot` slash command can say `'all'` / `'primary'` / `'1'` / `'2'` etc. Windows path uses `System.Windows.Forms.SystemInformation.VirtualScreen` for the union rect (handles negative coordinates when monitors are arranged left-of-primary). macOS path uses `screencapture -D N` for 1-indexed per-display capture. Linux path relies on grim/scrot/import's inherent whole-X-screen behavior. REPL `/screenshot` defaults to all monitors; `/screenshot primary` or `/screenshot 0 | 1 | 2` narrow. Live smoke on a multi-monitor Windows box: all = 1.6 MB stitched, primary = 405 KB — 4× size ratio confirms virtual-screen path. Zero server changes; `image.attach.bytes` RPC consumes whatever bytes the client sends.

### Added

- **Desktop CLI alpha.7 — native image paste in `hermes-relay chat`.** `desktop-v0.3.0-alpha.7`. Plan: [`docs/plans/2026-04-23-desktop-alpha-7-native-paste.md`](docs/plans/2026-04-23-desktop-alpha-7-native-paste.md). Users now type `/paste` (system clipboard), `/screenshot` (primary display), or `/image <path>` (file on disk) inside the `chat` REPL, get a one-line feedback echo (`[📎 clipboard 1920×1080, 234 KB — attached to next message]`), and the NEXT `prompt.submit` ships with the image attached so the vision-capable model sees it in the same turn. Parity with Claude Desktop's paste behavior — minus OS-level Ctrl+V, which terminals fundamentally don't deliver image bytes through. Spans two repos: the client half is new `desktop/src/chatAttach.ts` (captureClipboardImage / captureScreenshot / readImageFile — platform-shelled like the alpha.6 clipboard handler: Windows PowerShell `Get-Clipboard -Format Image` + `System.Drawing.Bitmap.CopyFromScreen`, macOS `pngpaste`/`screencapture -x -t png`, Linux Wayland-first `wl-paste --type image/png`/`grim` with X11 `xclip`/`scrot` fallbacks) plus new slash-command branches in `desktop/src/commands/chat.ts`; the server half is ONE new `@method("image.attach.bytes")` RPC handler on the fork's `tui_gateway/server.py` (`Codename-11/hermes-agent` branch `feat/image-attach-bytes` → merged to `axiom`) that accepts `{session_id, format, bytes_base64, filename_hint?}`, validates magic bytes (PNG `89 50 4E 47` / JPEG `FF D8 FF` / WEBP `RIFF....WEBP`) to prevent content-type laundering, decodes to `~/.hermes/images/remote_<ts>_<rand6>.<ext>`, and appends to `session["attached_images"]`. The fork's **existing** `_enrich_with_attached_images` pipeline already handles the hard part — multimodal payload plumbing, session-scoped image state, vision-model routing — so this release is almost entirely about bridging client-captured bytes to the server-side state that's been there for months. The `tui` relay channel is a transparent RPC forwarder; zero relay changes. Fallback when hermes-host hasn't been updated yet: client's `image.attach.bytes` RPC call gets `method not found`, client catches it specifically and prints `[attach failed: method not found — server may need axiom rollout]` to stderr, REPL stays alive, user can still send text — no crash, and the exact error points the operator at the fix. Non-goals locked for this release: no Ctrl+V terminal keybinding (terminals don't pipe image bytes to stdin — that's OS-level), no Kitty/iTerm2 inline image protocols (defer to alpha.10+), no PTY shell-mode support (the remote `hermes` CLI has its own paste handling), no multimodal `prompt.submit` payload extension (the attach-then-submit pattern is cleaner and matches the existing server state model).

- **Desktop CLI alpha.6 — seamless-local dev pass.** Nine features across six parallel agent workstreams delivered in one integration. Plan: [`docs/plans/2026-04-23-desktop-alpha-6-seamless-local.md`](docs/plans/2026-04-23-desktop-alpha-6-seamless-local.md). (1) **Workspace-awareness envelope** (`#1+#8`) — new `src/workspaceContext.ts` detects `cwd`/`git_root`/`git_branch`/`git_status_summary`/`repo_name`/`hostname`/`platform`/`arch`/`active_shell` via parallel `git rev-parse`/`git status --porcelain=v1 --branch` calls under a 2 s total budget; `RelayTransport` auto-sends a `desktop.workspace` envelope after first `auth.ok` (guarded against reconnect re-send); server-side `plugin/relay/channels/desktop.py::DesktopChannel` stashes per-ws as ephemeral session metadata. Active-editor hints (`src/activeEditor.ts`) poll tmux (`display-message -p "#{pane_current_path}:#{pane_current_command}"`) or detect VSCode/Cursor via `$VSCODE_IPC_HOOK_CLI`+`TERM_PROGRAM`; dedupes envelopes so only actual changes fire. New `hermes-relay workspace` subcommand prints the context; `doctor` output gains a `workspace:` block. Gated client-side by `--watch-editor` for the poller; envelope itself is always-on. (2) **`hermes-relay update` self-update** (`#2`) — new `src/updater.ts` + `src/commands/update.ts`. Polls GitHub Releases API (the same prerelease-aware resolver the installer uses), semver-compares to `VERSION`, downloads asset with SHA256 verification, and atomic-swaps on POSIX (`fs.rename` — running process's inode stays live so the daemon keeps running; next invocation picks up new binary). Windows can't replace a running `.exe`, so the updater writes to `<bin>.new.exe` and `finalizePendingUpdate()` runs at the top of `main()` on every subsequent invocation to rename it into place. `--check` dry-runs; `--yes` skips confirm; `--json` emits machine-readable status. (3+4) **Editor tool + interactive patch approval** (`#3+#4`) — new `src/tools/handlers/editor.ts` for `desktop_open_in_editor(path, line?, col?, wait?)` with launcher detection (`$VISUAL`→`$EDITOR`→PATH probe for `code`/`cursor`/`subl`/`nvim`/`vim`→platform fallback); `-g` injection for GUI editors supports `:line:col`. `desktop_patch` now routes through `src/tools/patchApproval.ts` in interactive mode — renders unified diff with ANSI (green/red/cyan, NO_COLOR/isTTY aware), prompts `y/n/e/r` via readline on stderr; `e` opens the patch in `$EDITOR` and re-reads on close. Non-interactive modes (daemon, piped stdin) auto-reject with structured reason; never auto-accepts. Router (`src/tools/router.ts`) carries an `interactive` flag set at construct time (`stdin.isTTY && HERMES_RELAY_DAEMON !== '1'`). (5) **Conversation picker on connect** (`#5`) — new `src/sessionPicker.ts` calls tui_gateway's `session.list` JSON-RPC (same RPC upstream Ink TUI uses), renders a numbered list with human-readable age + first-prompt preview. `shell.ts` injects after banner / before PTY attach, appending `--resume '<id>'` to the hermes exec when a session is picked. `chat.ts` injects before the chat loop. `--session <id>` (chat: legacy alias for `--conversation`; shell: tmux session name — distinct), `--conversation <id>` and `--new` bypass the picker. Graceful degradation: 404 / "method not found" returns empty list silently, picker falls through to `'new'`. (9+12) **Clipboard + screenshot handlers** (`#9+#12`) — `src/tools/handlers/clipboard.ts` and `.../screenshot.ts`. Clipboard: Windows `powershell Get-Clipboard -Raw` / `$input | Set-Clipboard` (strips trailing CRLF); macOS `pbpaste`/`pbcopy`; Linux Wayland-first (`wl-paste`/`wl-copy` via `$WAYLAND_DISPLAY`), xclip fallback. 5 s timeout, 10 MB cap both directions. Screenshot: Windows writes a temp `.ps1` using `System.Drawing.Bitmap.CopyFromScreen` (honors multi-monitor via `Screen.AllScreens[display]`); macOS `screencapture -x -t png`; Linux `grim`→`scrot`→`import` fallback chain. `save_to` keeps the file; otherwise base64 + tempfile delete. 10 s timeout, 50 MB cap. All three wired into `shell.ts`/`chat.ts`/`daemon.ts` router handler map (9 handlers advertised now, up from 5). (13) **`hermes` alias** (`#13`) — `install.sh` creates a POSIX symlink `~/.hermes/bin/hermes → hermes-relay`; `install.ps1` drops a universal `.cmd` shim (no admin required — avoids Windows symlink Developer-Mode requirement). Collision-safe: only creates if nothing else lives at that name. Uninstall scripts remove the alias only when it points at our binary (preserves an unrelated upstream hermes-agent install).

- **Dev-iteration additions.** `npm run smoke` expanded from 4 to 5 assertions (added `workspace`); still runs locally in ~1 s post-build. CI workflow already runs the equivalent 5-command smoke on the Linux binary before publishing.

### Fixed

- **desktop CLI binary was a no-op on alpha.3** — installed cleanly, exited 0, produced zero stdout/stderr, wasn't "recognized" as a CLI. Root cause: cli.ts guarded its entry-point invocation with `fileURLToPath(import.meta.url) === process.argv[1]`, which is a valid Node idiom but fails in Bun-compiled binaries because the entry module has a synthetic URL that doesn't match the `.exe` path — the check evaluated false, `main()` was never called, binary exited 0 silently. Replaced with `import.meta.main` (cross-runtime: Bun, Node 20.11+, tsx) which is true in the entry module regardless of compile mode. All four invocation paths stay correct (Bun --compile binary, `bin/hermes-relay.js` shim, `tsx src/cli.ts`, test imports). Caught by adding a local `npm run smoke` target that runs the compiled Windows binary against `--version` / `--help` / `doctor` and verifies each produces output. Same smoke runs in `release-cli.yml` on the Linux target so future regressions of this class are caught pre-publish. Affects `desktop-v0.3.0-alpha.3`; fix ships as `desktop-v0.3.0-alpha.4`.
- **`hermes-relay --version` printed `0.0.0` in compiled binaries.** `readVersion()` tried to read `package.json` via `__dirname + '../package.json'`, which doesn't resolve in a Bun `--compile` binary (no real filesystem layout). Replaced with a build-time-generated `src/version.ts` module (`npm run gen:version` writes the version from package.json before every build and every `build:bin:*`). `readVersion()` now just returns the embedded constant. Works identically in tsx / Node / Bun.
- **desktop CLI binary segfaulted at startup on Bun 1.3.13 Windows x64** (`panic(main thread): Segmentation fault at address 0x100000D9C`). Root cause identified as Bun's experimental `--bytecode` flag; attempted fix in alpha.2 only edited `desktop/package.json`'s build scripts while the release workflow's inline `bun build` commands silently kept `--bytecode`, so alpha.2 shipped with the same crash. alpha.3 fixes the workflow two ways: (1) dropped `--bytecode` from the CLI release workflow, and (2) refactored the four build steps to delegate to `npm run build:bin:*` so the package.json scripts are the single source of truth for compile flags. Added a `bun --version` diagnostic step to the workflow for future triage. Versions affected: `desktop-v0.3.0-alpha.1` and `desktop-v0.3.0-alpha.2`. Fix ships as `desktop-v0.3.0-alpha.3`.
- **Installer couldn't find alpha-only releases.** GitHub's `/releases/latest/download/` URL deliberately skips prereleases, so the default `curl | sh` / `irm | iex` one-liner failed against alpha.1 with "maybe no Windows release for this version yet?" Both `install.sh` and `install.ps1` now query the Releases API directly (`GET /repos/.../releases`, filter to `desktop-v*` tags, take first) when `HERMES_RELAY_VERSION=latest`. Pinned versions unchanged.

### Added

- **Pre-release hardening: uninstall, doctor, first-run prompts, version-aware install.** Four parallel workstreams that close the "feels like a dev preview" gap before tagging `desktop-v0.3.0-alpha.1`. (1) **Uninstall scripts** — new `desktop/scripts/uninstall.{sh,ps1}` matching install one-liners, 3-tier: default `--binary-only` (removes binary + PATH entry, preserves `~/.hermes/remote-sessions.json`), `--purge` (also wipes the shared session store with a loud cross-surface warning about Ink TUI + Android tooling dependencies), `--service` (stub for when daemon service installers ship — prints canonical systemd/launchd/sc.exe paths without acting). iex-pipe safety: Windows falls back to `HERMES_RELAY_UNINSTALL_{PURGE,SERVICE}` env vars since `$args` drops through `irm | iex`. Shell rc files deliberately untouched (mirrors install.sh philosophy). (2) **`hermes-relay doctor` subcommand** — local-only diagnostic report (225 lines, `src/commands/doctor.ts`); human format uses `!!` prefix for warnings + hint line at bottom, `--json` for support-paste / scripts. Fields: version / binary_path / install_dir / on_path / sessions file + size + count + summaries (no tokens — total omission, not even prefix) / daemon detection (stat of canonical service unit file paths) / platform + node version. Case-insensitive PATH comparison on Windows. (3) **Interactive first-run fallback** — new `src/relayUrlPrompt.ts` (~180 lines) with `promptForRelayUrl()` (readline on stderr, `^wss?:\/\/\S+$` validation, 3 retries) and `resolveFirstRunUrl()` (auto-picks single stored session, numbered picker for multiple, first-run banner for zero). Wired into `connectAndAuth` in `shell.ts` / `chat.ts` / `tools.ts` and `resolvePairTarget` in `pair.ts`, replacing the hard `No relay URL` error. Fresh-install UX: bare `hermes-relay` now prints `Welcome to hermes-relay. No stored sessions yet — let's pair with a Server.` → URL prompt → pairing code prompt → drops into shell. `--non-interactive` still fails fast. Daemon command deliberately untouched — headless binaries must never prompt; fails closed on missing credentials/consent as before. (4) **Version-aware install** — `install.{sh,ps1}` now read `$target --version` before download and print one of `upgrading X → Y`, `reinstalling X`, `will replace (could not read version)`, or `installing fresh` (no prior install); post-install readback re-invokes the new binary to confirm. Pinned-version mismatches (`HERMES_RELAY_VERSION=desktop-v0.3.0-alpha.1`) print a non-fatal WARN rather than failing (pre-release version-name drift is expected). 5s timeout on the version call (where `timeout(1)` available); all diagnostic failures fall through to the "could not read version" path. Cross-version normalizer strips `desktop-v` / `v` prefix + `-alpha.N` / `-beta.N` / `-rc.N` suffix for matching. All structural flow (SHA256 verify, tmp cleanup, PATH injection, quarantine note) preserved additively. Type-check + build green; live smoke: `doctor` both modes, `daemon` fails-closed without credentials, help text includes all new surfaces.

- **`hermes-relay daemon` — headless WSS + tool router, lifts the "tools only work while a shell is open" ceiling.** New `desktop/src/commands/daemon.ts` subcommand that opens a persistent relay connection and attaches `DesktopToolRouter` without a TTY. The agent can now reach the user's machine any time of day — first step toward "feels-local" parity. Fails closed on missing credentials (no stored session + no `--token` → exits 1) and on missing consent (no `toolsConsented: true` on the stored record → exits 1 unless `--allow-tools` is passed alongside an explicit `--token`); a headless binary must never be the thing that first grants tool access. Inherits `RelayTransport`'s reconnect state machine as-is — exp backoff 1s → 30s (5min on 429), reconnect listeners persistent across close/reconnect cycles because `channelListeners` is a Map on the transport (not wiped on socket close), so the router's `attach()` fires exactly once. Structured logging defaults to JSON-line on stderr (parseable by journald / log shippers / jq), auto-switches to human-readable when stderr is a TTY, or force either with `--log-json` / `--log-human`. Lifecycle events: `starting` → `authed` (includes `server_version`, `transport`) → `ready` (with `advertised_tools` list) → `reconnecting` (attempt + delay_ms) / `reconnected` → `shutdown` on SIGTERM/SIGINT/SIGHUP → `transport_exited` when the transport exhausts reconnects (exits 1 so the service manager restarts fresh). Live smoke against `ws://192.168.1.100:8767`: `starting` → `authed` (server 0.6.0) → `ready` (5 tools advertised) in ~120ms. New BOOLEAN_FLAGS entries: `log-human`, `log-json`, `allow-tools`. Service installers for Windows `sc.exe` / systemd user unit / macOS launchd plist are the obvious follow-up; the daemon binary is runnable standalone today via `hermes-relay daemon --remote <url>`.

- **Desktop CLI v0.2 — PTY shell, local tool routing, multi-endpoint pairing, reconnect + TOFU, devices, contextual banner.** The `@hermes-relay/cli` package at `desktop/` grew from a chat-only scripting surface into a full Hermes-experience thin client. Bare `hermes-relay` now drops into `shell` mode (interactive PTY pipe through the existing relay `terminal` channel → `tmux new-session -A` + post-attach `exec hermes` → the full local `hermes` banner/skin/session id verbatim, zero server changes). `Ctrl+A .` detaches preserving tmux; `Ctrl+A k` destroys it. New `devices` subcommand drives the relay's `GET/DELETE/PATCH /sessions` HTTP endpoints for listing, revoking, and extending server-side paired-device tokens. Status now surfaces `grants:` (per-channel expiry) and `expires:` (session TTL) pulled from the `auth.ok` handshake the transport already received — `RemoteSessionRecord` gained `grants`, `ttlExpiresAt`, `endpointRole`, `toolsConsented` (additive, back-compat preserved via a `SaveSessionOptions | string | null` overload on `saveSession`). Contextual connect banner (`Connected via LAN (plain) — server 0.6.0`) replaces the flat `Connected (server X)` line across `chat` + `shell`. Multi-endpoint pairing (ADR 24): `--pair-qr <payload>` / `HERMES_RELAY_PAIR_QR` accepts a full v3 QR payload (compact JSON or base64), decodes the `endpoints[]` array, probes each candidate with strict-priority-within-tier racing (`Promise.any` + `AbortSignal.any`, 4 s per-candidate timeout, 60 s reachability cache), and auto-selects the first reachable — role propagates into the banner + stored record. Reconnect-on-drop: `RelayTransport` gained a `ReconnectState` machine (`idle|connecting|connected|reconnecting`), exponential backoff (1 s → 30 s, 5 min on 429), `reconnectGate` re-checked both at schedule time and post-backoff (matches Android's mid-sleep purge-race lesson), `'reconnecting'` + `'reconnected'` events, and bufferedEvents-cleared-on-reconnect. TOFU cert pinning: TLS probe runs before the WebSocket opens on `wss://`, extracts peer-cert SPKI sha256 (`sha256/<base64>`, OkHttp-compatible), compares against the stored pin or captures it first-time; mismatches error out with a human-readable "re-pair to reset" pointer. Client-side tool routing (Phase B): new `desktop` relay channel on the server (`plugin/relay/channels/desktop.py` + `plugin/tools/desktop_tool.py` registering `desktop_read_file` / `desktop_write_file` / `desktop_terminal` / `desktop_search_files` / `desktop_patch`) forwards tool calls from Hermes to the connected Node CLI; client-side `DesktopToolRouter` dispatches to in-process handlers (`fs`, `terminal`, `search`) under a 30 s AbortController, 30 s heartbeat advertising the tool names. Gated behind a one-time per-URL consent prompt (`toolsConsented` on the session record) + `--no-tools` kill-switch; non-TTY stdin fails closed. New files on the client: `src/banner.ts`, `src/endpoint.ts`, `src/pairingQr.ts`, `src/certPin.ts`, `src/commands/devices.ts`, `src/tools/router.ts`, `src/tools/consent.ts`, `src/tools/handlers/{fs,terminal,search}.ts`. New files on the server: `plugin/relay/channels/desktop.py`, `plugin/tools/desktop_tool.py`, `docs/relay-protocol.md §3.5`. Still zero runtime deps on the client (Node ≥21 global `WebSocket` + `fetch` + `tls.connect` + `node:crypto` X509Certificate + `AbortSignal.any`). Build clean; live smoke passed for `status` / `tools` / `devices`; interactive `shell` + tool-call smoke pending user walk-through. Delivered as four parallel implementation agents (multi-endpoint, reconnect+TOFU, server-side desktop, client-side tool handlers) + one synthesis-and-integration pass; the `connectAndAuth → {relay, url, endpointRole}` return-shape refactor in `chat.ts` / `shell.ts` / `tools.ts` unifies how `--pair-qr`'s winning-endpoint URL overrides `--remote` across every subcommand.

- **Desktop thin-client CLI (`@hermes-relay/cli`) v0.1 under `desktop/`.** Node ≥21 package — installable via `npm install -g @hermes-relay/cli`, `npx @hermes-relay/cli`, or the new `scripts/install.sh` / `install.ps1` curl+iwr one-liners. One `hermes-relay` binary with four subcommands: `chat` (REPL + one-shot + piped-stdin, default), `pair` (one-time handshake → persists session token), `status` (local read of `~/.hermes/remote-sessions.json`), `tools` (`tools.list` RPC → enabled/available toolsets on the server). Credential precedence matches the Ink TUI exactly: `--token` → `HERMES_RELAY_TOKEN` → `--code` → `HERMES_RELAY_CODE` → stored session → interactive readline prompt. Reuses the **same** `~/.hermes/remote-sessions.json` store as the TUI, so a user paired via either surface sees the other work with no re-pair. Zero server changes: the CLI consumes the existing relay `tui` WSS channel + `tui_gateway` subprocess events (`message.delta`, `tool.start/complete`, `thinking.delta`, `status.update`, `error`, `approval.request`, …) and renders them as plain lines to stdout, with decorated tool arrows on stderr. Flags: `--remote <url>`, `--code <CODE>`, `--token <TOKEN>`, `--session <id>`, `--json` (event-per-line for `jq`), `--verbose`, `--quiet`, `--no-color`, `--non-interactive`, `--reveal-tokens` (opt-in full-token output on `status --json` — default redacts). Transport, gateway types, session storage, graceful-exit, and rpc helpers are **vendored verbatim** from `hermes-agent-tui-smoke/ui-tui/src/` (feat/tui-transport-pluggable) with a header note; the CLI and TUI stay in lockstep on the envelope protocol (docs/relay-protocol.md §3.7) until the shared surface can be lifted into a `@hermes-relay/core` package post-stabilization. SIGINT during a turn calls `session.interrupt` via a per-turn `{ promise, cancel }` handle — the REPL's cancellation state lives and dies with the turn so a late-arriving `error` event for a cancelled turn can't be misread by the next turn's handler. Smoke-tested end-to-end against `ws://192.168.1.100:8767` (hermes-relay 0.6.0, hermes-agent 0.10.0): connect/auth/session.create/prompt.submit/tools.list/--json/piped-stdin all clean. Not yet wired: interactive approval/clarify/sudo/secret request response (renderer logs a warning; out of scope for v0.1). Upstream PR candidate once the sibling Ink TUI stabilizes — see `desktop/README.md` and vault `Desktop Client.md` for the broader thin-client roadmap.

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

- **Top-bar connection chip → inline switcher in the Agent sheet.** The app-wide `ConnectionChip` row that used to sit above every primary tab has been removed. Multi-connection switching now renders as a radio list inside the existing Agent sheet's Connection section (matching the visual pattern of the Profile and Personality sections above it), visible only when ≥2 connections are paired. Tapping a non-active connection fires `switchConnection` + a confirmation toast. Reasons: the chip duplicated the Agent sheet's Connection metadata, ate vertical space above every screen, and exposed the placeholder's `New connection…` label whenever an orphan existed (the root cause of the double-pair confusion). Dead code removed: the `ConnectionChip` import, the `connectionSheetVisible` state, the `ConnectionSwitcherSheet` render block at the bottom of `RelayApp`, and the `connectionChipVisible` / `activeConnection` vals. `ConnectionSwitcherSheet.kt` itself is kept for future programmatic callers.

### Added

- **Card-dispatch → server session sync** (completes ADR 26). Every [HermesCardDispatch] now carries a `syncedToServer` idempotency flag; on the next chat send, `CardDispatchSyncBuilder` synthesizes unsynced dispatches into OpenAI-format `assistant`+`tool` pairs under a namespaced synthetic tool name `hermes_card_action` and splices them into the request body alongside the existing voice-intent synthetic messages. `ChatHandler.markCardDispatchesSynced` commits the flag after the API client accepts the request — same post-handoff timing as voice intents, so a thrown request-building exception leaves both streams retryable. Guarantees the LLM sees prior card interactions ("you approved the `Run shell command?` card") across server restarts and reconnects, including `open_url` dispatches that never go through `sendMessage`. Unit-tested under `CardDispatchSyncBuilderTest` (pure-function JVM tests, no Android deps).
- **Rich cards in chat via `CARD:{json}` inline markers** (ADR 26). Assistant messages can now surface structured Material 3 cards — skill results, approval prompts, link previews, calendar entries, weather — emitted as a single-line `CARD:{...}` alongside prose text. Follows the same streaming-endpoint-agnostic marker recipe as `MEDIA:`, so it works unchanged on `/v1/runs`, `/api/sessions/{id}/chat/stream`, and `/v1/chat/completions`. New `HermesCard` data class (`@Serializable`, `ignoreUnknownKeys=true` so newer agent schemas don't crash older phone builds) carries `title` / `subtitle` / `body` (markdown) / `fields` / `actions` / `footer` / `accent` (`info`/`success`/`warning`/`danger`). Built-in types: `skill_result`, `approval_request`, `link_preview`, `calendar_event`, `weather`; unknown types render via a generic fallback. `approval_request` intentionally mirrors Slack's exec-approval pattern (Allow / Deny with primary/danger button styles) so upstream Phase B adapter parity is a translation exercise, not a data-model rethink. Action dispatch (`send_text` default, `slash_command`, `open_url`) routes through `ChatViewModel.dispatchCardAction`, which stamps a `HermesCardDispatch` on the owning message before forwarding so the card collapses into a "Chose: X" confirmation even if the side effect fails. Renderer is `HermesCardBubble.kt` — accent stripe + Icon + Title/Subtitle + markdown body + fields table + FlowRow of action buttons. Cards render between the assistant's prose and any attachments in `MessageBubble`.
- **CI test jobs advisory on `dev`, strict on `main`.** Both `.github/workflows/ci-android.yml` (`test`) and `.github/workflows/ci-server.yml` (`unit-tests`) now carry `continue-on-error: ${{ github.ref != 'refs/heads/main' && github.base_ref != 'main' }}` — tests still run on every dev push/PR and surface annotations and reports, but they no longer red-gate the merge. Lint stays strict on both branches (deliberate: lint debt should still block). The release-merge PR from `dev` → `main` flips tests back to strict, so nothing sneaks through to a tagged release.
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
  Management** lists paired devices + health + Server version;
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

- **Voice actions now reach the server-side LLM's session memory.** Previously, phone-local voice intents (`open Chrome`, `text Sam saying hi`, etc.) ran in-process via `BridgeCommandHandler.handleLocalCommand` and appended local-only trace bubbles to the chat scroll. The Hermes API server's session never learned about them, so a follow-up text question like "did that work?" hit the LLM with no context and returned hallucinated answers (per a 2026-04-14 on-device repro).
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
- **Relay feature toggle** — Server settings and pairing sections gated behind developer options in release builds
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
- **Dual connection model** — API Server (HTTP) for chat, Server (WSS) for bridge/terminal
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
- **Settings** — API Server + Server config, theme, reasoning toggle, data export/import/reset
- **Offline detection** — banner shown when network connectivity is lost
- **What's New dialog** — shown automatically when app version changes
- **Splash screen** — branded splash via core-splashscreen API
- **Network security** — cleartext restricted to localhost only

#### Infrastructure
- **Server** — Python aiohttp WSS server for bridge/terminal channels
- **CI/CD** — GitHub Actions for lint, build, test, and tag-driven releases
- **Claude Code automation** — issue triage, PR fix, chat, and code review workflows
- **Dependabot** — weekly Gradle + GitHub Actions dependency updates with auto-merge
- **Dev scripts** — build, install, run, test, relay via scripts/dev.bat
- **ProGuard rules** — okhttp-sse, markdown renderer, intellij-markdown parser

[Unreleased]: https://github.com/Codename-11/hermes-relay/compare/android-v1.4.4...HEAD
[1.4.4]: https://github.com/Codename-11/hermes-relay/compare/android-v1.4.3...android-v1.4.4
[1.4.3]: https://github.com/Codename-11/hermes-relay/compare/android-v1.4.2...android-v1.4.3
[1.4.2]: https://github.com/Codename-11/hermes-relay/compare/android-v1.4.1...android-v1.4.2
[1.4.1]: https://github.com/Codename-11/hermes-relay/compare/android-v1.4.0...android-v1.4.1
[1.4.0]: https://github.com/Codename-11/hermes-relay/compare/android-v1.3.0...android-v1.4.0
[1.0.0]: https://github.com/Codename-11/hermes-relay/compare/android-v0.8.0...android-v1.0.0
[0.8.1]: https://github.com/Codename-11/hermes-relay/compare/android-v0.8.0...android-v0.8.1
[0.8.0]: https://github.com/Codename-11/hermes-relay/compare/v0.7.0...android-v0.8.0
[0.7.0]: https://github.com/Codename-11/hermes-relay/compare/v0.6.1...v0.7.0
[0.1.0]: https://github.com/Codename-11/hermes-relay/compare/v0.1.0-beta...v0.1.0
[0.1.0-beta]: https://github.com/Codename-11/hermes-relay/releases/tag/v0.1.0-beta
