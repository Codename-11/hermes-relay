# Hermes-Relay — TODO

Open items that don't fit a formal Phase plan but shouldn't be lost. Items move from here into a Plan in `docs/spec.md` or an Obsidian Phase plan once they're ready to schedule.

For shipped work, see `DEVLOG.md`. For architectural decisions, see `docs/decisions.md`.

---

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
- **`hermes-relay-self-setup` SKILL.md as a precedent** — we just shipped a self-installing skill that an LLM can fetch from a raw GitHub URL and execute. Does this pattern generalize? Could it become a recommended way for any third-party Hermes project to ship setup automation?
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
- **`llms.txt` standard** — explicitly skipped in favor of the `hermes-relay-self-setup` SKILL.md path; revisit if the standard gains traction in the agent ecosystem
- **`markdown-renderer` 0.40.x API update** — pinned at `0.30.0` in `gradle/libs.versions.toml` because 0.40.2 introduced breaking API changes that `app/src/main/kotlin/com/hermesandroid/relay/ui/components/MarkdownContent.kt` hasn't been updated for. Specifically: `markdownColor()` drops `codeText`/`linkText`, `MarkdownCodeBlock`/`MarkdownCodeFence` inner lambdas now take a 3rd `TextStyle` arg, and `MarkdownHighlightedCode`'s 3rd param is now `TextStyle` instead of `Highlights.Builder`. Dependabot auto-merged the bump on 2026-04-13 which silently broke CI; reverted for the v0.3.0 release. Update requires reading the new library API docs and testing in Studio — not a blind fix. Consider adding a dependabot ignore rule for `markdown-renderer` major bumps until this is handled.
- **Dependabot auto-merge guardrails** — Dependabot merged breaking bumps despite CI failing. Investigate why `.github/workflows/dependabot-auto-merge.yml` isn't gating on CI status, and consider adding an ignore rule for packages we know need manual attention on major bumps (`markdown-renderer`, compose BOM, activity-compose).

---

## Attachments (shipped 2026-06-18 — `docs/plans/2026-06-18-attachment-experience.md`)

- **B3 — download progress + cancel.** Inbound fetch is un-cancelable; the previews work scaffolded an indeterminate bar + nullable `onCancel`. Live wiring needs the fetch-path owner (`ChatViewModel`/`Attachment`) to expose determinate progress (Content-Length) + a cancel hook.
- **A6 — multi-image gallery.** N images in one message → grid + swipe-across viewer (Telegram media-group parity).
- **C5 — agent-side sensitivity config gate.** `RELAY_MEDIA_SENSITIVITY_HINTS` (env or per-profile) instructing the agent to annotate sensitive media via the prompt-builder. Transport (relay `X-Media-Sensitive` header + client blur) already ships; the agent isn't asked to set the bit yet.
- **Relay thumbnails (D6).** Server-side thumbnail generation to avoid full-size download for cards/galleries. Needs an image lib (Pillow not currently a dep) — evaluate before adding.
- **D5 — outbound upload progress.** No per-attachment progress during the 60s gateway PDF-render window.

## Voice overhaul (planned — `docs/plans/2026-06-18-voice-overhaul.md`)

- **Per-profile voice on Standard (upstream PR).** Upstream `/api/profiles/*` has no voice field and `/api/audio/*` is host-global. Long-term: PR a voice section to the profile config + make `/api/audio/*` honor the active/`?profile=` profile. The relay path already carries per-profile voice; ship that first.

## Chat clean-mode + pets (planned — `docs/plans/2026-06-18-chat-clean-mode-and-pets.md`)

- **Part-A chat polish (optional bundle).** Per-code-block copy + horizontal scroll, visible copy affordance, mid-stream stall feedback, profile/skill-aware empty-state chips, the ~40-flow recomposition hotspot at the top of `ChatScreen`, sphere `contentDescription` + reduced-motion gate.
