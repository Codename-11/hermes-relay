# Potential Upstream Contributions to hermes-agent

Improvements that would benefit hermes-relay (and other frontends) if added to [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent).

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

## 3. Wire Third-Party Plugin CLI Commands into Top-Level Argparser

**Current state (hermes-agent v0.8.0):** `PluginContext.register_cli_command(name, help, setup_fn, handler_fn, description)` is implemented in `hermes_cli/plugins.py:192` and plugins can call it during `register(ctx)`. The resulting registrations are stored in `PluginManager._cli_commands`, and a module-level getter `get_plugin_cli_commands()` exists at line 592. But `hermes_cli/main.py:5236` only consults `plugins.memory.discover_plugin_cli_commands()` (memory-subsystem-specific) when building the top-level argparser — it never iterates the generic `_cli_commands` dict.

**Result:** third-party plugins (like ours) correctly register sub-commands via the documented API, Hermes reports them loaded successfully in `hermes plugins list`, but typing `hermes <subcommand>` at the shell returns `argument command: invalid choice`. The plugin CLI path is effectively dead for anything outside the memory plugin subsystem.

**Proposed patch:** immediately after the existing memory discovery loop in `main.py`, add a parallel loop over `get_plugin_cli_commands()` and wire each entry into the subparsers the same way. Something like:

```python
try:
    from hermes_cli.plugins import get_plugin_cli_commands
    for cmd_name, cmd_info in get_plugin_cli_commands().items():
        if cmd_name in subparsers.choices:
            continue  # memory loop already handled it
        plugin_parser = subparsers.add_parser(
            cmd_name,
            help=cmd_info["help"],
            description=cmd_info.get("description", ""),
            formatter_class=__import__("argparse").RawDescriptionHelpFormatter,
        )
        cmd_info["setup_fn"](plugin_parser)
except Exception as _exc:
    import logging as _log
    _log.getLogger(__name__).debug("Generic plugin CLI discovery failed: %s", _exc)
```

**Impact:** any plugin declaring `ctx.register_cli_command(...)` in `register()` would instantly get a working `hermes <name>` sub-command. Our hermes-relay plugin would unlock `hermes pair` and `hermes relay start` without shell shims. All other third-party plugins would benefit too.

**Workaround (current):** ship a `hermes-pair` shell shim at `~/.local/bin/hermes-pair` that execs `<venv-python> -m plugin.pair "$@"`, plus a `/hermes-relay-pair` skill that auto-registers as a slash command in any Hermes chat session. Both work but are plumbing around the gap rather than using the intended API.

## 4. Follow Symlinks in Skill Discovery

**Current state:** `~/.hermes/skills/<category>/<name>/` directories are scanned for `SKILL.md` files and auto-registered as slash commands. But if `<name>` is a **symlink** to a directory outside `~/.hermes/skills/`, the scanner skips it — the skill doesn't appear in `hermes skills list` and its `/name` slash command isn't registered. Real directory copies work; symlinks don't.

**Proposed:** follow symlinks during skill discovery, the same way `skills.external_dirs` already handles external paths. The security model is identical — the user explicitly placed the symlink in their skills dir, same as they'd place an external dir in config.

**Workaround (current):** our `install.sh` registers the repo's `skills/` directory via `skills.external_dirs` in `~/.hermes/config.yaml` instead of symlinking. Works but forces every third-party skill author to touch the user's config.yaml instead of dropping a symlink.

## 5. Gateway Slash-Command Preprocessor on API Server Chat Endpoints

**Current state:** Built-in gateway slash commands (`/model`, `/new`, `/retry`, `/help`, the ~29 commands defined in `hermes_cli/commands.py::GATEWAY_KNOWN_COMMANDS`) are intercepted by in-process platform adapters — Discord, Telegram, Slack, Matrix, Signal, BlueBubbles, etc. — all of which route inbound `MessageEvent`s through `GatewayRouter._handle_message` at `gateway/run.py:2645–2929`. That router owns a ~300-line dispatch chain and the persistent state each command handler mutates (`_session_model_overrides`, `_agent_cache`, the `adapters[platform].send_model_picker(...)` helpers on other adapters, etc.).

`APIServerAdapter` (the `gateway/platforms/api_server.py` adapter serving `/v1/runs` and `/v1/chat/completions`) **intentionally bypasses the router** — see the `run.py:3148` comment flagging api_server as an "excluded platform" from the router notification path. It constructs a fresh agent per request with no persistent session state. The practical effect: a frontend typing `/model` gets the command text forwarded verbatim to the LLM, which hallucinates a plausible but wrong reply ("`/model` is a client-side command, I won't execute it") because the model has no way to know the gateway was supposed to handle it.

**Proposed — a two-stage arc, each stage a small, independently reviewable PR:**

**Stage 1 — stateless preprocessor (sibling follow-up to PR #8556).** A lightweight preprocessor in `api_server.py`'s `/v1/runs` + `/v1/chat/completions` handlers that detects a leading `/` in the user text, matches the first token against `GATEWAY_KNOWN_COMMANDS`, and splits on command type:

- **Stateless commands** (`/help`, `/commands`, and any others that can execute without touching router-owned state) are dispatched via existing helpers (`gateway_help_lines()` at `hermes_cli/commands.py:340`) and returned as a synthetic SSE stream matching the handlers' existing event shape.
- **Stateful commands** (`/model`, `/new`, `/retry`, `/undo`, `/compress`, `/title`, `/resume`, `/branch`, `/rollback`, `/yolo`, `/reasoning`, `/personality`, and most of the registry) return a deterministic, helpful SSE notice along the lines of *"The `/model` command requires a persistent session and isn't available on the stateless `/v1/runs` endpoint. Use `/api/sessions/{id}/chat/stream` (from PR #8556) or a channel with session state (Discord, CLI, Telegram)."*
- **Unknown** and **cli-only** commands fall through to the LLM path unchanged.
- **Preprocessor exceptions** fall through to the LLM path unchanged — a preprocessor bug must never take down a normal chat request.

This respects upstream's intentional design (api_server stays stateless, no router coupling) while fixing the hallucination symptom and unlocking the commands that *can* run statelessly.

**Stage 2 — stateful dispatch on `/api/sessions/{id}/chat/stream` (after PR #8556 lands).** Once session management primitives ship, a separate PR adds a preprocessor **scoped to the session chat stream endpoint only**, using the URL's `session_id` as the persistence handle. Stateful commands become session-scoped dict writes (`session.model_override = new_model`) without refactoring `GatewayRouter` or plumbing api_server into the router. This matches upstream's partition cleanly: `/v1/*` remains stateless and OpenAI-compatible; statefulness lives on `/api/sessions/*`.

**Why not one big PR:** a full GatewayRouter refactor plus api_server plumbing was considered and rejected. It would touch 10+ files across subsystems normally owned separately, fight the documented "api_server is excluded from router notification" design decision, and review as a much larger change than the value added. The two-stage arc ships faster, reviews cleaner, and matches the upstream partition better.

**Impact:** frontends that speak the API server (hermes-relay, hermes-workspace, ClawPort, and any OpenAI-compatible client that points at the Hermes base URL) get the same built-in command surface as Discord/Telegram/CLI, in two predictable stages.

**Workaround (current / near-term):** `hermes_relay_bootstrap/_command_middleware.py` (planned for v0.4.1) mirrors Stage 1 as an aiohttp middleware injected at bootstrap time, so vanilla upstream installs that ship with the relay get the hallucination fix and the stateless commands without waiting for an upstream release. The bootstrap middleware fork-detects the same way the existing route injection does — it no-ops once Stage 1 lands upstream.

## 6a. Rich Card Rendering Across Platform Adapters (ADR 26 Phase B)

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

## 6. Terminal HTTP API (for non-relay setups)

**Current state:** hermes-agent's `terminal_tool.py` supports 6 backends (local, Docker, SSH, Modal, Daytona, Singularity) but is only callable internally by the agent during conversations. There is no HTTP API for interactive terminal sessions.

**Proposed:** Add terminal session endpoints to the gateway, similar to what hermes-workspace implements (HTTP/SSE for output, POST for input/resize/close).

**Impact:** Would enable mobile terminal access without requiring the separate relay server. Any hermes-agent install would support remote terminal.

**Workaround (current):** Our relay server (`relay_server/`) provides terminal via WSS as a separate service. This works but requires deploying an additional component.

## Notes

- These are suggestions, not requirements. The app works without any of them.
- Priority: #1 (`/api/commands`) is the most impactful for code maintainability.
- All workarounds are documented in `docs/decisions.md`.
