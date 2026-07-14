# Installing the CLI <ExperimentalBadge />

Prebuilt, self-contained CLI binaries ship for Windows x64, Linux x64, and macOS x64/arm64 — **no Node or Python required**. Windows also has an optional native, menu-only systray installer.

## Prerequisites

- A running Hermes-Relay server reachable from this machine. `curl -s http://<host>:8767/health` should return `{"status":"ok"}`.
- That's it. The binary is self-contained (Bun-compiled, ~60–110 MB, depending on platform).

If you'd rather install from source, see the [source install](#install-from-source-node-21) section below — but the binary is the recommended path.

## Windows — PowerShell one-liner

```powershell
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

By default the script installs the Windows CLI **and** optional systray through the signed-checksum NSIS package. It:

1. Detects architecture (x64; ARM64 lands once Bun's cross-compile target stabilizes).
2. Resolves the **latest** CLI release by querying the GitHub Releases API directly and picking the SemVer-max `cli-v*` tag, with a migration fallback to historical `desktop-v*` prereleases. Prereleases are included, so alpha builds aren't skipped (see CHANGELOG entry on alpha.11 for why this matters).
3. Downloads `hermes-relay-windows-x64-setup.exe` and verifies SHA256 against the published `SHA256SUMS.txt`.
4. Runs the per-user installer. No administrator access is required.
5. Installs `hermes-relay.exe`, `hermes-relay-tray.exe`, and the uninstaller to `%USERPROFILE%\.hermes\bin`.
6. Adds that directory to your **user** PATH and creates Start-menu shortcuts for the TUI, systray, and uninstaller.
7. Offers an optional **Start systray when I sign in** component. The same preference can be changed later from the tray menu.
8. Starts the systray from the finish page when selected. The tray has no application window; right-click its notification-area icon.

For a CLI-only install, set the surface explicitly. This path downloads and verifies `hermes-relay-win-x64.exe` directly and prints the existing/new version comparison:

```powershell
$env:HERMES_RELAY_INSTALL_SURFACE = 'cli'
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

Open a **new** terminal (PATH updates don't retroactively apply to in-process shells), then verify:

```powershell
hermes-relay --version
hermes-relay daemon status
```

### SmartScreen warning on first launch

The binaries are unsigned during the experimental phase. Windows may show "Windows protected your PC" for the installer or first launch. Click **More info → Run anyway**. For a CLI-only install, you can also pre-allow the executable from PowerShell:

```powershell
Unblock-File "$env:USERPROFILE\.hermes\bin\hermes-relay.exe"
```

Code signing (EV cert) is a v1.0 milestone — the experimental phase doesn't justify the $300/yr.

### Pin a specific version

```powershell
$env:HERMES_RELAY_VERSION = 'cli-v0.4.0-alpha.2'
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

The resolver compares full SemVer including the prerelease tail, so an older alpha is correctly recognized as an upgrade.

### Uninstall

Use **Apps → Installed apps → Hermes Relay CLI + Systray**, the Start-menu uninstaller, or `%USERPROFILE%\.hermes\bin\uninstall-hermes-relay.exe`. Uninstall stops the tray and daemon, removes the installed binaries, Start-menu shortcuts, sign-in entry, and user-PATH entry, while preserving pairing/session data.

## macOS / Linux — curl one-liner

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
```

The script:

1. Detects OS/arch (published assets: `linux-x64`, `darwin-x64`, and `darwin-arm64`).
2. Resolves the latest `cli-v*` release via the Releases API + `sort -V`, with a migration fallback to historical `desktop-v*` prereleases (prerelease-aware, no shell deps beyond `curl` / `sort`).
3. Downloads the matching binary + `SHA256SUMS.txt` and verifies SHA256 (`sha256sum` on Linux, `shasum -a 256` on macOS).
4. Reads the existing binary's `--version` if present and prints `upgrading X → Y` / `reinstalling X` / `installing fresh`.
5. Installs to `$HOME/.hermes/bin/hermes-relay` (mode 0755).
6. Creates a `hermes` symlink next to the binary — same collision-safety as Windows: skipped if anything already exists at that path.
7. Hints how to add `$HOME/.hermes/bin` to your PATH if it isn't already — **does not mutate your shell rc silently**. Add the line yourself:

   ```bash
   export PATH="$HOME/.hermes/bin:$PATH"
   ```

   Put it in `~/.bashrc` / `~/.zshrc` / `~/.config/fish/config.fish` depending on your shell.

8. Re-runs the new binary's `--version` post-install to confirm.

Verify in a fresh shell:

```bash
hermes-relay --version
hermes --version
```

### macOS quarantine

Unsigned binaries get quarantined by Gatekeeper on first run. If macOS refuses to open the binary, clear the xattr:

```bash
xattr -dr com.apple.quarantine ~/.hermes/bin/hermes-relay
```

Apple Developer ID signing + notarization is a v1.0 milestone.

### Pin a specific version

```bash
HERMES_RELAY_VERSION=cli-v0.4.0-alpha.2 \
  curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
```

### Uninstall

See [Uninstall](#uninstall) below — the curl one-liner reverses install.sh, with optional tiers for session-data purge and service cleanup.

## Self-update — `hermes-relay update` {#self-update-hermes-relay-update}

Once installed, you don't have to keep re-running the `curl | sh` one-liner. The binary self-updates:

```bash
hermes-relay update             # download + verify + swap to latest cli-v*
hermes-relay update --check     # dry-run: print available version, don't install
hermes-relay update --yes       # skip confirm prompt
hermes-relay update --json      # machine-readable status
```

The updater:

1. Polls the GitHub Releases API and picks the SemVer-max `cli-v*` tag, with a migration fallback to historical `desktop-v*` prereleases (prereleases included). The same resolver as the install scripts — fixed in alpha.11; pre-alpha.11 builds may report "Up to date" when a newer alpha exists, so use the install one-liner once to bootstrap onto alpha.11+ if you're stuck below it.
2. SemVer-compares to your running version (`hermes-relay --version` — embedded at build time, accurate inside Bun-compiled binaries).
3. Downloads the platform asset and verifies SHA256.
4. **POSIX (macOS / Linux):** atomic `fs.rename` over the running binary. The running process keeps the old inode open, so `hermes-relay daemon` (if running) keeps serving until restarted; the next `hermes-relay <verb>` invocation picks up the new binary.
5. **Windows:** can't replace a running `.exe`, so the updater writes to `<bin>.new.exe` and `finalizePendingUpdate()` runs at the top of `main()` on every subsequent invocation to rename it into place. Result: the swap completes the **next** time you run `hermes-relay`.

`hermes-relay update` updates the CLI binary only. Windows systray/installer changes ship in the same release but require rerunning the PowerShell installer (or downloading the new setup asset) so both executables stay on the same version.

If `hermes-relay update --check` says "Up to date" but you know there's a newer alpha, see the [troubleshooting note](./troubleshooting.md#hermes-relay-update-says-up-to-date-but-i-know-there-s-a-newer-alpha).

## Install from source (Node ≥21) {#install-from-source-node-21}

For dev / contributors / custom builds:

```bash
git clone https://github.com/Codename-11/hermes-relay
cd hermes-relay/desktop
npm install
npm run build
npm link  # puts `hermes-relay` on your PATH via the npm global bin dir
```

Dev loop — skip the tsc build, run TypeScript directly:

```bash
npx tsx src/cli.ts --help
npx tsx src/cli.ts pair --remote ws://<host>:8767
```

The package name in `desktop/package.json` is local workspace metadata today. The CLI is not published to npm; use GitHub Release binaries or a local clone with `npm link`.

## Uninstall

The uninstallers mirror the installers — one-liners on both platforms, three removal tiers.

### Tiers

| Flag              | What it removes                                                                                                                          |
|-------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| *(default)*       | `$HOME/.hermes/bin/hermes-relay[.exe]` plus the `hermes` / `hermes.cmd` alias (only if it points at our binary) and the Windows user-PATH entry. Preserves `~/.hermes/remote-sessions.json`. |
| `--purge`         | Also deletes `~/.hermes/remote-sessions.json` — bearer tokens, cert pins, and the tools-consent flag.                                    |
| `--service`       | Stub. Prints the commands to remove a manually-installed systemd unit, launchd plist, or Windows service. No service installers ship yet. |

Tiers combine: `--purge --service` runs both.

**`--purge` warning:** `remote-sessions.json` is shared with the Ink TUI and the Hermes Android desktop tooling. Wiping it signs those surfaces out too. Use `--purge` when giving a machine away — not for routine cleanup.

### Windows

```powershell
# Binary + user-PATH entry only (default)
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/uninstall.ps1 | iex

# Also purge session tokens — iex can't forward args, so set env first
$env:HERMES_RELAY_UNINSTALL_PURGE = 1
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/uninstall.ps1 | iex
```

The script removes `%USERPROFILE%\.hermes\bin\hermes-relay.exe`, the `hermes.cmd` alias (if it points at our binary), strips that directory from your **user** PATH (not system — no admin needed), and removes the install dir if it's empty.

Open a new terminal afterward so shells pick up the PATH change.

### macOS / Linux

```bash
# Binary only (default)
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/uninstall.sh | sh

# Also purge session tokens
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/uninstall.sh | sh -s -- --purge
```

`install.sh` never touches your shell rc, so neither does `uninstall.sh`. If you added `$HOME/.hermes/bin` to your PATH manually, remove that line from your rc yourself — the script prints a reminder.

### Override install dir

Both scripts honor the same env var as the installers:

```bash
HERMES_RELAY_INSTALL_DIR=/opt/hermes \
  curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/uninstall.sh | sh
```

```powershell
$env:HERMES_RELAY_INSTALL_DIR = 'C:\tools\hermes\bin'
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/uninstall.ps1 | iex
```

## Verify

After install, all of these should succeed:

```bash
hermes-relay --version          # 0.x.x (matches release tag)
hermes-relay --help             # Full help text — every subcommand listed
hermes-relay status             # Local view — no sessions stored yet
hermes-relay doctor             # Full local diagnostic (version / paths / sessions / daemon)
hermes --version                # Alias resolves to the same binary
```

Next step: **[Pairing](./pairing.md)** — mint a code on the server and exchange it for a stored session token.
