---
name: hermes-relay-desktop-setup
description: Install, pair, and troubleshoot the Hermes-Relay desktop CLI on Windows, macOS, or Linux. Agent-readable recipe with live local-machine diagnostics via the desktop_terminal tool.
version: 0.1.0-experimental
author: Axiom Labs
license: MIT
platforms: [windows, macos, linux]
metadata:
  hermes:
    tags: [setup, install, hermes-relay, desktop, cli, thin-client, devops, experimental]
    category: devops
    homepage: https://github.com/Codename-11/hermes-relay
    related_skills: [hermes-relay-pair, hermes-relay-self-setup, hermes-relay-doctor]
---

# Hermes-Relay Desktop CLI Setup

> **Experimental.** The desktop CLI at `desktop/` is a preview-grade thin client. Pairing, chat, shell (PTY pipe to the host), and local tool routing (`desktop_terminal` / `desktop_read_file` / `desktop_write_file` / `desktop_search_files` / `desktop_patch`) all work end-to-end. Daemon mode, multi-client routing, and code-signed binaries are the v1.0 polish — see the [ROADMAP](https://github.com/Codename-11/hermes-relay/blob/main/ROADMAP.md#desktop-track).

The [Hermes-Relay](https://github.com/Codename-11/hermes-relay) desktop CLI (`hermes-relay`) is a thin client that gives you remote access to a Hermes agent running on another machine. It pipes a full PTY shell (with the agent's native Ink TUI), streams structured chat events for scripting, and — uniquely — lets the remote agent execute tools **on your local machine** (read files, run shell commands, search the filesystem) through a round-trip over the same WSS relay the Android client uses. The agent brain stays on the host; your laptop is the hands.

## When to Use

Invoke this skill when any of the following happens:

- User runs the `/hermes-relay-desktop-setup` slash command.
- User asks to "install the Hermes-Relay CLI", "set up hermes-relay on my laptop", "pair this desktop with my Hermes server", "hermes-relay shell isn't working", or anything equivalent.
- User is on a fresh machine and wants to connect to an existing Hermes-Relay host.
- User reports any of: `hermes-relay` command not found, Node version mismatch, auth timeouts, "No desktop client connected" errors, pasted-pairing-code mangled by bracketed paste, or desktop tools that "time out immediately" (seconds↔ms unit bug in pre-0.6 builds).

Do NOT use this skill to troubleshoot the relay **server** itself (that's `hermes-relay-self-setup`) or the Android client (that's `hermes-relay-self-setup` too). This skill is desktop-CLI-specific, running on the user's laptop/workstation.

## Unique Capability: Live Local Diagnostics

**This skill can invoke `desktop_terminal` to run commands on the user's own machine** — a capability the Android skill can't match. Use it aggressively during install / pairing / troubleshooting:

```
User: "Install the hermes-relay CLI"
→ [desktop_terminal] node --version
   Result: v20.10.0  →  Too old
→ "Node 21+ required. Upgrade via https://nodejs.org, then I'll retry."
→ User upgrades
→ [desktop_terminal] node --version   →  v22.0.1   OK
→ [desktop_terminal] npm install -g @hermes-relay/cli
→ [desktop_terminal] hermes-relay --version   →  0.2.0   Success
```

**Never guess the user's state — measure it.** `desktop_terminal` is cheap; one call per check is the right cadence.

## Prerequisites

1. **The Hermes-Relay server is running on the host** and reachable from this machine. Verify:
   ```bash
   curl -s http://<host>:8767/health
   ```
   Expect `{"status":"ok","version":"0.6.0"}` or later. If it fails, the user's server is down — stop here and run `/hermes-relay-self-setup` or `/hermes-relay-doctor` on the host instead.

2. **One of:**
   - Node.js **≥21** on this machine (for the built-in global `WebSocket`). Verify `node --version` ≥ v21. Older Node refuses to run.
   - A prebuilt `hermes-relay` binary from GitHub Releases (no Node required). Use this path on Windows desktops where Node isn't already installed.

3. **Network path from here to `<host>:8767`.** If the user is roaming (work + home + coffee shop), multi-endpoint pairing (ADR 24) handles it automatically — no prerequisite here.

## Procedure

### A. Install

Three install methods in decreasing order of Node-flexibility. Pick the first the user can do.

#### A1. Binary (Windows — recommended, no Node required)

```powershell
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

- Downloads `hermes-relay-win-x64.exe` from the latest GitHub Release.
- Verifies SHA256 against the published `SHA256SUMS.txt`.
- Installs to `%USERPROFILE%\.hermes\bin\hermes-relay.exe` and adds that directory to the **user** PATH.
- The binary is currently **unsigned** (experimental phase). Windows SmartScreen may show "Windows protected your PC" on first launch. Click "More info" → "Run anyway", or pre-unblock via `Unblock-File`.

Pin a version: `$env:HERMES_RELAY_VERSION='desktop-v0.3.0-alpha.1'; irm ... | iex`.

Uninstall: delete `%USERPROFILE%\.hermes\bin\hermes-relay.exe` and remove the PATH entry from **System Properties → Environment Variables → User → Path**.

#### A2. Binary (macOS / Linux — recommended, no Node required)

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
```

- Detects OS/arch (`linux-x64`, `darwin-x64`, `darwin-arm64`).
- Downloads the matching binary + SHA256 checksum from the latest release.
- Installs to `$HOME/.hermes/bin/hermes-relay` and appends that path to `~/.bashrc` or `~/.zshrc` if not already present.
- **macOS Gatekeeper** will quarantine the unsigned binary on first launch. If the user sees "cannot be opened because the developer cannot be verified", run once:
  ```bash
  xattr -dr com.apple.quarantine ~/.hermes/bin/hermes-relay
  ```

Pin a version: `HERMES_RELAY_VERSION=desktop-v0.3.0-alpha.1 curl -fsSL ... | sh`.

Uninstall: `rm ~/.hermes/bin/hermes-relay` and remove the PATH line from the shell rc.

#### A3. npm / npx (any platform with Node ≥21)

Once we publish to npm (not yet — experimental phase), this will be:
```bash
npm install -g @hermes-relay/cli
```

For now, install from a clone:
```bash
git clone https://github.com/Codename-11/hermes-relay
cd hermes-relay/desktop
npm install
npm run build
npm link
```

The `npm link` step drops `hermes-relay` into your npm global bin directory. Add that to your PATH if it isn't already (`npm config get prefix` shows where).

### B. Verify the install

Run a one-off `--version` check:

```bash
hermes-relay --version
```

Expect `0.2.0` or later (depending on which release is current).

If the command is not found:
- **Windows**: open a **new** PowerShell (PATH updates don't apply retroactively), or run `$env:Path += ';C:\Users\<you>\.hermes\bin'` temporarily.
- **macOS / Linux**: source the rc file (`source ~/.zshrc`) or open a new terminal.
- **npm path missing**: `export PATH="$(npm config get prefix)/bin:$PATH"`, then add permanently.

### C. Pair

On the **relay host**, mint a fresh 6-character pairing code:

```bash
# On the host (SSH in first if remote)
hermes-pair --ttl 600
```

Or, from any Hermes chat session on the host: `/hermes-relay-pair`.

You'll get a line like `Code: F3W7EY (valid 10 min)`.

On **this machine** (your laptop):

```bash
hermes-relay pair --remote ws://<host>:8767
```

The CLI prompts:
```
Pairing code (6 chars): _
```

Type or paste `F3W7EY`. The CLI will:
1. Disable bracketed-paste mode during the prompt (so pasted codes aren't wrapped in `\x1b[200~...`).
2. Strip ANSI + control chars defensively, clean to `A-Z0-9`, clamp to 6 chars.
3. Echo `→ using code: F3W7EY` as a sanity check.
4. Connect to the relay, exchange the code for a session token, persist to `~/.hermes/remote-sessions.json` (mode 0600).

On success:
```
✓ Paired. Token stored in ~/.hermes/remote-sessions.json
  Server: 0.6.0
  Relay:  ws://<host>:8767
  Route:  lan
```

Subsequent `hermes-relay` invocations reuse the stored token — no re-pair needed.

### D. First smoke — structured event chat

Quickest sanity check:

```bash
hermes-relay chat "what time is it?"
```

The CLI streams back the agent's reply. Stderr gets `Connecting... / Connected via LAN (plain) — server 0.6.0`; stdout gets the answer. Redirecting stdout to a file (`... > out.txt`) captures **only** the reply.

### E. Full Hermes TUI — shell mode

For the complete local-Hermes experience (the Axiom-Labs banner, Victor, the Ink status bar, slash commands, everything):

```bash
hermes-relay
```

(Bare invocation is shorthand for `hermes-relay shell`.)

The CLI:
1. Connects over WSS.
2. Prints `Connected via LAN (plain) — server 0.6.0`.
3. Prompts for **one-time local-tool consent** (see §F).
4. Attaches to the relay's `terminal` channel, which spawns (or re-attaches) a tmux session on the host.
5. Sends `clear; exec hermes\n` after a 350 ms settle — tmux's login shell replaces itself with `hermes`, which renders its native Ink TUI straight through to your terminal.

**Escape keys:**
- `Ctrl+A .` — detach (tmux preserved on the host — next `hermes-relay` re-attaches with full state)
- `Ctrl+A k` — kill the tmux session (destructive; fresh hermes on next run)
- `Ctrl+A Ctrl+A` — forward a literal `Ctrl+A` (for nested tmux)
- `Ctrl+C` — passes through to `hermes` (interrupts the agent, not the client)

### F. Desktop tool consent — **one-time per relay URL**

On first `shell` or `chat` with tools enabled, the CLI shows:

```
Desktop tools are about to be exposed to the remote Hermes agent.
The agent can read/write files, run shell commands, and search your filesystem.
This is AGENT-CONTROLLED access. Only use with trusted Hermes installs.
Type 'yes' to enable, or rerun with --no-tools to disable.
> _
```

Only `yes` (case-insensitive) enables. Anything else (`y`, `no`, Enter, Ctrl+C) denies. Consent is stored per-URL in `~/.hermes/remote-sessions.json` as `toolsConsented: true` and sticks across sessions.

**Kill-switches:**
- `--no-tools` on any subcommand suppresses the router entirely — the agent sees "no desktop client for tool X".
- `hermes-relay pair --reset-pin` (coming) wipes consent + cert pin; re-pair re-prompts.

### G. Verify end-to-end tool routing

Inside `hermes-relay shell`, at the agent prompt:

```
use desktop_terminal to run "hostname" and show me the raw JSON output
```

Expect the agent to render:
```json
{
  "stdout": "<YOUR-LOCAL-HOSTNAME>\r\n",
  "stderr": "",
  "exit_code": 0,
  "duration_ms": 70
}
```

The key signal: `stdout` contains **your local hostname**, not the server's. That proves the call routed from hermes → relay → WSS → this machine → shell exec → response back. If the agent instead sees the server's hostname (e.g., `Docker-Server`), the tool is running on the server — check consent and `/desktop/_ping` from the host:

```bash
# On the host
curl -s "http://127.0.0.1:8767/desktop/_ping?tool=desktop_terminal"
```

`{"connected": true, "advertised_tools": [...]}` means routing works; the issue is upstream in Hermes. `{"connected": false}` means the client isn't attached or consent was denied.

## Troubleshooting

### `hermes-relay: command not found`
Your shell hasn't picked up the PATH change. Options:
- Windows: open a **new** terminal window.
- macOS/Linux: `source ~/.bashrc` (or `~/.zshrc`) in the current session.
- Or run with the full path one time: `~/.hermes/bin/hermes-relay --version`.

### `Node version 20 too old. Need >=21`
You're on the npm/source install path with old Node. Either:
- Upgrade Node: https://nodejs.org → LTS or current, whichever is ≥21.
- Switch to the **binary** install (A1 / A2) which needs no Node.

### `auth timed out after 15000ms`
The relay subprocess takes 15–30 s on first attach (hermes agent full-init). Bump the timeout for slow first connects:
```bash
HERMES_RELAY_AUTH_TIMEOUT_MS=30000 hermes-relay shell
```

### `relay rejected credentials: auth failed` / `disconnected before auth`
Stored token expired, was revoked on the host, or was typo'd during pairing. Re-pair:
```bash
hermes-relay pair --remote ws://<host>:8767
```

### Pasting the pairing code produces weird chars (`[200~F3W7EY[201~`)
Your terminal's bracketed-paste mode wasn't respected by readline. Two workarounds:
- Type the 6 chars manually (fastest).
- Pass as a positional: `hermes-relay pair F3W7EY --remote ws://...` — skips the prompt entirely.

The CLI already disables bracketed paste during the prompt (+ strips escapes defensively), but some terminals ignore the disable flag.

### `No desktop client connected` when the agent tries a tool
The CLI isn't actively connected (or `--no-tools` is active). Start a session:
```bash
hermes-relay shell
```

Leave it running in one terminal. The agent can now fire tools into it. If you want the CLI connected **without** a visible shell, the forthcoming `hermes-relay daemon` subcommand will be the answer (v1.0 target).

### `timed out after 30ms` on any desktop tool call
You're running a pre-fix build. The unit-mismatch bug (Python sent `timeout` as seconds, Node treated as ms) was fixed in the commit that landed same-day as the initial Phase B release. Upgrade:
- Binary: re-run the install one-liner (picks up latest).
- npm/source: `cd hermes-relay/desktop && git pull && npm run build`.

### Windows SmartScreen warning on first launch
Unsigned binary (expected during experimental phase). Click "More info" → "Run anyway", or pre-allow via PowerShell:
```powershell
Unblock-File "$env:USERPROFILE\.hermes\bin\hermes-relay.exe"
```

### macOS "can't be opened because the developer cannot be verified"
Quarantine xattr. Clear once:
```bash
xattr -dr com.apple.quarantine ~/.hermes/bin/hermes-relay
```

### `hermes-relay shell` drops me into bash, not hermes
The post-attach `exec hermes` injection didn't happen. Usually: `--raw` was set, or the previous tmux session still has a different shell. Try:
```bash
hermes-relay shell --exec hermes
# or kill the old session:
# (inside shell) Ctrl+A k
# then retry
hermes-relay shell
```

### Tool calls hang, then fail with `aborted`
The 30-second router ceiling fired. Most likely the handler (especially `desktop_terminal`) is running a command that blocks on stdin or doesn't terminate. Kill the shell command from inside Hermes, or explicitly set a shorter `timeout` in the tool args: `desktop_terminal("slow_cmd", timeout=5)`.

## Verification (final)

Before declaring the install healthy, confirm:

1. **Install**: `hermes-relay --version` returns a version string (via `desktop_terminal` if the agent is already paired on another machine, else ask the user).
2. **Pairing**: `hermes-relay status` shows one entry for the relay URL with `expires` in the future and `grants` populated (`chat`, `terminal`, `tui`).
3. **Chat**: `hermes-relay "what time is it?"` returns a streamed reply.
4. **Shell**: `hermes-relay` drops into the full Hermes TUI. The Axiom-Labs banner appears; the bottom-right clock shows the **server's** time (confirms the shell is on the host).
5. **Tool routing** (if consent granted): ask the agent `use desktop_terminal to run hostname` — result must contain the **client's** hostname, not the server's.

If all five pass: the client is fully operational and the user can start a real work session.

## Agent Flow — Live Diagnostic Recipe

When the user reports a problem, follow this sequence using `desktop_terminal`:

```
1. [desktop_terminal] hermes-relay --version
   → if not found: PATH issue → walk user through PATH fix
   → if found but old: upgrade via install.sh/ps1

2. [desktop_terminal] node --version  (only on npm/source installs)
   → if < 21: upgrade Node OR switch to binary install

3. [desktop_terminal] hermes-relay status
   → if no entry for expected URL: never paired / token purged
   → if expired: re-pair

4. [desktop_terminal] curl -s http://<host>:8767/health
   → 200 + "ok": server healthy
   → timeout / refused: server down OR firewall OR wrong URL

5. [desktop_terminal] hermes-relay tools --remote ws://<host>:8767 --non-interactive
   → 46+ toolsets, 17 enabled: tool plumbing intact
   → 0 toolsets: hermes-gateway not registering plugin — run /hermes-relay-self-setup on host

6. [desktop_terminal] cat ~/.hermes/remote-sessions.json
   → redact the token field before echoing to the user
   → confirm toolsConsented: true if routing is expected
```

Each step is ~1 second; the user gets real answers based on real state, not guesses.

## Safety

- **Grant tool consent only to trusted Hermes servers.** Once consented, the agent on that URL can read/write/search your filesystem and run shell commands. Review the URL carefully before typing `yes`.
- **Never pipe `curl | sh` from URLs you don't recognize.** The install scripts live at `github.com/Codename-11/hermes-relay` — verify before piping.
- **Binaries are unsigned during experimental phase.** SmartScreen (Windows) and Gatekeeper (macOS) warnings are expected. Signing comes with the v1.0 release.
- **Session tokens are secrets.** `~/.hermes/remote-sessions.json` is mode 0600 for a reason. Don't commit it, don't share it, don't paste it into chat.
- **`--reveal-tokens` on `hermes-relay status --json`** prints full tokens — use only when you need them for scripted re-auth, never in a streamed session or shared terminal.
- **Never restart `hermes-gateway` on the host without asking the user.** It interrupts every active chat across every client.
- **Destructive tool calls** (`desktop_write_file` overwriting, `desktop_terminal` running `rm -rf`, `desktop_patch` rewriting files) are agent-initiated — if you (the agent) are about to request one, confirm with the user first. There's no client-side confirmation modal yet.
