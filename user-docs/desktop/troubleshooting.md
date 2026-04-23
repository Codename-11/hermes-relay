# Troubleshooting <ExperimentalBadge />

Common errors in the desktop CLI, indexed by exact message. If your problem isn't here, [open an issue](https://github.com/Codename-11/hermes-relay/issues/new).

## `hermes-relay: command not found` / `is not recognized`

Your PATH doesn't include the install directory.

**Windows**: open a **new** PowerShell (PATH updates don't retroactively apply to in-process shells).

```powershell
# Confirm the binary exists:
Test-Path "$env:USERPROFILE\.hermes\bin\hermes-relay.exe"
# Re-add to user PATH if missing:
[Environment]::SetEnvironmentVariable('Path', "$([Environment]::GetEnvironmentVariable('Path','User'));$env:USERPROFILE\.hermes\bin", 'User')
```

**macOS / Linux**: add to your shell rc.

```bash
echo 'export PATH="$HOME/.hermes/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

Or run with full path: `~/.hermes/bin/hermes-relay --version`.

## `auth timed out after 15000ms`

The relay subprocess takes 15–30 seconds on first attach because Hermes initializes the full agent. Bump the timeout for slow first connects:

```bash
HERMES_RELAY_AUTH_TIMEOUT_MS=30000 hermes-relay shell
```

If it still times out at 30 s, the relay itself is stuck. Check:
```bash
# On the host
systemctl --user status hermes-relay --no-pager
journalctl --user -u hermes-relay --since '5 minutes ago' --no-pager | tail -20
```

## `relay rejected credentials: auth failed`

Your stored token is expired, was revoked on the host, or you mistyped the pairing code.

```bash
hermes-relay status                    # see what's stored
# If the relay is in the list: the token is stale. Purge and re-pair:
rm ~/.hermes/remote-sessions.json      # or delete just this URL's entry
hermes-relay pair --remote ws://<host>:8767
```

Mint a fresh code on the server first: `hermes-pair --ttl 600`.

## `disconnected before auth`

The WebSocket closed before the server sent `auth.ok` or `auth.fail`. Usually means:

1. The pairing code was rejected and the server closed the socket (this is the most common case — re-pair).
2. Network path broke mid-handshake (check connectivity).
3. The relay crashed. Check `journalctl --user -u hermes-relay`.

Follow the re-pair steps above.

## `No relay URL. Pass --remote ws://host:port or set HERMES_RELAY_URL`

Exactly what it says. The `remote` URL is required for any network command. Either:

```bash
hermes-relay --remote ws://<host>:8767 ...
# or
export HERMES_RELAY_URL=ws://<host>:8767
hermes-relay ...
```

The stored session is keyed by URL, so once paired against a specific URL, that URL is the one to reuse.

## Pairing code pastes as `[200~F3W7EY[201~`

Your terminal's bracketed paste mode wasn't respected by readline. Options:

1. **Type the 6 chars manually.** Most reliable.
2. **Pass positionally** to skip the prompt:
   ```bash
   hermes-relay pair F3W7EY --remote ws://<host>:8767
   ```

The CLI disables bracketed paste before the readline prompt and strips ANSI defensively — but some terminals (older WezTerm, certain PowerShell+Windows-Terminal combos, Claude Desktop's embedded terminal) ignore the disable flag.

## `timed out after 30ms` (or any millisecond-range timeout on a desktop tool)

You're running a pre-fix desktop CLI build. The Python side sends `timeout` in seconds; early Node builds treated it as milliseconds — `30` seconds became 30 ms, and every shell command SIGKILL'd instantly.

Fixed in releases after 2026-04-23. Upgrade:

::: code-group

```powershell [Windows]
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

```bash [macOS / Linux]
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
```

```bash [from source]
cd hermes-relay/desktop && git pull && npm run build
```

:::

## Agent reports `desktop_*` tools are not available

Two layers — check them in order:

```bash
# On the server
curl -s "http://127.0.0.1:8767/desktop/_ping?tool=desktop_terminal"
```

**If `connected: false`**: no desktop CLI is attached. Start `hermes-relay shell` or `chat`; make sure you didn't pass `--no-tools`; make sure you consented on the first-run prompt.

**If `connected: true`** but Hermes still can't see the tools: the plugin isn't loaded by the gateway, or the `desktop` toolset isn't enabled for your session.

Check `~/.hermes/config.yaml` on the server:
```yaml
plugins:
  enabled:
    - model-router
    - hermes-relay   # ← must be here
```

If missing, add it and restart:
```bash
systemctl --user restart hermes-gateway
```

Enable the toolset for your current session — inside `hermes-relay shell`, ask Victor: "enable the `desktop` toolset for this session." Or add it to the default enabled toolsets in `config.yaml`.

## Agent calls a desktop tool but response comes back with the **server's** hostname instead of mine

The Python handler is running the command locally on the server instead of routing to your client. Two causes:

1. **No client is connected to the `desktop` channel** — the Python handler falls back to returning an error. Verify via `/desktop/_ping` (see above). If `connected: false`, start a shell session.
2. **The wrong tool was called.** Hermes has a `terminal` toolset (server-side shell) AND a `desktop` toolset (client-side). If Hermes picked `terminal` instead of `desktop_terminal`, it ran on the server. Ask explicitly: "use **desktop_terminal** to run ...".

## Windows SmartScreen: "Windows protected your PC"

Unsigned binary (expected during experimental phase). Click **More info → Run anyway**, or pre-allow:

```powershell
Unblock-File "$env:USERPROFILE\.hermes\bin\hermes-relay.exe"
```

Signed EV binaries land with v1.0.

## macOS: "hermes-relay can't be opened because the developer cannot be verified"

Quarantine xattr. Clear once:

```bash
xattr -dr com.apple.quarantine ~/.hermes/bin/hermes-relay
```

Apple Developer ID signing + notarization lands with v1.0.

## `shell` drops me into bash / tmux, not hermes

The post-attach `exec hermes` injection didn't happen. Usually: `--raw` was set, or the previous tmux session already has a different shell running that captures the injection.

```bash
# Kill the existing tmux session (inside shell)
Ctrl+A k
# Re-enter — fresh tmux will exec hermes on attach
hermes-relay shell
```

Or explicitly:
```bash
hermes-relay shell --exec hermes
```

## `Ctrl+A .` doesn't detach — it types a period

You're probably in hermes's TUI input field, not at the escape-key handler. Make sure hermes isn't capturing Ctrl+A as "select all" in an input — some Ink apps do.

Fallback: close the terminal window (tmux preserves state on disconnect — next run re-attaches).

## Tool calls hang, then fail with `aborted`

The 30-second router ceiling fired. The handler is stuck — usually because `desktop_terminal` is running a command that reads stdin, or a command that doesn't terminate.

- Confirm the command terminates when run manually.
- Pass a short explicit timeout in the tool call args: `desktop_terminal("command", timeout=5)`.
- If the command genuinely needs >30 s, it's too long for tool-use — break it into smaller steps, or use background + polling.

## `certificate pin mismatch` (wss only)

The TLS peer cert SHA256 differs from the one stored at pair time. Either the relay rotated its cert, or someone is MITMing the connection.

Legitimate rotation: re-pair (which wipes the old pin + stores the new one):
```bash
hermes-relay pair --remote wss://<host>:8767
```

If you DID NOT rotate and the pin mismatches, **DO NOT CONTINUE**. Check your network path — VPN/DNS hijack is a real possibility on public networks.

## Still stuck?

Run the self-diagnostic skill from any Hermes chat:

```
/hermes-relay-desktop-setup
```

The skill can invoke `desktop_terminal` on **your machine** to read your config, check versions, trace PATH issues — without you having to paste console output. It's the fastest triage path.

Or open an issue with:
1. The full CLI output (run with `--verbose` and redact tokens).
2. `hermes-relay --version` + `hermes-relay status` output.
3. `/desktop/_ping` output from the server.
