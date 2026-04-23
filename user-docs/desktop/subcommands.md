# Subcommands <ExperimentalBadge />

Full reference for every `hermes-relay` verb. Flags map one-to-one with env vars where noted.

## `hermes-relay` (bare — defaults to `shell`)

```bash
hermes-relay                       # interactive shell (full Hermes TUI over PTY)
hermes-relay "what time is it?"    # one-shot chat (structured events)
```

Bare invocation opens `shell` mode if no positional arg is given, `chat` mode if a positional is provided. This matches user expectation — "I want to talk to Hermes" drops into the rich interactive experience by default.

## `hermes-relay shell`

Pipes a PTY from the server's tmux (+ `exec hermes` post-attach) directly to your local terminal. You see the literal Hermes CLI — banner, Victor, Ink status bar, all of it.

```bash
hermes-relay shell                             # default: exec hermes
hermes-relay shell --exec btop                 # run something else in tmux
hermes-relay shell --raw                       # drop into bare tmux/bash (no auto-exec)
hermes-relay shell --session my-work           # pin tmux session name for deterministic resume
```

**Escape keys:**
- `Ctrl+A .` — detach cleanly. tmux session persists on the server; next `hermes-relay shell` re-attaches with full state.
- `Ctrl+A k` — destroy the tmux session. Fresh hermes on next run.
- `Ctrl+A Ctrl+A` — forward a literal `Ctrl+A` (for nested tmux).
- `Ctrl+C` — passes through to the remote process (interrupts hermes, not the client).

## `hermes-relay chat`

Structured-event chat. Renders `message.delta` → stdout, tool events → decorated stderr lines, optional JSON firehose for scripting.

```bash
hermes-relay chat                                  # interactive REPL
hermes-relay chat "<prompt>"                       # one-shot
echo "<prompt>" | hermes-relay chat                # pipe stdin
hermes-relay chat --json "<prompt>" | jq -c '.type'  # structured event stream
hermes-relay chat --verbose                        # include thinking/reasoning + transport stderr
hermes-relay chat --quiet                          # suppress tool decorations + status lines
hermes-relay chat --session <id>                   # resume a specific hermes session
hermes-relay chat --no-tools                       # skip desktop tool handlers for this invocation
```

**Ctrl+C** during a turn fires `session.interrupt` on the relay — the in-flight turn is cancelled, the REPL prompt returns. Ctrl+C at the empty prompt exits.

## `hermes-relay pair`

Mint a pairing code on the server, exchange it here.

```bash
hermes-relay pair --remote ws://<host>:8767              # interactive (prompts for code)
hermes-relay pair <CODE> --remote ws://<host>:8767       # positional code (avoids paste issues)
hermes-relay pair --code <CODE> --remote ws://<host>:8767
hermes-relay pair --pair-qr '<v3-QR-payload>'            # multi-endpoint ADR 24 flow
```

After success, the session token is stored in `~/.hermes/remote-sessions.json` (mode 0600) and subsequent commands reuse it.

See **[Pairing](./pairing.md)** for the full walkthrough.

## `hermes-relay status`

Local inventory — no network. Reads `~/.hermes/remote-sessions.json`.

```bash
hermes-relay status                    # human-readable
hermes-relay status --json             # redacted JSON (tokens truncated)
hermes-relay status --json --reveal-tokens   # full tokens (careful — don't paste this anywhere)
```

Output per URL:
```
ws://172.16.24.250:8767
    server:   0.6.0
    paired:   2h ago
    token:    79d2cf41…8d8c
    expires:  in 29d
    route:    LAN
    grants:   bridge (in 6d), chat (in 29d), terminal (in 29d), tui (in 29d)
    cert:     sha256:a1b2c3d4e5f6… (wss only)
```

## `hermes-relay tools`

Ask the server what tool access the agent will have on this connection. Hits `tools.list` RPC, prints the toolset taxonomy.

```bash
hermes-relay tools --remote ws://<host>:8767            # summary
hermes-relay tools --remote <url> --verbose             # per-tool detail per toolset
hermes-relay tools --remote <url> --json                # machine-readable
```

`●` = enabled for this session, `○` = available but off. Useful to sanity-check that `desktop` is in the list and enabled before starting a shell that will use local tools.

## `hermes-relay devices`

Server-side paired-device management (the relay's `GET /sessions` HTTP API). Shows all paired devices across all your clients — Android phones, desktop CLIs, Ink TUIs — and lets you revoke or extend them.

```bash
hermes-relay devices                               # list (defaults to the one stored relay)
hermes-relay devices --remote ws://<host>:8767     # list specific relay
hermes-relay devices revoke abc12345               # destroy a session by token prefix
hermes-relay devices extend abc12345 --ttl 604800  # extend TTL (seconds)
hermes-relay devices --json                        # machine-readable (redacted)
```

The current device is marked `●`. Prefix must be unambiguous — if multiple sessions share a prefix, you'll get a 409 with the conflicting list; use a longer prefix.

## Global flags

Available on every subcommand.

| Flag | Env | Purpose |
|------|-----|---------|
| `--remote <url>` | `HERMES_RELAY_URL` | Relay WSS URL |
| `--code <CODE>` | `HERMES_RELAY_CODE` | One-time pairing code (pair only) |
| `--token <token>` | `HERMES_RELAY_TOKEN` | Session token, skips pairing entirely |
| `--pair-qr <payload>` | `HERMES_RELAY_PAIR_QR` | Multi-endpoint QR (ADR 24) |
| `--session <id>` | — | `chat`: resume session. `shell`: tmux session name. |
| `--exec <cmd>` | — | `shell` only: override `hermes` auto-exec |
| `--raw` | — | `shell` only: skip auto-exec, bare tmux/bash |
| `--no-tools` | — | Don't wire desktop tool handlers for this invocation |
| `--json` | — | `chat` / `status` / `tools` / `devices`: JSON output |
| `--verbose` | — | `chat` / `tools`: include thinking/reasoning + transport stderr |
| `--quiet`, `-q` | — | Suppress status lines + tool decorations |
| `--no-color` | `NO_COLOR` | Disable ANSI colors |
| `--non-interactive` | — | Never prompt; fail fast if creds missing |
| `--reveal-tokens` | — | `status` / `devices`: print full tokens in `--json` output |
| `--help`, `-h` | — | Print help |
| `--version`, `-v` | — | Print version |

## Environment variables (summary)

| Var | Effect |
|-----|--------|
| `HERMES_RELAY_URL` | Default `--remote` |
| `HERMES_RELAY_CODE` | Default `--code` |
| `HERMES_RELAY_TOKEN` | Default `--token` |
| `HERMES_RELAY_PAIR_QR` | Default `--pair-qr` |
| `HERMES_RELAY_AUTH_TIMEOUT_MS` | Override 15s auth timeout (default `15000`) — useful on slow first connects while tui_gateway spawns |
| `HERMES_RELAY_RPC_TIMEOUT_MS` | Override 120s RPC timeout (default `120000`) |
| `HERMES_RELAY_INSTALL_DIR` | Install script override (default `~/.hermes/bin`) |
| `HERMES_RELAY_VERSION` | Install script pin (default `latest`) |
| `NO_COLOR` | Disable ANSI output |
| `FORCE_COLOR=1` | Force ANSI even on non-TTY |

## Exit codes

| Code | Meaning |
|------|---------|
| `0` | Success |
| `1` | General error (auth failed, relay rejected, tool call errored) |
| `2` | Argument / flag parsing error |

## Config files

| Path | Purpose |
|------|---------|
| `~/.hermes/remote-sessions.json` | Session tokens, grants, TTLs, cert pins, tool consent (mode 0600, atomic tempfile+rename) |
| `~/.hermes/bin/hermes-relay` (`.exe` on Windows) | Installed binary path |

## Related

- [Pairing](./pairing.md) — how to get a session token in the first place.
- [Local tool routing](./tools.md) — `desktop_*` tools and consent.
- [Troubleshooting](./troubleshooting.md) — common errors and fixes.
