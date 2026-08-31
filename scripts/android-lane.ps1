# Hermes-Relay's machine-wide Windows lane for Gradle and Android device work.
# Every worktree uses the same named mutex. Keep this script dependency-free so
# it can run under the Windows PowerShell bundled with Windows.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$mutexName = "Global\HermesRelay.AndroidBuildLane.v1"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Show-Usage {
    Write-Host @"
Usage:
  .\scripts\android-lane.ps1 status
  .\scripts\android-lane.ps1 [--timeout-seconds <seconds>] gradle <gradle-args...>
  .\scripts\android-lane.ps1 [--timeout-seconds <seconds>] exec <command> <args...>

Examples:
  .\scripts\android-lane.ps1 gradle :app:testSideloadDebugUnitTest --tests "*ChatViewModelTest*"
  .\scripts\android-lane.ps1 exec python scripts/android-gateway-certify.py --help

The default wait is unbounded. Ctrl+C cancels a waiter without affecting the
current owner. Use exec for connected-test or APK-install workflows that must
serialize with wrapper-managed Gradle lanes. Keep Android Studio idle separately.
"@
}

function New-AndroidLaneMutex {
    try {
        return [System.Threading.Mutex]::new($false, $mutexName)
    }
    catch [System.UnauthorizedAccessException] {
        throw "The machine-wide Hermes-Relay Android lane exists but this Windows account cannot open it. Run all worktrees under the same account or correct the mutex permissions."
    }
}

function Test-AcquireMutex {
    param(
        [System.Threading.Mutex] $Mutex,
        [TimeSpan] $Wait
    )

    try {
        return $Mutex.WaitOne($Wait)
    }
    catch [System.Threading.AbandonedMutexException] {
        Write-Warning "The previous Android lane owner exited without releasing the mutex; ownership was recovered."
        return $true
    }
}

$tokens = @($args)
if ($tokens.Count -eq 0 -or $tokens[0] -in @("help", "--help", "-h")) {
    Show-Usage
    exit 0
}

$timeoutSeconds = 0
if ($tokens[0] -eq "--timeout-seconds") {
    if ($tokens.Count -lt 3) {
        throw "--timeout-seconds requires a non-negative integer and a mode."
    }
    $parsedTimeout = 0
    if (-not [int]::TryParse($tokens[1], [ref] $parsedTimeout) -or $parsedTimeout -lt 0) {
        throw "--timeout-seconds must be a non-negative integer."
    }
    $timeoutSeconds = $parsedTimeout
    $tokens = @($tokens[2..($tokens.Count - 1)])
}

$mode = $tokens[0]
$mutex = New-AndroidLaneMutex
$acquired = $false

try {
    if ($mode -eq "status") {
        $acquired = Test-AcquireMutex -Mutex $mutex -Wait ([TimeSpan]::Zero)
        if ($acquired) {
            Write-Host "Hermes-Relay Android lane: IDLE"
            $mutex.ReleaseMutex()
            $acquired = $false
            exit 0
        }
        Write-Host "Hermes-Relay Android lane: BUSY"
        exit 1
    }

    if ($mode -notin @("gradle", "exec")) {
        throw "Unknown mode '$mode'. Use status, gradle, or exec."
    }
    if ($mode -eq "exec" -and $tokens.Count -lt 2) {
        throw "exec requires a command."
    }

    $waitDescription = if ($timeoutSeconds -eq 0) { "without a timeout" } else { "for up to $timeoutSeconds seconds" }
    Write-Host "Waiting for the machine-wide Hermes-Relay Android lane $waitDescription..."

    $startedWaiting = [System.Diagnostics.Stopwatch]::StartNew()
    while (-not $acquired) {
        $sliceSeconds = 30
        if ($timeoutSeconds -gt 0) {
            $remaining = $timeoutSeconds - [int][Math]::Floor($startedWaiting.Elapsed.TotalSeconds)
            if ($remaining -le 0) {
                throw "Timed out waiting for the machine-wide Hermes-Relay Android lane after $timeoutSeconds seconds."
            }
            $sliceSeconds = [Math]::Min($sliceSeconds, $remaining)
        }
        $acquired = Test-AcquireMutex -Mutex $mutex -Wait ([TimeSpan]::FromSeconds($sliceSeconds))
        if (-not $acquired) {
            Write-Host "Still waiting for the Hermes-Relay Android lane ($([int]$startedWaiting.Elapsed.TotalSeconds)s elapsed)..."
        }
    }

    Write-Host "Acquired the machine-wide Hermes-Relay Android lane."
    Push-Location $repoRoot
    try {
        if ($mode -eq "gradle") {
            $executable = Join-Path $repoRoot "gradlew.bat"
            $commandArguments = if ($tokens.Count -gt 1) { @($tokens[1..($tokens.Count - 1)]) } else { @() }
        }
        else {
            $executable = $tokens[1]
            $commandArguments = if ($tokens.Count -gt 2) { @($tokens[2..($tokens.Count - 1)]) } else { @() }
        }

        & $executable @commandArguments
        $commandExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
    }
    finally {
        Pop-Location
    }

    exit $commandExitCode
}
finally {
    if ($acquired) {
        $mutex.ReleaseMutex()
        Write-Host "Released the machine-wide Hermes-Relay Android lane."
    }
    $mutex.Dispose()
}
