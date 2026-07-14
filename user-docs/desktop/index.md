# Hermes-Relay CLI <ExperimentalBadge />

**A hand for your agent, on any computer you pair.**

`hermes-relay` is a single binary you drop on a machine — desktop, laptop, or headless box — so your Hermes agent can work there: read and write files, search a codebase, run shell and PowerShell commands, manage processes and long-running background jobs, transfer and archive files, read the clipboard, capture screenshots — all over the same WSS relay, all consent-gated per device. The brain (LLM, tools, memory, sessions) never leaves your Hermes host. This binary is the hand it reaches with.

It also includes a terminal escape hatch for when *you* want to drive: bare `hermes-relay` attaches your server's own Hermes TUI over a PTY, tmux-backed so disconnects lose nothing.

::: warning Experimental phase
Prebuilt CLI binaries ship for Windows x64, Linux x64, and macOS x64/arm64. The optional native systray is Windows-only. Assets are unsigned, so SmartScreen or Gatekeeper warnings are expected. Wire protocol details may shift between alphas, and multi-client routing remains a single-client MVP. [File an issue](https://github.com/Codename-11/hermes-relay/issues) when something does not behave as documented.
:::

::: info Where this track is headed
This surface is focusing into a **remote-hands connector** — remote control, filesystem, and terminal access for the agent on machines you install it to. Desktop chat and management UX belong to [hermes-desktop](https://github.com/NousResearch/hermes-agent); this CLI's `chat` mode keeps working for scripting but isn't where new features land. "Desktop" is shorthand, not a constraint — the same binary runs on laptops and headless servers (`daemon` mode needs no display at all). New release tags use the `cli-v*` track; historical alpha prereleases used `desktop-v*`.
:::

## The point — the agent works on *your* machine

Ask your agent — from your phone, from the attached TUI, from anywhere — to "check whether that build passes on my desktop" or "grab the error from my clipboard," and it reaches through the relay to do it: read your notes, grep your codebase, run a build, patch a file, capture a screenshot — while the brain and conversation state stay on the host. [Read how →](./tools.md)

This mirrors how the Android client hands the agent `android_tap` / `android_screenshot`. Zero hermes-agent core changes — the `desktop_*` tools register via the standard plugin system, same pattern as `android_*`. Run `hermes-relay daemon` and the hand stays available with no window open.

## Demo — native paste into the attached TUI {#demo-native-paste-into-the-attached-tui}

The escape hatch earns its keep too. You are inside `hermes-relay` (bare invocation drops you straight into the Hermes Ink TUI over a PTY — no subcommand needed), talking to your remote Hermes the same way you would a local one.

```text
Win+Shift+S          # Windows snipping tool — screenshot goes to the clipboard
                     # (still inside the same hermes-relay session)
Ctrl+A v             # Client reads YOUR clipboard, ships the image to the
                     # server's inbox, types `/paste` into the TUI for you.
                     #   [shell] pasted 1920×1080 (245 KB) → /paste
                     # The image is now attached to the next message.
type your prompt     # Send normally. The vision-capable model sees image + text
                     # in one turn.
```

Identical UX to native local-Hermes paste — and a small taste of the hands model: the clipboard read happens on your machine, the file lives on the server, the model sees both. One round-trip, no SSH, no SCP, no manual upload.

The same chord set works on macOS (`Cmd+Shift+4` → screenshot to clipboard → `Ctrl+A v`) and on Linux (Wayland `wl-paste` / X11 `xclip` are detected automatically).

## What it does

| Mode | Command | Best for |
|------|---------|----------|
| **Tools (the hand)** | Automatic, in-session | The remote agent can call 23 `desktop_*` tools — filesystem (`read_file` / `write_file` / `patch` / `search_files`), shell (`terminal` / `powershell`), process control (`spawn_detached` / `list_processes` / `kill_process` / `find_pid_by_port`), a job API for long tasks (`job_start` / `_status` / `_logs` / `_cancel` / `_list`), archive/transfer (`copy_directory` / `zip` / `unzip` / `checksum`), and user-context bridges (`clipboard_read/write` / `screenshot` / `open_in_editor`) — **executed on your machine**, not the server. One-time per-URL consent gate. An experimental computer-use family is off by default. |
| **Daemon** | `hermes-relay daemon start` | Headless tool router, in the **background** — no console window, survives closing the terminal. `daemon status` / `daemon stop` manage it; bare `daemon` runs foreground with JSON-line logs. |
| **Shell** (default) | `hermes-relay` | The escape hatch: full Hermes Ink TUI over a PTY — banner, Victor, slash commands, the whole experience. Uses tmux on the host so disconnects preserve state. |
| **Chat (structured)** | `hermes-relay chat "<prompt>"` / `hermes-relay "<prompt>"` | Scriptable, one-shot, pipes stdin. `--json` emits `GatewayEvent`s per line for `jq` / automation. Maintained for scripting; not a growth surface. |
| **Surface plugins** | `hermes-relay plugins` | Install and launch optional terminal dashboard surfaces such as Herm from the CLI. |
| **Pair / Sessions / Status / Tools / Devices / Relay / Audit / Doctor / Update / Workspace / Paste** | `hermes-relay <verb>` | First-time setup, TUI tmux session management, session inventory, server-side toolset introspection, paired-device management, relay-server inspection, desktop-tool activity audit, local diagnostics, self-update, workspace-context inspection, one-shot clipboard staging. See [Subcommands](./subcommands.md). |

### In-shell chord set

While inside the shell/TUI session (bare `hermes-relay`, the default mode), `Ctrl+A` is the prefix for client-side actions. Everything else passes straight through to the remote `hermes` CLI.

| Chord | Action |
|-------|--------|
| `Ctrl+A .` | Detach cleanly. tmux session persists on the server; next `hermes-relay` re-attaches with full state. |
| `Ctrl+A k` | Destroy the tmux session. Fresh hermes on next run. |
| `Ctrl+A v` | [Stage clipboard image to server inbox + auto-type `/paste`](#demo-native-paste-into-the-attached-tui). |
| `Ctrl+A ?` (or `Ctrl+A h`) | Re-print the chord-help banner. The attach-time banner scrolls off as soon as anything writes — this is the way back. |
| `Ctrl+A Ctrl+A` | Forward a literal `Ctrl+A` (for nested tmux). |

`Ctrl+C` always passes through to the remote process — it interrupts the agent, not the client.

## Headline features

- **[Native paste / screenshot / image](./subcommands.md)** — the chord set above, plus REPL slash commands `/paste`, `/screenshot`, `/screenshot primary`, `/screenshot 1`, `/image <path>`. Multi-monitor aware: `/screenshot` defaults to the virtual-screen union; `primary` / a 1-indexed display narrows. Identical wire format to a local Hermes paste.
- **[Local tool routing](./tools.md)** — 23 agent-callable tools: file I/O, unified-diff patching, ripgrep, shell + PowerShell exec, process control, a background-job API, archive/transfer, clipboard, screenshot, and editor-launcher. Strict consent gate per relay URL; non-TTY stdin fails closed. The experimental computer-use family is off by default and has a separate persistent enablement switch.
- **[Self-update](./subcommands.md#hermes-relay-update)** — `hermes-relay update` polls GitHub Releases, semver-compares, downloads + verifies SHA256, and atomic-swaps the binary. POSIX renames in place; Windows uses cooperative `.new.exe` swap on next start.
- **[Surface plugins](./subcommands.md#hermes-relay-plugins)** — install, update, and launch terminal dashboard plugins from the CLI. The first built-in plugin is [Herm](https://github.com/liftaris/herm), installed as `herm-tui` and resumed with `herm -c`.
- **[Workspace awareness](./subcommands.md#hermes-relay-workspace)** — on connect, the client advertises `cwd`, `git_root`, `git_branch`, `repo_name`, `hostname`, `platform`, `active_shell` to the relay so the agent knows which repo you're in. Client-side capability shipped in alpha.6; server-side prompt-context consumption is on the way (see [ROADMAP.md](https://github.com/Codename-11/hermes-relay/blob/main/ROADMAP.md#desktop-track-parallel-lane-to-android--experimental)).
- **[Conversation picker](./subcommands.md#hermes-relay-shell)** — on first or fresh attach, choose from recent server-side Hermes conversations with first-prompt previews before the TUI starts.
- **[TUI session continuity](./subcommands.md#hermes-relay-sessions)** — bare `hermes-relay` resumes the active/default tmux session, replays recent scrollback, and `sessions list/resume/new/kill` gives explicit control when you need it.
- **[Editor tool + interactive patch approval](./tools.md#desktop-open-in-editor-and-interactive-patches)** — agent calls `desktop_open_in_editor(path, line, col)` to open `$VISUAL` / `$EDITOR` / VSCode / Cursor / Sublime / nvim. Agent-proposed patches render as colored unified diffs with `y`/`n`/`e`/`r` prompts.
- **[Daemon mode](./subcommands.md#hermes-relay-daemon)** — `hermes-relay daemon start` runs the tool router headless **in the background** (no console window, survives closing the terminal); `daemon status` / `daemon stop` manage it. Bare `hermes-relay daemon` runs in the foreground.
- **[Activity audit](./subcommands.md#hermes-relay-audit)** — `hermes-relay audit` shows what the agent has actually run on your machine through the desktop tools, from a local log — no network, no auth.
- **[Relay inspection](./subcommands.md#hermes-relay-relay)** — `hermes-relay relay context` audits the system-prompt context the relay injects into the agent; `relay info` / `relay security` report server state for operators on the relay host.
- **Polished CLI** — every subcommand answers `--help`; lists render as aligned tables with on/off status dots; slow operations (endpoint probe, gateway connect) show a spinner; pairing reports per-endpoint probe progress and warns before a stored session expires; and a banner greets you (`hermes-relay logo`).
- **[Multi-endpoint pairing](./pairing.md#multi-endpoint-pairing-adr-24)** — one QR carries LAN + Tailscale + public URLs. The client races candidates in priority order, picks the first reachable, and re-probes on every network change.
- **[Reconnect-on-drop + TOFU cert pinning](./pairing.md)** — exponential backoff (1 s → 30 s, 5 min on 429), per-host SPKI sha256 pin captured first-time and verified every reconnect.
- **[Bun-compiled native binary, no Node required](./installation.md)** — curl/irm one-liners install a self-contained binary. Version-aware (`upgrading X → Y` readback), collision-safe `hermes` alias, `~/.hermes/bin/` on PATH.

## When to use the CLI vs. installing Hermes locally

Both are valid. Pick based on where the agent's compute, models, and state should live.

| Setup | When it fits | What lives where |
|-------|--------------|------------------|
| **Hermes on its own host + Hermes-Relay CLI** | Multiple devices, shared sessions, GPU on a different box, model API keys you don't want spread across machines. | Compute, models, secrets, sessions, memory all live on the Hermes host. The CLI is a thin client. Pair from laptop, desktop, work box, headless server — same agent, shared state. |
| **Native local Hermes install** | Single machine, willing to manage Python venv + model API keys yourself, no cross-device session continuity needed. | Everything on your laptop. Model API calls go directly from your machine. No relay involved. |

Hermes-Relay is for the first case. If you're in the second case, you don't need this CLI at all — just install hermes-agent and use `hermes` directly. The two paths are complements, not alternatives.

## Quick start

::: code-group

```powershell [Windows]
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
hermes-relay pair --remote ws://<host>:8767
hermes-relay
```

```bash [macOS / Linux]
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
hermes-relay pair --remote ws://<host>:8767
hermes-relay
```

:::

The third command (`hermes-relay` with no args) drops you into `shell` mode — the full Hermes TUI verbatim, running in tmux on the server. Try `Ctrl+A v` after a `Win+Shift+S` and you'll see the [native-paste demo](#demo-native-paste-into-the-attached-tui) in action.

See **[Installation](./installation.md)** for the full walkthrough (Bun-compiled binaries, version-aware install, `hermes` alias, self-update flow) and **[Pairing](./pairing.md)** for minting a 6-char code on the server.

## Windows systray: menu only, no desktop window

The optional Windows systray is a native right-click menu over the installed CLI. It has no dashboard, WebView, embedded terminal, chat window, settings window, or background GUI framework. Choosing an interactive action opens the real CLI in a terminal.

The menu reports the daemon's connection and privilege state, opens the Hermes TUI, starts/stops/restarts the daemon, requests an explicit UAC elevation when you choose **Start/Restart daemon as Administrator…**, opens pairing, pending grants, recent activity, diagnostics, and logs, and provides an emergency stop. The tray itself remains a normal user process even when it starts an elevated daemon.

Desktop use is independently disabled by default. **Enable desktop use…** stores the preference in `~/.hermes/desktop-settings.json`, restarts the daemon at its existing privilege level, and allows the experimental screenshot/input tool family to be advertised. Pending assist/control approvals raise a native security alert; **Review pending grants…** opens `hermes-relay grants` in a terminal, and **Cancel active desktop grant** ends the current task-scoped grant. The status rows show the grant mode and expiry, with an explicit warning when an Administrator control grant is active.

The installer can register **Start tray at sign-in**, and the same setting is available from the menu. This is per-user startup—not a Windows service. The tray starts the daemon on launch; choosing **Exit tray** intentionally leaves the daemon running.

## Why both shell AND chat modes?

They're not the same thing:

- **`shell`** pipes the host's actual `hermes` CLI through a PTY. You see exactly what `ssh you@hermes-host hermes` would show — same banner, same skin, same slash commands. Best for interactive use.
- **`chat`** speaks the relay's structured `tui` channel (JSON-RPC-over-WSS), renders events as plain lines. Scriptable, pipeable, survives non-TTY environments. Best for automation / CI / one-shot queries.

Use `shell` when you want to drive interactively; use `chat --json` from scripts. Chat mode is maintained for automation — it isn't where new features land, and it isn't a desktop chat app (that's [hermes-desktop](https://github.com/NousResearch/hermes-agent)'s job).

## Related

- [Hermes-Relay Android client](/guide/) — same project, same relay, different surface (phone control, voice, bridge).
- [Hermes Agent](https://github.com/NousResearch/hermes-agent) — the agent platform the CLI talks to.
- [Herm](https://github.com/liftaris/herm) — optional terminal dashboard plugin installable from the CLI.
- [CLI GitHub source](https://github.com/Codename-11/hermes-relay/tree/main/desktop) — `@hermes-relay/cli` package.
- [Release notes](https://github.com/Codename-11/hermes-relay/releases?q=cli) — tagged `cli-v*` (separate track from Android); old alpha prereleases are under `desktop-v*`.
