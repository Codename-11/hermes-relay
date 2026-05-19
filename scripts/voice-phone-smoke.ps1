param(
    [string]$RemoteHost = "bailey@172.16.24.250",
    [string]$Package = "com.axiomlabs.hermesrelay.sideload",
    [string]$OutputRoot = "voice-lab-runs\phone-smoke",
    [int]$SinceMinutes = 15,
    [switch]$CaptureOnly,
    [switch]$NoClear,
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Invoke-Capture {
    param(
        [string]$Command,
        [string]$Path
    )
    Write-Host "capture: $Path"
    $output = Invoke-Expression $Command 2>&1
    $output | Out-File -LiteralPath $Path -Encoding utf8
    return $output
}

function Get-AdbPrefix {
    $devices = adb devices -l
    $active = @($devices | Where-Object { $_ -match "\sdevice\s" })
    if ($active.Count -eq 0) {
        throw "No authorized adb device found."
    }
    if ($active.Count -gt 1) {
        $serial = ($active[0] -split "\s+")[0]
        return "adb -s $serial"
    }
    return "adb"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path $repoRoot (Join-Path $OutputRoot $timestamp)
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$adb = Get-AdbPrefix

Write-Host "Hermes voice phone smoke"
Write-Host "  package: $Package"
Write-Host "  remote:  $RemoteHost"
Write-Host "  output:  $runDir"
Write-Host ""

Invoke-Capture "$adb devices -l" (Join-Path $runDir "adb-devices.txt") | Out-Null
$windowBefore = Invoke-Capture "$adb shell dumpsys window" (Join-Path $runDir "window-before.txt")
Invoke-Capture "$adb shell dumpsys package $Package" (Join-Path $runDir "package.txt") | Out-Null
Invoke-Capture "ssh $RemoteHost 'systemctl --user is-active hermes-relay.service && curl -fsS http://127.0.0.1:8767/health'" (Join-Path $runDir "relay-health-before.txt") | Out-Null

$locked = ($windowBefore -join "`n") -match "mDreamingLockscreen=true|mCurrentFocus=Window\{[^}]*NotificationShade"
if ($locked) {
    Write-Warning "Device appears locked. Unlock it before running the manual voice turn."
}

if (-not $CaptureOnly) {
    if (-not $NoClear) {
        & $adb logcat -c
    }
    if (-not $NoLaunch) {
        & $adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
    }

    Write-Host ""
    Write-Host "Manual phone steps:"
    Write-Host "  1. Unlock the device."
    Write-Host "  2. Open Hermes Relay sideload build."
    Write-Host "  3. Run tap-to-talk: check the relay status"
    Write-Host "  4. Interrupt while it is speaking to verify barge-in."
    Write-Host ""
    Read-Host "Press Enter after the phone smoke attempt finishes"
}

$adbPattern = "VoiceViewModel|RelayVoiceClient|voice/output|voice.response|voice.audio|RealtimePcmPlayer|BargeIn|transcribe|synthesize|auth.ok|sendMessage|tool|ConnectionManager"
$journalPattern = "Client connected|Client disconnected|voice.output|voice/output|voice/realtime|voice/config|voice/transcribe|voice/synthesize|ERROR|Traceback"
$journalCommand = 'journalctl --user -u hermes-relay.service --since "' + $SinceMinutes + ' minutes ago" --no-pager | grep -E "' + $journalPattern + '" | tail -160'

Invoke-Capture "$adb logcat -d -v time" (Join-Path $runDir "adb-logcat-full.txt") | Out-Null
Invoke-Capture "$adb logcat -d -v time | Select-String -Pattern '$adbPattern' | Select-Object -Last 260" (Join-Path $runDir "adb-logcat-voice.txt") | Out-Null
Invoke-Capture "ssh $RemoteHost '$journalCommand'" (Join-Path $runDir "relay-journal-voice.txt") | Out-Null
Invoke-Capture "ssh $RemoteHost 'systemctl --user is-active hermes-relay.service && curl -fsS http://127.0.0.1:8767/health'" (Join-Path $runDir "relay-health-after.txt") | Out-Null
Invoke-Capture "$adb shell dumpsys window" (Join-Path $runDir "window-after.txt") | Out-Null

Write-Host ""
Write-Host "Smoke artifacts written to:"
Write-Host "  $runDir"
Write-Host ""
Write-Host "Review:"
Write-Host "  adb-logcat-voice.txt"
Write-Host "  relay-journal-voice.txt"
