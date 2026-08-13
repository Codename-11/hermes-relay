[CmdletBinding()]
param(
  [switch]$Run,
  [string]$CuaDriver = "$env:USERPROFILE\.cua-driver\packages\current\cua-driver.exe"
)

$ErrorActionPreference = 'Stop'

if (-not $Run) {
  Write-Host 'Hermes CUA live acceptance is opt-in because it opens and controls two Calculator windows.'
  Write-Host 'Re-run with: powershell -ExecutionPolicy Bypass -File scripts/test-cua-windows.ps1 -Run'
  exit 0
}

if (-not (Test-Path -LiteralPath $CuaDriver -PathType Leaf)) {
  throw "Canonical CUA Driver was not found at $CuaDriver"
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class HermesCuaAcceptanceNative {
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X; public int Y; }
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT point);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
'@

function Invoke-Cua([string]$Tool, [hashtable]$Payload) {
  $json = $Payload | ConvertTo-Json -Depth 12 -Compress
  $raw = $json | & $CuaDriver call $Tool
  if ($LASTEXITCODE -ne 0) { throw "CUA $Tool failed with exit code $LASTEXITCODE" }
  $result = $raw | ConvertFrom-Json
  if ($result.isError -eq $true) { throw "CUA $Tool rejected the request: $raw" }
  return $result
}

function Get-DesktopSentinel {
  $point = New-Object HermesCuaAcceptanceNative+POINT
  [void][HermesCuaAcceptanceNative]::GetCursorPos([ref]$point)
  [pscustomobject]@{
    CursorX = $point.X
    CursorY = $point.Y
    Foreground = [HermesCuaAcceptanceNative]::GetForegroundWindow().ToInt64()
  }
}

function Assert-Unchanged([object]$Before, [string]$Step) {
  $after = Get-DesktopSentinel
  if ($after.CursorX -ne $Before.CursorX -or $after.CursorY -ne $Before.CursorY) {
    throw "$Step moved the physical cursor from ($($Before.CursorX),$($Before.CursorY)) to ($($after.CursorX),$($after.CursorY))"
  }
  if ($after.Foreground -ne $Before.Foreground) {
    throw "$Step changed the foreground HWND from $($Before.Foreground) to $($after.Foreground)"
  }
}

function Get-Window([object]$Launch) {
  $window = @($Launch.windows | Where-Object { $_.is_on_screen -ne $false }) | Select-Object -First 1
  if (-not $window) { throw "CUA launch did not return a usable window: $($Launch | ConvertTo-Json -Depth 8 -Compress)" }
  return $window
}

$sessionA = "hermes-live-a-$([guid]::NewGuid().ToString('N'))"
$sessionB = "hermes-live-b-$([guid]::NewGuid().ToString('N'))"
$opened = @()
try {
  $health = Invoke-Cua health_report @{}
  if ($health.overall -ne 'healthy') { throw "CUA health must be healthy, got $($health.overall)" }

  [void](Invoke-Cua start_session @{ session = $sessionA; capture_scope = 'window' })
  [void](Invoke-Cua start_session @{ session = $sessionB; capture_scope = 'window' })

  $sentinel = Get-DesktopSentinel
  $launchA = Invoke-Cua launch_app @{ aumid = 'Microsoft.WindowsCalculator_8wekyb3d8bbwe!App'; creates_new_application_instance = $true; session = $sessionA }
  $launchB = Invoke-Cua launch_app @{ aumid = 'Microsoft.WindowsCalculator_8wekyb3d8bbwe!App'; creates_new_application_instance = $true; session = $sessionB }
  $opened = @($launchA.pid, $launchB.pid)
  Start-Sleep -Milliseconds 800
  Assert-Unchanged $sentinel 'background launch'

  $windowA = Get-Window $launchA
  $windowB = Get-Window $launchB
  if ($windowA.window_id -eq $windowB.window_id) { throw 'isolated sessions resolved to the same Calculator window' }

  $beforeA = Invoke-Cua get_window_state @{ pid = $launchA.pid; window_id = $windowA.window_id; session = $sessionA; query = 'Seven'; include_screenshot = $false }
  $seven = @($beforeA.elements | Where-Object { $_.label -eq 'Seven' -or $_.name -eq 'Seven' }) | Select-Object -First 1
  if (-not $seven.element_token) { throw 'Calculator Seven element did not expose an opaque token' }

  [void](Invoke-Cua click @{ pid = $launchA.pid; window_id = $windowA.window_id; element_token = $seven.element_token; session = $sessionA; scope = 'window'; delivery_mode = 'background' })
  Assert-Unchanged $sentinel 'background element action'
  $afterA = Invoke-Cua get_window_state @{ pid = $launchA.pid; window_id = $windowA.window_id; session = $sessionA; query = 'Display'; include_screenshot = $false }
  if ($afterA.snapshot_id -eq $beforeA.snapshot_id) { throw 'post-action snapshot did not advance its generation' }

  $staleRaw = (@{ pid = $launchA.pid; window_id = $windowA.window_id; element_token = $seven.element_token; session = $sessionA; scope = 'window'; delivery_mode = 'background' } | ConvertTo-Json -Compress) | & $CuaDriver call click
  $stale = $staleRaw | ConvertFrom-Json
  if ($stale.isError -ne $true) { throw 'stale element token was accepted after a newer snapshot' }

  $cursorA = Invoke-Cua get_agent_cursor_state @{ session = $sessionA }
  $cursorB = Invoke-Cua get_agent_cursor_state @{ session = $sessionB }
  if (($cursorA | ConvertTo-Json -Depth 8 -Compress) -eq ($cursorB | ConvertTo-Json -Depth 8 -Compress)) {
    throw 'two control sessions did not expose isolated cursor state'
  }

  [void](Invoke-Cua end_session @{ session = $sessionA })
  $endedRaw = (@{ session = $sessionA } | ConvertTo-Json -Compress) | & $CuaDriver call get_session_state
  $ended = $endedRaw | ConvertFrom-Json
  if ($ended.code -ne 'session_not_started') { throw 'ended session remained active' }

  Write-Host 'PASS: physical cursor and foreground stayed unchanged.'
  Write-Host 'PASS: background Calculator action was bracketed by snapshots.'
  Write-Host 'PASS: stale token rejection and two isolated cursor sessions were verified.'
  Write-Host 'PASS: ending a session immediately revoked its CUA state.'
} finally {
  foreach ($session in @($sessionA, $sessionB)) {
    try { [void](Invoke-Cua end_session @{ session = $session }) } catch { Write-Warning $_ }
  }
  Write-Host "Calculator processes created by this acceptance run: $($opened -join ', '). Close them manually after inspection."
}
