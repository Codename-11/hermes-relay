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

## 5. Terminal HTTP API (for non-relay setups)

**Current state:** hermes-agent's `terminal_tool.py` supports 6 backends (local, Docker, SSH, Modal, Daytona, Singularity) but is only callable internally by the agent during conversations. There is no HTTP API for interactive terminal sessions.

**Proposed:** Add terminal session endpoints to the gateway, similar to what hermes-workspace implements (HTTP/SSE for output, POST for input/resize/close).

**Impact:** Would enable mobile terminal access without requiring the separate relay server. Any hermes-agent install would support remote terminal.

**Workaround (current):** Our relay server (`relay_server/`) provides terminal via WSS as a separate service. This works but requires deploying an additional component.

## Notes

- These are suggestions, not requirements. The app works without any of them.
- Priority: #1 (`/api/commands`) is the most impactful for code maintainability.
- All workarounds are documented in `docs/decisions.md`.
