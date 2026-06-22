# hermes-relay-cli

Desktop tray app and thin-client CLI for [Hermes-Relay](https://github.com/Codename-11/hermes-relay) — talk to a remote [Hermes agent](https://github.com/NousResearch/hermes-agent) over WSS from a native tray surface or any terminal.

The agent brain (LLM + tools + sessions + memory) runs on your Hermes host. The Windows tray app is the default desktop surface: pair one active relay, chat from the dashboard, start/pause the daemon, open or manage remote TUI sessions in a real terminal, install desktop surface plugins such as Herm, view devices, inspect the task log, copy terminal/shim commands, run local diagnostics, edit compact settings, see the compact above-taskbar overlay pill, and emergency-stop from the tray or hotkey. The CLI remains the local line-mode thin-client: it handles pairing, persists the session token, resumes tmux-backed TUI sessions, and renders the agent's stream to plain stdout so `>`, `|`, and `jq` all work.

> **What this is not:** A local Hermes install. Point it at an existing Hermes-Relay server (`ws://host:8767`). For the full TUI with Ink, see the sibling package [`ui-tui`](../../hermes-agent-tui-smoke/ui-tui) in the hermes-agent fork.

## Desktop control posture

The Windows tray app is the primary desktop install surface for most users. It bundles the compiled CLI as a Tauri sidecar and uses the same `~/.hermes/remote-sessions.json` and relay session APIs as the CLI. The CLI and daemon remain first-class for macOS/Linux, headless hosts, scripts, terminals, and operators.

| Surface | Intended use | Default experience |
|---------|--------------|--------------------|
| Windows tray | Daily desktop use | Tauri tray + one active relay + compact status-only above-taskbar overlay pill + tray/hotkey pause/emergency stop |
| Tray dashboard | Management | Pair/replace, Chat, first-class embedded TUI tab, Terminal/CLI launcher and commands, surface plugins, diagnostics, devices/revoke, grants, task log, compact settings, collapsed Advanced controls |
| CLI/daemon | Operator and headless use | Commands, flags, scripts, and JSON policy |

Tauri is optional outside Windows. The CLI and `hermes-relay daemon` must continue to work without a native app installed.

Tray behavior is intentionally split: left-click the tray icon to open the dashboard, right-click it for the native management menu, and use the compact overlay pill as a click-through status indicator only. The dashboard defaults to a dark graphite UI, keeps Start, Pause, and Emergency Stop in the sticky topbar so daemon controls stay reachable while views scroll, and exposes one active desktop relay instance at a time. Pairing is a first-class flow with pasted pairing invites, manual code, stored-session selection, LAN/Tailscale/manual route preview, and explicit replacement confirmation before changing the active relay. A raw relay URL in Advanced is not treated as paired until the matching stored session token exists, so unpaired installs keep daemon, devices, and TUI actions gated with "Pair first" UI instead of silently falling back to localhost.

The Chat tab is the first-run conversational surface. If the desktop is already paired, Chat streams through the saved relay and reuses the same CLI/gateway session path as `hermes-relay chat --json`. If the desktop is not paired, Chat offers a direct Hermes gateway/API URL (`http://host:8642`) with an optional API bearer for that tray session; only the URL is saved in `~/.hermes/desktop-control.json`. Relay pairing remains the fuller desktop-control path for daemon, terminal, devices, grants, and local tools, while direct gateway mode is for chat-only WebAPI access.

The TUI tab owns the experimental embedded xterm/PTY surface inside the dashboard, while Terminal / CLI opens the remote TUI in a real terminal and turns the active relay into copyable standard `hermes-relay` commands for remote TUI, one-shot chat, headless daemon, TUI sessions, status, tool inventory, and doctor. The Plugins view registers terminal surface plugins that can be installed, updated, launched externally, or embedded in the same xterm/PTY surface. The Sessions view lists global server-side `hermes-*` tmux sessions, resumes named sessions, creates new sessions, kills stale sessions, and copies the matching CLI commands. Those commands use the saved active relay by default; `--remote` is shown only as an explicit one-off override. Diagnostics renders local install/session checks and can run `hermes-relay doctor --json` through the bundled sidecar. Settings keep daily controls short and put raw relay override, computer-use flag, emergency hotkey, and blocklist under a collapsed Advanced disclosure. The sidebar is navigation-only and uses the same Chevron Compass brand mark as the Android/docs surfaces. The pill sizes itself to the current state (`Observing`, `TUI active`, `Tools`, `Gateway`, `Approval`, `Paused`, `Offline`, or `Unavailable`) instead of reserving dashboard-width space. Start, pause, emergency stop, chat turns, embedded/external TUI session actions, plugin launches, grant resolution, settings saves, doctor runs, and log clears all emit a shared dashboard refresh event so the dashboard and overlay pill stay on the same activity state.

## Install

### GitHub Release install (recommended, no Node required)

```powershell
# Windows tray app (default)
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

```powershell
# Windows CLI only
$env:HERMES_RELAY_INSTALL_SURFACE='cli'; irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

```sh
# macOS / Linux CLI
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
```

Windows downloads and verifies `hermes-relay-desktop-windows-x64-setup.exe`, then launches the tray installer. The tray app bundles the compiled CLI sidecar so pair, daemon start/stop, devices, revoke, and task log work without a separate PATH install. CLI-only installs download the prebuilt single-file binary from GitHub Releases (Bun `--compile`, ~60–110 MB per platform) into `~/.hermes/bin/`. Pin a specific release with `HERMES_RELAY_VERSION=cli-v0.3.0-alpha.18`; CLI-only installs can override the install dir with `HERMES_RELAY_INSTALL_DIR=...`.

After install, use `hermes-relay <prompt>`. The shorter `hermes <prompt>` alias is optional because it can shadow a real local hermes-agent install. Enable it only when you want hermes-relay to be the `hermes` command for tools like Orca:

```powershell
# Windows enable / disable
$env:HERMES_RELAY_HERMES_ALIAS='enable'; irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/hermes-alias.ps1 | iex
$env:HERMES_RELAY_HERMES_ALIAS='disable'; irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/hermes-alias.ps1 | iex
```

```sh
# macOS / Linux enable / disable
HERMES_RELAY_HERMES_ALIAS=enable curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/hermes-alias.sh | sh
HERMES_RELAY_HERMES_ALIAS=disable curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/hermes-alias.sh | sh
```

Both alias managers are collision-safe: they create/remove only the hermes-relay-owned alias and refuse to overwrite or delete an unrelated `hermes` command.

> **Experimental.** Assets are currently unsigned — Windows SmartScreen and macOS Gatekeeper may warn on first launch. The CLI installers print the `Unblock-File` / `xattr -dr com.apple.quarantine` escape hatches. Code signing lands before v1.0.

### Local clone + npm link

For development builds:

```sh
git clone https://github.com/Codename-11/hermes-relay
cd hermes-relay/desktop
npm install
npm run build
npm link
```

The package name in `package.json` is workspace metadata only today. The desktop CLI is not published to npm.

For tray development on Windows:

```sh
npm run tray:dev
npm run tray:check
npm run tray:test
npm run tray:build
```

## Uninstall

Uninstall modes use the same shape on both platforms. Default keeps `~/.hermes/remote-sessions.json` so a future re-install pairs seamlessly.

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

### Modes

| Flag              | What it removes                                                                                               |
|-------------------|---------------------------------------------------------------------------------------------------------------|
| *(default)*       | `~/.hermes/bin/hermes-relay[.exe]` plus any hermes-relay-owned optional `hermes` / `hermes.cmd` alias and the Windows user-PATH entry. Preserves `~/.hermes/remote-sessions.json`. |
| `--purge`         | Also deletes `~/.hermes/remote-sessions.json` — bearer tokens, cert pins, tools-consent flag.                 |
| `--service`       | Stub. Prints the commands to remove a manually-installed systemd unit / launchd plist / Windows service.      |

Modes combine: `--purge --service` runs both.

**Heads-up about `--purge`:** `remote-sessions.json` is shared with the Ink TUI and Android desktop tooling. Wiping it signs those surfaces out too. Use `--purge` when giving the machine away — not for routine cleanup.

### Requirements

- **A running `hermes-relay` server** reachable over the network. See the [Hermes-Relay README](https://github.com/Codename-11/hermes-relay#readme) to stand one up.
- **For the `curl | sh` / `irm | iex` binary install:** no runtime deps — the binary is self-contained. `~/.hermes/bin/` on PATH.
- **For local clone + `npm link`:** Node.js ≥21 — needed for the built-in global `WebSocket`. Older Node needs `--experimental-websocket`; we don't support that.

## First-time pairing

On the Hermes host, mint a one-time pairing invite:

```sh
# on the relay host
hermes-pair
# -> prints "Copy/paste pairing invite" with hermes-relay://pair?payload=...
```

In the tray app, open **Pair** and paste the `hermes-relay://pair?...` URL into
**Paste invite**. From a terminal, use the same invite directly:

```sh
hermes-relay pair --pair-qr 'hermes-relay://pair?payload=...' --grant-tools
# ✓ Paired. Token stored in ~/.hermes/remote-sessions.json
#   Server: 0.6.0
#   Relay:  ws://172.16.24.250:8767
```

Manual URL + six-character code pairing still works with
`hermes-relay pair --remote ws://172.16.24.250:8767`, but the invite URL is
the preferred path because it carries endpoint candidates and the correct
relay one-shot code.

Now subsequent `hermes-relay ...` calls reuse the stored session token. Tokens live at `~/.hermes/remote-sessions.json` (mode 0600) — same file the Ink TUI uses, so pairing once from either surface works for both.

### Tray Chat

Open **Chat** after pairing to stream a desktop chat turn through the saved relay. The tray spawns the bundled CLI sidecar in JSON mode, so relay auth, session resume, gateway events, and stop/cancel behavior stay aligned with `hermes-relay chat`.

If you are not paired, switch the Chat route to **Gateway/API** and enter a Hermes WebAPI base URL such as `http://host:8642`. The tray probes `/api/sessions/*/chat/stream` first and falls back to `/v1/runs` when that is the available upstream path. API key is optional and kept in memory for the current tray session; only the gateway URL is saved.

### Tray TUI, Terminal / CLI and Diagnostics

After pairing from the tray, open **TUI** to run the embedded dashboard terminal, or open **Terminal** to launch the remote TUI in a real terminal and copy commands that target the active desktop relay:

```powershell
hermes-relay
hermes-relay sessions list
hermes-relay chat "summarize my current project"
hermes-relay daemon --log-human
hermes-relay status
hermes-relay tools
hermes-relay doctor --json
```

The copied commands intentionally use the standard `hermes-relay` command rather than a bundled app path. The CLI reads the tray-selected active relay from `~/.hermes/desktop-control.json`, then falls back to the single stored session or an interactive picker. Bare `hermes-relay` resumes the active TUI tmux session stored in `~/.hermes/desktop-sessions.json`, falling back to `default`; `hermes-relay sessions list/resume/new/kill` is the explicit management path. Use `--remote ws://host:8767` only when you want to override the saved active relay for a single command. If the tray can only see its sidecar, Terminal shows a CLI install nudge and a copyable CLI-only installer command. The TUI tab hosts the embedded terminal: xterm.js renders inside the dashboard, Rust owns the local PTY, and the PTY runs the same `hermes-relay --session <name>` / `--new` path as an external terminal. Keep the external terminal button as the fallback when testing PTY focus, resize, or chord behavior. Terminal still shows shim state, session store, desktop config path, active route, daemon state, and whether experimental computer-use is enabled.

Open **Diagnostics** for local state checks: active relay, route, CLI shim availability, daemon status, desktop-tool consent, overlay visibility, blocklist count, session store, config path, and pending grants. **Run Doctor** executes the sidecar's `doctor --json` command and renders a redacted summary in the dashboard.

### Surface plugins

The tray **Plugins** view and `hermes-relay plugins` command expose installable terminal dashboard surfaces. The first built-in plugin is [Herm](https://github.com/liftaris/herm), packaged as `herm-tui`.

```powershell
hermes-relay plugins status herm
hermes-relay plugins install herm
hermes-relay plugins launch herm
hermes-relay plugins resume herm
```

Herm uses `bun add -g herm-tui` when Bun is available and falls back to `npm install -g herm-tui`; launch uses the installed `herm` binary or `bunx herm-tui` / `npx --yes herm-tui` when available. The tray can also embed Herm in the dashboard PTY, with `resume` mapped to `herm -c`.

### Pair + grant tools in one shot (CLI-only daemon bring-up)

If you plan to run `daemon` (headless tool serving), tack `--grant-tools` onto `pair` to capture the per-URL desktop-tool consent in the same step. That removes the historical `pair` → `shell` (consent prompt) → `daemon` dance:

```sh
hermes-relay pair   --remote ws://172.16.24.250:8767 --grant-tools
# ...prompts for code, then prompts for tool consent, stamps it on the stored session.

hermes-relay daemon
# ...starts headless; consent gate already satisfied.
```

For non-interactive provisioning (CI, install scripts, automated boxes) use `--auto-grant-tools` — same effect, no prompt:

```sh
HERMES_RELAY_CODE=F3W7EY hermes-relay pair \
  --remote ws://172.16.24.250:8767 --auto-grant-tools --non-interactive
```

The two flags are deliberately separate so consent is never implicit — `--grant-tools` means "ask me", `--auto-grant-tools` means "I've already decided". Plain `pair` (no flag) leaves consent untouched, matching the original behavior.

## Usage

```
hermes-relay [shell]             Pipe the full Hermes CLI over a PTY (default — interactive)
hermes-relay chat [<prompt>]     Structured-event chat (REPL or one-shot, scriptable)
hermes-relay "<prompt>"          One-shot structured chat (shortcut for chat "...")
hermes-relay pair [CODE]         Pair with the relay and store a session token
hermes-relay plugins             List/install/update/launch desktop surface plugins
hermes-relay sessions            List / resume / create / kill TUI tmux sessions
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
Attached (tmux session "default") — re-attached to existing session.
Escape: Ctrl+A then . (detach, preserves tmux) · Ctrl+A then k (kill tmux) · Ctrl+A Ctrl+A (literal Ctrl+A)

Desktop tools: 23 handlers advertised

[Axiom-Labs banner, Victor, "Hermes Agent v0.10.0 · claude-opus-4-7 ..."]
❯
```

- **`Ctrl+A .`** — detach cleanly; tmux session survives on the server, next bare `hermes-relay` re-attaches.
- **`Ctrl+A k`** — kill the tmux session; next run gets a fresh hermes.
- **`Ctrl+A Ctrl+A`** — forward a literal `Ctrl+A` (for nested tmux).
- **Ctrl+C** passes through to `hermes` — interrupts the agent, not the client.
- **`--raw`** — skip the auto-`exec hermes`; drop into bare tmux/bash.
- **`--exec <cmd>`** — exec something else instead (e.g. `--exec btop`).
- **`--session <name>`** — override the tmux session name for deterministic resume.

### Sessions — tmux continuity

```sh
hermes-relay sessions list
hermes-relay sessions resume default
hermes-relay sessions new
hermes-relay sessions kill default
```

The relay discovers background server-side tmux sessions named `hermes-*`, so `sessions list` shows sessions even when the current WebSocket did not create them. Bare `hermes-relay` is still the normal path: it resumes the active session recorded in `~/.hermes/desktop-sessions.json` and falls back to `default`. On reattach, the server captures recent tmux scrollback and replays it before live output so the reopened terminal has context immediately.

### Multi-endpoint pairing (ADR 24)

If your Hermes server is reachable via multiple routes (LAN + Tailscale + a public URL), the pairing invite encoded by the host QR carries all of them. Pass the printed `hermes-relay://pair?...` URL, raw JSON payload, or base64 payload to `--pair-qr` and the CLI probes in priority order, picks the first reachable endpoint, and records which route it used — subsequent connects show `Connected via LAN (plain)` / `Connected via Tailscale (secure)` etc.

```sh
# Paste the full pairing invite URL printed by hermes-pair:
hermes-relay pair --pair-qr 'hermes-relay://pair?payload=...'
# Or via env:
HERMES_RELAY_PAIR_QR='hermes-relay://pair?payload=...' hermes-relay shell
```

Priority is strict — reachability only breaks ties within a priority level. 4-second per-candidate timeout, 60-second reachability cache. Signature verification is TODO (matches Android).

### Local tool access for the agent

When you run `chat`, `shell`, or `daemon` with tools consented, the CLI advertises its desktop handlers to the remote Hermes agent so it can operate on YOUR machine (not the server). The regular surface covers file I/O, shell/PowerShell, process lookup, long-running jobs, transfers, clipboard, screenshots, and editor handoff. Computer-use tools are separate and experimental.

First connect per relay URL prompts:

```
Desktop tools are about to be exposed to the remote Hermes agent.
The agent can read/write files, run shell commands, and search your filesystem.
This is AGENT-CONTROLLED access. Only use with trusted Hermes installs.
Type 'yes' to enable, or rerun with --no-tools to disable.
> yes
```

Consent is stored per-URL in `~/.hermes/remote-sessions.json`. `--no-tools` suppresses the router entirely. Non-TTY stdin fails closed.

You can also grant consent up front during pairing — `hermes-relay pair --grant-tools` (TTY prompt) or `--auto-grant-tools` (no prompt). Useful when you only intend to run `daemon` and don't want the interactive `shell` round-trip. See [Pair + grant tools in one shot](#pair--grant-tools-in-one-shot-cli-only-daemon-bring-up) above.

The server-side plugin (`plugin/tools/desktop_tool.py`) registers `desktop_*` tools with Hermes so the agent discovers them naturally; `check_fn` returns 503 when no client is connected so the LLM learns which tools it currently has.

### Experimental computer-use tools

`desktop_computer_status`, `desktop_computer_screenshot`, `desktop_computer_action`, `desktop_computer_grant_request`, and `desktop_computer_cancel` are registered server-side but the desktop client advertises and serves them only when explicitly enabled:

```sh
hermes-relay chat --experimental-computer-use
hermes-relay shell --experimental-computer-use
HERMES_RELAY_EXPERIMENTAL_COMPUTER_USE=1 hermes-relay daemon
```

They still require normal desktop-tool consent. Observe grants allow screenshots; assist/control grants require a visible local approval prompt before host input can run. The tray-managed daemon uses a local grant bridge: a pending assist/control grant opens the Grant Requests view, where it can be approved or rejected. Headless CLI daemon mode still fails closed unless an interactive local prompt provider is active.

Default computer-use policy blocks password managers, credential prompts, banking/payment/crypto surfaces, OS security/admin settings, and private-key/token material. `~/.hermes/desktop-control.json` lets operators tighten or extend that baseline.

### Devices — server-side session management

```sh
hermes-relay devices                        # list paired devices
hermes-relay devices revoke <token-prefix>  # destroy a server-side session
hermes-relay devices extend <prefix> --ttl 604800  # extend TTL to 7 days
```

Talks to the relay's `GET/DELETE/PATCH /sessions` endpoints (same port as WSS, auto-detected). Current device is marked with ● (this device). `--json` redacts tokens; `--reveal-tokens` opts in for scripting.

### Chat — REPL

```sh
hermes-relay
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
hermes-relay "summarize the last commit"
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
hermes-relay tools
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

### Audit — what the agent ran on this machine

```sh
hermes-relay audit            # last 50 desktop-tool calls
hermes-relay audit --limit 20
hermes-relay audit --json
```

```
Desktop-tool activity (4 most recent)

  WHEN     TOOL                STATUS   DETAIL
  12s ago  desktop_read_file   ● ok     path=C:\src\app.ts
  10s ago  desktop_terminal    ● ok     exit 0
   8s ago  desktop_write_file  ✗ error  EACCES: permission denied
   2s ago  desktop_search      ● ok     pattern=TODO
```

Read from a local log (`~/.hermes/desktop-audit.jsonl`) the tool router writes whenever the agent runs a `desktop_*` tool — no network, no auth, works whether the relay is local or remote.

### Relay — inspect the server

```sh
hermes-relay relay context     # what context the relay injects into the agent's prompt
hermes-relay relay info        # version, uptime, sessions (run on the relay host)
hermes-relay relay security    # runtime auth toggles (run on the relay host)
```

`relay context` works from any paired machine; `relay info` / `relay security` are loopback-only (for operators on the relay host) and say so if reached remotely.

### Daemon — background tool router

```sh
hermes-relay daemon start      # run in the background (no console window)
hermes-relay daemon status     # state + uptime of the running daemon
hermes-relay daemon stop       # stop it
hermes-relay daemon            # run in the FOREGROUND (current console)
```

`daemon start` detaches the headless tool router so it keeps running after you close the terminal — the agent can reach your machine any time, not just while a shell is open. It logs to `~/.hermes/daemon.log`. Bare `hermes-relay daemon` still runs in the foreground (handy for watching logs live or running under your own supervisor).

```
$ hermes-relay daemon status
hermes-relay daemon
  state:    ● connected
  pid:      48213
  relay:    ws://172.16.24.250:8767
  uptime:   3h 12m
  updated:  4s ago
  server:   1.2.0
  tools:    23 advertised
```

`status` reads the heartbeat file a running daemon maintains and cross-checks that the pid is alive — it exits non-zero (and says "not running") when the daemon is gone, so scripts can branch on it.

> **Auto-start on boot/login** (survive a reboot, not just a closed terminal) needs an OS service — a Windows service, a systemd user unit, or a launchd agent. Those installers aren't shipped yet; for now `daemon start` covers "background process, this session."

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
| `--experimental-computer-use` | `HERMES_RELAY_EXPERIMENTAL_COMPUTER_USE=1` | Advertise experimental `desktop_computer_*` tools after desktop-tool consent |
| `--no-computer-use` | —                    | Suppress computer-use advertisement even if env enables it |

Precedence for credentials: `--token` → `HERMES_RELAY_TOKEN` → `--code` → `HERMES_RELAY_CODE` → stored session → interactive prompt.

## Troubleshooting

- **`auth timed out after 15000ms`** — the relay subprocess takes 15–30 s on first attach because it initializes the full agent. Bump the timeout: `HERMES_RELAY_AUTH_TIMEOUT_MS=30000 hermes-relay …`.
- **`relay rejected credentials: auth failed`** — your stored token expired or was revoked. Re-pair: `hermes-relay pair --remote ws://…`.
- **`RelayTransport: global WebSocket not available`** — your Node is too old. Need >=21.
- **Hangs on a tool call that asks for approval** — v0.1 doesn't wire interactive approvals; the agent's approval request is surfaced to stderr but can't be answered. Turn off the offending toolset on the server or use `--verbose` to see the block.

## Roadmap

What's shipped in `desktop-v0.3.0-alpha.1`: remote chat + tool-event rendering, one-time pairing (including multi-endpoint QR with strict-priority probe), interactive PTY shell routing the full `hermes` CLI, client-side tool routing (`desktop_read_file` / `desktop_write_file` / `desktop_patch` / `desktop_terminal` / `desktop_search_files` handlers run locally against your machine while the agent brain stays remote — consent-gated per-URL), auto-reconnect with TOFU cert pinning, server-side session management (`devices`), headless `daemon` for always-on tool serving, and local diagnostics (`doctor`).

What's next (see [ROADMAP.md](../ROADMAP.md#desktop-track) for the full track):

- Service installers — `install-service-{win,linux,mac}` to register the daemon with `sc.exe` / systemd user unit / `launchd` so it auto-starts on login.
- Optional Tauri v2 tray/overlay app — dark first-class pairing, one active desktop relay, visible status-only observing/control chip, task log, grant prompts, Devices/Revoke/Settings menu, and pause/emergency stop from the tray or hotkey on top of the CLI daemon.
- Multi-client server-side routing — today a connected desktop client is single-slot; allow laptop + home-desktop + work-box attached simultaneously with per-client tool dispatch via a new hermes-agent `ContextVar`.
- Code signing — Windows EV cert + Apple Developer ID + notarization to silence SmartScreen/Gatekeeper.
- npm registry publication — future v1.0 distribution work. Until then, use GitHub Release binaries or a local clone with `npm link`.

## Related

- [Hermes-Relay](https://github.com/Codename-11/hermes-relay) — parent project (Android client + relay server + plugin)
- [hermes-agent](https://github.com/NousResearch/hermes-agent) — upstream agent platform
- [Codename-11/hermes-agent](https://github.com/Codename-11/hermes-agent) — our fork with the `tui_gateway` and pluggable-transport work

## License

MIT
