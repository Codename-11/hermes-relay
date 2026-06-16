# Potential Upstream Contributions to hermes-agent

Improvements that would benefit hermes-relay (and other frontends) if added to [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent).

## Current Upstream PR Alignment

- PR #33134 (`feat(api-server): session control API — sessions/chat/fork/SSE-stream`) merged the canonical upstream path for `/api/sessions/*`, message history, fork, chat, and chat stream. It salvaged the useful portion of PR #29302, which superseded the older broad PR #8556. Hermes-Relay should prefer these native routes and keep the bootstrap only as an older-build compatibility overlay.
- PR #33016 (`feat(api-server): add GET /v1/skills and /v1/toolsets`) merged the canonical read-only skill/toolset discovery path. Hermes-Relay should prefer `/v1/skills` and `/v1/toolsets` over legacy `/api/skills` list shapes.
- PR #8199 (`feat(api): add native audio transcription and speech endpoints`) is the canonical upstream path for core STT/TTS execution through `/v1/audio/transcriptions` and `/v1/audio/speech`. Hermes-Relay should keep `/voice/*` as the paired-device facade but eventually call those native core endpoints internally before falling back to private helper imports.
- PR #29364 (`feat: add API server audio endpoints`) should not become a competing `/api/audio/*` API if #8199 remains the accepted audio base. Rework it as a discovery/compatibility follow-up or close it after confirming the upstream maintainer preference.

## 1. `GET /api/commands` — Expose Gateway Slash Commands

**Current state:** Built-in slash commands (29 gateway-compatible commands like `/new`, `/retry`, `/model`, `/yolo`, etc.) are defined in `hermes_cli/commands.py` as `COMMAND_REGISTRY` / `GATEWAY_KNOWN_COMMANDS`. There is no HTTP API to fetch them — the app must hardcode the list.

**Proposed:** Add a `GET /api/commands` endpoint to `gateway/platforms/api_server.py` that returns `GATEWAY_KNOWN_COMMANDS` with their names, descriptions, aliases, categories, and argument specs.

**Response format:**
```json
{
  "commands": [
    {
      "name": "/new",
      "aliases": ["/reset"],
      "description": "Start a new session",
      "category": "session",
      "args": null
    },
    {
      "name": "/model",
      "aliases": [],
      "description": "Switch model for this session",
      "category": "configuration",
      "args": "[model] [--global]"
    }
  ]
}
```

**Impact:** All frontends (hermes-relay, hermes-workspace, ClawPort) could dynamically show available commands without hardcoding. New commands added upstream would appear automatically.

**Workaround (current):** 29 gateway commands hardcoded in `ChatScreen.kt`, manually synced with `hermes_cli/commands.py`. Personality commands generated from `GET /api/config`. Skills from `GET /api/skills`.

## 2. Personality Switching via Dedicated API Parameter

**Current state:** The chat streaming endpoint (`POST /api/sessions/{id}/chat/stream`) accepts `system_message` for ephemeral system prompts. To switch personalities, the app sends the personality's system prompt text as `system_message`. This works but is indirect — the app must know the full prompt text.

**Proposed:** Add a `personality` parameter to the chat request body that the server resolves against `config.agent.personalities`. The server would look up the system prompt and apply it, without the client needing to send the full prompt text.

```json
{
  "message": "Hello",
  "personality": "creative"
}
```

**Impact:** Cleaner API contract, client doesn't need to fetch and send system prompt text, server-side validation of personality names.

**Workaround (current):** App fetches `config.agent.personalities` map, sends the system prompt as `system_message`.

## 3. Third-Party Plugin CLI Commands (Resolved Upstream)

**Current state (2026-06-16 source check against `55cb4103`):** current upstream discovers plugins before top-level CLI parser finalization and iterates `get_plugin_manager()._cli_commands.values()` in `hermes_cli/main.py`. Third-party plugins that call `ctx.register_cli_command(...)` now reach plugin-provided commands such as `hermes pair` and `hermes relay` through the plugin-native path.

**Impact:** no new upstream patch is needed for generic plugin CLI command dispatch. Hermes-Relay should prefer plugin-registered `hermes pair` / `hermes relay` on current upstream installs once the Hermes-Relay plugin is installed and enabled. These are not built-in Hermes core commands.

**Compatibility fallback:** keep the dashed `hermes-pair` shell shim and `/hermes-relay-pair` slash command while we support older Hermes builds and existing scripts. They call the same implementation and can be retired only after the supported baseline includes the upstream CLI discovery fix and docs/install examples no longer depend on the shim.

## 4. Follow Symlinks in Skill Discovery

**Current state:** `~/.hermes/skills/<category>/<name>/` directories are scanned for `SKILL.md` files and auto-registered as slash commands. But if `<name>` is a **symlink** to a directory outside `~/.hermes/skills/`, the scanner skips it — the skill doesn't appear in `hermes skills list` and its `/name` slash command isn't registered. Real directory copies work; symlinks don't.

**Proposed:** follow symlinks during skill discovery, the same way `skills.external_dirs` already handles external paths. The security model is identical — the user explicitly placed the symlink in their skills dir, same as they'd place an external dir in config.

**Workaround (current):** our `install.sh` registers the repo's `skills/` directory via `skills.external_dirs` in `~/.hermes/config.yaml` instead of symlinking. Works but forces every third-party skill author to touch the user's config.yaml instead of dropping a symlink.

## 5. Gateway Slash-Command Preprocessor on API Server Chat Endpoints

**Current state:** Built-in gateway slash commands (`/model`, `/new`, `/retry`, `/help`, the ~29 commands defined in `hermes_cli/commands.py::GATEWAY_KNOWN_COMMANDS`) are intercepted by in-process platform adapters — Discord, Telegram, Slack, Matrix, Signal, BlueBubbles, etc. — all of which route inbound `MessageEvent`s through `GatewayRouter._handle_message` at `gateway/run.py:2645–2929`. That router owns a ~300-line dispatch chain and the persistent state each command handler mutates (`_session_model_overrides`, `_agent_cache`, the `adapters[platform].send_model_picker(...)` helpers on other adapters, etc.).

`APIServerAdapter` (the `gateway/platforms/api_server.py` adapter serving `/v1/runs` and `/v1/chat/completions`) **intentionally bypasses the router** — see the `run.py:3148` comment flagging api_server as an "excluded platform" from the router notification path. It constructs a fresh agent per request with no persistent session state. The practical effect: a frontend typing `/model` gets the command text forwarded verbatim to the LLM, which hallucinates a plausible but wrong reply ("`/model` is a client-side command, I won't execute it") because the model has no way to know the gateway was supposed to handle it.

**Proposed — a two-stage arc, each stage a small, independently reviewable PR:**

**Stage 1 — stateless preprocessor (follow-up to the API-server chat work).** A lightweight preprocessor in `api_server.py`'s `/v1/runs` + `/v1/chat/completions` handlers that detects a leading `/` in the user text, matches the first token against `GATEWAY_KNOWN_COMMANDS`, and splits on command type:

- **Stateless commands** (`/help`, `/commands`, and any others that can execute without touching router-owned state) are dispatched via existing helpers (`gateway_help_lines()` at `hermes_cli/commands.py:340`) and returned as a synthetic SSE stream matching the handlers' existing event shape.
- **Stateful commands** (`/model`, `/new`, `/retry`, `/undo`, `/compress`, `/title`, `/resume`, `/branch`, `/rollback`, `/yolo`, `/reasoning`, `/personality`, and most of the registry) return a deterministic, helpful SSE notice along the lines of *"The `/model` command requires a persistent session and isn't available on the stateless `/v1/runs` endpoint. Use `/api/sessions/{id}/chat/stream` (native since PR #33134) or a channel with session state (Discord, CLI, Telegram)."*
- **Unknown** and **cli-only** commands fall through to the LLM path unchanged.
- **Preprocessor exceptions** fall through to the LLM path unchanged — a preprocessor bug must never take down a normal chat request.

This respects upstream's intentional design (api_server stays stateless, no router coupling) while fixing the hallucination symptom and unlocking the commands that *can* run statelessly.

**Stage 2 — stateful dispatch on `/api/sessions/{id}/chat/stream` (now unblocked by PR #33134).** A separate PR can add a preprocessor **scoped to the session chat stream endpoint only**, using the URL's `session_id` as the persistence handle. Stateful commands become session-scoped dict writes (`session.model_override = new_model`) without refactoring `GatewayRouter` or plumbing api_server into the router. This matches upstream's partition cleanly: `/v1/*` remains stateless and OpenAI-compatible; statefulness lives on `/api/sessions/*`.

**Why not one big PR:** a full GatewayRouter refactor plus api_server plumbing was considered and rejected. It would touch 10+ files across subsystems normally owned separately, fight the documented "api_server is excluded from router notification" design decision, and review as a much larger change than the value added. The two-stage arc ships faster, reviews cleaner, and matches the upstream partition better.

**Impact:** frontends that speak the API server (hermes-relay, hermes-workspace, ClawPort, and any OpenAI-compatible client that points at the Hermes base URL) get the same built-in command surface as Discord/Telegram/CLI, in two predictable stages.

**Workaround (current / near-term):** `hermes_relay_bootstrap/_command_middleware.py` mirrors Stage 1 as an aiohttp middleware injected at bootstrap time, so older upstream installs that ship with the relay get the hallucination fix and the stateless commands without waiting for an upstream release. The bootstrap middleware feature-detects native support and should no-op once Stage 1 lands upstream.

## 6. API Server Audio Endpoints for Relay-Compatible STT/TTS

**Current state:** Hermes-Relay exposes `/voice/config`, `/voice/transcribe`, and `/voice/synthesize` on the relay server because upstream Hermes voice tooling historically lived in CLI/TUI helpers rather than the API server. That works for mobile pairing and grants, but the relay still has to import private STT/TTS helpers through `plugin.relay.upstream_voice`.

**Canonical upstream path:** PR #8199 adds OpenAI-style core endpoints:

- `POST /v1/audio/transcriptions`
- `POST /v1/audio/speech`

Those should be treated as the canonical Hermes core STT/TTS execution surface once merged.

**Relay follow-up shape:** Keep `/voice/*` as the relay-owned mobile facade for pairing/session grants, profile labeling, transport guards, and backwards-compatible Android clients. Internally, relay should prefer core `/v1/audio/*` when present and fall back to helper imports only when running against older core builds.

**Avoid:** Do not establish `/api/audio/*` or `/voice/*` as competing Hermes core APIs if `/v1/audio/*` is accepted upstream. Any PR based on our earlier `/api/audio/*` exploration should be closed or reduced to capability-discovery/compatibility metadata on top of the canonical audio PR.

**Impact:** Hermes-Relay can stop depending on private voice helper imports over time while still preserving its paired-device trust model and existing Android wire contract.

**Workaround (current):** Relay calls `tools.transcription_tools.transcribe_audio` and `tools.tts_tool.text_to_speech_tool` through `plugin.relay.upstream_voice`, with route auth constrained in `plugin.relay.voice_auth`.

## 7. Rich Card Rendering Across Platform Adapters (ADR 26 Phase B)

**Current state (upstream).** `gateway/platforms/base.py` exposes no rich-content abstraction — just `send()` / `send_image()` / `edit_message()`. Confirmed:
- `discord.py` contains zero `discord.Embed()` instantiations; all assistant output flows as plain text chunks + native file attachments.
- `slack.py` uses Block Kit **only** for `send_exec_approval` (a 4-button command-approval dialog). No block rendering for regular assistant output.
- The base class hints at a rich-surface concept via `REQUIRES_EDIT_FINALIZE` + its DingTalk AI Cards reference, but stops short of a uniform abstraction.

**Current state (hermes-relay Android).** We shipped Phase A of ADR 26 — agents emit a `CARD:{json}` inline line marker in the text stream, and the phone parses + renders it as a Material 3 card. The envelope (type / title / body / fields / actions / accent / footer, with semantic `info` / `success` / `warning` / `danger` colors and `primary` / `secondary` / `danger` button styles) is intentionally compatible with both Slack Block Kit and Discord Embeds. Session-memory sync is shipped too — card action dispatches materialize as OpenAI `assistant`+`tool` pairs under a synthetic `hermes_card_action` tool name on the next chat send.

**Proposed upstream contribution.** Add a `gateway/rich_cards.py` helper that takes a card dict and returns platform-appropriate output:
- **Discord** → a `List[discord.Embed]` + optional `discord.ui.View` for buttons (Discord's first real embed usage; approval_request cards become interactive with real buttons).
- **Slack** → a Block Kit `{"blocks": [...]}` structure (reuses the existing Block Kit precedent from `send_exec_approval` for any card, not just command approvals).
- **Plain-text platforms** (Signal, SMS, email) → a deterministic markdown rendering so the same card gracefully degrades.

Adapters would gain a `send_card()` method with a default fallback that renders the card as markdown via the helper. Platforms override to emit native richer output. Matches how `send_image()` has a default text-URL fallback today.

**Envelope shape for the upstream PR** (identical to our phone-side parser — no translation needed):
```json
{
  "type": "approval_request",
  "title": "Run shell command?",
  "body": "`rm -rf /tmp/cache`",
  "accent": "warning",
  "fields": [{"label": "Working dir", "value": "/home/user"}],
  "actions": [
    {"label": "Allow", "value": "/approve", "style": "primary"},
    {"label": "Deny",  "value": "/deny",    "style": "danger"}
  ]
}
```

**Impact.** Every hermes-agent frontend — Discord server, Slack workspace, mobile relay — renders skill results and approval prompts with the same information density. Skills authored once work everywhere. Removes the ambiguity about how (or whether) rich content surfaces across platforms.

**Held because.** We want concrete phone-side card fidelity issues to surface first (via real usage) before designing the translation layer. Speculating on Discord Embed layout without watching at least a dozen real cards flow through our own UI tends to produce a helper that looks right on paper and wrong in practice.

**Workaround (current):** Rich cards only render on hermes-relay Android today. Other platforms receive the raw `CARD:{json}` line as plain text — visible as a JSON string in the message, which is ugly but functionally harmless (users can still read the content). A quick-fix middleware in `hermes_relay_bootstrap/` could strip the markers on platforms that don't consume them, but we haven't prioritized it since Hermes-Relay is currently the only UI on this pipeline.

## 8. Terminal HTTP API (for non-relay setups)

**Current state:** hermes-agent's `terminal_tool.py` supports 6 backends (local, Docker, SSH, Modal, Daytona, Singularity) but is only callable internally by the agent during conversations. There is no HTTP API for interactive terminal sessions.

**Proposed:** Add terminal session endpoints to the gateway, similar to what hermes-workspace implements (HTTP/SSE for output, POST for input/resize/close).

**Impact:** Would enable mobile terminal access without requiring the separate relay server. Any hermes-agent install would support remote terminal.

**Workaround (current):** Our relay server (`relay_server/`) provides terminal via WSS as a separate service. This works but requires deploying an additional component.

## Notes

- These are suggestions, not requirements. The app works without any of them.
- Priority: #1 (`/api/commands`) is the most impactful for code maintainability.
- All workarounds are documented in `docs/decisions.md`.
