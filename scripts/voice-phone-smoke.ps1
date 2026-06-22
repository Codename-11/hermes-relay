param(
    [string]$RemoteHost = "you@hermes-host",
    [string]$Package = "com.axiomlabs.hermesrelay.sideload",
    [string]$OutputRoot = "voice-lab-runs\phone-smoke",
    [int]$SinceMinutes = 15,
    [switch]$CaptureOnly,
    [switch]$NoClear,
    [switch]$NoLaunch,
    [switch]$Handoff,
    [int]$WaitForUnlockSeconds = 0,
    [int]$ManualSeconds = 0,
    [switch]$KeepAwake,
    [int]$WifiOffAtSeconds = 0,
    [int]$WifiOnAtSeconds = 0
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

function Test-DeviceLocked {
    param([string[]]$WindowDump)
    $text = $WindowDump -join "`n"
    return $text -match "mDreamingLockscreen=true|mCurrentFocus=Window\{[^}]*Bouncer|mCurrentFocus=Window\{[^}]*NotificationShade"
}

function Wait-ForDeviceUnlock {
    param(
        [string]$AdbPrefix,
        [int]$TimeoutSeconds
    )
    if ($TimeoutSeconds -le 0) {
        return $null
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $window = Invoke-Expression "$AdbPrefix shell dumpsys window" 2>&1
        if (-not (Test-DeviceLocked $window)) {
            return $window
        }
        Start-Sleep -Seconds 2
    }
    return Invoke-Expression "$AdbPrefix shell dumpsys window" 2>&1
}

function Wait-ManualWindow {
    param(
        [string]$AdbPrefix,
        [int]$Seconds,
        [int]$WifiOffAt,
        [int]$WifiOnAt
    )
    if ($Seconds -le 0) {
        return
    }
    $wifiOffDone = $false
    $wifiOnDone = $false
    for ($elapsed = 0; $elapsed -lt $Seconds; $elapsed++) {
        if (-not $wifiOffDone -and $WifiOffAt -gt 0 -and $elapsed -ge $WifiOffAt) {
            Write-Host "handoff: disabling Wi-Fi at ${elapsed}s"
            Invoke-Expression "$AdbPrefix shell svc wifi disable" | Out-Null
            $wifiOffDone = $true
        }
        if (-not $wifiOnDone -and $WifiOnAt -gt 0 -and $elapsed -ge $WifiOnAt) {
            Write-Host "handoff: enabling Wi-Fi at ${elapsed}s"
            Invoke-Expression "$AdbPrefix shell svc wifi enable" | Out-Null
            $wifiOnDone = $true
        }
        Start-Sleep -Seconds 1
    }
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path $repoRoot (Join-Path $OutputRoot $timestamp)
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$adb = Get-AdbPrefix
$effectiveKeepAwake = $KeepAwake -or $Handoff

Write-Host "Hermes voice phone smoke"
Write-Host "  package: $Package"
Write-Host "  remote:  $RemoteHost"
Write-Host "  output:  $runDir"
if ($WaitForUnlockSeconds -gt 0) {
    Write-Host "  unlock wait: ${WaitForUnlockSeconds}s"
}
if ($ManualSeconds -gt 0) {
    Write-Host "  manual window: ${ManualSeconds}s"
}
if ($effectiveKeepAwake) {
    Write-Host "  keep awake: enabled"
}
if ($WifiOffAtSeconds -gt 0) {
    Write-Host "  Wi-Fi off at: ${WifiOffAtSeconds}s"
}
if ($WifiOnAtSeconds -gt 0) {
    Write-Host "  Wi-Fi on at:  ${WifiOnAtSeconds}s"
}
Write-Host ""

$unlockWindow = Wait-ForDeviceUnlock $adb $WaitForUnlockSeconds
if ($unlockWindow -ne $null) {
    $unlockWindow | Out-File -LiteralPath (Join-Path $runDir "window-after-unlock-wait.txt") -Encoding utf8
}

if ($effectiveKeepAwake) {
    Invoke-Expression "$adb shell input keyevent KEYCODE_WAKEUP" | Out-Null
    Invoke-Expression "$adb shell svc power stayon true" | Out-Null
}

Invoke-Capture "$adb devices -l" (Join-Path $runDir "adb-devices.txt") | Out-Null
$windowBefore = Invoke-Capture "$adb shell dumpsys window" (Join-Path $runDir "window-before.txt")
Invoke-Capture "$adb shell dumpsys package $Package" (Join-Path $runDir "package.txt") | Out-Null
Invoke-Capture "$adb shell dumpsys connectivity | Select-String -Pattern 'Active default network|NetworkAgentInfo|LinkProperties|TRANSPORT_|validated|Capabilities' | Select-Object -First 260" (Join-Path $runDir "connectivity-before.txt") | Out-Null
Invoke-Capture "ssh $RemoteHost 'systemctl --user is-active hermes-relay.service && curl -fsS http://127.0.0.1:8767/health'" (Join-Path $runDir "relay-health-before.txt") | Out-Null

$locked = Test-DeviceLocked $windowBefore
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
    if ($Handoff) {
        Write-Host "  1. Unlock the device and open Hermes Relay sideload build."
        Write-Host "  2. In Voice Settings, select Realtime Agent and confirm the relay route is connected."
        Write-Host "  3. Start a voice turn: check the relay status, then tell me the current date from Hermes."
        if ($WifiOffAtSeconds -gt 0) {
            Write-Host "  4. Keep the voice turn active; this script will turn Wi-Fi off at ${WifiOffAtSeconds}s."
        } else {
            Write-Host "  4. While it is thinking or speaking, turn Wi-Fi off or switch LAN/Tailscale route."
        }
        Write-Host "  5. Confirm voice mode shows reconnecting, then resumes without starting a duplicate Hermes run."
        Write-Host "  6. Repeat once with Hermes Chat + Voice Output using a long spoken response."
    } else {
        Write-Host "  1. Unlock the device."
        Write-Host "  2. Open Hermes Relay sideload build."
        Write-Host "  3. Run tap-to-talk: check the relay status"
        Write-Host "  4. Interrupt while it is speaking to verify barge-in."
    }
    Write-Host ""
    if ($ManualSeconds -gt 0) {
        Write-Host "Capturing after ${ManualSeconds}s. Run the phone smoke now."
        Wait-ManualWindow $adb $ManualSeconds $WifiOffAtSeconds $WifiOnAtSeconds
    } else {
        Read-Host "Press Enter after the phone smoke attempt finishes"
    }
}

$adbPattern = "VoiceViewModel|RelayVoiceClient|EndpointResolver|VoiceHandoff|handoff|Trying voice route|Route changed|Waiting for route|Voice reconnected|Caught up|voice/output|voice.response|voice.audio|voice.session|voice.replay|session.resume|replayed|RealtimePcmPlayer|BargeIn|transcribe|synthesize|auth.ok|sendMessage|tool|ConnectionManager|endpoint fallback|probeAndReconnect|scheduleReconnect|tailscale|reachable"
$journalPattern = "Client connected|Client disconnected|voice.output|voice/output|voice/realtime|voice.session|voice.replay|session.resume|detached|resumed|resume_failed|voice/config|voice/transcribe|voice/synthesize|ERROR|Traceback"
$journalCommand = 'journalctl --user -u hermes-relay.service --since "' + $SinceMinutes + ' minutes ago" --no-pager | grep -E "' + $journalPattern + '" | tail -160'

Invoke-Capture "$adb logcat -d -v time" (Join-Path $runDir "adb-logcat-full.txt") | Out-Null
Invoke-Capture "$adb logcat -d -v time | Select-String -Pattern '$adbPattern' | Select-Object -Last 260" (Join-Path $runDir "adb-logcat-voice.txt") | Out-Null
Invoke-Capture "ssh $RemoteHost '$journalCommand'" (Join-Path $runDir "relay-journal-voice.txt") | Out-Null
Invoke-Capture "ssh $RemoteHost 'systemctl --user is-active hermes-relay.service && curl -fsS http://127.0.0.1:8767/health'" (Join-Path $runDir "relay-health-after.txt") | Out-Null
Invoke-Capture "$adb shell dumpsys connectivity | Select-String -Pattern 'Active default network|NetworkAgentInfo|LinkProperties|TRANSPORT_|validated|Capabilities' | Select-Object -First 260" (Join-Path $runDir "connectivity-after.txt") | Out-Null
Invoke-Capture "$adb shell dumpsys window" (Join-Path $runDir "window-after.txt") | Out-Null

Write-Host ""
Write-Host "Smoke artifacts written to:"
Write-Host "  $runDir"
Write-Host ""
Write-Host "Review:"
Write-Host "  adb-logcat-voice.txt"
Write-Host "  relay-journal-voice.txt"
Write-Host "  connectivity-before.txt / connectivity-after.txt"
