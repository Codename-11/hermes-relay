# hermes-relay-cli

Thin-client CLI for [Hermes-Relay](https://github.com/Codename-11/hermes-relay) — talk to a remote [Hermes agent](https://github.com/NousResearch/hermes-agent) over WSS from any terminal.

The agent brain (LLM + tools + sessions + memory) runs on your Hermes host. This CLI is the local line-mode thin-client: it handles pairing, persists the session token, and renders the agent's stream to plain stdout so `>`, `|`, and `jq` all work.

> **What this is not:** A local Hermes install. Point it at an existing Hermes-Relay server (`ws://host:8767`). For the full TUI with Ink, see the sibling package [`ui-tui`](../../hermes-agent-tui-smoke/ui-tui) in the hermes-agent fork.

## Install

### npm (recommended)

```sh
npm install -g @hermes-relay/cli
```

### curl / irm

```sh
# macOS / Linux
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
```

```powershell
# Windows
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

Both installers check for Node >=21 and delegate to `npm install -g @hermes-relay/cli` — they do not install Node for you.

### npx (no install)

```sh
npx @hermes-relay/cli --help
```

First run downloads and caches ~2 MB.

## Uninstall

Three tiers — same shape on both platforms. Default keeps `~/.hermes/remote-sessions.json` so a future re-install pairs seamlessly.

### curl / irm

```sh
# macOS / Linux — binary only (default)
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/uninstall.sh | sh

# macOS / Linux — also wipe session tokens
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/uninstall.sh | sh -s -- --purge
```

```powershell
# Windows — binary only (default)
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/uninstall.ps1 | iex

# Windows — also wipe session tokens (iex can't forward args; use env)
$env:HERMES_RELAY_UNINSTALL_PURGE=1; irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/uninstall.ps1 | iex
```

### Tiers

| Flag              | What it removes                                                                                               |
|-------------------|---------------------------------------------------------------------------------------------------------------|
| *(default)*       | `~/.hermes/bin/hermes-relay[.exe]` and the Windows user-PATH entry. Preserves `~/.hermes/remote-sessions.json`. |
| `--purge`         | Also deletes `~/.hermes/remote-sessions.json` — bearer tokens, cert pins, tools-consent flag.                 |
| `--service`       | Stub. Prints the commands to remove a manually-installed systemd unit / launchd plist / Windows service.      |

Tiers combine: `--purge --service` runs both.

**Heads-up about `--purge`:** `remote-sessions.json` is shared with the Ink TUI and Android desktop tooling. Wiping it signs those surfaces out too. Use `--purge` when giving the machine away — not for routine cleanup.

### npm

```sh
npm uninstall -g @hermes-relay/cli
```

If you previously paired, tokens remain in `~/.hermes/remote-sessions.json`. Delete it manually if you want a full wipe.

### Requirements

- **Node.js >=21** — needed for the built-in global `WebSocket` (added stable in Node 21). Older Node needs `--experimental-websocket`; we don't support that path.
- A running `hermes-relay` server reachable over the network. See the [Hermes-Relay README](https://github.com/Codename-11/hermes-relay#readme) to stand one up.

## First-time pairing

On the Hermes host, mint a one-time pairing code:

```sh
# on the relay host
hermes-pair                  # or: python -m plugin.pair --register-code
# → prints e.g. "CODE: F3W7EY (TTL: 10m)"
```

On your machine, pair:

```sh
hermes-relay pair --remote ws://172.16.24.250:8767
# prompts for the code, then:
# ✓ Paired. Token stored in ~/.hermes/remote-sessions.json
#   Server: 0.6.0
#   Relay:  ws://172.16.24.250:8767
```

Now subsequent `hermes-relay ...` calls reuse the stored session token. Tokens live at `~/.hermes/remote-sessions.json` (mode 0600) — same file the Ink TUI uses, so pairing once from either surface works for both.

## Usage

```
hermes-relay [shell]             Pipe the full Hermes CLI over a PTY (default — interactive)
hermes-relay chat [<prompt>]     Structured-event chat (REPL or one-shot, scriptable)
hermes-relay "<prompt>"          One-shot structured chat (shortcut for chat "...")
hermes-relay pair [CODE]         Pair with the relay and store a session token
hermes-relay status              Show stored sessions + grants + TTL
hermes-relay tools               List tools available on the server
hermes-relay devices             List / revoke / extend server-side paired devices
hermes-relay --help              Full help
```

### Shell — the default mode

`hermes-relay` with no args drops into an interactive PTY session piping the full Hermes CLI over the relay's `terminal` channel inside tmux. You see the literal local-Hermes experience — banner, skin, session id, everything — not a re-rendered approximation.

```
$ hermes-relay
Connecting...
Connected via Tailscale (secure) — server 0.6.0
Attached (tmux session "shell-9f2a1c30") — re-attached to existing session.
Escape: Ctrl+A then . (detach, preserves tmux) · Ctrl+A then k (kill tmux) · Ctrl+A Ctrl+A (literal Ctrl+A)

Desktop tools: 5 handlers advertised (read_file, write_file, terminal, search_files, patch)

[Axiom-Labs banner, Victor, "Hermes Agent v0.10.0 · claude-opus-4-7 ..."]
❯
```

- **`Ctrl+A .`** — detach cleanly; tmux session survives on the server, next `hermes-relay shell` re-attaches.
- **`Ctrl+A k`** — kill the tmux session; next run gets a fresh hermes.
- **`Ctrl+A Ctrl+A`** — forward a literal `Ctrl+A` (for nested tmux).
- **Ctrl+C** passes through to `hermes` — interrupts the agent, not the client.
- **`--raw`** — skip the auto-`exec hermes`; drop into bare tmux/bash.
- **`--exec <cmd>`** — exec something else instead (e.g. `--exec btop`).
- **`--session <name>`** — override the tmux session name for deterministic resume.

### Multi-endpoint pairing (ADR 24)

If your Hermes server is reachable via multiple routes (LAN + Tailscale + a public URL), the QR payload `hermes-pair` produces carries all of them. Pass the raw payload string to `--pair-qr` and the CLI probes in priority order, picks the first reachable endpoint, and records which route it used — subsequent connects show `Connected via LAN (plain)` / `Connected via Tailscale (secure)` etc.

```sh
# Paste the full QR payload (the string inside the QR code, not the URL):
hermes-relay pair --pair-qr '{"hermes":3,"host":"192.168.1.10","port":8642,"key":"ABC123",...}'
# Or via env:
HERMES_RELAY_PAIR_QR='<payload>' hermes-relay shell
```

Priority is strict — reachability only breaks ties within a priority tier. 4-second per-candidate timeout, 60-second reachability cache. Signature verification is TODO (matches Android).

### Local tool access for the agent

When you run `chat` or `shell` with tools consented, the CLI advertises five handlers to the remote Hermes agent so it can operate on YOUR machine (not the server):

- `desktop_read_file` / `desktop_write_file` / `desktop_patch` — file I/O in the CWD
- `desktop_terminal` — shell exec via `bash -lc` (30s timeout, SIGKILL on abort)
- `desktop_search_files` — ripgrep (with pure-Node fallback)

First connect per relay URL prompts:

```
Desktop tools are about to be exposed to the remote Hermes agent.
The agent can read/write files, run shell commands, and search your filesystem.
This is AGENT-CONTROLLED access. Only use with trusted Hermes installs.
Type 'yes' to enable, or rerun with --no-tools to disable.
> yes
```

Consent is stored per-URL in `~/.hermes/remote-sessions.json`. `--no-tools` suppresses the router entirely. Non-TTY stdin fails closed. The server-side plugin (`plugin/tools/desktop_tool.py`) registers `desktop_*` tools with Hermes so the agent discovers them naturally; `check_fn` returns 503 when no client is connected so the LLM learns which tools it currently has.

### Devices — server-side session management

```sh
hermes-relay devices                        # list paired devices
hermes-relay devices revoke <token-prefix>  # destroy a server-side session
hermes-relay devices extend <prefix> --ttl 604800  # extend TTL to 7 days
```

Talks to the relay's `GET/DELETE/PATCH /sessions` endpoints (same port as WSS, auto-detected). Current device is marked with ● (this device). `--json` redacts tokens; `--reveal-tokens` opts in for scripting.

### Chat — REPL

```sh
hermes-relay --remote ws://172.16.24.250:8767
```

```
Connecting to ws://172.16.24.250:8767...
Connected (server 0.6.0).
Session 4a3c1f2e… on claude-opus-4-7

Type a message. Ctrl+C to interrupt a turn, /quit to exit.

> what's in /tmp?

→ terminal
✓ terminal — 3 files
The /tmp directory contains …

>
```

Ctrl+C during a turn interrupts that turn (via `session.interrupt`). Ctrl+C at the empty prompt exits.

### Chat — one-shot

```sh
hermes-relay "summarize the last commit" --remote ws://172.16.24.250:8767
```

Stderr gets diagnostics; stdout gets the agent's reply — so `... > out.txt` captures just the answer.

### Chat — piped stdin

```sh
cat README.md | hermes-relay "summarize this"
```

Reads stdin to EOF, sends as one prompt.

### JSON event stream (scripting)

```sh
hermes-relay --json "ls ~/" | jq -c '{type, name: .payload.name, text: .payload.text}'
```

`--json` emits one `GatewayEvent` per line on stdout. Useful for pipelines that need structured tool events.

### Inspect tool access

```sh
hermes-relay tools --remote ws://172.16.24.250:8767
```

```
Server: ws://172.16.24.250:8767
Version: 0.6.0
Toolsets: 18 (12 enabled)

  ● terminal        (8 tools)  — shell and process control
  ● filesystem      (6 tools)
  ● browser         (12 tools)
  ○ image           (4 tools)
  …
```

Pass `--verbose` to list every tool inside each toolset.

## Flags and environment

| Flag              | Env                    | Purpose                                      |
|-------------------|------------------------|----------------------------------------------|
| `--remote <url>`  | `HERMES_RELAY_URL`     | Relay WSS URL                                |
| `--code <code>`   | `HERMES_RELAY_CODE`    | One-time pairing code                        |
| `--token <token>` | `HERMES_RELAY_TOKEN`   | Session token (skips pairing entirely)       |
| `--session <id>`  | —                      | Resume a specific session (chat)             |
| `--json`          | —                      | Emit GatewayEvents as JSON lines             |
| `--verbose`       | —                      | Include thinking/reasoning + transport stderr |
| `--quiet, -q`     | —                      | Suppress status lines and tool decorations   |
| `--no-color`      | `NO_COLOR`             | Disable ANSI colors                          |
| `--non-interactive` | —                    | Never prompt; fail if credentials missing    |

Precedence for credentials: `--token` → `HERMES_RELAY_TOKEN` → `--code` → `HERMES_RELAY_CODE` → stored session → interactive prompt.

## Troubleshooting

- **`auth timed out after 15000ms`** — the relay subprocess takes 15–30 s on first attach because it initializes the full agent. Bump the timeout: `HERMES_RELAY_AUTH_TIMEOUT_MS=30000 hermes-relay …`.
- **`relay rejected credentials: auth failed`** — your stored token expired or was revoked. Re-pair: `hermes-relay pair --remote ws://…`.
- **`RelayTransport: global WebSocket not available`** — your Node is too old. Need >=21.
- **Hangs on a tool call that asks for approval** — v0.1 doesn't wire interactive approvals; the agent's approval request is surfaced to stderr but can't be answered. Turn off the offending toolset on the server or use `--verbose` to see the block.

## What's next

The current v0.1 covers remote chat, tool-event rendering, and pairing. Client-side tool routing (so `read_file` / `terminal` run locally against your machine while the agent brain stays remote) is the follow-on work — see [Desktop Client architecture](../docs/) for the plan.

## Related

- [Hermes-Relay](https://github.com/Codename-11/hermes-relay) — parent project (Android client + relay server + plugin)
- [hermes-agent](https://github.com/NousResearch/hermes-agent) — upstream agent platform
- [Codename-11/hermes-agent](https://github.com/Codename-11/hermes-agent) — our fork with the `tui_gateway` and pluggable-transport work

## License

MIT
