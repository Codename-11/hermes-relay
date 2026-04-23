# Local tool routing <ExperimentalBadge />

The big feature. The remote Hermes agent can read, write, search, and execute on **your machine** — not the server — through the same WSS relay it uses for chat. The agent's brain and conversation state stay on the host; your laptop is the hands.

## What the agent can do

Five tools are registered in the `desktop` toolset. The agent sees them as normal tools alongside its usual ones — no special syntax needed, just "read my notes" or "run `tsc --noEmit`".

| Tool | Signature | Example use |
|------|-----------|-------------|
| `desktop_read_file` | `(path: string, max_bytes?: number)` | "Read my notes.md and summarize." |
| `desktop_write_file` | `(path: string, content: string, create_dirs?: boolean)` | "Write a quick-start guide to `~/Desktop/quickstart.md`." |
| `desktop_patch` | `(path: string, patch: string)` | Apply a unified diff. Strict — no fuzzy matching. |
| `desktop_terminal` | `(command: string, cwd?: string, timeout?: number)` | "Run `tsc --noEmit` and tell me what's broken." |
| `desktop_search_files` | `(pattern: string, cwd?: string, max_results?: number, content?: boolean)` | "Find every file mentioning `DesktopToolRouter`." |

All run under a **30-second AbortController** ceiling enforced by the router. `desktop_terminal` accepts a per-call `timeout` (seconds, per the wire spec — converted to ms internally) that's clamped to a 10-minute maximum.

## How it works

1. You pair + connect via `hermes-relay shell` or `hermes-relay chat`.
2. On connect, the CLI's `DesktopToolRouter` attaches to the relay's `desktop` channel and heartbeats every 30s with the list of advertised tools.
3. Hermes's Python-side `desktop_tool.py` handlers register with `tools.registry` (same pattern as `android_tool.py`) — the agent sees `desktop_read_file` as just another tool.
4. When the agent calls a `desktop_*` tool, the Python handler HTTP-POSTs to `localhost:8767/desktop/<tool_name>` on the host.
5. The relay's `desktop` channel forwards the call over WSS to the connected CLI.
6. The CLI's `DesktopToolRouter` dispatches to an in-process handler (`fs.ts`, `terminal.ts`, `search.ts`).
7. The handler runs on **your** machine, returns the result, and the response bubbles back: CLI → relay → Python → Hermes → agent.
8. Typical round-trip: 60–100 ms for a simple command.

No hermes-agent core changes. It's the same pattern the Android client uses for `android_tap` / `android_screenshot` / etc. — just swapping the bridge endpoint for a desktop one.

## Consent gate

On your first `shell` or `chat` session per relay URL with tools enabled, you'll see a prompt:

```
Desktop tools are about to be exposed to the remote Hermes agent.
The agent can read/write files, run shell commands, and search your filesystem.
This is AGENT-CONTROLLED access. Only use with trusted Hermes installs.
Type 'yes' to enable, or rerun with --no-tools to disable.
>
```

Only `yes` (case-insensitive) enables. Anything else (`y`, `no`, `Enter`, `Ctrl+C`) denies.

Consent is stored per-URL in `~/.hermes/remote-sessions.json` as `toolsConsented: true` and sticks across sessions. You won't be asked again for this relay until the URL changes or you wipe the session.

**Kill-switches:**
- `--no-tools` on any subcommand suppresses the router entirely for that invocation.
- Non-TTY stdin (e.g. piped invocations) fails closed — never auto-consents.
- Delete the session record (or set `toolsConsented: false` in the file) to force re-prompt.

## Safety walls

The desktop tools run **in-process on your machine** with your full user privileges. That's a real risk — a compromised relay or a misaligned agent could ask to `rm -rf /`, exfiltrate tokens, or rewrite your `.ssh/config`. The walls:

1. **Consent per-URL, not per-run.** Once you say yes to `ws://hermes.example.com`, the agent on THAT server has persistent tool access. A different URL re-prompts.
2. **No sudo / privilege escalation.** All tools inherit your shell's environment. `desktop_terminal "sudo rm -rf /"` requires a passwordless sudo configuration to succeed — we're not adding it.
3. **Per-call AbortController ceiling.** 30 seconds per tool call hard stop. A long-running compromise would trip this.
4. **Handler implementations are defensive:**
   - `desktop_read_file` caps at `max_bytes` (default 1 MB) and truncates with a marker.
   - `desktop_write_file` refuses to create parent dirs unless `create_dirs: true` is set.
   - `desktop_patch` is strict — any hunk mismatch aborts the whole patch. No fuzzy matching. Better to fail than to corrupt.
   - `desktop_terminal` uses `bash -lc` on POSIX, `cmd /c` on Windows — no shell injection beyond what the command itself carries (it IS the command).
   - `desktop_search_files` skips `.git` / `node_modules` / `dist` / `.next` / `.cache` by default.
5. **No stdin.** `desktop_terminal` pipes `/dev/null` to the child — a command that reads stdin hangs up immediately rather than blocking the handler.
6. **SIGKILL on abort/timeout.** No chance for a signal handler to trap and keep running.

**What we DON'T have yet (v1.0 targets):**
- Command allowlist / blocklist per session.
- Destructive-verb confirmation modal (like the Android bridge's `send_sms`/`call` prompts).
- Per-tool sandbox (e.g., restrict `desktop_read_file` to a project root).
- Code signing (`hermes-relay` binary is currently unsigned).

## Diagnosing routing

If the agent says "desktop_terminal is not available" or calls time out immediately:

```bash
# On the server, verify the channel sees your client
ssh bailey@<host> curl -s "http://127.0.0.1:8767/desktop/_ping?tool=desktop_terminal"
```

Expected:
```json
{
  "connected": true,
  "advertised_tools": ["desktop_patch", "desktop_read_file", "desktop_search_files", "desktop_terminal", "desktop_write_file"],
  "client_status": { ... },
  "last_seen_at": 1776964298.02,
  "pending_commands": 0
}
```

If `connected: false`:
- No active shell/chat session is connected. Start one.
- `--no-tools` was used. Retry without it.
- Consent was denied. Delete the session record or re-pair.

If `connected: true` but the agent still says the tool is missing:
- The toolset isn't enabled for this Hermes session. Inside the shell, ask Hermes: "enable the `desktop` toolset for this session." Or add it to your Hermes config's default enabled toolsets.
- The plugin wasn't loaded on the gateway. See `hermes-relay-self-setup` skill — `plugins.enabled` in `~/.hermes/config.yaml` must include `hermes-relay`.

## Future — daemon mode

Currently the tools only work while a `shell` or `chat` session is open. The forthcoming `hermes-relay daemon` subcommand will run the CLI headless in the background, attaching the tool router without a visible shell. That lets the agent reach you anytime you're on the machine, not just when you're in an active session.

Daemon mode + multi-client routing + Windows Service / systemd / launchd integration is the v1.0 work. Tracked in [ROADMAP.md](https://github.com/Codename-11/hermes-relay/blob/main/ROADMAP.md#desktop-track).

## Related

- [Pairing](./pairing.md) — must pair before tools work.
- [Subcommands](./subcommands.md) — `--no-tools` flag.
- [Troubleshooting](./troubleshooting.md) — common tool routing errors.
