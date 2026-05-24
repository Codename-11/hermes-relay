<#
.SYNOPSIS
  Automated on-device regression gate for realtime PCM playback start-up latency.

.DESCRIPTION
  Drives the sideload build's Developer > "Realtime voice lab" via UI automation
  (no human, no mic): launches the app, navigates to the lab, taps "Text demo",
  and asserts from logcat that the AudioTrack hardware cursor starts promptly —
  i.e. time-to-first-audio is below -MaxFirstAudioMs and the cursor actually
  advances. This catches the "deep-buffer cold-start parking" regression class
  (silent first/short turns) that no JVM unit test can simulate, because
  Robolectric's ShadowAudioTrack does not model playbackHeadPosition.

  Requires: a paired+connected sideload build on the connected device (the lab
  reuses the live relay session) and a configured realtime provider.

.EXAMPLE
  pwsh scripts/realtime-voice-lab-smoke.ps1
  pwsh scripts/realtime-voice-lab-smoke.ps1 -MaxFirstAudioMs 600
#>
param(
    [string]$Package = "com.axiomlabs.hermesrelay.sideload",
    # The lab's time-to-first-audio is write-sampled (the production path uses a
    # 75ms timer watchdog and reads much lower). Healthy starts land ~700-900ms
    # here; the deep-buffer cold-start regression parks the cursor for 2.3s+.
    # 1200ms cleanly separates the two with margin on both sides.
    [int]$MaxFirstAudioMs = 1200,
    [int]$TurnTimeoutSeconds = 25
)

$ErrorActionPreference = "Stop"

function Get-AdbPrefix {
    $devices = adb devices -l
    $active = @($devices | Where-Object { $_ -match "\sdevice\s" })
    if ($active.Count -eq 0) { throw "No authorized adb device found." }
    if ($active.Count -gt 1) {
        $serial = ($active[0] -split "\s+")[0]
        return "adb -s $serial"
    }
    return "adb"
}

$adb = Get-AdbPrefix
$uiPath = Join-Path $env:TEMP "hermes-lab-ui.xml"

function Get-Ui {
    Invoke-Expression "$adb shell uiautomator dump /sdcard/hermes-ui.xml" | Out-Null
    Invoke-Expression "$adb pull /sdcard/hermes-ui.xml `"$uiPath`"" | Out-Null
    return Get-Content -Raw -LiteralPath $uiPath
}

# Find the on-screen centre (x,y) of the first node whose text or content-desc
# equals $Key. Returns $null if not present.
function Find-NodeCenter {
    param([string]$Xml, [string]$Key)
    $escaped = [Regex]::Escape($Key)
    foreach ($node in ($Xml -split "<node")) {
        if ($node -match "(text|content-desc)=`"$escaped`"") {
            if ($node -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
                $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
                $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
                return @([int]$x, [int]$y)
            }
        }
    }
    return $null
}

function Tap-Node {
    param([string]$Key, [switch]$Optional)
    $xml = Get-Ui
    $center = Find-NodeCenter $xml $Key
    if ($null -eq $center) {
        if ($Optional) { return $false }
        throw "UI node not found: '$Key' (screen changed or not paired/connected?)"
    }
    Write-Host "tap '$Key' @ ($($center[0]),$($center[1]))"
    Invoke-Expression "$adb shell input tap $($center[0]) $($center[1])" | Out-Null
    Start-Sleep -Milliseconds 900
    return $true
}

Write-Host "Realtime voice lab smoke"
Write-Host "  package: $Package   maxFirstAudio: ${MaxFirstAudioMs}ms"
Write-Host ""

# 1. Launch
Invoke-Expression "$adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1" | Out-Null
Start-Sleep -Seconds 2

# 2. Navigate: Settings tab -> scroll -> Developer options -> Realtime voice lab
Tap-Node "Settings" | Out-Null
Invoke-Expression "$adb shell input swipe 540 1800 540 600 300" | Out-Null
Start-Sleep -Milliseconds 600
Tap-Node "Developer options" | Out-Null
# The row's action is the chevron, exposed as content-desc.
Tap-Node "Open realtime voice lab" | Out-Null

# 3. Confirm we're on the lab, then run a text-only turn.
$xml = Get-Ui
if (-not ($xml -match 'Realtime Voice Lab')) {
    throw "Did not reach Realtime Voice Lab screen."
}
& $adb logcat -c
if (-not (Tap-Node "Text demo")) { throw "Text demo button not found." }
Write-Host "Text demo started; waiting up to ${TurnTimeoutSeconds}s for playback..."

# 4. Poll logcat for the time-to-first-audio metric.
$deadline = (Get-Date).AddSeconds($TurnTimeoutSeconds)
$ttfa = $null
$cursorParked = $false
while ((Get-Date) -lt $deadline) {
    $log = Invoke-Expression "$adb logcat -d -v time RealtimePcmPlayer:* *:S"
    $line = $log | Select-String -Pattern "time-to-first-audio=(\d+)ms" | Select-Object -Last 1
    if ($line -and $line -match "time-to-first-audio=(\d+)ms") {
        $ttfa = [int]$Matches[1]
        break
    }
    if ($log | Select-String -Pattern "hardware cursor not advancing") {
        $cursorParked = $true
    }
    Start-Sleep -Milliseconds 500
}

Write-Host ""
if ($null -eq $ttfa) {
    Write-Host "FAIL: no audio playback detected within ${TurnTimeoutSeconds}s." -ForegroundColor Red
    Write-Host "      (cursor parked seen: $cursorParked) — check provider config / pairing." -ForegroundColor Red
    exit 1
}

Write-Host "time-to-first-audio = ${ttfa}ms (threshold ${MaxFirstAudioMs}ms)"
if ($ttfa -le $MaxFirstAudioMs) {
    Write-Host "PASS: realtime playback started promptly." -ForegroundColor Green
    exit 0
} else {
    Write-Host "FAIL: playback start too slow — possible deep-buffer cold-start regression." -ForegroundColor Red
    exit 1
}
