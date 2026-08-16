# Troubleshooting <ExperimentalBadge />

Common errors in the CLI, indexed by exact message. If your problem isn't here, [open an issue](https://github.com/Codename-11/hermes-relay/issues/new).

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

Or run with the platform-appropriate full path:

```powershell
# PowerShell
& "$env:USERPROFILE\.hermes\bin\hermes-relay.exe" --version
```

```bat
:: Command Prompt — one pair of quotes, no leading apostrophe or escaped quote
"%USERPROFILE%\.hermes\bin\hermes-relay.exe" --version
```

If Command Prompt says `'\"C:\Users\...\hermes-relay.exe\"' is not recognized`, the quotes were copied with a literal apostrophe/backslash wrapper. Use the exact `cmd.exe` form above.

## Clicking the tray icon does not open the management UI

Find **Hermes-Relay CLI UI** in the notification area, including the hidden-icons chevron, and left-click it. The compact popup should open directly above the taskbar icon. It is intentionally a focused management surface rather than a full chat or terminal application.

If the icon is absent, run `hermes-relay ui` or launch **Hermes-Relay CLI UI** from the Start menu. Check `~/.hermes/tray.log` for startup errors, inspect the installation, and confirm only one copy is running:

```powershell
Get-Process hermes-relay-tray -ErrorAction SilentlyContinue
hermes-relay ui status
```

For a CLI-only installation, add the UI with `hermes-relay ui install`.

## Many `Bun` or `hermes-relay.exe` processes / Windows error `0xc0000142`

Quit **Hermes-Relay CLI UI** first, then open a fresh PowerShell and stop the
normal background daemon:

```powershell
hermes-relay daemon stop
```

If the CLI cannot start, identify Hermes-Relay-owned processes by their exact
installed executable path. Review the rows before stopping anything:

```powershell
$relayBin = [IO.Path]::GetFullPath("$env:USERPROFILE\.hermes\bin\")
$relayProcesses = Get-CimInstance Win32_Process | Where-Object {
  $_.ExecutablePath -and
  [IO.Path]::GetFullPath($_.ExecutablePath).StartsWith($relayBin, [StringComparison]::OrdinalIgnoreCase) -and
  $_.Name -in @('hermes-relay.exe', 'hermes-relay-tray.exe')
}
$relayProcesses | Select-Object ProcessId, ParentProcessId, Name, ExecutablePath, CommandLine

# Run only after confirming every row belongs to the installed Hermes-Relay bundle:
$relayProcesses | ForEach-Object { Stop-Process -Id $_.ProcessId }
```

Do not stop every process named `Bun`. Bun is a shared runtime name, so a broad
name-based cleanup can terminate unrelated development tools. Do not broadly
terminate `adb.exe` or `reg.exe` either; exit the tray and let its owned probes
end, then target only processes whose executable path and command line you have
reviewed.

The affected tray build refreshed its complete management snapshot from a
hidden grant window every second. Each refresh launched several short-lived CLI,
registry, and ADB probes. A slow or stuck probe allowed refreshes to overlap,
building an unbounded process queue until Windows began rejecting new process
initialization. Updated builds coalesce refreshes, use a lightweight local grant
read, cache static probes, and apply child timeouts and retry backoff.

After cleanup, inspect these separate logs:

- `~/.hermes/tray.log` — tray snapshot, subprocess timeout, launch, exit, and
  panic failures.
- `~/.hermes/daemon.log` — the one long-running daemon's authentication,
  transport, reconnect, and desktop-tool router lifecycle.

If unrelated Windows programs still fail with `0xc0000142`, restart Windows
before relaunching the tray. A process storm and memory exhaustion can explain
the application failures, but a Windows stop or reboot cannot be attributed to
this defect conclusively without the corresponding Windows crash dump.

## The daemon is User but I need Administrator access

Normal-user operation is the safe default. Open UI Settings and choose **Restart as Administrator...**, then approve the Windows UAC prompt. The UI remains a normal user process; only the daemon and its approved tool/input actions are elevated.

Use `hermes-relay daemon status` or the tray status row to confirm the privilege. Elevation is never automatic and is not required for ordinary user files/apps.

When elevated access is no longer needed, choose **Return to user mode**. The tray stops the elevated daemon once and starts a normal daemon; it does not retain elevation as a startup preference.

## I need the CLI, logs, or a diagnostic report

Open UI Settings and use **Open terminal** for a normal command prompt with `hermes-relay` available, or **Open Hermes CLI** to start the paired Hermes TUI directly. **View daemon log** opens `~/.hermes/daemon.log`; tray startup and child-process failures are recorded separately in `~/.hermes/tray.log`. **Run diagnostics** uses the CLI diagnostic path so the UI and `hermes-relay doctor` report the same local install state.

The **Help & About** page links to the [desktop documentation](https://hermes-relay.dev/docs/desktop/), [troubleshooting guide](https://hermes-relay.dev/docs/desktop/troubleshooting/), and [release notes](https://github.com/Codename-11/hermes-relay/releases?q=desktop) in your default browser and also provides the log and diagnostic shortcuts.

## Changing host access did not change the daemon's tools

The persistent preference is read when the daemon starts. Check the state and restart requirement:

```powershell
hermes-relay hosts list
hermes-relay daemon restart
```

Access is stored per host. **Restricted** connects with no desktop tools, **Ask Every Time** requests local approval for each available operation, **Standard** enables typed operations without general raw command launch, and **Full Access** allows every available capability without task grants for the selected host. New pairings default to Ask Every Time, while existing hosts keep their stored policy. Commands, Files, Screen & Input, Raw USB, Microphone, and Camera can be changed individually; an exact combination selects its preset automatically and any other combination is Custom. ADB remains secondary to the host-wide Raw USB gate. The UI normally restarts a running daemon after a host or access change; use the explicit restart above if an interrupted transition left stale state.

## A desktop-control request is waiting or needs to be stopped

The Windows UI presents a focused approval card without requiring the main popup. Approve or reject there, or run `hermes-relay grants` for terminal review. To end active access immediately:

```powershell
hermes-relay computer-use cancel
```

Switching the host to **Restricted** or using emergency stop also requests cancellation. Without a local approval response, a headless request times out and input remains blocked.

## CUA Driver is unavailable, incompatible, or degraded

Inspect the machine-local engine report:

```powershell
hermes-relay computer-use status --json
```

- **Not installed** means the canonical package was not found at
  `%USERPROFILE%\.cua-driver\packages\current\cua-driver.exe`. A PATH-only shim
  is intentionally ignored.
- **Incompatible** means the executable, manifest, supported version, required
  tool set, or permission mode did not match the Hermes adapter contract.
- **Accessibility health** is a separate, explicit diagnostic on Windows. Use
  **Recheck** in the UI or run `hermes-relay computer-use cua health`. A
  degraded result does not disable the runtime while the temporary workaround
  for the upstream fixed-timeout issue is active; canonical runtime checks and
  each structured action still fail closed. Confirm the driver is running in
  the interactive user's logon session rather than Session 0 before deeper
  diagnosis.

CUA is preferred for new structured-control sessions. If its executable,
manifest, required tool set, daemon status, or safe permission mode is not
ready before a session begins, Hermes can select the Windows input compatibility backend;
it never changes backend in the middle of a control session. Re-check with
`hermes-relay computer-use cua status`, repair explicitly with
`computer-use cua install --yes`, or use `computer-use cua check-update` followed
by `computer-use cua update --yes`. The UI exposes matching Install, Check, and
Update actions. None run automatically.

If a CUA action reports that background delivery is unavailable, Hermes does
not silently foreground the target. Use another structured action or complete
that step manually. **Allow foreground escalation** is reserved in this release:
runtime status remains off even if an older preview saved the preference, and
Full Access does not turn it on.

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

Mint a fresh code on the server first: `hermes pair --ttl 600`.

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

## `Win+Shift+S` then `/paste` says "no image found"

Windows's `Win+Shift+S` snipping tool stages the screenshot in the clipboard, but on first capture some PowerShell environments don't see it immediately because of the **`-STA`** flag requirement. Two fixes — pick whichever is most cohesive:

- **Inside `hermes-relay`** (the default shell/TUI mode): just press `Ctrl+A v`. The chord reads your clipboard with the right STA flag, ships it to the server inbox via `/clipboard/inbox`, and auto-types `/paste` into the TUI for you. One keystroke, no leaving the session.
- **From a separate terminal**: run `hermes-relay paste` (one-shot) — same plumbing as the chord, exits when done.
- **In `hermes-relay chat` REPL**: just type `/paste`. Same code path.

If `/paste` still says no image found after either of the above, see the `-STA` note below — your custom PowerShell invocation may have lost the flag.

## PowerShell `Add-Type` / `Get-Clipboard -Format Image` returns null

Almost always missing the `-STA` flag. `powershell.exe -Command` defaults to MTA (Multi-Threaded Apartment), and `[System.Windows.Forms.Clipboard]::GetImage()` only returns a valid image from STA threads — from MTA it silently returns null, indistinguishable from "no image present."

The CLI uses `-STA` everywhere it shells out to PowerShell (fixed in alpha.10 — `desktop-v0.3.0-alpha.7` through `alpha.9` were affected). If you've wrapped the binary in your own PowerShell invocation that calls one of the same paths, make sure your wrapper passes `-STA`:

```powershell
powershell.exe -NoProfile -NonInteractive -STA -Command "<your script>"
```

## Drag-drop a file onto the terminal window

Drag-dropping a file from File Explorer onto Windows Terminal pastes its path; the server-side `input.detect_drop` handler picks up the path on the next prompt and attaches it as an image (or other supported type). Useful when `/paste` and `Ctrl+A v` aren't applicable — e.g. attaching a file you've already saved to disk.

## `hermes-relay update` says "Up to date" but I know there's a newer alpha

Fixed in alpha.11. Pre-alpha.11 builds picked the wrong "latest" release because GitHub's `/repos/.../releases` API returns rows ordered by `created_at`, not by SemVer of the tag — and `created_at` shifts whenever a release row is touched (re-tag, manual edit, asset replacement). All three resolvers (`updater.ts`, `install.sh`, `install.ps1`) blindly took `[0]`.

**Bootstrap onto alpha.11+ via the install one-liner once:**

::: code-group

```powershell [Windows]
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

```bash [macOS / Linux]
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
```

:::

Both install scripts have been updated with the SemVer-max picker (`sort -V | tail -1` on bash; a custom `Sort-Object` comparator on PowerShell that packs (Major, Minor, Patch, PrereleaseRank, PrereleaseNum) into a sortable string). After re-installing, `hermes-relay update --check` will see new releases correctly.

## Install scripts truncated the prerelease suffix in the upgrade line

Fixed in alpha.12. Pre-alpha.12 `install.sh` and `install.ps1` printed lines like `existing install detected: 0.3.0-alpha.9 — upgrading to 0.3.` (truncated mid-token) because both normalizers stripped everything after the first `-`, including `-alpha.N`. The strip used to be defensive (binary's `--version` reported only the bare semver), but since alpha.4 the binary reports the full tail via the embedded `gen:version` constant — so the strip became lossy. Removed in both normalizers; full `0.3.0-alpha.14` now round-trips correctly.

## `timed out after 30ms` (or any millisecond-range timeout on a desktop tool)

You're running a pre-fix CLI build. The Python side sends `timeout` in seconds; early Node builds treated it as milliseconds — `30` seconds became 30 ms, and every shell command SIGKILL'd instantly.

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

**If `connected: false`**: no CLI client is attached. Run `hermes-relay` (bare = shell/TUI mode by default) or `hermes-relay chat`; make sure you didn't pass `--no-tools`; make sure you consented on the first-run prompt.

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

The SHA256 pin of the TLS leaf certificate's public key (SPKI, stored as `sha256/<base64>` — the same format as the Android app's pin store) differs from the one recorded at pair time. Either the relay rotated its cert, or someone is MITMing the connection.

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
