# hermes-relay desktop installer - Windows (PowerShell 5.1+).
#
#   irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
#
# Downloads the CLI + optional menu-only systray installer by default - no Node.js required.
# Install only the CLI binary instead:
#   $env:HERMES_RELAY_INSTALL_SURFACE='cli'; irm ... | iex
# Pin a specific release:
#   $env:HERMES_RELAY_VERSION='desktop-v0.3.0-alpha.18'; irm ... | iex
# Override CLI install dir:
#   $env:HERMES_RELAY_INSTALL_DIR='C:\tools\hermes\bin'; irm ... | iex
# Optional `hermes` alias for Orca/upstream-style workflows:
#   $env:HERMES_RELAY_HERMES_ALIAS='enable'; $env:HERMES_RELAY_INSTALL_SURFACE='cli'; irm ... | iex
# Disable an existing hermes-relay-owned alias without reinstalling:
#   $env:HERMES_RELAY_HERMES_ALIAS='disable'; irm .../hermes-alias.ps1 | iex
#
# **Experimental phase** - assets are unsigned. Windows SmartScreen may warn
# on first launch. The CLI branch documents the `Unblock-File` escape hatch on
# completion; the systray branch runs the downloaded NSIS installer.

#Requires -Version 5.1
$ErrorActionPreference = 'Stop'

$repo    = if ($env:HERMES_RELAY_REPO)    { $env:HERMES_RELAY_REPO }    else { 'Codename-11/hermes-relay' }
$version = if ($env:HERMES_RELAY_VERSION) { $env:HERMES_RELAY_VERSION } else { 'latest' }
$dir     = if ($env:HERMES_RELAY_INSTALL_DIR) { $env:HERMES_RELAY_INSTALL_DIR } else { Join-Path $HOME '.hermes\bin' }
$surface = if ($env:HERMES_RELAY_INSTALL_SURFACE) { $env:HERMES_RELAY_INSTALL_SURFACE.ToLowerInvariant() } else { 'tray' }
$aliasMode = if ($env:HERMES_RELAY_HERMES_ALIAS) { $env:HERMES_RELAY_HERMES_ALIAS.ToLowerInvariant() } else { 'skip' }

function Say($msg)  { Write-Host "  $msg" }
function Die($msg)  { Write-Host "install.ps1: $msg" -ForegroundColor Red; exit 1 }

# Best-effort read of an installed binary's version. Returns the bare
# version string (e.g. "0.3.0") or $null on any failure.
#
# NOTE: we deliberately DON'T wrap this in Start-Job / Wait-Job -Timeout,
# which would give us a kill switch if the binary hangs. Start-Job spins
# up a whole child PowerShell process per call, which is heavy on Windows.
# In practice, `hermes-relay --version` reads package.json and exits in
# under 100ms. If it hangs, the user has bigger problems than a blocked
# installer — they'll Ctrl-C and rerun.
function Read-InstalledVersion {
  param([string]$Path)
  if (-not (Test-Path $Path)) { return $null }
  try {
    $line = & $Path --version 2>$null | Select-Object -First 1
    if (-not $line) { return $null }
    # Expected form: "hermes-relay X.Y.Z" — second whitespace-separated token.
    $parts = ($line -split '\s+') | Where-Object { $_ -ne '' }
    if ($parts.Count -ge 2) { return $parts[1] }
    return $null
  } catch {
    return $null
  }
}

# Strip the release tag prefix to get the bare semver (KEEPS the
# prerelease suffix — `0.3.0-alpha.11`, not `0.3.0`). The binary's
# `--version` output has reported the full semver since alpha.4 (when
# `gen:version` started embedding the full string from package.json), so
# the comparison is full-semver vs full-semver. Stripping the prerelease
# here used to be defensive when the binary reported bare `0.3.0`, but
# now it just produces "upgrading to 0.3.0" instead of "upgrading to
# 0.3.0-alpha.11" in the user-facing message.
function Get-NormalizedPin {
  param([string]$Pin)
  if (-not $Pin -or $Pin -eq 'latest') { return '' }
  $v = $Pin
  if ($v.StartsWith('desktop-v')) { $v = $v.Substring('desktop-v'.Length) }
  elseif ($v.StartsWith('cli-v')) { $v = $v.Substring('cli-v'.Length) }
  elseif ($v.StartsWith('v'))     { $v = $v.Substring(1) }
  return $v
}

foreach ($cmd in 'Invoke-WebRequest','Get-FileHash') {
  if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
    Die "'$cmd' not available (need PowerShell 5.1+)"
  }
}

function Is-RelayShim {
  param([string]$Path)
  if (-not (Test-Path -LiteralPath $Path)) { return $false }
  $content = Get-Content -LiteralPath $Path -Raw -ErrorAction SilentlyContinue
  return $content -match 'hermes-relay\.exe'
}

function Alias-Enabled {
  param([string]$Mode)
  return $Mode -in @('enable', 'on', 'true', 'yes', '1')
}

function Alias-Disabled {
  param([string]$Mode)
  return $Mode -in @('disable', 'off', 'false', 'no', '0', 'remove')
}
if ($surface -ne 'tray' -and $surface -ne 'cli') {
  Die "HERMES_RELAY_INSTALL_SURFACE must be 'tray' or 'cli'"
}
if ($aliasMode -notin @('skip', '', 'enable', 'on', 'true', 'yes', '1', 'disable', 'off', 'false', 'no', '0', 'remove')) {
  Die "HERMES_RELAY_HERMES_ALIAS must be 'enable', 'disable', or unset"
}

if (-not [Environment]::Is64BitOperatingSystem) {
  Die "32-bit Windows is not supported"
}

$arch  = 'x64'  # No ARM64 build yet; add hermes-relay-win-arm64 when cross-compile target lands.
$asset = if ($surface -eq 'tray') { "hermes-relay-windows-$arch-setup.exe" } else { "hermes-relay-win-$arch.exe" }

# Resolve "latest" to a concrete tag. GitHub's /releases/latest/download/ URL
# always skips prereleases, which breaks install during any all-alpha window.
# Walk the releases API and prefer the SemVer-max `desktop-v*` tag. Historical
# public releases used `cli-v*`, so fall back to that track. Pinned versions
# skip this and use the tag directly.
$resolvedVersion = $version
if ($version -eq 'latest') {
  Say "-> resolving latest desktop-v* release..."
  try {
    $releases = Invoke-RestMethod -UseBasicParsing "https://api.github.com/repos/$repo/releases"
    # Don't trust the API's first-element ordering — GitHub orders by the
    # release row's created_at which shifts when the row is touched (re-tag,
    # edit). Sort by parsed version components explicitly so a touched
    # alpha.9 row can't outrank a freshly-tagged alpha.10. Pack as a
    # zero-padded sortable string: MAJOR.MINOR.PATCH.PRERANK.PRENUM where
    # PRERANK is 1=alpha, 2=beta, 3=rc, 999=stable (semver §11: stable >
    # any prerelease) and PRENUM is the prerelease number (so alpha.10 >
    # alpha.9).
    $candidates = $releases | Where-Object { $_.tag_name -like 'desktop-v*' }
    if (-not $candidates) {
      Say '   no desktop-v* releases yet; checking historical cli-v* releases...'
      $candidates = $releases | Where-Object { $_.tag_name -like 'cli-v*' }
    }
    if (-not $candidates) { Die "no desktop-v* or historical cli-v* releases found on $repo" }
    $pick = $candidates | Sort-Object @{Expression = {
        $v = $_.tag_name -replace '^cli-v', ''
        $v = $v -replace '^desktop-v', ''
        $core, $pre = ($v -split '-', 2)
        $parts = $core -split '\.'
        $major = [int]$parts[0]; $minor = [int]$parts[1]; $patch = [int]$parts[2]
        $preRank = 999
        $preNum = 0
        if ($pre) {
            $preParts = $pre -split '\.'
            switch -regex ($preParts[0]) {
                '^alpha$' { $preRank = 1 }
                '^beta$'  { $preRank = 2 }
                '^rc$'    { $preRank = 3 }
                default   { $preRank = 0 }
            }
            if ($preParts.Length -gt 1 -and $preParts[1] -match '^\d+$') {
                $preNum = [int]$preParts[1]
            }
        }
        '{0:D5}.{1:D5}.{2:D5}.{3:D5}.{4:D5}' -f $major, $minor, $patch, $preRank, $preNum
    }} | Select-Object -Last 1
    $resolvedVersion = $pick.tag_name
    Say "   $resolvedVersion$(if ($pick.prerelease) { ' (prerelease)' } else { '' })"
  } catch {
    Die "could not query GitHub Releases API: $($_.Exception.Message)"
  }
}
$base = "https://github.com/$repo/releases/download/$resolvedVersion"

Say "Hermes-Relay desktop installer"
Say "  surface  : $surface"
Say "  platform : win-$arch"
Say "  asset    : $asset"
Say "  version  : $resolvedVersion"
if ($surface -eq 'cli') { Say "  install  : $dir" }
Say ""

if ($surface -eq 'tray') {
  $tmp = New-Item -ItemType Directory -Path (Join-Path $env:TEMP ("hermes-relay-" + [Guid]::NewGuid()))
  try {
    Say '-> downloading tray installer...'
    $installer = Join-Path $tmp $asset
    try {
      Invoke-WebRequest -UseBasicParsing "$base/$asset" -OutFile $installer
    } catch {
      Die "download failed: $base/$asset (maybe no Windows tray installer for this version yet?)"
    }

    Say '-> downloading checksums...'
    try {
      Invoke-WebRequest -UseBasicParsing "$base/SHA256SUMS.txt" -OutFile (Join-Path $tmp 'SHA256SUMS.txt')
    } catch {
      Die "could not fetch SHA256SUMS.txt (release incomplete?)"
    }

    Say '-> verifying SHA256...'
    $expectedLine = (Select-String -Path (Join-Path $tmp 'SHA256SUMS.txt') -Pattern " $asset$").Line
    if (-not $expectedLine) { Die "SHA256SUMS.txt has no entry for $asset" }
    $expected = $expectedLine.Split(' ')[0].ToLower()
    $actual   = (Get-FileHash $installer -Algorithm SHA256).Hash.ToLower()
    if ($expected -ne $actual) { Die "checksum mismatch (expected $expected, got $actual) - refusing to install" }
    Say '   ok'

    if (Get-Command Unblock-File -ErrorAction SilentlyContinue) {
      Unblock-File $installer -ErrorAction SilentlyContinue
    }

    $installerArgs = @()
    if ($env:HERMES_RELAY_TRAY_SILENT -eq '1') {
      $installerArgs += '/S'
    }
    Say '-> launching installer...'
    $proc = Start-Process -FilePath $installer -ArgumentList $installerArgs -Wait -PassThru
    if ($proc.ExitCode -ne 0) {
      Die "tray installer exited with code $($proc.ExitCode)"
    }
  } finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
  }

  Say ''
  Say 'Installed the Hermes Relay CLI and optional menu-only systray. Right-click the tray icon to manage the daemon or open the real CLI/TUI.'
  Say 'For CLI-only installs, rerun with:'
  Say "  `$env:HERMES_RELAY_INSTALL_SURFACE='cli'; irm https://raw.githubusercontent.com/$repo/main/desktop/scripts/install.ps1 | iex"
  return
}

$tmp = New-Item -ItemType Directory -Path (Join-Path $env:TEMP ("hermes-relay-" + [Guid]::NewGuid()))
try {
  # Pre-install: detect an existing binary so the user can see upgrade vs
  # reinstall vs overwrite-after-corruption, instead of the install going
  # silent and requiring a follow-up `hermes-relay --version` to find out.
  $target             = Join-Path $dir 'hermes-relay.exe'
  # Use the RESOLVED tag (same as $version for pins, or the API-resolved
  # tag when $version = 'latest') so version compares stay accurate when
  # `latest` resolved to a prerelease.
  $expectedNewVersion = Get-NormalizedPin $resolvedVersion
  $existingVersion    = $null
  if (Test-Path $target) {
    $existingVersion = Read-InstalledVersion $target
    if ($existingVersion) {
      if ($expectedNewVersion) {
        if ($existingVersion -eq $expectedNewVersion) {
          Say "-> existing install detected: $existingVersion — reinstalling same version"
        } else {
          Say "-> existing install detected: $existingVersion — upgrading to $expectedNewVersion"
        }
      } else {
        Say "-> existing install detected: $existingVersion — will replace with latest"
      }
    } else {
      Say '-> existing install detected (could not read version) — overwriting'
    }
  }

  Say '-> downloading binary...'
  try {
    Invoke-WebRequest -UseBasicParsing "$base/$asset" -OutFile (Join-Path $tmp $asset)
  } catch {
    Die "download failed: $base/$asset (maybe no Windows release for this version yet?)"
  }

  Say '-> downloading checksums...'
  try {
    Invoke-WebRequest -UseBasicParsing "$base/SHA256SUMS.txt" -OutFile (Join-Path $tmp 'SHA256SUMS.txt')
  } catch {
    Die "could not fetch SHA256SUMS.txt (release incomplete?)"
  }

  Say '-> verifying SHA256...'
  $expectedLine = (Select-String -Path (Join-Path $tmp 'SHA256SUMS.txt') -Pattern " $asset$").Line
  if (-not $expectedLine) { Die "SHA256SUMS.txt has no entry for $asset" }
  $expected = $expectedLine.Split(' ')[0].ToLower()
  $actual   = (Get-FileHash (Join-Path $tmp $asset) -Algorithm SHA256).Hash.ToLower()
  if ($expected -ne $actual) { Die "checksum mismatch (expected $expected, got $actual) — refusing to install" }
  Say '   ok'

  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  Copy-Item -Force (Join-Path $tmp $asset) $target

  # Optional `hermes.cmd` alias for Orca/upstream-style workflows. It is not
  # created by default because it can shadow a real local hermes-agent install.
  $hermesShim = Join-Path $dir 'hermes.cmd'
  if (Alias-Enabled $aliasMode) {
    if ((Test-Path -LiteralPath $hermesShim) -and -not (Is-RelayShim $hermesShim)) {
      Say "-> hermes.cmd already exists at $hermesShim (left untouched)"
    } else {
      # NB: the closing '@ of a PowerShell here-string MUST sit at column 0.
      # Indenting it is a parse error, so we pull the here-string flush-left
      # and scope-hide it in a subexpression.
$shimBody = @'
@echo off
"%~dp0hermes-relay.exe" %*
'@
      Set-Content -Path $hermesShim -Value $shimBody -Encoding ASCII -Force
      Say "-> created hermes.cmd -> hermes-relay.exe alias"
    }
  } elseif (Alias-Disabled $aliasMode) {
    if ((Test-Path -LiteralPath $hermesShim) -and (Is-RelayShim $hermesShim)) {
      Remove-Item -LiteralPath $hermesShim -Force
      Say "-> removed hermes.cmd alias"
    } elseif (Test-Path -LiteralPath $hermesShim) {
      Say "-> hermes.cmd exists but is not managed by hermes-relay (left untouched)"
    } else {
      Say "-> hermes.cmd alias already disabled"
    }
  } elseif ((Test-Path -LiteralPath $hermesShim) -and (Is-RelayShim $hermesShim)) {
    Say "-> existing hermes.cmd alias left enabled (disable with HERMES_RELAY_HERMES_ALIAS='disable' and hermes-alias.ps1)"
  } else {
    Say "-> skipped optional hermes.cmd alias (enable with HERMES_RELAY_HERMES_ALIAS='enable')"
  }

  # Post-install: confirm the NEW binary reports a sensible version. Don't
  # fail the install on mismatch — the user may have pinned to a pre-release
  # whose version_name differs slightly from the tag.
  $installedVersion = Read-InstalledVersion $target
  if ($installedVersion) {
    Say "-> installed $installedVersion at $target"
    if ($expectedNewVersion -and ($installedVersion -ne $expectedNewVersion)) {
      Say "   WARN: expected version $expectedNewVersion from tag ($resolvedVersion), got $installedVersion"
      Say '   (pre-release tags can diverge from package version — this is usually fine)'
    }
  } else {
    Say "-> installed $target (could not read post-install version)"
  }

  # PATH: add to the USER scope so it persists but doesn't need admin.
  $userPath = [Environment]::GetEnvironmentVariable('Path','User')
  $parts    = @()
  if ($userPath) { $parts = $userPath -split ';' | Where-Object { $_ -ne '' } }
  if ($parts -notcontains $dir) {
    [Environment]::SetEnvironmentVariable('Path', ($parts + $dir) -join ';', 'User')
    Say "-> added $dir to user PATH"
    Say "   (open a new terminal to pick it up — in-process PATH isn't refreshed)"
  } else {
    Say "-> $dir is already on PATH"
  }
} finally {
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

Say ''
Say '   Windows note: this binary is unsigned (experimental phase).'
Say '   If SmartScreen blocks first launch, click "More info" -> "Run anyway",'
Say '   or pre-allow via:'
Say ''
Say "     Unblock-File '$target'"
Say ''
if ($installedVersion) {
  Say "Installed hermes-relay $installedVersion. Try:"
} else {
  Say 'Installed. Try:'
}
Say '  hermes-relay --help'
Say '  hermes-relay pair --remote ws://<host>:8767'
Say ''
Say 'Optional Orca/upstream-style alias:'
Say "  `$env:HERMES_RELAY_HERMES_ALIAS='enable'; irm https://raw.githubusercontent.com/$repo/main/desktop/scripts/hermes-alias.ps1 | iex"
Say "  `$env:HERMES_RELAY_HERMES_ALIAS='disable'; irm https://raw.githubusercontent.com/$repo/main/desktop/scripts/hermes-alias.ps1 | iex"
Say ''
