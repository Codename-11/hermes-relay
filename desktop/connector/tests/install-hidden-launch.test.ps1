param([string]$InstallerPath,[string]$InstallerSource)
$ErrorActionPreference = 'Stop'
if (-not $InstallerSource) {
    if (-not $InstallerPath) { $InstallerPath = Join-Path $PSScriptRoot '..\install.ps1' }
    $InstallerSource = Get-Content -LiteralPath $InstallerPath -Raw -Encoding UTF8
}
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput($InstallerSource,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count) { throw 'Installer parsing failed' }
foreach ($name in @('Invoke-ConnectorHiddenCli','Get-ConnectorLauncherContent','Get-ConnectorDaemonState','Get-ConnectorProcessSnapshot')) {
    $node=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true)
    if (-not $node) { throw ('Missing installer function '+$name) }
    . ([ScriptBlock]::Create($node.Extent.Text))
}
function Assert-Case([bool]$condition,[string]$message) { if (-not $condition) { throw $message } }
Assert-Case ($InstallerSource.Contains('$info.UseShellExecute = $false') -and $InstallerSource.Contains('$info.CreateNoWindow = $true')) 'CLI must suppress its console at creation'
Assert-Case ($InstallerSource.Contains("'relay-connector-hidden.vbs','connector-control.ps1'")) 'Launcher must participate in backup and rollback'
Assert-Case ($InstallerSource.Contains('$expectedExecutable = $launcherExecutable')) 'Task management validation must require the GUI launcher'
Assert-Case ($InstallerSource.Contains('$action = New-ScheduledTaskAction -Execute $launcherExecutable')) 'Task must launch without a console'
$root=Join-Path ([IO.Path]::GetTempPath()) ('Relay '+[char]0x6D4B+[char]0x8BD5+' '+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
try {
    # Probe the real current process without creating a diagnostic subprocess.
    $snapshot=Get-ConnectorProcessSnapshot $PID
    Assert-Case ($snapshot.State -eq 'alive' -and $snapshot.ExecutablePath) 'Current process must be identifiable'
    # Fixture only the OS process observation, retaining real JSON/file handling.
    $script:SnapshotFixture=@{State='alive';ExecutablePath='C:\Relay Test\hermes-relay.exe'}
    function Get-ConnectorProcessSnapshot { param([int]$ProcessId) return $script:SnapshotFixture }
    $statusPath=Join-Path $root 'daemon-status.json'
    $expectedExecutable='C:\Relay Test\hermes-relay.exe'
    Assert-Case ((Get-ConnectorDaemonState $statusPath $expectedExecutable) -eq 'missing') 'Missing initial status permits first start'
    [IO.File]::WriteAllText($statusPath,'{"pid":1234,"state":"connected"}')
    Assert-Case ((Get-ConnectorDaemonState $statusPath $expectedExecutable) -eq 'alive') 'Exact live image must stay running'
    $script:SnapshotFixture=@{State='gone'}
    Assert-Case ((Get-ConnectorDaemonState $statusPath $expectedExecutable) -eq 'gone') 'Confirmed exited PID permits recovery'
    $script:SnapshotFixture=@{State='alive';ExecutablePath='C:\Other\hermes-relay.exe'}
    Assert-Case ((Get-ConnectorDaemonState $statusPath $expectedExecutable) -eq 'recycled') 'Same image name at another path is a recycled PID'
    $script:SnapshotFixture=@{State='unknown'}
    Assert-Case ((Get-ConnectorDaemonState $statusPath $expectedExecutable) -eq 'unknown') 'Unknown process state must not restart'
    $script:SnapshotFixture=@{State='alive';ExecutablePath=$null}
    Assert-Case ((Get-ConnectorDaemonState $statusPath $expectedExecutable) -eq 'unknown') 'Unavailable image must not restart'
    [IO.File]::WriteAllText($statusPath,'not JSON')
    Assert-Case ((Get-ConnectorDaemonState $statusPath $expectedExecutable) -eq 'unknown') 'Corrupt status must not restart'
    Assert-Case ((Get-ConnectorDaemonState $root $expectedExecutable) -eq 'unknown') 'Unreadable status location must not restart'
    $powershell=Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $wscript=Join-Path $env:WINDIR 'System32\wscript.exe'
    $script=Join-Path $root 'child script.ps1'
    # Exceed both pipe buffers. A sequential stream reader would deadlock.
    $source="[Console]::OutputEncoding=New-Object Text.UTF8Encoding(`$false); [Console]::Error.Write(('e'*131072)); [Console]::Out.Write(('o'*131072)); exit 7"
    [IO.File]::WriteAllText($script,$source,(New-Object Text.UTF8Encoding($true)))
    $result=Invoke-ConnectorHiddenCli $powershell ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+$script+'"') 15000
    Assert-Case ($result.ExitCode -eq 7) 'Nonzero exit must be preserved'
    Assert-Case ($result.StandardOutput.Length -eq 131072 -and $result.StandardOutput -match '^o+$') 'Both output pipes must drain without deadlock'
    $marker=Join-Path $root 'done.txt'
    $source="Start-Sleep -Milliseconds 600; [IO.File]::WriteAllText('"+$marker.Replace("'","''")+"','done'); exit 7"
    [IO.File]::WriteAllText($script,$source,(New-Object Text.UTF8Encoding($true)))
    $launcher=Join-Path $root 'hidden launcher.vbs'
    [IO.File]::WriteAllText($launcher,(Get-ConnectorLauncherContent $powershell $script),[Text.Encoding]::Unicode)
    $watch=[Diagnostics.Stopwatch]::StartNew()
    $result=Invoke-ConnectorHiddenCli $wscript ('//B //Nologo "'+$launcher+'"') 15000
    $watch.Stop()
    Assert-Case ($result.ExitCode -eq 7) 'Launcher must propagate script exit code'
    Assert-Case ($watch.ElapsedMilliseconds -ge 600 -and (Get-Content -LiteralPath $marker -Raw) -eq 'done') 'Launcher must wait and support Unicode/space paths'
    $source='Start-Sleep -Seconds 30'
    [IO.File]::WriteAllText($script,$source,(New-Object Text.UTF8Encoding($true)))
    $failed=$false;$watch.Restart()
    try { $null=Invoke-ConnectorHiddenCli $powershell ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+$script+'"') 300 }
    catch { $failed=$true }
    $watch.Stop()
    Assert-Case ($failed -and $watch.ElapsedMilliseconds -lt 8000) 'A hung command must stop within its bounded timeout'
    Write-Output 'PASS: hidden process creation, dual-stream drain, exit status, waiting launcher, Unicode paths, timeout, alive/gone/recycled/unknown watchdog state'
} finally { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
