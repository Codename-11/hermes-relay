# Desktop CLI <ExperimentalBadge />

The **Hermes-Relay Desktop CLI** (`hermes-relay`) is a thin command-line client for remote Hermes agent access. One install gets you three ways to talk to a Hermes server running anywhere else on your network — locally, on a Tailscale-tailed box, or behind a public URL.

::: warning Experimental phase
Binaries are unsigned (SmartScreen/Gatekeeper warnings are expected). Daemon mode, multi-client routing, and code-signed releases land with v1.0. Safe to use — just expect occasional friction and [file an issue](https://github.com/Codename-11/hermes-relay/issues) when you hit one.
:::

## What it does

| Mode | Command | Best for |
|------|---------|----------|
| **Shell** (default) | `hermes-relay` | Full Hermes Ink TUI over a PTY — banner, Victor, slash commands, the whole experience. Uses tmux on the host so disconnects preserve state. |
| **Chat (structured)** | `hermes-relay chat "<prompt>"` / `hermes-relay "<prompt>"` | Scriptable, one-shot, pipes stdin. `--json` emits `GatewayEvent`s per line for `jq` / automation. |
| **Tools** | Automatic, in-session | The remote agent can call `desktop_read_file`, `desktop_write_file`, `desktop_terminal`, `desktop_search_files`, `desktop_patch` — **executed on your machine**, not the server. One-time per-URL consent gate. |
| **Pair / Status / Tools / Devices** | `hermes-relay pair` / `status` / `tools` / `devices` | First-time setup, session inventory, server-side toolset introspection, paired-device management. |

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

See **[Installation](./installation.md)** for the full walkthrough and **[Pairing](./pairing.md)** for minting a 6-char code on the server.

## Why both shell AND chat modes?

They're not the same thing:

- **`shell`** pipes the host's actual `hermes` CLI through a PTY. You see exactly what `ssh bailey@hermes-host hermes` would show — same banner, same skin, same slash commands. Best for interactive use.
- **`chat`** speaks the relay's structured `tui` channel (JSON-RPC-over-WSS), renders events as plain lines. Scriptable, pipeable, survives non-TTY environments. Best for automation / CI / one-shot queries.

Most users want `shell`. If you're writing a script, use `chat --json`.

## Local tool routing (the big deal)

The agent on the server can reach through the relay and run tools on **your** machine — read your notes, grep your codebase, run a build, edit a file — while the agent's brain + conversation state stay on the host. [Read how](./tools.md).

This mirrors how the Android client exposes `android_tap` / `android_screenshot` to the agent. Zero hermes-agent core changes — the `desktop_*` tools are registered via the standard plugin system, same pattern as `android_*`.

## Related

- [Hermes-Relay Android client](/guide/) — parent project, same relay, different device.
- [Hermes Agent](https://github.com/NousResearch/hermes-agent) — the agent platform the CLI talks to.
- [Desktop CLI GitHub source](https://github.com/Codename-11/hermes-relay/tree/main/desktop) — `@hermes-relay/cli` package.
- [Release notes](https://github.com/Codename-11/hermes-relay/releases?q=desktop) — tagged `desktop-v*` (separate track from Android).
