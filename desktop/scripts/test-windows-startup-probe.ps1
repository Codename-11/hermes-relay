param(
    [string]$BundlePath = $PSScriptRoot,
    [ValidateRange(1, 10)][int]$Repeats = 3,
    [ValidateRange(2, 60)][int]$TimeoutSeconds = 10
)
$ErrorActionPreference = 'Stop'
$bundle = [IO.Path]::GetFullPath($BundlePath)
$manifest = Get-Content -LiteralPath (Join-Path $bundle 'manifest.json') -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1) { throw 'Unsupported probe manifest' }
# Verify every executable before starting any process. No downloads or installs.
foreach ($binary in $manifest.binaries) {
    if ($binary.file -notmatch '^[a-zA-Z0-9.-]+\.exe$') { throw 'Invalid executable filename' }
    if ($binary.kind -notin @('relay', 'runtime', 'minimal')) { throw 'Invalid probe kind' }
    $path = Join-Path $bundle $binary.file
    $stream = [IO.File]::OpenRead($path)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $actualHash = ([BitConverter]::ToString($hasher.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $hasher.Dispose() }
    if ($actualHash -ne $binary.sha256) {
        throw "Checksum mismatch: $($binary.file)"
    }
}
$scratch = Join-Path $bundle ('run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
$rows = @()
foreach ($binary in $manifest.binaries) {
    foreach ($ca in @('unset', '1')) {
        foreach ($argument in @('--version', '--help')) {
            for ($attempt = 1; $attempt -le $Repeats; $attempt++) {
                $profile = Join-Path $scratch ([Guid]::NewGuid().ToString('N'))
                New-Item -ItemType Directory -Path $profile | Out-Null
                $info = New-Object Diagnostics.ProcessStartInfo
                $info.FileName = Join-Path $bundle $binary.file
                $info.Arguments = $argument
                $info.WorkingDirectory = $profile
                $info.UseShellExecute = $false
                $info.CreateNoWindow = $true
                $info.RedirectStandardOutput = $true
                $info.RedirectStandardError = $true
                # Only this child sees the clean profile and startup environment.
                foreach ($name in @($info.EnvironmentVariables.Keys)) {
                    if ($name -match '^(HERMES|BUN|NODE)_') { $info.EnvironmentVariables.Remove($name) }
                }
                foreach ($name in @('HOME', 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'TEMP', 'TMP')) {
                    $info.EnvironmentVariables[$name] = $profile
                }
                if ($ca -eq '1') { $info.EnvironmentVariables['NODE_USE_SYSTEM_CA'] = '1' }
                $process = New-Object Diagnostics.Process
                $process.StartInfo = $info
                $timer = [Diagnostics.Stopwatch]::StartNew()
                $exitCode = $null
                $exitHex = $null
                $outText = ''
                $errText = ''
                $timedOut = $false
                try {
                    if (-not $process.Start()) { throw 'Process did not start' }
                    $stdout = $process.StandardOutput.ReadToEndAsync()
                    $stderr = $process.StandardError.ReadToEndAsync()
                    $timedOut = -not $process.WaitForExit($TimeoutSeconds * 1000)
                    if ($timedOut) {
                        # Stop only the process tree launched by this case, including CA relaunch.
                        & taskkill.exe /PID $process.Id /T /F 2>&1 | Out-Null
                        $process.WaitForExit()
                    }
                    $exitCode = $process.ExitCode
                    $exitHex = '0x{0:X8}' -f [BitConverter]::ToUInt32([BitConverter]::GetBytes([int]$exitCode), 0)
                    if ($stdout.Wait(2000)) { $outText = $stdout.Result }
                    if ($stderr.Wait(2000)) { $errText = $stderr.Result }
                } catch {
                    $errText = $_.Exception.Message
                } finally {
                    $timer.Stop()
                    $process.Dispose()
                }
                $expected = if ($binary.kind -eq 'minimal') { 'relay-startup-probe:' + $binary.version }
                    elseif ($argument -eq '--help') { 'Usage:' }
                    elseif ($binary.kind -eq 'runtime') { $binary.version }
                    else { 'hermes-relay 0.4.0-beta.8' }
                $passed = -not $timedOut -and $exitCode -eq 0 -and $outText.Contains($expected)
                # Do not collect dumps, event logs, environment, host/user names, or paths.
                # Output contents stay local; the report needs only lengths and expected markers.
                $rows += [pscustomobject]@{
                    file = $binary.file; sha256 = $binary.sha256; argument = $argument
                    nodeUseSystemCa = $ca; attempt = $attempt; passed = $passed
                    exitCode = $exitCode; exitHex = $exitHex; timedOut = $timedOut
                    elapsedMs = $timer.ElapsedMilliseconds; stdoutLength = $outText.Length
                    stderrLength = $errText.Length; expectedOutputPresent = $outText.Contains($expected)
                }
                Write-Host "$($binary.file) $argument CA=$ca attempt=$attempt exit=$exitHex pass=$passed"
            }
        }
    }
}
$os = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
$report = [ordered]@{
    schemaVersion = 1; sourceCommit = $manifest.sourceCommit; sourceTag = $manifest.sourceTag
    builderCommit = $manifest.builderCommit
    windowsBuild = "$($os.CurrentBuild).$($os.UBR)"; architecture = $env:PROCESSOR_ARCHITECTURE
    rows = $rows
}
$resultPath = Join-Path $scratch 'results.json'
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultPath -Encoding UTF8
Write-Host "Results: $resultPath"
Write-Host "Passed $(@($rows | Where-Object passed).Count)/$($rows.Count). Failures are evidence, not a fix verdict."
