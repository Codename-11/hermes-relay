# hermes-relay desktop CLI installer — Windows (PowerShell 5.1+).
#
#   irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
#
# Downloads a prebuilt binary from GitHub Releases — no Node.js required.
# Pin a specific release:
#   $env:HERMES_RELAY_VERSION='desktop-v0.3.0-alpha.1'; irm ... | iex
# Override install dir:
#   $env:HERMES_RELAY_INSTALL_DIR='C:\tools\hermes\bin'; irm ... | iex
#
# **Experimental phase** — binaries are unsigned. Windows SmartScreen may
# warn on first launch. This script documents the `Unblock-File` escape
# hatch on completion.

#Requires -Version 5.1
$ErrorActionPreference = 'Stop'

$repo    = if ($env:HERMES_RELAY_REPO)    { $env:HERMES_RELAY_REPO }    else { 'Codename-11/hermes-relay' }
$version = if ($env:HERMES_RELAY_VERSION) { $env:HERMES_RELAY_VERSION } else { 'latest' }
$dir     = if ($env:HERMES_RELAY_INSTALL_DIR) { $env:HERMES_RELAY_INSTALL_DIR } else { Join-Path $HOME '.hermes\bin' }

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

# Strip the `desktop-v` tag prefix and any `-alpha.N` / `-beta.N` / `-rc.N`
# tail so the pinned tag can be compared to the post-install --version
# output (which reports the bare package.json semver).
function Get-NormalizedPin {
  param([string]$Pin)
  if (-not $Pin -or $Pin -eq 'latest') { return '' }
  $v = $Pin
  if ($v.StartsWith('desktop-v')) { $v = $v.Substring('desktop-v'.Length) }
  elseif ($v.StartsWith('v'))     { $v = $v.Substring(1) }
  $dash = $v.IndexOf('-')
  if ($dash -ge 0) { $v = $v.Substring(0, $dash) }
  return $v
}

foreach ($cmd in 'Invoke-WebRequest','Get-FileHash') {
  if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
    Die "'$cmd' not available (need PowerShell 5.1+)"
  }
}

if (-not [Environment]::Is64BitOperatingSystem) {
  Die "32-bit Windows is not supported"
}

$arch  = 'x64'  # No ARM64 build yet; add hermes-relay-win-arm64 when cross-compile target lands.
$asset = "hermes-relay-win-$arch.exe"

# Resolve "latest" to a concrete tag. GitHub's /releases/latest/download/ URL
# always skips prereleases, which breaks install during any all-alpha window.
# Walk the releases API (newest first) and pick the first `desktop-v*` tag,
# prerelease or not. Pinned versions (HERMES_RELAY_VERSION=desktop-v...) skip
# this and use the tag directly.
$resolvedVersion = $version
if ($version -eq 'latest') {
  Say "-> resolving latest desktop-v* release..."
  try {
    $releases = Invoke-RestMethod -UseBasicParsing "https://api.github.com/repos/$repo/releases"
    $pick = $releases | Where-Object { $_.tag_name -like 'desktop-v*' } | Select-Object -First 1
    if (-not $pick) { Die "no desktop-v* releases found on $repo" }
    $resolvedVersion = $pick.tag_name
    Say "   $resolvedVersion$(if ($pick.prerelease) { ' (prerelease)' } else { '' })"
  } catch {
    Die "could not query GitHub Releases API: $($_.Exception.Message)"
  }
}
$base = "https://github.com/$repo/releases/download/$resolvedVersion"

Say "Hermes-Relay desktop CLI installer"
Say "  platform : win-$arch"
Say "  asset    : $asset"
Say "  version  : $resolvedVersion"
Say "  install  : $dir"
Say ""

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

  # Create a `hermes.cmd` alias so muscle memory from the upstream hermes-agent
  # CLI (also called `hermes`) just works. Using a .cmd shim (not a symlink)
  # because Windows symlinks require admin or Developer Mode. .cmd also works
  # from any shell — cmd.exe, PowerShell, Git Bash, WSL interop — whereas a
  # .ps1 shim would only fire from PowerShell.
  $hermesShim = Join-Path $dir 'hermes.cmd'
  $existingShim = $null
  if (Test-Path $hermesShim) {
    $existingShim = Get-Content $hermesShim -Raw -ErrorAction SilentlyContinue
  }
  if (-not (Test-Path $hermesShim) -or ($existingShim -match 'hermes-relay\.exe')) {
    # NB: the closing '@ of a PowerShell here-string MUST sit at column 0.
    # Indenting it is a parse error, so we pull the here-string flush-left
    # and scope-hide it in a subexpression.
$shimBody = @'
@echo off
"%~dp0hermes-relay.exe" %*
'@
    Set-Content -Path $hermesShim -Value $shimBody -Encoding ASCII -Force
    Say "-> created hermes.cmd -> hermes-relay.exe alias"
  } else {
    Say "-> hermes.cmd already exists at $hermesShim (skipped alias creation)"
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
Say '  hermes-relay --help       # (or the short alias: hermes --help)'
Say '  hermes-relay pair --remote ws://<host>:8767'
Say ''
