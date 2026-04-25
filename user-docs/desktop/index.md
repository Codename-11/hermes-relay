# Desktop CLI <ExperimentalBadge />

**Run Hermes on your server. Use it from your laptop. It feels native.**

`hermes-relay` is a single-binary desktop client that talks to a server-deployed Hermes agent over WSS — same shell, same Ink TUI, same `/paste` an image into the conversation, same session continuity. The agent brain (LLM, tools, memory, sessions) stays on your Hermes host. Your laptop is the keyboard and the eyes.

::: warning Experimental phase
Binaries are unsigned (SmartScreen / Gatekeeper warnings are expected — the installers print the escape hatches). Wire protocol may shift between alphas. Multi-client routing is single-client MVP. Code-signed releases land with v1.0. Safe to use today — just expect occasional friction and [file an issue](https://github.com/Codename-11/hermes-relay/issues) when you hit one.
:::

## The killer demo — paste a screenshot in your TUI

This is the experience that earns the "feels-native" claim. You are inside `hermes-relay` (bare invocation drops you straight into the Hermes Ink TUI over a PTY — no subcommand needed), talking to your remote Hermes the same way you would a local one.

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

Identical UX to native local-Hermes paste. That's the whole pitch. The clipboard read happens on your machine, the file lives on the server, the model sees both — one round-trip, no SSH, no SCP, no manual upload.

The same chord set works on macOS (`Cmd+Shift+4` → screenshot to clipboard → `Ctrl+A v`) and on Linux (Wayland `wl-paste` / X11 `xclip` are detected automatically).

## What it does

| Mode | Command | Best for |
|------|---------|----------|
| **Shell** (default) | `hermes-relay` | Full Hermes Ink TUI over a PTY — banner, Victor, slash commands, the whole experience. Uses tmux on the host so disconnects preserve state. |
| **Chat (structured)** | `hermes-relay chat "<prompt>"` / `hermes-relay "<prompt>"` | Scriptable, one-shot, pipes stdin. `--json` emits `GatewayEvent`s per line for `jq` / automation. REPL supports `/paste`, `/screenshot`, `/image <path>`. |
| **Tools** | Automatic, in-session | The remote agent can call `desktop_read_file`, `desktop_write_file`, `desktop_terminal`, `desktop_search_files`, `desktop_patch`, `desktop_clipboard_read/write`, `desktop_screenshot`, `desktop_open_in_editor` — **executed on your machine**, not the server. One-time per-URL consent gate. |
| **Daemon** | `hermes-relay daemon` | Headless tool router. Keeps the agent's hands available even when no shell is open. JSON-line lifecycle logs. |
| **Pair / Status / Tools / Devices / Doctor / Update / Workspace / Paste** | `hermes-relay <verb>` | First-time setup, session inventory, server-side toolset introspection, paired-device management, local diagnostics, self-update, workspace-context inspection, one-shot clipboard staging. See [Subcommands](./subcommands.md). |

### In-shell chord set

While inside the shell/TUI session (bare `hermes-relay`, the default mode), `Ctrl+A` is the prefix for client-side actions. Everything else passes straight through to the remote `hermes` CLI.

| Chord | Action |
|-------|--------|
| `Ctrl+A .` | Detach cleanly. tmux session persists on the server; next `hermes-relay` re-attaches with full state. |
| `Ctrl+A k` | Destroy the tmux session. Fresh hermes on next run. |
| `Ctrl+A v` | [Stage clipboard image to server inbox + auto-type `/paste`](#the-killer-demo-paste-a-screenshot-in-your-tui). |
| `Ctrl+A ?` (or `Ctrl+A h`) | Re-print the chord-help banner. The attach-time banner scrolls off as soon as anything writes — this is the way back. |
| `Ctrl+A Ctrl+A` | Forward a literal `Ctrl+A` (for nested tmux). |

`Ctrl+C` always passes through to the remote process — it interrupts the agent, not the client.

## Headline features

- **[Native paste / screenshot / image](./subcommands.md)** — the chord set above, plus REPL slash commands `/paste`, `/screenshot`, `/screenshot primary`, `/screenshot 1`, `/image <path>`. Multi-monitor aware: `/screenshot` defaults to the virtual-screen union; `primary` / a 1-indexed display narrows. Identical wire format to a local Hermes paste.
- **[Local tool routing](./tools.md)** — agent-callable file I/O, shell exec, ripgrep, clipboard, screenshot, editor-launcher, and unified-diff patching. Strict consent gate per relay URL; non-TTY stdin fails closed.
- **[Self-update](./subcommands.md#hermes-relay-update)** — `hermes-relay update` polls GitHub Releases, semver-compares, downloads + verifies SHA256, and atomic-swaps the binary. POSIX renames in place; Windows uses cooperative `.new.exe` swap on next start.
- **[Workspace awareness](./subcommands.md#hermes-relay-workspace)** — on connect, the client advertises `cwd`, `git_root`, `git_branch`, `repo_name`, `hostname`, `platform`, `active_shell` to the relay so the agent knows which repo you're in. Client-side capability shipped in alpha.6; server-side prompt-context consumption is on the way (see [ROADMAP.md](https://github.com/Codename-11/hermes-relay/blob/main/ROADMAP.md#desktop-track-parallel-lane-to-android--experimental)).
- **[Conversation picker](./subcommands.md#hermes-relay-shell)** — attach with no `--conversation` / `--new` and you get a numbered list of recent server-side sessions to resume, with first-prompt previews.
- **[Editor tool + interactive patch approval](./tools.md#desktop_open_in_editor-and-interactive-patches)** — agent calls `desktop_open_in_editor(path, line, col)` to open `$VISUAL` / `$EDITOR` / VSCode / Cursor / Sublime / nvim. Agent-proposed patches render as colored unified diffs with `y`/`n`/`e`/`r` prompts.
- **[Daemon mode](./subcommands.md#hermes-relay-daemon)** — `hermes-relay daemon` runs the tool router headless so the agent can reach your machine while you context-switch.
- **[Multi-endpoint pairing](./pairing.md#multi-endpoint-pairing-adr-24)** — one QR carries LAN + Tailscale + public URLs. The client races candidates in priority order, picks the first reachable, and re-probes on every network change.
- **[Reconnect-on-drop + TOFU cert pinning](./pairing.md)** — exponential backoff (1 s → 30 s, 5 min on 429), per-host SPKI sha256 pin captured first-time and verified every reconnect.
- **[Bun-compiled native binary, no Node required](./installation.md)** — curl/irm one-liners install a self-contained binary. Version-aware (`upgrading X → Y` readback), collision-safe `hermes` alias, `~/.hermes/bin/` on PATH.

## When to use the desktop CLI vs. installing Hermes locally

Both are valid. Pick based on where the agent's compute, models, and state should live.

| Setup | When it fits | What lives where |
|-------|--------------|------------------|
| **Server-deployed Hermes + Hermes-Relay desktop CLI** | Multiple devices, shared sessions, GPU on a different box, model API keys you don't want spread across machines. | Compute, models, secrets, sessions, memory all live on the Hermes host. The CLI is a thin client. Pair from laptop, desktop, work box — same agent, shared state. |
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

The third command (`hermes-relay` with no args) drops you into `shell` mode — the full Hermes TUI verbatim, running in tmux on the server. Try `Ctrl+A v` after a `Win+Shift+S` (or `Cmd+Shift+4`) and you'll see the killer demo in action.

See **[Installation](./installation.md)** for the full walkthrough (Bun-compiled binaries, version-aware install, `hermes` alias, self-update flow) and **[Pairing](./pairing.md)** for minting a 6-char code on the server.

## Why both shell AND chat modes?

They're not the same thing:

- **`shell`** pipes the host's actual `hermes` CLI through a PTY. You see exactly what `ssh bailey@hermes-host hermes` would show — same banner, same skin, same slash commands. Best for interactive use.
- **`chat`** speaks the relay's structured `tui` channel (JSON-RPC-over-WSS), renders events as plain lines. Scriptable, pipeable, survives non-TTY environments. The REPL still has slash-command paste / screenshot / image attach. Best for automation / CI / one-shot queries.

Most users want `shell`. If you're writing a script, use `chat --json`.

## Local tool routing — the big deal

The agent on the server can reach through the relay and run tools on **your** machine — read your notes, grep your codebase, run a build, edit a file, capture a screenshot, read your clipboard — while the agent's brain + conversation state stay on the host. [Read how →](./tools.md)

This mirrors how the Android client exposes `android_tap` / `android_screenshot` to the agent. Zero hermes-agent core changes — the `desktop_*` tools are registered via the standard plugin system, same pattern as `android_*`.

## Related

- [Hermes-Relay Android client](/guide/) — same project, same relay, different surface (phone control, voice, bridge).
- [Hermes Agent](https://github.com/NousResearch/hermes-agent) — the agent platform the CLI talks to.
- [Desktop CLI GitHub source](https://github.com/Codename-11/hermes-relay/tree/main/desktop) — `@hermes-relay/cli` package.
- [Release notes](https://github.com/Codename-11/hermes-relay/releases?q=desktop) — tagged `desktop-v*` (separate track from Android).
