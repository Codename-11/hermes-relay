param(
    [string]$InstallerPath,
    [string]$InstallerSource
)
$ErrorActionPreference = 'Stop'
if (-not $InstallerSource) {
    if (-not $InstallerPath) { $InstallerPath = Join-Path $PSScriptRoot '..\install.ps1' }
    $InstallerSource = Get-Content -LiteralPath $InstallerPath -Raw -Encoding UTF8
}
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput($InstallerSource,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count) { throw 'Installer parsing failed' }
foreach ($name in @('Get-ConnectorBinaryProcesses','Stop-RemainingConnectorProcesses','Replace-ConnectorBinary','Get-ConnectorTaskManagementSddl')) {
    $node=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true)
    if (-not $node) { throw ('Missing installer function '+$name) }
    . ([ScriptBlock]::Create($node.Extent.Text))
}
$cli='C:\Connector-Test\.hermes\bin\hermes-relay.exe'
$backup='C:\Connector-Test\.hermes\backup'
function Assert-Case([bool]$condition,[string]$message) { if (-not $condition) { throw $message } }
function Reset-Fixture {
    $script:Rows=@()
    $script:Processes=@{}
    $script:Events=New-Object 'Collections.Generic.List[string]'
    $script:Killed=New-Object 'Collections.Generic.List[int]'
    $script:QueryFails=$false
    $script:DenyKill=$false
    $script:WaitSucceeds=$true
    $script:FirstRows=$null
}
function New-FixtureProcess([int]$id,[string]$path) {
    $p=[pscustomobject]@{Id=$id;Path=$path;Handle=99;HasExited=$false}
    $p|Add-Member ScriptMethod Kill {
        if ($script:DenyKill) { throw 'access denied' }
        $script:Killed.Add($this.Id)
        $script:Events.Add('kill')
        $this.HasExited=$true
        $script:Rows=@($script:Rows|Where-Object {$_.ProcessId -ne $this.Id})
    }
    $p|Add-Member ScriptMethod WaitForExit { param($timeout) return $script:WaitSucceeds }
    $p|Add-Member ScriptMethod Dispose {}
    $script:Processes[$id]=$p
    $script:Rows+= [pscustomobject]@{ProcessId=$id;ExecutablePath=$path}
}
function Get-CimInstance {
    [CmdletBinding()]param([string]$ClassName,[string]$Filter)
    if ($script:QueryFails) { throw 'query failed' }
    if ($null -ne $script:FirstRows) { $rows=$script:FirstRows;$script:FirstRows=$null;return $rows }
    return $script:Rows
}
function Get-Process {
    [CmdletBinding()]param([int]$Id)
    return $script:Processes[$Id]
}
function Set-Content {
    [CmdletBinding()]param([Parameter(ValueFromPipeline=$true)]$Value,[string]$LiteralPath,[string]$Encoding)
    process { $script:Events.Add('snapshot') }
}
Reset-Fixture
New-FixtureProcess 110 $cli
New-FixtureProcess 111 'C:\Other-Installation\.hermes\bin\hermes-relay.exe'
New-FixtureProcess 112 'C:\Isolated-Profile\.hermes\bin\hermes-relay.exe'
Stop-RemainingConnectorProcesses
Assert-Case ($script:Killed.Count -eq 1 -and $script:Killed[0] -eq 110) 'A different installation was selected'
Assert-Case ($script:Events[0] -eq 'snapshot' -and $script:Events[1] -eq 'kill') 'Snapshot must precede closure'

Reset-Fixture
New-FixtureProcess 110 'C:\Unrelated\powershell.exe'
$script:FirstRows=@([pscustomobject]@{ProcessId=110;ExecutablePath=$cli})
Stop-RemainingConnectorProcesses
Assert-Case ($script:Killed.Count -eq 0) 'A PID reused by another image must not be stopped'

Reset-Fixture
$script:QueryFails=$true
$failed=$false
try { Stop-RemainingConnectorProcesses } catch { $failed=$true }
Assert-Case ($failed -and $script:Killed.Count -eq 0) 'Unknown process state must abort replacement'

Reset-Fixture
New-FixtureProcess 110 $cli
$script:DenyKill=$true
$failed=$false
try { Stop-RemainingConnectorProcesses } catch { $failed=$true }
Assert-Case ($failed) 'A refused stop must abort replacement'

Reset-Fixture
New-FixtureProcess 110 $cli
$script:WaitSucceeds=$false
$failed=$false
try { Stop-RemainingConnectorProcesses } catch { $failed=$true }
Assert-Case ($failed) 'An unconfirmed exit must abort replacement'

# A real bounded filesystem probe verifies replacement of an existing target
# on Windows without loading a CLI or touching an installation directory.
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('connector-replace-test-'+[Guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($temporary)|Out-Null
    $target=Join-Path $temporary 'before.txt'
    $candidate=$target+'.new'
    [IO.File]::WriteAllText($target,'before')
    [IO.File]::WriteAllText($candidate,'after')
    $cli=$target;$hadCli=$true;$manifest=@{sha256=(Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash}
    Replace-ConnectorBinary
    # Consumed-source confirmation is safe and idempotent for exact bytes.
    Replace-ConnectorBinary
    Assert-Case ([IO.File]::ReadAllText($target) -ceq 'after') 'Existing-target replacement failed'
} finally {
    # Windows scanners may briefly retain File.Replace's own ~RF temp file.
    $deadline=(Get-Date).AddSeconds(5)
    while ([IO.Directory]::Exists($temporary)) {
        try { [IO.Directory]::Delete($temporary,$true) }
        catch { if ((Get-Date) -ge $deadline) { throw }; Start-Sleep -Milliseconds 200 }
    }
}
$sid=New-Object Security.Principal.SecurityIdentifier('S-1-5-21-1-2-3-1001')
$before='D:(A;;FA;;;SY)(A;;FA;;;BA)(A;;FR;;;S-1-5-21-1-2-3-1001)'
$after=Get-ConnectorTaskManagementSddl $before $sid
$descriptor=New-Object Security.AccessControl.RawSecurityDescriptor($after)
$system=@($descriptor.DiscretionaryAcl|Where-Object {$_.SecurityIdentifier.Value -eq 'S-1-5-18'})
$administrators=@($descriptor.DiscretionaryAcl|Where-Object {$_.SecurityIdentifier.Value -eq 'S-1-5-32-544'})
Assert-Case ($system.Count -eq 1 -and $administrators.Count -eq 1) 'Task ACL must retain SYSTEM and Administrators'
Assert-Case ((Get-ConnectorTaskManagementSddl $after $sid) -ceq $after) 'Task management grant must be idempotent'
@{passed=8;installer_parse_errors=0;scope='mocked process closure, task ACL and temporary-file replacement'}|ConvertTo-Json -Compress
