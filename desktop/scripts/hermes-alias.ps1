# Manage the optional `hermes` alias for hermes-relay on Windows.
#
# Status:
#   irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/hermes-alias.ps1 | iex
#
# Enable:
#   $env:HERMES_RELAY_HERMES_ALIAS='enable'; irm .../hermes-alias.ps1 | iex
#
# Disable:
#   $env:HERMES_RELAY_HERMES_ALIAS='disable'; irm .../hermes-alias.ps1 | iex
#
# Override install dir:
#   $env:HERMES_RELAY_INSTALL_DIR='C:\tools\hermes\bin'; irm .../hermes-alias.ps1 | iex

#Requires -Version 5.1
$ErrorActionPreference = 'Stop'

$dir = if ($env:HERMES_RELAY_INSTALL_DIR) { $env:HERMES_RELAY_INSTALL_DIR } else { Join-Path $HOME '.hermes\bin' }
$action = if ($args.Count -gt 0) { $args[0] } elseif ($env:HERMES_RELAY_HERMES_ALIAS) { $env:HERMES_RELAY_HERMES_ALIAS } else { 'status' }
$action = $action.ToLowerInvariant()
$target = Join-Path $dir 'hermes-relay.exe'
$shim = Join-Path $dir 'hermes.cmd'

function Say($msg) { Write-Host "  $msg" }
function Die($msg) { Write-Host "hermes-alias.ps1: $msg" -ForegroundColor Red; exit 1 }

function Is-RelayShim {
  param([string]$Path)
  if (-not (Test-Path -LiteralPath $Path)) { return $false }
  $content = Get-Content -LiteralPath $Path -Raw -ErrorAction SilentlyContinue
  return $content -match 'hermes-relay\.exe'
}

function Show-Status {
  Say "install dir : $dir"
  Say "relay binary: $(if (Test-Path -LiteralPath $target) { $target } else { 'missing' })"
  if (Test-Path -LiteralPath $shim) {
    if (Is-RelayShim $shim) {
      Say "hermes alias: enabled ($shim -> hermes-relay.exe)"
    } else {
      Say "hermes alias: present but not managed by hermes-relay ($shim)"
    }
  } else {
    Say 'hermes alias: disabled'
  }
  $resolved = Get-Command hermes -ErrorAction SilentlyContinue
  if ($resolved) {
    Say "shell resolves: $($resolved.Source)"
  } else {
    Say 'shell resolves: not found in this process PATH'
  }
}

switch ($action) {
  { $_ -in @('status', 'check', 'show') } {
    Show-Status
  }
  { $_ -in @('enable', 'on', 'true', 'yes', '1') } {
    if (-not (Test-Path -LiteralPath $target)) {
      Die "cannot enable alias because $target does not exist; install the CLI first"
    }
    if ((Test-Path -LiteralPath $shim) -and -not (Is-RelayShim $shim)) {
      Die "refusing to overwrite existing non-hermes-relay shim at $shim"
    }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
$shimBody = @'
@echo off
"%~dp0hermes-relay.exe" %*
'@
    Set-Content -LiteralPath $shim -Value $shimBody -Encoding ASCII -Force
    Say "enabled hermes alias at $shim"
  }
  { $_ -in @('disable', 'off', 'false', 'no', '0', 'remove') } {
    if (Test-Path -LiteralPath $shim) {
      if (Is-RelayShim $shim) {
        Remove-Item -LiteralPath $shim -Force
        Say "disabled hermes alias by removing $shim"
      } else {
        Say "left existing non-hermes-relay shim untouched at $shim"
      }
    } else {
      Say 'hermes alias already disabled'
    }
  }
  default {
    Die "unknown action '$action' (use status, enable, or disable)"
  }
}
