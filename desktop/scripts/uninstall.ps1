# hermes-relay desktop CLI uninstaller — Windows (PowerShell 5.1+).
#
#   irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/uninstall.ps1 | iex
#
# Reverses install.ps1. Three tiers:
#
#   (default)    Remove the binary and the user-PATH entry for $INSTALL_DIR.
#                Preserves $HOME\.hermes\remote-sessions.json so a future
#                re-install pairs seamlessly.
#   --purge      Also delete $HOME\.hermes\remote-sessions.json (bearer tokens,
#                cert pins, tools-consent flag). This file is SHARED with the
#                Ink TUI and Android tooling — wiping it affects those too.
#   --service    Stub: daemon service installers aren't shipped yet. Prints
#                the `sc.exe delete` invocation a future install would need,
#                so you can remove a manually crafted service yourself.
#
# Tiers combine: `--purge --service` runs both.
#
# Override install dir (matches install.ps1):
#   $env:HERMES_RELAY_INSTALL_DIR='C:\tools\hermes\bin'; irm ... | iex
#
# Piped into iex, argument passing is awkward — set these env vars instead:
#   $env:HERMES_RELAY_UNINSTALL_PURGE=1
#   $env:HERMES_RELAY_UNINSTALL_SERVICE=1

#Requires -Version 5.1
$ErrorActionPreference = 'Stop'

$dir          = if ($env:HERMES_RELAY_INSTALL_DIR) { $env:HERMES_RELAY_INSTALL_DIR } else { Join-Path $HOME '.hermes\bin' }
$sessionsFile = Join-Path $HOME '.hermes\remote-sessions.json'

# Parse from $args when invoked directly; fall back to env for iex pipelines.
$purge   = $false
$service = $false
foreach ($a in $args) {
  switch ($a) {
    '--purge'       { $purge = $true }
    '--service'     { $service = $true }
    '--binary-only' { }  # default; accept for symmetry with uninstall.sh
    '-h'            { Get-Content $PSCommandPath | Select-Object -First 22 | Where-Object { $_ -like '#*' } | ForEach-Object { $_ -replace '^# ?','' }; exit 0 }
    '--help'        { Get-Content $PSCommandPath | Select-Object -First 22 | Where-Object { $_ -like '#*' } | ForEach-Object { $_ -replace '^# ?','' }; exit 0 }
    default         { Write-Host "uninstall.ps1: unknown argument: $a" -ForegroundColor Red; exit 1 }
  }
}
if ($env:HERMES_RELAY_UNINSTALL_PURGE)   { $purge = $true }
if ($env:HERMES_RELAY_UNINSTALL_SERVICE) { $service = $true }

function Say($msg)  { Write-Host "  $msg" }
function Die($msg)  { Write-Host "uninstall.ps1: $msg" -ForegroundColor Red; exit 1 }

Say 'Hermes-Relay desktop CLI uninstaller'
Say "  install  : $dir"
if ($purge)      { Say "  mode     : --purge (binary + session data)" }
elseif ($service) { Say "  mode     : --service (binary + service stub)" }
else             { Say "  mode     : binary-only (preserves remote-sessions.json)" }
Say ''

# -- Tier 1: binary + PATH --------------------------------------------------

$target = Join-Path $dir 'hermes-relay.exe'
if (Test-Path -LiteralPath $target) {
  try {
    Remove-Item -LiteralPath $target -Force
    Say "-> removed $target"
  } catch {
    Die "failed to remove ${target}: $($_.Exception.Message)"
  }
} else {
  Say "-> no binary at $target (already removed?)"
}

# Remove install dir if empty — never touch user-created content.
if (Test-Path -LiteralPath $dir) {
  $leftover = @(Get-ChildItem -LiteralPath $dir -Force -ErrorAction SilentlyContinue)
  if ($leftover.Count -eq 0) {
    try {
      Remove-Item -LiteralPath $dir -Force
      Say "-> removed empty $dir"
    } catch {
      # Non-fatal; directory may be locked.
    }
  }
}

# PATH: mirror install.ps1's approach — read user scope, split on ;, filter
# out our dir, write back. Only rewrite if we actually changed something.
$userPath = [Environment]::GetEnvironmentVariable('Path','User')
if ($userPath) {
  $parts    = $userPath -split ';' | Where-Object { $_ -ne '' }
  $filtered = $parts | Where-Object { $_ -ne $dir }
  if ($parts.Count -ne $filtered.Count) {
    [Environment]::SetEnvironmentVariable('Path', ($filtered -join ';'), 'User')
    Say "-> removed $dir from user PATH"
    Say '   (open a new terminal to pick up the change)'
  } else {
    Say "-> $dir was not on user PATH"
  }
} else {
  Say '-> user PATH is empty; nothing to remove'
}

# -- Tier 2: purge session data --------------------------------------------

if ($purge) {
  Say ''
  if (Test-Path -LiteralPath $sessionsFile) {
    $size = (Get-Item -LiteralPath $sessionsFile).Length
    Say "!! --purge: wiping $sessionsFile ($size bytes)"
    Say '   This file is SHARED with:'
    Say '     - the Ink TUI (hermes-agent-tui-smoke)'
    Say '     - the Hermes Android desktop tooling'
    Say '   Those surfaces will lose their stored session tokens + cert pins too.'
    try {
      Remove-Item -LiteralPath $sessionsFile -Force
      Say "-> removed $sessionsFile"
    } catch {
      Die "failed to remove ${sessionsFile}: $($_.Exception.Message)"
    }
  } else {
    Say "-> --purge: no session file at $sessionsFile (already clean)"
  }
}

# -- Tier 3: service stub ---------------------------------------------------

if ($service) {
  Say ''
  Say '   --service: daemon service installers are not yet shipped.'
  Say '   If you manually installed a Windows service, remove it yourself:'
  Say ''
  Say '     sc.exe stop HermesRelayDaemon'
  Say '     sc.exe delete HermesRelayDaemon'
  Say ''
  Say '   (requires an elevated PowerShell / cmd prompt)'
}

Say ''
Say 'Removed. hermes-relay is gone. To reinstall:'
Say '  irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex'
Say ''
