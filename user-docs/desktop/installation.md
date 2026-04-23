# Installing the Desktop CLI <ExperimentalBadge />

Three install paths — binary (recommended, no Node required), npm (when we publish), or source clone. Windows is covered first because the binaries are ready; Mac/Linux binaries ship in the same release.

## Prerequisites

- A running Hermes-Relay server reachable from this machine (`curl -s http://<host>:8767/health` should return `{"status":"ok"}`).
- One of:
  - A prebuilt `hermes-relay` binary from [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases?q=desktop) — no Node.js needed. **Recommended.**
  - Node.js ≥21 if you want to install via npm or run from source.

## Windows — PowerShell one-liner

```powershell
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

The script:

1. Detects architecture (x64; ARM64 lands after the Bun cross-compile target stabilizes).
2. Downloads `hermes-relay-win-x64.exe` from the latest `desktop-v*` GitHub Release.
3. Verifies the SHA256 checksum against the published `SHA256SUMS.txt` from the same release.
4. Installs to `%USERPROFILE%\.hermes\bin\hermes-relay.exe`.
5. Adds `%USERPROFILE%\.hermes\bin` to your **user** PATH (no admin needed).

Open a **new** terminal (PATH updates don't retroactively apply), then verify:

```powershell
hermes-relay --version
```

### SmartScreen warning on first launch

The binary is unsigned during the experimental phase. Windows will show "Windows protected your PC" the first time you run it. Click **More info → Run anyway**, or pre-allow from PowerShell:

```powershell
Unblock-File "$env:USERPROFILE\.hermes\bin\hermes-relay.exe"
```

Code signing (EV cert) is a v1.0 milestone — the experimental phase doesn't justify the $300/yr.

### Pin a specific version

```powershell
$env:HERMES_RELAY_VERSION = 'desktop-v0.3.0-alpha.1'
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

### Uninstall

See [Uninstall](#uninstall) below — the PowerShell one-liner reverses everything install.ps1 did (binary + user-PATH entry), with optional tiers for session-data purge and service cleanup.

## macOS / Linux — curl one-liner

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
```

The script:

1. Detects OS/arch (supports `linux-x64`, `linux-arm64`, `darwin-x64`, `darwin-arm64`).
2. Downloads the matching binary + `SHA256SUMS.txt` from the latest `desktop-v*` release.
3. Verifies SHA256 (`sha256sum` on Linux, `shasum -a 256` on macOS).
4. Installs to `$HOME/.hermes/bin/hermes-relay` (mode 0755).
5. Hints how to add `$HOME/.hermes/bin` to your PATH if it isn't already — **does not mutate your shell rc silently**. Add the line yourself:

   ```bash
   export PATH="$HOME/.hermes/bin:$PATH"
   ```

   Put it in `~/.bashrc` / `~/.zshrc` / `~/.config/fish/config.fish` depending on your shell.

Verify in a fresh shell:

```bash
hermes-relay --version
```

### macOS quarantine

Unsigned binaries get quarantined by Gatekeeper on first run. If macOS refuses to open the binary, clear the xattr:

```bash
xattr -dr com.apple.quarantine ~/.hermes/bin/hermes-relay
```

Apple Developer ID signing + notarization is a v1.0 milestone.

### Pin a specific version

```bash
HERMES_RELAY_VERSION=desktop-v0.3.0-alpha.1 \
  curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
```

### Uninstall

See [Uninstall](#uninstall) below — the curl one-liner reverses install.sh, with optional tiers for session-data purge and service cleanup.

## Install from source (Node ≥21)

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

## Install via npm (coming soon)

Once we publish to npm (after experimental phase wraps), it'll be:

```bash
npm install -g @hermes-relay/cli
```

For now, the binary or source paths are the way.

## Uninstall

The uninstallers mirror the installers — one-liners on both platforms, three removal tiers.

### Tiers

| Flag              | What it removes                                                                                                                          |
|-------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| *(default)*       | `$HOME/.hermes/bin/hermes-relay[.exe]` + the Windows user-PATH entry install.ps1 added. Preserves `~/.hermes/remote-sessions.json`.         |
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

The script removes `%USERPROFILE%\.hermes\bin\hermes-relay.exe`, strips that directory from your **user** PATH (not system — no admin needed), and removes the install dir if it's empty.

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

After install, all three of these should succeed:

```bash
hermes-relay --version          # 0.x.x (matches release tag)
hermes-relay --help             # Full help text
hermes-relay status             # Local view — no sessions stored yet
```

Next step: **[Pairing](./pairing.md)** — mint a code on the server and exchange it for a stored session token.
