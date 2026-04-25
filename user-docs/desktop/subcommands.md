# Subcommands <ExperimentalBadge />

Full reference for every `hermes-relay` verb. Flags map one-to-one with env vars where noted.

| Verb | Purpose |
|------|---------|
| `chat` | Structured-event chat — REPL, one-shot, or piped stdin. Supports `/paste`, `/screenshot`, `/image <path>`. |
| `shell` (default) | Pipe the full Hermes Ink TUI through a PTY in tmux on the host. The killer chord set lives here. |
| `pair` | First-time / re-pair handshake. Stores a session token. |
| `paste` | One-shot stage clipboard image to the server inbox for `/paste`. |
| `status` | Local view of stored sessions. Default-redacts tokens. |
| `tools` | Server-side tool inventory (`tools.list` RPC). |
| `devices` | Server-side paired-device management — list / revoke / extend. |
| `daemon` | Headless tool router — keeps `desktop_*` tools advertised even with no shell open. |
| `doctor` | Local diagnostic — version, install paths, session summary, daemon detection, platform info. |
| `update` | Self-update via GitHub Releases. |
| `workspace` | Print local workspace context (cwd / git / editor / shell) — same envelope shipped to the relay on connect. |
| `help` | Print full help. |

## `hermes-relay` (bare — defaults to `shell`)

```bash
hermes-relay                       # interactive shell (full Hermes TUI over PTY)
hermes-relay "what time is it?"    # one-shot chat (structured events)
```

Bare invocation opens `shell` mode if no positional arg is given, `chat` mode if a positional is provided. Matches user expectation — "I want to talk to Hermes" drops into the rich interactive experience by default.

## `hermes-relay shell`

Pipes a PTY from the server's tmux (+ post-attach `exec hermes` after tmux settles) directly to your local terminal. You see the literal Hermes CLI — banner, Victor, Ink status bar, all of it.

```bash
hermes-relay shell                             # default: exec hermes
hermes-relay shell --exec btop                 # run something else in tmux
hermes-relay shell --raw                       # drop into bare tmux/bash (no auto-exec)
hermes-relay shell --session my-work           # pin tmux session name for deterministic resume
hermes-relay shell --conversation <id>         # resume a specific hermes conversation; bypass picker
hermes-relay shell --new                       # force a fresh hermes conversation; bypass picker
hermes-relay shell --watch-editor              # poll tmux/$VSCODE for active-editor hints (ships every 5s)
```

**Conversation picker on attach.** Without `--conversation` or `--new`, the client calls `session.list` on the relay's `tui` channel and renders a numbered list of recent server-side hermes sessions with first-prompt previews and ages. Pick one to resume; pick `new` for a fresh conversation. Servers that don't expose `session.list` fall through to "new" silently.

**In-shell chord set.** `Ctrl+A` is the prefix for client-side actions. Everything else passes straight through.

| Chord | Action |
|-------|--------|
| `Ctrl+A .` | Detach. tmux session persists on the server; next `hermes-relay shell` re-attaches with full state. |
| `Ctrl+A k` | Destroy the tmux session. Fresh hermes on next run. |
| `Ctrl+A v` | Read your local clipboard, ship the image to the server's inbox via `/clipboard/inbox`, then auto-type `/paste\r` into the PTY so the upstream Hermes TUI consumes it in the same flow you would have typed by hand. Status line goes to **stderr** so it doesn't pollute the PTY: `[shell] pasted 1920×1080 (245 KB) → /paste`. Reentrancy guard prevents double-stage on a fast double-press. |
| `Ctrl+A ?` (or `Ctrl+A h`) | Re-print the chord-help banner — the attach-time banner scrolls off as soon as anything writes, so this is the way back. |
| `Ctrl+A Ctrl+A` | Forward a literal `Ctrl+A` (for nested tmux). |
| `Ctrl+C` | Passes through to the remote process — interrupts hermes, not the client. |

## `hermes-relay chat`

Structured-event chat. Renders `message.delta` → stdout, tool events → decorated stderr lines, optional JSON firehose for scripting. The REPL also accepts slash commands for native paste / screenshot / image attach.

```bash
hermes-relay chat                                  # interactive REPL
hermes-relay chat "<prompt>"                       # one-shot
echo "<prompt>" | hermes-relay chat                # pipe stdin
hermes-relay chat --json "<prompt>" | jq -c '.type'  # structured event stream
hermes-relay chat --verbose                        # include thinking/reasoning + transport stderr
hermes-relay chat --quiet                          # suppress tool decorations + status lines
hermes-relay chat --conversation <id>              # resume a specific hermes conversation; bypass picker
hermes-relay chat --new                            # force a fresh conversation; bypass picker
hermes-relay chat --no-tools                       # skip desktop tool handlers for this invocation
```

**Ctrl+C** during a turn fires `session.interrupt` on the relay — the in-flight turn is cancelled, the REPL prompt returns. Ctrl+C at the empty prompt exits.

### REPL slash commands

Inside the chat REPL, these commands attach an image to the **next** message:

| Command | Effect |
|---------|--------|
| `/paste` | Read system clipboard image (Windows: PowerShell `[System.Windows.Forms.Clipboard]::GetImage()` with `-STA`; macOS: `pngpaste`; Linux: `wl-paste --type image/png` → `xclip` fallback). Echoes `[📎 clipboard 1920×1080, 234 KB — attached to next message]`. |
| `/screenshot` | All monitors, stitched (virtual-screen union — handles multi-monitor with negative coordinates). Default since alpha.8. |
| `/screenshot primary` | Primary display only. |
| `/screenshot 1` / `2` / ... | 1-indexed display number. |
| `/image <path>` | File on disk. |

The image is staged on the server via the `image.attach.bytes` RPC (server-side `_enrich_with_attached_images` pipeline does the multimodal payload plumbing); your next prompt ships with the image attached so the vision-capable model sees it in the same turn. If the host hasn't been updated yet, the client catches the `method not found` response and prints a pointer at the axiom rollout — REPL stays alive.

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

## `hermes-relay paste`

One-shot equivalent of the `Ctrl+A v` chord — useful from outside `shell` mode, or when scripting. Captures your clipboard, validates the magic bytes, POSTs to `/clipboard/inbox` on the relay, prints a one-line confirmation, and exits. The next time you run `/paste` inside any hermes surface against this server (TUI, `chat` REPL, etc.), it picks up the staged image.

```bash
hermes-relay paste                          # one-shot stage current clipboard image
hermes-relay paste --remote ws://<host>:8767
```

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

`--json` redacts tokens by default; pass `--reveal-tokens` to opt in to full-token output for scripted re-auth.

## `hermes-relay tools`

Ask the server what tool access the agent will have on this connection. Hits `tools.list` RPC, prints the toolset taxonomy.

```bash
hermes-relay tools --remote ws://<host>:8767            # summary
hermes-relay tools --remote <url> --verbose             # per-tool detail per toolset
hermes-relay tools --remote <url> --json                # machine-readable
```

`●` = enabled for this session, `○` = available but off. Useful to sanity-check that `desktop` is in the list and enabled before starting a shell that will use local tools.

## `hermes-relay devices`

Server-side paired-device management (the relay's `GET/DELETE/PATCH /sessions` HTTP API). Shows all paired devices across all your clients — Android phones, desktop CLIs, Ink TUIs — and lets you revoke or extend them.

```bash
hermes-relay devices                               # list (defaults to the one stored relay)
hermes-relay devices --remote ws://<host>:8767     # list specific relay
hermes-relay devices revoke abc12345               # destroy a session by token prefix
hermes-relay devices extend abc12345 --ttl 604800  # extend TTL (seconds)
hermes-relay devices --json                        # machine-readable (redacted)
```

The current device is marked `●`. Prefix must be unambiguous — if multiple sessions share a prefix, you'll get a 409 with the conflicting list; use a longer prefix.

## `hermes-relay daemon`

Run the tool router headless — no PTY, no Ink TUI, just the WSS connection + `desktop_*` handlers. Lets the agent reach your machine while you're working in another window.

```bash
hermes-relay daemon                              # default — auto-human logs on TTY, JSON on pipes
hermes-relay daemon --log-json                   # force JSON-line lifecycle events on stderr
hermes-relay daemon --log-human                  # force human-readable
hermes-relay daemon --token <token>              # explicit token (for service-managed installs)
hermes-relay daemon --token <t> --allow-tools    # skip stored-consent gate (only with --token)
```

**Lifecycle events** (each emitted on stderr — JSON-line by default for journald / log shippers, human-readable on a TTY):

```
starting              → process up, transport not yet attempting
authed                → WSS connected + auth.ok received (server_version, transport)
ready                 → DesktopToolRouter attached (advertised_tools list)
reconnecting          → backoff delay (attempt, delay_ms)
reconnected           → back online
shutdown              → SIGTERM/SIGINT/SIGHUP received
transport_exited      → reconnect budget exhausted; exit 1 so service manager restarts fresh
```

**Fails closed:** no stored session + no `--token` → exits 1. No `toolsConsented: true` on the stored record → exits 1 unless `--allow-tools` is passed alongside an explicit `--token` (a headless binary must never be the thing that first grants tool access).

Service installers (Windows `sc.exe` / systemd user unit / launchd plist) are v1.0 work — until then, run from a terminal tab or wrap with your service manager of choice.

## `hermes-relay doctor`

Local-only diagnostic report — no network. Useful for support pastes and triaging "is my install OK?"

```bash
hermes-relay doctor              # human format (! for warnings, hint at bottom)
hermes-relay doctor --json       # machine-readable; safe to paste — tokens are omitted entirely (no prefix)
```

Fields: `version`, `binary_path`, `install_dir`, `on_path` (case-insensitive on Windows), `sessions` file size + count + per-URL summaries, `daemon` (stat of canonical service unit file paths), `workspace` (since alpha.6: `cwd`, `git_root`, `git_branch`, `repo_name`, `hostname`, `platform`, `active_shell`), platform + node version.

## `hermes-relay update`

Self-update via GitHub Releases. See [Installation → Self-update](./installation.md#self-update-hermes-relay-update) for the full mechanism.

```bash
hermes-relay update              # check + download + verify + swap (with confirm prompt)
hermes-relay update --check      # dry-run: print available version, don't install
hermes-relay update --yes        # skip confirm
hermes-relay update --json       # machine-readable status
```

POSIX uses atomic `fs.rename` (the running daemon keeps its inode); Windows uses a cooperative `.new.exe` swap on the next start.

## `hermes-relay workspace`

Print the workspace-context envelope this client sends to the relay on connect. Useful for verifying what the agent will see, and for scripting.

```bash
hermes-relay workspace           # human format
hermes-relay workspace --json    # machine-readable
```

Fields detected via parallel `git rev-parse` / `git status --porcelain=v1 --branch` calls under a 2 s total budget: `cwd`, `git_root`, `git_branch`, `git_status_summary` (staged / modified counts), `repo_name`, `hostname`, `platform`, `arch`, `active_shell`. Active-editor hints (`active_editor`) detect VSCode / Cursor via `$VSCODE_IPC_HOOK_CLI` + `TERM_PROGRAM`, or poll tmux's `display-message -p "#{pane_current_path}:#{pane_current_command}"` if `--watch-editor` is on.

::: tip Server-side consumption
The client-side workspace envelope shipped in alpha.6. Server-side prompt-context injection (so the agent reads "Active desktop workspace: machine=X · repo=Y · branch=Z" every turn) is on the way — see [ROADMAP.md](https://github.com/Codename-11/hermes-relay/blob/main/ROADMAP.md#desktop-track-parallel-lane-to-android--experimental). Until then, the envelope is captured by the relay as ephemeral session metadata; you can ask the agent about it explicitly via tool calls.
:::

## Global flags

Available on every subcommand.

| Flag | Env | Purpose |
|------|-----|---------|
| `--remote <url>` | `HERMES_RELAY_URL` | Relay WSS URL |
| `--code <CODE>` | `HERMES_RELAY_CODE` | One-time pairing code (pair only) |
| `--token <token>` | `HERMES_RELAY_TOKEN` | Session token, skips pairing entirely |
| `--pair-qr <payload>` | `HERMES_RELAY_PAIR_QR` | Multi-endpoint QR (ADR 24) |
| `--session <id>` | — | `chat`: legacy alias for `--conversation`. `shell`: tmux session name (distinct — tmux, not hermes). |
| `--conversation <id>` | — | `chat` / `shell`: resume a specific hermes conversation; bypasses picker |
| `--new` | — | `chat` / `shell`: force a fresh conversation; bypasses picker |
| `--exec <cmd>` | — | `shell` only: override `hermes` auto-exec |
| `--raw` | — | `shell` only: skip auto-exec, bare tmux/bash |
| `--watch-editor` | — | `shell` / `chat`: poll tmux / `$VSCODE` and send `active_editor` hints every 5 s |
| `--no-tools` | — | Don't wire desktop tool handlers for this invocation |
| `--log-human` / `--log-json` | — | `daemon` only: force log format |
| `--allow-tools` | — | `daemon` only: skip stored-consent gate (use only with `--token`; implies trust) |
| `--json` | — | `chat` / `status` / `tools` / `devices` / `doctor` / `workspace` / `update`: JSON output |
| `--verbose` | — | `chat` / `tools`: include thinking/reasoning + transport stderr |
| `--quiet`, `-q` | — | Suppress status lines + tool decorations |
| `--no-color` | `NO_COLOR` | Disable ANSI colors |
| `--non-interactive` | — | Never prompt; fail fast if creds missing |
| `--reveal-tokens` | — | `status` / `devices`: print full tokens in `--json` output |
| `--check` | — | `update`: dry-run |
| `--yes` | — | `update`: skip confirm |
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
| `HERMES_RELAY_DAEMON` | Set to `1` inside `daemon` mode — handlers read this to disable interactive prompts |
| `NO_COLOR` | Disable ANSI output |
| `FORCE_COLOR=1` | Force ANSI even on non-TTY |

## Exit codes

| Code | Meaning |
|------|---------|
| `0` | Success |
| `1` | General error (auth failed, relay rejected, tool call errored, daemon transport exhausted) |
| `2` | Argument / flag parsing error |

## Config files

| Path | Purpose |
|------|---------|
| `~/.hermes/remote-sessions.json` | Session tokens, grants, TTLs, cert pins, tool consent (mode 0600, atomic tempfile+rename) |
| `~/.hermes/bin/hermes-relay` (`.exe` on Windows) | Installed binary path |
| `~/.hermes/bin/hermes` (or `hermes.cmd` on Windows) | Short alias — created at install time, collision-safe |
| `~/.hermes/bin/hermes-relay.new.exe` (Windows only, transient) | Pending self-update; renamed into place on next invocation |

## Related

- [Pairing](./pairing.md) — how to get a session token in the first place.
- [Local tool routing](./tools.md) — `desktop_*` tools and consent.
- [Troubleshooting](./troubleshooting.md) — common errors and fixes.
