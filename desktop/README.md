# hermes-relay-cli

Cross-platform CLI/TUI and optional menu-only Windows systray for [Hermes-Relay](https://github.com/Codename-11/hermes-relay).

These are the only Hermes-Relay desktop deliverables: the cross-platform CLI
and its optional Windows systray. Hermes-Relay does not ship a separate
full desktop chat or management client; that product surface belongs to
[hermes-desktop](https://github.com/NousResearch/hermes-agent). The systray has
no application window: right-clicking its icon exposes a small native menu that
invokes the installed CLI for TUI, pairing, daemon control, grants, audit, and logs.

The agent brain (LLM + tools + sessions + memory) runs on your Hermes host. The
CLI is the product: bare `hermes-relay` opens the remote Hermes TUI, while
subcommands provide scriptable chat, pairing, sessions, daemon management,
grants, diagnostics, and desktop-tool routing. The optional systray is only a
Windows convenience launcher and controller for those commands.

> **What this is not:** A local Hermes install. Point it at an existing Hermes-Relay server (`ws://host:8767`). For the full TUI with Ink, see the sibling package [`ui-tui`](../../hermes-agent-tui-smoke/ui-tui) in the hermes-agent fork.

## Desktop surfaces

The Windows installer places `hermes-relay.exe` and the small
`hermes-relay-tray.exe` beside each other in `~/.hermes/bin`. There is one CLI
binary and one set of state files; the tray does not bundle a private sidecar.

| Surface | Intended use | Default experience |
|---------|--------------|--------------------|
| CLI/TUI | Primary desktop experience | Interactive remote Hermes TUI plus scriptable commands and JSON output |
| Windows systray | Optional convenience | Right-click menu for TUI, daemon, pairing, grants, audit, logs, and emergency stop |
| Daemon | Headless operation | Background desktop-tool router controlled by the CLI or tray menu |

Left-clicking the tray icon intentionally does nothing. Right-clicking opens its
only interface. Actions that need input open the real CLI in a terminal rather
than embedding another terminal or management window.

The menu cross-checks the daemon heartbeat and PID, labels the account as
**User** or **Administrator**, and disables lifecycle actions that do not apply
to the current state. **Start/Restart daemon as Administrator...** uses the
standard Windows UAC prompt; the tray itself stays unelevated. The menu also
shows pending-grant counts, exposes CLI diagnostics, can toggle tray startup at
sign-in, and states explicitly that exiting the tray leaves the daemon running.

## Install

### GitHub Release install (recommended, no Node required)

```powershell
# Windows CLI + optional menu-only systray (default)
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

Windows downloads and verifies `hermes-relay-windows-x64-setup.exe`. The installer
places the CLI and systray together, adds `~/.hermes/bin` to the user PATH, and
lets the systray start at sign-in. CLI-only installs download the same prebuilt
single-file CLI binary without the systray. Pin a release with
`HERMES_RELAY_VERSION=desktop-v0.3.0-alpha.18`; CLI-only installs can override the
install directory with `HERMES_RELAY_INSTALL_DIR=...`.

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

### Local development

For development builds:

```sh
git clone https://github.com/Codename-11/hermes-relay
cd hermes-relay/desktop
npm ci
npm run dev -- --help
npm run build
```

The package name in `package.json` is workspace metadata only today. The desktop CLI is not published to npm.

Use `npm run build:watch` while editing TypeScript. To exercise a revision as
the real compiled binary on your PATH, run:

```sh
npm run dev:install
```

That builds the current-platform Bun binary, installs it to `~/.hermes/bin/`,
and saves the previous binary beside it as `.bak`. Stop a running daemon first
on Windows because Windows locks its executable.

For tray development on Windows:

```sh
npm run tray:dev
npm run tray:fmt
npm run tray:lint
npm run tray:check
npm run tray:test
npm run tray:build
npm run dev:install:tray
```

`tray:dev` builds the current CLI binary, points the Rust systray at it, and runs
without installing. `dev:install:tray` replaces both real binaries under
`~/.hermes/bin` and keeps `.bak` files for rollback. Exit the running tray and
stop the daemon before replacing Windows executables. `tray:build` also requires
NSIS (`makensis.exe`) on PATH or addressed by the `MAKENSIS` environment variable.

Before handing off a CLI/systray revision or preparing a tag on Windows, run the
single release-parity gate:

```sh
npm run verify
```

This checks version synchronization, type-checks, tests, builds, smokes the
compiled CLI, and runs the tray formatting, Clippy, check, and test gates. Full
installer packaging remains `npm run tray:build`.

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

On Windows, the prebuilt CLI trusts certificates from the Windows system store
in addition to its bundled roots. Local Node runs gain the same behavior on
Node 22.19 or newer; older supported Node releases keep their existing bundled
and `NODE_EXTRA_CA_CERTS` trust behavior because they do not expose the required
system-CA APIs. Certificate validation is never disabled, and the Relay's TOFU
SPKI pin is still checked after the certificate chain is accepted.

## First-time pairing

On the Hermes host, mint a one-time pairing invite:

```sh
# on the relay host
hermes-pair
# -> prints "Copy/paste pairing invite" with hermes-relay://pair?payload=...
```

Right-click the tray and choose **Pair or re-pair...**, or use the CLI directly:

```sh
hermes-relay pair --pair-qr 'hermes-relay://pair?payload=...' --grant-tools
# ✓ Paired. Token stored in ~/.hermes/remote-sessions.json
#   Server: 0.6.0
#   Relay:  ws://192.168.1.100:8767
```

Manual URL + six-character code pairing still works with
`hermes-relay pair --remote ws://192.168.1.100:8767`, but the invite URL is
the preferred path because it carries endpoint candidates and the correct
relay one-shot code.

Now subsequent `hermes-relay ...` calls reuse the stored session token. Tokens live at `~/.hermes/remote-sessions.json` (mode 0600) — same file the Ink TUI uses, so pairing once from either surface works for both.

### Terminal plugins

The `hermes-relay plugins` command exposes optional terminal surfaces. The first
built-in plugin is [Herm](https://github.com/liftaris/herm), packaged as `herm-tui`.

```powershell
hermes-relay plugins status herm
hermes-relay plugins install herm
hermes-relay plugins launch herm
hermes-relay plugins resume herm
```

Herm uses `bun add -g herm-tui` when Bun is available and falls back to
`npm install -g herm-tui`; launch uses the installed `herm` binary or
`bunx herm-tui` / `npx --yes herm-tui` when available.

### Pair + grant tools in one shot (CLI-only daemon bring-up)

If you plan to run `daemon` (headless tool serving), tack `--grant-tools` onto `pair` to capture the per-URL desktop-tool consent in the same step. That removes the historical `pair` → `shell` (consent prompt) → `daemon` dance:

```sh
hermes-relay pair   --remote ws://192.168.1.100:8767 --grant-tools
# ...prompts for code, then prompts for tool consent, stamps it on the stored session.

hermes-relay daemon
# ...starts headless; consent gate already satisfied.
```

For non-interactive provisioning (CI, install scripts, automated boxes) use `--auto-grant-tools` — same effect, no prompt:

```sh
HERMES_RELAY_CODE=F3W7EY hermes-relay pair \
  --remote ws://192.168.1.100:8767 --auto-grant-tools --non-interactive
```

The two flags are deliberately separate so consent is never implicit — `--grant-tools` means "ask me", `--auto-grant-tools` means "I've already decided". Plain `pair` (no flag) leaves consent untouched, matching the original behavior.

## Usage

```
hermes-relay [shell]             Pipe the full Hermes CLI over a PTY (default — interactive)
hermes-relay chat [<prompt>]     Structured-event chat (REPL or one-shot, scriptable)
hermes-relay "<prompt>"          One-shot structured chat (shortcut for chat "...")
hermes-relay pair [CODE]         Pair with the relay and store a session token
hermes-relay plugins             List/install/update/launch terminal plugins
hermes-relay sessions            List / resume / create / kill TUI tmux sessions
hermes-relay grants              Review pending local computer-use grants
hermes-relay computer-use        Enable, inspect, disable, or cancel desktop use
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
hermes-relay computer-use enable
hermes-relay computer-use status
hermes-relay computer-use cancel
hermes-relay computer-use disable
```

The preference is stored in `~/.hermes/desktop-settings.json` and applies to
future chat, shell, daemon, tray restart, and UAC elevation flows. The explicit
`--experimental-computer-use` and `--no-computer-use` flags remain one-process
overrides. The Windows tray exposes the same enable/disable control, active
grant mode and expiry, and a cancel action.

They still require normal desktop-tool consent. Observe grants allow screenshots;
assist/control grants require explicit local approval before host input can run.
The daemon writes pending requests to `~/.hermes/grant-bridge`; review them with
`hermes-relay grants` or the systray's **Review pending grants...** action. The
tray raises a native security alert when a new approval request appears. Grants
expire automatically after at most one hour, and disabling desktop use,
**Cancel active desktop grant**, or **Emergency stop daemon** ends local input
authority. An Administrator daemon displays a prominent warning while an
assist/control grant is active because approved input inherits that privilege.

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
Connecting to ws://192.168.1.100:8767...
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

Use an upstream model for one turn without changing the session default:

```sh
hermes-relay chat --model-once sonnet --model-provider anthropic "summarize this"
```

In the REPL, `/model sonnet --once` queues the choice locally; it is sent to
the gateway only when the next prompt is submitted. Completion, interruption,
or failure restores the prior model upstream, and quitting before the prompt
does not persist or arm the choice.

#### Relay WSS typed-stream mode

`chat` keeps the gateway/TUI session path above as its default. Paired clients
that want chat, tools, and lifecycle events on the single Relay WebSocket can
opt into the Relay `chat` channel:

```sh
hermes-relay chat --relay-chat "run the tests and summarize failures"
hermes-relay chat --relay-chat --conversation <session-id>
printf 'summarize this input' | hermes-relay chat --relay-chat
```

This mode sends `chat.send` and consumes versioned `chat:stream.event` v1
envelopes. Assistant deltas stream normally; progress, pending/started/completed
tools, artifacts, memory/skill notices, errors, and terminal completion retain
their distinct CLI affordances. Duplicate sequence numbers are ignored and gaps
are surfaced as subdued status warnings. A fresh session is created when no
conversation id is supplied. Since chat-channel v1 has no interrupt envelope,
Ctrl+C cancels by closing the Relay connection instead of leaving late events to
bleed into another turn.

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
Server: ws://192.168.1.100:8767
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
hermes-relay daemon restart    # restart with the caller's current privileges
hermes-relay daemon stop       # stop it
hermes-relay daemon            # run in the FOREGROUND (current console)
```

`daemon start` detaches the headless tool router so it keeps running after you close the terminal — the agent can reach your machine any time, not just while a shell is open. It logs to `~/.hermes/daemon.log`. Bare `hermes-relay daemon` still runs in the foreground (handy for watching logs live or running under your own supervisor).

```
$ hermes-relay daemon status
hermes-relay daemon
  state:    ● connected
  pid:      48213
  relay:    ws://192.168.1.100:8767
  uptime:   3h 12m
  updated:  4s ago
  server:   1.2.0
  tools:    23 advertised
  account:  Bailey (User)
```

`status` reads the heartbeat file a running daemon maintains and cross-checks that the pid is alive — it exits non-zero (and says "not running") when the daemon is gone, so scripts can branch on it.

On Windows, keep the tray and normal daemon unelevated for routine operation.
Use **Start/Restart daemon as Administrator...** only when a desktop action
requires administrator access. Windows displays UAC consent, and the elevated
daemon records its privilege level in the same status file so later stop and
restart actions preserve the required elevation.

> **Auto-start on boot/login:** the Windows menu can start the tray at user
> sign-in; it intentionally does not auto-elevate or silently start an
> Administrator daemon. Starting the daemon itself as a service still needs an
> OS service, systemd user unit, or launchd agent.

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
| `--experimental-computer-use` | `HERMES_RELAY_EXPERIMENTAL_COMPUTER_USE=1` | One-process override enabling experimental `desktop_computer_*` tools |
| `--no-computer-use` | —                    | One-process override suppressing computer use even when the persistent preference or env enables it |

Precedence for credentials: `--token` → `HERMES_RELAY_TOKEN` → `--code` → `HERMES_RELAY_CODE` → stored session → interactive prompt.

## Troubleshooting

- **`auth timed out after 15000ms`** — the relay subprocess takes 15–30 s on first attach because it initializes the full agent. Bump the timeout: `HERMES_RELAY_AUTH_TIMEOUT_MS=30000 hermes-relay …`.
- **`relay rejected credentials: auth failed`** — your stored token expired or was revoked. Re-pair: `hermes-relay pair --remote ws://…`.
- **`RelayTransport: global WebSocket not available`** — your Node is too old. Need >=21.
- **Hangs on a tool call that asks for approval** — v0.1 doesn't wire interactive approvals; the agent's approval request is surfaced to stderr but can't be answered. Turn off the offending toolset on the server or use `--verbose` to see the block.

## Roadmap

What's shipped on the `desktop-v*` track: remote chat + tool-event rendering,
one-time pairing, the interactive PTY/TUI shell, client-side tool routing,
auto-reconnect with TOFU cert pinning, server-side session management, the
headless daemon, local diagnostics, and the optional menu-only Windows systray.

What's next (see [ROADMAP.md](../ROADMAP.md#desktop-track) for the full track):

- Service installers — `install-service-{win,linux,mac}` to register the daemon with `sc.exe` / systemd user unit / `launchd` so it auto-starts on login.
- Multi-client server-side routing — today a connected desktop client is single-slot; allow laptop + home-desktop + work-box attached simultaneously with per-client tool dispatch via a new hermes-agent `ContextVar`.
- Code signing — Windows EV cert + Apple Developer ID + notarization to silence SmartScreen/Gatekeeper.
- npm registry publication — future v1.0 distribution work. Until then, use GitHub Release binaries or a local clone with `npm link`.

## Related

- [Hermes-Relay](https://github.com/Codename-11/hermes-relay) — parent project (Android client + relay server + plugin)
- [hermes-agent](https://github.com/NousResearch/hermes-agent) — upstream agent platform
- [Codename-11/hermes-agent](https://github.com/Codename-11/hermes-agent) — our fork with the `tui_gateway` and pluggable-transport work

## License

MIT
