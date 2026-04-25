# FAQ <ExperimentalBadge />

## Can I use this with hermes installed locally instead of remotely?

You don't need to. Native local-Hermes already has the same `/paste`, `/image <path>`, drag-drop a file from Explorer, and `Alt+V` paths — they ship in upstream hermes-agent and the Ink TUI directly. Hermes-Relay is specifically for the **remote-server** case: when you want to use a Hermes that lives somewhere else (a home server, a GPU box, a cloud VM) from your laptop, with the same UX as a local install. If everything's already on the same machine, run `hermes` directly and skip the relay.

The two paths are complements, not alternatives. You'd use Hermes-Relay's desktop CLI when:
- The agent's compute, models, or secrets need to live somewhere other than your daily-driver laptop.
- You want the same agent / sessions / memory accessible from multiple devices.
- You're sharing a GPU or model API key across machines.

You'd use a native local Hermes install when:
- Single machine.
- Willing to manage Python venv + model API keys locally.
- No cross-device session continuity needed.

## Is this just SSH?

No. It's closer to **"give the agent on my server a set of hands on my local machine"**. Three differences from SSH:

1. **The agent drives the tools, not you.** Hermes picks when to call `desktop_read_file` or `desktop_terminal` based on what you asked it to do. SSH hands the keyboard to a human; this hands it to the agent.
2. **The connection carries chat + shell + tools simultaneously.** SSH does shell; this does shell *and* a structured JSON-RPC event stream *and* a reverse tool channel, on one socket.
3. **Pairing is one-time and tokenized.** No passwords, no keys to distribute, no SSH-config to maintain. The pair exchange is a 6-char code valid for 10 minutes, and the resulting session token is revocable from the server.

You'd use SSH *instead* of this if you want to type commands yourself. You'd use this *instead* of SSH if you want an LLM to drive and coordinate multi-step work where some steps happen remotely and some happen locally.

## Can I use it offline?

No. The whole point is the agent lives on a server reachable over the network. If your laptop is offline, you can't reach the Hermes host. (You can of course run Hermes itself on your laptop and skip the relay — but then the desktop CLI is redundant.)

## How do I revoke access from one of my machines?

Two ways:

1. **From the device**: delete the entry in `~/.hermes/remote-sessions.json`, or remove the whole file.
2. **From the server** (destroys the session so even a compromised device can't reuse the token):
   ```bash
   hermes-relay devices revoke <token-prefix>
   ```
   The prefix is shown in `hermes-relay devices`. Use at least 8 chars to be unambiguous.

## Does it work over Tailscale?

Yes. Pair against your tailnet hostname: `hermes-relay pair --remote wss://hermes.<tailnet>.ts.net:8767`. Use `wss://` if you've enabled `tailscale serve` for managed TLS + ACL-based identity.

If your Hermes host is reachable via **multiple** routes (LAN + Tailscale + public), use the [multi-endpoint QR flow](./pairing.md#multi-endpoint-pairing-adr-24) so the CLI auto-picks the best reachable endpoint as you move between networks.

## What happens when my laptop goes to sleep / network drops?

- **In shell mode** (bare `hermes-relay`, the default): the tmux session persists on the server. The WSS disconnects when the network goes away. When you wake up and re-run `hermes-relay`, it re-attaches to the same tmux session with the hermes process still running.
- **In `chat` mode**: the in-flight turn gets torn down; the server's session state persists. Next `hermes-relay chat` resumes cleanly.
- **Tool calls that were in-flight when the drop happened**: the handler's AbortController fires, the child process is SIGKILL'd, and the relay-side Python handler sees a disconnect error.

Auto-reconnect with exponential backoff (1 s → 30 s, 5 min on 429) is built into the transport — short drops heal transparently. Longer drops you'll want to re-run the command.

## Can I pipe stdin?

Yes, in `chat` mode:

```bash
cat README.md | hermes-relay "summarize this"
```

The CLI reads stdin to EOF and sends it as a single prompt. Good for CI / scripts / one-shot queries. `shell` mode requires an interactive TTY (it needs raw-mode stdin to forward every keystroke through the PTY).

## What's the maximum tool execution time?

30 seconds, enforced by the router's `AbortController`. Per-call overrides are allowed via the `timeout` arg (seconds):

```
use desktop_terminal to run "long_cmd", timeout=60
```

But clamped to a 10-minute absolute ceiling. Anything longer should be broken into multiple calls or run as a background job (with the agent polling).

## How do I share a session between two machines?

You can't — each `(URL, device)` gets its own stored session token. Pair each machine separately. Same agent server can see both as distinct paired devices (`hermes-relay devices` lists all).

## What's the difference between `shell` and `chat` modes?

| Mode | Rendering | Stdin | Best for |
|------|-----------|-------|----------|
| `shell` | PTY pipe of the host's literal `hermes` CLI (Ink TUI) | Raw-mode forward | Interactive conversation with agent, slash commands, rich rendering |
| `chat` | CLI's own structured-event renderer (plain lines, optional `--json`) | One-shot / REPL / piped | Scripting, CI, automation, machine-readable transcripts |

## Why the experimental badge?

Because:

- Binaries are unsigned (SmartScreen/Gatekeeper warnings).
- `hermes-relay daemon` mode hasn't shipped yet (currently tools only work while a shell/chat is open).
- Multi-client routing is single-client MVP (one desktop per relay session).
- Wire protocol may change between releases.
- No npm publish yet — installation is binary-via-curl or source clone.

Everything currently shipped works — pairing, shell, chat, tools, devices, status. It's "experimental" in the sense of "the stability contract isn't promised yet," not "expect it to break."

## Does it log my tool calls?

Server-side: yes. The relay's `desktop` channel keeps a rolling 100-command audit buffer (`/desktop/activity`) with tool name, request ID, latency. The contents of `desktop_terminal` commands and `desktop_read_file` paths are in that buffer.

Client-side: no. The CLI doesn't write a separate audit log — just stdout/stderr of the current session.

If you care about this (you probably should), pair only with Hermes hosts you control.

## How is this different from MCP?

[MCP](https://modelcontextprotocol.io/) is a protocol for exposing tools to LLMs over stdio or SSE. It works — but each tool needs its own MCP server process running somewhere the agent can reach.

Hermes's desktop tools are:
- **Zero-config** for the end user — pair once, tools are there.
- **Bidirectional with the existing session transport** — same WSS that carries chat, not a separate port.
- **Scoped by pairing** — the agent only has tools for the specific machines you've explicitly paired.

You can totally use MCP alongside Hermes-Relay — the agent sees MCP tools (under the `hermes-acp` / `hermes-api-server` toolsets etc.) and `desktop_*` tools in the same registry.

## When will there be a daemon mode?

v1.0. Tracked in [ROADMAP.md](https://github.com/Codename-11/hermes-relay/blob/main/ROADMAP.md#desktop-track). The daemon will:

- Run in the background with no visible shell.
- Advertise desktop tools so the agent can reach you anytime you're on the machine.
- Install as a Windows service / systemd user unit / launchd plist so it auto-starts on login.

Until then: keep a `hermes-relay` session open in a spare terminal tab for the agent to dispatch tool calls into.

## Can multiple people use the same Hermes host from different desktop CLIs?

Right now the server tracks a single "active" desktop client per relay — if you pair from two machines, the most recently connected wins routing. v1.0 adds per-session-token routing (each hermes session binds to a specific desktop client) so multi-client is clean.

For now: one desktop attached at a time. Or two if you pair them with different tokens and only one is connected.

## Is there voice mode?

Not in the desktop CLI. The Android client has voice mode. The CLI is text-first.

## Is there a Windows-on-ARM build?

Not yet — Bun's `bun-windows-arm64` target is still experimental as of this writing. When it stabilizes we'll add it to the release matrix. In the meantime, ARM64 Windows users can run the x64 build under emulation.
